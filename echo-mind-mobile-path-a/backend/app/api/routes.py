from datetime import datetime, timezone
from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.auth import Principal, get_principal, require_roles
from app.config import get_settings
from app.database import get_db
from app.models import (
    AuditEvent,
    Checkin,
    Consent,
    DataSubjectRequest,
    EmergencyContact,
    Escalation,
    JournalEntry,
    OnboardingScreening,
    PracticeCompletion,
    QuestionnaireResult,
    RiskSignal,
    Tenant,
    User,
)
from app.schemas import (
    CheckinCreate,
    ConsentCreate,
    DataSubjectRequestComplete,
    DataSubjectRequestCreate,
    EmergencyContactCreate,
    EscalationClose,
    EscalationCreate,
    EscalationReview,
    FreeTextSafetyCheck,
    JournalCreate,
    JournalRevise,
    L0ScreeningCreate,
    PracticeCompletionCreate,
    QuestionnaireCreate,
    TenantCreate,
    UserCreate,
)
from app.services.audit import append_audit, verify_audit_chain
from app.services.crypto import decrypt_text, encrypt_text
from app.services.safety import RULE_PACK_VERSION, evaluate_text
from app.services.scoring import score_gad7, score_phq9
from app.services.trends import build_trend

router = APIRouter(prefix="/v1")
DB = Annotated[Session, Depends(get_db)]
PRINCIPAL = Annotated[Principal, Depends(get_principal)]
settings = get_settings()


def ensure_user(db: Session, principal: Principal, user_id: str) -> User:
    user = db.get(User, user_id)
    if not user or user.tenant_id != principal.tenant_id:
        raise HTTPException(status_code=404, detail="user not found")
    if principal.role == "user" and principal.subject != user_id:
        raise HTTPException(status_code=403, detail="cannot access another user")
    if user.status not in {"active", "restricted"}:
        raise HTTPException(status_code=409, detail="user is not active")
    return user


def latest_consent(db: Session, tenant_id: str, user_id: str, consent_type: str) -> Consent | None:
    return db.scalar(
        select(Consent).where(
            Consent.tenant_id == tenant_id,
            Consent.user_id == user_id,
            Consent.consent_type == consent_type,
        ).order_by(Consent.granted_at.desc()).limit(1)
    )


def require_psychological_consent(db: Session, principal: Principal, user_id: str) -> None:
    row = latest_consent(db, principal.tenant_id, user_id, "psychological_data")
    if not row or not row.granted or row.revoked_at is not None:
        raise HTTPException(status_code=412, detail="active psychological-data consent required")


def get_escalation(db: Session, principal: Principal, escalation_id: str) -> Escalation:
    row = db.get(Escalation, escalation_id)
    if not row or row.tenant_id != principal.tenant_id:
        raise HTTPException(status_code=404, detail="not found")
    return row


def open_escalation(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    trigger: str,
    evidence_summary: str,
    actor_id: str,
    source_event_id: str | None = None,
) -> Escalation:
    event_id = source_event_id or f"esc_evt_{uuid4().hex}"
    existing = db.scalar(select(Escalation).where(
        Escalation.tenant_id == tenant_id,
        Escalation.event_id == event_id,
    ))
    if existing:
        return existing
    row = Escalation(
        event_id=event_id,
        tenant_id=tenant_id,
        user_id=user_id,
        level="L3",
        trigger=trigger,
        evidence_summary=evidence_summary,
    )
    db.add(row)
    db.flush()
    append_audit(
        db,
        tenant_id=tenant_id,
        actor_type="safety_service",
        actor_id=actor_id,
        action="escalation.open",
        object_type="escalation",
        object_id=row.id,
        metadata={"trigger": trigger},
    )
    return row


@router.post("/tenants")
def create_tenant(
    payload: TenantCreate,
    db: DB,
    x_bootstrap_key: Annotated[str | None, Header()] = None,
):
    if x_bootstrap_key != settings.bootstrap_key:
        raise HTTPException(status_code=403, detail="invalid bootstrap key")
    tenant = Tenant(name=payload.name)
    db.add(tenant)
    db.commit()
    db.refresh(tenant)
    return {"id": tenant.id, "name": tenant.name}


