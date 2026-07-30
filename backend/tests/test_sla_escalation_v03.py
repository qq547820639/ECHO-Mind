from datetime import datetime, timedelta, timezone

from sqlalchemy import select, text

from app.auth import create_access_token
from app.config import get_settings
from app.database import SessionLocal, engine
from app.models import AuditEvent, Escalation
from app.services.audit import verify_audit_chain

SETTINGS = get_settings()
ACK_SLA = SETTINGS.ack_sla_seconds
TAKEOVER_SLA = SETTINGS.takeover_sla_seconds
ORG_LEAD_SLA = SETTINGS.org_lead_sla_seconds


def headers_for(role: str, subject: str | None = None) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject or f'subj_{role}', 't_demo', role)}"}


def open_red_escalation(client, user_headers, event_id: str = "evt_sla_esc") -> str:
    opened = client.post("/v1/escalations", json={
        "event_id": event_id, "user_id": "u_demo", "level": "L3",
        "trigger": "help_requested", "evidence_summary": "用户主动求助",
    }, headers=user_headers)
    assert opened.status_code == 200
    return opened.json()["id"]


def backdate_opened(escalation_id: str, seconds: float) -> None:
    """直连库把时间回拨（绕过 ORM 守卫），模拟事件已存在指定秒数。"""
    opened = datetime.now(timezone.utc) - timedelta(seconds=seconds)
    with engine.begin() as conn:
        conn.execute(
            text("UPDATE escalations SET opened_at = :opened WHERE id = :id"),
            {"opened": opened.strftime("%Y-%m-%d %H:%M:%S.%f"), "id": escalation_id},
        )


def run_scan(client, role: str = "on_call") -> dict:
    response = client.post("/v1/escalations/sla-scan", headers=headers_for(role))
    assert response.status_code == 200, response.text
    return response.json()


def escalation_row(escalation_id: str) -> Escalation:
    with SessionLocal() as db:
        return db.get(Escalation, escalation_id)


def audit_actions_for(escalation_id: str) -> list[str]:
    with SessionLocal() as db:
        rows = db.scalars(select(AuditEvent).where(
            AuditEvent.object_type == "escalation",
            AuditEvent.object_id == escalation_id,
        )).all()
        return [row.action for row in rows]


def test_delivery_confirmed_at_set_on_create(client, user_headers):
    esc_id = open_red_escalation(client, user_headers)
    row = escalation_row(esc_id)
    assert row.delivery_confirmed_at is not None
    assert row.escalation_level == 0
    assert row.notified_l1_at is None
    assert row.notified_l2_at is None
    assert row.chain_broken_at is None


def test_ack_sla_breach_escalates_to_second_duty(client, user_headers):
    esc_id = open_red_escalation(client, user_headers)
    backdate_opened(esc_id, ACK_SLA + 1)
    summary = run_scan(client)
    assert esc_id in summary["notified_second_duty"]
    assert summary["notified_org_lead"] == []
    assert summary["chain_broken"] == []
    row = escalation_row(esc_id)
    assert row.escalation_level == 1
    assert row.notified_l1_at is not None
    assert row.notified_l2_at is None
    assert row.chain_broken_at is None
    assert "notify.second_duty" in audit_actions_for(esc_id)


def test_second_tier_breach_escalates_to_org_lead(client, user_headers):
    esc_id = open_red_escalation(client, user_headers)
    backdate_opened(esc_id, TAKEOVER_SLA + 1)
    summary = run_scan(client)
    assert esc_id in summary["notified_second_duty"]
    assert esc_id in summary["notified_org_lead"]
    row = escalation_row(esc_id)
    assert row.escalation_level == 2
    assert row.notified_l1_at is not None
    assert row.notified_l2_at is not None
    assert row.chain_broken_at is None
    actions = audit_actions_for(esc_id)
    assert "notify.second_duty" in actions
    assert "notify.org_lead" in actions


def test_full_chain_timeout_marks_chain_broken_and_is_auditable(client, user_headers):
    esc_id = open_red_escalation(client, user_headers)
    backdate_opened(esc_id, ORG_LEAD_SLA + 1)
    summary = run_scan(client)
    assert esc_id in summary["chain_broken"]
    row = escalation_row(esc_id)
    assert row.escalation_level == 2
    assert row.chain_broken_at is not None
    assert "escalation.chain_broken" in audit_actions_for(esc_id)
    with SessionLocal() as db:
        assert verify_audit_chain(db, "t_demo")["valid"] is True


