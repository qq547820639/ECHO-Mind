from datetime import datetime, timezone
from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.auth import (
    NON_DATA_ROLES,
    PSYCH_CONTENT_ROLES,
    READ_ONLY_ROLES,
    Principal,
    get_principal,
    require_roles,
)
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
from app.services.escalation import scan_sla_breaches
from app.services.safety import RULE_PACK_VERSION, evaluate_text, resolve_rule_ids
from app.services.scoring import score_gad7, score_phq9
from app.services.trends import build_trend

router = APIRouter(prefix="/v1")
DB = Annotated[Session, Depends(get_db)]
PRINCIPAL = Annotated[Principal, Depends(get_principal)]
settings = get_settings()

# 队列展示的风险类型标签；未登记的 trigger 原样透传，保证可溯源。
RISK_TYPE_LABELS = {
    "l0_current_danger": "准入当前危险",
    "help_requested": "用户主动求助",
    "text_red_signal": "文本红色信号",
    "journal_red_signal": "日记红色信号",
    "phq9_item9_positive": "PHQ-9 高风险题项",
}


def forbid(
    db: Session,
    principal: Principal,
    *,
    action: str,
    object_type: str,
    object_id: str,
    detail: str,
) -> None:
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action=action,
        object_type=object_type,
        object_id=object_id,
    )
    db.commit()
    raise HTTPException(status_code=403, detail=detail)


def require_write_role(db: Session, principal: Principal, *, object_type: str) -> None:
    if principal.role in READ_ONLY_ROLES or principal.role in NON_DATA_ROLES:
        forbid(db, principal, action="authz.write_denied", object_type=object_type,
               object_id=principal.subject, detail="role is not permitted to write")


def require_psych_content_role(db: Session, principal: Principal, *, user_id: str) -> None:
    if principal.role not in PSYCH_CONTENT_ROLES:
        forbid(db, principal, action="authz.psych_content_denied", object_type="user",
               object_id=user_id, detail="role cannot access psychological content")


def require_step_up(db: Session, principal: Principal, *, object_type: str, object_id: str) -> None:
    if not principal.step_up:
        forbid(db, principal, action="authz.step_up_denied", object_type=object_type,
               object_id=object_id, detail="step-up authentication required")


def ensure_user(db: Session, principal: Principal, user_id: str) -> User:
    if principal.role in NON_DATA_ROLES:
        forbid(db, principal, action="authz.denied", object_type="user",
               object_id=user_id, detail="role has no data access")
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
        # 服务端接收事件即视为送达确认；与人工接管（ack/takeover）严格区分。
        delivery_confirmed_at=datetime.now(timezone.utc),
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
        city=payload.city,
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
    require_write_role(db, principal, object_type="consent")
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
    require_write_role(db, principal, object_type="onboarding_screening")
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
    require_write_role(db, principal, object_type="emergency_contact")
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
    require_psych_content_role(db, principal, user_id=payload.user_id)
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
    require_psych_content_role(db, principal, user_id=payload.user_id)
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
    require_psych_content_role(db, principal, user_id=latest.user_id)
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
    require_psych_content_role(db, principal, user_id=latest.user_id)
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
    require_psych_content_role(db, principal, user_id=user_id)
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
    require_psych_content_role(db, principal, user_id=payload.user_id)
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
    require_psych_content_role(db, principal, user_id=payload.user_id)
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
    require_write_role(db, principal, object_type="practice_completion")
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
    require_psych_content_role(db, principal, user_id=user_id)
    ensure_user(db, principal, user_id)
    return build_trend(db, principal.tenant_id, user_id, days)


