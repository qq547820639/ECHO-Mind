"""v0.6 final：Onboarding 激活码交换端点（POST /v1/onboarding/verify-code）测试。

覆盖：
- 有效码 200（user_id / access_token / consent_versions / l0_decision / restricted）
- 无效码 404（不泄露租户存在性）
- restricted 用户 403；withdrawal_pending 等不可用状态 403
- 响应不含 tenant_id / role / external_ref 等内部字段
- 预认证语义：无 Authorization 头也可完成激活交换（激活前设备无凭证）
- 重复调用幂等（同一 user_id，不重复创建用户）
- code 长度越界 → 422
- 受保护端点无 token → 401（激活签发凭证是后续鉴权前提）
"""
from app.database import SessionLocal
from app.models import Consent, OnboardingScreening, User

ACTIVE_CODE = "ACTIV-CODE-0001"
RESTRICTED_CODE = "RESTR-CODE-0001"
WITHDRAWN_CODE = "WITHD-CODE-0001"


def _seed_user(user_id: str, external_ref: str, *, status: str = "active") -> None:
    with SessionLocal() as db:
        db.add(User(id=user_id, tenant_id="t_demo", external_ref=external_ref, status=status))
        db.commit()


def _seed_consent(user_id: str, consent_type: str, version: str = "test-v1") -> None:
    with SessionLocal() as db:
        db.add(Consent(
            id=f"c_{user_id}_{consent_type}",
            tenant_id="t_demo",
            user_id=user_id,
            consent_type=consent_type,
            version=version,
            granted=True,
            evidence_hash="0" * 64,
        ))
        db.commit()


def _seed_l0(user_id: str, decision: str = "eligible") -> None:
    with SessionLocal() as db:
        db.add(OnboardingScreening(
            event_id=f"evt_l0_{user_id}",
            tenant_id="t_demo",
            user_id=user_id,
            decision=decision,
        ))
        db.commit()


def test_verify_code_valid_returns_token_and_fields(client):
    _seed_user("u_activation", ACTIVE_CODE)
    _seed_consent("u_activation", "psychological_data", version="psy-v1")
    _seed_consent("u_activation", "passive_sensing", version="ps-v1")
    _seed_l0("u_activation", decision="eligible")

    response = client.post("/v1/onboarding/verify-code", json={"code": ACTIVE_CODE})
    assert response.status_code == 200
    body = response.json()
    assert body["user_id"] == "u_activation"
    assert body["access_token"]
    assert body["consent_versions"]["psychological_data"] == "psy-v1"
    assert body["consent_versions"]["passive_sensing"] == "ps-v1"
    assert body["l0_decision"] == "eligible"
    assert body["restricted"] is False


def test_verify_code_no_internal_fields_leaked(client):
    _seed_user("u_activation", ACTIVE_CODE)
    _seed_consent("u_activation", "psychological_data")
    response = client.post("/v1/onboarding/verify-code", json={"code": ACTIVE_CODE})
    assert response.status_code == 200
    body = response.json()
    # 内部字段绝不外泄；v0.7 订阅字段为公开契约（contract-manifest OnboardingVerifyOut.fields）
    assert set(body.keys()) == {
        "user_id", "access_token", "consent_versions", "l0_decision", "restricted",
        "subscription_expires_at", "subscription_plan",
    }
    for forbidden in ("tenant_id", "role", "external_ref", "bootstrap"):
        assert forbidden not in body, f"响应泄露内部字段: {forbidden}"


def test_verify_code_invalid_returns_404(client):
    response = client.post("/v1/onboarding/verify-code", json={"code": "NO-SUCH-CODE-99"})
    assert response.status_code == 404
    assert "无效激活码" in response.json()["detail"]


def test_verify_code_restricted_user_returns_403(client):
    _seed_user("u_restricted", RESTRICTED_CODE, status="restricted")
    response = client.post("/v1/onboarding/verify-code", json={"code": RESTRICTED_CODE})
    assert response.status_code == 403
    assert "已受限" in response.json()["detail"]


def test_verify_code_withdrawn_user_returns_403(client):
    _seed_user("u_withdrawn", WITHDRAWN_CODE, status="withdrawal_pending")
    response = client.post("/v1/onboarding/verify-code", json={"code": WITHDRAWN_CODE})
    assert response.status_code == 403


def test_verify_code_requires_no_token_pre_auth(client):
    """激活交换是预认证步骤：无 Authorization 头，有效码仍返回 200。"""
    _seed_user("u_activation", ACTIVE_CODE)
    _seed_consent("u_activation", "psychological_data")
    response = client.post("/v1/onboarding/verify-code", json={"code": ACTIVE_CODE})
    assert response.status_code == 200
    assert response.json()["user_id"] == "u_activation"


def test_verify_code_idempotent_repeat(client):
    """重复调用幂等：返回同一 user_id，不重复创建用户。"""
    _seed_user("u_activation", ACTIVE_CODE)
    _seed_consent("u_activation", "psychological_data")
    first = client.post("/v1/onboarding/verify-code", json={"code": ACTIVE_CODE})
    second = client.post("/v1/onboarding/verify-code", json={"code": ACTIVE_CODE})
    assert first.status_code == 200
    assert second.status_code == 200
    assert second.json()["user_id"] == first.json()["user_id"] == "u_activation"
    with SessionLocal() as db:
        count = db.query(User).filter(User.external_ref == ACTIVE_CODE).count()
        assert count == 1


def test_verify_code_code_length_validation(client):
    # 太短（<8）→ 422；太长（>80）→ 422
    short = client.post("/v1/onboarding/verify-code", json={"code": "short"})
    assert short.status_code == 422
    long = client.post("/v1/onboarding/verify-code", json={"code": "X" * 81})
    assert long.status_code == 422


def test_verify_code_issued_token_works_on_protected_endpoint(client):
    """签发凭证可访问受保护端点；无凭证访问受保护端点 → 401。"""
    _seed_user("u_activation", ACTIVE_CODE)
    _seed_consent("u_activation", "psychological_data")
    _seed_consent("u_activation", "passive_sensing")
    response = client.post("/v1/onboarding/verify-code", json={"code": ACTIVE_CODE})
    assert response.status_code == 200
    token = response.json()["access_token"]

    # 无 token → 401
    no_token = client.get("/v1/narratives", params={"user_id": "u_activation"})
    assert no_token.status_code == 401

    # 用签发 token → 200（叙事尚无数据 → 404 是业务语义，鉴权已通过）
    authed = client.get(
        "/v1/narratives",
        params={"user_id": "u_activation"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert authed.status_code in (200, 404)
