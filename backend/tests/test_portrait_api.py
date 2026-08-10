"""Milestone E 测试：Portrait API（鉴权 / 租户隔离 / GET 无副作用 / rebuild / 响应形状 / 状态机）。"""
from datetime import UTC, datetime
from zoneinfo import ZoneInfo

from sqlalchemy import func, select

from app.auth import create_access_token
from app.database import SessionLocal
from app.models import DailyPortrait

LOCAL_TODAY = datetime.now(UTC).astimezone(ZoneInfo("Asia/Shanghai")).date()


def _portrait_count() -> int:
    with SessionLocal() as db:
        return db.scalar(select(func.count()).select_from(DailyPortrait)) or 0


def test_requires_auth(client):
    response = client.get("/v1/portraits/today", params={"user_id": "u_demo"})
    assert response.status_code == 401


def test_tenant_isolation(client):
    outsider = {"Authorization": f"Bearer {create_access_token('u_demo', 't_other', 'user')}"}
    response = client.get("/v1/portraits/today", params={"user_id": "u_demo"}, headers=outsider)
    assert response.status_code == 404
    response = client.post("/v1/portraits/rebuild", json={"user_id": "u_demo"}, headers=outsider)
    assert response.status_code == 404


def test_user_cannot_rebuild_other_user(client, user_headers):
    response = client.post("/v1/portraits/rebuild", json={"user_id": "u_other"}, headers=user_headers)
    assert response.status_code == 403


def test_get_today_no_side_effect(client, user_headers):
    """GET /portraits/today 前后 daily_portraits 计数不变（只读轻量视图）。"""
    before = _portrait_count()
    response = client.get("/v1/portraits/today", params={"user_id": "u_demo"}, headers=user_headers)
    assert response.status_code == 200
    after = _portrait_count()
    assert before == after
    body = response.json()
    assert body["date"] == str(LOCAL_TODAY)
    assert body["timezone_used"] == "Asia/Shanghai"


def test_new_user_warming_up_state(client, user_headers):
    """新用户（无基线）显式重建 → WARMING_UP。"""
    response = client.post("/v1/portraits/rebuild", json={"user_id": "u_demo"}, headers=user_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "WARMING_UP"
    assert body["confidence"] == "LOW"


def test_rebuild_response_shape(client, user_headers):
    response = client.post("/v1/portraits/rebuild", json={"user_id": "u_demo"}, headers=user_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["date"] == str(LOCAL_TODAY)
    assert body["status"] in ("WARMING_UP", "EARLY_BASELINE", "READY", "PARTIAL_DATA", "LOW_CONFIDENCE")
    assert body["confidence"] in ("HIGH", "MEDIUM", "LOW")
    assert isinstance(body["baseline_days"], int)
    assert body["baseline_version"] in (None, "base-v1")
    assert isinstance(body["headline"], list)
    assert isinstance(body["summary"], str)
    assert isinstance(body["dimensions"], dict)
    assert "coverage_score" in body["coverage"]
    assert isinstance(body["facts"], list)
    assert body["timezone_used"] == "Asia/Shanghai"
    # 重建写库
    assert _portrait_count() == 1


def test_rebuild_is_idempotent(client, user_headers):
    r1 = client.post("/v1/portraits/rebuild", json={"user_id": "u_demo"}, headers=user_headers)
    r2 = client.post("/v1/portraits/rebuild", json={"user_id": "u_demo"}, headers=user_headers)
    assert r1.status_code == 200 and r2.status_code == 200
    assert _portrait_count() == 1


def test_rebuild_with_local_date(client, user_headers):
    response = client.post("/v1/portraits/rebuild",
                           json={"user_id": "u_demo", "local_date": "2026-08-10"},
                           headers=user_headers)
    assert response.status_code == 200
    assert response.json()["date"] == "2026-08-10"


def test_get_today_after_rebuild_returns_full(client, user_headers):
    client.post("/v1/portraits/rebuild", json={"user_id": "u_demo"}, headers=user_headers)
    response = client.get("/v1/portraits/today", params={"user_id": "u_demo"}, headers=user_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "WARMING_UP"
    assert body["dimensions"] == {}
    assert body["summary"] != ""


def test_list_portraits_sorted_ascending(client, user_headers):
    client.post("/v1/portraits/rebuild", json={"user_id": "u_demo"}, headers=user_headers)
    response = client.get("/v1/portraits", params={"user_id": "u_demo", "days": 7}, headers=user_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["user_id"] == "u_demo"
    assert body["days"] == 7
    assert isinstance(body["portraits"], list)
    dates = [p["date"] for p in body["portraits"]]
    assert dates == sorted(dates)
    assert dates == [str(LOCAL_TODAY)]
    assert "coverage" in body["portraits"][0]


def test_list_portraits_days_validation(client, user_headers):
    assert client.get("/v1/portraits", params={"user_id": "u_demo", "days": 0},
                      headers=user_headers).status_code == 422
    assert client.get("/v1/portraits", params={"user_id": "u_demo", "days": 91},
                      headers=user_headers).status_code == 422


def test_baseline_status_shape(client, user_headers):
    response = client.get("/v1/baseline/status", params={"user_id": "u_demo"}, headers=user_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["status"] in ("WARMING_UP", "EARLY_BASELINE", "BASELINE_READY")
    assert isinstance(body["baseline_days"], int)
    assert "baseline_version" in body
    assert "window_start" in body
    assert "window_end" in body
    assert body["bucket_usage"] in ("weekday", "weekend", "all_days")
    assert isinstance(body["today_coverage"], float)


def test_professional_can_rebuild_for_user(client, professional_headers):
    response = client.post("/v1/portraits/rebuild", json={"user_id": "u_demo"},
                           headers=professional_headers)
    assert response.status_code == 200
    assert response.json()["status"] == "WARMING_UP"


def test_rebuild_writes_audit(client, user_headers):
    from app.models import AuditEvent

    client.post("/v1/portraits/rebuild", json={"user_id": "u_demo"}, headers=user_headers)
    with SessionLocal() as db:
        events = db.scalars(select(AuditEvent).where(
            AuditEvent.tenant_id == "t_demo",
            AuditEvent.action == "portrait.rebuild",
        )).all()
        assert len(events) == 1
        assert events[0].object_type == "daily_portrait"
