"""画像路由（v0.7, Milestone E）：当日画像只读视图 + 显式重建。

- GET 绝不写库（不生成/不 rebuild）：已生成则返回完整画像，否则只读计算轻量状态视图；
- POST /portraits/rebuild 为显式重建写路径（写审计，仅写路径调用）。
"""
from __future__ import annotations

from datetime import UTC, datetime, timedelta
from datetime import date as date_cls

from fastapi import APIRouter, Query
from sqlalchemy import func, select
from zoneinfo import ZoneInfo

from app.models import DailyBehaviorAggregate, DailyPortrait, PersonalBaseline, User
from app.schemas import BaselineStatusOut, PortraitListOut, PortraitOut, PortraitRebuildIn
from app.services.audit import append_audit
from app.services.baseline.calculator import baseline_state
from app.services.baseline.confidence import confidence_for
from app.services.baseline.day_type import bucket_for_date
from app.services.portrait.engine import generate_portrait

from app.api.deps import DB, PRINCIPAL, ensure_user

router = APIRouter(prefix="/v1")


def _local_today(tz_name: str) -> date_cls:
    return datetime.now(UTC).astimezone(ZoneInfo(tz_name)).date()


def _portrait_out(row: DailyPortrait) -> dict:
    return {
        "date": str(row.local_date),
        "status": row.status,
        "confidence": row.confidence,
        "baseline_days": row.baseline_valid_days,
        "baseline_version": row.baseline_version,
        "headline": list(row.highlights or []),
        "summary": row.summary or "",
        "dimensions": row.dimensions or {},
        "coverage": row.coverage or {},
        "facts": row.facts or [],
        "timezone_used": row.timezone,
    }


def _lightweight_status(db, *, tenant_id: str, user_id: str, tz_name: str, today: date_cls) -> dict:
    """只读计算轻量状态视图（绝不写库）。

    返回 dict（BaselineStatusOut 形状）供 GET /portraits/today 与 GET /baseline/status 使用。
    """
    agg = db.scalar(select(DailyBehaviorAggregate).where(
        DailyBehaviorAggregate.tenant_id == tenant_id,
        DailyBehaviorAggregate.user_id == user_id,
        DailyBehaviorAggregate.local_date == today,
    ))
    today_coverage = agg.coverage_score if agg else 0.0
    bucket = bucket_for_date(today)
    baseline_row = db.scalar(select(PersonalBaseline).where(
        PersonalBaseline.tenant_id == tenant_id,
        PersonalBaseline.user_id == user_id,
        PersonalBaseline.bucket == bucket,
    ).order_by(PersonalBaseline.updated_at.desc()))
    if baseline_row is None:
        baseline_row = db.scalar(select(PersonalBaseline).where(
            PersonalBaseline.tenant_id == tenant_id,
            PersonalBaseline.user_id == user_id,
            PersonalBaseline.bucket == "all_days",
        ).order_by(PersonalBaseline.updated_at.desc()))
    if baseline_row is not None:
        valid_days = baseline_row.valid_days
        version = baseline_row.baseline_version
        window_start = baseline_row.window_start
        window_end = baseline_row.window_end
        bucket_usage = baseline_row.bucket
    else:
        start = today - timedelta(days=28)
        end = today - timedelta(days=1)
        valid_days = db.scalar(select(func.count()).select_from(DailyBehaviorAggregate).where(
            DailyBehaviorAggregate.tenant_id == tenant_id,
            DailyBehaviorAggregate.user_id == user_id,
            DailyBehaviorAggregate.local_date >= start,
            DailyBehaviorAggregate.local_date <= end,
            DailyBehaviorAggregate.coverage_score >= 0.25,
        )) or 0
        version = None
        window_start = window_end = None
        bucket_usage = bucket
    state = baseline_state(valid_days)
    if state == "BASELINE_READY":
        confidence = confidence_for(today_coverage, valid_days, list(agg.missing_sources or []) if agg else [])
    else:
        confidence = "LOW"
    return {
        "status": state,
        "confidence": confidence,
        "baseline_days": valid_days,
        "baseline_version": version,
        "window_start": window_start,
        "window_end": window_end,
        "bucket_usage": bucket_usage,
        "today_coverage": today_coverage,
    }