@router.post("/escalations")
def create_escalation(payload: EscalationCreate, db: DB, principal: PRINCIPAL):
    require_write_role(db, principal, object_type="escalation")
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
    principal: Annotated[Principal, Depends(require_roles(
        "on_call", "professional", "admin", "auditor", "quality_reviewer", "security_auditor",
    ))],
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
    user_ids = {x.user_id for x in rows}
    users: dict[str, User] = {}
    contact_status: dict[str, str] = {}
    if user_ids:
        users = {u.id: u for u in db.scalars(select(User).where(
            User.tenant_id == principal.tenant_id, User.id.in_(user_ids),
        )).all()}
        contacts = db.scalars(select(EmergencyContact).where(
            EmergencyContact.tenant_id == principal.tenant_id,
            EmergencyContact.user_id.in_(user_ids),
        )).all()
        for contact in contacts:
            # 任一联系人可用即"可用"；登记过但全部停用为"不可用"；未登记另行标注。
            if contact.active:
                contact_status[contact.user_id] = "可用"
            else:
                contact_status.setdefault(contact.user_id, "不可用")
    return [{
        "id": x.id,
        "user_id": x.user_id,
        "level": x.level,
        "status": x.status,
        "trigger": x.trigger,
        "risk_type": RISK_TYPE_LABELS.get(x.trigger, x.trigger),
        # 高危证据摘要仅在持有 step-up 声明时可见；队列元数据不受限。
        "evidence_summary": x.evidence_summary if principal.step_up else None,
        "opened_at": x.opened_at,
        "waiting_seconds": int(age_seconds(x.opened_at)),
        "ack_at": x.ack_at,
        "takeover_at": x.takeover_at,
        "closed_at": x.closed_at,
        "reviewed_at": x.reviewed_at,
        "assigned_to": x.assigned_to,
        "disposition": x.disposition,
        # 升级链路状态：通知不等于接管；链路失效单独标识。
        "escalation_level": x.escalation_level,
        "second_duty_notified": x.notified_l1_at is not None,
        "notified_l1_at": x.notified_l1_at,
        "notified_l2_at": x.notified_l2_at,
        "chain_broken": x.chain_broken_at is not None,
        # 仅表示服务端已确认接收事件；不得解读为"人工已收到"。
        "delivery_confirmed": x.delivery_confirmed_at is not None,
        "delivery_confirmed_at": x.delivery_confirmed_at,
        "user_city": users[x.user_id].city if x.user_id in users else None,
        "emergency_contact_status": contact_status.get(x.user_id, "未登记"),
        "ack_sla_breached": x.ack_at is None and age_seconds(x.opened_at) > settings.ack_sla_seconds,
        "takeover_sla_breached": x.takeover_at is None and age_seconds(x.opened_at) > settings.takeover_sla_seconds,
    } for x in rows]


@router.get("/escalations/metrics")
def escalation_metrics(
    db: DB,
    principal: Annotated[Principal, Depends(require_roles(
        "professional", "admin", "auditor", "quality_reviewer", "security_auditor",
    ))],
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


@router.post("/escalations/sla-scan")
def sla_scan(
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("on_call", "admin"))],
):
    """运维定时触发的 SLA 扫描：推进未确认红色事件的自动升级链路。

    幂等：每一档升级由各自的时间戳守卫，重复调用不会重复通知。扫描只写
    通知/失效等生命周期字段，绝不改变 ack/takeover 接管状态。
    """
    summary = scan_sla_breaches(db, tenant_id=principal.tenant_id, actor_id=principal.subject)
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="escalation.sla_scan",
        object_type="escalation",
        object_id="sla-scan",
        metadata={
            "scanned": summary["scanned"],
            "notified_second_duty": len(summary["notified_second_duty"]),
            "notified_org_lead": len(summary["notified_org_lead"]),
            "chain_broken": len(summary["chain_broken"]),
        },
    )
    db.commit()
    return summary


