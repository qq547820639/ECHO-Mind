"""画像置信度（Milestone C/D）。

规则（确定性，设计决定——任务未给具体阈值，保证 LOW_CONFIDENCE 与
PARTIAL_DATA 均可达：coverage 0.3~0.4 且基线充足 → MEDIUM → PARTIAL_DATA）：
- HIGH：今日覆盖 >= 0.7 且基线有效日 >= 7 且无缺失信号源；
- MEDIUM：今日覆盖 >= 0.3 且基线有效日 >= 3；
- LOW：其余情况。
"""
from __future__ import annotations


def confidence_for(today_coverage: float, baseline_valid_days: int, missing_sources: list[str]) -> str:
    missing = set(missing_sources or [])
    if today_coverage >= 0.7 and baseline_valid_days >= 7 and not missing:
        return "HIGH"
    if today_coverage >= 0.3 and baseline_valid_days >= 3:
        return "MEDIUM"
    return "LOW"
