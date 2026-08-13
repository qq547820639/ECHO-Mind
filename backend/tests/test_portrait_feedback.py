"""Portrait feedback 端点测试（v0.7 Phase 6.6）。

覆盖：鉴权 / LIKE·NOT_LIKE 合法 / 非法 feedback 422 / 幂等重放 /
租户隔离 / principal 用户隔离（body user_id 被忽略）/ GET 无副作用 / 审计写入。
"""
from datetime import UTC, datetime
from zoneinfo import ZoneInfo

from sqlalchemy import func, select

from app.auth import create_access_token
from app.database import SessionLocal
from app.models import PortraitFeedback

LOCAL_TODAY = datetime.now(UTC).astimezone(ZoneInfo("Asia/Shanghai")).date()


def _feedback_count() -> int:
    with SessionLocal() as db:
        return db.scalar(select(func.count()).select_from(PortraitFeedback)) or 0


def _payload(**overrides) -> dict:
    payload = {
        "event_id": "pf_test_00000001",
        "user_id": "u_demo",
        "portrait_id": str(LOCAL_TODAY),
        "feedback": "LIKE",
        "portrait_schema_version": "portrait-v1",
        "created_at": "2026-08-13T00:00:00Z",
    }
    payload.update(overrides)
    return payload


def test_requires_auth(client):
    response = client.post("/v1/me/portraits/feedback", json=_payload())
    assert response.status_code == 401


def test_like_and_not_like_accepted(client, user_headers):
    r1 = client.post("/v1/me/portraits/feedback", json=_payload(feedback="LIKE"), headers=user_headers)
    assert r1.status_code == 200
    assert r1.json()["idempotent_replay"] is False
    r2 = client.post(
        "/v1/me/portraits/feedback",
        json=_payload(event_id="pf_test_00000002", feedback="NOT_LIKE"),
        headers=user_headers,
    )
    assert r2.status_code == 200
    assert _feedback_count() == 2


def test_invalid_feedback_rejected(client, user_headers):
    assert client.post("/v1/me/portraits/feedback", json=_payload(feedback="MAYBE"),
                       headers=user_headers).status_code == 422
    assert client.post("/v1/me/portraits/feedback", json=_payload(feedback="like"),
                       headers=user_headers).status_code == 422


def test_invalid_portrait_id_rejected(client, user_headers):
    assert client.post("/v1/me/portraits/feedback", json=_payload(portrait_id="not-a-date"),
                       headers=user_headers).status_code == 422


def test_idempotent_replay(client, user_headers):
    payload = _payload()
    r1 = client.post("/v1/me/portraits/feedback", json=payload, headers=user_headers)
    r2 = client.post("/v1/me/portraits/feedback", json=payload, headers=user_headers)
    assert r1.status_code == 200 and r2.status_code == 200
    assert r1.json()["idempotent_replay"] is False
    assert r2.json()["idempotent_replay"] is True
    assert r1.json()["id"] == r2.json()["id"]
    assert _feedback_count() == 1


def test_tenant_isolation(client):
    outsider = {"Authorization": f"Bearer {create_access_token('u_demo', 't_other', 'user')}"}
    assert client.post("/v1/me/portraits/feedback", json=_payload(), headers=outsider).status_code == 404
    assert _feedback_count() == 0


def test_body_user_id_is_ignored(client, user_headers):
    """body.user_id 被忽略：反馈归属 principal.subject，而非 body 声称的用户。"""
    response = client.post("/v1/me/portraits/feedback", json=_payload(user_id="u_other"),
                           headers=user_headers)
    assert response.status_code == 200
    with SessionLocal() as db:
        row = db.scalar(select(PortraitFeedback))
        assert row.user_id == "u_demo"
        assert row.local_date == LOCAL_TODAY
        assert row.feedback == "LIKE"
        assert row.portrait_schema_version == "portrait-v1"


def test_get_no_side_effect(client, user_headers):
    """GET /v1/me/portraits/today 不写 portrait_feedback。"""
    before = _feedback_count()
    assert client.get("/v1/me/portraits/today", headers=user_headers).status_code == 200
    assert _feedback_count() == before


def test_feedback_writes_audit(client, user_headers):
    from app.models import AuditEvent

    client.post("/v1/me/portraits/feedback", json=_payload(), headers=user_headers)
    with SessionLocal() as db:
        events = db.scalars(select(AuditEvent).where(
            AuditEvent.tenant_id == "t_demo",
            AuditEvent.action == "portrait.feedback",
        )).all()
        assert len(events) == 1
        assert events[0].object_type == "portrait_feedback"
        assert events[0].actor_id == "u_demo"
