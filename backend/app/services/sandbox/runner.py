"""沙箱运行编排器：SandboxRun 生命周期 + 造工具回路（生成→验证→归纳）。

状态机：pending → running → completed / failed。
回路步骤：audit_day → find_gaps → forge_tools → validate_tools → induct_skills。

v0.6 隔离与真超时：
- 生产（PostgreSQL / 文件型 SQLite）：``execute()`` 用 ``multiprocessing.Process``
  启动独立子进程（``app.services.sandbox.worker``），父进程 ``join(timeout)``
  超时后 ``terminate()`` → ``kill()`` 确保真终止，不再出现"超时后 worker 仍在跑"。
- in-memory SQLite（测试环境，子进程无法看到内存库）：回退为线程执行
  （线程无法强制终止，仅标记 failed；生产路径不受影响）。
"""
from __future__ import annotations

import concurrent.futures
import multiprocessing
from datetime import UTC, datetime

from sqlalchemy.orm import Session

from app.config import get_settings
from app.models import SandboxRun
from app.services.sandbox.worker import run_sandbox_loop, run_sandbox_worker

settings = get_settings()

#: terminate 后等待回收的宽限秒数；超过则 SIGKILL。
_WORKER_TERMINATE_GRACE_SECONDS = 5.0


def _db_url_is_in_memory(db: Session) -> bool:
    url = str(db.get_bind().engine.url)
    return url in {"sqlite:///:memory:", "sqlite+pysqlite:///:memory:"}


def _process_context() -> multiprocessing.context.BaseContext:
    """返回子进程上下文：POSIX 用 fork（避免 spawn 重导入 __main__ 的
    freeze_support 问题），Windows 回退 spawn。"""
    try:
        return multiprocessing.get_context("fork")
    except ValueError:
        return multiprocessing.get_context("spawn")


class SandboxRunner:
    """单个 SandboxRun 的执行器。"""

    def __init__(self, db: Session, *, tenant_id: str, user_id: str, run_id: str) -> None:
        self.db = db
        self.tenant_id = tenant_id
        self.user_id = user_id
        self.run_id = run_id
        run = db.get(SandboxRun, run_id)
        if run is None or run.tenant_id != tenant_id or run.user_id != user_id:
            raise ValueError("sandbox run does not belong to the given tenant/user")
        self.run = run

    def _record_failure(self, message: str) -> None:
        """将 run 状态转为 failed 并写审计（失败）。"""
        self.run.status = "failed"
        self.run.error_message = message
        self.run.completed_at = datetime.now(UTC)
        self.db.flush()
        from app.services.audit import append_audit

        append_audit(
            self.db,
            tenant_id=self.tenant_id,
            actor_type="sandbox",
            actor_id="runner",
            action="sandbox.run",
            object_type="sandbox_run",
            object_id=self.run.id,
            metadata={
                "run_date": str(self.run.run_date),
                "status": "failed",
                "error_message": self.run.error_message,
            },
        )
        self.db.flush()

    def _run_loop(self) -> None:
        """实际造工具回路（线程回退路径），在 worker 线程中执行。"""
        run_sandbox_loop(
            self.db,
            tenant_id=self.tenant_id,
            user_id=self.user_id,
            run_id=self.run_id,
        )

    def _execute_thread_fallback(self, timeout_seconds: float) -> SandboxRun:
        """in-memory SQLite 测试环境回退：线程执行（超时标记 failed，线程不可强杀）。"""
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
                future = executor.submit(self._run_loop)
                future.result(timeout=timeout_seconds)
        except concurrent.futures.TimeoutError:
            # 主线程等待超时：标记 failed。`with` 块会等待 worker 结束，
            # 避免并发 DB 写入；最终由主线程覆盖状态。
            self._record_failure(f"timeout after {timeout_seconds}s")
        return self.run

    def _execute_subprocess(self, timeout_seconds: float) -> SandboxRun:
        """子进程隔离执行：join(timeout) 超时后 terminate/kill，保证真终止。"""
        database_url = str(self.db.get_bind().engine.url)
        context = _process_context()
        process = context.Process(
            target=run_sandbox_worker,
            args=(self.tenant_id, self.user_id, self.run_id, database_url),
            daemon=True,
            name=f"sandbox-worker-{self.run_id}",
        )
        process.start()
        process.join(timeout=timeout_seconds)
        if process.is_alive():
            # 真超时终止：先 SIGTERM，宽限后 SIGKILL，并 join 回收避免僵尸进程
            process.terminate()
            process.join(timeout=_WORKER_TERMINATE_GRACE_SECONDS)
            if process.is_alive():
                process.kill()
                process.join(timeout=_WORKER_TERMINATE_GRACE_SECONDS)
            self._record_failure(f"timeout after {timeout_seconds}s")
            self.db.commit()
        else:
            # worker 正常退出（completed 或 failed）；从 DB 重载最新状态
            self.db.expire_all()
            refreshed = self.db.get(SandboxRun, self.run_id)
            if refreshed is None:
                self._record_failure("sandbox run vanished during execution")
                self.db.commit()
            else:
                self.run = refreshed
        return self.run

    def execute(self) -> SandboxRun:
        """执行造工具回路：pending → running → completed/failed。

        生产路径：子进程隔离 + 真超时终止；in-memory SQLite 回退线程执行。
        超时（超过 sandbox_timeout_seconds）转 failed；其它异常也转 failed。
        不向上抛出（调用方可检查 run.status）。

        并发（v0.6.1）：执行期通过数据库原子租户槽（[slots.acquire_tenant_slot]）
        获取 permit；获取失败 → 直接标记 failed（不进入 running，不占配额）。
        completed/failed/timeout/异常路径统一在 finally 释放槽（worker crash
        由心跳过期回收兜底）。
        """
        from app.services.sandbox.slots import acquire_tenant_slot, release_tenant_slot

        if not acquire_tenant_slot(self.db, self.tenant_id):
            # 并发上限已满：不启动执行，直接失败（不占运行配额）
            self._record_failure("concurrency limit reached")
            self.db.commit()
            return self.run

        now = datetime.now(UTC)
        self.run.status = "running"
        self.run.started_at = now
        self.run.error_message = None
        self.db.flush()
        self.db.commit()
        try:
            timeout_seconds = settings.sandbox_timeout_seconds
            if _db_url_is_in_memory(self.db):
                return self._execute_thread_fallback(timeout_seconds)
            return self._execute_subprocess(timeout_seconds)
        except Exception as exc:  # noqa: BLE001 执行期任何异常统一转 failed（文档语义）
            self._record_failure(f"{type(exc).__name__}: {exc}")
            return self.run
        finally:
            # 统一释放执行槽（completed/failed/timeout/异常均到达此处）
            release_tenant_slot(self.db, self.tenant_id)
            self.db.commit()
