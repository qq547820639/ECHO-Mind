"""Skill 下发脱敏器。

下发前移除敏感引用，只保留能力描述：
- 移除：其他用户数据引用、原始 DerivedFeature 引用、原始特征 summary 引用、SandboxRun 内部 ID
- 保留：name / trigger_conditions / guardrails / steps / status 等能力描述字段

脱敏后的 dict 与 SkillOut 结构类似但不含敏感字段，供 GET /v1/skills 下发使用。
"""
from __future__ import annotations

from typing import Any

from app.models import Skill

# trigger_conditions.field 中出现的"原始特征 summary 引用"前缀，需过滤
_RAW_FEATURE_FIELD_PREFIXES: tuple[str, ...] = (
    "passive_feature.summary",
    "passive_feature.vector",
    "derived_feature",
    "feature.summary",
    "feature.vector",
)

# steps / trigger_conditions 中需移除的"内部引用"键
_INTERNAL_REF_KEYS: tuple[str, ...] = (
    "reason",          # 可能含 gap_id（SandboxRun 内部 ID）
    "source_gap",      # 缺口原始引用
    "gap_id",          # SandboxRun 内部 ID
    "sandbox_run_id",  # SandboxRun 内部 ID
    "feature_id",      # 原始 DerivedFeature 引用
    "summary",         # 原始特征 summary 引用
    "vector",          # 原始特征向量引用
    "source_user_id",  # 其他用户数据引用
)


def sanitize_skill(skill: Skill) -> dict[str, Any]:
    """单个 Skill 下发前脱敏。

    - 保留：id / user_id（用户自己的）/ name / version / status / created_at / updated_at
    - 过滤 trigger_conditions：移除引用原始特征 summary 的条件，移除内部引用键
    - 清洗 steps：移除内部引用键
    - 保留 guardrails（安全底线，本身不含用户数据）
    - 移除：content_hash（内部指纹）、tenant_id（租户隔离内部字段）
    """
    # 1. 过滤 trigger_conditions
    sanitized_triggers: list[dict[str, Any]] = []
    for cond in skill.trigger_conditions or []:
        if not isinstance(cond, dict):
            continue
        field = str(cond.get("field", ""))
        # 移除引用原始特征 summary 的条件
        if any(field.startswith(prefix) for prefix in _RAW_FEATURE_FIELD_PREFIXES):
            continue
        sanitized_triggers.append(_strip_internal_refs(cond))

    # 2. 清洗 steps
    sanitized_steps: list[dict[str, Any]] = []
    for step in skill.steps or []:
        if isinstance(step, dict):
            sanitized_steps.append(_strip_internal_refs(step))
        else:
            sanitized_steps.append(step)

    # 3. 保留 guardrails 原样（安全底线，不含用户数据）
    sanitized_guardrails: list[str] = list(skill.guardrails or [])

    return {
        "id": skill.id,
        "user_id": skill.user_id,
        "name": skill.name,
        "version": skill.version,
        "trigger_conditions": sanitized_triggers,
        "guardrails": sanitized_guardrails,
        "steps": sanitized_steps,
        "status": skill.status,
        "created_at": skill.created_at,
        "updated_at": skill.updated_at,
    }


def sanitize_skills(skills: list[Skill]) -> list[dict[str, Any]]:
    """批量脱敏。"""
    return [sanitize_skill(skill) for skill in skills]


def _strip_internal_refs(obj: dict[str, Any]) -> dict[str, Any]:
    """移除 dict 中的内部引用键（递归一层 dict / list）。"""
    cleaned: dict[str, Any] = {}
    for key, value in obj.items():
        if key in _INTERNAL_REF_KEYS:
            continue
        cleaned[key] = value
    return cleaned
