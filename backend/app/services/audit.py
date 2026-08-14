"""Append-only audit chain with tenant-scoped serialization.

v0.6 变更：
- ``append_audit`` 的 read-head→hash→insert 在 PostgreSQL 下经
  ``pg_advisory_xact_lock(hashtext(:tenant_id))`` 串行化；SQLite 回退 per-tenant
  ``threading.Lock``，保证同租户并发 append 不产生共同 previous_hash 分叉。
- ``occurred_at`` 保证 per-tenant 严格递增（并发下若时间戳碰撞则 +1μs），
  使 ``verify_audit_chain`` 的排序与哈希链插入顺序一致。
- ``request_id`` 参数可选，缺省从 contextvar 读取（HTTP 中间件写入），
  保证 HTTP response 的 ``X-Request-ID`` 与 audit 表的 ``request_id`` 一致。
"""
import hashlib
import json
import threading
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from typing import Iterator
from uuid import uuid4

from sqlalchemy import select, text
from sqlalchemy.orm import Session

from app.models import AuditEvent
from app.request_context import get_current_request_id
from typing import Any

#: per-tenant 线程锁（SQLite 测试环境回退路径）；PostgreSQL 使用 advisory xact lock。
#: 使用 RLock 以便 tenant_append_serialized 上下文与 append_audit 内部可嵌套获取。
_tenant_locks: dict[str, threading.RLock] = {}
_tenant_locks_guard = threading.Lock()


def _tenant_lock(tenant_id: str) -> threading.RLock:
    with _tenant_locks_guard:
        lock = _tenant_locks.get(tenant_id)
        if lock is None:
            lock = threading.RLock()
            _tenant_locks[tenant_id] = lock
        return lock


@contextmanager
def tenant_append_serialized(tenant_id: str) -> Iterator[None]:
    """SQLite 回退路径：把"读头→插入→commit"作为原子段持有 per-tenant 锁。

    PostgreSQL 生产路径使用 ``pg_advisory_xact_lock``，锁在事务内天然覆盖
    到 commit；SQLite 无事务级 advisory lock，本上下文提供等价语义，供
    需要严格线性化 append+commit 的调用方使用（如并发测试）。
    """
    with _tenant_lock(tenant_id):
        yield


def _is_postgres(db: Session) -> bool:
    return db.get_bind().dialect.name == "postgresql"


def _canonical_payload(event: AuditEvent | None = None, **kwargs: Any) -> dict:
    if event is not None:
        return {
            "event_id": event.event_id,
            "tenant_id": event.tenant_id,
            "occurred_at": (event.occurred_at if event.occurred_at.tzinfo else event.occurred_at.replace(tzinfo=timezone.utc)).isoformat(),
            "actor_type": event.actor_type,
            "actor_id": event.actor_id,
            "action": event.action,
            "object_type": event.object_type,
            "object_id": event.object_id,
            "metadata": event.metadata_json or {},
            "previous_event_hash": event.previous_event_hash,
        }
    return kwargs


def _hash_payload(payload: dict) -> str:
    return hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def _normalize_aware(value: datetime) -> datetime:
    return value if value.tzinfo else value.replace(tzinfo=timezone.utc)


def _append_audit_unlocked(
    db: Session,
    *,
    tenant_id: str,
    actor_type: str,
    actor_id: str,
    action: str,
    object_type: str,
    object_id: str,
    metadata: dict | None = None,
    request_id: str | None = None,
) -> AuditEvent:
    """在调用方已持有 tenant 级串行化锁时执行 read-head→hash→insert。

    通过 flush 确保 INSERT 在锁内完成，避免并发读取共同头产生分叉。
    """
    previous = db.scalar(
        select(AuditEvent)
        .where(AuditEvent.tenant_id == tenant_id)
        .order_by(AuditEvent.occurred_at.desc(), AuditEvent.id.desc())
        .limit(1)
    )
    occurred_at = datetime.now(timezone.utc)
    if previous is not None:
        prev_at = _normalize_aware(previous.occurred_at)
        if occurred_at <= prev_at:
            # 保证 per-tenant 严格递增：时间戳碰撞时 +1μs，使链顺序与插入顺序一致
            occurred_at = prev_at + timedelta(microseconds=1)
    event_id = f"evt_{uuid4().hex}"
    payload = _canonical_payload(
        event_id=event_id,
        tenant_id=tenant_id,
        occurred_at=occurred_at.isoformat(),
        actor_type=actor_type,
        actor_id=actor_id,
        action=action,
        object_type=object_type,
        object_id=object_id,
        metadata=metadata or {},
        previous_event_hash=previous.event_hash if previous else None,
    )
    event = AuditEvent(
        event_id=event_id,
        tenant_id=tenant_id,
        occurred_at=occurred_at,
        actor_type=actor_type,
        actor_id=actor_id,
        action=action,
        object_type=object_type,
        object_id=object_id,
        request_id=request_id,
        metadata_json=metadata or {},
        previous_event_hash=previous.event_hash if previous else None,
        event_hash=_hash_payload(payload),
    )
    db.add(event)
    db.flush()
    return event


def append_audit(
    db: Session,
    *,
    tenant_id: str,
    actor_type: str,
    actor_id: str,
    action: str,
    object_type: str,
    object_id: str,
    metadata: dict | None = None,
    request_id: str | None = None,
) -> AuditEvent:
    """追加审计事件（append-only，只增不改）。

    request_id 缺省从 contextvar 读取（HTTP 中间件写入），保证响应头与审计列一致。
    同租户并发 append 经 tenant-scoped 串行化，避免 previous_hash 分叉。
    """
    if request_id is None:
        request_id = get_current_request_id()
    if _is_postgres(db):
        db.execute(
            text("SELECT pg_advisory_xact_lock(hashtext(:tenant_id))"),
            {"tenant_id": tenant_id},
        )
        return _append_audit_unlocked(
            db,
            tenant_id=tenant_id,
            actor_type=actor_type,
            actor_id=actor_id,
            action=action,
            object_type=object_type,
            object_id=object_id,
            metadata=metadata,
            request_id=request_id,
        )
    with _tenant_lock(tenant_id):
        return _append_audit_unlocked(
            db,
            tenant_id=tenant_id,
            actor_type=actor_type,
            actor_id=actor_id,
            action=action,
            object_type=object_type,
            object_id=object_id,
            metadata=metadata,
            request_id=request_id,
        )


def verify_audit_chain(db: Session, tenant_id: str) -> dict:
    rows = db.scalars(
        select(AuditEvent)
        .where(AuditEvent.tenant_id == tenant_id)
        .order_by(AuditEvent.occurred_at.asc(), AuditEvent.id.asc())
    ).all()
    previous_hash = None
    failures: list[dict] = []
    for index, row in enumerate(rows):
        expected_hash = _hash_payload(_canonical_payload(row))
        if row.previous_event_hash != previous_hash:
            failures.append({"index": index, "event_id": row.event_id, "reason": "previous_hash_mismatch"})
        if row.event_hash != expected_hash:
            failures.append({"index": index, "event_id": row.event_id, "reason": "event_hash_mismatch"})
        previous_hash = row.event_hash
    return {
        "tenant_id": tenant_id,
        "events": len(rows),
        "valid": not failures,
        "failures": failures,
        "head_hash": previous_hash,
    }