@router.post("/users")
def create_user(payload: UserCreate, db: DB, principal: PRINCIPAL):
    if principal.role not in {"admin", "professional"}:
        raise HTTPException(status_code=403, detail="insufficient role")
    user = User(
        tenant_id=principal.tenant_id,
        external_ref=payload.external_ref,
        age_band=payload.age_band,
        timezone=payload.timezone,
    )
    db.add(user)
    db.flush()
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="user.create",
        object_type="user",
        object_id=user.id,
    )
    try:
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(status_code=409, detail="external_ref already exists") from exc
    db.refresh(user)
    return {"id": user.id, "status": user.status}


@router.post("/onboarding/consents")
def create_consent(payload: ConsentCreate, db: DB, principal: PRINCIPAL):
    ensure_user(db, principal, payload.user_id)
    now = datetime.now(timezone.utc)
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
def get_latest_consents(user_id: str, db: DB, principal: PRINCIPAL):
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


@router.post("/onboarding/l0")
def create_l0(payload: L0ScreeningCreate, db: DB, principal: PRINCIPAL):
    user = ensure_user(db, principal, payload.user_id)
    existing = db.scalar(select(OnboardingScreening).where(
        OnboardingScreening.tenant_id == principal.tenant_id,
        OnboardingScreening.event_id == payload.event_id,
    ))
    if existing:
        return {"id": existing.id, "decision": existing.decision, "idempotent_replay": True}
    urgent = payload.current_danger
    excluded = payload.psychosis_or_mania or payload.substance_impairment
    decision = "urgent_human" if urgent else "human_assessment" if excluded else "eligible"
    row = OnboardingScreening(
        event_id=payload.event_id,
        tenant_id=principal.tenant_id,
        user_id=payload.user_id,
        current_danger=payload.current_danger,
        prior_attempt_or_admission=payload.prior_attempt_or_admission,
        psychosis_or_mania=payload.psychosis_or_mania,
        substance_impairment=payload.substance_impairment,
        has_professional_support=payload.has_professional_support,
        decision=decision,
    )
    db.add(row)
    if decision != "eligible":
        user.status = "restricted"
    db.flush()
    escalation_id = None
    if urgent:
        escalation_id = open_escalation(
            db,
            tenant_id=principal.tenant_id,
            user_id=payload.user_id,
            trigger="l0_current_danger",
            evidence_summary="L0 准入发现当前危险，常规 AI 服务已停止。",
            actor_id="l0_rules",
        ).id
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="onboarding.l0",
        object_type="onboarding_screening",
        object_id=row.id,
        metadata={"decision": decision},
    )
    db.commit()
    return {"id": row.id, "decision": decision, "escalation_id": escalation_id}


@router.post("/onboarding/emergency-contact")
def create_emergency_contact(payload: EmergencyContactCreate, db: DB, principal: PRINCIPAL):
    ensure_user(db, principal, payload.user_id)
    consent = latest_consent(db, principal.tenant_id, payload.user_id, "emergency_contact")
    if not consent or not consent.granted or consent.revoked_at:
        raise HTTPException(status_code=412, detail="emergency-contact consent required")
    row = EmergencyContact(
        tenant_id=principal.tenant_id,
        user_id=payload.user_id,
        name_ciphertext=encrypt_text(payload.name, aad=f"{principal.tenant_id}:{payload.user_id}:ec-name"),
        phone_ciphertext=encrypt_text(payload.phone, aad=f"{principal.tenant_id}:{payload.user_id}:ec-phone"),
        relationship=payload.relationship,
    )
    db.add(row)
    db.flush()
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="emergency_contact.create",
        object_type="emergency_contact",
        object_id=row.id,
    )
    db.commit()
    return {"id": row.id, "relationship": row.relationship}


