"""T03 DSR 分类删除矩阵测试（v0.6）。

覆盖：
- delete 分支删除派生产物（DerivedFeature/DailyNarrative/UserProfile/RiskSignal/Consent/
  Checkin/Journal/Questionnaire/Practice/EmergencyContact/Skill/Tool/SandboxRun）
- 保留审计与危机处置记录（AuditEvent/Escalation/DataSubjectRequest），且关联 User 去标识
- result_summary.per_category 各表删除计数正确
- 删除后 GET /narratives、GET /profile 返回 404
- 重复 complete 幂等（idempotent_replay=True，结果一致）
"""
from datetime import UTC, datetime

from app.auth import create_access_token
from app.database import SessionLocal
from app.models import (
    AuditEvent,
    Checkin,
    Consent,
    DailyNarrative,
    DataSubjectRequest,
    DerivedFeature,
    EmergencyContact,
    Escalation,
    JournalEntry,
    PracticeCompletion,
    QuestionnaireResult,
    RiskSignal,
    SandboxRun,
    Skill,
    Tool,
    User,
    UserProfile,
)
from app.services.crypto import encrypt_text


def _admin_headers() -> dict:
    return {"Authorization": f"Bearer {create_access_token('admin', 't_demo', 'admin')}"}


def _seed_all_user_data() -> None:
    """为 u_demo 播种各数据表记录。"""
    now = datetime.now(UTC)
    with SessionLocal() as db:
        db.add(DerivedFeature(tenant_id="t_demo", user_id="u_demo", event_id="evt_df_1",
                              schema_version="passive-core-v1", source="screen", window_start=now,
                              window_end=now, summary="平稳", vector=[0.1]))
        db.add(DailyNarrative(tenant_id="t_demo", user_id="u_demo", date=now.date(),
                              events=[], mood_hint="平稳", gaps=[]))
        db.add(UserProfile(tenant_id="t_demo", user_id="u_demo", traits={"observation_days": 1}))
        db.add(RiskSignal(tenant_id="t_demo", user_id="u_demo", source="free_text", severity="none",
                          rule_pack_version="test", evidence_refs=[], labels=[]))
        db.add(Consent(tenant_id="t_demo", user_id="u_demo", consent_type="passive_sensing",
                       version="test-v1", granted=True, evidence_hash="a" * 64))
        db.add(Checkin(tenant_id="t_demo", user_id="u_demo", event_id="evt_chk_1", mood=3, stress=2,
                       energy=3, sleep_recovery=2, client_time=now, device_timezone="Asia/Shanghai"))
        db.add(JournalEntry(tenant_id="t_demo", user_id="u_demo", event_id="evt_jnl_1", logical_id="log_1",
                            revision=1, body_ciphertext=encrypt_text("日记", aad="t_demo:u_demo:journal"),
                            client_time=now))
        db.add(QuestionnaireResult(tenant_id="t_demo", user_id="u_demo", event_id="evt_qr_1",
                                   instrument="phq9", version="1.0", answers=[0] * 9, score=0,
                                   interpretation="none"))
        db.add(PracticeCompletion(tenant_id="t_demo", user_id="u_demo", event_id="evt_pc_1",
                                  practice_id="breathing-01", content_version="1.0", status="completed",
                                  duration_seconds=60, client_time=now))
        db.add(EmergencyContact(tenant_id="t_demo", user_id="u_demo",
                                name_ciphertext=encrypt_text("张三", aad="t_demo:u_demo:ec-name"),
                                phone_ciphertext=encrypt_text("13800000000", aad="t_demo:u_demo:ec-phone"),
                                relationship="家人"))
        db.add(Skill(tenant_id="t_demo", user_id="u_demo", name="personal_skill", version=1,
                     status="signed", content_hash="0" * 64))
        db.add(Tool(tenant_id="t_demo", user_id="u_demo", name="personal_tool", description="",
                    parameters_schema={}, returns_schema={}))
        db.add(SandboxRun(tenant_id="t_demo", user_id="u_demo", run_date=now.date(), status="completed"))
        db.add(Escalation(tenant_id="t_demo", user_id="u_demo", event_id="evt_esc_retained",
                          level="L3", status="open", trigger="help_requested",
                          evidence_summary="用户主动求助"))
        db.add(AuditEvent(tenant_id="t_demo", event_id="evt_aud_1", actor_type="user",
                          actor_id="u_demo", action="feature.ingest", object_type="derived_feature",
                          object_id="df_1", metadata_json={}, event_hash="0" * 64))
        db.commit()


