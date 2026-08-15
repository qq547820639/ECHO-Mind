"""画像事实清单（Milestone D + Phase 5, C4）：本地时间格式化的确定性 facts。

delta 语义（Phase 5, C4）：
- 与维度分类一致：|v - med| < max(MIN_ABS_DELTA[metric], med * MIN_REL_DELTA)
  → "和近期水平接近"（不输出无意义百分比）；
- med 接近 0（低于粗粒度阈值）→ coarse wording（"比近期略多/略少/
  明显更多/明显更少"），禁止 +900% 类数字；
- 正常基线 → bounded percentage（超过 PERCENTAGE_CAP=300% 回退 coarse wording）。
"""
from __future__ import annotations
from typing import Any

from app.services.portrait.dimensions import (
    MIN_ABS_DELTA,
    MIN_REL_DELTA,
    Z_SIMILAR,
    _scale,
)

#: med 低于该阈值的指标视为"近零基线"，禁止输出百分比（按指标配置）
COARSE_BASELINE_THRESHOLDS: dict[str, float] = {
    "movement_index": 0.05,
    "screen_on_minutes": 5.0,
    "late_screen_minutes": 5.0,
    "active_hour_spread": 0.05,
    "active_start_minute": 10.0,
    "active_end_minute": 10.0,
}
#: 正常基线的百分比上限（超过则回退 coarse wording）
PERCENTAGE_CAP = 300


def _hhmm(minute: float) -> str:
    m = int(round(minute))
    return f"{m // 60:02d}:{m % 60:02d}"


def _coarse_delta(z: float, direction: str) -> str:
    """粗粒度措辞：z 强度决定"略"还是"明显"。"""
    if abs(z) > 2 * Z_SIMILAR:
        return f"比近期明显更{direction}"
    return f"比近期略{direction}"


def _delta_text(value: float, stats: dict[str, Any], metric: str) -> str:
    med = stats.get("median")
    if med is None:
        return "暂无基线"
    abs_delta = abs(value - med)
    threshold = max(MIN_ABS_DELTA.get(metric, 0.0), abs(med) * MIN_REL_DELTA)
    if abs_delta < threshold:
        return "和近期水平接近"
    z = (value - med) / _scale(stats, metric)
    direction = "少" if value < med else "多"
    if abs(med) < COARSE_BASELINE_THRESHOLDS.get(metric, 0.0):
        # 近零基线：百分比无意义（如 +900%），用粗粒度措辞
        return _coarse_delta(z, direction)
    pct = round(abs_delta / max(med, 1e-9) * 100)
    if pct >= PERCENTAGE_CAP:
        return _coarse_delta(z, direction)
    return f"比近期中位水平{direction}约 {pct}%"


def build_facts(today: dict[str, Any], baseline_metrics: dict[str, Any]) -> list[dict[str, Any]]:
    """构建 3 个确定性事实（数据缺失时对应事实省略）。"""
    facts: list[dict[str, Any]] = []

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
        # Phase 6.3（Explainability）：不渲染原始指标值（movement_index = 0.47 类数字
        # 对普通用户无意义且误导）；today_text 用行为化相对描述（coarse wording）。
        today_text: str
        if move_stats.get("median") is not None:
            # _delta_text 已是完整句（"和近期水平接近"/"比近期略少"…），
            # 但 facts 结构要求 today_text 短句，这里用方向性措辞
            med = move_stats.get("median") or 0.0
            if abs(movement - med) < max(MIN_ABS_DELTA.get("movement_index", 0.0), abs(med) * MIN_REL_DELTA):
                today_text = "和近期典型水平接近"
            else:
                today_text = "比近期典型水平少一些" if movement < med else "比近期典型水平多一些"
        else:
            today_text = "暂无基线"
        facts.append({
            "label": "日间移动",
            "today_text": today_text,
            "delta_text": _delta_text(movement, move_stats, "movement_index"),
        })

    late = today.get("late_screen_minutes") or 0.0
    late_stats = baseline_metrics.get("late_screen_minutes") or {}
    if late > 0:
        facts.append({
            "label": "晚间屏幕",
            "today_text": f"{int(round(late))} 分钟",
            "delta_text": _delta_text(late, late_stats, "late_screen_minutes"),
        })

    return facts
