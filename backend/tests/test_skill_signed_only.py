"""T03 契约：Skill signed-only 下发 + 完成上报（v0.6）。

覆盖：
- reviewed 不再下发给普通用户（列表 + 详情）
- signed 正常下发（列表 + 详情），治理字段 signed_by/signed_at 透出
- POST /v1/skills/completions：signed 校验、归属校验、tenant+event_id 幂等、
  审计 action=skill.completion
- 非 signed（draft/reviewed/retired）Skill 上报 → 409
"""
from datetime import UTC, datetime

from app.auth import create_access_token
from app.database import SessionLocal
from app.models import Skill, SkillCompletion


def _admin_headers() -> dict:
    return {"Authorization": f"Bearer {create_access_token('admin', 't_demo', 'admin')}"}


def _professional_headers() -> dict:
    return {"Authorization": f"Bearer {create_access_token('pro', 't_demo', 'professional')}"}


def _make_skill(*, name: str, status: str = "signed", user_id: str = "u_demo") -> Skill:
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
            signed_by="professional:pro" if status == "signed" else None,
            signed_at=datetime.now(UTC) if status == "signed" else None,
        )
        db.add(skill)
        db.commit()
        db.refresh(skill)
        return skill


def _sign_skill(client, skill_id: str) -> None:
    """draft→reviewed→signed 完整签名（含 policy_version + review_evidence）。"""
    r1 = client.post(f"/v1/skills/{skill_id}/transition",
                     json={"new_status": "reviewed"}, headers=_admin_headers())
    assert r1.status_code == 200
    r2 = client.post(f"/v1/skills/{skill_id}/transition",
                     json={"new_status": "signed",
                           "policy_version": "policy-2026.08",
                           "review_evidence": {"reviewer": "pro", "notes": "ok"}},
                     headers=_professional_headers())
    assert r2.status_code == 200


def test_sign_requires_policy_version_and_review_evidence(client):
    """契约点 5：转入 signed 缺 policy_version/review_evidence → 422。"""
    skill = _make_skill(name="sign_validation", status="draft")
    client.post(f"/v1/skills/{skill.id}/transition",
                json={"new_status": "reviewed"}, headers=_admin_headers())
    r_missing = client.post(f"/v1/skills/{skill.id}/transition",
                            json={"new_status": "signed"}, headers=_professional_headers())
    assert r_missing.status_code == 422
    r_partial = client.post(f"/v1/skills/{skill.id}/transition",
                            json={"new_status": "signed", "policy_version": "p1"},
                            headers=_professional_headers())
    assert r_partial.status_code == 422


def test_reviewed_skill_not_delivered_to_plain_user(client, user_headers):
    """reviewed 不下发：列表与详情均不可见（signed-only）。"""
    reviewed = _make_skill(name="only_reviewed", status="reviewed")
    signed = _make_skill(name="only_signed", status="signed")

    response = client.get("/v1/skills", headers=user_headers)
    assert response.status_code == 200
    names = [item["name"] for item in response.json()["skills"]]
    assert "only_signed" in names
    assert "only_reviewed" not in names

    detail = client.get(f"/v1/skills/{reviewed.id}", headers=user_headers)
    assert detail.status_code == 404
    detail_signed = client.get(f"/v1/skills/{signed.id}", headers=user_headers)
    assert detail_signed.status_code == 200
    assert detail_signed.json()["status"] == "signed"


def test_signed_skill_exposes_governance_fields(client, user_headers):
    """signed Skill 下发含 signed_by / signed_at 治理字段。"""
    skill = _make_skill(name="signed_governance", status="signed")
    response = client.get(f"/v1/skills/{skill.id}", headers=user_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["signed_by"]
    assert body["signed_at"] is not None
    # 内部字段仍脱敏
    assert "content_hash" not in body
    assert "tenant_id" not in body


def test_transition_to_signed_writes_governance_fields(client):
    """transition 到 signed 写入 signed_by/signed_at。"""
    skill = _make_skill(name="sign_flow", status="draft")
    _sign_skill(client, skill.id)
    with SessionLocal() as db:
        row = db.get(Skill, skill.id)
        assert row.status == "signed"
        assert row.signed_by
        assert row.signed_at is not None


def test_completion_requires_signed_skill(client, user_headers):
    """非 signed Skill 上报完成 → 409。"""
    for status in ("draft", "reviewed", "retired"):
        skill = _make_skill(name=f"completion_{status}", status=status)
        response = client.post("/v1/skills/completions", json={
            "event_id": f"evt_comp_{status}_0001",
            "user_id": "u_demo",
            "skill_id": skill.id,
            "status": "completed",
            "duration_seconds": 60,
            "client_time": datetime.now(UTC).isoformat(),
        }, headers=user_headers)
        assert response.status_code == 409, status


def test_completion_signed_ok_and_idempotent(client, user_headers):
    """signed Skill 上报成功且 tenant+event_id 幂等。"""
    skill = _make_skill(name="completion_signed", status="signed")
    payload = {
        "event_id": "evt_comp_signed_0001",
        "user_id": "u_demo",
        "skill_id": skill.id,
        "status": "completed",
        "duration_seconds": 120,
        "client_time": datetime.now(UTC).isoformat(),
    }
    first = client.post("/v1/skills/completions", json=payload, headers=user_headers)
    assert first.status_code == 200
    assert first.json()["idempotent_replay"] is False

    replay = client.post("/v1/skills/completions", json=payload, headers=user_headers)
    assert replay.status_code == 200
    assert replay.json()["id"] == first.json()["id"]
    assert replay.json()["idempotent_replay"] is True

    with SessionLocal() as db:
        rows = db.query(SkillCompletion).filter(
            SkillCompletion.tenant_id == "t_demo",
            SkillCompletion.event_id == "evt_comp_signed_0001",
        ).all()
        assert len(rows) == 1
        assert rows[0].skill_id == skill.id
        assert rows[0].status == "completed"
        assert rows[0].duration_seconds == 120


def test_completion_cross_tenant_skill_404(client, user_headers):
    """skill 不属于当前用户/租户 → 404。"""
    skill = _make_skill(name="comp_other_user", user_id="u_other", status="signed")
    response = client.post("/v1/skills/completions", json={
        "event_id": "evt_comp_cross_0001",
        "user_id": "u_demo",
        "skill_id": skill.id,
        "status": "started",
        "duration_seconds": 0,
        "client_time": datetime.now(UTC).isoformat(),
    }, headers=user_headers)
    assert response.status_code == 404


def test_completion_audit_written(client, user_headers, auditor_headers):
    """上报成功记录审计 action=skill.completion。"""
    skill = _make_skill(name="comp_audit", status="signed")
    client.post("/v1/skills/completions", json={
        "event_id": "evt_comp_audit_0001",
        "user_id": "u_demo",
        "skill_id": skill.id,
        "status": "stopped",
        "duration_seconds": 30,
        "client_time": datetime.now(UTC).isoformat(),
    }, headers=user_headers)
    events = client.get("/v1/audit/events?limit=20", headers=auditor_headers).json()
    actions = [e["action"] for e in events]
    assert "skill.completion" in actions


def test_completion_requires_auth(client):
    response = client.post("/v1/skills/completions", json={
        "event_id": "evt_comp_noauth_0001",
        "user_id": "u_demo",
        "skill_id": "sk_any",
        "status": "started",
        "duration_seconds": 0,
        "client_time": datetime.now(UTC).isoformat(),
    })
    assert response.status_code == 401