def test_ladder_progresses_tier_by_tier(client, user_headers):
    esc_id = open_red_escalation(client, user_headers)
    backdate_opened(esc_id, ACK_SLA + 1)
    run_scan(client)
    row = escalation_row(esc_id)
    assert row.escalation_level == 1
    first_l1 = row.notified_l1_at
    backdate_opened(esc_id, TAKEOVER_SLA + 1)
    run_scan(client)
    row = escalation_row(esc_id)
    assert row.escalation_level == 2
    assert row.notified_l1_at == first_l1
    assert row.chain_broken_at is None
    backdate_opened(esc_id, ORG_LEAD_SLA + 1)
    run_scan(client)
    row = escalation_row(esc_id)
    assert row.chain_broken_at is not None
    assert row.notified_l1_at == first_l1


def test_acked_escalation_never_escalates(client, user_headers, staff_headers):
    esc_id = open_red_escalation(client, user_headers)
    acked = client.post(f"/v1/escalations/{esc_id}/ack", headers=staff_headers)
    assert acked.status_code == 200
    backdate_opened(esc_id, ORG_LEAD_SLA + 1)
    summary = run_scan(client)
    assert summary["notified_second_duty"] == []
    assert summary["notified_org_lead"] == []
    assert summary["chain_broken"] == []
    row = escalation_row(esc_id)
    assert row.escalation_level == 0
    assert row.notified_l1_at is None
    assert row.notified_l2_at is None
    assert row.chain_broken_at is None


def test_notification_is_not_takeover(client, user_headers):
    esc_id = open_red_escalation(client, user_headers)
    backdate_opened(esc_id, ORG_LEAD_SLA + 1)
    run_scan(client)
    row = escalation_row(esc_id)
    # 即使已通知全部层级并标记链路失效，接管状态必须保持不变。
    assert row.notified_l1_at is not None
    assert row.notified_l2_at is not None
    assert row.chain_broken_at is not None
    assert row.status == "open"
    assert row.ack_at is None
    assert row.takeover_at is None
    status = client.get(f"/v1/escalations/{esc_id}/user-status", headers=user_headers)
    assert status.status_code == 200
    assert status.json()["human_acknowledged"] is False
    assert status.json()["dial_entry_visible"] is True


def test_user_status_semantics(client, user_headers, professional_headers):
    esc_id = open_red_escalation(client, user_headers)
    before = client.get(f"/v1/escalations/{esc_id}/user-status", headers=user_headers)
    assert before.status_code == 200
    assert before.json() == {
        "escalation_id": esc_id,
        "delivery_confirmed": True,
        "human_acknowledged": False,
        "dial_entry_visible": True,
        "chain_broken": False,
    }
    taken = client.post(f"/v1/escalations/{esc_id}/takeover", headers=professional_headers)
    assert taken.status_code == 200
    after = client.get(f"/v1/escalations/{esc_id}/user-status", headers=user_headers)
    assert after.json()["human_acknowledged"] is True
    assert after.json()["dial_entry_visible"] is False


def test_user_status_forbidden_for_other_user_and_auditor(client, user_headers):
    esc_id = open_red_escalation(client, user_headers)
    other = client.get(
        f"/v1/escalations/{esc_id}/user-status",
        headers=headers_for("user", subject="u_other"),
    )
    assert other.status_code == 403
    auditor = client.get(f"/v1/escalations/{esc_id}/user-status", headers=headers_for("auditor"))
    assert auditor.status_code == 403


def test_scan_is_idempotent(client, user_headers):
    esc_id = open_red_escalation(client, user_headers)
    backdate_opened(esc_id, ACK_SLA + 1)
    first = run_scan(client)
    assert esc_id in first["notified_second_duty"]
    notified_at = escalation_row(esc_id).notified_l1_at
    second = run_scan(client)
    assert second["notified_second_duty"] == []
    assert second["notified_org_lead"] == []
    assert second["chain_broken"] == []
    assert escalation_row(esc_id).notified_l1_at == notified_at
    assert audit_actions_for(esc_id).count("notify.second_duty") == 1


def test_sla_scan_role_restricted(client, user_headers):
    denied = client.post("/v1/escalations/sla-scan", headers=user_headers)
    assert denied.status_code == 403
    allowed = client.post("/v1/escalations/sla-scan", headers=headers_for("admin"))
    assert allowed.status_code == 200
