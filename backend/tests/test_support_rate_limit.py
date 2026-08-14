"""ERA 42 安全复核——支持请求创建频率上限契约测试。

语义：
- 非红色信号触发：每用户每小时 20 条上限，超限 429；
- 红色信号触发（危机/主动求助）豁免——429 永不阻断危机信号；
- 幂等重放（同 event_id）不计入窗口、超限后仍可重放。
"""

from __future__ import annotations


def make_payload(i: int, trigger: str = "schedule_help") -> dict[str, object]:
    return {
        "event_id": f"rate-limit-evt-{i:04d}",
        "user_id": "u_demo",
        "trigger": trigger,
        "evidence_summary": f"支持请求频率测试第 {i} 条摘要",
    }


def test_escalation_creation_rate_limited(client, user_headers):
    for i in range(20):
        resp = client.post("/v1/escalations", json=make_payload(i), headers=user_headers)
        assert resp.status_code == 200, f"第 {i} 条应创建成功：{resp.text}"

    resp = client.post("/v1/escalations", json=make_payload(20), headers=user_headers)
    assert resp.status_code == 429, resp.text


def test_red_signal_triggers_exempt_from_rate_limit(client, user_headers):
    for i in range(20):
        resp = client.post("/v1/escalations", json=make_payload(i), headers=user_headers)
        assert resp.status_code == 200

    # 危机信号：超限后仍必须放行（安全优先于限流）
    resp = client.post(
        "/v1/escalations",
        json=make_payload(30, trigger="l0_current_danger"),
        headers=user_headers,
    )
    assert resp.status_code == 200, resp.text
    resp = client.post(
        "/v1/escalations",
        json=make_payload(31, trigger="text_red_signal"),
        headers=user_headers,
    )
    assert resp.status_code == 200, resp.text


def test_idempotent_replay_not_limited(client, user_headers):
    for i in range(20):
        resp = client.post("/v1/escalations", json=make_payload(i), headers=user_headers)
        assert resp.status_code == 200

    # 重放既有 event_id：不计入窗口，仍返回幂等结果
    resp = client.post("/v1/escalations", json=make_payload(5), headers=user_headers)
    assert resp.status_code == 200
    assert resp.json().get("idempotent_replay") is True


def test_rate_limited_attempt_is_audited(client, user_headers):
    from app.auth import create_access_token

    auditor_headers = {"Authorization": f"Bearer {create_access_token('aud', 't_demo', 'auditor')}"}
    for i in range(20):
        client.post("/v1/escalations", json=make_payload(i), headers=user_headers)
    client.post("/v1/escalations", json=make_payload(99), headers=user_headers)

    # 审计链可检索到限流拒绝事件（最小必要审计，不泄露内部阈值）
    resp = client.get("/v1/audit/events", headers=auditor_headers)
    assert resp.status_code == 200
    actions = [item["action"] for item in resp.json()]
    assert "escalation.rate_limited" in actions
