"""5 维度画像计算（Milestone D）：today aggregate vs 基线桶 metrics 的确定性比较。

比较方法：对指标 m，today 值 v，基线 med/mad/p25/p75：
  scale = max(mad*1.4826, (p75-p25)/2, 1e-6)
  z = (v - med) / scale
  |z| <= 0.7 → SIMILAR；否则按方向。

维度取值禁止 GOOD/BAD/HEALTHY/NORMAL/ABNORMAL（产品契约）。
"""
from __future__ import annotations


def _scale(stats: dict) -> float:
    mad_v = stats.get("mad") or 0.0
    p25 = stats.get("p25") or 0.0
    p75 = stats.get("p75") or 0.0
    return max(mad_v * 1.4826, (p75 - p25) / 2.0, 1e-6)


def _z(value: float, stats: dict) -> float | None:
    med = stats.get("median")
    if med is None:
        return None
    return (value - med) / _scale(stats)


def _classify(value: float, stats: dict, lower: str, higher: str) -> str:
    z = _z(value, stats)
    if z is None:
        return "SIMILAR"
    if abs(z) <= 0.7:
        return "SIMILAR"
    return higher if z > 0 else lower


def compute_dimensions(today: dict, baseline_metrics: dict) -> dict:
    """计算 5 维度。返回 {DIM: {value, metric, z}}；基线缺失的维度不输出。"""
    dims: dict = {}

    # RHYTHM：active_start_minute
    v = today.get("active_start_minute")
    stats = baseline_metrics.get("active_start_minute") or {}
    if v is None:
        dims["RHYTHM"] = {"value": "IRREGULAR", "metric": "active_start_minute", "z": None}
    elif stats.get("median") is not None:
        z = _z(v, stats)
        if abs(z) <= 0.7:
            value = "SIMILAR"
        elif z < 0:
            value = "EARLIER"
        else:
            value = "LATER"
        dims["RHYTHM"] = {"value": value, "metric": "active_start_minute", "z": round(z, 4)}

    # MOVEMENT：movement_index（None 则维度不输出）
    v = today.get("movement_index")
    stats = baseline_metrics.get("movement_index") or {}
    if v is not None and stats.get("median") is not None:
        dims["MOVEMENT"] = {
            "value": _classify(v, stats, "LESS", "MORE"),
            "metric": "movement_index",
            "z": round(_z(v, stats), 4),
        }

    # SCREEN_PATTERN：screen_on_minutes；晚间屏幕 z>0.7 时以 LATER 覆盖 MORE
    v = today.get("screen_on_minutes")
    stats = baseline_metrics.get("screen_on_minutes") or {}
    if v is not None and stats.get("median") is not None:
        value = _classify(v, stats, "LESS", "MORE")
        late_v = today.get("late_screen_minutes") or 0.0
        late_stats = baseline_metrics.get("late_screen_minutes") or {}
        if late_stats.get("median") is not None and value == "MORE":
            late_z = _z(late_v, late_stats)
            if late_z is not None and late_z > 0.7:
                value = "LATER"
        dims["SCREEN_PATTERN"] = {
            "value": value,
            "metric": "screen_on_minutes",
            "z": round(_z(v, stats), 4),
        }

    # DAY_STRUCTURE：rhythm_regularity（None 则不输出）
    v = today.get("rhythm_regularity")
    stats = baseline_metrics.get("rhythm_regularity") or {}
    if v is not None and stats.get("median") is not None:
        z = _z(v, stats)
        if abs(z) <= 0.7:
            value = "SIMILAR"
        elif z < 0:
            value = "MORE_CONCENTRATED"
        else:
            value = "MORE_FRAGMENTED"
        dims["DAY_STRUCTURE"] = {"value": value, "metric": "rhythm_regularity", "z": round(z, 4)}

    # STABILITY：可用维度中非 SIMILAR 个数
    diff_count = sum(1 for entry in dims.values() if entry["value"] != "SIMILAR")
    if diff_count == 0:
        stability = "VERY_SIMILAR"
    elif diff_count <= 2:
        stability = "SLIGHTLY_DIFFERENT"
    else:
        stability = "CLEARLY_DIFFERENT"
    dims["STABILITY"] = {
        "value": stability,
        "metric": None,
        "z": None,
        "diff_count": diff_count,
    }
    return dims
