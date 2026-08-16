"""ERA 32 R25：无凭证续期端点（POST /v1/auth/refresh）+ 默认秘密 fail-closed 测试。

覆盖：
- verify-code 响应携带 refresh_token；
- 续期成功：新 access token + 轮换新 refresh token；
- 轮换后旧 refresh token 立即作废（重放 → 401）；
- 错误令牌 / 已过期令牌 → 401（不泄露具体原因）；
- 任何环境下仓库默认 dev 秘密都拒绝启动（config fail-closed）。
"""
from datetime import UTC, datetime, timedelta

import pytest

from app.database import SessionLocal
from app.models import User


def _seed_user(user_id: str, external_ref: str, *, status: str = "active") -> None:
    with SessionLocal() as db:
        db.add(User(id=user_id, tenant_id="t_demo", external_ref=external_ref, status=status))
        db.commit()


def _bind(client, code: str) -> dict:
    response = client.post("/v1/onboarding/verify-code", json={"code": code})
    assert response.status_code == 200
    return response.json()


def test_verify_code_returns_refresh_token(client):
    _seed_user("u_refresh", "REFRESH-CODE-01")
    body = _bind(client, "REFRESH-CODE-01")
    assert body["refresh_token"]


def test_refresh_returns_new_access_and_rotated_refresh(client):
    _seed_user("u_refresh", "REFRESH-CODE-02")
    body = _bind(client, "REFRESH-CODE-02")
    response = client.post(
        "/v1/auth/refresh",
        json={"user_id": body["user_id"], "refresh_token": body["refresh_token"]},
    )
    assert response.status_code == 200
    refreshed = response.json()
    assert refreshed["access_token"]
    assert refreshed["refresh_token"] != body["refresh_token"]


def test_refresh_rotates_and_old_token_rejected(client):
    _seed_user("u_refresh", "REFRESH-CODE-03")
    body = _bind(client, "REFRESH-CODE-03")
    first = client.post(
        "/v1/auth/refresh",
        json={"user_id": body["user_id"], "refresh_token": body["refresh_token"]},
    )
    assert first.status_code == 200
    replay = client.post(
        "/v1/auth/refresh",
        json={"user_id": body["user_id"], "refresh_token": body["refresh_token"]},
    )
    assert replay.status_code == 401


def test_refresh_wrong_token_rejected(client):
    _seed_user("u_refresh", "REFRESH-CODE-04")
    body = _bind(client, "REFRESH-CODE-04")
    response = client.post(
        "/v1/auth/refresh",
        json={"user_id": body["user_id"], "refresh_token": "x" * 40},
    )
    assert response.status_code == 401


def test_refresh_expired_token_rejected(client):
    _seed_user("u_refresh", "REFRESH-CODE-05")
    body = _bind(client, "REFRESH-CODE-05")
    with SessionLocal() as db:
        user = db.get(User, body["user_id"])
        user.refresh_expires_at = datetime.now(UTC) - timedelta(seconds=1)
        db.commit()
    response = client.post(
        "/v1/auth/refresh",
        json={"user_id": body["user_id"], "refresh_token": body["refresh_token"]},
    )
    assert response.status_code == 401


def test_default_dev_secrets_rejected_in_any_environment():
    from app.config import Settings

    for env in ("local", "pilot", "production"):
        settings = Settings(
            environment=env,
            jwt_secret="dev-secret-change-me-please-32-bytes",
            field_encryption_secret="custom-not-default",
            bootstrap_key="custom-not-default",
        )
        with pytest.raises(RuntimeError):
            settings.validate_production_secrets()


def test_non_default_secrets_accepted():
    from app.config import Settings

    settings = Settings(
        environment="local",
        jwt_secret="custom-jwt-secret",
        field_encryption_secret="custom-field-secret",
        bootstrap_key="custom-bootstrap-key",
    )
    settings.validate_production_secrets()