@router.get("/escalations/{escalation_id}")
def escalation_detail(
    escalation_id: str,
    db: DB,
    principal: Annotated[Principal, Depends(require_roles(
        "on_call", "professional", "admin", "auditor", "quality_reviewer", "security_auditor",
    ))],
):
    row = get_escalation(db, principal, escalation_id)
    require_step_up(db, principal, object_type="escalation", object_id=row.id)
    return {
        "id": row.id,
        "user_id": row.user_id,
        "level": row.level,
        "status": row.status,
        "trigger": row.trigger,
        "evidence_summary": row.evidence_summary,
        "opened_at": row.opened_at,
        "ack_at": row.ack_at,
        "takeover_at": row.takeover_at,
        "closed_at": row.closed_at,
        "reviewed_at": row.reviewed_at,
        "assigned_to": row.assigned_to,
        "disposition": row.disposition,
        "review_notes": row.review_notes,
        "escalation_level": row.escalation_level,
        "notified_l1_at": row.notified_l1_at,
        "notified_l2_at": row.notified_l2_at,
        "chain_broken_at": row.chain_broken_at,
        "delivery_confirmed_at": row.delivery_confirmed_at,
        "contact_method": row.contact_method,
        "contact_succeeded": row.contact_succeeded,
        "safety_status": row.safety_status,
        "emergency_contact_called": row.emergency_contact_called,
        "referred_12356": row.referred_12356,
        "called_emergency_services": row.called_emergency_services,
        "follow_up_plan": row.follow_up_plan,
        "operator_signature": row.operator_signature,
    }


@router.get("/escalations/{escalation_id}/user-status")
def escalation_user_status(
    escalation_id: str,
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("user", "on_call", "professional", "admin"))],
):
    """用户侧状态查询：只暴露送达确认与接管状态，不暴露内部升级细节。

    human_acknowledged 仅由显式 ack/takeover 决定；系统通知（notified_*）与
    送达确认（delivery_confirmed）都不得被当作"人工已收到/已接管"。
    """
    row = get_escalation(db, principal, escalation_id)
    if principal.role == "user" and principal.subject != row.user_id:
        raise HTTPException(status_code=403, detail="cannot access another user")
    human_acknowledged = row.ack_at is not None or row.takeover_at is not None
    return {
        "escalation_id": row.id,
        "delivery_confirmed": row.delivery_confirmed_at is not None,
        "human_acknowledged": human_acknowledged,
        # 未接管时始终显示主动拨号入口。
        "dial_entry_visible": not human_acknowledged,
        "chain_broken": row.chain_broken_at is not None,
    }


# 个案复核证据链仅对复核类角色开放；on_call 在此映射 spec 中的 clinical_lead。
CASE_REVIEW_ROLES = ("professional", "on_call", "quality_reviewer")