@router.post("/checkins")
def create_checkin(payload: CheckinCreate, request: Request, db: DB, principal: PRINCIPAL):
    ensure_user(db, principal, payload.user_id)
    require_psychological_consent(db, principal, payload.user_id)
    existing = db.scalar(select(Checkin).where(
        Checkin.tenant_id == principal.tenant_id,
        Checkin.event_id == payload.event_id,
    ))
    if existing:
        return {"id": existing.id, "idempotent_replay": True}
    safety = evaluate_text(payload.note or "") if payload.note else None
    checkin = Checkin(
        event_id=payload.event_id,
        tenant_id=principal.tenant_id,
        user_id=payload.user_id,
        mood=payload.mood,
        stress=payload.stress,
        energy=payload.energy,
        sleep_recovery=payload.sleep_recovery,
        event_flag=payload.event_flag,
        help_requested=payload.help_requested,
        note_ciphertext=encrypt_text(payload.note, aad=f"{principal.tenant_id}:{payload.user_id}:checkin"),
        client_time=payload.client_time,
        device_timezone=payload.device_timezone,
    )
    db.add(checkin)
    db.flush()
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="checkin.create",
        object_type="checkin",
        object_id=checkin.id,
        request_id=request.headers.get("x-request-id"),
    )
    escalation_id = None
    if payload.help_requested or (safety and safety.severity == "red"):
        escalation_id = open_escalation(
            db,
            tenant_id=principal.tenant_id,
            user_id=payload.user_id,
            trigger="help_requested" if payload.help_requested else "text_red_signal",
            evidence_summary="用户主动请求人工帮助" if payload.help_requested else "文本命中确定性红色规则",
            actor_id="mobile_rules",
        ).id
    db.commit()
    return {"id": checkin.id, "safety": safety.__dict__ if safety else None, "escalation_id": escalation_id}


@router.post("/journals")
def create_journal(payload: JournalCreate, db: DB, principal: PRINCIPAL):
    ensure_user(db, principal, payload.user_id)
    require_psychological_consent(db, principal, payload.user_id)
    existing = db.scalar(select(JournalEntry).where(
        JournalEntry.tenant_id == principal.tenant_id,
        JournalEntry.event_id == payload.event_id,
    ))
    if existing:
        return {"id": existing.id, "logical_id": existing.logical_id, "revision": existing.revision, "idempotent_replay": True}
    safety = evaluate_text(payload.body)
    row = JournalEntry(
        event_id=payload.event_id,
        tenant_id=principal.tenant_id,
        user_id=payload.user_id,
        logical_id=payload.logical_id,
        revision=1,
        body_ciphertext=encrypt_text(payload.body, aad=f"{principal.tenant_id}:{payload.user_id}:journal"),
        event_tags=payload.event_tags,
        client_time=payload.client_time,
    )
    db.add(row)
    db.flush()
    escalation_id = None
    if safety.severity == "red":
        escalation_id = open_escalation(
            db,
            tenant_id=principal.tenant_id,
            user_id=payload.user_id,
            trigger="journal_red_signal",
            evidence_summary="日记文本命中确定性红色规则；正文不写入审计日志。",
            actor_id="journal_rules",
        ).id
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="journal.create",
        object_type="journal",
        object_id=row.id,
        metadata={"logical_id": row.logical_id, "revision": row.revision, "safety": safety.severity},
    )
    db.commit()
    return {"id": row.id, "logical_id": row.logical_id, "revision": 1, "safety": safety.__dict__, "escalation_id": escalation_id}


