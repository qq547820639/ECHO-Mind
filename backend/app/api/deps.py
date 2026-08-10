"""API 共享依赖（v0.6.1）：bounded-context 拆分后的公共 helper / 常量。

所有子 router 从这里导入共享逻辑；禁止各 router 自行复制实现。
"""
from __future__ import annotations

from datetime import UTC, datetime
from typing import Annotated
from uuid import uuid4

from fastapi import Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth import (
    NON_DATA_ROLES,
    READ_ONLY_ROLES,
    Principal,
    create_access_token,
    get_principal,
)
from app.database import get_db
from app.models import Consent, Escalation, OnboardingScreening, User
from app.schemas import OnboardingVerifyOut
from app.services.audit import append_audit

DB = Annotated[Session, Depends(get_db)]
PRINCIPAL = Annotated[Principal, Depends(get_principal)]

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
    if principal.role not in {"user", "professional"}:
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


def require_passive_sensing_consent(db: Session, principal: Principal, user_id: str) -> None:
    row = latest_consent(db, principal.tenant_id, user_id, "passive_sensing")
    if not row or not row.granted or row.revoked_at is not None:
        raise HTTPException(status_code=412, detail="active passive-sensing consent required")


def require_voice_features_consent(db: Session, principal: Principal, user_id: str) -> None:
    """mic_opt 派生特征专用：当前用户/租户必须有有效 voice_features consent。"""
    row = latest_consent(db, principal.tenant_id, user_id, "voice_features")
    if not row or not row.granted or row.revoked_at is not None:
        raise HTTPException(status_code=412, detail="active voice-features consent required")


def require_feature_flag(flag_key: str):
    """灰度回滚：FastAPI 依赖工厂，校验当前租户某 feature flag 是否开启。

    关闭则返回 410 Gone（与路由层 idempotent 410 语义一致）。
    租户不存在时放行（交由下游 ensure_user 等返回 404），避免泄露 flag 状态。
    """
    from app.services.feature_flags import is_flag_enabled, tenant_exists

    def dependency(
        db: Annotated[Session, Depends(get_db)],
        principal: Annotated[Principal, Depends(get_principal)],
    ) -> None:
        if not tenant_exists(db, principal.tenant_id):
            return
        if not is_flag_enabled(db, principal.tenant_id, flag_key):
            raise HTTPException(status_code=410, detail=f"{flag_key} disabled for tenant")

    return dependency


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
    """打开 L3 升级事件（幂等：同 tenant+event_id 返回既有记录）。

    - 服务端接收事件即写 delivery_confirmed_at（送达确认）；
      与人工接管（ack/takeover）严格区分，绝不把"已送达"当作"人工已收到"。
    """
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
        delivery_confirmed_at=datetime.now(UTC),
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


def build_verify_out(db: Session, user: User) -> OnboardingVerifyOut:
    """按用户组装 verify-code 输出（consent_versions + l0_decision）。"""
    access_token = create_access_token(subject=user.id, tenant_id=user.tenant_id, role="user")
    consent_versions: dict[str, str] = {}
    for consent_type in ("psychological_data", "passive_sensing", "voice_features"):
        consent = latest_consent(db, user.tenant_id, user.id, consent_type)
        if consent is not None:
            consent_versions[consent_type] = consent.version
    screening = db.scalar(
        select(OnboardingScreening).where(
            OnboardingScreening.tenant_id == user.tenant_id,
            OnboardingScreening.user_id == user.id,
        ).order_by(OnboardingScreening.created_at.desc()).limit(1)
    )
    l0_decision = screening.decision if screening is not None else None
    return OnboardingVerifyOut(
        user_id=user.id,
        access_token=access_token,
        consent_versions=consent_versions,
        l0_decision=l0_decision,
        restricted=False,
    )
