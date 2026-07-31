from datetime import datetime, timezone
from pathlib import Path

from app.auth import create_access_token
from app.database import SessionLocal
from app.models import Checkin, EmergencyContact, Escalation, QuestionnaireResult, RiskSignal, User
from app.services.crypto import encrypt_text
from app.services.safety import RULE_PACK_VERSION
from app.services.scoring import score_phq9

BACKEND_DIR = Path(__file__).resolve().parents[1]
CONSOLE_HTML = BACKEND_DIR / "app" / "templates" / "console.html"

FULL_CLOSE_PAYLOAD = {
    "disposition": "已联系用户，情况稳定，转入随访",
    "contact_method": "电话",
    "contact_succeeded": True,
    "safety_status": "情绪平稳，无即时危险",
    "emergency_contact_called": True,
    "referred_12356": True,
    "called_emergency_services": False,
    "follow_up_plan": "24 小时内电话随访",
    "operator_signature": "值班员甲",
}


def headers_for(role: str, subject: str | None = None, step_up: bool = False) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject or f'subj_{role}', 't_demo', role, step_up=step_up)}"}


def open_escalation(client, user_headers, event_id: str = "evt_wb_esc") -> str:
    opened = client.post("/v1/escalations", json={
        "event_id": event_id, "user_id": "u_demo", "level": "L3",
        "trigger": "help_requested", "evidence_summary": "用户主动求助",
    }, headers=user_headers)
    assert opened.status_code == 200
    return opened.json()["id"]


def set_user_city(city: str) -> None:
    with SessionLocal() as db:
        user = db.get(User, "u_demo")
        user.city = city
        db.commit()


def add_emergency_contact(active: bool = True) -> None:
    with SessionLocal() as db:
        db.add(EmergencyContact(
            tenant_id="t_demo", user_id="u_demo", name_ciphertext="enc",
            phone_ciphertext="enc", relationship="家人", active=active,
        ))
        db.commit()


def test_queue_fields_complete_and_delivery_labels(client, user_headers, staff_headers):
    set_user_city("杭州")
    add_emergency_contact(active=True)
    esc_id = open_escalation(client, user_headers)
    # 直接落库一个无服务端送达确认的历史事件（迁移前的存量数据形态）。
    with SessionLocal() as db:
        legacy = Escalation(
            event_id="evt_wb_legacy", tenant_id="t_demo", user_id="u_demo",
            level="L3", trigger="text_red_signal", evidence_summary="历史事件，无服务端送达确认",
        )
        db.add(legacy)
        db.commit()
        legacy_id = legacy.id
    queue = client.get("/v1/escalations", headers=staff_headers)
    assert queue.status_code == 200
    items = {item["id"]: item for item in queue.json()}
    assert set(items) == {esc_id, legacy_id}
    current = items[esc_id]
    for field in (
        "risk_type", "waiting_seconds", "escalation_level", "chain_broken",
        "delivery_confirmed", "delivery_confirmed_at", "user_city",
        "emergency_contact_status", "assigned_to", "second_duty_notified",
        "notified_l1_at", "notified_l2_at",
    ):
        assert field in current, field
    assert current["risk_type"] == "用户主动求助"
    assert current["waiting_seconds"] >= 0
    assert current["escalation_level"] == 0
    assert current["chain_broken"] is False
    assert current["delivery_confirmed"] is True
    assert current["user_city"] == "杭州"
    assert current["emergency_contact_status"] == "可用"
    legacy_item = items[legacy_id]
    # 无服务端确认送达的事件必须被明确标识为未送达。
    assert legacy_item["delivery_confirmed"] is False
    assert legacy_item["delivery_confirmed_at"] is None
    assert legacy_item["risk_type"] == "文本红色信号"


def test_queue_emergency_contact_status_variants(client, user_headers, staff_headers):
    open_escalation(client, user_headers)
    queue = client.get("/v1/escalations", headers=staff_headers).json()
    assert queue[0]["emergency_contact_status"] == "未登记"
    add_emergency_contact(active=False)
    queue = client.get("/v1/escalations", headers=staff_headers).json()
    assert queue[0]["emergency_contact_status"] == "不可用"


def test_no_human_received_wording_anywhere(client, user_headers):
    assert "人工已收到" not in CONSOLE_HTML.read_text(encoding="utf-8")
    esc_id = open_escalation(client, user_headers)
    status = client.get(f"/v1/escalations/{esc_id}/user-status", headers=user_headers)
    assert status.status_code == 200
    assert "人工已收到" not in status.text


