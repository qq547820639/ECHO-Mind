"""沙箱技能归纳：将验证通过的 Tool 归纳为 Skill（草稿）。

- 相同 suggested_tool_type 的 Tool 合并为一个 Skill
- trigger_conditions 从 gap 推导，guardrails 合并去重，steps 合并去重
- Skill status="draft"，写入 content_hash（sha256 of JSON）
- 幂等：相同内容（content_hash 匹配）复用已有 Skill，不产生重复版本
- 不写审计（由 runner 统一写），不 commit
"""
from __future__ import annotations

import hashlib
import json
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Skill, Tool


def induct_skills(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    validated_tools: list[dict[str, Any]],
) -> list[Skill]:
    """将验证通过的 Tool 归纳为 Skill 草稿。

    返回本次涉及的 Skill 列表（新建或复用的）。不写审计，不 commit。
    """
    # 按 suggested_tool_type 分组合并
    groups: dict[str, list[dict[str, Any]]] = {}
    for tool in validated_tools:
        tool_type = tool.get("suggested_tool_type", "unknown")
        groups.setdefault(tool_type, []).append(tool)

    skills: list[Skill] = []
    for tool_type, tools in groups.items():
        skill_name = f"auto_{tool_type}"
        trigger_conditions = _merge_trigger_conditions(tools)
        guardrails = _merge_guardrails(tools)
        steps = _merge_steps(tools)

        content = {
            "name": skill_name,
            "trigger_conditions": trigger_conditions,
            "guardrails": guardrails,
            "steps": steps,
        }
        content_hash = _hash_content(content)

        # 幂等：同 name 最新版本 content_hash 匹配则复用
        existing = db.scalar(
            select(Skill).where(
                Skill.tenant_id == tenant_id,
                Skill.user_id == user_id,
                Skill.name == skill_name,
            ).order_by(Skill.version.desc()).limit(1)
        )
        if existing and existing.content_hash == content_hash:
            skill = existing
        else:
            new_version = (existing.version + 1) if existing else 1
            skill = Skill(
                tenant_id=tenant_id,
                user_id=user_id,
                name=skill_name,
                version=new_version,
                trigger_conditions=trigger_conditions,
                guardrails=guardrails,
                steps=steps,
                status="draft",
                content_hash=content_hash,
            )
            db.add(skill)
            db.flush()

        # 为每个 Tool 创建记录（幂等：同名跳过）
        _upsert_tools(db, tenant_id, user_id, skill.id, tools)
        skills.append(skill)

    return skills


def _merge_trigger_conditions(tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """从每个 Tool 的 source_gap 推导 trigger_conditions，按 value 去重。"""
    seen_values: set[str] = set()
    conditions: list[dict[str, Any]] = []
    for tool in tools:
        gap = tool.get("source_gap") or {}
        value = gap.get("description", "")
        if value in seen_values:
            continue
        seen_values.add(value)
        conditions.append({
            "field": "gap.description",
            "op": "eq",
            "value": value,
            "reason": f"由缺口 {gap.get('gap_id', '?')} 触发",
        })
    return conditions


def _merge_guardrails(tools: list[dict[str, Any]]) -> list[str]:
    """合并所有 Tool 的 guardrails，去重并排序（确定性顺序）。"""
    merged: set[str] = set()
    for tool in tools:
        for g in tool.get("guardrails") or []:
            merged.add(str(g))
    return sorted(merged)


def _merge_steps(tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """合并所有 Tool 的 steps，按 description 去重，保持首次出现顺序。"""
    seen: set[str] = set()
    merged: list[dict[str, Any]] = []
    for tool in tools:
        for step in tool.get("steps") or []:
            key = step.get("description", "") if isinstance(step, dict) else str(step)
            if key in seen:
                continue
            seen.add(key)
            merged.append(dict(step) if isinstance(step, dict) else {"description": str(step)})
    return merged


def _hash_content(content: dict[str, Any]) -> str:
    """对 Skill 内容做 sha256，与 audit.py 的规范化方式保持一致。"""
    return hashlib.sha256(
        json.dumps(content, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def _upsert_tools(
    db: Session,
    tenant_id: str,
    user_id: str,
    skill_id: str,
    tools: list[dict[str, Any]],
) -> None:
    """为每个候选 Tool 创建 Tool 记录（status=draft），同名跳过。"""
    for tool in tools:
        name = tool.get("name", "")
        if not name:
            continue
        existing = db.scalar(
            select(Tool).where(
                Tool.tenant_id == tenant_id,
                Tool.user_id == user_id,
                Tool.name == name,
            )
        )
        if existing:
            # 已存在则更新 skill_id 归属（可能升级到新版本 Skill）
            if existing.skill_id != skill_id:
                existing.skill_id = skill_id
            continue
        row = Tool(
            tenant_id=tenant_id,
            user_id=user_id,
            skill_id=skill_id,
            name=name,
            description=tool.get("description", ""),
            parameters_schema=tool.get("parameters_schema", {}),
            returns_schema=tool.get("returns_schema", {}),
            status="draft",
        )
        db.add(row)
    db.flush()
