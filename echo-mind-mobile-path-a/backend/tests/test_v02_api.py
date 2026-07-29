from datetime import datetime, timezone
from app.auth import create_access_token


def test_l0_current_danger_opens_escalation(client, user_headers, staff_headers):
    response = client.post("/v1/onboarding/l0", json={
        "event_id": "evt_l0_danger_1",
        "user_id": "u_demo",
        "current_danger": True,
    }, headers=user_headers)
    assert response.status_code == 200
    assert response.json()["decision"] == "urgent_human"
    assert response.json()["escalation_id"]
    queue = client.get("/v1/escalations", headers=staff_headers)
    assert len(queue.json()) == 1


def test_journal_revision_and_tombstone(client, user_headers):
    created = client.post("/v1/journals", json={
        "event_id": "evt_journal_001",
        "user_id": "u_demo",
        "logical_id": "journal_logical_001",
        "body": "今天完成了一次散步",
        "event_tags": ["activity"],
        "client_time": datetime.now(timezone.utc).isoformat(),
    }, headers=user_headers)
    assert created.status_code == 200
    revised = client.post("/v1/journals/journal_logical_001/revisions", json={
        "event_id": "evt_journal_002",
        "body": "今天完成了一次十分钟散步",
        "event_tags": ["activity", "outdoor"],
        "client_time": datetime.now(timezone.utc).isoformat(),
    }, headers=user_headers)
    assert revised.json()["revision"] == 2
    rows = client.get("/v1/journals?user_id=u_demo", headers=user_headers).json()
    assert rows[0]["body"] == "今天完成了一次十分钟散步"
    deleted = client.delete("/v1/journals/journal_logical_001", headers=user_headers)
    assert deleted.status_code == 200
    assert client.get("/v1/journals?user_id=u_demo", headers=user_headers).json() == []


def test_journal_red_signal_opens_escalation(client, user_headers):
    response = client.post("/v1/journals", json={
        "event_id": "evt_journal_red_1",
        "user_id": "u_demo",
        "logical_id": "journal_logical_red_1",
        "body": "我已经准备好告别，今晚结束生命",
        "client_time": datetime.now(timezone.utc).isoformat(),
    }, headers=user_headers)
    assert response.status_code == 200
    assert response.json()["safety"]["severity"] == "red"
    assert response.json()["escalation_id"]


def test_consent_revocation_blocks_checkin(client, user_headers):
    revoke = client.post("/v1/onboarding/consents", json={
        "user_id": "u_demo", "consent_type": "psychological_data", "version": "test-v2",
        "granted": False, "evidence_hash": "a" * 64,
    }, headers=user_headers)
    assert revoke.status_code == 200
    response = client.post("/v1/checkins", json={
        "event_id": "evt_checkin_revoked", "user_id": "u_demo", "mood": 3, "stress": 3,
        "energy": 3, "sleep_recovery": 3,
        "client_time": datetime.now(timezone.utc).isoformat(), "device_timezone": "Asia/Shanghai",
    }, headers=user_headers)
    assert response.status_code == 412


def test_bootstrap_key_required_for_tenant(client):
    assert client.post("/v1/tenants", json={"name": "Nope"}).status_code == 403
    ok = client.post("/v1/tenants", json={"name": "Pilot"}, headers={"X-Bootstrap-Key": "local-bootstrap-only"})
    assert ok.status_code == 200


def test_escalation_review_state(client, user_headers, professional_headers):
    opened = client.post("/v1/escalations", json={
        "event_id": "evt_esc_review", "user_id": "u_demo", "level": "L3",
        "trigger": "help_requested", "evidence_summary": "用户主动求助",
    }, headers=user_headers)
    esc_id = opened.json()["id"]
    client.post(f"/v1/escalations/{esc_id}/takeover", headers=professional_headers)
    client.post(f"/v1/escalations/{esc_id}/close", json={
        "disposition": "已按流程联系",
        "contact_method": "电话",
        "contact_succeeded": True,
        "safety_status": "情绪平稳",
        "emergency_contact_called": False,
        "referred_12356": True,
        "called_emergency_services": False,
        "follow_up_plan": "24 小时内随访",
        "operator_signature": "值班员甲",
    }, headers=professional_headers)
    reviewed = client.post(f"/v1/escalations/{esc_id}/review", json={"review_notes": "流程符合要求"}, headers=professional_headers)
    assert reviewed.status_code == 200
    assert reviewed.json()["status"] == "reviewed"


def test_audit_chain_verifies(client, user_headers):
    client.post("/v1/checkins", json={
        "event_id": "evt_audit_checkin", "user_id": "u_demo", "mood": 3, "stress": 3,
        "energy": 3, "sleep_recovery": 3,
        "client_time": datetime.now(timezone.utc).isoformat(), "device_timezone": "Asia/Shanghai",
    }, headers=user_headers)
    auditor = {"Authorization": f"Bearer {create_access_token('audit', 't_demo', 'auditor')}"}
    result = client.get("/v1/audit/verify", headers=auditor)
    assert result.status_code == 200
    assert result.json()["valid"] is True


def test_data_subject_request_workflow(client, user_headers):
    created = client.post("/v1/data-subject-requests", json={
        "event_id": "evt_dsr_001", "user_id": "u_demo", "request_type": "export"
    }, headers=user_headers)
    assert created.status_code == 200
    admin = {"Authorization": f"Bearer {create_access_token('admin', 't_demo', 'admin')}"}
    listed = client.get("/v1/data-subject-requests", headers=admin)
    assert len(listed.json()) == 1
    done = client.post(f"/v1/data-subject-requests/{created.json()['id']}/complete", json={
        "result_summary": "已通过受控渠道提供导出文件"
    }, headers=admin)
    assert done.json()["status"] == "completed"
