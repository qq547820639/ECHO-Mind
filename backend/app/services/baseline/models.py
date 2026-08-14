"""BaselineSnapshot 数据契约（Milestone C）。"""
from __future__ import annotations
from typing import Any

from dataclasses import dataclass
from datetime import date


@dataclass
class BaselineSnapshot:
    """一次基线构建的结果快照（实际使用的桶 + 该桶 metrics）。

    bucket：weekday / weekend / all_days（fallback 兜底）。
    """

    bucket: str
    window_start: date
    window_end: date
    valid_days: int
    metrics: dict[str, Any]
    version: str
