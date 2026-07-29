from datetime import datetime, timezone
from pathlib import Path

import pytest
from sqlalchemy import create_engine, select, text

from app.auth import create_access_token
from app.config import get_settings
from app.database import SessionLocal
from app.models import AuditEvent, Escalation, RiskSignal
from app.services.audit import verify_audit_chain
from app.services.immutability import ImmutableRecordError

ROLES = ["user", "on_call", "professional", "auditor", "admin"]
BACKEND_DIR = Path(__file__).resolve().parents[1]


def headers_for(role: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(f'subj_{role}', 't_demo', role)}"}


def open_escalation(client, user_headers, event_id: str = "evt_esc_immutable") -> str:
    opened = client.post("/v1/escalations", json={
        "event_id": event_id, "user_id": "u_demo", "level": "L3",
        "trigger": "help_requested", "evidence_summary": "用户主动求助",
    }, headers=user_headers)
    assert opened.status_code == 200
    return opened.json()["id"]


def create_risk_signal(client, user_headers) -> str:
    response = client.post("/v1/safety/check", json={
        "user_id": "u_demo", "text": "今天情绪平稳，完成了散步",
    }, headers=user_headers)
    assert response.status_code == 200
    with SessionLocal() as db:
        signal = db.scalar(select(RiskSignal).order_by(RiskSignal.created_at.desc()).limit(1))
        assert signal is not None
        return signal.id


def audit_attempts(client, object_type: str, object_id: str) -> list[dict]:
    events = client.get("/v1/audit/events", headers=headers_for("auditor")).json()
    return [
        e for e in events
        if e["action"] == "security.immutable_mutation_attempt"
        and e["object_type"] == object_type
        and e["object_id"] == object_id
    ]


def test_any_role_cannot_delete_escalation_and_attempts_are_audited(client, user_headers):
    esc_id = open_escalation(client, user_headers)
    for role in ROLES:
        response = client.delete(f"/v1/escalations/{esc_id}", headers=headers_for(role))
        assert response.status_code == 405, role
    with SessionLocal() as db:
        assert db.get(Escalation, esc_id) is not None
    attempts = audit_attempts(client, "escalation", esc_id)
    assert len(attempts) == len(ROLES)
    assert {a["actor_type"] for a in attempts} == set(ROLES)


def test_tenant_admin_cannot_patch_escalation(client, user_headers):
    esc_id = open_escalation(client, user_headers)
    response = client.patch(
        f"/v1/escalations/{esc_id}",
        json={"status": "closed", "evidence_summary": "篡改"},
        headers=headers_for("admin"),
    )
    assert response.status_code == 405
    with SessionLocal() as db:
        row = db.get(Escalation, esc_id)
        assert row.status == "open"
        assert row.evidence_summary == "用户主动求助"
    assert audit_attempts(client, "escalation", esc_id)


def test_risk_signal_delete_and_patch_rejected(client, user_headers):
    signal_id = create_risk_signal(client, user_headers)
    deleted = client.delete(f"/v1/risk-signals/{signal_id}", headers=headers_for("admin"))
    patched = client.patch(f"/v1/risk-signals/{signal_id}", json={"severity": "green"}, headers=headers_for("admin"))
    assert deleted.status_code == 405
    assert patched.status_code == 405
    with SessionLocal() as db:
        assert db.get(RiskSignal, signal_id) is not None
    assert len(audit_attempts(client, "risk_signal", signal_id)) == 2


