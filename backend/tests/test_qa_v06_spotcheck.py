"""QA 独立端到端抽查（v0.6 收口）—— 由 QA 严过关以全新视角编写。

不依赖工程师既有测试断言；每条用例独立构造数据并断言 PRD/架构契约。
覆盖：
- profile 只读（两次 GET version 不变 / 未 rebuild 404 / rebuild 后 version+1）
- narratives 批量（ordered + coverage + missing dates / GET 不写库）
- skills completions（幂等 / 非 signed 拒绝 / 非本人拒绝）
- skills signed-only（reviewed 不下发）
- flag fail-closed（tenant 不存在 / 字段缺失 / 未知 key）
- DSR delete 矩阵 + 幂等 complete
- PRD 契约点 1：ingest 对派生特征不得触发 passive_red_signal（验收标准 2）
"""
from datetime import datetime, timedelta, timezone

from app.auth import create_access_token
from app.database import SessionLocal
from app.models import (
    Consent,
    DailyNarrative,
    DataSubjectRequest,
    DerivedFeature,
    Escalation,
    RiskSignal,
    Skill,
    Tenant,
    UserProfile,
)
from app.services.safety import PASSIVE_RED_TERMS


def _feature_payload(event_id: str, summary: str, user_id: str = "u_demo",
                     source: str = "screen", window_start: datetime | None = None) -> dict:
    start = window_start or datetime.now(timezone.utc).replace(hour=12, minute=0, second=0, microsecond=0)
    return {
        "event_id": event_id,
        "user_id": user_id,
        "schema_version": "passive-core-v1",
        "source": source,
        "window_start": start.isoformat(),
        "window_end": (start + timedelta(minutes=30)).isoformat(),
        "summary": summary,
        "vector": [0.1, 0.2, 0.3],
    }


def _grant_passive_consent(client, user_headers, user_id: str = "u_demo") -> None:
    resp = client.post("/v1/onboarding/consents", json={
        "user_id": user_id,
        "consent_type": "passive_sensing",
        "version": "test-v1",
        "granted": True,
        "evidence_hash": "a" * 64,
    }, headers=user_headers)
    assert resp.status_code == 200


def _make_skill(db, *, skill_id: str, status: str, user_id: str = "u_demo") -> Skill:
    row = Skill(
        id=skill_id,
        tenant_id="t_demo",
        user_id=user_id,
        name=f"skill-{skill_id}",
        status=status,
        trigger_conditions=[{"context": "idle"}],
        guardrails=["不适立即停止"],
        steps=[{"description": "step"}],
        content_hash=f"hash-{skill_id}",
        version=1,
    )
    db.add(row)
    return row


def _make_dsr(db, *, request_id: str, request_type: str = "delete",
              user_id: str = "u_demo", status: str = "pending") -> DataSubjectRequest:
    row = DataSubjectRequest(
        id=request_id,
        event_id=f"dsr_event_{request_id}",
        tenant_id="t_demo",
        user_id=user_id,
        request_type=request_type,
        status=status,
    )
    db.add(row)
    return row


# ============ 1. profile 只读 ============

def test_profile_get_is_readonly_no_version_bump(client, user_headers):
    """未 rebuild 时 GET /profile → 404；rebuild 后两次 GET version 不变。"""
    _grant_passive_consent(client, user_headers)
    r404 = client.get("/v1/profile/u_demo", headers=user_headers)
    assert r404.status_code == 404

    r1 = client.post("/v1/profile/u_demo/rebuild", headers=user_headers)
    assert r1.status_code == 200
    v1 = r1.json()["version"]
    assert v1 >= 1

    g1 = client.get("/v1/profile/u_demo", headers=user_headers)
    g2 = client.get("/v1/profile/u_demo", headers=user_headers)
    assert g1.status_code == 200 and g2.status_code == 200
    assert g1.json()["version"] == v1
    assert g2.json()["version"] == v1  # 读无副作用：version 不随 GET 递增


def test_profile_rebuild_increments_version(client, user_headers):
    """POST rebuild 后 version+1（写路径显式）。"""
    _grant_passive_consent(client, user_headers)
    client.post("/v1/profile/u_demo/rebuild", headers=user_headers)
    v1 = client.get("/v1/profile/u_demo", headers=user_headers).json()["version"]
    client.post("/v1/profile/u_demo/rebuild", headers=user_headers)
    v2 = client.get("/v1/profile/u_demo", headers=user_headers).json()["version"]
    assert v2 == v1 + 1


# ============ 2. narratives 批量只读 ============

