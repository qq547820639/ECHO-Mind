"""P2 沙箱算力预算测试。

覆盖场景：
- 超时转 failed：sandbox_timeout_seconds=1，run 内部 sleep 2s → status=failed + error_message 含 timeout
- 并发上限 429：mock scheduler 返回 None → 429 "sandbox concurrency limit reached"
- 速率限制 429：连续触发 11 次，第 11 次 429 "sandbox rate limit exceeded"
"""
import time
from datetime import datetime, timezone

from app.database import SessionLocal
from app.models import User
from app.services.sandbox import schedule_sandbox_run
from app.services.sandbox.runner import SandboxRunner


# ---------- P2.2 超时转 failed ----------

def test_runner_timeout_to_failed(monkeypatch):
    """超时转 failed：sandbox_timeout_seconds=1，audit_day sleep 2s。

    主线程 1s 超时后标记 failed；`with` 块 shutdown(wait=True) 等 worker
    结束后，主线程覆盖 status=failed + error_message="timeout after 1s"。
    """
    from app.config import get_settings
    # 直接引用 runner 模块绑定的 settings 对象，避免 get_settings.cache_clear()
    # 后返回不同实例导致 monkeypatch 不生效。
    import app.services.sandbox.runner as runner_mod
    settings = runner_mod.settings
    monkeypatch.setattr(settings, "sandbox_timeout_seconds", 1)

    from app.services.sandbox.audit_day import audit_day as original_audit_day

    def slow_audit_day(db, *, tenant_id, user_id, run_date):
        time.sleep(2)
        return original_audit_day(db, tenant_id=tenant_id, user_id=user_id, run_date=run_date)

    monkeypatch.setattr("app.services.sandbox.runner.audit_day", slow_audit_day)

    today = datetime.now(timezone.utc).date()
    with SessionLocal() as db:
        run = schedule_sandbox_run(db, tenant_id="t_demo", user_id="u_demo", run_date=today)
        db.commit()
        assert run.status == "pending"

        runner = SandboxRunner(db, tenant_id="t_demo", user_id="u_demo", run_id=run.id)
        result = runner.execute()
        db.commit()

        assert result.status == "failed"
        assert result.error_message is not None
        assert "timeout" in result.error_message
        assert "1" in result.error_message  # timeout after 1s


# ---------- P2.3/P2.4 并发上限 429 ----------

def test_concurrency_limit_429(client, admin_headers, monkeypatch):
    """并发上限：scheduler 返回 None（并发超限 sentinel）时路由返回 429。"""
    from app.api.routes import _sandbox_rate
    _sandbox_rate.clear()

    monkeypatch.setattr("app.api.routes.schedule_sandbox_run", lambda db, **kwargs: None)

    response = client.post("/v1/sandbox/runs", json={"user_id": "u_demo"}, headers=admin_headers)
    assert response.status_code == 429
    assert response.json()["detail"] == "sandbox concurrency limit reached"


# ---------- P2.4 速率限制 429 ----------

def test_rate_limit_429(client, admin_headers):
    """速率限制：连续触发 11 次（limit=10），第 11 次 429。"""
    from app.api.routes import _sandbox_rate
    _sandbox_rate.clear()

    # 创建独立用户，避免与其他测试的速率计数器冲突
    with SessionLocal() as db:
        if not db.get(User, "u_rate"):
            db.add(User(id="u_rate", tenant_id="t_demo", external_ref="rate"))
            db.commit()

    for i in range(10):
        resp = client.post(
            "/v1/sandbox/runs",
            json={"user_id": "u_rate"},
            headers=admin_headers,
        )
        assert resp.status_code == 200, f"call {i + 1} failed: {resp.text}"

    resp = client.post(
        "/v1/sandbox/runs",
        json={"user_id": "u_rate"},
        headers=admin_headers,
    )
    assert resp.status_code == 429
    assert resp.json()["detail"] == "sandbox rate limit exceeded"
