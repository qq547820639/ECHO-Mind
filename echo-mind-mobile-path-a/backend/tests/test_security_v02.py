from datetime import datetime, timezone
from sqlalchemy import select
from app.auth import create_access_token
from app.database import SessionLocal
from app.models import Checkin, Consent, Tenant, User


def test_sensitive_checkin_note_encrypted_at_rest(client, user_headers):
    secret = "这是一段不应明文存储的心理记录"
    response = client.post("/v1/checkins", json={
        "event_id": "evt_encrypt_001", "user_id": "u_demo", "mood": 3, "stress": 3,
        "energy": 3, "sleep_recovery": 3, "note": secret,
        "client_time": datetime.now(timezone.utc).isoformat(), "device_timezone": "Asia/Shanghai",
    }, headers=user_headers)
    assert response.status_code == 200
    with SessionLocal() as db:
        row = db.scalar(select(Checkin).where(Checkin.event_id == "evt_encrypt_001"))
        assert row is not None
        assert secret not in row.note_ciphertext
        assert row.note_ciphertext.startswith("enc:v1:")


def test_same_event_id_is_isolated_by_tenant(client, user_headers):
    payload = {
        "event_id": "evt_same_across_tenants", "user_id": "u_demo", "mood": 3, "stress": 3,
        "energy": 3, "sleep_recovery": 3,
        "client_time": datetime.now(timezone.utc).isoformat(), "device_timezone": "Asia/Shanghai",
    }
    assert client.post("/v1/checkins", json=payload, headers=user_headers).status_code == 200
    with SessionLocal() as db:
        db.add(Tenant(id="t_second", name="Second"))
        db.add(User(id="u_second", tenant_id="t_second", external_ref="second"))
        db.add(Consent(id="c_second", tenant_id="t_second", user_id="u_second", consent_type="psychological_data", version="v1", granted=True, evidence_hash="b" * 64))
        db.commit()
    headers = {"Authorization": f"Bearer {create_access_token('u_second', 't_second', 'user')}"}
    payload["user_id"] = "u_second"
    second = client.post("/v1/checkins", json=payload, headers=headers)
    assert second.status_code == 200
    assert second.json().get("idempotent_replay") is not True


def test_security_headers_present(client):
    response = client.get("/health")
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["x-frame-options"] == "DENY"
    assert response.headers["cache-control"] == "no-store"
    assert response.headers.get("x-request-id")


def test_emergency_contact_requires_separate_consent_and_is_encrypted(client, user_headers):
    payload = {
        "user_id": "u_demo",
        "name": "可信联系人",
        "phone": "13800000000",
        "relationship": "家人",
    }
    denied = client.post("/v1/onboarding/emergency-contact", json=payload, headers=user_headers)
    assert denied.status_code == 412
    granted = client.post("/v1/onboarding/consents", json={
        "user_id": "u_demo",
        "consent_type": "emergency_contact",
        "version": "test-emergency-v1",
        "granted": True,
        "evidence_hash": "e" * 64,
    }, headers=user_headers)
    assert granted.status_code == 200
    created = client.post("/v1/onboarding/emergency-contact", json=payload, headers=user_headers)
    assert created.status_code == 200
    from app.models import EmergencyContact
    with SessionLocal() as db:
        row = db.scalar(select(EmergencyContact).where(EmergencyContact.user_id == "u_demo"))
        assert row is not None
        assert row.name_ciphertext.startswith("enc:v1:")
        assert row.phone_ciphertext.startswith("enc:v1:")
        assert "可信联系人" not in row.name_ciphertext
        assert "13800000000" not in row.phone_ciphertext
