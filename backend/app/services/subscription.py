"""订阅生命周期服务（v0.7 本地优先 + 订阅制）。

语义：
- `subscription_expires_at` 为 NULL → 永不过期（机构旧用户/存量数据向后兼容）；
- 显式到期（expires_at <= now）→ 订阅不可用（402：人工支持/能力下发暂停）；
- 续订（兑换含 subscription_days 的激活码）从 max(now, 当前到期) 顺延。

本地模式（未订阅）不受本服务影响：本地画像/趋势/小结/数据权利全部端侧可用；
订阅到期只冻结云端能力（专业支持、能力练习、云端分析消息由端侧镜像兜底）。
"""
from __future__ import annotations
from typing import Any

from datetime import UTC, datetime

from app.models import User


def _aware(value: datetime | None) -> datetime | None:
    if value is None:
        return None
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def subscription_active(user: User, now: datetime | None = None) -> bool:
    """订阅是否有效：NULL 到期 = 永不过期（机构旧用户兼容）；显式到期且已过 → False。"""
    expires = _aware(user.subscription_expires_at)
    if expires is None:
        return True
    reference = now if now is not None else datetime.now(UTC)
    return expires > reference


def subscription_status(user: User, now: datetime | None = None) -> dict[str, Any]:
    """订阅状态视图（不泄露内部字段）。"""
    reference = now if now is not None else datetime.now(UTC)
    expires = _aware(user.subscription_expires_at)
    active = expires is None or expires > reference
    days_left: int | None = None
    if expires is not None:
        days_left = max((expires - reference).days, 0)
    return {
        "subscribed": active,
        "plan": user.subscription_plan or ("standard" if expires is not None else None),
        "expires_at": expires.isoformat() if expires is not None else None,
        "days_left": days_left,
    }


def grant_subscription(user: User, *, days: int, now: datetime | None = None) -> None:
    """授予/续订订阅：从 max(now, 当前到期) 顺延 days 天；NULL 到期从 now 起算。"""
    reference = now if now is not None else datetime.now(UTC)
    current = _aware(user.subscription_expires_at)
    base = reference
    if current is not None and current > reference:
        base = current
    from datetime import timedelta

    user.subscription_expires_at = base + timedelta(days=days)
    user.subscription_plan = user.subscription_plan or "standard"
