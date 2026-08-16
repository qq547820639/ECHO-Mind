from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import hashlib
import secrets
from typing import Annotated

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from app.config import get_settings
from collections.abc import Callable

bearer = HTTPBearer(auto_error=False)


# ===== ERA 32 R25：轮换式刷新令牌 =====

def new_refresh_token() -> str:
    """生成刷新令牌（256-bit 随机，url-safe；服务端只存哈希）。"""
    return secrets.token_urlsafe(48)


def hash_refresh_token(token: str) -> str:
    """刷新令牌 → SHA-256 hex（服务端存储形态，绝不明文落库）。"""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class Principal:
    subject: str
    tenant_id: str
    role: str
    step_up: bool = False


ALLOWED_ROLES = {
    "user",
    "on_call",
    "professional",
    "auditor",
    "admin",
    "quality_reviewer",
    "security_auditor",
    "vendor_support",
}

# Roles allowed to read psychological content (dialog text, raw scale answers, notes).
PSYCH_CONTENT_ROLES = {"user", "professional"}
# Roles restricted to read-only access; any write attempt is rejected.
READ_ONLY_ROLES = {"auditor", "quality_reviewer", "security_auditor"}
# Roles with no access to identity or psychological data (system health/version only).
NON_DATA_ROLES = {"vendor_support"}


def create_access_token(
    subject: str,
    tenant_id: str,
    role: str,
    minutes: int | None = None,
    *,
    step_up: bool = False,
) -> str:
    if role not in ALLOWED_ROLES:
        raise ValueError("invalid role")
    settings = get_settings()
    now = datetime.now(timezone.utc)
    payload = {
        "sub": subject,
        "tenant_id": tenant_id,
        "role": role,
        "step_up": step_up,
        "iss": settings.jwt_issuer,
        "aud": settings.jwt_audience,
        "iat": now,
        "exp": now + timedelta(minutes=minutes or settings.access_token_minutes),
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm="HS256")


def get_principal(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
) -> Principal:
    if credentials is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="missing token")
    settings = get_settings()
    try:
        payload = jwt.decode(
            credentials.credentials,
            settings.jwt_secret,
            algorithms=["HS256"],
            issuer=settings.jwt_issuer,
            audience=settings.jwt_audience,
        )
        role = str(payload["role"])
        if role not in ALLOWED_ROLES:
            raise ValueError("role")
        return Principal(
            subject=str(payload["sub"]),
            tenant_id=str(payload["tenant_id"]),
            role=role,
            step_up=bool(payload.get("step_up", False)),
        )
    except Exception as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid token") from exc


def require_roles(*roles: str) -> Callable[..., Principal]:
    def dependency(principal: Annotated[Principal, Depends(get_principal)]) -> Principal:
        if principal.role not in roles:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="insufficient role")
        return principal
    return dependency