@router.post("/journals/{logical_id}/revisions")
def revise_journal(logical_id: str, payload: JournalRevise, db: DB, principal: PRINCIPAL):
    latest = db.scalar(select(JournalEntry).where(
        JournalEntry.tenant_id == principal.tenant_id,
        JournalEntry.logical_id == logical_id,
    ).order_by(JournalEntry.revision.desc()).limit(1))
    if not latest:
        raise HTTPException(status_code=404, detail="journal not found")
    ensure_user(db, principal, latest.user_id)
    existing = db.scalar(select(JournalEntry).where(
        JournalEntry.tenant_id == principal.tenant_id,
        JournalEntry.event_id == payload.event_id,
    ))
    if existing:
        return {"id": existing.id, "revision": existing.revision, "idempotent_replay": True}
    row = JournalEntry(
        event_id=payload.event_id,
        tenant_id=principal.tenant_id,
        user_id=latest.user_id,
        logical_id=logical_id,
        revision=latest.revision + 1,
        body_ciphertext=encrypt_text(payload.body, aad=f"{principal.tenant_id}:{latest.user_id}:journal"),
        event_tags=payload.event_tags,
        client_time=payload.client_time,
        supersedes_id=latest.id,
    )
    db.add(row)
    db.flush()
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="journal.revise", object_type="journal", object_id=row.id,
                 metadata={"logical_id": logical_id, "revision": row.revision})
    db.commit()
    return {"id": row.id, "revision": row.revision}


@router.delete("/journals/{logical_id}")
def delete_journal(logical_id: str, db: DB, principal: PRINCIPAL):
    latest = db.scalar(select(JournalEntry).where(
        JournalEntry.tenant_id == principal.tenant_id,
        JournalEntry.logical_id == logical_id,
    ).order_by(JournalEntry.revision.desc()).limit(1))
    if not latest:
        raise HTTPException(status_code=404, detail="journal not found")
    ensure_user(db, principal, latest.user_id)
    row = JournalEntry(
        event_id=f"evt_{uuid4().hex}",
        tenant_id=principal.tenant_id,
        user_id=latest.user_id,
        logical_id=logical_id,
        revision=latest.revision + 1,
        body_ciphertext=None,
        event_tags=[],
        deleted=True,
        client_time=datetime.now(timezone.utc),
        supersedes_id=latest.id,
    )
    db.add(row)
    db.flush()
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="journal.delete_tombstone", object_type="journal", object_id=row.id,
                 metadata={"logical_id": logical_id, "revision": row.revision})
    db.commit()
    return {"logical_id": logical_id, "deleted": True, "revision": row.revision}


@router.get("/journals")
def list_journals(user_id: str, db: DB, principal: PRINCIPAL, limit: int = Query(50, ge=1, le=200)):
    ensure_user(db, principal, user_id)
    rows = db.scalars(select(JournalEntry).where(
        JournalEntry.tenant_id == principal.tenant_id,
        JournalEntry.user_id == user_id,
    ).order_by(JournalEntry.created_at.desc()).limit(limit * 4)).all()
    latest: dict[str, JournalEntry] = {}
    for row in rows:
        latest.setdefault(row.logical_id, row)
    output = []
    for row in list(latest.values())[:limit]:
        if row.deleted:
            continue
        output.append({
            "logical_id": row.logical_id,
            "revision": row.revision,
            "body": decrypt_text(row.body_ciphertext, aad=f"{principal.tenant_id}:{user_id}:journal"),
            "event_tags": row.event_tags,
            "client_time": row.client_time,
        })
    return output


@router.post("/safety/check")
def safety_check(payload: FreeTextSafetyCheck, db: DB, principal: PRINCIPAL):
    ensure_user(db, principal, payload.user_id)
    result = evaluate_text(payload.text)
    signal = RiskSignal(
        tenant_id=principal.tenant_id,
        user_id=payload.user_id,
        source="free_text",
        severity=result.severity,
        rule_pack_version=RULE_PACK_VERSION,
        evidence_refs=result.matched_rule_ids,
        labels=result.labels,
    )
    db.add(signal)
    db.flush()
    escalation_id = None
    if result.severity == "red":
        escalation_id = open_escalation(
            db,
            tenant_id=principal.tenant_id,
            user_id=payload.user_id,
            trigger="text_red_signal",
            evidence_summary="文本命中确定性红色规则；原文不写入审计日志。",
            actor_id="rules",
        ).id
    append_audit(db, tenant_id=principal.tenant_id, actor_type="safety_service", actor_id="rules",
                 action="safety.evaluate", object_type="risk_signal", object_id=signal.id,
                 metadata={"severity": result.severity, "rules": result.matched_rule_ids})
    db.commit()
    return {**result.__dict__, "rule_pack_version": RULE_PACK_VERSION, "escalation_id": escalation_id}