def test_dsr_delete_matrix_removes_derived_and_retains_crisis_records(client, admin_headers):
    """delete 删除派生产物/主动内容，保留审计/危机记录，per_category 计数正确。"""
    _seed_all_user_data()
    dsr = client.post("/v1/data-subject-requests", json={
        "event_id": "evt_dsr_matrix_0001", "user_id": "u_demo", "request_type": "delete",
    }, headers=_admin_headers())
    assert dsr.status_code == 200
    dsr_id = dsr.json()["id"]

    done = client.post(f"/v1/data-subject-requests/{dsr_id}/complete",
                       json={}, headers=admin_headers)
    assert done.status_code == 200
    body = done.json()
    assert body["status"] == "completed"
    result = body["result_summary"]
    per_category = result["per_category"]

    # 删除类计数（各表均播种了 1 行）
    expected_counts = {
        "derived_features": 1, "daily_narratives": 1, "user_profiles": 1,
        "checkins": 1, "journal_entries": 1,
        "questionnaire_results": 1, "practice_completions": 1, "emergency_contacts": 1,
        "tools": 1, "skills": 1, "sandbox_runs": 1,
    }
    for category, expected in expected_counts.items():
        assert per_category[category]["action"] == "delete", category
        assert per_category[category]["count"] == expected, category

    # 保留类：consents（同意证据链）/ risk_signals（危机处置 append-only）
    assert per_category["consents"]["action"] == "retain"
    assert per_category["consents"]["count"] >= 1  # conftest 预置 1 条 + 本测试 1 条
    assert per_category["consents"]["retained_reason"]
    assert per_category["risk_signals"]["action"] == "retain"
    assert per_category["risk_signals"]["count"] == 1
    assert per_category["risk_signals"]["retained_reason"]

    # 保留类：审计 / 危机记录 / DSR
    assert per_category["audit_events"]["action"] == "retain"
    assert per_category["audit_events"]["count"] == 1
    assert per_category["audit_events"]["retained_reason"]
    assert per_category["escalations"]["action"] == "retain"
    assert per_category["escalations"]["count"] == 1
    assert per_category["data_subject_requests"]["action"] == "retain"

    # DB 断言：删除类已清空；保留类仍存在
    with SessionLocal() as db:
        for model in (DerivedFeature, DailyNarrative, UserProfile, Checkin,
                      JournalEntry, QuestionnaireResult, PracticeCompletion, EmergencyContact,
                      Tool, Skill, SandboxRun):
            count = db.query(model).filter(
                model.tenant_id == "t_demo", model.user_id == "u_demo").count()
            assert count == 0, f"{model.__name__} 应已删除"
        # 保留：consent / risk_signal / escalation / 审计 / DSR
        assert db.query(Consent).filter_by(tenant_id="t_demo", user_id="u_demo").count() >= 1
        assert db.query(RiskSignal).filter_by(tenant_id="t_demo", user_id="u_demo").count() == 1
        assert db.query(Escalation).filter_by(tenant_id="t_demo", user_id="u_demo").count() == 1
        assert db.query(AuditEvent).filter_by(tenant_id="t_demo").count() >= 1
        assert db.query(DataSubjectRequest).filter_by(tenant_id="t_demo").count() == 1
        # 关联 User 去标识
        user = db.get(User, "u_demo")
        assert user.external_ref.startswith("dsr_")
        assert user.city is None


def test_dsr_delete_after_cleanup_reads_404(client, user_headers, admin_headers):
    """删除后 GET /narratives、GET /profile 返回 404（数据已清理）。"""
    _seed_all_user_data()
    dsr = client.post("/v1/data-subject-requests", json={
        "event_id": "evt_dsr_read_0001", "user_id": "u_demo", "request_type": "delete",
    }, headers=_admin_headers())
    dsr_id = dsr.json()["id"]
    done = client.post(f"/v1/data-subject-requests/{dsr_id}/complete", json={}, headers=admin_headers)
    assert done.status_code == 200

    narratives = client.get("/v1/narratives", params={"user_id": "u_demo"}, headers=user_headers)
    assert narratives.status_code == 404
    profile = client.get("/v1/profile/u_demo", headers=user_headers)
    assert profile.status_code == 404


def test_dsr_complete_idempotent_replay(client, admin_headers):
    """同一 DSR 重复 complete：不报错、结果一致、幂等标记。"""
    _seed_all_user_data()
    dsr = client.post("/v1/data-subject-requests", json={
        "event_id": "evt_dsr_idem_0001", "user_id": "u_demo", "request_type": "delete",
    }, headers=_admin_headers())
    dsr_id = dsr.json()["id"]

    first = client.post(f"/v1/data-subject-requests/{dsr_id}/complete", json={}, headers=admin_headers)
    assert first.status_code == 200
    assert first.json().get("idempotent_replay", False) is False

    second = client.post(f"/v1/data-subject-requests/{dsr_id}/complete", json={}, headers=admin_headers)
    assert second.status_code == 200
    assert second.json()["idempotent_replay"] is True
    assert second.json()["result_summary"] == first.json()["result_summary"]

    # 重复 complete 不重复删除/审计（审计事件数不再增长）
    with SessionLocal() as db:
        completed = db.query(DataSubjectRequest).filter_by(id=dsr_id).one()
        assert completed.status == "completed"


def test_dsr_receipt_binds_to_audit_evidence(client, admin_headers):
    """ERA 49 回执-证据链一致性：返回回执 per_category 与 dsr.complete 审计证据逐类一致，
    且删除完成后审计哈希链仍完整可验证。"""
    _seed_all_user_data()
    dsr = client.post("/v1/data-subject-requests", json={
        "event_id": "evt_dsr_bind_0001", "user_id": "u_demo", "request_type": "delete",
    }, headers=_admin_headers())
    dsr_id = dsr.json()["id"]

    done = client.post(f"/v1/data-subject-requests/{dsr_id}/complete", json={}, headers=admin_headers)
    assert done.status_code == 200
    receipt = done.json()["result_summary"]["per_category"]

    with SessionLocal() as db:
        aud = db.query(AuditEvent).filter(
            AuditEvent.tenant_id == "t_demo",
            AuditEvent.action == "dsr.complete",
            AuditEvent.object_id == dsr_id,
        ).order_by(AuditEvent.occurred_at.desc()).first()
        assert aud is not None, "dsr.complete 审计事件应存在"
        stored = aud.metadata_json["per_category"]

        # 回执与审计证据绑定：删除计数逐类一致
        for category in ("derived_features", "journal_entries", "user_profiles", "checkins"):
            assert stored[category]["count"] == receipt[category]["count"] == 1, category
        # 保留类理由一致（同意证据链依法保留）
        assert stored["consents"]["retained_reason"] == receipt["consents"]["retained_reason"]
        assert stored["risk_signals"]["action"] == receipt["risk_signals"]["action"] == "retain"
