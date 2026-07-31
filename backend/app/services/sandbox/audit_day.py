"""沙箱当日审计：汇总当日 DerivedFeature / DailyNarrative / UserProfile。

只读，不写库；不接触原始传感数据，仅消费端侧派生特征与画像聚合。
"""
from __future__ import annotations

from datetime import date as date_cls
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import DailyNarrative, DerivedFeature, UserProfile


def audit_day(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    run_date: date_cls,
) -> dict[str, Any]:
    """读取当日 DerivedFeature + DailyNarrative + UserProfile，返回汇总 dict。

    返回字段：
    - feature_count：当日派生特征条数
    - narrative_mood_hint：当日叙事情绪提示（无则为 None）
    - profile_traits：当前用户画像 traits（无则为空 dict）
    - gaps：感知覆盖缺口（当前为空 list，T09 由 gap_finder 填充）
    """
    features = db.scalars(
        select(DerivedFeature).where(
            DerivedFeature.tenant_id == tenant_id,
            DerivedFeature.user_id == user_id,
        )
    ).all()
    day_features = [f for f in features if f.window_start.date() == run_date]

    narrative = db.scalar(
        select(DailyNarrative).where(
            DailyNarrative.tenant_id == tenant_id,
            DailyNarrative.user_id == user_id,
            DailyNarrative.date == run_date,
        )
    )

    profile = db.scalar(
        select(UserProfile).where(
            UserProfile.tenant_id == tenant_id,
            UserProfile.user_id == user_id,
        )
    )

    # gaps 预留：T09 的 gap_finder 会基于 source 覆盖度填充
    gaps: list[str] = []

    return {
        "feature_count": len(day_features),
        "narrative_mood_hint": narrative.mood_hint if narrative else None,
        "profile_traits": dict(profile.traits) if profile else {},
        "gaps": gaps,
    }