def test_audit_event_delete_and_patch_rejected_and_chain_stays_valid(client, user_headers):
    client.post("/v1/checkins", json={
        "event_id": "evt_audit_immutable", "user_id": "u_demo", "mood": 3, "stress": 3,
        "energy": 3, "sleep_recovery": 3,
        "client_time": datetime.now(timezone.utc).isoformat(), "device_timezone": "Asia/Shanghai",
    }, headers=user_headers)
    events = client.get("/v1/audit/events", headers=headers_for("auditor")).json()
    target = next(e for e in events if e["action"] == "checkin.create")
    deleted = client.delete(f"/v1/audit/events/{target['event_id']}", headers=headers_for("admin"))
    patched = client.patch(f"/v1/audit/events/{target['event_id']}", json={"action": "tampered"}, headers=headers_for("admin"))
    assert deleted.status_code == 405
    assert patched.status_code == 405
    with SessionLocal() as db:
        row = db.scalar(select(AuditEvent).where(AuditEvent.event_id == target["event_id"]))
        assert row is not None
        assert row.action == "checkin.create"
        assert verify_audit_chain(db, "t_demo")["valid"] is True
    assert len(audit_attempts(client, "audit_event", target["event_id"])) == 2


def test_orm_delete_escalation_blocked(client, user_headers):
    esc_id = open_escalation(client, user_headers)
    with SessionLocal() as db:
        row = db.get(Escalation, esc_id)
        db.delete(row)
        with pytest.raises(ImmutableRecordError):
            db.flush()
        db.rollback()
        assert db.get(Escalation, esc_id) is not None


def test_orm_update_risk_signal_blocked(client, user_headers):
    signal_id = create_risk_signal(client, user_headers)
    with SessionLocal() as db:
        row = db.get(RiskSignal, signal_id)
        original = row.severity
        row.severity = "red" if original != "red" else "green"
        with pytest.raises(ImmutableRecordError):
            db.flush()
        db.rollback()
        assert db.get(RiskSignal, signal_id).severity == original


def test_orm_update_audit_event_blocked(client, user_headers):
    open_escalation(client, user_headers)
    with SessionLocal() as db:
        row = db.scalar(select(AuditEvent).limit(1))
        assert row is not None
        row.action = "tampered"
        with pytest.raises(ImmutableRecordError):
            db.flush()
        db.rollback()
        assert verify_audit_chain(db, "t_demo")["valid"] is True


def test_orm_escalation_lifecycle_update_still_allowed(client, user_headers):
    esc_id = open_escalation(client, user_headers)
    with SessionLocal() as db:
        row = db.get(Escalation, esc_id)
        row.status = "acknowledged"
        row.ack_at = datetime.now(timezone.utc)
        row.assigned_to = "subj_professional"
        db.commit()
        assert db.get(Escalation, esc_id).status == "acknowledged"


def test_orm_escalation_fact_field_tamper_blocked(client, user_headers):
    esc_id = open_escalation(client, user_headers)
    with SessionLocal() as db:
        row = db.get(Escalation, esc_id)
        row.evidence_summary = "篡改证据摘要"
        with pytest.raises(ImmutableRecordError):
            db.flush()
        db.rollback()
        assert db.get(Escalation, esc_id).evidence_summary == "用户主动求助"


def test_migration_replays_on_sqlite(tmp_path, monkeypatch):
    from alembic import command
    from alembic.config import Config

    db_file = tmp_path / "migration_replay.db"
    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{db_file}")
    get_settings.cache_clear()
    try:
        cfg = Config(str(BACKEND_DIR / "alembic.ini"))
        cfg.set_main_option("script_location", str(BACKEND_DIR / "alembic"))
        command.upgrade(cfg, "head")
        with create_engine(f"sqlite:///{db_file}").connect() as conn:
            tables = {
                row[0]
                for row in conn.execute(text("SELECT name FROM sqlite_master WHERE type='table'"))
            }
            head = conn.execute(text("SELECT version_num FROM alembic_version")).scalar_one()
        assert {"escalations", "risk_signals", "audit_events"} <= tables
        assert head == "20260729_0004"
        command.downgrade(cfg, "base")
        with create_engine(f"sqlite:///{db_file}").connect() as conn:
            remaining = conn.execute(
                text("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='escalations'")
            ).scalar_one()
        assert remaining == 0
        command.upgrade(cfg, "head")
    finally:
        get_settings.cache_clear()
