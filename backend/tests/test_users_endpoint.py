"""POST /v1/users 端点直接测试（T8-P2-8 补缺：此前全仓无该端点测试引用）。

锁定：
- admin/professional 可创建（user 落在 principal 自己的租户）；
- user 角色 403；
- external_ref 租户内唯一 → 409；
- UserCreate 契约校验（external_ref 长度）→ 422。
"""
from app.database import SessionLocal
from app.models import User


def _create(client, headers, **overrides):
    payload = {"external_ref": "stu_default_ref"}
    payload.update(overrides)
    return client.post("/v1/users", json=payload, headers=headers)


def test_admin_creates_active_user_in_own_tenant(client, admin_headers):
    resp = _create(client, admin_headers, external_ref="stu_create_001")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "active"
    with SessionLocal() as db:
        user = db.get(User, body["id"])
        assert user is not None, "用户必须真实落库（新 session 读回）"
        assert user.tenant_id == "t_demo", "租户取自 principal，不由客户端指定"
        assert user.external_ref == "stu_create_001"
        assert user.age_band == "18_plus"
        assert user.timezone == "Asia/Shanghai"


def test_professional_role_can_create(client, professional_headers):
    resp = _create(client, professional_headers, external_ref="stu_create_002")
    assert resp.status_code == 200


def test_user_role_forbidden(client, user_headers):
    resp = _create(client, user_headers, external_ref="stu_create_003")
    assert resp.status_code == 403


def test_duplicate_external_ref_in_tenant_conflicts(client, admin_headers):
    assert _create(client, admin_headers, external_ref="stu_dup_001").status_code == 200
    resp = _create(client, admin_headers, external_ref="stu_dup_001")
    assert resp.status_code == 409
    assert resp.json()["detail"] == "external_ref already exists"


def test_short_external_ref_rejected_by_contract(client, admin_headers):
    resp = _create(client, admin_headers, external_ref="x")
    assert resp.status_code == 422


def test_city_over_max_length_rejected(client, admin_headers):
    resp = _create(client, admin_headers, external_ref="stu_create_004", city="长" * 121)
    assert resp.status_code == 422
