"""SLA auto-escalation ladder for unacknowledged red escalations.

State machine per escalation (all timeouts measured from opened_at):

* age > ack_sla_seconds and still unacked      -> escalation_level=1, notify 第二值班人
* age > takeover_sla_seconds and still unacked -> escalation_level=2, notify 机构负责人
* age > org_lead_sla_seconds and still unacked -> chain_broken_at set (机构链路失效)

Invariant: notification is never takeover. This module only writes lifecycle
notification fields (escalation_level / notified_*_at / chain_broken_at) and
audit events; ack_at / takeover_at / status change exclusively through the
explicit ack/takeover endpoints. The scan is idempotent: each tier is guarded
by its own timestamp and fires at most once.
"""
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.models import Escalation
from app.services.audit import append_audit

CLOSED_STATUSES = ("closed", "reviewed")


def _age_seconds(opened_at: datetime, now: datetime) -> float:
    aware = opened_at if opened_at.tzinfo else opened_at.replace(tzinfo=timezone.utc)
    return (now - aware).total_seconds()


def scan_sla_breaches(
    db: Session,
    *,
    tenant_id: str | None = None,
    now: datetime | None = None,
    actor_id: str = "sla_scanner",
) -> dict:
    """Advance the escalation ladder for unacked red escalations.

    Returns a summary with the ids that changed tier in this run. Mutations are
    flushed by the caller (endpoint commits; tests may commit or roll back).
    """
    settings = get_settings()
    now = now or datetime.now(timezone.utc)
    query = select(Escalation).where(
        Escalation.level == "L3",
        Escalation.ack_at.is_(None),
        Escalation.status.notin_(CLOSED_STATUSES),
    )
    if tenant_id is not None:
        query = query.where(Escalation.tenant_id == tenant_id)
    rows = db.scalars(query).all()
    summary: dict[str, object] = {
        "scanned": len(rows),
        "notified_second_duty": [],
        "notified_org_lead": [],
        "chain_broken": [],
    }

    def record(action: str, row: Escalation, metadata: dict) -> None:
        append_audit(
            db,
            tenant_id=row.tenant_id,
            actor_type="system",
            actor_id=actor_id,
            action=action,
            object_type="escalation",
            object_id=row.id,
            metadata=metadata,
        )
        # 同一次扫描可能追加多条审计事件；逐条落库保证哈希链前后衔接。
        db.flush()

    for row in rows:
        age = _age_seconds(row.opened_at, now)
        if age <= settings.ack_sla_seconds:
            continue
        if row.notified_l1_at is None:
            row.escalation_level = 1
            row.notified_l1_at = now
            record("notify.second_duty", row, {"escalation_level": 1, "age_seconds": int(age)})
            summary["notified_second_duty"].append(row.id)
        if age > settings.takeover_sla_seconds and row.notified_l2_at is None:
            row.escalation_level = 2
            row.notified_l2_at = now
            record("notify.org_lead", row, {"escalation_level": 2, "age_seconds": int(age)})
            summary["notified_org_lead"].append(row.id)
        if age > settings.org_lead_sla_seconds and row.chain_broken_at is None:
            row.chain_broken_at = now
            record("escalation.chain_broken", row, {"age_seconds": int(age)})
            summary["chain_broken"].append(row.id)
    return summary
