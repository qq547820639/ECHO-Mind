"""Circular statistics for time-of-day minutes（Phase 5, C3）。

把 0..1439 的分钟视为圆周上的角度（minute * 2π / 1440），使 23:55 与 00:05
正确接近（线性视角下相差 1430 分钟，圆周视角下仅相差 10 分钟）。

提供两个纯函数：
- ``circular_median``：使到其余样本的圆周距离之和最小的样本值（确定性，返回输入值之一）；
- ``circular_mad``：围绕 circular median 的圆周绝对偏差中位数。

基线统计（active_start_minute / active_end_minute）使用本模块而非线性 median/MAD，
避免跨午夜用户（如 23:55 入睡、00:05 醒来）被错误判为"差异巨大"。
"""
from __future__ import annotations

from statistics import median as _linear_median

MINUTES_PER_DAY = 1440
TWO_PI = 2.0 * 3.141592653589793


def _wrap(value: float) -> float:
    """把分钟数折回 [0, 1440)。"""
    return value % MINUTES_PER_DAY


def circular_distance(a: float, b: float) -> float:
    """两个分钟值之间的最短圆周距离（0..720 分钟）。"""
    raw = abs(_wrap(a) - _wrap(b))
    return min(raw, MINUTES_PER_DAY - raw)


def circular_median(values: list[float]) -> float | None:
    """圆周中位数：使圆周距离之和最小的输入样本值；空输入返回 None。

    以输入样本为候选（避免输出不存在的值），确定性可复现。
    """
    if not values:
        return None
    samples = [_wrap(float(v)) for v in values]
    best = min(samples, key=lambda m: sum(circular_distance(m, v) for v in samples))
    return float(best)


def circular_mad(values: list[float]) -> float | None:
    """圆周 MAD：样本到 circular median 的圆周距离的中位数；空输入返回 None。"""
    med = circular_median(values)
    if med is None:
        return None
    distances = [circular_distance(med, v) for v in values]
    return float(_linear_median(distances))
