"""画像生成引擎（Milestone D）：写路径编排。

generate_portrait 流程：
1. 查 User.timezone（画像记录 timezone_used）；
2. upsert 当日聚合（从 DerivedFeature 确定性重算）；
3. build baseline（幂等 upsert personal_baselines）；
4. 按状态机生成 daily_portrait（幂等 upsert）并返回。

状态机：
- WARMING_UP（基线有效日 0-2）：固定文案，无维度比较；
- EARLY_BASELINE（3-6）：当天事实句，不输出"比平常"；
- BASELINE_READY（>=7）：confidence_for → LOW → LOW_CONFIDENCE 固定文案；
  否则 READY；today_coverage < 0.4 时 status=PARTIAL_DATA（narrative 加前缀）。

Phase 5（C3）：可复现性 —— baseline_snapshot_digest = SHA-256(基线 metrics
规范化 JSON 序列化)；相同输入必然产生相同摘要。
"""
from __future__ import annotations

import hashlib
import json
from datetime import date
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import DailyBehaviorAggregate, DailyPortrait, User, utcnow

from app.services.aggregates.calculator import upsert_daily_aggregate
from app.services.baseline.calculator import baseline_state, build_baseline
from app.services.baseline.confidence import confidence_for
from app.services.portrait.dimensions import compute_dimensions
from app.services.portrait.explain import build_facts
from app.services.portrait.narrative import (
    LOW_CONFIDENCE_TEXT,
    PARTIAL_DATA_PREFIX,
    WARMING_UP_TEXT,
    build_narrative,
    fact_sentences,
)

PORTRAIT_SCHEMA_VERSION = "portrait-v1"
PARTIAL_COVERAGE_THRESHOLD = 0.4


