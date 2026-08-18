"""ERA 32 R25：无凭证续期端点（POST /v1/auth/refresh）。

access token 60 分钟过期后，端侧用轮换式 refresh token 换新 access token
（预认证端点：此时 Authorization 头已不可用）。每次成功续期轮换 refresh token：
旧令牌立即作废（重放 → 401）；哈希比对用常数时间比较。
"""
from __future__ import annotations

import secrets
from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, HTTPException

from app.api.deps import DB
from app.auth import create_access_token, hash_refresh_token, new_refresh_token
from app.config import get_settings
from app.models import User
from app.schemas import RefreshTokenIn, RefreshTokenOut
from app.services.audit import append_audit

router = APIRouter(prefix="/v1")


@router.post("/auth/refresh", response_model=RefreshTokenOut)
def refresh_access_token(payload: RefreshTokenIn, db: DB) -> RefreshTokenOut:
    user = db.get(User, payload.user_id)
    now = datetime.now(UTC)
    expires_at = user.refresh_expires_at if user is not None else None
    if expires_at is not None and expires_at.tzinfo is None:
        # SQLite 读回 naive datetime：一律按 UTC 解释（与写入侧 UTC 一致）
        expires_at = expires_at.replace(tzinfo=UTC)
    if (
        user is None
        or user.status != "active"
        or user.refresh_token_hash is None
        or expires_at is None
        or expires_at < now
        or not secrets.compare_digest(user.refresh_token_hash, hash_refresh_token(payload.refresh_token))
    ):
        # 统一 401 不泄露具体原因（防用户枚举/令牌探测）
        raise HTTPException(status_code=401, detail="invalid refresh token")

    access_token = create_access_token(subject=user.id, tenant_id=user.tenant_id, role="user")
    rotated = new_refresh_token()
    user.refresh_token_hash = hash_refresh_token(rotated)
    user.refresh_expires_at = now + timedelta(days=get_settings().refresh_token_days)
    append_audit(
        db,
        tenant_id=user.tenant_id,
        actor_type="user",
        actor_id=user.id,
        action="auth.refresh",
        object_type="user",
        object_id=user.id,
        metadata={},
    )
    db.commit()
    return RefreshTokenOut(user_id=user.id, access_token=access_token, refresh_token=rotated)
