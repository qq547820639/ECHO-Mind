from datetime import datetime, timezone

import pytest

from app.auth import ALLOWED_ROLES, create_access_token
from app.database import SessionLocal
from app.models import JournalEntry
from app.services.crypto import encrypt_text

ALL_ROLES = [
    "user",
    "on_call",
    "professional",
    "auditor",
    "admin",
    "quality_reviewer",
    "security_auditor",
    "vendor_support",
]

# 队列元数据（不含证据摘要）对值班/专业/管理/审计类角色开放。
QUEUE_ROLES = {"on_call", "professional", "admin", "auditor", "quality_reviewer", "security_auditor"}


def role_headers(role: str, subject: str = "rbac_subject", step_up: bool = False) -> dict:
    return {"Authorization": f"Bearer {create_access_token(subject, 't_demo', role, step_up=step_up)}"}


def open_escalation(client, user_headers, event_id: str = "evt_rbac_esc") -> str:
    opened = client.post("/v1/escalations", json={
        "event_id": event_id, "user_id": "u_demo", "level": "L3",
        "trigger": "help_requested", "evidence_summary": "用户主动求助，存在自伤表述",
    }, headers=user_headers)
    assert opened.status_code == 200
    return opened.json()["id"]


def audit_actions(client) -> list[str]:
    events = client.get("/v1/audit/events", headers=role_headers("auditor"))
    assert events.status_code == 200
    return [event["action"] for event in events.json()]


def test_eight_role_matrix_issues_tokens():
    assert set(ALL_ROLES) == ALLOWED_ROLES
    for role in ALL_ROLES:
        assert create_access_token("subject", "t_demo", role)
    with pytest.raises(ValueError):
        create_access_token("subject", "t_demo", "superuser")


@pytest.mark.parametrize("role", ALL_ROLES)
def test_escalation_queue_access_matrix(client, role):
    response = client.get("/v1/escalations", headers=role_headers(role))
    if role in QUEUE_ROLES:
        assert response.status_code == 200
    else:
        assert response.status_code == 403


def test_tenant_admin_cannot_read_psychological_content(client, user_headers):
    with SessionLocal() as db:
        db.add(JournalEntry(
            event_id="evt_rbac_journal",
            tenant_id="t_demo",
            user_id="u_demo",
            logical_id="journal_rbac_001",
            revision=1,
            body_ciphertext=encrypt_text("这是一段心理对话正文", aad="t_demo:u_demo:journal"),
            event_tags=[],
            client_time=datetime.now(timezone.utc),
        ))
        db.commit()
    admin = role_headers("admin")
    assert client.get("/v1/journals?user_id=u_demo", headers=admin).status_code == 403
    answers = client.post("/v1/questionnaires/phq9/responses", json={
        "event_id": "evt_rbac_phq9", "user_id": "u_demo", "answers": [0] * 9,
    }, headers=admin)
    assert answers.status_code == 403
    # 租户管理员仍可访问队列元数据。
    assert client.get("/v1/escalations", headers=admin).status_code == 200
    # 用户本人与专业人员不受此限制。
    assert client.get("/v1/journals?user_id=u_demo", headers=user_headers).status_code == 200
    assert client.get("/v1/journals?user_id=u_demo", headers=role_headers("professional")).status_code == 200


def test_vendor_support_has_no_data_access(client):
    vendor = role_headers("vendor_support")
    assert client.get("/v1/journals?user_id=u_demo", headers=vendor).status_code == 403
    assert client.get("/v1/onboarding/consents/latest?user_id=u_demo", headers=vendor).status_code == 403
    assert client.get("/v1/escalations", headers=vendor).status_code == 403
    assert client.get("/v1/audit/events", headers=vendor).status_code == 403
    write = client.post("/v1/checkins", json={
        "event_id": "evt_rbac_vendor", "user_id": "u_demo", "mood": 3, "stress": 3,
        "energy": 3, "sleep_recovery": 3,
        "client_time": datetime.now(timezone.utc).isoformat(), "device_timezone": "Asia/Shanghai",
    }, headers=vendor)
    assert write.status_code == 403
    # 系统健康/版本类非敏感信息保持可访问。
    assert client.get("/health").status_code == 200


def test_security_auditor_is_read_only(client, user_headers):
    esc_id = open_escalation(client, user_headers)
    auditor = role_headers("security_auditor")
    assert client.get("/v1/escalations", headers=auditor).status_code == 200
    assert client.get("/v1/escalations/metrics", headers=auditor).status_code == 200
    assert client.get("/v1/audit/events", headers=auditor).status_code == 200
    assert client.get("/v1/audit/verify", headers=auditor).status_code == 200
    write = client.post("/v1/onboarding/consents", json={
        "user_id": "u_demo", "consent_type": "psychological_data", "version": "rbac-v1",
        "granted": True, "evidence_hash": "0" * 64,
    }, headers=auditor)
    assert write.status_code == 403
    assert client.post(f"/v1/escalations/{esc_id}/ack", headers=auditor).status_code == 403


def test_duty_staff_limited_to_queue_metadata(client, user_headers):
    on_call = role_headers("on_call")
    assert client.get("/v1/journals?user_id=u_demo", headers=on_call).status_code == 403
    open_escalation(client, user_headers, "evt_rbac_duty")
    queue = client.get("/v1/escalations", headers=on_call)
    assert queue.status_code == 200
    assert len(queue.json()) == 1


def test_escalation_evidence_requires_step_up(client, user_headers):
    esc_id = open_escalation(client, user_headers)
    denied = client.get(f"/v1/escalations/{esc_id}", headers=role_headers("professional"))
    assert denied.status_code == 403
    queue = client.get("/v1/escalations", headers=role_headers("on_call"))
    assert queue.status_code == 200
    assert queue.json()[0]["evidence_summary"] is None
    allowed = client.get(f"/v1/escalations/{esc_id}", headers=role_headers("professional", step_up=True))
    assert allowed.status_code == 200
    assert allowed.json()["evidence_summary"]
    stepped_queue = client.get("/v1/escalations", headers=role_headers("on_call", step_up=True))
    assert stepped_queue.json()[0]["evidence_summary"]


def test_step_up_denial_is_audited(client, user_headers):
    esc_id = open_escalation(client, user_headers)
    response = client.get(f"/v1/escalations/{esc_id}", headers=role_headers("professional"))
    assert response.status_code == 403
    assert "authz.step_up_denied" in audit_actions(client)


def test_unauthorized_attempts_are_audited(client):
    assert client.get("/v1/journals?user_id=u_demo", headers=role_headers("admin")).status_code == 403
    assert client.get("/v1/journals?user_id=u_demo", headers=role_headers("vendor_support")).status_code == 403
    write = client.post("/v1/onboarding/consents", json={
        "user_id": "u_demo", "consent_type": "psychological_data", "version": "rbac-v1",
        "granted": True, "evidence_hash": "0" * 64,
    }, headers=role_headers("security_auditor"))
    assert write.status_code == 403
    actions = audit_actions(client)
    assert "authz.psych_content_denied" in actions
    assert "authz.write_denied" in actions
    verify = client.get("/v1/audit/verify", headers=role_headers("auditor"))
    assert verify.status_code == 200
    assert verify.json()["valid"] is True
