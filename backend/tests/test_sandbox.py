"""T08 自进化沙箱骨架测试。"""
from datetime import datetime, timedelta, timezone

from app.auth import create_access_token
from app.database import SessionLocal
from app.models import DerivedFeature
from app.services.profile import build_daily_narrative
from app.services.sandbox import audit_day, schedule_sandbox_run
from app.services.sandbox.runner import SandboxRunner


def _admin_headers() -> dict:
    return {"Authorization": f"Bearer {create_access_token('admin', 't_demo', 'admin')}"}


def _professional_headers() -> dict:
    return {"Authorization": f"Bearer {create_access_token('pro', 't_demo', 'professional')}"}


def test_admin_can_schedule_sandbox_run(client):
    """admin 能触发 sandbox run。"""
    response = client.post("/v1/sandbox/runs", json={"user_id": "u_demo"}, headers=_admin_headers())
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "pending"
    assert body["user_id"] == "u_demo"
    assert body["id"].startswith("sr_")


def test_professional_can_schedule_sandbox_run(client):
    """professional 也能触发 sandbox run。"""
    response = client.post("/v1/sandbox/runs", json={"user_id": "u_demo"}, headers=_professional_headers())
    assert response.status_code == 200
    assert response.json()["status"] == "pending"


def test_plain_user_cannot_schedule_sandbox_run(client, user_headers):
    """普通 user 角色触发 sandbox run 返回 403。"""
    response = client.post("/v1/sandbox/runs", json={"user_id": "u_demo"}, headers=user_headers)
    assert response.status_code == 403


def test_schedule_sandbox_run_is_idempotent_per_day(client):
    """同一天重复触发幂等：返回同一条 run。"""
    first = client.post("/v1/sandbox/runs", json={"user_id": "u_demo"}, headers=_admin_headers())
    assert first.status_code == 200
    second = client.post("/v1/sandbox/runs", json={"user_id": "u_demo"}, headers=_admin_headers())
    assert second.status_code == 200
    assert first.json()["id"] == second.json()["id"]


def test_get_sandbox_run_cross_tenant_404(client):
    """跨租户 GET /v1/sandbox/runs/{id} 返回 404。"""
    created = client.post("/v1/sandbox/runs", json={"user_id": "u_demo"}, headers=_admin_headers())
    run_id = created.json()["id"]
    outsider_headers = {"Authorization": f"Bearer {create_access_token('u_demo', 't_other', 'user')}"}
    response = client.get(f"/v1/sandbox/runs/{run_id}", headers=outsider_headers)
    assert response.status_code == 404


def test_get_sandbox_run_owner_can_read(client, user_headers):
    """属主用户能读取自己的 sandbox run。"""
    created = client.post("/v1/sandbox/runs", json={"user_id": "u_demo"}, headers=_admin_headers())
    run_id = created.json()["id"]
    response = client.get(f"/v1/sandbox/runs/{run_id}", headers=user_headers)
    assert response.status_code == 200
    assert response.json()["id"] == run_id


def test_audit_day_reads_daily_data():
    """audit_day 能读取当日 DerivedFeature + DailyNarrative。"""
    today = datetime.now(timezone.utc).date()
    noon = datetime.now(timezone.utc).replace(hour=12, minute=0, second=0, microsecond=0)
    with SessionLocal() as db:
        feature = DerivedFeature(
            tenant_id="t_demo",
            user_id="u_demo",
            event_id="evt_sandbox_audit_0001",
            schema_version="feat-v1",
            source="screen",
            window_start=noon,
            window_end=noon + timedelta(minutes=30),
            summary="夜间屏幕使用增多，情绪疲惫",
            vector=[0.1, 0.2],
        )
        db.add(feature)
        db.flush()
        build_daily_narrative(db, tenant_id="t_demo", user_id="u_demo", date=today)
        db.commit()

        summary = audit_day(db, tenant_id="t_demo", user_id="u_demo", run_date=today)
        assert summary["feature_count"] >= 1
        assert summary["narrative_mood_hint"] == "偏低"
        assert summary["gaps"] == []


def test_runner_execute_state_transition():
    """SandboxRunner.execute() 状态流转 pending→running→completed。"""
    today = datetime.now(timezone.utc).date()
    with SessionLocal() as db:
        run = schedule_sandbox_run(
            db, tenant_id="t_demo", user_id="u_demo", run_date=today
        )
        db.commit()
        assert run.status == "pending"

        runner = SandboxRunner(
            db, tenant_id="t_demo", user_id="u_demo", run_id=run.id
        )
        result = runner.execute()
        db.commit()

        assert result.status == "completed"
        assert result.started_at is not None
        assert result.completed_at is not None


def test_runner_rejects_cross_tenant_run():
    """SandboxRunner 拒绝跨租户/跨用户的 run。"""
    today = datetime.now(timezone.utc).date()
    with SessionLocal() as db:
        run = schedule_sandbox_run(
            db, tenant_id="t_demo", user_id="u_demo", run_date=today
        )
        db.commit()
        try:
            SandboxRunner(db, tenant_id="t_other", user_id="u_demo", run_id=run.id)
            raise AssertionError("expected ValueError for cross-tenant run")
        except ValueError:
            pass
