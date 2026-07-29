import hashlib
import json
from datetime import datetime, timezone
from uuid import uuid4
from sqlalchemy import select
from sqlalchemy.orm import Session
from app.models import AuditEvent


def _canonical_payload(event: AuditEvent | None = None, **kwargs) -> dict:
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
    previous = db.scalar(
        select(AuditEvent)
        .where(AuditEvent.tenant_id == tenant_id)
        .order_by(AuditEvent.occurred_at.desc(), AuditEvent.id.desc())
        .limit(1)
    )
    occurred_at = datetime.now(timezone.utc)
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
    return event


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