@router.post("/questionnaires/{code}/responses")
def questionnaire(code: str, payload: QuestionnaireCreate, db: DB, principal: PRINCIPAL):
    ensure_user(db, principal, payload.user_id)
    require_psychological_consent(db, principal, payload.user_id)
    existing = db.scalar(select(QuestionnaireResult).where(
        QuestionnaireResult.tenant_id == principal.tenant_id,
        QuestionnaireResult.event_id == payload.event_id,
    ))
    if existing:
        return {"id": existing.id, "score": existing.score, "idempotent_replay": True}
    code = code.lower()
    if code == "phq9":
        result = score_phq9(payload.answers)
    elif code == "gad7":
        result = score_gad7(payload.answers)
    else:
        raise HTTPException(status_code=404, detail="unsupported questionnaire")
    row = QuestionnaireResult(
        event_id=payload.event_id,
        tenant_id=principal.tenant_id,
        user_id=payload.user_id,
        instrument=code,
        version=payload.version,
        answers=payload.answers,
        score=result.score,
        interpretation=result.interpretation,
    )
    db.add(row)
    db.flush()
    escalation_id = None
    if result.urgent_item:
        escalation_id = open_escalation(
            db,
            tenant_id=principal.tenant_id,
            user_id=payload.user_id,
            trigger="phq9_item9_positive",
            evidence_summary="PHQ-9 高风险题项非零，需立即人工复核；分数本身不构成诊断。",
            actor_id="questionnaire_rules",
        ).id
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="questionnaire.score", object_type="questionnaire_result", object_id=row.id,
                 metadata={"instrument": code, "score": result.score, "urgent_item": result.urgent_item})
    db.commit()
    return {"id": row.id, "score": result.score, "interpretation": result.interpretation,
            "urgent_item": result.urgent_item, "escalation_id": escalation_id,
            "boundary": "筛查结果不是诊断。"}


@router.post("/practices/completions")
def practice_completion(payload: PracticeCompletionCreate, db: DB, principal: PRINCIPAL):
    ensure_user(db, principal, payload.user_id)
    existing = db.scalar(select(PracticeCompletion).where(
        PracticeCompletion.tenant_id == principal.tenant_id,
        PracticeCompletion.event_id == payload.event_id,
    ))
    if existing:
        return {"id": existing.id, "idempotent_replay": True}
    row = PracticeCompletion(
        event_id=payload.event_id,
        tenant_id=principal.tenant_id,
        user_id=payload.user_id,
        practice_id=payload.practice_id,
        content_version=payload.content_version,
        status=payload.status,
        duration_seconds=payload.duration_seconds,
        client_time=payload.client_time,
    )
    db.add(row)
    db.flush()
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="practice.record", object_type="practice_completion", object_id=row.id,
                 metadata={"practice_id": payload.practice_id, "status": payload.status})
    db.commit()
    return {"id": row.id, "status": row.status}


@router.get("/trends/summary")
def trends(user_id: str, db: DB, principal: PRINCIPAL, days: int = Query(14, ge=7, le=90)):
    ensure_user(db, principal, user_id)
    return build_trend(db, principal.tenant_id, user_id, days)


@router.post("/escalations")
def create_escalation(payload: EscalationCreate, db: DB, principal: PRINCIPAL):
    ensure_user(db, principal, payload.user_id)
    existing = db.scalar(select(Escalation).where(
        Escalation.tenant_id == principal.tenant_id,
        Escalation.event_id == payload.event_id,
    ))
    if existing:
        return {"id": existing.id, "status": existing.status, "idempotent_replay": True}
    row = open_escalation(
        db,
        tenant_id=principal.tenant_id,
        user_id=payload.user_id,
        trigger=payload.trigger,
        evidence_summary=payload.evidence_summary,
        actor_id=principal.subject,
        source_event_id=payload.event_id,
    )
    db.commit()
    return {"id": row.id, "status": row.status}