@router.get("/escalations/{escalation_id}/case-review")
def escalation_case_review(escalation_id: str, db: DB, principal: PRINCIPAL):
    """个案复核证据链视图。

    区块化返回完整证据：用户直接表达、规则触发依据、安全分类器结果、
    PHQ-9/GAD-7 原始答案、最近签到趋势、数据质量、历史风险事件与人工处置
    记录。需要 step-up 二次认证；admin 等心理内容受限角色一律拒绝并留痕
    （沿用 Task 1 守卫语义），绝不只输出单一"AI 风险分数"。
    """
    row = get_escalation(db, principal, escalation_id)
    if principal.role not in CASE_REVIEW_ROLES:
        forbid(db, principal, action="authz.psych_content_denied", object_type="escalation",
               object_id=row.id, detail="role cannot access case review evidence")
    require_step_up(db, principal, object_type="escalation", object_id=row.id)
    tenant_id = principal.tenant_id
    user_id = row.user_id

    checkins = db.scalars(select(Checkin).where(
        Checkin.tenant_id == tenant_id, Checkin.user_id == user_id,
    ).order_by(Checkin.created_at.desc()).limit(5)).all()
    journals = db.scalars(select(JournalEntry).where(
        JournalEntry.tenant_id == tenant_id,
        JournalEntry.user_id == user_id,
        JournalEntry.deleted.is_(False),
    ).order_by(JournalEntry.created_at.desc()).limit(5)).all()
    direct_expressions = [
        {
            "source": "checkin",
            "record_id": c.id,
            "client_time": c.client_time,
            "text": decrypt_text(c.note_ciphertext, aad=f"{tenant_id}:{user_id}:checkin"),
        }
        for c in checkins if c.note_ciphertext
    ] + [
        {
            "source": "journal",
            "record_id": j.id,
            "client_time": j.client_time,
            "text": decrypt_text(j.body_ciphertext, aad=f"{tenant_id}:{user_id}:journal"),
        }
        for j in journals if j.body_ciphertext
    ]

    signals = db.scalars(select(RiskSignal).where(
        RiskSignal.tenant_id == tenant_id, RiskSignal.user_id == user_id,
    ).order_by(RiskSignal.created_at.desc()).limit(20)).all()
    rule_hits = [{
        "signal_id": s.id,
        "source": s.source,
        "severity": s.severity,
        "rule_pack_version": s.rule_pack_version,
        "matched_rules": resolve_rule_ids(s.evidence_refs),
        "labels": s.labels,
        "created_at": s.created_at,
    } for s in signals]
    latest_signal = signals[0] if signals else None
    safety_classifier = {
        "latest_severity": latest_signal.severity if latest_signal else None,
        "latest_labels": latest_signal.labels if latest_signal else [],
        "current_rule_pack_version": RULE_PACK_VERSION,
        "signal_count": len(signals),
        "red_signal_count": sum(1 for s in signals if s.severity == "red"),
    }

    questionnaires = db.scalars(select(QuestionnaireResult).where(
        QuestionnaireResult.tenant_id == tenant_id,
        QuestionnaireResult.user_id == user_id,
    ).order_by(QuestionnaireResult.created_at.desc()).limit(10)).all()

    trend = build_trend(db, tenant_id, user_id, 14)
    data_quality = {
        "window_days": trend["window_days"],
        "data_days": trend["data_days"],
        "coverage": trend["coverage"],
        "baseline_ready": trend["baseline_ready"],
        "questionnaire_count": len(questionnaires),
        "risk_signal_count": len(signals),
    }

    history = db.scalars(select(Escalation).where(
        Escalation.tenant_id == tenant_id, Escalation.user_id == user_id,
    ).order_by(Escalation.opened_at.desc()).limit(20)).all()

    trail = db.scalars(select(AuditEvent).where(
        AuditEvent.tenant_id == tenant_id,
        AuditEvent.object_type == "escalation",
        AuditEvent.object_id == row.id,
    ).order_by(AuditEvent.occurred_at)).all()

    return {
        "escalation": {
            "id": row.id,
            "user_id": row.user_id,
            "level": row.level,
            "status": row.status,
            "trigger": row.trigger,
            "risk_type": RISK_TYPE_LABELS.get(row.trigger, row.trigger),
            "evidence_summary": row.evidence_summary,
            "opened_at": row.opened_at,
            "escalation_level": row.escalation_level,
            "chain_broken": row.chain_broken_at is not None,
            "delivery_confirmed": row.delivery_confirmed_at is not None,
        },
        "direct_expressions": direct_expressions,
        "rule_hits": rule_hits,
        "safety_classifier": safety_classifier,
        "questionnaires": [{
            "id": q.id,
            "instrument": q.instrument,
            "version": q.version,
            "answers": q.answers,
            "score": q.score,
            "interpretation": q.interpretation,
            "created_at": q.created_at,
        } for q in questionnaires],
        "recent_trend": trend,
        "data_quality": data_quality,
        "risk_history": [{
            "id": h.id,
            "trigger": h.trigger,
            "status": h.status,
            "opened_at": h.opened_at,
            "closed_at": h.closed_at,
            "disposition": h.disposition,
            "is_current": h.id == row.id,
        } for h in history],
        "human_handling": {
            "ack_at": row.ack_at,
            "takeover_at": row.takeover_at,
            "assigned_to": row.assigned_to,
            "closed_at": row.closed_at,
            "reviewed_at": row.reviewed_at,
            "disposition": row.disposition,
            "review_notes": row.review_notes,
            "contact_method": row.contact_method,
            "contact_succeeded": row.contact_succeeded,
            "safety_status": row.safety_status,
            "emergency_contact_called": row.emergency_contact_called,
            "referred_12356": row.referred_12356,
            "called_emergency_services": row.called_emergency_services,
            "follow_up_plan": row.follow_up_plan,
            "operator_signature": row.operator_signature,
            "audit_trail": [{
                "occurred_at": e.occurred_at,
                "actor_type": e.actor_type,
                "actor_id": e.actor_id,
                "action": e.action,
            } for e in trail],
        },
        "boundary": "证据链仅供人工复核溯源，不构成诊断；最终专业判断由机构人员承担。",
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


# 关闭事件时必填的接管处置记录字段；布尔字段 False 是有效作答，仅 None 视为缺失。
CLOSE_REQUIRED_FIELDS = (
    "disposition",
    "contact_method",
    "contact_succeeded",
    "safety_status",
    "emergency_contact_called",
    "referred_12356",
    "called_emergency_services",
    "follow_up_plan",
    "operator_signature",
)


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
    missing = [name for name in CLOSE_REQUIRED_FIELDS if getattr(payload, name) is None]
    if missing:
        raise HTTPException(
            status_code=422,
            detail={"missing_fields": missing, "message": "close requires a complete takeover record"},
        )
    row.status = "closed"
    row.closed_at = row.closed_at or datetime.now(timezone.utc)
    for name in CLOSE_REQUIRED_FIELDS:
        setattr(row, name, getattr(payload, name))
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
    require_write_role(db, principal, object_type="data_subject_request")
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
    principal: Annotated[Principal, Depends(require_roles("auditor", "admin", "security_auditor"))],
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
    principal: Annotated[Principal, Depends(require_roles("auditor", "admin", "security_auditor"))],
):
    return verify_audit_chain(db, principal.tenant_id)


