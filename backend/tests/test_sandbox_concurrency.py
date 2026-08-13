"""v0.6.1：沙箱租户执行槽并发配额回归测试。

覆盖（hardening 需求十）：
- 多 worker 并发启动不得突破 tenant max concurrency（原子 permit）
- pending 数量与 running concurrency 分离
- worker crash / 异常后释放（finally + 心跳过期回收）
- timeout / failed 均释放
- SQLite 文件库并发测试（多连接）；PG job 复用同一 UPDATE 语句
- 真实并发测试：线程并发 acquire 恰好放行 max 个
"""
import threading
from datetime import UTC, datetime, timedelta

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.config import get_settings
from app.database import Base
from app.models import SandboxRun, Tenant, TenantSandboxSlot, User
from app.services.sandbox.runner import SandboxRunner
from app.services.sandbox.scheduler import schedule_sandbox_run
from app.services.sandbox.slots import acquire_tenant_slot, release_tenant_slot, running_count


def _file_engine():
    import tempfile

    tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
    tmp.close()
    engine = create_engine(
        f"sqlite:///{tmp.name}",
        connect_args={"check_same_thread": False, "timeout": 30},
        pool_pre_ping=True,
    )
    Base.metadata.create_all(bind=engine)
    factory = sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)
    with factory() as db:
        db.add(Tenant(id="t_sandbox", name="Sandbox Demo"))
        for i in range(6):
            db.add(User(id=f"u_sb{i}", tenant_id="t_sandbox", external_ref=f"sb-{i}"))
        db.commit()
    return tmp.name, factory


def test_concurrent_acquire_never_exceeds_max():
    """真实并发：N 个线程同时 acquire，恰好放行 max 个，其余全部失败。"""
    from app.services.sandbox.slots import acquire_tenant_slot

    max_concurrent = get_settings().sandbox_max_concurrent  # 默认 4
    _, factory = _file_engine()
    results: list[bool] = []
    lock = threading.Lock()

    def try_acquire() -> None:
        with factory() as db:
            ok = acquire_tenant_slot(db, "t_sandbox")
            db.commit()
            with lock:
                results.append(ok)

    threads = [threading.Thread(target=try_acquire) for _ in range(12)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert results.count(True) == max_concurrent, f"并发 acquire 应恰好放行 {max_concurrent}: {results}"
    with factory() as db:
        assert running_count(db, "t_sandbox") == max_concurrent


def test_release_decrements_and_heartbeat_refreshes():
    _, factory = _file_engine()
    with factory() as db:
        assert acquire_tenant_slot(db, "t_sandbox") is True
        assert acquire_tenant_slot(db, "t_sandbox") is True
        db.commit()
        release_tenant_slot(db, "t_sandbox")
        db.commit()
        assert running_count(db, "t_sandbox") == 1
        row = db.get(TenantSandboxSlot, "t_sandbox")
        assert row.heartbeat_at is not None


def test_stale_slot_reclaimed_after_worker_crash():
    """worker crash（无 finally 释放）后：心跳过期 → acquire 时回收。"""
    _, factory = _file_engine()
    with factory() as db:
        db.add(TenantSandboxSlot(tenant_id="t_sandbox", running_count=3, heartbeat_at=datetime.now(UTC) - timedelta(hours=1)))
        db.commit()
        # acquire 触发陈旧回收
        assert acquire_tenant_slot(db, "t_sandbox") is True
        db.commit()
        assert running_count(db, "t_sandbox") == 1


def test_runner_acquires_and_releases_on_execution():
    """SandboxRunner.execute：acquire → running → completed → 释放（finally）。"""
    from app.services.sandbox.worker import run_sandbox_loop

    _, factory = _file_engine()
    with factory() as db:
        run = schedule_sandbox_run(db, tenant_id="t_sandbox", user_id="u_sb0")
        db.commit()
        # 直接走线程回退路径执行（内存库语义由 run_sandbox_loop 模拟；这里用真实 loop）
        run_sandbox_loop(db, tenant_id="t_sandbox", user_id="u_sb0", run_id=run.id)
        db.commit()
        runner = SandboxRunner(db, tenant_id="t_sandbox", user_id="u_sb0", run_id=run.id)
        result = runner.execute()
        db.commit()
        assert result.status in ("completed", "failed")
        # 执行结束后槽必须已释放
        assert running_count(db, "t_sandbox") == 0


def test_runner_releases_slot_when_concurrency_full():
    """并发满时 execute 直接 failed，且不占用槽。"""
    _, factory = _file_engine()
    with factory() as db:
        # 占满所有槽
        for _ in range(get_settings().sandbox_max_concurrent):
            assert acquire_tenant_slot(db, "t_sandbox") is True
        db.commit()
        run = schedule_sandbox_run(db, tenant_id="t_sandbox", user_id="u_sb1")
        db.commit()
        runner = SandboxRunner(db, tenant_id="t_sandbox", user_id="u_sb1", run_id=run.id)
        result = runner.execute()
        db.commit()
        assert result.status == "failed"
        assert result.error_message == "concurrency limit reached"
        assert running_count(db, "t_sandbox") == get_settings().sandbox_max_concurrent


def test_runner_releases_slot_on_timeout_path():
    """timeout/failed 路径也释放（通过异常注入验证 finally 语义）。"""
    from unittest.mock import patch

    tmp_name, factory = _file_engine()
    with factory() as db:
        run = schedule_sandbox_run(db, tenant_id="t_sandbox", user_id="u_sb2")
        db.commit()
        runner = SandboxRunner(db, tenant_id="t_sandbox", user_id="u_sb2", run_id=run.id)
        with patch("app.services.sandbox.runner._db_url_is_in_memory", return_value=True), \
             patch.object(SandboxRunner, "_execute_thread_fallback",
                          side_effect=RuntimeError("boom")):
            runner.execute()
        db.commit()
        assert running_count(db, "t_sandbox") == 0


def test_pending_and_running_are_separate():
    """pending 数量（sandbox_runs）与 running 并发（slots）分离。"""
    _, factory = _file_engine()
    with factory() as db:
        schedule_sandbox_run(db, tenant_id="t_sandbox", user_id="u_sb0")
        schedule_sandbox_run(db, tenant_id="t_sandbox", user_id="u_sb1")
        db.commit()
        pending = db.query(SandboxRun).filter(
            SandboxRun.tenant_id == "t_sandbox",
            SandboxRun.status == "pending",
        ).count()
        assert pending == 2
        assert running_count(db, "t_sandbox") == 0
        # 执行一个 → running 1，pending 剩 1
        run = db.query(SandboxRun).filter(SandboxRun.user_id == "u_sb0").one()
        runner = SandboxRunner(db, tenant_id="t_sandbox", user_id="u_sb0", run_id=run.id)
        runner.execute()
        db.commit()
        assert running_count(db, "t_sandbox") == 0  # 已释放
        assert db.get(SandboxRun, run.id).status in ("completed", "failed")
