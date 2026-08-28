"""ERA 42 安全复核 + 2026-08-28 P0-3 收口——支持请求创建频率上限契约测试。

语义（P0-3 收口后）：
- 客户端提交的 ``trigger`` 只是**声明标签**，不再具备自证豁免能力：
  即使自称 ``l0_current_danger`` / ``text_red_signal``，无服务端证据同样受限（429）；
- 豁免**只**授予服务端可验证的危机证据，且证据必须新鲜
  （L0 准入筛查 current_danger=True / 服务端红色 RiskSignal，30 分钟回溯窗口）；
- 真实危机信号永不被限流（服务端证据存在即放行）；
- 幂等重放（同 event_id）不计入窗口、超限后仍可重放；
- 伪造/误报留痕：自称危机触发但无证据 → 审计动作 ``escalation.exemption_denied``；
  豁免放行留痕 → ``escalation.rate_limit_exempted``。
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import select

from app.models import AuditEvent, RiskSignal
from app.services.escalation import ESCALATION_EXEMPTION_LOOKBACK


def make_payload(i: int, trigger: str = "schedule_help") -> dict[str, object]:
    return {
        "event_id": f"rate-limit-evt-{i:04d}",
        "user_id": "u_demo",
        "trigger": trigger,
        "evidence_summary": f"支持请求频率测试第 {i} 条摘要",
    }


def _fill_window(client, user_headers) -> None:
    """用满 20/h 窗口（前 20 条必须全部成功）。"""
    for i in range(20):
        resp = client.post("/v1/escalations", json=make_payload(i), headers=user_headers)
        assert resp.status_code == 200, f"第 {i} 条应创建成功：{resp.text}"


def _post_l0_screening(client, user_headers, event_id: str = "l0-evt-0001") -> None:
    resp = client.post("/v1/onboarding/l0", json={
        "event_id": event_id,
        "user_id": "u_demo",
        "current_danger": True,
    }, headers=user_headers)
    assert resp.status_code == 200, resp.text
    assert resp.json()["decision"] == "urgent_human"


def test_escalation_creation_rate_limited(client, user_headers):
    _fill_window(client, user_headers)
    resp = client.post("/v1/escalations", json=make_payload(20), headers=user_headers)
    assert resp.status_code == 429, resp.text


def test_client_declared_crisis_trigger_no_longer_exempts(client, user_headers):
    """P0-3 回归：客户端伪造危机触发词不得绕过 20/h 限流。

    修复前，任意客户端只要把 trigger 设为 ``l0_current_danger`` 即可无限创建
    L3 支持请求（刷值班队列 / 掩盖真实红色信号）。修复后判定权归服务端。
    """
    _fill_window(client, user_headers)

    for i, trigger in enumerate(("l0_current_danger", "text_red_signal"), start=30):
        resp = client.post(
            "/v1/escalations", json=make_payload(i, trigger=trigger), headers=user_headers
        )
        assert resp.status_code == 429, f"自称 {trigger} 不应获得豁免：{resp.text}"


def test_self_service_help_requested_is_rate_limited(client, user_headers):
    """Me 页「请求人工支持」按钮是自助动作，恒计入频控窗口。"""
    _fill_window(client, user_headers)
    resp = client.post(
        "/v1/escalations", json=make_payload(40, trigger="help_requested"), headers=user_headers
    )
    assert resp.status_code == 429, resp.text


def test_server_verified_l0_screening_exempts_from_rate_limit(client, user_headers):
    """服务端自己写入的 L0 当前危险筛查 → 危机通道必须放行（安全优先于限流）。"""
    _post_l0_screening(client, user_headers)
    _fill_window(client, user_headers)

    resp = client.post(
        "/v1/escalations", json=make_payload(50, trigger="help_requested"), headers=user_headers
    )
    assert resp.status_code == 200, f"存在服务端危机证据时必须放行：{resp.text}"

    auditor_headers = _auditor_headers()
    events = client.get("/v1/audit/events", headers=auditor_headers).json()
    actions = [item["action"] for item in events]
    assert "escalation.rate_limit_exempted" in actions


def test_server_verified_red_risk_signal_exempts_from_rate_limit(client, user_headers, db_session):
    """服务端红色 RiskSignal → 危机通道放行（服务端文本/被动评估落地后即生效）。"""
    db_session.add(RiskSignal(
        id="risk_exempt_1",
        tenant_id="t_demo",
        user_id="u_demo",
        source="server_safety_eval",
        severity="red",
        rule_pack_version="safety-rules-2026.07.2",
        created_at=datetime.now(timezone.utc),
    ))
    db_session.commit()

    _fill_window(client, user_headers)
    resp = client.post(
        "/v1/escalations", json=make_payload(60, trigger="help_requested"), headers=user_headers
    )
    assert resp.status_code == 200, resp.text


def test_stale_crisis_evidence_does_not_exempt(client, user_headers, db_session):
    """超过回溯窗口的陈旧筛查不得充当永久免限流通行证。"""
    db_session.add(RiskSignal(
        id="risk_stale_1",
        tenant_id="t_demo",
        user_id="u_demo",
        source="server_safety_eval",
        severity="red",
        rule_pack_version="safety-rules-2026.07.2",
        created_at=datetime.now(timezone.utc) - ESCALATION_EXEMPTION_LOOKBACK - timedelta(minutes=1),
    ))
    db_session.commit()

    _fill_window(client, user_headers)
    resp = client.post(
        "/v1/escalations", json=make_payload(70, trigger="help_requested"), headers=user_headers
    )
    assert resp.status_code == 429, resp.text


def test_cross_user_crisis_evidence_does_not_exempt(client, user_headers, db_session):
    """他人（同租户）的危机证据不得为本人解锁豁免。"""
    db_session.add(RiskSignal(
        id="risk_other_1",
        tenant_id="t_demo",
        user_id="u_other",
        source="server_safety_eval",
        severity="red",
        rule_pack_version="safety-rules-2026.07.2",
        created_at=datetime.now(timezone.utc),
    ))
    db_session.commit()

    _fill_window(client, user_headers)
    resp = client.post(
        "/v1/escalations", json=make_payload(80, trigger="help_requested"), headers=user_headers
    )
    assert resp.status_code == 429, resp.text


def test_forged_crisis_trigger_is_audited(client, user_headers, db_session):
    """自称危机触发但服务端无证据 → 留痕（值班可据此识别伪造/误报）。

    注：GET /v1/audit/events 按数据最小化原则不返回 metadata，故此处直接查库断言。
    """
    _fill_window(client, user_headers)
    resp = client.post(
        "/v1/escalations", json=make_payload(90, trigger="text_red_signal"), headers=user_headers
    )
    assert resp.status_code == 429

    denied = db_session.scalars(
        select(AuditEvent).where(
            AuditEvent.tenant_id == "t_demo",
            AuditEvent.action == "escalation.exemption_denied",
        )
    ).all()
    assert len(denied) == 1
    assert denied[0].metadata_json["declared_trigger"] == "text_red_signal"
    assert denied[0].metadata_json["reason"] == "no_server_verifiable_crisis_evidence"


def test_idempotent_replay_not_limited(client, user_headers):
    for i in range(20):
        resp = client.post("/v1/escalations", json=make_payload(i), headers=user_headers)
        assert resp.status_code == 200

    # 重放既有 event_id：不计入窗口，仍返回幂等结果
    resp = client.post("/v1/escalations", json=make_payload(5), headers=user_headers)
    assert resp.status_code == 200
    assert resp.json().get("idempotent_replay") is True


def test_rate_limited_attempt_is_audited(client, user_headers):
    for i in range(20):
        client.post("/v1/escalations", json=make_payload(i), headers=user_headers)
    client.post("/v1/escalations", json=make_payload(99), headers=user_headers)

    # 审计链可检索到限流拒绝事件（最小必要审计，不泄露内部阈值）
    resp = client.get("/v1/audit/events", headers=_auditor_headers())
    assert resp.status_code == 200
    actions = [item["action"] for item in resp.json()]
    assert "escalation.rate_limited" in actions


def _auditor_headers() -> dict[str, str]:
    from app.auth import create_access_token

    return {"Authorization": f"Bearer {create_access_token('aud', 't_demo', 'auditor')}"}


@pytest.fixture
def db_session():
    from app.database import SessionLocal

    with SessionLocal() as db:
        yield db
