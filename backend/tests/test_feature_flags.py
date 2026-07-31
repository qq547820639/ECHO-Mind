"""P5 灰度回滚方案测试。

覆盖场景：
- flag 关闭后路由 410（passive_sensing / sandbox / skills_delivery 三个场景）
- admin 修改 flag（PUT /v1/tenant/flags）
- 用户拉取 flag（GET /v1/config/flags）
- 批量 retired（POST /v1/skills/batch-retire）
- 非 admin 修改 flag → 403
"""
from datetime import datetime, timezone

from app.auth import create_access_token
from app.database import SessionLocal
from app.models import Skill, Tenant


def _admin_headers() -> dict:
    return {"Authorization": f"Bearer {create_access_token('admin_demo', 't_demo', 'admin')}"}


def _professional_headers() -> dict:
    return {"Authorization": f"Bearer {create_access_token('pro', 't_demo', 'professional')}"}


def _set_flag(tenant_id: str, key: str, value: bool) -> None:
    """直接在 DB 层设置 flag，绕过路由用于测试前置。"""
    with SessionLocal() as db:
        tenant = db.get(Tenant, tenant_id)
        flags = dict(tenant.feature_flags or {})
        flags[key] = value
        tenant.feature_flags = flags
        db.commit()


def _make_skill(*, name: str, status: str = "reviewed", user_id: str = "u_demo") -> Skill:
    with SessionLocal() as db:
        skill = Skill(
            tenant_id="t_demo",
            user_id=user_id,
            name=name,
            version=1,
            trigger_conditions=[],
            guardrails=[],
            steps=[],
            status=status,
            content_hash="0" * 64,
        )
        db.add(skill)
        db.commit()
        db.refresh(skill)
        return skill


def _feature_payload(event_id: str, user_id: str = "u_demo") -> dict:
    start = datetime.now(timezone.utc).replace(hour=12, minute=0, second=0, microsecond=0)
    from datetime import timedelta
    return {
        "event_id": event_id,
        "user_id": user_id,
        "schema_version": "feat-v1",
        "source": "screen",
        "window_start": start.isoformat(),
        "window_end": (start + timedelta(minutes=30)).isoformat(),
        "summary": "屏幕使用平稳",
        "vector": [0.1, 0.2, 0.3],
    }


def _grant_passive_consent(client, user_headers) -> None:
    response = client.post("/v1/onboarding/consents", json={
        "user_id": "u_demo",
        "consent_type": "passive_sensing",
        "version": "test-v1",
        "granted": True,
        "evidence_hash": "a" * 64,
    }, headers=user_headers)
    assert response.status_code == 200


# ---------- P5.4 flag 关闭后路由 410 ----------

def test_passive_sensing_disabled_returns_410(client, user_headers, passive_sensing_consent):
    """passive_sensing_enabled=false 时 POST /v1/features/ingest 返回 410。"""
    _set_flag("t_demo", "passive_sensing_enabled", False)
    try:
        response = client.post(
            "/v1/features/ingest",
            json=_feature_payload("evt_p5_passive_0001"),
            headers=user_headers,
        )
        assert response.status_code == 410
    finally:
        _set_flag("t_demo", "passive_sensing_enabled", True)


def test_sandbox_disabled_returns_410_on_schedule(client):
    """sandbox_enabled=false 时 POST /v1/sandbox/runs 返回 410。"""
    _set_flag("t_demo", "sandbox_enabled", False)
    try:
        response = client.post(
            "/v1/sandbox/runs",
            json={"user_id": "u_demo"},
            headers=_admin_headers(),
        )
        assert response.status_code == 410
    finally:
        _set_flag("t_demo", "sandbox_enabled", True)


def test_sandbox_disabled_returns_410_on_get(client, admin_headers):
    """sandbox_enabled=false 时 GET /v1/sandbox/runs/{id} 返回 410。"""
    # 先在 flag 开启时创建一个 run
    created = client.post(
        "/v1/sandbox/runs",
        json={"user_id": "u_demo"},
        headers=admin_headers,
    )
    assert created.status_code == 200
    run_id = created.json()["id"]

    _set_flag("t_demo", "sandbox_enabled", False)
    try:
        response = client.get(f"/v1/sandbox/runs/{run_id}", headers=admin_headers)
        assert response.status_code == 410
    finally:
        _set_flag("t_demo", "sandbox_enabled", True)


def test_skills_delivery_disabled_returns_410_on_list(client, user_headers):
    """skills_delivery_enabled=false 时 GET /v1/skills 返回 410。"""
    _set_flag("t_demo", "skills_delivery_enabled", False)
    try:
        response = client.get("/v1/skills", headers=user_headers)
        assert response.status_code == 410
    finally:
        _set_flag("t_demo", "skills_delivery_enabled", True)


def test_skills_delivery_disabled_returns_410_on_detail(client, user_headers):
    """skills_delivery_enabled=false 时 GET /v1/skills/{id} 返回 410。"""
    skill = _make_skill(name="p5_detail_skill", status="reviewed")
    _set_flag("t_demo", "skills_delivery_enabled", False)
    try:
        response = client.get(f"/v1/skills/{skill.id}", headers=user_headers)
        assert response.status_code == 410
    finally:
        _set_flag("t_demo", "skills_delivery_enabled", True)


def test_flag_open_routes_work_normally(client, user_headers, passive_sensing_consent):
    """flag 全开时路由正常工作（回归保护）。"""
    response = client.post(
        "/v1/features/ingest",
        json=_feature_payload("evt_p5_normal_0001"),
        headers=user_headers,
    )
    assert response.status_code == 200


