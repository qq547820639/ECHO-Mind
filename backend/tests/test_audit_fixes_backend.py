"""全仓审计修复回归测试（对应 findings/T6-backend.md P0/P1 修复项）。

- P0-1  DSR delete 矩阵补齐 v0.7 Portrait Core 全部派生表
- P0-2  激活码拒绝路径 attempt 记录/计数真实持久化（TestClient 真实请求路径，
        不与路由共用同一 Session——锁死"事务回滚掩盖失败计数"回归）
- P1-10 写端点补 require_write_role（auditor/security_auditor 只读，403）
- P1-11 rebuild/feedback 补 passive_sensing consent 门禁 + 订阅 402 门
- P1-12 escalations cursor 分页 DateTime 同型比较（不 500，全量遍历不重不漏）
- P1-13 /v1/me/messages 补 require_active_subscription（无订阅到期 → 402）
- P1-14 DailyNarrative 日界线统一用户本地日（与 aggregate/portrait 同源）
- P1-15 DSR export 真实导出用户数据（范围=删除矩阵，JSON 落 result_summary）
"""
from datetime import UTC, date, datetime, timedelta

import pytest
from sqlalchemy import func, select

from app.auth import create_access_token
from app.database import SessionLocal
from app.services.activation import hash_code
from app.models import (
    ActivationAttempt,
    ActivationCode,
    AuditEvent,
    Consent,
    DailyBehaviorAggregate,
    DailyNarrative,
    DailyPortrait,
    DataSubjectRequest,
    DerivedFeature,
    Escalation,
    MaterializationState,
    PersonalBaseline,
    PortraitFeedback,
    Skill,
    SkillCompletion,
    User,
)


# ============ helpers ============

def _seed_consent(user_id: str = "u_demo", *, granted: bool = True) -> None:
    """直插一条 passive_sensing consent（granted=False 即撤回，最新记录生效）。"""
    with SessionLocal() as db:
        db.add(Consent(tenant_id="t_demo", user_id=user_id, consent_type="passive_sensing",
                       version="test-v1", granted=granted,
                       revoked_at=None if granted else datetime.now(UTC),
                       evidence_hash="f" * 64))
        db.commit()


def _set_subscription(user_id: str, expires_at: datetime | None) -> None:
    with SessionLocal() as db:
        user = db.get(User, user_id)
        user.subscription_expires_at = expires_at
        db.commit()


def _seed_portrait_core_data() -> None:
    """播种 v0.7 Portrait Core 派生表数据（P0-1/P1-15 共用）。"""
    now = datetime.now(UTC)
    today = now.date()
    with SessionLocal() as db:
        db.add(DerivedFeature(tenant_id="t_demo", user_id="u_demo", event_id="evt_audit_df1",
                              schema_version="passive-core-v1", source="screen",
                              window_start=now, window_end=now, summary="平稳", vector=[0.1]))
        db.add(DailyNarrative(tenant_id="t_demo", user_id="u_demo", date=today,
                              events=[{"source": "screen"}], mood_hint=None, gaps=[]))
        db.add(DailyBehaviorAggregate(tenant_id="t_demo", user_id="u_demo", local_date=today,
                                      timezone="Asia/Shanghai", coverage_score=0.5))
        db.add(PersonalBaseline(tenant_id="t_demo", user_id="u_demo", bucket="all_days",
                                window_start=today - timedelta(days=28),
                                window_end=today - timedelta(days=1), valid_days=3, metrics={}))
        db.add(DailyPortrait(tenant_id="t_demo", user_id="u_demo", local_date=today,
                             timezone="Asia/Shanghai", status="READY", confidence="MEDIUM"))
        db.add(MaterializationState(tenant_id="t_demo", user_id="u_demo", local_date=today, dirty=False))
        db.add(PortraitFeedback(tenant_id="t_demo", user_id="u_demo", event_id="pfb_audit_0001",
                                local_date=today, feedback="LIKE"))
        skill = Skill(tenant_id="t_demo", user_id="u_demo", name="audit_fix_skill", version=1,
                      status="signed", content_hash="0" * 64)
        db.add(skill)
        db.flush()
        db.add(SkillCompletion(tenant_id="t_demo", user_id="u_demo", event_id="sc_audit_0001",
                               skill_id=skill.id, status="completed", client_time=now))
        db.commit()


