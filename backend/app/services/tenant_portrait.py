"""机构去标识群体画像聚合服务。

按 tenant_id 聚合本租户所有用户的画像/叙事/风险数据。
去标识保护：任何聚合桶计数 < 5 时合并到 "other" 桶，防重标识。
不返回单个用户 ID/特征，仅返回聚合统计。
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from statistics import median
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models import DerivedFeature, Escalation, Skill, UserProfile

SMALL_BUCKET_THRESHOLD = 5


def _suppress_small_buckets(counts: dict[str, int], threshold: int = SMALL_BUCKET_THRESHOLD) -> dict[str, int]:
    """任何计数 < threshold 的桶合并到 "other" 桶（累加计数）。

    防御性：即使 "other" 桶最终计数 < threshold 也保留输出（机构聚合兜底）。
    """
    result: dict[str, int] = {}
    other_total = 0
    for key, value in counts.items():
        if value < threshold:
            other_total += value
        else:
            result[key] = value
    if other_total > 0:
        result["other"] = result.get("other", 0) + other_total
    return result


def build_tenant_portrait(db: Session, tenant_id: str) -> dict[str, Any]:
    """聚合本租户去标识群体画像。

    返回字段：
    - mood_distribution：mood_hint 分布（从 UserProfile.traits.recent_mood_hint 聚合，小桶合并后）
    - observation_stats：observation_days 统计（min/max/avg/median）
    - active_users_7d：近 7 天活跃用户数（DerivedFeature.window_start 去重 user_id）
    - escalation_metrics：近 7 天 escalation 计数（total/open/closed/level_l3/level_l2）
    - skill_count：Skill 下发数（status in reviewed/signed/retired，按状态分组）
    """
    # 1. mood_hint 分布 + observation_days 统计（从 UserProfile.traits）
    profiles = db.scalars(
        select(UserProfile).where(UserProfile.tenant_id == tenant_id)
    ).all()

    mood_counts: dict[str, int] = {}
    observation_days_list: list[int] = []
    for profile in profiles:
        traits = profile.traits or {}
        mood_hint = traits.get("recent_mood_hint")
        if mood_hint:
            mood_counts[str(mood_hint)] = mood_counts.get(str(mood_hint), 0) + 1
        obs_days = traits.get("observation_days")
        if isinstance(obs_days, bool):
            continue
        if isinstance(obs_days, (int, float)):
            observation_days_list.append(int(obs_days))

    mood_distribution = _suppress_small_buckets(mood_counts)

    if observation_days_list:
        observation_stats = {
            "min": float(min(observation_days_list)),
            "max": float(max(observation_days_list)),
            "avg": float(sum(observation_days_list) / len(observation_days_list)),
            "median": float(median(observation_days_list)),
        }
    else:
        observation_stats = {"min": 0.0, "max": 0.0, "avg": 0.0, "median": 0.0}

    # 2. 近 7 天活跃用户数（DerivedFeature.window_start >= now - 7d 去重 user_id）
    seven_days_ago = datetime.now(timezone.utc) - timedelta(days=7)
    active_users_7d = db.scalar(
        select(func.count(func.distinct(DerivedFeature.user_id))).where(
            DerivedFeature.tenant_id == tenant_id,
            DerivedFeature.window_start >= seven_days_ago,
        )
    ) or 0

    # 3. escalation 计数（近 7 天，按 opened_at 过滤）
    escalations = db.scalars(
        select(Escalation).where(
            Escalation.tenant_id == tenant_id,
            Escalation.opened_at >= seven_days_ago,
        )
    ).all()
    escalation_metrics = {
        "total": len(escalations),
        "open": sum(1 for e in escalations if e.status in {"open", "acknowledged", "taken_over"}),
        "closed": sum(1 for e in escalations if e.status in {"closed", "reviewed"}),
        "level_l3": sum(1 for e in escalations if e.level == "L3"),
        "level_l2": sum(1 for e in escalations if e.level == "L2"),
    }

    # 4. Skill 下发数（status in reviewed/signed/retired，draft 不计入）
    skills = db.scalars(
        select(Skill).where(
            Skill.tenant_id == tenant_id,
            Skill.status.in_(("reviewed", "signed", "retired")),
        )
    ).all()
    skill_count = {
        "reviewed": sum(1 for s in skills if s.status == "reviewed"),
        "signed": sum(1 for s in skills if s.status == "signed"),
        "retired": sum(1 for s in skills if s.status == "retired"),
    }

    return {
        "mood_distribution": mood_distribution,
        "observation_stats": observation_stats,
        "active_users_7d": int(active_users_7d),
        "escalation_metrics": escalation_metrics,
        "skill_count": skill_count,
    }
