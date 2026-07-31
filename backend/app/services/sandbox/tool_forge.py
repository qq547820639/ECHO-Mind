"""沙箱工具锻造：为每个缺口生成候选 Tool 描述（不执行、不持久化）。

根据 gap.suggested_tool_type 选择模板，组装 parameters_schema / returns_schema /
guardrails / steps。guardrails 必含三条安全底线。
"""
from __future__ import annotations

from typing import Any

from sqlalchemy.orm import Session

# 所有候选 Tool 必须包含的安全底线 guardrails
REQUIRED_GUARDRAILS: tuple[str, ...] = (
    "不输出诊断结论",
    "不替代专业医疗",
    "命中红色信号立即冻结",
)

# 按 suggested_tool_type 索引的模板字典
_TEMPLATES: dict[str, dict[str, Any]] = {
    "data_check": {
        "description": "检测当日感知数据覆盖度，识别缺失的信号源并输出覆盖率。",
        "parameters_schema": {
            "type": "object",
            "properties": {
                "time_window": {
                    "type": "string",
                    "description": "检查的时间窗口，如 '1d' 或 '24h'",
                },
            },
            "required": ["time_window"],
        },
        "returns_schema": {
            "type": "object",
            "properties": {
                "missing_sources": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "缺失的 source 列表",
                },
                "coverage_ratio": {
                    "type": "number",
                    "description": "覆盖率 0-1",
                },
            },
            "required": ["missing_sources", "coverage_ratio"],
        },
        "steps": [
            {"key": "scan", "description": "扫描当日 DerivedFeature，统计各 source 覆盖情况"},
            {"key": "report", "description": "输出缺失 source 列表与覆盖率，不做诊断推断"},
        ],
    },
    "signal_probe": {
        "description": "探测指定信号源的最新状态，补全缺失的感知覆盖。",
        "parameters_schema": {
            "type": "object",
            "properties": {
                "source": {
                    "type": "string",
                    "description": "信号源名称，如 accel/gyro/screen/notification/app_activity",
                },
                "time_window": {
                    "type": "string",
                    "description": "回溯窗口，如 '24h'",
                },
            },
            "required": ["source"],
        },
        "returns_schema": {
            "type": "object",
            "properties": {
                "source": {"type": "string"},
                "available": {"type": "boolean", "description": "该 source 是否有数据"},
                "last_seen": {"type": "string", "description": "最近一次出现时间 ISO 字符串"},
            },
            "required": ["source", "available"],
        },
        "steps": [
            {"key": "probe", "description": "查询指定 source 的最新 DerivedFeature"},
            {"key": "fallback", "description": "若缺失则标记需要端侧补采，不主动推断情绪"},
        ],
    },
    "mood_check": {
        "description": "基于近期叙事与派生特征做情绪检查，输出情绪评分与趋势。",
        "parameters_schema": {
            "type": "object",
            "properties": {
                "time_window": {
                    "type": "string",
                    "description": "回溯窗口，如 '7d'",
                },
            },
            "required": ["time_window"],
        },
        "returns_schema": {
            "type": "object",
            "properties": {
                "mood_score": {
                    "type": "number",
                    "description": "情绪评分 0-100，越低越偏暗",
                },
                "trend": {
                    "type": "string",
                    "description": "趋势标签：improving/stable/declining",
                },
            },
            "required": ["mood_score", "trend"],
        },
        "steps": [
            {"key": "aggregate", "description": "汇总时间窗口内叙事 mood_hint 与特征摘要"},
            {"key": "score", "description": "输出情绪评分与趋势标签，不下诊断结论"},
        ],
    },
    "observation_wait": {
        "description": "标记观测数据不足，建议延长观测期后再做进一步决策。",
        "parameters_schema": {
            "type": "object",
            "properties": {
                "min_days": {
                    "type": "integer",
                    "description": "最少观测天数",
                },
            },
            "required": ["min_days"],
        },
        "returns_schema": {
            "type": "object",
            "properties": {
                "current_days": {"type": "integer", "description": "当前已观测天数"},
                "ready": {"type": "boolean", "description": "是否达到 min_days"},
            },
            "required": ["current_days", "ready"],
        },
        "steps": [
            {"key": "count", "description": "统计 UserProfile.observation_days"},
            {"key": "advise", "description": "若不足 min_days 则建议继续观测，不主动干预"},
        ],
    },
}


def forge_tools(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    gaps: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """为每个缺口生成候选 Tool 描述（尚未持久化）。

    返回列表，每项结构：
      ``{name, description, parameters_schema, returns_schema,
         guardrails, steps, suggested_tool_type, source_gap}``
    """
    # db 参数保留以备未来扩展（如基于已有 Tool 去重），当前不写库
    _ = (db, tenant_id, user_id)

    tools: list[dict[str, Any]] = []
    for gap in gaps:
        tool_type = gap.get("suggested_tool_type", "")
        template = _TEMPLATES.get(tool_type)
        if template is None:
            # 未知 tool_type 跳过，不生成候选
            continue
        gap_id = gap.get("gap_id", "unknown")
        tools.append({
            "name": f"{tool_type}__{gap_id}",
            "description": template["description"],
            "parameters_schema": template["parameters_schema"],
            "returns_schema": template["returns_schema"],
            "guardrails": list(REQUIRED_GUARDRAILS),
            "steps": [dict(step) for step in template["steps"]],
            "suggested_tool_type": tool_type,
            "source_gap": dict(gap),
        })
    return tools