def _feedback_payload(**overrides) -> dict:
    payload = {
        "event_id": "pfb_gate_00001",
        "user_id": "u_demo",
        "portrait_id": "2026-08-18",
        "feedback": "LIKE",
        "portrait_schema_version": "portrait-v1",
    }
    payload.update(overrides)
    return payload


def _completion_payload(**overrides) -> dict:
    payload = {
        "event_id": "sc_gate_000001",
        "user_id": "u_demo",
        "skill_id": "sk_nonexistent",
        "status": "completed",
        "duration_seconds": 30,
        "client_time": datetime.now(UTC).isoformat(),
    }
    payload.update(overrides)
    return payload


def _open_dsr(client, headers, *, request_type: str, event_id: str) -> str:
    resp = client.post("/v1/data-subject-requests", json={
        "event_id": event_id, "user_id": "u_demo", "request_type": request_type,
    }, headers=headers)
    assert resp.status_code == 200, resp.text
    return resp.json()["id"]


# ============ P0-1：DSR delete 补齐派生表 ============

def test_p0_1_dsr_delete_removes_portrait_core_derived_tables(client, user_headers, admin_headers):
    """delete 后 aggregates/baselines/portraits/materialization/feedback/skill_completions
    该用户行数必须为 0（修复前 6 表全部残留）。"""
    _seed_portrait_core_data()
    dsr_id = _open_dsr(client, user_headers, request_type="delete", event_id="evt_audit_p01_del")
    done = client.post(f"/v1/data-subject-requests/{dsr_id}/complete", json={}, headers=admin_headers)
    assert done.status_code == 200
    per_category = done.json()["result_summary"]["per_category"]
    for category in ("daily_behavior_aggregates", "personal_baselines", "daily_portraits",
                     "materialization_state", "portrait_feedback", "skill_completions"):
        assert per_category[category]["action"] == "delete", category
        assert per_category[category]["count"] == 1, category
    with SessionLocal() as db:
        for model in (DailyBehaviorAggregate, PersonalBaseline, DailyPortrait,
                      MaterializationState, PortraitFeedback, SkillCompletion):
            count = db.query(model).filter(
                model.tenant_id == "t_demo", model.user_id == "u_demo").count()
            assert count == 0, f"{model.__name__} 应随 DSR delete 删除"


# ============ P0-2：激活码拒绝路径 attempt 持久化（真实 HTTP） ============

def test_p0_2_rejected_redemptions_persist_attempts_across_requests(client, admin_headers):
    """TestClient 真实请求路径：每次 403 拒绝后 attempt 行数递增（独立会话可见）、
    attempt_count 累计、达 max_attempts 后持续拒绝。"""
    issued = client.post("/v1/admin/activation-codes",
                         json={"user_id": "u_demo", "max_attempts": 2},
                         headers=admin_headers)
    assert issued.status_code == 200, issued.text
    code_id, plain = issued.json()["id"], issued.json()["code"]
    revoked = client.post(f"/v1/admin/activation-codes/{code_id}/revoke", headers=admin_headers)
    assert revoked.status_code == 200

    # 连续 2 次 403：每次请求结束后（请求事务已回滚/关闭）用新会话断言持久化
    for i in range(1, 3):
        resp = client.post("/v1/onboarding/verify-code", json={"code": plain},
                           headers={"x-device-id": f"dev-p02-{i}"})
        assert resp.status_code == 403
        with SessionLocal() as db:
            failures = db.scalar(select(func.count()).select_from(ActivationAttempt).where(
                ActivationAttempt.result == "failure",
                ActivationAttempt.code_hash == hash_code(plain),
            )) or 0
        assert failures == i, f"第 {i} 次拒绝后失败 attempt 必须已持久化（P0-2 回归锁）"

    # max_attempts=2 已达：解除吊销后仍拒绝（attempt_count 计数真实生效）
    with SessionLocal() as db:
        row = db.get(ActivationCode, code_id)
        assert row.attempt_count >= 2
        row.revoked_at = None
        db.commit()
    resp = client.post("/v1/onboarding/verify-code", json={"code": plain},
                       headers={"x-device-id": "dev-p02-3"})
    assert resp.status_code == 403


