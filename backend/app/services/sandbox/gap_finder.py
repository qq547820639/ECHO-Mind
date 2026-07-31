"""沙箱缺口识别：基于 audit_day 汇总结果标记感知覆盖缺口。

只读，不写库；返回缺口列表供 tool_forge 消费。
"""
from __future__ import annotations

from datetime import date as date_cls
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import DailyNarrative, DerivedFeature

# 端侧派生特征期望覆盖的 source 集合
EXPECTED_SOURCES: tuple[str, ...] = (
    "accel",
    "gyro",
    "screen",
    "notification",
    "app_activity",
)

# 判定"持续低落"所需连续天数（含当天）
PERSISTENT_LOW_MIN_DAYS = 2


def find_gaps(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    run_date: date_cls,
    audit_summary: dict[str, Any],
) -> list[dict[str, Any]]:
    """基于 audit_day 返回的 summary 识别感知覆盖缺口。

    返回 ``[{gap_id, description, severity, suggested_tool_type}]``，缺口 ID 稳定，
    便于幂等去重。识别规则：
      - feature_count==0 → "无感知数据"（high）
      - 某个 source 缺失 → "缺少{source}信号"（medium）
      - narrative_mood_hint=="偏低" 且连续多天 → "持续低落状态"（high）
      - profile_traits.observation_days < 3 → "观测数据不足"（low）
    """
    gaps: list[dict[str, Any]] = []
    feature_count: int = int(audit_summary.get("feature_count", 0) or 0)

    # 1. 完全无感知数据
    if feature_count == 0:
        gaps.append({
            "gap_id": "no_data",
            "description": "无感知数据",
            "severity": "high",
            "suggested_tool_type": "data_check",
        })

    # 2. 逐 source 覆盖检查（当日 DerivedFeature 中缺失的 source）
    day_features = _day_features(db, tenant_id, user_id, run_date)
    present_sources = {f.source for f in day_features}
    for source in EXPECTED_SOURCES:
        if source not in present_sources:
            gaps.append({
                "gap_id": f"source_missing_{source}",
                "description": f"缺少{source}信号",
                "severity": "medium",
                "suggested_tool_type": "signal_probe",
            })

    # 3. 持续低落状态：当天 mood_hint=="偏低" 且最近 N 天连续偏低
    if audit_summary.get("narrative_mood_hint") == "偏低":
        recent = db.scalars(
            select(DailyNarrative).where(
                DailyNarrative.tenant_id == tenant_id,
                DailyNarrative.user_id == user_id,
            ).order_by(DailyNarrative.date.desc()).limit(PERSISTENT_LOW_MIN_DAYS)
        ).all()
        if len(recent) >= PERSISTENT_LOW_MIN_DAYS and all(
            n.mood_hint == "偏低" for n in recent
        ):
            gaps.append({
                "gap_id": "persistent_low",
                "description": "持续低落状态",
                "severity": "high",
                "suggested_tool_type": "mood_check",
            })

    # 4. 观测数据不足
    traits: dict[str, Any] = dict(audit_summary.get("profile_traits") or {})
    observation_days = int(traits.get("observation_days", 0) or 0)
    if observation_days < 3:
        gaps.append({
            "gap_id": "observation_insufficient",
            "description": "观测数据不足",
            "severity": "low",
            "suggested_tool_type": "observation_wait",
        })

    return gaps


def _day_features(db: Session, tenant_id: str, user_id: str, run_date: date_cls) -> list[DerivedFeature]:
    """读取当日 DerivedFeature（与 audit_day 同口径）。"""
    features = db.scalars(
        select(DerivedFeature).where(
            DerivedFeature.tenant_id == tenant_id,
            DerivedFeature.user_id == user_id,
        )
    ).all()
    return [f for f in features if f.window_start.date() == run_date]
