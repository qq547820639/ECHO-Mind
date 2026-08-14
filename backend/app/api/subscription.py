"""订阅状态路由（v0.7 订阅生命周期）：GET /v1/me/subscription。

只读：返回当前用户（principal.subject）的订阅状态；
不泄露 tenant/plan 内部字段之外的任何信息。
"""
from __future__ import annotations

from fastapi import APIRouter

from app.models import User
from app.services.subscription import subscription_status

from app.api.deps import DB, PRINCIPAL, ensure_user

router = APIRouter(prefix="/v1")


@router.get("/me/subscription")
def me_subscription(db: DB, principal: PRINCIPAL):
    user: User = ensure_user(db, principal, principal.subject)
    return subscription_status(user)
