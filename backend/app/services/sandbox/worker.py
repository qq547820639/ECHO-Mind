"""沙箱 worker：子进程隔离执行入口（v0.6）。

父进程（``SandboxRunner.execute``）用 ``multiprocessing`` 启动本模块的
:func:`run_sandbox_worker` 在独立子进程中执行造工具回路，从而保证：

- 真超时终止：父进程 ``join(timeout)`` 超时后 ``terminate()/kill()`` 子进程，
  不会像线程那样"超时后 worker 仍在跑"；
- 独立 DB Session：worker 使用传入的 database_url 自建 engine/session，
  不共享请求线程的 Session；
- CPU/内存配额：子进程内通过 :func:`resource.setrlimit` 设置墙钟与地址空间上限；
- network deny-by-default：worker 不发起任何网络调用（无网络代码），
  不提供网络访问能力；
- filesystem：worker 只读写数据库，不写任何本地文件（除测试注入的 pidfile）。

入口函数 :func:`run_sandbox_worker` 为模块级 picklable 函数，参数均为
基本类型（str），可被 spawn/fork 子进程加载。
"""
from __future__ import annotations

import os
from datetime import UTC, datetime
from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.models import SandboxRun
from app.services.audit import append_audit
from app.services.sandbox.audit_day import audit_day
from app.services.sandbox.gap_finder import find_gaps
from app.services.sandbox.skill_induct import induct_skills
from app.services.sandbox.tool_forge import forge_tools
from app.services.sandbox.tool_validator import validate_tools

#: 沙箱 worker 默认 CPU 墙钟上限（秒）；可由环境变量覆盖（测试注入用）。
WORKER_CPU_LIMIT_SECONDS = int(os.environ.get("SANDBOX_WORKER_CPU_LIMIT_SECONDS", "600"))
#: 沙箱 worker 默认地址空间上限（字节，RLIMIT_AS）；默认 512MB。
WORKER_MEMORY_LIMIT_BYTES = int(os.environ.get("SANDBOX_WORKER_MEMORY_LIMIT_BYTES", str(512 * 1024 * 1024)))


def _apply_resource_limits() -> None:
    """在子进程内设置 CPU（RLIMIT_CPU）与内存（RLIMIT_AS）配额。

    仅 POSIX 可用；Windows 下静默跳过（pilot 目标为 Linux/macOS）。
    """
    try:
        import resource

        resource.setrlimit(resource.RLIMIT_CPU, (WORKER_CPU_LIMIT_SECONDS, WORKER_CPU_LIMIT_SECONDS))
        resource.setrlimit(resource.RLIMIT_AS, (WORKER_MEMORY_LIMIT_BYTES, WORKER_MEMORY_LIMIT_BYTES))
    except (ImportError, ValueError, OSError):
        # 非 POSIX 或无法设置时忽略：配额尽力而为，不阻塞执行
        pass


def _maybe_sleep_for_test() -> None:
    """测试注入点：读取 SANDBOX_WORKER_SLEEP_SECONDS 环境变量后 sleep。

    仅测试使用（test_sandbox_isolation 模拟超时）；生产环境不设置该变量。
    """
    raw = os.environ.get("SANDBOX_WORKER_SLEEP_SECONDS", "0")
    try:
        seconds = float(raw)
    except ValueError:
        seconds = 0.0
    if seconds > 0:
        import time

        time.sleep(seconds)


def _maybe_write_pidfile() -> None:
    """测试注入点：写入 worker 自身 PID（test_sandbox_isolation 断言进程已终止）。"""
    pidfile = os.environ.get("SANDBOX_WORKER_PIDFILE")
    if pidfile:
        Path(pidfile).write_text(str(os.getpid()), encoding="utf-8")


def run_sandbox_loop(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    run_id: str,
) -> SandboxRun:
    """在给定 Session 中执行造工具回路并更新 run 状态（不 commit，由调用方负责）。

    回路：audit_day → find_gaps → forge_tools → validate_tools → induct_skills。
    内部捕获所有异常并转为 failed；不向上抛出。
    """
    run = db.get(SandboxRun, run_id)
    if run is None or run.tenant_id != tenant_id or run.user_id != user_id:
        raise ValueError("sandbox run does not belong to the given tenant/user")

    def _record_failure(message: str) -> None:
        run.status = "failed"
        run.error_message = message
        run.completed_at = datetime.now(UTC)
        db.flush()
        append_audit(
            db,
            tenant_id=tenant_id,
            actor_type="sandbox",
            actor_id="runner",
            action="sandbox.run",
            object_type="sandbox_run",
            object_id=run.id,
            metadata={
                "run_date": str(run.run_date),
                "status": "failed",
                "error_message": run.error_message,
            },
        )
        db.flush()

    try:
        # 1. 当日审计汇总
        audit_summary = audit_day(
            db,
            tenant_id=tenant_id,
            user_id=user_id,
            run_date=run.run_date,
        )

        # 2. 缺口识别
        gaps = find_gaps(
            db,
            tenant_id=tenant_id,
            user_id=user_id,
            run_date=run.run_date,
            audit_summary=audit_summary,
        )

        # 3. 工具锻造
        candidates = forge_tools(
            db,
            tenant_id=tenant_id,
            user_id=user_id,
            gaps=gaps,
        )

        # 4. 工具验证
        validated = validate_tools(
            db,
            tenant_id=tenant_id,
            user_id=user_id,
            candidate_tools=candidates,
        )
        valid_tools = [tool for tool, is_valid, _ in validated if is_valid]

        # 5. 技能归纳
        skills = induct_skills(
            db,
            tenant_id=tenant_id,
            user_id=user_id,
            validated_tools=valid_tools,
        )

        # 更新运行记录
        run.gaps_found = [g["description"] for g in gaps]
        run.tools_generated = len(candidates)
        run.tools_validated = len(validated)
        run.skills_inducted = len(skills)
        run.status = "completed"
        run.completed_at = datetime.now(UTC)
        db.flush()

        # 写审计（成功）
        append_audit(
            db,
            tenant_id=tenant_id,
            actor_type="sandbox",
            actor_id="runner",
            action="sandbox.run",
            object_type="sandbox_run",
            object_id=run.id,
            metadata={
                "run_date": str(run.run_date),
                "status": run.status,
                "gaps_found": list(run.gaps_found or []),
                "tools_generated": run.tools_generated,
                "tools_validated": run.tools_validated,
                "skills_inducted": run.skills_inducted,
            },
        )
        db.flush()
    except Exception as exc:  # noqa: BLE001 沙箱内部捕获所有异常，记录后不抛出
        _record_failure(str(exc))
    return run


def run_sandbox_worker(tenant_id: str, user_id: str, run_id: str, database_url: str) -> None:
    """子进程入口（picklable）。

    - 应用资源配额（rlimit）
    - 自建独立 engine/session（不共享父进程 Session）
    - 执行 run_sandbox_loop 并 commit
    """
    _apply_resource_limits()
    _maybe_write_pidfile()
    _maybe_sleep_for_test()

    engine = create_engine(database_url)
    session_factory = sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)
    with session_factory() as db:
        run_sandbox_loop(db, tenant_id=tenant_id, user_id=user_id, run_id=run_id)
        db.commit()