@router.get("/escalations")
def list_escalations(
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("on_call", "professional", "admin", "auditor"))],
    status: str | None = None,
):
    query = select(Escalation).where(Escalation.tenant_id == principal.tenant_id)
    if status:
        query = query.where(Escalation.status == status)
    rows = db.scalars(query.order_by(Escalation.opened_at.desc())).all()
    now = datetime.now(timezone.utc)
    def age_seconds(value: datetime) -> float:
        aware = value if value.tzinfo else value.replace(tzinfo=timezone.utc)
        return (now - aware).total_seconds()
    return [{
        "id": x.id,
        "user_id": x.user_id,
        "level": x.level,
        "status": x.status,
        "trigger": x.trigger,
        "evidence_summary": x.evidence_summary,
        "opened_at": x.opened_at,
        "ack_at": x.ack_at,
        "takeover_at": x.takeover_at,
        "closed_at": x.closed_at,
        "reviewed_at": x.reviewed_at,
        "assigned_to": x.assigned_to,
        "disposition": x.disposition,
        "ack_sla_breached": x.ack_at is None and age_seconds(x.opened_at) > settings.ack_sla_seconds,
        "takeover_sla_breached": x.takeover_at is None and age_seconds(x.opened_at) > settings.takeover_sla_seconds,
    } for x in rows]


@router.get("/escalations/metrics")
def escalation_metrics(
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("professional", "admin", "auditor"))],
):
    rows = db.scalars(select(Escalation).where(Escalation.tenant_id == principal.tenant_id)).all()
    ack_seconds = [(x.ack_at - x.opened_at).total_seconds() for x in rows if x.ack_at]
    takeover_seconds = [(x.takeover_at - x.opened_at).total_seconds() for x in rows if x.takeover_at]
    return {
        "total": len(rows),
        "open": sum(x.status in {"open", "acknowledged", "taken_over"} for x in rows),
        "closed": sum(x.status in {"closed", "reviewed"} for x in rows),
        "ack_p50_seconds": sorted(ack_seconds)[len(ack_seconds)//2] if ack_seconds else None,
        "takeover_p50_seconds": sorted(takeover_seconds)[len(takeover_seconds)//2] if takeover_seconds else None,
        "ack_sla_seconds": settings.ack_sla_seconds,
        "takeover_sla_seconds": settings.takeover_sla_seconds,
    }


@router.post("/escalations/{escalation_id}/ack")
def ack_escalation(
    escalation_id: str,
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("on_call", "professional", "admin"))],
):
    row = get_escalation(db, principal, escalation_id)
    if row.status in {"closed", "reviewed"}:
        raise HTTPException(status_code=409, detail="already closed")
    if row.ack_at is None:
        row.ack_at = datetime.now(timezone.utc)
    if row.status == "open":
        row.status = "acknowledged"
    row.assigned_to = row.assigned_to or principal.subject
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="escalation.ack", object_type="escalation", object_id=row.id)
    db.commit()
    return {"id": row.id, "status": row.status, "ack_at": row.ack_at}


@router.post("/escalations/{escalation_id}/takeover")
def takeover(
    escalation_id: str,
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("on_call", "professional", "admin"))],
):
    row = get_escalation(db, principal, escalation_id)
    if row.status in {"closed", "reviewed"}:
        raise HTTPException(status_code=409, detail="already closed")
    now = datetime.now(timezone.utc)
    row.ack_at = row.ack_at or now
    row.takeover_at = row.takeover_at or now
    row.status = "taken_over"
    row.assigned_to = principal.subject
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="escalation.takeover", object_type="escalation", object_id=row.id)
    db.commit()
    return {"id": row.id, "status": row.status, "takeover_at": row.takeover_at}


@router.post("/escalations/{escalation_id}/close")
def close_escalation(
    escalation_id: str,
    payload: EscalationClose,
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("professional", "admin"))],
):
    row = get_escalation(db, principal, escalation_id)
    if row.takeover_at is None:
        raise HTTPException(status_code=409, detail="takeover required before close")
    if row.status == "reviewed":
        raise HTTPException(status_code=409, detail="already reviewed")
    row.status = "closed"
    row.closed_at = row.closed_at or datetime.now(timezone.utc)
    row.disposition = payload.disposition
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="escalation.close", object_type="escalation", object_id=row.id)
    db.commit()
    return {"id": row.id, "status": row.status, "closed_at": row.closed_at}


