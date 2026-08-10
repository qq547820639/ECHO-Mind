"""T04 沙箱隔离测试（v0.6）。

覆盖：
- 子进程真超时终止：注入 sleep 的 worker → run.status=="failed" 且
  worker 进程不再存活（pidfile + os.kill(pid, 0) 断言），不残留
- 子进程成功路径：文件型 SQLite 下完整回路 completed
- 并发配额覆盖执行期：4 个 running run → 第 5 个 POST 429
"""
import os
import time
from datetime import UTC

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.auth import create_access_token
from app.database import Base, SessionLocal
from app.models import SandboxRun, User
from app.services.sandbox import schedule_sandbox_run
from app.services.sandbox.runner import SandboxRunner


def _make_file_db(tmp_path, name: str):
    """创建文件型 SQLite 引擎并建表，返回 (db_file, engine)。"""
    db_file = tmp_path / name
    engine = create_engine(f"sqlite:///{db_file}")
    Base.metadata.create_all(bind=engine)
    return db_file, engine


def _admin_headers() -> dict:
    return {"Authorization": f"Bearer {create_access_token('admin', 't_demo', 'admin')}"}


def _create_file_user(engine, user_id: str = "u_iso") -> None:
    from app.models import Tenant
    from app.models import User as UserModel

    session_factory = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    with session_factory() as db:
        if not db.get(Tenant, "t_iso"):
            db.add(Tenant(id="t_iso", name="Iso"))
        if not db.get(UserModel, user_id):
            db.add(UserModel(id=user_id, tenant_id="t_iso", external_ref="iso"))
        db.commit()


def test_sandbox_worker_true_termination_on_timeout(tmp_path, monkeypatch):
    """注入 sleep 的 worker：超时 → status=failed 且 worker 进程真终止。"""
    _, engine = _make_file_db(tmp_path, "sandbox_timeout.db")
    _create_file_user(engine, "u_timeout")

    session_factory = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    with session_factory() as db:
        run = schedule_sandbox_run(db, tenant_id="t_iso", user_id="u_timeout",
                                    run_date=None)
        db.commit()
        run_id = run.id

    pidfile = tmp_path / "sandbox_worker.pid"
    monkeypatch.setenv("SANDBOX_WORKER_SLEEP_SECONDS", "30")
    monkeypatch.setenv("SANDBOX_WORKER_PIDFILE", str(pidfile))

    import app.services.sandbox.runner as runner_mod
    monkeypatch.setattr(runner_mod.settings, "sandbox_timeout_seconds", 1)

    with session_factory() as db:
        runner = SandboxRunner(db, tenant_id="t_iso", user_id="u_timeout", run_id=run_id)
        result = runner.execute()
        db.commit()
        assert result.status == "failed"
        assert result.error_message is not None
        assert "timeout" in result.error_message

    # worker 进程已写入 pidfile 且已被 terminate/kill（不再存活）
    assert pidfile.exists(), "worker 未写入 pidfile"
    pid = int(pidfile.read_text().strip())
    deadline = time.time() + 5
    alive = True
    while time.time() < deadline:
        try:
            os.kill(pid, 0)
            time.sleep(0.1)
        except ProcessLookupError:
            alive = False
            break
        except PermissionError:
            # 进程存在但属于其他用户（不可能发生于子进程）；视为仍存活
            pass
    assert not alive, f"worker 进程 {pid} 超时后仍存活（未真终止）"


def test_sandbox_worker_subprocess_success(tmp_path, monkeypatch):
    """子进程隔离执行完整回路：status=completed，产物落库。"""
    from app.models import Skill as SkillModel

    _, engine = _make_file_db(tmp_path, "sandbox_success.db")
    _create_file_user(engine, "u_ok")
    session_factory = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    with session_factory() as db:
        run = schedule_sandbox_run(db, tenant_id="t_iso", user_id="u_ok", run_date=None)
        db.commit()
        run_id = run.id

    import app.services.sandbox.runner as runner_mod
    monkeypatch.setattr(runner_mod.settings, "sandbox_timeout_seconds", 30)

    with session_factory() as db:
        runner = SandboxRunner(db, tenant_id="t_iso", user_id="u_ok", run_id=run_id)
        result = runner.execute()
        db.commit()
        assert result.status == "completed"
        assert result.error_message is None
        assert result.started_at is not None
        assert result.completed_at is not None
        assert len(result.gaps_found) > 0
        skills = db.query(SkillModel).filter_by(tenant_id="t_iso", user_id="u_ok").count()
        assert skills > 0


def test_sandbox_concurrency_covers_execution_period(client, admin_headers):
    """执行期并发配额：4 个 running run（tenant 上限 4）→ 第 5 个 POST 429。"""
    from datetime import datetime, timedelta

    today = datetime.now(UTC).date()
    with SessionLocal() as db:
        if not db.get(User, "u_conc_exec"):
            db.add(User(id="u_conc_exec", tenant_id="t_demo", external_ref="conc_exec"))
        # 清掉同租户可能遗留的 running run，保证计数精确
        db.query(SandboxRun).filter(
            SandboxRun.tenant_id == "t_demo",
            SandboxRun.status == "running",
        ).delete(synchronize_session=False)
        for i in range(4):
            # 用不同 run_date 避免 unique(tenant,user,run_date) 冲突
            db.add(SandboxRun(tenant_id="t_demo", user_id="u_conc_exec",
                              run_date=today - timedelta(days=3 - i),
                              status="running", created_at=datetime.now(UTC)))
        db.commit()

    response = client.post("/v1/sandbox/runs", json={"user_id": "u_conc_exec"}, headers=_admin_headers())
    assert response.status_code == 429
    assert response.json()["detail"] == "sandbox concurrency limit reached"