def test_case_review_evidence_blocks_complete_and_traceable(client, user_headers):
    with SessionLocal() as db:
        db.add(Checkin(
            event_id="evt_wb_checkin",
            tenant_id="t_demo",
            user_id="u_demo",
            mood=2, stress=5, energy=2, sleep_recovery=2,
            event_flag=False, help_requested=False,
            note_ciphertext=encrypt_text("最近状态很平稳", aad="t_demo:u_demo:checkin"),
            client_time=datetime.now(timezone.utc),
            device_timezone="Asia/Shanghai",
        ))
        db.commit()
    safety = client.post("/v1/safety/check", json={
        "user_id": "u_demo", "text": "我想死",
    }, headers=user_headers)
    assert safety.status_code == 410
    # /safety/check 已停用，通过 API 创建 escalation（含审计轨迹）+ DB 插入 red RiskSignal。
    esc_id = open_escalation(client, user_headers, event_id="evt_wb_safety_esc")
    answers = [0, 0, 0, 0, 0, 0, 0, 0, 1]
    with SessionLocal() as db:
        signal = RiskSignal(
            tenant_id="t_demo",
            user_id="u_demo",
            source="free_text",
            severity="red",
            rule_pack_version=RULE_PACK_VERSION,
            evidence_refs=["RED-001"],
            labels=["immediate_safety_risk"],
        )
        db.add(signal)
        db.flush()
        phq_result = score_phq9(answers)
        db.add(QuestionnaireResult(
            event_id="evt_wb_phq9",
            tenant_id="t_demo",
            user_id="u_demo",
            instrument="phq9",
            version="phq9-v1",
            answers=answers,
            score=phq_result.score,
            interpretation=phq_result.interpretation,
        ))
        db.commit()

    review = client.get(
        f"/v1/escalations/{esc_id}/case-review",
        headers=headers_for("professional", step_up=True),
    )
    assert review.status_code == 200
    data = review.json()
    for block in (
        "escalation", "direct_expressions", "rule_hits", "safety_classifier",
        "questionnaires", "recent_trend", "data_quality", "risk_history", "human_handling",
    ):
        assert block in data, block
    # 用户直接表达可溯源到签到原文。
    assert any(
        d["source"] == "checkin" and d["text"] == "最近状态很平稳"
        for d in data["direct_expressions"]
    )
    # 规则命中明细可溯源到具体规则、模式与规则包版本。
    red_hits = [h for h in data["rule_hits"] if h["severity"] == "red"]
    assert red_hits
    hit = red_hits[0]
    assert hit["rule_pack_version"]
    assert hit["matched_rules"][0]["rule_id"].startswith("RED-")
    assert hit["matched_rules"][0]["pattern"]
    # 安全分类器结果。
    assert data["safety_classifier"]["latest_severity"] == "red"
    assert data["safety_classifier"]["red_signal_count"] >= 1
    # PHQ-9 原始答案原样返回。
    phq_rows = [q for q in data["questionnaires"] if q["instrument"] == "phq9"]
    assert phq_rows and phq_rows[0]["answers"] == answers
    # 趋势与数据质量。
    assert data["recent_trend"]["points"]
    assert data["data_quality"]["data_days"] >= 1
    # 历史风险事件包含当前事件。
    assert any(h["id"] == esc_id and h["is_current"] for h in data["risk_history"])
    # 人工处置记录含可溯源审计轨迹。
    actions = [a["action"] for a in data["human_handling"]["audit_trail"]]
    assert "escalation.open" in actions


def test_case_review_role_and_step_up_guards(client, user_headers):
    esc_id = open_escalation(client, user_headers)
    # admin（tenant_admin）即使持有 step-up 也不得读取心理内容证据链。
    admin = client.get(
        f"/v1/escalations/{esc_id}/case-review",
        headers=headers_for("admin", step_up=True),
    )
    assert admin.status_code == 403
    for role in ("user", "auditor", "security_auditor", "vendor_support"):
        denied = client.get(
            f"/v1/escalations/{esc_id}/case-review",
            headers=headers_for(role, step_up=True),
        )
        assert denied.status_code == 403, role
    # 复核类角色缺少 step-up 同样被拒。
    no_step = client.get(
        f"/v1/escalations/{esc_id}/case-review", headers=headers_for("professional"),
    )
    assert no_step.status_code == 403
    for role in ("professional", "on_call", "quality_reviewer"):
        ok = client.get(
            f"/v1/escalations/{esc_id}/case-review",
            headers=headers_for(role, step_up=True),
        )
        assert ok.status_code == 200, role
    # 拒绝均留有审计记录（沿用 Task 1 守卫语义）。
    events = client.get("/v1/audit/events", headers=headers_for("auditor")).json()
    assert "authz.psych_content_denied" in [e["action"] for e in events]


def test_close_requires_complete_takeover_record(client, user_headers, professional_headers):
    esc_id = open_escalation(client, user_headers)
    # 未接管先关闭仍是 409（保持既有校验顺序）。
    early = client.post(
        f"/v1/escalations/{esc_id}/close", json=FULL_CLOSE_PAYLOAD, headers=professional_headers,
    )
    assert early.status_code == 409
    client.post(f"/v1/escalations/{esc_id}/takeover", headers=professional_headers)
    only_disposition = client.post(
        f"/v1/escalations/{esc_id}/close", json={"disposition": "已完成"}, headers=professional_headers,
    )
    assert only_disposition.status_code == 422
    missing = only_disposition.json()["detail"]["missing_fields"]
    assert set(missing) == {
        "contact_method", "contact_succeeded", "safety_status",
        "emergency_contact_called", "referred_12356", "called_emergency_services",
        "follow_up_plan", "operator_signature",
    }
    # 校验失败不改变事件状态。
    with SessionLocal() as db:
        assert db.get(Escalation, esc_id).status == "taken_over"


def test_close_with_complete_record_persists_fields(client, user_headers, professional_headers):
    esc_id = open_escalation(client, user_headers)
    client.post(f"/v1/escalations/{esc_id}/takeover", headers=professional_headers)
    closed = client.post(
        f"/v1/escalations/{esc_id}/close", json=FULL_CLOSE_PAYLOAD, headers=professional_headers,
    )
    assert closed.status_code == 200
    assert closed.json()["status"] == "closed"
    detail = client.get(
        f"/v1/escalations/{esc_id}", headers=headers_for("professional", step_up=True),
    )
    assert detail.status_code == 200
    body = detail.json()
    for key, value in FULL_CLOSE_PAYLOAD.items():
        assert body[key] == value, key
    assert body["closed_at"] is not None
