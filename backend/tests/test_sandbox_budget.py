"""P2 沙箱算力预算测试。

覆盖场景：
- 超时转 failed：sandbox_timeout_seconds=1，run 内部 sleep 2s → status=failed + error_message 含 timeout
- 并发上限 429：mock scheduler 返回 None → 429 "sandbox concurrency limit reached"
- 速率限制 429：连续触发 11 次，第 11 次 429 "sandbox rate limit exceeded"（基于 SandboxRun.created_at）
"""
import time
from datetime import datetime, timezone

from app.database import SessionLocal
from app.models import User
from app.services.sandbox import schedule_sandbox_run
from app.services.sandbox.runner import SandboxRunner
import pytest
pytestmark = pytest.mark.sqlite_only  # 依赖 SQLite 内存/文件库语义，PG job 跳过



# ---------- P2.2 超时转 failed ----------

def test_runner_timeout_to_failed(monkeypatch):
    """超时转 failed：sandbox_timeout_seconds=1，audit_day sleep 2s。

    in-memory SQLite 回退为线程执行：主线程 1s 超时后标记 failed；
    `with` 块等待 worker 结束后主线程覆盖 status=failed。
    """
    import app.services.sandbox.runner as runner_mod
    settings = runner_mod.settings
    monkeypatch.setattr(settings, "sandbox_timeout_seconds", 1)

    from app.services.sandbox.audit_day import audit_day as original_audit_day

    def slow_audit_day(db, *, tenant_id, user_id, run_date):
        time.sleep(2)
        return original_audit_day(db, tenant_id=tenant_id, user_id=user_id, run_date=run_date)

    # v0.6：造工具回路迁至 worker.run_sandbox_loop，patch worker 模块的 audit_day
    monkeypatch.setattr("app.services.sandbox.worker.audit_day", slow_audit_day)

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
    # v0.6.1：路由拆分后 scheduler 位于 app.api.sandbox（语义不变）
    monkeypatch.setattr("app.api.sandbox.schedule_sandbox_run", lambda db, **kwargs: None)

    response = client.post("/v1/sandbox/runs", json={"user_id": "u_demo"}, headers=admin_headers)
    assert response.status_code == 429
    assert response.json()["detail"] == "sandbox concurrency limit reached"


# ---------- P2.4 速率限制 429 ----------

def test_rate_limit_429(client, admin_headers):
    """速率限制：最近 1 小时创建 10 个 run（不同 run_date），第 11 次 429。

    v0.6 基于 SandboxRun.created_at 计数；同一天调度幂等（同 tenant+user+date
    返回同一记录），因此用 10 个不同 run_date 累积计数。
    """
    from datetime import timedelta
    # 创建独立用户，避免与其他测试的速率计数冲突
    with SessionLocal() as db:
        if not db.get(User, "u_rate"):
            db.add(User(id="u_rate", tenant_id="t_demo", external_ref="rate"))
            db.commit()

    today = datetime.now(timezone.utc).date()
    for i in range(10):
        run_date = str(today - timedelta(days=9 - i))
        resp = client.post(
            "/v1/sandbox/runs",
            json={"user_id": "u_rate", "run_date": run_date},
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