def baseline_digest(metrics: dict) -> str:
    """基线 metrics 的规范化序列化 SHA-256 摘要（确定性可复现）。"""
    canonical = json.dumps(
        metrics,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        default=str,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _agg_to_dict(agg: DailyBehaviorAggregate | None) -> dict:
    if agg is None:
        return {
            "coverage_score": 0.0,
            "valid_window_count": 0,
            "expected_window_count": 288,
            "movement_index": None,
            "movement_variability": None,
            "screen_on_minutes": 0.0,
            "screen_open_count": 0,
            "late_screen_minutes": 0.0,
            "app_switch_count": 0,
            "notification_count": 0,
            "active_start_minute": None,
            "active_end_minute": None,
            "active_hour_spread": None,
            "sources_present": [],
            "missing_sources": [],
        }
    return {
        "coverage_score": agg.coverage_score,
        "valid_window_count": agg.valid_window_count,
        "expected_window_count": agg.expected_window_count,
        "movement_index": agg.movement_index,
        "movement_variability": agg.movement_variability,
        "screen_on_minutes": agg.screen_on_minutes,
        "screen_open_count": agg.screen_open_count,
        "late_screen_minutes": agg.late_screen_minutes,
        "app_switch_count": agg.app_switch_count,
        "notification_count": agg.notification_count,
        "active_start_minute": agg.active_start_minute,
        "active_end_minute": agg.active_end_minute,
        "active_hour_spread": agg.active_hour_spread,
        "sources_present": list(agg.sources_present or []),
        "missing_sources": list(agg.missing_sources or []),
    }


def _coverage_dict(today: dict) -> dict:
    return {
        "coverage_score": today["coverage_score"],
        "valid_window_count": today["valid_window_count"],
        "expected_window_count": today["expected_window_count"],
        "sources_present": today["sources_present"],
        "missing_sources": today["missing_sources"],
    }


def _upsert_portrait(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    local_date: date,
    tz_name: str,
    status: str,
    confidence: str,
    coverage: dict,
    dimensions: dict,
    highlights: list[str],
    summary: str,
    facts: list[dict],
    baseline_start: date | None,
    baseline_end: date | None,
    baseline_valid_days: int,
    baseline_version: str | None,
    baseline_snapshot_digest: str | None,
) -> DailyPortrait:
    existing = db.scalar(select(DailyPortrait).where(
        DailyPortrait.tenant_id == tenant_id,
        DailyPortrait.user_id == user_id,
        DailyPortrait.local_date == local_date,
    ))
    now = utcnow()
    if existing:
        existing.timezone = tz_name
        existing.status = status
        existing.confidence = confidence
        existing.baseline_start = baseline_start
        existing.baseline_end = baseline_end
        existing.baseline_valid_days = baseline_valid_days
        existing.baseline_version = baseline_version
        existing.baseline_snapshot_digest = baseline_snapshot_digest
        existing.coverage = coverage
        existing.dimensions = dimensions
        existing.highlights = highlights
        existing.summary = summary
        existing.facts = facts
        existing.finalized_at = now
        db.flush()
        return existing
    row = DailyPortrait(
        tenant_id=tenant_id,
        user_id=user_id,
        local_date=local_date,
        timezone=tz_name,
        status=status,
        confidence=confidence,
        baseline_start=baseline_start,
        baseline_end=baseline_end,
        baseline_valid_days=baseline_valid_days,
        baseline_version=baseline_version,
        baseline_snapshot_digest=baseline_snapshot_digest,
        coverage=coverage,
        dimensions=dimensions,
        highlights=highlights,
        summary=summary,
        facts=facts,
        schema_version=PORTRAIT_SCHEMA_VERSION,
        generated_at=now,
        finalized_at=now,
    )
    db.add(row)
    db.flush()
    return row


def generate_portrait(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    local_date: date,
) -> DailyPortrait:
    """生成/重建当日画像（写路径：upsert aggregate + baseline + portrait）。"""
    user = db.get(User, user_id)
    if user is None:
        raise ValueError("user not found")
    tz_name = user.timezone or "Asia/Shanghai"

    upsert_daily_aggregate(db, tenant_id=tenant_id, user_id=user_id,
                           local_date=local_date, tz_name=tz_name)
    agg = db.scalar(select(DailyBehaviorAggregate).where(
        DailyBehaviorAggregate.tenant_id == tenant_id,
        DailyBehaviorAggregate.user_id == user_id,
        DailyBehaviorAggregate.local_date == local_date,
    ))
    today = _agg_to_dict(agg)
    coverage = _coverage_dict(today)

    snapshot = build_baseline(db, tenant_id=tenant_id, user_id=user_id, today_local=local_date)
    state = baseline_state(snapshot.valid_days)
    # 异构公共参数（str/date/int/dict 混合），显式 Any 以便 **common 展开时
    # 各关键字参数与 _upsert_portrait 签名逐项匹配（各具体字段类型见函数签名）。
    common: dict[str, Any] = dict(
        tenant_id=tenant_id,
        user_id=user_id,
        local_date=local_date,
        tz_name=tz_name,
        coverage=coverage,
        baseline_start=snapshot.window_start,
        baseline_end=snapshot.window_end,
        baseline_valid_days=snapshot.valid_days,
        baseline_version=snapshot.version,
        baseline_snapshot_digest=baseline_digest(snapshot.metrics),
    )

    # 产品指标（数据质量/可靠性）：画像生成分类计数（不记录任何特征内容）
    from app.services.telemetry import count_event

    if state == "WARMING_UP":
        count_event("portrait_warming_up")
        return _upsert_portrait(
            db, status="WARMING_UP", confidence="LOW",
            summary=WARMING_UP_TEXT, dimensions={}, highlights=[], facts=[], **common,
        )

    if state == "EARLY_BASELINE":
        count_event("portrait_early_baseline")
        return _upsert_portrait(
            db, status="EARLY_BASELINE", confidence="LOW",
            summary=fact_sentences(today), dimensions={}, highlights=[], facts=[], **common,
        )

    # BASELINE_READY
    confidence = confidence_for(today["coverage_score"], snapshot.valid_days, today["missing_sources"])
    if confidence == "LOW":
        count_event("portrait_low_confidence")
        return _upsert_portrait(
            db, status="LOW_CONFIDENCE", confidence="LOW",
            summary=LOW_CONFIDENCE_TEXT, dimensions={}, highlights=[], facts=[], **common,
        )

    status = "PARTIAL_DATA" if today["coverage_score"] < PARTIAL_COVERAGE_THRESHOLD else "READY"
    count_event("portrait_partial_data" if status == "PARTIAL_DATA" else "portrait_ready")
    dimensions = compute_dimensions(today, snapshot.metrics)
    summary, highlights = build_narrative(dimensions)
    facts = build_facts(today, snapshot.metrics)
    if status == "PARTIAL_DATA" and summary:
        summary = PARTIAL_DATA_PREFIX + summary
    return _upsert_portrait(
        db, status=status, confidence=confidence,
        summary=summary, dimensions=dimensions, highlights=highlights, facts=facts, **common,
    )
