"""订阅用户分析消息（v0.7 本地优先：后台「分析 → 推送回应用」的拉取式过渡实现）。

从近 7 天 daily_portraits 确定性生成「本周节律小结」：
- 各维度统计 SIMILAR 比例，最高者 = 「最接近」；
- 各维度统计非 SIMILAR 次数，最多者 = 「变化较明显」；
- 有效画像天数 < 3 → 不生成（数据不足不硬编，产品契约 abstain）。

message_id = SHA-256(标题 + 正文 + 窗口) 前 16 位 —— 确定性幂等，
客户端据此去重（新小结才发本地通知）。

词表安全：复用 portrait 契约措辞（与端侧 portraitStabilitySummary 逐语义镜像），
禁止情绪推断词；只陈述行为事实，不做心理状态解释。
"""
from __future__ import annotations
from typing import Any

import hashlib
from datetime import date, timedelta

from sqlalchemy.orm import Session

from app.models import DailyPortrait

#: 小结统计窗口：近 7 天（含今天）。
DIGEST_WINDOW_DAYS = 7
#: 生成小结所需最少有效画像天数（不足则 abstain）。
MIN_PORTRAIT_DAYS = 3

#: 维度展示顺序（与端侧 PORTRAIT_DIMENSIONS 一致）。
DIMENSION_ORDER = (
    "RHYTHM",
    "MOVEMENT",
    "SCREEN_AMOUNT",
    "SCREEN_TIMING",
    "DAY_STRUCTURE",
    "STABILITY",
)

#: 维度键 → 中文标签（与端侧 dimensionDisplayName 一致）。
DIMENSION_LABELS = {
    "RHYTHM": "作息",
    "MOVEMENT": "移动",
    "SCREEN_AMOUNT": "屏幕总量",
    "SCREEN_TIMING": "屏幕时段",
    "DAY_STRUCTURE": "行为分布",
    "STABILITY": "整体节律",
}

#: 画像契约 BLOCK 词（小结文案输出后应断言不含任何命中）。
BLOCKED_VOCABULARY = ("焦虑", "抑郁", "孤独", "压力过大", "情绪低落", "社交退缩", "心理异常", "心理风险", "精神疾病", "自杀", "自伤")


def _contains_blocked(text: str) -> bool:
    return any(word in text for word in BLOCKED_VOCABULARY)


def build_weekly_digest(portraits: list[DailyPortrait]) -> dict[str, Any] | None:
    """从画像行确定性生成小结；不足 MIN_PORTRAIT_DAYS 天返回 None。

    返回结构：{"id", "title", "body", "generated_at"}（generated_at 由调用方传入时区无关；
    此处用窗口起止日期保证幂等 id）。
    """
    if len(portraits) < MIN_PORTRAIT_DAYS:
        return None

    stats: dict[str, dict[str, int]] = {}
    for row in portraits:
        dims = row.dimensions or {}
        for dim in DIMENSION_ORDER:
            entry = dims.get(dim)
            if not isinstance(entry, dict):
                continue
            value = entry.get("value")
            if not isinstance(value, str):
                continue
            stat = stats.setdefault(dim, {"similar": 0, "total": 0})
            stat["total"] += 1
            if value == "SIMILAR":
                stat["similar"] += 1

    if not stats:
        return None

    dates = sorted({row.local_date for row in portraits})
    window_start, window_end = dates[0], dates[-1]

    def _ratio(stat: dict[str, int]) -> float:
        return stat["similar"] / max(stat["total"], 1)

    most_similar = max(stats.items(), key=lambda kv: _ratio(kv[1]))
    most_changed = max(stats.items(), key=lambda kv: kv[1]["total"] - kv[1]["similar"])

    body = (
        f"最接近：{DIMENSION_LABELS.get(most_similar[0], most_similar[0])}；"
        f"变化较明显：{DIMENSION_LABELS.get(most_changed[0], most_changed[0])}。"
    )
    if _contains_blocked(body):
        # fail-closed：命中契约 BLOCK 词则不出小结（宁可 abstain，绝不越界）
        return None

    digest_id = hashlib.sha256(
        f"weekly_digest|{window_start}|{window_end}|{body}".encode("utf-8")
    ).hexdigest()[:16]

    return {
        "id": digest_id,
        "title": "本周节律小结",
        "body": body,
        "window_start": str(window_start),
        "window_end": str(window_end),
    }


def recent_portraits(db: Session, *, tenant_id: str, user_id: str, today: date) -> list[DailyPortrait]:
    """近 7 天画像（含今天，按日期升序；调用方负责 tenant/user 校验）。"""
    start = today - timedelta(days=DIGEST_WINDOW_DAYS - 1)
    from sqlalchemy import select

    return list(
        db.scalars(
            select(DailyPortrait)
            .where(
                DailyPortrait.tenant_id == tenant_id,
                DailyPortrait.user_id == user_id,
                DailyPortrait.local_date >= start,
                DailyPortrait.local_date <= today,
            )
            .order_by(DailyPortrait.local_date.asc())
        ).all()
    )
