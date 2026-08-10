"""机构去标识群体画像聚合服务。

按 tenant_id 聚合本租户所有用户的画像/叙事/风险数据。
去标识保护（v0.6）：
- cohort 总人数 < 5 → 敏感维度（observation_stats）suppressed
  （返回 suppression 标记，不输出可间接识别单人的 min/max 统计）
- PRD 契约点 2：情绪语义已废弃，mood_distribution 恒为空并标记 suppressed
不返回单个用户 ID/特征，仅返回聚合统计。
"""
from __future__ import annotations

from datetime import UTC, datetime, timedelta
from statistics import median
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models import DerivedFeature, Escalation, Skill, UserProfile

SMALL_BUCKET_THRESHOLD = 5


def _cohort_suppressed(value: bool) -> str:
    return "suppressed" if value else "ok"


def build_tenant_portrait(db: Session, tenant_id: str) -> dict[str, Any]:
    """聚合本租户去标识群体画像。

    返回字段：
    - mood_distribution：恒为空（情绪语义已废弃，PRD 契约点 2）
    - observation_stats：observation_days 统计（cohort<5 时隐藏 min/max 等）
    - active_users_7d：近 7 天活跃用户数（DerivedFeature.window_start 去重 user_id）
    - escalation_metrics：近 7 天 escalation 计数（total/open/closed/level_l3/level_l2）
    - skill_count：Skill 下发数（status in reviewed/signed/retired，按状态分组）
    - suppression：敏感维度抑制标记 {mood_distribution: "suppressed", observation_stats: "ok"|"suppressed"}
    """
    # 1. observation_days 统计（从 UserProfile.traits）
    # PRD 契约点 2：情绪语义（recent_mood_hint/mood_hint）已废弃，被动数据不做情绪推断，
    # mood_distribution 恒为空并在 suppression 标记 suppressed。
    profiles = db.scalars(
        select(UserProfile).where(UserProfile.tenant_id == tenant_id)
    ).all()
    cohort_total = len(profiles)

    observation_days_list: list[int] = []
    for profile in profiles:
        traits = profile.traits or {}
        obs_days = traits.get("observation_days")
        if isinstance(obs_days, bool):
            continue
        if isinstance(obs_days, (int, float)):
            observation_days_list.append(int(obs_days))

    suppression: dict[str, str] = {}
    suppression["mood_distribution"] = "suppressed"  # 无情绪语义数据源
    mood_distribution: dict[str, int] = {}

    if cohort_total < SMALL_BUCKET_THRESHOLD:
        # cohort 总人数 <5：整个敏感维度 suppressed（不输出可间接识别单人的统计）
        observation_stats: dict[str, float] = {}
        suppression["observation_stats"] = _cohort_suppressed(True)
    else:
        if observation_days_list:
            observation_stats = {
                "min": float(min(observation_days_list)),
                "max": float(max(observation_days_list)),
                "avg": float(sum(observation_days_list) / len(observation_days_list)),
                "median": float(median(observation_days_list)),
            }
        else:
            observation_stats = {"min": 0.0, "max": 0.0, "avg": 0.0, "median": 0.0}
        suppression["observation_stats"] = _cohort_suppressed(False)

    # 2. 近 7 天活跃用户数（DerivedFeature.window_start >= now - 7d 去重 user_id）
    seven_days_ago = datetime.now(UTC) - timedelta(days=7)
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
        "suppression": suppression,
    }
