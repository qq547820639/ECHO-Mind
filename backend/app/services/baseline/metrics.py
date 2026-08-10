"""Robust 统计纯函数（Milestone C）。

- percentile 固定使用线性插值（numpy 风格）；
- 空值处理：输入为空 → 各统计量返回 None（valid_days=0）；
- 全零数据正常计算（median=0, mad=0, p10..p90=0）。
"""
from __future__ import annotations

from statistics import median as _median


def median(values: list[float]) -> float | None:
    if not values:
        return None
    return _median(values)


def mad(values: list[float]) -> float | None:
    """Median Absolute Deviation（围绕中位数）。"""
    med = median(values)
    if med is None:
        return None
    return _median([abs(x - med) for x in values])


def percentile(values: list[float], p: float) -> float | None:
    """线性插值分位数（0 < p < 100）。固定一种插值保证确定性。"""
    if not values:
        return None
    ordered = sorted(values)
    k = (len(ordered) - 1) * (p / 100.0)
    lower = int(k)
    upper = lower + 1
    if upper > len(ordered) - 1:
        return ordered[-1]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (k - lower)


def compute_stats(values: list[float]) -> dict:
    """计算指标的 robust 统计量；空输入返回全 None + valid_days=0。"""
    if not values:
        return {
            "median": None,
            "mad": None,
            "p10": None,
            "p25": None,
            "p75": None,
            "p90": None,
            "valid_days": 0,
        }
    return {
        "median": median(values),
        "mad": mad(values),
        "p10": percentile(values, 10),
        "p25": percentile(values, 25),
        "p75": percentile(values, 75),
        "p90": percentile(values, 90),
        "valid_days": len(values),
    }
