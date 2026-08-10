"""本地日期分桶（Milestone C）：weekday（周一至周五）/ weekend（周六日）。"""
from __future__ import annotations

from datetime import date


def is_weekend(local_date: date) -> bool:
    """周六/周日 → True（date.weekday(): 周一=0 … 周日=6）。"""
    return local_date.weekday() >= 5


def bucket_for_date(local_date: date) -> str:
    return "weekend" if is_weekend(local_date) else "weekday"