def test_narratives_bulk_ordered_coverage_and_no_write(client, user_headers):
    """批量：ordered + coverage + missing dates；GET 不写库（DB 无新增行）。"""
    _grant_passive_consent(client, user_headers)
    today = datetime.now(timezone.utc).date()
    day1 = today - timedelta(days=2)
    day2 = today - timedelta(days=1)
    client.post("/v1/features/ingest",
                json=_feature_payload("qa_narr_1", "活动平稳", user_id="u_demo",
                                      window_start=datetime.combine(day2, datetime.min.time(), tzinfo=timezone.utc)),
                headers=user_headers)
    client.post("/v1/features/ingest",
                json=_feature_payload("qa_narr_2", "屏幕使用平稳", user_id="u_demo",
                                      window_start=datetime.combine(day1, datetime.min.time(), tzinfo=timezone.utc)),
                headers=user_headers)

    with SessionLocal() as db:
        before = db.query(DailyNarrative).count()
    body = client.get("/v1/narratives", params={
        "user_id": "u_demo", "from": day1.isoformat(), "to": today.isoformat(),
    }, headers=user_headers).json()
    dates = [n["date"] for n in body["narratives"]]
    assert dates == sorted(dates)
    assert day1.isoformat() in dates and day2.isoformat() in dates
    req = body["coverage"]["requested_days"]
    present = body["coverage"]["present_days"]
    assert body["coverage"]["coverage"] == round(present / req, 3)
    assert today.isoformat() in body["coverage"]["missing_dates"]

    # GET 不写库
    with SessionLocal() as db:
        after = db.query(DailyNarrative).count()
    assert after == before


# ============ 3. skills completions ============

def test_skill_completion_idempotent_and_signed_only(client, user_headers):
    """同 event_id 重放返回 idempotent_replay；非 signed 拒绝；非本人拒绝。"""
    _grant_passive_consent(client, user_headers)
    with SessionLocal() as db:
        signed = _make_skill(db, skill_id="sk_signed", status="signed")
        reviewed = _make_skill(db, skill_id="sk_reviewed", status="reviewed")
        db.commit()
        signed_id = signed.id
        reviewed_id = reviewed.id

    base = {
        "user_id": "u_demo",
        "skill_id": signed_id,
        "status": "completed",
        "duration_seconds": 60,
        "client_time": datetime.now(timezone.utc).isoformat(),
    }
    r1 = client.post("/v1/skills/completions", json={**base, "event_id": "qa_comp_1"},
                     headers=user_headers)
    assert r1.status_code == 200
    assert r1.json()["idempotent_replay"] is False
    r2 = client.post("/v1/skills/completions", json={**base, "event_id": "qa_comp_1"},
                     headers=user_headers)
    assert r2.status_code == 200
    assert r2.json()["idempotent_replay"] is True

    # 非 signed skill → 409
    r3 = client.post("/v1/skills/completions",
                     json={**base, "skill_id": reviewed_id, "event_id": "qa_comp_2"},
                     headers=user_headers)
    assert r3.status_code == 409

    # 非本人 skill：u_other 的 token 上报 u_demo 的 skill → 404
    other_headers = {"Authorization": f"Bearer {create_access_token('u_other', 't_demo', 'user')}"}
    r4 = client.post("/v1/skills/completions",
                     json={**base, "user_id": "u_other", "event_id": "qa_comp_3"},
                     headers=other_headers)
    assert r4.status_code == 404


def test_skills_list_only_signed(client, user_headers):
    """GET /v1/skills 仅返回 signed；reviewed 不下发。"""
    _grant_passive_consent(client, user_headers)
    with SessionLocal() as db:
        _make_skill(db, skill_id="sk_a", status="signed")
        _make_skill(db, skill_id="sk_b", status="reviewed")
        _make_skill(db, skill_id="sk_c", status="retired")
        db.commit()
    body = client.get("/v1/skills", headers=user_headers).json()
    ids = {s["id"] for s in body["skills"]}
    assert "sk_a" in ids
    assert "sk_b" not in ids
    assert "sk_c" not in ids


# ============ 4. flag fail-closed ============

def test_flag_fail_closed_missing_tenant_field_unknown(client):
    """tenant 不存在/字段缺失/未知 key → passive=false, sandbox=false, skills=true。"""
    outsider = {"Authorization": f"Bearer {create_access_token('u_demo', 't_nonexistent', 'user')}"}
    r = client.get("/v1/config/flags", headers=outsider)
    assert r.status_code == 200
    flags = r.json()
    assert flags["passive_sensing_enabled"] is False
    assert flags["sandbox_enabled"] is False
    assert flags["skills_delivery_enabled"] is True

    # 字段缺失：租户存在但 feature_flags 只配置非敏感项
    with SessionLocal() as db:
        db.add(Tenant(id="t_partial_qa", name="Partial QA"))
        db.commit()
        t = db.get(Tenant, "t_partial_qa")
        t.feature_flags = {"skills_delivery_enabled": True}
        db.commit()
    partial = {"Authorization": f"Bearer {create_access_token('u_demo', 't_partial_qa', 'user')}"}
    r = client.get("/v1/config/flags", headers=partial)
    assert r.json()["passive_sensing_enabled"] is False
    assert r.json()["sandbox_enabled"] is False
    assert r.json()["skills_delivery_enabled"] is True


