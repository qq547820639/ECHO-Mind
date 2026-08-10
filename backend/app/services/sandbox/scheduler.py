"""沙箱运行调度器：幂等创建当日 SandboxRun。

不写审计，由路由层负责；不 commit，由调用方负责。

v0.6 并发语义：
- 路由层（routes._check_sandbox_concurrency）改为基于 `SandboxRun` 表查询
  （status=="running" 计数），覆盖整个执行期且跨实例生效；
- 本模块不再持有瞬时信号量（原信号量只在调度瞬间持有，无法覆盖执行期），
  仅负责幂等创建/返回 SandboxRun 记录。
"""
from __future__ import annotations

from datetime import date as date_cls, datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import SandboxRun


def acquire_tenant_slot(tenant_id: str) -> bool:
    """（已废弃，保留兼容）并发控制已迁移到 SandboxRun 表查询；恒返回 True。"""
    del tenant_id
    return True


def release_tenant_slot(tenant_id: str) -> None:
    """（已废弃，保留兼容）并发控制已迁移到 SandboxRun 表查询；无操作。"""
    del tenant_id


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
    - 并发/速率限制由路由层基于 SandboxRun 表查询执行（本模块不返回 None 哨兵）
    """
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
