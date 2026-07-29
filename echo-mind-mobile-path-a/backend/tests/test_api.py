from datetime import datetime, timezone


def test_checkin_idempotency(client, user_headers):
    payload = {
        "event_id": "evt_checkin_0001", "user_id": "u_demo", "mood": 3, "stress": 2,
        "energy": 3, "sleep_recovery": 2, "event_flag": False, "help_requested": False,
        "client_time": datetime.now(timezone.utc).isoformat(), "device_timezone": "Asia/Shanghai"
    }
    first = client.post("/v1/checkins", json=payload, headers=user_headers)
    second = client.post("/v1/checkins", json=payload, headers=user_headers)
    assert first.status_code == 200
    assert second.json()["idempotent_replay"] is True


def test_user_cannot_submit_for_other_user(client, user_headers):
    payload = {
        "event_id": "evt_checkin_0002", "user_id": "u_other", "mood": 3, "stress": 2,
        "energy": 3, "sleep_recovery": 2, "client_time": datetime.now(timezone.utc).isoformat(),
        "device_timezone": "Asia/Shanghai"
    }
    response = client.post("/v1/checkins", json=payload, headers=user_headers)
    assert response.status_code == 403


def test_phq9_item9_creates_escalation(client, user_headers, staff_headers):
    response = client.post("/v1/questionnaires/phq9/responses", json={
        "event_id": "evt_phq_0001", "user_id": "u_demo", "answers": [0,0,0,0,0,0,0,0,1]
    }, headers=user_headers)
    assert response.status_code == 200
    assert response.json()["urgent_item"] is True
    queue = client.get("/v1/escalations", headers=staff_headers)
    assert queue.status_code == 200
    assert len(queue.json()) == 1


def test_escalation_requires_takeover_before_close(client, user_headers, professional_headers):
    opened = client.post("/v1/escalations", json={
        "event_id": "evt_esc_0001", "user_id": "u_demo", "level": "L3",
        "trigger": "help_requested", "evidence_summary": "用户主动求助"
    }, headers=user_headers)
    esc_id = opened.json()["id"]
    close = client.post(f"/v1/escalations/{esc_id}/close", json={"disposition": "已完成"}, headers=professional_headers)
    assert close.status_code == 409
    client.post(f"/v1/escalations/{esc_id}/takeover", headers=professional_headers)
    close = client.post(f"/v1/escalations/{esc_id}/close", json={
        "disposition": "已联系并按机构流程处理",
        "contact_method": "电话",
        "contact_succeeded": True,
        "safety_status": "情绪平稳",
        "emergency_contact_called": False,
        "referred_12356": True,
        "called_emergency_services": False,
        "follow_up_plan": "24 小时内随访",
        "operator_signature": "值班员甲",
    }, headers=professional_headers)
    assert close.status_code == 200