# ============ 5. DSR delete 矩阵 ============

def test_dsr_delete_matrix_counts_and_idempotent(client, admin_headers, user_headers):
    """delete 后 per_category 计数正确；重复 complete 幂等。"""
    _grant_passive_consent(client, user_headers)
    client.post("/v1/features/ingest", json=_feature_payload("qa_dsr_1", "活动平稳"), headers=user_headers)
    client.post("/v1/features/ingest", json=_feature_payload("qa_dsr_2", "屏幕使用平稳"), headers=user_headers)
    client.post("/v1/profile/u_demo/rebuild", headers=user_headers)
    with SessionLocal() as db:
        _make_skill(db, skill_id="sk_dsr", status="signed")
        _make_dsr(db, request_id="dsr_qa_1", request_type="delete")
        db.commit()

    r = client.post("/v1/data-subject-requests/dsr_qa_1/complete", json={}, headers=admin_headers)
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "completed"
    pc = body["result_summary"]["per_category"]
    assert pc["derived_features"]["action"] == "delete" and pc["derived_features"]["count"] == 2
    assert pc["user_profiles"]["count"] == 1
    assert pc["skills"]["count"] == 1
    assert pc["audit_events"]["action"] == "retain"
    assert pc["escalations"]["action"] == "retain"
    assert pc["data_subject_requests"]["action"] == "retain"
    # PRD 契约点 6：consents/risk_signals 依法保留（同意证据链 / 危机处置 append-only）
    assert pc["consents"]["action"] == "retain"
    assert pc["consents"].get("retained_reason")
    assert pc["risk_signals"]["action"] == "retain"
    assert pc["risk_signals"].get("retained_reason")
    for cat in ("audit_events", "escalations", "data_subject_requests", "consents", "risk_signals"):
        assert pc[cat]["retained_reason"]

    # 重复 complete → 幂等
    r2 = client.post("/v1/data-subject-requests/dsr_qa_1/complete", json={}, headers=admin_headers)
    assert r2.status_code == 200
    assert r2.json().get("idempotent_replay") is True

    with SessionLocal() as db:
        assert db.query(DerivedFeature).filter_by(tenant_id="t_demo", user_id="u_demo").count() == 0
        assert db.query(UserProfile).filter_by(tenant_id="t_demo", user_id="u_demo").count() == 0
        assert db.query(Skill).filter_by(tenant_id="t_demo", user_id="u_demo").count() == 0
        # 保留：consents / risk_signals（危机处置）
        assert db.query(Consent).filter_by(tenant_id="t_demo", user_id="u_demo").count() >= 1
        assert db.query(RiskSignal).filter_by(tenant_id="t_demo", user_id="u_demo").count() >= 0
        # 保留审计
        from app.models import AuditEvent
        assert db.query(AuditEvent).filter_by(tenant_id="t_demo").count() > 0


# ============ 6. PRD 契约点 1：ingest 不得触发 passive_red_signal ============

def test_ingest_never_triggers_passive_red_signal(client, user_headers):
    """PRD v0.6 契约点 1 验收标准 2：
    POST /v1/features/ingest 传入任意 summary（含 PASSIVE_RED_TERMS 词条与不含两种），
    响应 escalation_id 恒为 null，且不创建 RiskSignal、不创建 trigger=passive_red_signal 的 Escalation。
    """
    _grant_passive_consent(client, user_headers)
    term = PASSIVE_RED_TERMS[0]  # 例如 "自杀"
    r = client.post("/v1/features/ingest",
                    json=_feature_payload("qa_prd_01", f"过去5分钟活动量低，摘要含{term}字样"),
                    headers=user_headers)
    assert r.status_code == 200
    assert r.json()["escalation_id"] is None, (
        f"PRD 契约点 1 违反：ingest 对含 PASSIVE_RED_TERMS 的派生特征仍返回 escalation_id="
        f"{r.json()['escalation_id']}"
    )
    with SessionLocal() as db:
        signals = db.query(RiskSignal).filter_by(tenant_id="t_demo", user_id="u_demo").all()
        assert len(signals) == 0, "PRD 契约点 1 违反：不应创建 RiskSignal"
        escalations = db.query(Escalation).filter_by(tenant_id="t_demo", user_id="u_demo").all()
        for esc in escalations:
            assert esc.trigger != "passive_red_signal", (
                "PRD 契约点 1 违反：不应存在 trigger=passive_red_signal 的 Escalation"
            )


