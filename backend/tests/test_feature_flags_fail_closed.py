"""T03 Feature flag fail-closed 测试（v0.6）。

断言：
- 未知 key → passive_sensing_enabled / sandbox_enabled 为 false（skills_delivery 默认 true）
- tenant 不存在 → 敏感 flag false
- 字段缺失（租户存在但 JSON 无该键）→ 敏感 flag false
- 显式配置的租户不受影响（t_demo 三开关 true）
- require_feature_flag：未知 key/敏感 flag 关闭 → 410；租户不存在 → 放行由下游 404
"""
from datetime import UTC

from app.auth import create_access_token
from app.database import SessionLocal
from app.models import Tenant
from app.services.feature_flags import (
    DEFAULT_FEATURE_FLAGS,
    FAIL_CLOSED_DEFAULTS,
    get_tenant_flags,
    is_flag_enabled,
    tenant_exists,
)


def _user_headers() -> dict:
    return {"Authorization": f"Bearer {create_access_token('u_demo', 't_demo', 'user')}"}


def _outsider_headers() -> dict:
    return {"Authorization": f"Bearer {create_access_token('u_demo', 't_nonexistent', 'user')}"}


# ---------- 服务层 fail-closed ----------

def test_unknown_key_fails_closed():
    """未知 key：敏感 flag false，skills_delivery 可默认 true。"""
    with SessionLocal() as db:
        assert is_flag_enabled(db, "t_demo", "passive_sensing_enabled") is True  # 显式 true
        assert is_flag_enabled(db, "t_demo", "unknown_flag") is False
        assert is_flag_enabled(db, "t_demo", "sandbox_enabled") is True
        assert is_flag_enabled(db, "t_demo", "skills_delivery_enabled") is True


def test_missing_tenant_fails_closed():
    """tenant 不存在：敏感 flag false。"""
    with SessionLocal() as db:
        assert tenant_exists(db, "t_nonexistent") is False
        assert is_flag_enabled(db, "t_nonexistent", "passive_sensing_enabled") is False
        assert is_flag_enabled(db, "t_nonexistent", "sandbox_enabled") is False
        assert is_flag_enabled(db, "t_nonexistent", "skills_delivery_enabled") is True
        flags = get_tenant_flags(db, "t_nonexistent")
        assert flags == FAIL_CLOSED_DEFAULTS


def test_missing_field_fails_closed():
    """tenant 存在但 feature_flags 缺字段：敏感 flag false。"""
    with SessionLocal() as db:
        db.add(Tenant(id="t_partial", name="Partial"))
        db.commit()
    with SessionLocal() as db:
        tenant = db.get(Tenant, "t_partial")
        tenant.feature_flags = {"skills_delivery_enabled": True}  # 只配置非敏感项
        db.commit()
    with SessionLocal() as db:
        assert is_flag_enabled(db, "t_partial", "passive_sensing_enabled") is False
        assert is_flag_enabled(db, "t_partial", "sandbox_enabled") is False
        assert is_flag_enabled(db, "t_partial", "skills_delivery_enabled") is True


def test_explicitly_granted_tenant_keeps_sensitive_flags():
    """显式 grant 的租户（t_demo）不受 fail-closed 影响。"""
    with SessionLocal() as db:
        flags = get_tenant_flags(db, "t_demo")
        assert flags == DEFAULT_FEATURE_FLAGS
        assert is_flag_enabled(db, "t_demo", "passive_sensing_enabled") is True


# ---------- 路由层 ----------

def test_ingest_with_nonexistent_tenant_still_404_not_410(client):
    """租户不存在时 require_feature_flag 放行，由 ensure_user 返回 404（不泄露 flag）。"""
    from datetime import datetime, timedelta
    start = datetime.now(UTC).replace(hour=12, minute=0, second=0, microsecond=0)
    response = client.post("/v1/features/ingest", json={
        "event_id": "evt_fc_0001",
        "user_id": "u_demo",
        "schema_version": "passive-core-v1",
        "source": "screen",
        "window_start": start.isoformat(),
        "window_end": (start + timedelta(minutes=30)).isoformat(),
        "summary": "平稳",
        "vector": [0.1],
    }, headers=_outsider_headers())
    assert response.status_code == 404


def test_disabled_sensitive_flag_returns_410(client, user_headers):
    """passive_sensing_enabled=false → ingest 410。"""
    with SessionLocal() as db:
        tenant = db.get(Tenant, "t_demo")
        flags = dict(tenant.feature_flags or {})
        flags["passive_sensing_enabled"] = False
        tenant.feature_flags = flags
        db.commit()
    try:
        from datetime import datetime, timedelta
        start = datetime.now(UTC).replace(hour=12, minute=0, second=0, microsecond=0)
        response = client.post("/v1/features/ingest", json={
            "event_id": "evt_fc_0002",
            "user_id": "u_demo",
            "schema_version": "passive-core-v1",
            "source": "screen",
            "window_start": start.isoformat(),
            "window_end": (start + timedelta(minutes=30)).isoformat(),
            "summary": "平稳",
            "vector": [0.1],
        }, headers=user_headers)
        assert response.status_code == 410
    finally:
        with SessionLocal() as db:
            tenant = db.get(Tenant, "t_demo")
            flags = dict(tenant.feature_flags or {})
            flags["passive_sensing_enabled"] = True
            tenant.feature_flags = flags
            db.commit()


def test_config_flags_never_exposes_sensitive_on_missing_tenant(client):
    """GET /v1/config/flags：不存在租户的敏感 flag 为 false。"""
    response = client.get("/v1/config/flags", headers=_outsider_headers())
    assert response.status_code == 200
    flags = response.json()
    assert flags["passive_sensing_enabled"] is False
    assert flags["sandbox_enabled"] is False
    assert flags["skills_delivery_enabled"] is True


def test_config_flags_returns_defaults_for_granted_tenant(client, user_headers):
    """显式 grant 租户 GET /v1/config/flags 返回全部 true。"""
    response = client.get("/v1/config/flags", headers=user_headers)
    assert response.status_code == 200
    flags = response.json()
    assert flags["passive_sensing_enabled"] is True
    assert flags["sandbox_enabled"] is True
    assert flags["skills_delivery_enabled"] is True
