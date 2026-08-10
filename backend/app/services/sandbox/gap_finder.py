"""沙箱缺口识别：基于 audit_day 汇总结果标记感知覆盖缺口。

只读，不写库；返回缺口列表供 tool_forge 消费。

v0.6 final sources_present 契约：
- 覆盖度判定以「当日全部 DerivedFeature.sources_present 并集」为权威；
- 某行 sources_present 为空/None（旧客户端或历史数据）时 fallback 到该行 source；
- EXPECTED_SOURCES 保持 5 项（accel/gyro/screen/notification/app_activity），
  mic_opt 可选（缺失不产生 gap）、health 不强制（不擅自 mandatory）；
- gap_id 稳定幂等：no_data / source_missing_{source} / observation_insufficient
  跨日重复运行产生相同 id 的 gap（runner 侧 gaps_found 覆写、Skill 归纳按
  content_hash 幂等，无新表）。
"""
from __future__ import annotations

from datetime import UTC, datetime, time, timedelta
from datetime import date as date_cls
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import DerivedFeature

# 端侧派生特征期望覆盖的 source 集合（mic_opt 可选、health 不强制）
EXPECTED_SOURCES: tuple[str, ...] = (
    "accel",
    "gyro",
    "screen",
    "notification",
    "app_activity",
)


def _row_sources(row: DerivedFeature) -> set[str]:
    """窗口内实际信号源集合：sources_present 非空时取并集，否则 fallback 到单数 source。

    与 audit_day 的本地 helper 保持同口径（避免跨模块耦合过深）。
    """
    if getattr(row, "sources_present", None):
        return set(row.sources_present)
    return {row.source}


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
    便于幂等去重。识别规则（PRD 契约点 1/2：被动数据不做情绪/危机推断）：
      - feature_count==0 → "无感知数据"（high）
      - 某个 source 缺失 → "缺少{source}信号"（medium）
      - profile_traits.observation_days < 3 → "观测数据不足"（low）
    覆盖度判定：当日全部窗口 sources_present 并集（空则回退单数 source）。
    （"持续低落状态"情绪推断规则已按 PRD 契约点 2 移除。）
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

    # 2. 逐 source 覆盖检查（当日全部窗口 sources_present 并集为权威；空则回退单数 source）
    day_features = _day_features(db, tenant_id, user_id, run_date)
    present_sources = (
        set().union(*[_row_sources(f) for f in day_features]) if day_features else set()
    )
    for source in EXPECTED_SOURCES:
        if source not in present_sources:
            gaps.append({
                "gap_id": f"source_missing_{source}",
                "description": f"缺少{source}信号",
                "severity": "medium",
                "suggested_tool_type": "signal_probe",
            })

    # 3. 观测数据不足
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
    """读取当日 DerivedFeature（与 audit_day 同口径，时间范围查询）。"""
    start = datetime.combine(run_date, time.min, tzinfo=UTC)
    end = start + timedelta(days=1)
    features = db.scalars(
        select(DerivedFeature).where(
            DerivedFeature.tenant_id == tenant_id,
            DerivedFeature.user_id == user_id,
            DerivedFeature.window_start >= start,
            DerivedFeature.window_start < end,
        )
    ).all()
    return list(features)
