"""Consent 领域路由（v0.6.1 拆分）：版本化同意记录 + 最新同意查询。

同意记录为 append-only 证据链（revoked_at 表达撤回），不删除、不修改历史。
"""
from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter
from sqlalchemy import select

from app.models import Consent
from app.schemas import ConsentCreate
from app.services.audit import append_audit

from app.api.deps import DB, PRINCIPAL, ensure_user, require_write_role

router = APIRouter(prefix="/v1")


@router.post("/onboarding/consents")
def create_consent(payload: ConsentCreate, db: DB, principal: PRINCIPAL) -> dict:
    require_write_role(db, principal, object_type="consent")
    ensure_user(db, principal, payload.user_id)
    now = datetime.now(UTC)
    consent = Consent(
        tenant_id=principal.tenant_id,
        user_id=payload.user_id,
        consent_type=payload.consent_type,
        version=payload.version,
        granted=payload.granted,
        evidence_hash=payload.evidence_hash,
        revoked_at=None if payload.granted else now,
    )
    db.add(consent)
    db.flush()
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="consent.record",
        object_type="consent",
        object_id=consent.id,
        metadata={"type": payload.consent_type, "version": payload.version, "granted": payload.granted},
    )
    db.commit()
    return {"id": consent.id, "granted": consent.granted, "revoked_at": consent.revoked_at}


@router.get("/onboarding/consents/latest")
def get_latest_consents(user_id: str, db: DB, principal: PRINCIPAL) -> dict:
    ensure_user(db, principal, user_id)
    rows = db.scalars(select(Consent).where(
        Consent.tenant_id == principal.tenant_id,
        Consent.user_id == user_id,
    ).order_by(Consent.granted_at.desc())).all()
    latest: dict[str, Consent] = {}
    for row in rows:
        latest.setdefault(row.consent_type, row)
    return {
        key: {
            "version": row.version,
            "granted": row.granted,
            "granted_at": row.granted_at,
            "revoked_at": row.revoked_at,
        }
        for key, row in latest.items()
    }