@router.get("/portraits/today")
def get_today_portrait(user_id: str, db: DB, principal: PRINCIPAL):
    """只读：今日画像。已生成返回完整；否则返回轻量状态视图（不写库）。"""
    ensure_user(db, principal, user_id)
    user = db.get(User, user_id)
    tz_name = user.timezone or "Asia/Shanghai"
    today = _local_today(tz_name)
    row = db.scalar(select(DailyPortrait).where(
        DailyPortrait.tenant_id == principal.tenant_id,
        DailyPortrait.user_id == user_id,
        DailyPortrait.local_date == today,
    ))
    if row is not None:
        return PortraitOut(**_portrait_out(row))
    status = _lightweight_status(db, tenant_id=principal.tenant_id, user_id=user_id,
                                 tz_name=tz_name, today=today)
    return PortraitOut(
        date=today,
        status=status["status"],
        confidence=status["confidence"],
        baseline_days=status["baseline_days"],
        baseline_version=status["baseline_version"],
        coverage={"coverage_score": status["today_coverage"]},
        timezone_used=tz_name,
    )


@router.get("/portraits")
def list_portraits(
    user_id: str,
    db: DB,
    principal: PRINCIPAL,
    days: int = Query(default=7, ge=1, le=90),
) -> PortraitListOut:
    """只读：最近 N 天画像列表（按 local_date 升序，含 coverage）。"""
    ensure_user(db, principal, user_id)
    user = db.get(User, user_id)
    tz_name = user.timezone or "Asia/Shanghai"
    today = _local_today(tz_name)
    start = today - timedelta(days=days - 1)
    rows = db.scalars(select(DailyPortrait).where(
        DailyPortrait.tenant_id == principal.tenant_id,
        DailyPortrait.user_id == user_id,
        DailyPortrait.local_date >= start,
        DailyPortrait.local_date <= today,
    ).order_by(DailyPortrait.local_date.asc())).all()
    return PortraitListOut(
        user_id=user_id,
        days=days,
        portraits=[PortraitOut(**_portrait_out(r)) for r in rows],
    )


@router.get("/baseline/status")
def baseline_status(user_id: str, db: DB, principal: PRINCIPAL) -> BaselineStatusOut:
    """只读：基线状态（绝不 rebuild / 写库）。"""
    ensure_user(db, principal, user_id)
    user = db.get(User, user_id)
    tz_name = user.timezone or "Asia/Shanghai"
    today = _local_today(tz_name)
    status = _lightweight_status(db, tenant_id=principal.tenant_id, user_id=user_id,
                                 tz_name=tz_name, today=today)
    return BaselineStatusOut(
        status=status["status"],
        baseline_days=status["baseline_days"],
        baseline_version=status["baseline_version"],
        window_start=status["window_start"],
        window_end=status["window_end"],
        bucket_usage=status["bucket_usage"],
        today_coverage=status["today_coverage"],
    )


@router.post("/portraits/rebuild")
def rebuild_portrait(payload: PortraitRebuildIn, db: DB, principal: PRINCIPAL):
    """显式重建当日画像（写路径：upsert aggregate + baseline + portrait，写审计）。"""
    ensure_user(db, principal, payload.user_id)
    user = db.get(User, payload.user_id)
    tz_name = user.timezone or "Asia/Shanghai"
    local_date = payload.local_date or _local_today(tz_name)
    row = generate_portrait(db, tenant_id=principal.tenant_id, user_id=payload.user_id,
                            local_date=local_date)
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="portrait.rebuild", object_type="daily_portrait", object_id=row.id,
                 metadata={"local_date": str(local_date)})
    db.commit()
    return PortraitOut(**_portrait_out(row))
