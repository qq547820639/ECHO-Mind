"""沙箱当日审计：汇总当日 DerivedFeature / DailyNarrative / UserProfile。

只读，不写库；不接触原始传感数据，仅消费端侧派生特征与画像聚合。
"""
from __future__ import annotations

from datetime import UTC, datetime, time, timedelta
from datetime import date as date_cls
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import DerivedFeature, UserProfile


def _row_sources(row: DerivedFeature) -> set[str]:
    """窗口内实际信号源集合：sources_present 非空时取并集，否则 fallback 到单数 source。

    与 gap_finder 的 `_row_sources` 保持同口径（本地重复实现，避免跨模块耦合过深）。
    """
    if getattr(row, "sources_present", None):
        return set(row.sources_present)
    return {row.source}


def audit_day(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    run_date: date_cls,
) -> dict[str, Any]:
    """读取当日 DerivedFeature + DailyNarrative + UserProfile，返回汇总 dict。

    v0.6：使用时间范围查询（window_start >= 当日 00:00 AND < 次日 00:00），
    禁止全量拉取后 Python 过滤。
    PRD 契约点 2：不输出情绪推断（narrative_mood_hint 恒为 None）。

    返回字段：
    - feature_count：当日派生特征条数
    - sources_present：当日全部窗口 sources_present 并集（sorted；供审计/观测下游消费）
    - narrative_mood_hint：恒为 None（情绪语义废弃）
    - profile_traits：当前用户画像 traits（无则为空 dict）
    - gaps：感知覆盖缺口（当前为空 list，T09 由 gap_finder 填充）
    """
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
    day_features = list(features)

    profile = db.scalar(
        select(UserProfile).where(
            UserProfile.tenant_id == tenant_id,
            UserProfile.user_id == user_id,
        )
    )

    # gaps 预留：T09 的 gap_finder 会基于 source 覆盖度填充
    gaps: list[str] = []
    sources_union = (
        set().union(*[_row_sources(f) for f in day_features]) if day_features else set()
    )

    return {
        "feature_count": len(day_features),
        "sources_present": sorted(sources_union),
        "narrative_mood_hint": None,
        "profile_traits": dict(profile.traits) if profile else {},
        "gaps": gaps,
    }
