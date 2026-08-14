"""被动感知画像服务：派生特征入库、每日叙事、画像聚合。

原则：后端只处理端侧派生特征（摘要/向量），不接触原始传感数据。
审计写入由路由层负责，本模块不写审计。

v0.6 契约：
- ``get_profile`` 只读缓存，绝不 rebuild / version+1 / commit；
- ``rebuild_profile`` 显式重建（traits + version+1 + rebuilt_at），仅由 POST /rebuild 调用；
- ``build_daily_narrative`` 使用时间范围查询（window_start >= date00:00 AND < date+1），
  禁止全量拉取后 Python 过滤；
- PRD 契约点 2：取消"行为遥测 → 情绪"的错误语义映射。叙事/画像不生成任何情绪标签
  （mood_hint / recent_mood_hint 不再写入；DailyNarrative.mood_hint 列保留置空兼容历史）。
"""
from __future__ import annotations

from datetime import date as date_cls, datetime, time, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import DailyNarrative, DerivedFeature, UserProfile, utcnow
from app.schemas import DerivedFeatureIn

#: 画像聚合窗口：近 N 天叙事 + 派生特征
PROFILE_WINDOW_DAYS = 7


def ingest_feature(
    db: Session, *, tenant_id: str, user_id: str, feature: DerivedFeatureIn
) -> tuple[DerivedFeature, bool]:
    """幂等入库派生特征。返回 (row, idempotent_replay)。"""
    existing = db.scalar(select(DerivedFeature).where(
        DerivedFeature.tenant_id == tenant_id,
        DerivedFeature.event_id == feature.event_id,
    ))
    if existing:
        return existing, True
    row = DerivedFeature(
        tenant_id=tenant_id,
        user_id=user_id,
        event_id=feature.event_id,
        schema_version=feature.schema_version,
        source=feature.source,
        window_start=feature.window_start,
        window_end=feature.window_end,
        summary=feature.summary,
        vector=feature.vector,
        sources_present=list(getattr(feature, "sources_present", []) or []),
    )
    db.add(row)
    db.flush()
    return row, False


def build_daily_narrative(
    db: Session, *, tenant_id: str, user_id: str, date: date_cls
) -> DailyNarrative:
    """按 tenant+user+date 幂等生成/更新每日叙事（写路径：ingest/显式重建时调用）。

    使用时间范围查询：``window_start >= date 00:00 AND window_start < date+1 00:00``，
    禁止全量拉取后 Python 过滤。事件不含任何情绪标签（PRD 契约点 2）。
    """
    start = datetime.combine(date, time.min, tzinfo=timezone.utc)
    end = start + timedelta(days=1)
    features = db.scalars(
        select(DerivedFeature).where(
            DerivedFeature.tenant_id == tenant_id,
            DerivedFeature.user_id == user_id,
            DerivedFeature.window_start >= start,
            DerivedFeature.window_start < end,
        ).order_by(DerivedFeature.window_start)
    ).all()

    events = [{
        "source": f.source,
        "summary": f.summary,
        # v0.6 final：事实性覆盖信息（窗口内实际信号源），非情绪语义
        "sources_present": list(f.sources_present or []),
    } for f in features]
    # legacy 预留：缺口真值由 SandboxRun.gaps_found 承担（v0.8 removal target）
    gaps: list[str] = []

    existing = db.scalar(select(DailyNarrative).where(
        DailyNarrative.tenant_id == tenant_id,
        DailyNarrative.user_id == user_id,
        DailyNarrative.date == date,
    ))
    now = utcnow()
    if existing:
        existing.events = events
        existing.mood_hint = None  # 情绪语义字段废弃：新写入恒为 None（兼容历史列）
        existing.gaps = gaps
        existing.last_rebuilt_at = now
        db.flush()
        return existing
    row = DailyNarrative(
        tenant_id=tenant_id,
        user_id=user_id,
        date=date,
        events=events,
        mood_hint=None,
        gaps=gaps,
        last_rebuilt_at=now,
    )
    db.add(row)
    db.flush()
    return row


def get_profile(db: Session, *, tenant_id: str, user_id: str) -> UserProfile | None:
    """只读：查询缓存 UserProfile，不存在返回 None（路由层转 404）。

    绝不 rebuild / version+1 / commit；无任何写副作用。
    """
    return db.scalar(select(UserProfile).where(
        UserProfile.tenant_id == tenant_id,
        UserProfile.user_id == user_id,
    ))


def rebuild_profile(db: Session, *, tenant_id: str, user_id: str) -> UserProfile:
    """显式重建画像：基于近 7 天叙事 + 派生特征聚合 traits，version+1。

    仅由 POST /v1/profile/{user_id}/rebuild 调用（写路径）。
    派生特征查询使用时间范围（window_start >= now-7d），禁止全量拉取。
    traits 不再包含 recent_mood_hint（PRD 契约点 2：非诊断表达）。
    """
    since_date = datetime.now(timezone.utc).date() - timedelta(days=PROFILE_WINDOW_DAYS)
    narratives = db.scalars(select(DailyNarrative).where(
        DailyNarrative.tenant_id == tenant_id,
        DailyNarrative.user_id == user_id,
        DailyNarrative.date >= since_date,
    ).order_by(DailyNarrative.date.desc())).all()
    since_dt = datetime.now(timezone.utc) - timedelta(days=PROFILE_WINDOW_DAYS)
    features = db.scalars(select(DerivedFeature).where(
        DerivedFeature.tenant_id == tenant_id,
        DerivedFeature.user_id == user_id,
        DerivedFeature.window_start >= since_dt,
    )).all()
    observation_days = len({f.window_start.date() for f in features})
    # v0.6 final：近 7 天观察窗口内实际信号源并集（与 gap_finder 同口径：sources_present
    # 非空取并集，空列表 fallback 到单数 source；供机构工作台观测覆盖度）
    sources_union = (
        set().union(*[set(f.sources_present or []) or {f.source} for f in features])
        if features
        else set()
    )
    traits = {
        "observation_days": observation_days,
        "narrative_days_last_7": len(narratives),
        "sources_present_union": sorted(sources_union),
    }

    existing = db.scalar(select(UserProfile).where(
        UserProfile.tenant_id == tenant_id,
        UserProfile.user_id == user_id,
    ))
    now = utcnow()
    if existing:
        existing.traits = traits
        existing.version = existing.version + 1
        existing.updated_at = now
        existing.rebuilt_at = now
        db.flush()
        return existing
    row = UserProfile(tenant_id=tenant_id, user_id=user_id, traits=traits, version=1,
                      updated_at=now, rebuilt_at=now)
    db.add(row)
    db.flush()
    return row
