"""订阅生命周期测试（v0.7 订阅制）。

覆盖：
- 服务层：NULL 到期 = 永不过期（机构旧用户兼容）；显式到期判定；续订顺延；
- 激活码：subscription_days 兑换授予订阅；重复兑换续订从当前到期顺延；
- HTTP：GET /v1/me/subscription 形状与鉴权；verify-code 响应携带订阅字段；
- 门禁：显式到期 → /v1/escalations 与 /v1/skills 402；NULL 到期不受影响。
"""
from __future__ import annotations

from datetime import UTC, datetime, timedelta

from fastapi.testclient import TestClient

from app.database import SessionLocal
from app.models import User
from app.services.activation import issue_code, redeem_code
from app.services.subscription import grant_subscription, subscription_active, subscription_status


def _set_expiry(user_id: str, dt: datetime | None) -> None:
    with SessionLocal() as db:
        user = db.get(User, user_id)
        assert user is not None
        user.subscription_expires_at = dt
        db.commit()


def test_subscription_active_semantics():
    with SessionLocal() as db:
        user = db.get(User, "u_demo")
        now = datetime(2026, 8, 10, tzinfo=UTC)
        # NULL 到期 = 永不过期（机构旧用户兼容）
        assert user.subscription_expires_at is None
        assert subscription_active(user, now) is True
        # 未来到期 → 有效；过去到期 → 无效
        user.subscription_expires_at = now + timedelta(days=1)
        assert subscription_active(user, now) is True
        user.subscription_expires_at = now - timedelta(seconds=1)
        assert subscription_active(user, now) is False


def test_subscription_status_shape():
    with SessionLocal() as db:
        user = db.get(User, "u_demo")
        grant_subscription(user, days=30, now=datetime(2026, 8, 10, tzinfo=UTC))
        status = subscription_status(user, now=datetime(2026, 8, 10, tzinfo=UTC))
        assert status["subscribed"] is True
        assert status["plan"] == "standard"
        assert status["days_left"] == 30
        assert status["expires_at"] is not None


def test_grant_subscription_extends_from_current_expiry():
    with SessionLocal() as db:
        user = db.get(User, "u_demo")
        base = datetime(2026, 8, 10, tzinfo=UTC)
        grant_subscription(user, days=30, now=base)
        first_expiry = user.subscription_expires_at
        # 续订 30 天：从当前到期顺延，而非从 now 起算
        grant_subscription(user, days=30, now=base + timedelta(days=10))
        assert user.subscription_expires_at == first_expiry + timedelta(days=30)


def test_redeem_code_grants_and_renews_subscription():
    with SessionLocal() as db:
        code, raw = issue_code(
            db, tenant_id="t_demo", created_by="test", user_id="u_demo", subscription_days=30
        )
        db.commit()
        row, reason = redeem_code(db, code=raw, actor_ip=None, device_id=None)
        assert reason is None
        db.commit()
        user = db.get(User, "u_demo")
        assert user is not None and user.subscription_expires_at is not None
        assert user.subscription_plan == "standard"
        # SQLite 读回 naive datetime（按 UTC 解释，见 services/subscription._aware）
        first_expiry = user.subscription_expires_at.replace(tzinfo=UTC)

        # 再发一枚 30 天码续订：从当前到期顺延
        code2, raw2 = issue_code(
            db, tenant_id="t_demo", created_by="test", user_id="u_demo", subscription_days=30
        )
        db.commit()
        _, reason2 = redeem_code(db, code=raw2, actor_ip=None, device_id=None)
        assert reason2 is None
        db.commit()
        assert user.subscription_expires_at == first_expiry + timedelta(days=30)


def test_http_subscription_status(client: TestClient, user_headers):
    resp = client.get("/v1/me/subscription", headers=user_headers)
    assert resp.status_code == 200
    body = resp.json()
    # 机构旧用户：无显式到期 → 永不过期（subscribed=true, expires_at=null）
    assert body["subscribed"] is True
    assert body["expires_at"] is None

    resp_unauth = client.get("/v1/me/subscription")
    assert resp_unauth.status_code == 401


def test_verify_code_carries_subscription_fields(client: TestClient, user_headers):
    # 预绑定到 u_demo 的 30 天订阅码：verify-code 响应携带订阅字段
    with SessionLocal() as db:
        code, raw = issue_code(
            db, tenant_id="t_demo", created_by="test", user_id="u_demo", subscription_days=30
        )
        db.commit()
        resp = client.post("/v1/onboarding/verify-code", json={"code": raw})
        assert resp.status_code == 200
        body = resp.json()
        assert body["subscription_plan"] == "standard"
        assert body["subscription_expires_at"] is not None


def test_expired_subscription_blocks_cloud_features(client: TestClient, user_headers):
    past = datetime.now(UTC) - timedelta(days=1)
    _set_expiry("u_demo", past)
    # 人工支持（订阅能力）→ 402
    resp_esc = client.post(
        "/v1/escalations",
        json={
            "event_id": "evt_sub_1",
            "user_id": "u_demo",
            "trigger": "help_requested",
            "evidence_summary": "需要支持",
        },
        headers=user_headers,
    )
    assert resp_esc.status_code == 402
    # 能力练习（订阅能力）→ 402
    resp_skills = client.get("/v1/skills", headers=user_headers)
    assert resp_skills.status_code == 402
    # 本地主链不受影响：画像端点仍可用
    resp_portrait = client.get("/v1/me/portraits/today", headers=user_headers)
    assert resp_portrait.status_code in (200, 409)


def test_legacy_users_without_expiry_are_unaffected(client: TestClient, user_headers):
    """NULL 到期（机构旧用户）永不过期：云能力不受门禁影响（存量兼容锚点）。"""
    resp_skills = client.get("/v1/skills", headers=user_headers)
    assert resp_skills.status_code == 200
