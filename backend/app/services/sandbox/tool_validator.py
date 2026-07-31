"""沙箱工具验证：对候选 Tool 做静态验证（不执行）。

校验项：
  - parameters_schema / returns_schema 是合法 JSON Schema（基本结构检查）
  - guardrails 含三条必须安全底线
  - steps 非空且每步有 description
  - description + guardrails 文本不含被动红色关键词（防沙箱生成危险 Tool）
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from sqlalchemy.orm import Session

from app.services.safety import evaluate_passive
from app.services.sandbox.tool_forge import REQUIRED_GUARDRAILS


@dataclass
class _TextFeature:
    """适配 evaluate_passive 的简易包装器。"""

    summary: str


def validate_tools(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    candidate_tools: list[dict[str, Any]],
) -> list[tuple[dict[str, Any], bool, str]]:
    """对每个候选 Tool 做静态验证。

    返回 ``[(tool, is_valid, reason)]``。不写库，不抛异常。
    """
    # db 参数保留以备未来扩展（如查重），当前不写库
    _ = (db, tenant_id, user_id)

    results: list[tuple[dict[str, Any], bool, str]] = []
    for tool in candidate_tools:
        is_valid, reason = _validate_one(tool)
        results.append((tool, is_valid, reason))
    return results


def _validate_one(tool: dict[str, Any]) -> tuple[bool, str]:
    """验证单个候选 Tool。返回 (is_valid, reason)。"""
    # 1. JSON Schema 基本结构检查
    if not _is_valid_json_schema(tool.get("parameters_schema")):
        return False, "parameters_schema 不是合法 JSON Schema"
    if not _is_valid_json_schema(tool.get("returns_schema")):
        return False, "returns_schema 不是合法 JSON Schema"

    # 2. guardrails 三条必须安全底线
    guardrails: list[str] = list(tool.get("guardrails") or [])
    for required in REQUIRED_GUARDRAILS:
        if not any(required in g for g in guardrails):
            return False, f"缺少必要 guardrail: {required}"

    # 3. steps 非空且每步有 description
    steps: list[dict[str, Any]] = list(tool.get("steps") or [])
    if not steps:
        return False, "steps 为空"
    for index, step in enumerate(steps):
        if not isinstance(step, dict) or not step.get("description"):
            return False, f"step {index} 缺少 description"

    # 4. 红色关键词检查：description + guardrails 文本不得命中被动红色词
    text_parts = [str(tool.get("description", ""))]
    text_parts.extend(str(g) for g in guardrails)
    text_parts.extend(str(step.get("description", "")) for step in steps)
    combined = " ".join(text_parts)
    severity, matched = evaluate_passive([_TextFeature(combined)])
    if severity == "red":
        return False, f"含被动红色关键词: {','.join(matched)}"

    return True, "ok"


def _is_valid_json_schema(schema: Any) -> bool:
    """基本 JSON Schema 结构检查（不依赖 jsonschema 库）。

    校验：
      - 顶层必须是 dict
      - type（如有）必须是 str 或 list[str]
      - properties（如有）必须是 dict
      - required（如有）必须是 list
      - items（如有）必须是 dict 或 list
    """
    if not isinstance(schema, dict):
        return False
    if "type" in schema:
        t = schema["type"]
        if isinstance(t, list):
            if not all(isinstance(x, str) for x in t):
                return False
        elif not isinstance(t, str):
            return False
    if "properties" in schema and not isinstance(schema["properties"], dict):
        return False
    if "required" in schema and not isinstance(schema["required"], list):
        return False
    if "items" in schema and not isinstance(schema["items"], (dict, list)):
        return False
    return True
