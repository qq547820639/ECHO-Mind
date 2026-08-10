"""沙箱租户执行槽（v0.6.1）：执行期原子并发配额。

背景（hardening 需求十）：旧实现只在 schedule 阶段 count ``status=="running"`` 的
SandboxRun，存在 check-then-act race（两个 worker 同时通过检查后一起执行）。

本模块提供数据库原子 permit：
- ``acquire_tenant_slot``：行级原子 UPDATE ``running_count+1 WHERE running_count < max``，
  多 worker 并发启动不可能突破 tenant max concurrency；
- 行不存在时 INSERT（唯一约束冲突则重试一次）；
- ``release_tenant_slot``：原子递减（completed / failed / timeout / 异常路径统一 finally 释放）；
- 陈旧槽回收：worker crash 后 heartbeat 不再刷新，acquire 时把超过
  [LEASE_STALE_SECONDS] 的槽归零（进程整体死亡场景兜底）。

SQLite 与 PostgreSQL 语义一致（UPDATE ... RETURNING 均行级原子）。
"""
from __future__ import annotations

from datetime import UTC, datetime, timedelta

from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.config import get_settings
from app.models import TenantSandboxSlot

#: 槽心跳过期阈值：超过该时长未刷新视为 worker 已死，acquire 时回收。
LEASE_STALE_SECONDS = 15 * 60


def _aware(value: datetime) -> datetime:
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def _now() -> datetime:
    return datetime.now(UTC)


def acquire_tenant_slot(db: Session, tenant_id: str) -> bool:
    """原子获取租户执行槽；返回是否获得（不 commit，由调用方统一 commit/rollback）。"""
    settings = get_settings()
    now = _now()
    # 1. 回收陈旧槽（worker crash 后配额释放）
    stale_cutoff = now - timedelta(seconds=LEASE_STALE_SECONDS)
    db.execute(
        update(TenantSandboxSlot)
        .where(
            TenantSandboxSlot.tenant_id == tenant_id,
            TenantSandboxSlot.heartbeat_at < stale_cutoff,
        )
        .values(running_count=0, heartbeat_at=now)
    )
    # 2. 行级原子递增（带上限）；RETURNING 在 SQLite 3.35+ / PostgreSQL 均可
    result = db.execute(
        update(TenantSandboxSlot)
        .where(
            TenantSandboxSlot.tenant_id == tenant_id,
            TenantSandboxSlot.running_count < settings.sandbox_max_concurrent,
        )
        .values(
            running_count=TenantSandboxSlot.running_count + 1,
            heartbeat_at=now,
        )
        .returning(TenantSandboxSlot.running_count)
    )
    if result.scalar_one_or_none() is not None:
        return True
    # 3. 行不存在：INSERT 首槽
    row = db.scalar(select(TenantSandboxSlot).where(TenantSandboxSlot.tenant_id == tenant_id))
    if row is not None:
        return False  # 行存在但已达上限
    try:
        db.add(TenantSandboxSlot(tenant_id=tenant_id, running_count=1, heartbeat_at=now))
        db.flush()
        return True
    except IntegrityError:
        db.rollback()
        # 并发 INSERT 竞争：回退为原子 UPDATE 再试一次
        retry = db.execute(
            update(TenantSandboxSlot)
            .where(
                TenantSandboxSlot.tenant_id == tenant_id,
                TenantSandboxSlot.running_count < settings.sandbox_max_concurrent,
            )
            .values(running_count=TenantSandboxSlot.running_count + 1, heartbeat_at=now)
            .returning(TenantSandboxSlot.running_count)
        )
        return retry.scalar_one_or_none() is not None


def release_tenant_slot(db: Session, tenant_id: str) -> None:
    """原子释放租户执行槽（completed/failed/timeout/异常路径统一调用）。"""
    db.execute(
        update(TenantSandboxSlot)
        .where(
            TenantSandboxSlot.tenant_id == tenant_id,
            TenantSandboxSlot.running_count > 0,
        )
        .values(running_count=TenantSandboxSlot.running_count - 1, heartbeat_at=_now())
    )
    db.flush()


def running_count(db: Session, tenant_id: str) -> int:
    """当前租户 running 并发数（观测用）。"""
    row = db.scalar(select(TenantSandboxSlot).where(TenantSandboxSlot.tenant_id == tenant_id))
    return row.running_count if row is not None else 0
