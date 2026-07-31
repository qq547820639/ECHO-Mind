"""T12.5 旧主动输入写入入口停用回归。

断言 T12.1 中停用的 7 个写入路由在有效身份下返回 410 Gone（而非 404），
且认证链仍生效（无 token → 401）。同时验证保留的 GET 查询路由仍可读取历史。
"""
from datetime import datetime, timezone

from app.database import SessionLocal
from app.models import JournalEntry
from app.services.crypto import encrypt_text


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def test_checkin_write_returns_410(client, user_headers):
    response = client.post("/v1/checkins", json={
        "event_id": "evt_deprecated_checkin", "user_id": "u_demo", "mood": 3, "stress": 2,
        "energy": 3, "sleep_recovery": 2,
        "client_time": _now(), "device_timezone": "Asia/Shanghai",
    }, headers=user_headers)
    assert response.status_code == 410
    assert "已停用" in response.json()["detail"]


def test_journal_write_returns_410(client, user_headers):
    response = client.post("/v1/journals", json={
        "event_id": "evt_deprecated_journal", "user_id": "u_demo",
        "logical_id": "journal_deprecated_001", "body": "今天散步了",
        "client_time": _now(),
    }, headers=user_headers)
    assert response.status_code == 410


def test_journal_revision_returns_410(client, user_headers):
    response = client.post("/v1/journals/journal_deprecated_001/revisions", json={
        "event_id": "evt_deprecated_revise", "body": "今天散步了十分钟",
        "client_time": _now(),
    }, headers=user_headers)
    assert response.status_code == 410


def test_journal_delete_returns_410(client, user_headers):
    response = client.delete("/v1/journals/journal_deprecated_001", headers=user_headers)
    assert response.status_code == 410


def test_safety_check_returns_410(client, user_headers):
    response = client.post("/v1/safety/check", json={
        "user_id": "u_demo", "text": "最近有些疲惫",
    }, headers=user_headers)
    assert response.status_code == 410


def test_questionnaire_response_returns_410(client, user_headers):
    response = client.post("/v1/questionnaires/phq9/responses", json={
        "event_id": "evt_deprecated_phq9", "user_id": "u_demo",
        "answers": [0, 0, 0, 0, 0, 0, 0, 0, 0],
    }, headers=user_headers)
    assert response.status_code == 410


def test_practice_completion_returns_410(client, user_headers):
    response = client.post("/v1/practices/completions", json={
        "event_id": "evt_deprecated_practice", "user_id": "u_demo",
        "practice_id": "breathing-01", "content_version": "1.0",
        "status": "completed", "duration_seconds": 120,
        "client_time": _now(),
    }, headers=user_headers)
    assert response.status_code == 410


def test_deprecated_routes_still_require_auth(client):
    # 认证链仍生效：无 token 访问停用路由返回 401，而非 410/404。
    response = client.post("/v1/checkins", json={
        "event_id": "evt_no_auth_checkin", "user_id": "u_demo", "mood": 3, "stress": 2,
        "energy": 3, "sleep_recovery": 2,
        "client_time": _now(), "device_timezone": "Asia/Shanghai",
    })
    assert response.status_code == 401


def test_get_journals_still_reads_history(client, user_headers):
    # GET /v1/journals 保留：直接播种一条历史日记，查询应返回 200 且可解密正文。
    with SessionLocal() as db:
        db.add(JournalEntry(
            event_id="evt_history_journal_001",
            tenant_id="t_demo",
            user_id="u_demo",
            logical_id="journal_history_001",
            revision=1,
            body_ciphertext=encrypt_text("历史日记正文", aad="t_demo:u_demo:journal"),
            event_tags=["activity"],
            client_time=datetime.now(timezone.utc),
        ))
        db.commit()
    response = client.get("/v1/journals?user_id=u_demo", headers=user_headers)
    assert response.status_code == 200
    rows = response.json()
    assert any(r["logical_id"] == "journal_history_001" for r in rows)
    assert rows[0]["body"] == "历史日记正文"


def test_get_trends_summary_still_available(client, user_headers):
    # GET /v1/trends/summary 保留（T12 未移除查询路由）。
    response = client.get("/v1/trends/summary?user_id=u_demo", headers=user_headers)
    assert response.status_code == 200
