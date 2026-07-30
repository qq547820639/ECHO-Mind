"""Append-only protection for risk events and the audit log.

Risk events (escalations, risk signals) and audit events must never be updated or
deleted once written. Database triggers (Alembic revision 20260729_0002) enforce
this on PostgreSQL; this module provides the application-layer fallback so the
same guarantee holds on SQLite and against accidental ORM misuse.

Escalations have a legitimate lifecycle (ack/takeover/close/review), so only their
immutable fact fields are protected; lifecycle fields remain updatable.
"""
from sqlalchemy import event, inspect
from sqlalchemy.orm import Session

from app.models import AuditEvent, Escalation, RiskSignal

APPEND_ONLY_MODELS = (AuditEvent, RiskSignal)
PROTECTED_MODELS = (AuditEvent, RiskSignal, Escalation)
ESCALATION_MUTABLE_FIELDS = frozenset({
    "status",
    "ack_at",
    "takeover_at",
    "closed_at",
    "reviewed_at",
    "assigned_to",
    "disposition",
    "review_notes",
    # SLA 自动升级状态机生命周期字段（v0.4）。
    "escalation_level",
    "notified_l1_at",
    "notified_l2_at",
    "chain_broken_at",
    "delivery_confirmed_at",
    # 接管处置记录生命周期字段（v0.5），close 流程写入。
    "contact_method",
    "contact_succeeded",
    "safety_status",
    "emergency_contact_called",
    "referred_12356",
    "called_emergency_services",
    "follow_up_plan",
    "operator_signature",
})


class ImmutableRecordError(RuntimeError):
    """Raised when code attempts to update or delete an append-only record."""


def _changed_columns(obj: object) -> set[str]:
    state = inspect(obj)
    return {
        attr.key
        for attr in state.mapper.column_attrs
        if state.attrs[attr.key].history.has_changes()
    }


def _guard_before_flush(session: Session, flush_context, instances) -> None:
    for obj in session.deleted:
        if isinstance(obj, PROTECTED_MODELS):
            raise ImmutableRecordError(
                f"{type(obj).__name__} records are append-only and cannot be deleted"
            )
    for obj in session.dirty:
        if not session.is_modified(obj, include_collections=False):
            continue
        if isinstance(obj, APPEND_ONLY_MODELS):
            raise ImmutableRecordError(
                f"{type(obj).__name__} records are append-only and cannot be modified"
            )
        if isinstance(obj, Escalation):
            forbidden = _changed_columns(obj) - ESCALATION_MUTABLE_FIELDS
            if forbidden:
                raise ImmutableRecordError(
                    f"escalation immutable fields cannot be modified: {sorted(forbidden)}"
                )


def register_immutability_guard() -> None:
    if not event.contains(Session, "before_flush", _guard_before_flush):
        event.listen(Session, "before_flush", _guard_before_flush)


register_immutability_guard()