def reject_immutable_mutation(
    db: Session,
    request: Request,
    principal: Principal,
    method: str,
    object_type: str,
    object_id: str,
) -> None:
    """Explicitly refuse DELETE/PATCH on append-only resources for every role.

    Risk events and audit events are never modified or removed; the attempt itself
    is appended to the audit log before the request is rejected.
    """
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="security.immutable_mutation_attempt",
        object_type=object_type,
        object_id=object_id,
        metadata={"method": method},
        request_id=request.headers.get("x-request-id"),
    )
    db.commit()
    raise HTTPException(
        status_code=405,
        detail=f"{object_type} records are append-only; {method} is not allowed",
    )


@router.delete("/escalations/{escalation_id}")
def delete_escalation(escalation_id: str, request: Request, db: DB, principal: PRINCIPAL):
    reject_immutable_mutation(db, request, principal, "DELETE", "escalation", escalation_id)


@router.patch("/escalations/{escalation_id}")
def patch_escalation(escalation_id: str, request: Request, db: DB, principal: PRINCIPAL):
    reject_immutable_mutation(db, request, principal, "PATCH", "escalation", escalation_id)


@router.delete("/risk-signals/{signal_id}")
def delete_risk_signal(signal_id: str, request: Request, db: DB, principal: PRINCIPAL):
    reject_immutable_mutation(db, request, principal, "DELETE", "risk_signal", signal_id)


@router.patch("/risk-signals/{signal_id}")
def patch_risk_signal(signal_id: str, request: Request, db: DB, principal: PRINCIPAL):
    reject_immutable_mutation(db, request, principal, "PATCH", "risk_signal", signal_id)


@router.delete("/audit/events/{event_id}")
def delete_audit_event(event_id: str, request: Request, db: DB, principal: PRINCIPAL):
    reject_immutable_mutation(db, request, principal, "DELETE", "audit_event", event_id)


@router.patch("/audit/events/{event_id}")
def patch_audit_event(event_id: str, request: Request, db: DB, principal: PRINCIPAL):
    reject_immutable_mutation(db, request, principal, "PATCH", "audit_event", event_id)