def test_p0_2_unknown_code_404_persisted_as_attempt(client):
    """not_found 拒绝路径（404）同样持久化失败 attempt（IP/device 限流数据来源）。"""
    before = 0
    with SessionLocal() as db:
        before = db.scalar(select(func.count()).select_from(ActivationAttempt).where(
            ActivationAttempt.device_id == "dev-p02-nf")) or 0
    resp = client.post("/v1/onboarding/verify-code", json={"code": "NO-SUCH-AUDIT-01"},
                       headers={"x-device-id": "dev-p02-nf"})
    assert resp.status_code == 404
    with SessionLocal() as db:
        after = db.scalar(select(func.count()).select_from(ActivationAttempt).where(
            ActivationAttempt.device_id == "dev-p02-nf")) or 0
    assert after == before + 1


# ============ P1-10：写端点 require_write_role ============

WRITE_ENDPOINTS = [
    ("/v1/portraits/rebuild", {"user_id": "u_demo"}),
    ("/v1/me/portraits/rebuild", {}),
    ("/v1/profile/u_demo/rebuild", None),
    ("/v1/me/portraits/feedback", _feedback_payload()),
    ("/v1/skills/completions", _completion_payload()),
]


@pytest.mark.parametrize("role", ["auditor", "security_auditor", "quality_reviewer"])
def test_p1_10_write_endpoints_reject_read_only_roles(client, role):
    """auditor/security_auditor/quality_reviewer 调用写端点一律 403（RBAC 只读契约）。"""
    headers = {"Authorization": f"Bearer {create_access_token(f'{role}_audit', 't_demo', role)}"}
    for url, body in WRITE_ENDPOINTS:
        resp = client.post(url, json=body, headers=headers) if body is not None else client.post(url, headers=headers)
        assert resp.status_code == 403, f"{role} 调 {url} 应被拒绝，实际 {resp.status_code}"


def test_p1_10_user_role_can_still_write_after_gates(client, user_headers):
    """user 角色在补齐门禁后仍可正常调用写路径（consent 已授予、订阅未到期）。"""
    _seed_consent()
    resp = client.post("/v1/portraits/rebuild", json={"user_id": "u_demo"}, headers=user_headers)
    assert resp.status_code == 200
    resp = client.post("/v1/me/portraits/rebuild", json={}, headers=user_headers)
    assert resp.status_code == 200
    resp = client.post("/v1/profile/u_demo/rebuild", headers=user_headers)
    assert resp.status_code == 200
    resp = client.post("/v1/me/portraits/feedback", json=_feedback_payload(), headers=user_headers)
    assert resp.status_code == 200


# ============ P1-11：rebuild/feedback consent 门禁 + 订阅 402 门 ============

def test_p1_11_rebuild_and_feedback_rejected_after_consent_withdrawn(client, user_headers):
    """撤回 passive_sensing consent 后 rebuild/feedback 拒绝（412，对齐 ingest 语义）。"""
    _seed_consent(granted=True)
    _seed_consent(granted=False)  # 最新记录 = 撤回
    resp = client.post("/v1/portraits/rebuild", json={"user_id": "u_demo"}, headers=user_headers)
    assert resp.status_code == 412
    resp = client.post("/v1/me/portraits/rebuild", json={}, headers=user_headers)
    assert resp.status_code == 412
    resp = client.post("/v1/profile/u_demo/rebuild", headers=user_headers)
    assert resp.status_code == 412
    resp = client.post("/v1/me/portraits/feedback", json=_feedback_payload(), headers=user_headers)
    assert resp.status_code == 412