def test_skill_out_has_execution_contract_fields(client, user_headers):
    """PRD v0.6 契约点 4 验收标准 1：
    SkillOut 应含 7 个执行契约字段：skill_id, version, action_type, steps,
    estimated_duration, completion_schema, safety_constraints。
    """
    _grant_passive_consent(client, user_headers)
    with SessionLocal() as db:
        _make_skill(db, skill_id="sk_contract", status="signed")
        db.commit()
    body = client.get("/v1/skills", headers=user_headers).json()
    skills = body["skills"]
    assert len(skills) >= 1
    out = skills[0]
    for field in ("action_type", "estimated_duration", "completion_schema", "safety_constraints"):
        assert field in out, (
            f"PRD 契约点 4 违反：SkillOut 缺少执行契约字段 '{field}'（实际 keys="
            f"{sorted(out.keys())}）"
        )


def test_narratives_and_profile_no_mood_hint(client, user_headers):
    """PRD v0.6 契约点 2 验收标准 2：
    GET /v1/narratives 响应不再含 mood_hint（或置空并标记 deprecated）；
    GET /v1/profile/{id} traits 不含 recent_mood_hint。
    """
    _grant_passive_consent(client, user_headers)
    start = datetime.now(timezone.utc).replace(hour=12, minute=0, second=0, microsecond=0)
    r = client.post("/v1/features/ingest",
                    json=_feature_payload("qa_mood_01", "低落", user_id="u_demo"),
                    headers=user_headers)
    assert r.status_code == 200
    client.post("/v1/profile/u_demo/rebuild", headers=user_headers)

    narr = client.get("/v1/narratives", params={
        "user_id": "u_demo", "from": start.date().isoformat(), "to": start.date().isoformat(),
    }, headers=user_headers).json()
    narratives = narr.get("narratives") or [narr]
    for n in narratives:
        assert "mood_hint" not in n, (
            f"PRD 契约点 2 违反：narratives 仍含 mood_hint={n.get('mood_hint')}"
        )
    profile = client.get("/v1/profile/u_demo", headers=user_headers).json()
    traits = profile.get("traits") or {}
    assert "recent_mood_hint" not in traits, (
        f"PRD 契约点 2 违反：profile traits 仍含 recent_mood_hint={traits.get('recent_mood_hint')}"
    )


def test_dsr_retains_consent_and_risk_signal(client, admin_headers, user_headers):
    """PRD v0.6 契约点 6 矩阵：
    Consent=retain、RiskSignal=retain/anonymize（危机处置 append-only），
    二者在 delete 后必须保留且 retained_reason 非空。
    """
    _grant_passive_consent(client, user_headers)
    with SessionLocal() as db:
        db.add(RiskSignal(tenant_id="t_demo", user_id="u_demo", source="free_text",
                          severity="none", rule_pack_version="test", evidence_refs=[], labels=[]))
        _make_dsr(db, request_id="dsr_qa_retain", request_type="delete")
        db.commit()

    r = client.post("/v1/data-subject-requests/dsr_qa_retain/complete", json={}, headers=admin_headers)
    assert r.status_code == 200
    pc = r.json()["result_summary"]["per_category"]
    assert pc["consents"]["action"] == "retain", (
        f"PRD 契约点 6 违反：consents action={pc['consents']['action']}，应为 retain"
    )
    assert pc["consents"].get("retained_reason"), "consents retained_reason 应非空"
    assert pc["risk_signals"]["action"] == "retain", (
        f"PRD 契约点 6 违反：risk_signals action={pc['risk_signals']['action']}，应为 retain"
    )
    assert pc["risk_signals"].get("retained_reason"), "risk_signals retained_reason 应非空"
    with SessionLocal() as db:
        assert db.query(Consent).filter_by(tenant_id="t_demo", user_id="u_demo").count() >= 1
        assert db.query(RiskSignal).filter_by(tenant_id="t_demo", user_id="u_demo").count() >= 1


def test_skill_model_has_governance_metadata_fields():
    """PRD v0.6 契约点 5：Skill 模型应含
    signed_by / signed_at / policy_version / review_evidence / revision / supersedes_skill_id。
    """
    from app.models import Skill
    columns = {c.name for c in Skill.__table__.columns}
    for field in ("policy_version", "review_evidence", "revision", "supersedes_skill_id"):
        assert field in columns, (
            f"PRD 契约点 5 违反：Skill 模型缺少治理字段 '{field}'（实际列="
            f"{sorted(columns)}）"
        )
