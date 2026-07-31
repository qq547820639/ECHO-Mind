"""沙箱运行调度器：幂等创建当日 SandboxRun。

不写审计，由路由层负责；不 commit，由调用方负责。

并发控制：每租户一个 `threading.BoundedSemaphore(sandbox_max_concurrent)`，
`schedule_sandbox_run` 入口非阻塞 acquire；超限返回 None（路由层据此 429）。
"""
from __future__ import annotations

import threading
from datetime import date as date_cls, datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.models import SandboxRun

settings = get_settings()

# 模块级：每租户并发信号量 + 字典访问锁
_tenant_semaphores: dict[str, threading.BoundedSemaphore] = {}
_semaphores_lock = threading.Lock()


def _get_tenant_semaphore(tenant_id: str) -> threading.BoundedSemaphore:
    """获取（或惰性创建）某租户的并发信号量。"""
    with _semaphores_lock:
        sem = _tenant_semaphores.get(tenant_id)
        if sem is None:
            sem = threading.BoundedSemaphore(settings.sandbox_max_concurrent)
            _tenant_semaphores[tenant_id] = sem
        return sem


def acquire_tenant_slot(tenant_id: str) -> bool:
    """非阻塞获取租户并发槽位；成功返回 True，超限返回 False。"""
    return _get_tenant_semaphore(tenant_id).acquire(blocking=False)


def release_tenant_slot(tenant_id: str) -> None:
    """释放租户并发槽位。仅在 acquire 成功后调用。"""
    with _semaphores_lock:
        sem = _tenant_semaphores.get(tenant_id)
    if sem is not None:
        try:
            sem.release()
        except ValueError:
            # 防御性：信号量已释放到上限，忽略重复 release
            pass


def schedule_sandbox_run(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    run_date: date_cls | None = None,
) -> SandboxRun | None:
    """幂等创建/返回某用户某天的 SandboxRun。

    - 同 tenant+user+date 已存在则直接返回已有记录（幂等）
    - 否则创建 status=pending 的新记录
    - 同租户并发超过 `sandbox_max_concurrent` 时返回 None（路由层 429）
    """
    if not acquire_tenant_slot(tenant_id):
        return None
    try:
        target_date = run_date or datetime.now(timezone.utc).date()
        existing = db.scalar(
            select(SandboxRun).where(
                SandboxRun.tenant_id == tenant_id,
                SandboxRun.user_id == user_id,
                SandboxRun.run_date == target_date,
            )
        )
        if existing:
            return existing
        run = SandboxRun(
            tenant_id=tenant_id,
            user_id=user_id,
            run_date=target_date,
            status="pending",
        )
        db.add(run)
        db.flush()
        return run
    finally:
        release_tenant_slot(tenant_id)
