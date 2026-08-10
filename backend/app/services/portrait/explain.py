"""画像事实清单（Milestone D）：本地时间格式化的确定性 facts。

delta 百分比 = round(abs(v - med) / max(med, 1e-9) * 100)。
"""
from __future__ import annotations

from app.services.portrait.dimensions import _scale


def _hhmm(minute: float) -> str:
    m = int(round(minute))
    return f"{m // 60:02d}:{m % 60:02d}"


def _delta_text(value: float, stats: dict) -> str:
    med = stats.get("median")
    if med is None:
        return "暂无基线"
    z = (value - med) / _scale(stats)
    if abs(z) <= 0.7:
        return "和近期水平接近"
    pct = round(abs(value - med) / max(med, 1e-9) * 100)
    direction = "少" if value < med else "多"
    return f"比近期中位水平{direction}约 {pct}%"


def build_facts(today: dict, baseline_metrics: dict) -> list[dict]:
    """构建 3 个确定性事实（数据缺失时对应事实省略）。"""
    facts: list[dict] = []

    start = today.get("active_start_minute")
    start_stats = baseline_metrics.get("active_start_minute") or {}
    if start is not None:
        baseline_text = "暂无基线"
        if start_stats.get("median") is not None:
            baseline_text = f"约 {_hhmm(start_stats['median'])}"
        facts.append({
            "label": "开始活跃",
            "today_text": _hhmm(start),
            "baseline_text": baseline_text,
        })

    movement = today.get("movement_index")
    move_stats = baseline_metrics.get("movement_index") or {}
    if movement is not None:
        facts.append({
            "label": "日间移动",
            "today_text": f"{movement:.2f}",
            "delta_text": _delta_text(movement, move_stats),
        })

    late = today.get("late_screen_minutes") or 0.0
    late_stats = baseline_metrics.get("late_screen_minutes") or {}
    if late > 0:
        facts.append({
            "label": "晚间屏幕",
            "today_text": f"{int(round(late))} 分钟",
            "delta_text": _delta_text(late, late_stats),
        })

    return facts
