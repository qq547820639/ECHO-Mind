"""画像维度计算（Milestone D + Phase 5, C4）：today aggregate vs 基线桶 metrics 的确定性比较。

比较方法：对指标 m，today 值 v，基线 med/mad/p25/p75：
  scale = max(mad*1.4826, (p75-p25)/2, 1e-6)
  z = (v - med) / scale
  |z| <= 0.7 → SIMILAR；否则按方向。

Phase 5（C4）语义修正：
- missing != irregular：active_start_minute 缺失 → RHYTHM 维度**省略**（不输出
  IRREGULAR）；STABILITY diff_count 只统计实际输出维度的非 SIMILAR 个数；
- minimum meaningful absolute delta：|v - med| < max(MIN_ABS_DELTA[metric],
  med * MIN_REL_DELTA) → SIMILAR（z 置 0），near-zero baseline 不产生巨大 z；
- SCREEN_AMOUNT（screen_on_minutes）与 SCREEN_TIMING（late_screen_minutes）
  解耦为两个独立维度（移除旧的合并 SCREEN_PATTERN）。

维度取值禁止 GOOD/BAD/HEALTHY/NORMAL/ABNORMAL（产品契约）。
"""
from __future__ import annotations

#: 每个指标的最小有意义绝对差（低于该差视为 SIMILAR）。
#: 分钟类：active_start_minute=10 分钟；活动量：movement_index=0.02；
#: 屏幕时长：screen_on_minutes/late_screen_minutes=5 分钟；覆盖比：active_hour_spread=0.05。
MIN_ABS_DELTA: dict[str, float] = {
    "active_start_minute": 10.0,
    "active_end_minute": 10.0,
    "movement_index": 0.02,
    "screen_on_minutes": 5.0,
    "late_screen_minutes": 5.0,
    "active_hour_spread": 0.05,
}

#: 相对基线比例下限（med 较大时按比例放大阈值）
MIN_REL_DELTA = 0.05

#: 分类 z 阈值（|z| <= 该值 → SIMILAR）
Z_SIMILAR = 0.7


def _scale(stats: dict) -> float:
    mad_v = stats.get("mad") or 0.0
    p25 = stats.get("p25") or 0.0
    p75 = stats.get("p75") or 0.0
    return max(mad_v * 1.4826, (p75 - p25) / 2.0, 1e-6)


def _below_min_delta(value: float, stats: dict, metric: str) -> bool:
    med = stats.get("median")
    if med is None:
        return False
    abs_delta = abs(value - med)
    threshold = max(MIN_ABS_DELTA.get(metric, 0.0), abs(med) * MIN_REL_DELTA)
    return abs_delta < threshold


def _z(value: float, stats: dict, metric: str | None = None) -> float | None:
    med = stats.get("median")
    if med is None:
        return None
    if metric is not None and _below_min_delta(value, stats, metric):
        # 差异小于最小有意义差 → 视为接近（z=0，避免 near-zero baseline 的巨大 z）
        return 0.0
    return (value - med) / _scale(stats)


def _classify(value: float, stats: dict, lower: str, higher: str, metric: str) -> str:
    z = _z(value, stats, metric)
    if z is None:
        return "SIMILAR"
    if abs(z) <= Z_SIMILAR:
        return "SIMILAR"
    return higher if z > 0 else lower


def _emit(dims: dict, name: str, value: str, metric: str | None, z: float | None) -> None:
    """写入一个维度（z 为 None 时输出 None 而非四舍五入）。"""
    dims[name] = {"value": value, "metric": metric, "z": round(z, 4) if z is not None else None}


def compute_dimensions(today: dict, baseline_metrics: dict) -> dict:
    """计算画像维度。返回 {DIM: {value, metric, z}}；基线缺失/数据缺失的维度不输出。"""
    dims: dict = {}
    diff_count = 0

    # RHYTHM：active_start_minute；数据缺失 → 维度省略（missing != irregular）
    v = today.get("active_start_minute")
    stats = baseline_metrics.get("active_start_minute") or {}
    if v is not None and stats.get("median") is not None:
        z = _z(v, stats, "active_start_minute")
        if z is None:
            pass  # 基线缺失 → 省略
        elif abs(z) <= Z_SIMILAR:
            _emit(dims, "RHYTHM", "SIMILAR", "active_start_minute", z)
        elif z < 0:
            _emit(dims, "RHYTHM", "EARLIER", "active_start_minute", z)
            diff_count += 1
        else:
            _emit(dims, "RHYTHM", "LATER", "active_start_minute", z)
            diff_count += 1

    # MOVEMENT：movement_index（None 则维度不输出）
    v = today.get("movement_index")
    stats = baseline_metrics.get("movement_index") or {}
    if v is not None and stats.get("median") is not None:
        value = _classify(v, stats, "LESS", "MORE", "movement_index")
        _emit(dims, "MOVEMENT", value, "movement_index", _z(v, stats, "movement_index"))
        if value != "SIMILAR":
            diff_count += 1

    # SCREEN_AMOUNT：screen_on_minutes（LESS/SIMILAR/MORE）
    v = today.get("screen_on_minutes")
    stats = baseline_metrics.get("screen_on_minutes") or {}
    if v is not None and stats.get("median") is not None:
        value = _classify(v, stats, "LESS", "MORE", "screen_on_minutes")
        _emit(dims, "SCREEN_AMOUNT", value, "screen_on_minutes", _z(v, stats, "screen_on_minutes"))
        if value != "SIMILAR":
            diff_count += 1

    # SCREEN_TIMING：late_screen_minutes（EARLIER/SIMILAR/LATER）
    v = today.get("late_screen_minutes")
    stats = baseline_metrics.get("late_screen_minutes") or {}
    if v is not None and stats.get("median") is not None:
        value = _classify(v, stats, "EARLIER", "LATER", "late_screen_minutes")
        _emit(dims, "SCREEN_TIMING", value, "late_screen_minutes", _z(v, stats, "late_screen_minutes"))
        if value != "SIMILAR":
            diff_count += 1

    # DAY_STRUCTURE：active_hour_spread（None 则不输出）
    v = today.get("active_hour_spread")
    stats = baseline_metrics.get("active_hour_spread") or {}
    if v is not None and stats.get("median") is not None:
        z = _z(v, stats, "active_hour_spread")
        if z is None:
            pass
        elif abs(z) <= Z_SIMILAR:
            _emit(dims, "DAY_STRUCTURE", "SIMILAR", "active_hour_spread", z)
        elif z < 0:
            _emit(dims, "DAY_STRUCTURE", "MORE_CONCENTRATED", "active_hour_spread", z)
            diff_count += 1
        else:
            _emit(dims, "DAY_STRUCTURE", "MORE_FRAGMENTED", "active_hour_spread", z)
            diff_count += 1

    # STABILITY：实际输出维度中非 SIMILAR 个数（缺失/UNKNOWN 自然不计入）
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