# ---------- P5.5 用户拉取 flag ----------

def test_user_can_fetch_flags(client, user_headers):
    """任何登录用户能 GET /v1/config/flags 拉取本租户 flags。"""
    response = client.get("/v1/config/flags", headers=user_headers)
    assert response.status_code == 200
    flags = response.json()
    assert flags["passive_sensing_enabled"] is True
    assert flags["sandbox_enabled"] is True
    assert flags["skills_delivery_enabled"] is True


def test_fetch_flags_requires_auth(client):
    """未认证 GET /v1/config/flags 返回 401。"""
    response = client.get("/v1/config/flags")
    assert response.status_code == 401


# ---------- P5.5 admin 修改 flag ----------

def test_admin_can_update_flag(client, admin_headers):
    """admin 能 PUT /v1/tenant/flags 修改 flag。"""
    response = client.put(
        "/v1/tenant/flags",
        json={"flag_key": "passive_sensing_enabled", "value": False},
        headers=admin_headers,
    )
    assert response.status_code == 200
    flags = response.json()
    assert flags["passive_sensing_enabled"] is False
    # 其他 flag 不受影响
    assert flags["sandbox_enabled"] is True
    assert flags["skills_delivery_enabled"] is True
    # 恢复
    client.put(
        "/v1/tenant/flags",
        json={"flag_key": "passive_sensing_enabled", "value": True},
        headers=admin_headers,
    )


def test_non_admin_cannot_update_flag(client, user_headers, professional_headers):
    """非 admin 修改 flag 返回 403。"""
    for headers in (user_headers, professional_headers):
        response = client.put(
            "/v1/tenant/flags",
            json={"flag_key": "passive_sensing_enabled", "value": False},
            headers=headers,
        )
        assert response.status_code == 403


def test_update_flag_records_audit(client, admin_headers):
    """PUT /v1/tenant/flags 应记录审计 action=tenant.flags.update。"""
    client.put(
        "/v1/tenant/flags",
        json={"flag_key": "sandbox_enabled", "value": False},
        headers=admin_headers,
    )
    events = client.get("/v1/audit/events?limit=20", headers=admin_headers).json()
    actions = [e["action"] for e in events]
    assert "tenant.flags.update" in actions
    # 恢复
    client.put(
        "/v1/tenant/flags",
        json={"flag_key": "sandbox_enabled", "value": True},
        headers=admin_headers,
    )


# ---------- P5.6 批量回滚 Skill ----------

def test_admin_can_batch_retire_skills(client, admin_headers):
    """admin 能 POST /v1/skills/batch-retire 批量回滚 Skill。"""
    s1 = _make_skill(name="batch_retire_1", status="reviewed")
    s2 = _make_skill(name="batch_retire_2", status="signed")
    _make_skill(name="batch_retire_3", status="reviewed")  # 不在列表中，不受影响

    response = client.post(
        "/v1/skills/batch-retire",
        json={"skill_ids": [s1.id, s2.id]},
        headers=admin_headers,
    )
    assert response.status_code == 200
    assert response.json()["retired"] == 2

    # 确认状态已是 retired
    with SessionLocal() as db:
        for sid in (s1.id, s2.id):
            row = db.get(Skill, sid)
            assert row.status == "retired"


def test_batch_retire_idempotent_for_already_retired(client, admin_headers):
    """已是 retired 的 Skill 幂等跳过，retired 计数不含已 retired 的。"""
    s1 = _make_skill(name="retire_already_1", status="reviewed")
    s2 = _make_skill(name="retire_already_2", status="retired")

    response = client.post(
        "/v1/skills/batch-retire",
        json={"skill_ids": [s1.id, s2.id]},
        headers=admin_headers,
    )
    assert response.status_code == 200
    assert response.json()["retired"] == 1  # 只有 s1 被新转


def test_batch_retire_cross_tenant_ignored(client, admin_headers):
    """跨租户的 skill_id 被忽略，不报错。"""
    # 在 t_other 创建一个 Skill
    with SessionLocal() as db:
        if not db.get(Tenant, "t_other"):
            db.add(Tenant(id="t_other", name="Other"))
            db.commit()
    with SessionLocal() as db:
        other_skill = Skill(
            tenant_id="t_other",
            user_id="u_other",
            name="cross_tenant_retire",
            version=1,
            trigger_conditions=[],
            guardrails=[],
            steps=[],
            status="reviewed",
            content_hash="0" * 64,
        )
        db.add(other_skill)
        db.commit()
        db.refresh(other_skill)
        other_id = other_skill.id

    # admin of t_demo 尝试 retire t_other 的 skill
    response = client.post(
        "/v1/skills/batch-retire",
        json={"skill_ids": [other_id]},
        headers=admin_headers,
    )
    assert response.status_code == 200
    assert response.json()["retired"] == 0


def test_non_admin_cannot_batch_retire(client, user_headers, professional_headers):
    """非 admin 调 batch-retire 返回 403。"""
    for headers in (user_headers, professional_headers):
        response = client.post(
            "/v1/skills/batch-retire",
            json={"skill_ids": ["sk_any"]},
            headers=headers,
        )
        assert response.status_code == 403


def test_batch_retire_records_audit(client, admin_headers):
    """batch-retire 应记录审计 action=skill.batch_retire。"""
    skill = _make_skill(name="retire_audit", status="reviewed")
    client.post(
        "/v1/skills/batch-retire",
        json={"skill_ids": [skill.id]},
        headers=admin_headers,
    )
    events = client.get("/v1/audit/events?limit=20", headers=admin_headers).json()
    actions = [e["action"] for e in events]
    assert "skill.batch_retire" in actions