def test_p1_11_subscription_expired_blocks_rebuild_and_feedback(client, user_headers):
    """订阅显式到期 → rebuild/feedback 402（对齐 escalations/skills 订阅门）。"""
    _seed_consent()
    _set_subscription("u_demo", datetime.now(UTC) - timedelta(days=1))
    resp = client.post("/v1/portraits/rebuild", json={"user_id": "u_demo"}, headers=user_headers)
    assert resp.status_code == 402
    resp = client.post("/v1/me/portraits/rebuild", json={}, headers=user_headers)
    assert resp.status_code == 402
    resp = client.post("/v1/me/portraits/feedback", json=_feedback_payload(), headers=user_headers)
    assert resp.status_code == 402


# ============ P1-12：escalations cursor 分页 ============

def _seed_escalations() -> None:
    """7 条：5 条不同 opened_at + 2 条同 opened_at（tie-break by id）。"""
    with SessionLocal() as db:
        for i in range(5):
            db.add(Escalation(tenant_id="t_demo", user_id="u_demo", event_id=f"esc_audit_{i}",
                              level="L3", status="open", trigger="help_requested",
                              evidence_summary="audit",
                              opened_at=datetime(2026, 8, 10, 0, i, 0, tzinfo=UTC)))
        tie = datetime(2026, 8, 9, 12, 0, 0, tzinfo=UTC)
        for j in range(2):
            db.add(Escalation(tenant_id="t_demo", user_id="u_demo", event_id=f"esc_audit_tie{j}",
                              level="L3", status="open", trigger="help_requested",
                              evidence_summary="audit", opened_at=tie))
        db.commit()


def test_p1_12_cursor_pagination_roundtrip_no_dup_no_loss(client, professional_headers):
    """全量翻页不重不漏；cursor 时间部分 fromisoformat 可解析（DateTime 同型锁）；
    第二页 200（修复前 PG 下 str vs timestamp 比较 500）。"""
    _seed_escalations()
    seen: list[str] = []
    cursor: str | None = None
    for _ in range(10):
        params = {"limit": 2}
        if cursor:
            params["cursor"] = cursor
        resp = client.get("/v1/escalations", params=params, headers=professional_headers)
        assert resp.status_code == 200, f"cursor={cursor} 第二页起不得 500：{resp.text}"
        seen.extend(e["id"] for e in resp.json())
        next_cursor = resp.headers.get("X-Next-Cursor") or None
        if next_cursor is None:
            break
        # cursor 时间部分必须可解析回 datetime（P1-3 类型同型回归锁）
        opened_raw = next_cursor.split("_", 1)[0]
        datetime.fromisoformat(opened_raw)
        cursor = next_cursor
    assert len(seen) == 7, "全量遍历必须覆盖 7 条"
    assert len(set(seen)) == 7, "翻页不得重复"
    # 排序语义：opened_at desc（首条最新）
    with SessionLocal() as db:
        expected_first = db.scalar(select(Escalation).where(
            Escalation.event_id == "esc_audit_4"))
        assert seen[0] == expected_first.id


def test_p1_12_invalid_cursor_returns_422(client, professional_headers):
    resp = client.get("/v1/escalations", params={"cursor": "not-a-date_esc_1"},
                      headers=professional_headers)
    assert resp.status_code == 422


# ============ P1-13：/v1/me/messages 订阅门禁 ============

def test_p1_13_me_messages_requires_active_subscription(client, user_headers):
    """订阅显式到期 → 402；未设到期（机构旧用户）→ 200。"""
    _set_subscription("u_demo", datetime.now(UTC) - timedelta(days=1))
    resp = client.get("/v1/me/messages", headers=user_headers)
    assert resp.status_code == 402
    _set_subscription("u_demo", None)
    resp = client.get("/v1/me/messages", headers=user_headers)
    assert resp.status_code == 200
    assert "message" in resp.json()


# ============ P1-14：narrative 日界线统一用户本地日 ============