@router.post("/escalations/{escalation_id}/review")
def review_escalation(
    escalation_id: str,
    payload: EscalationReview,
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("professional", "admin"))],
):
    row = get_escalation(db, principal, escalation_id)
    if row.status not in {"closed", "reviewed"}:
        raise HTTPException(status_code=409, detail="close required before review")
    row.status = "reviewed"
    row.reviewed_at = row.reviewed_at or datetime.now(timezone.utc)
    row.review_notes = payload.review_notes
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="escalation.review", object_type="escalation", object_id=row.id)
    db.commit()
    return {"id": row.id, "status": row.status, "reviewed_at": row.reviewed_at}


@router.post("/data-subject-requests")
def create_dsr(payload: DataSubjectRequestCreate, db: DB, principal: PRINCIPAL):
    ensure_user(db, principal, payload.user_id)
    existing = db.scalar(select(DataSubjectRequest).where(
        DataSubjectRequest.tenant_id == principal.tenant_id,
        DataSubjectRequest.event_id == payload.event_id,
    ))
    if existing:
        return {"id": existing.id, "status": existing.status, "idempotent_replay": True}
    row = DataSubjectRequest(
        event_id=payload.event_id,
        tenant_id=principal.tenant_id,
        user_id=payload.user_id,
        request_type=payload.request_type,
    )
    db.add(row)
    db.flush()
    if payload.request_type == "revoke_service":
        user = db.get(User, payload.user_id)
        if user:
            user.status = "withdrawal_pending"
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="dsr.create", object_type="data_subject_request", object_id=row.id,
                 metadata={"request_type": payload.request_type})
    db.commit()
    return {"id": row.id, "status": row.status}


@router.get("/data-subject-requests")
def list_dsr(
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("admin", "auditor"))],
    status: str | None = None,
):
    query = select(DataSubjectRequest).where(DataSubjectRequest.tenant_id == principal.tenant_id)
    if status:
        query = query.where(DataSubjectRequest.status == status)
    rows = db.scalars(query.order_by(DataSubjectRequest.requested_at)).all()
    return [{
        "id": x.id,
        "user_id": x.user_id,
        "request_type": x.request_type,
        "status": x.status,
        "requested_at": x.requested_at,
        "completed_at": x.completed_at,
    } for x in rows]


@router.post("/data-subject-requests/{request_id}/complete")
def complete_dsr(
    request_id: str,
    payload: DataSubjectRequestComplete,
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("admin"))],
):
    row = db.get(DataSubjectRequest, request_id)
    if not row or row.tenant_id != principal.tenant_id:
        raise HTTPException(status_code=404, detail="not found")
    row.status = "completed"
    row.completed_at = datetime.now(timezone.utc)
    row.result_summary = payload.result_summary
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="dsr.complete", object_type="data_subject_request", object_id=row.id,
                 metadata={"request_type": row.request_type})
    db.commit()
    return {"id": row.id, "status": row.status, "completed_at": row.completed_at}


@router.get("/audit/events")
def audit_events(
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("auditor", "admin"))],
    limit: int = Query(100, ge=1, le=1000),
):
    rows = db.scalars(select(AuditEvent).where(
        AuditEvent.tenant_id == principal.tenant_id,
    ).order_by(AuditEvent.occurred_at.desc()).limit(limit)).all()
    return [{
        "event_id": x.event_id,
        "occurred_at": x.occurred_at,
        "actor_type": x.actor_type,
        "actor_id": x.actor_id,
        "action": x.action,
        "object_type": x.object_type,
        "object_id": x.object_id,
        "previous_event_hash": x.previous_event_hash,
        "event_hash": x.event_hash,
    } for x in rows]


@router.get("/audit/verify")
def audit_verify(
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("auditor", "admin"))],
):
    return verify_audit_chain(db, principal.tenant_id)