def test_p1_14_narrative_and_aggregate_share_local_day_boundary(client, user_headers):
    """UTC 20:00 = 上海次日 04:00：narrative.date 与 aggregate.local_date 必须同为
    本地日 2026-08-19（修复前 narrative 用 UTC 日 2026-08-18，两链路错位）。"""
    _seed_consent()
    ws = datetime(2026, 8, 18, 20, 0, 0, tzinfo=UTC)
    payload = {
        "event_id": "evt_p1_14_00001",
        "user_id": "u_demo",
        "schema_version": "passive-core-v1",
        "source": "screen",
        "window_start": ws.isoformat(),
        "window_end": (ws + timedelta(minutes=30)).isoformat(),
        "summary": "平稳",
        "vector": [0.1],
    }
    resp = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert resp.status_code == 200, resp.text
    with SessionLocal() as db:
        narrative = db.scalar(select(DailyNarrative).where(DailyNarrative.user_id == "u_demo"))
        agg = db.scalar(select(DailyBehaviorAggregate).where(
            DailyBehaviorAggregate.user_id == "u_demo"))
        assert narrative is not None and agg is not None
        assert narrative.date == date(2026, 8, 19), "narrative 必须归属用户本地日"
        assert agg.local_date == date(2026, 8, 19), "narrative 与 aggregate 日界线必须一致"
    # 查询：本地日命中；UTC 日 404（日界线一致的查询侧回归锁）
    local = client.get("/v1/narratives", params={"user_id": "u_demo", "date": "2026-08-19"},
                       headers=user_headers)
    assert local.status_code == 200
    assert len(local.json()["events"]) == 1
    utc_day = client.get("/v1/narratives", params={"user_id": "u_demo", "date": "2026-08-18"},
                         headers=user_headers)
    assert utc_day.status_code == 404


# ============ P1-15：DSR export 实现 ============

def test_p1_15_dsr_export_returns_user_derived_data(client, user_headers, admin_headers):
    """export 完成后 result_summary.export 含删除矩阵同范围的用户数据
    （portraits/aggregates/baselines/feedback/completions/narratives）。"""
    _seed_portrait_core_data()
    dsr_id = _open_dsr(client, user_headers, request_type="export", event_id="evt_audit_p15_exp")
    done = client.post(f"/v1/data-subject-requests/{dsr_id}/complete", json={}, headers=admin_headers)
    assert done.status_code == 200
    summary = done.json()["result_summary"]
    export = summary["export"]
    assert export["daily_portraits"], "导出产物必须包含画像记录"
    assert export["daily_portraits"][0]["status"] == "READY"
    assert "local_date" in export["daily_portraits"][0]
    assert export["daily_behavior_aggregates"]
    assert export["personal_baselines"]
    assert export["portrait_feedback"]
    assert export["skill_completions"]
    assert export["daily_narratives"]
    # 幂等重放返回同一产物
    again = client.post(f"/v1/data-subject-requests/{dsr_id}/complete", json={}, headers=admin_headers)
    assert again.status_code == 200
    assert again.json()["idempotent_replay"] is True
    assert again.json()["result_summary"]["export"] == export
    # 审计只记类别计数，不携带导出数据内容（最小必要审计）
    with SessionLocal() as db:
        aud = db.scalar(select(AuditEvent).where(
            AuditEvent.tenant_id == "t_demo",
            AuditEvent.action == "dsr.complete",
            AuditEvent.object_id == dsr_id,
        ).order_by(AuditEvent.occurred_at.desc()))
        assert aud is not None
        assert aud.metadata_json["export_counts"]["daily_portraits"] == 1
        assert "export" not in aud.metadata_json


def test_p1_15_dsr_export_result_summary_persisted(client, user_headers, admin_headers):
    """export 产物持久化在既有 DSR result_summary 存储字段（不新造存储机制）。"""
    _seed_portrait_core_data()
    dsr_id = _open_dsr(client, user_headers, request_type="export", event_id="evt_audit_p15b")
    client.post(f"/v1/data-subject-requests/{dsr_id}/complete", json={}, headers=admin_headers)
    with SessionLocal() as db:
        row = db.get(DataSubjectRequest, dsr_id)
        assert row.status == "completed"
        import json as _json
        stored = _json.loads(row.result_summary)
        assert stored["export"]["daily_portraits"][0]["status"] == "READY"
