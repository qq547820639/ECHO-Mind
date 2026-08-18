"""Onboarding 领域路由（v0.6.1 拆分）：租户/用户/激活码交换/L0/紧急联系人。

权限声明靠近 endpoint；业务逻辑下沉 services。
"""
from __future__ import annotations

import secrets
from typing import Annotated, Any

from fastapi import APIRouter, Header, HTTPException, Request
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.models import EmergencyContact, OnboardingScreening, Tenant, User
from app.schemas import (
    EmergencyContactCreate,
    L0ScreeningCreate,
    OnboardingVerifyIn,
    OnboardingVerifyOut,
    TenantCreate,
    UserCreate,
)
from app.services.audit import append_audit
from app.services.crypto import encrypt_text
from app.services.activation import redeem_code

from app.api.deps import (
    DB,
    PRINCIPAL,
    build_verify_out,
    ensure_user,
    latest_consent,
    open_escalation,
    require_write_role,
)

router = APIRouter(prefix="/v1")


@router.post("/tenants")
def create_tenant(
    payload: TenantCreate,
    db: DB,
    x_bootstrap_key: Annotated[str | None, Header()] = None,
) -> dict[str, Any]:
    from app.config import get_settings

    # 审计 P3 修复：高熵 key 比对改常数时间比较（防时序侧信道）。
    if x_bootstrap_key is None or not secrets.compare_digest(
        x_bootstrap_key, get_settings().bootstrap_key
    ):
        raise HTTPException(status_code=403, detail="invalid bootstrap key")
    tenant = Tenant(name=payload.name)
    db.add(tenant)
    db.commit()
    db.refresh(tenant)
    return {"id": tenant.id, "name": tenant.name}


@router.post("/users")
def create_user(payload: UserCreate, db: DB, principal: PRINCIPAL) -> dict[str, Any]:
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
    # T8-P2-8 补测暴露的缺陷修复：flush 同样可能触发唯一约束（SQLite 立即执行 INSERT），
    # 原先 flush 在 try 外 → 重复 external_ref 返回 500 而非约定的 409。flush/audit/commit
    # 同置 try 内：失败创建不落审计、统一 409。
    try:
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
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(status_code=409, detail="external_ref already exists") from exc
    db.refresh(user)
    return {"id": user.id, "status": user.status}


@router.post("/onboarding/l0")
def create_l0(payload: L0ScreeningCreate, db: DB, principal: PRINCIPAL) -> dict[str, Any]:
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
def create_emergency_contact(payload: EmergencyContactCreate, db: DB, principal: PRINCIPAL) -> dict[str, Any]:
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


@router.post("/onboarding/verify-code", response_model=OnboardingVerifyOut)
def verify_onboarding_code(payload: OnboardingVerifyIn, db: DB, request: Request) -> OnboardingVerifyOut:
    """激活码交换（v0.6.1）：预认证薄路由，供端侧激活使用。

    主路径：机构激活码（ActivationCode）——哈希存储 / TTL / 一次性消费 /
    防爆破 rate limit / IP+device 审计；revoked/expired/replay/restricted/超限 → 403。
    legacy 回退（v0.8 退役）：未命中时回退查 User.external_ref == code。
    响应不含 tenant_id / role / external_ref 等内部字段。
    """
    code = payload.code.strip()
    device_id = request.headers.get("x-device-id")
    actor_ip = request.client.host if request.client else None

    consumed, reason = redeem_code(db, code=code, actor_ip=actor_ip, device_id=device_id)
    if consumed is None:
        if reason is not None and reason != "not_found":
            # P0-2 修复：拒绝路径先持久化防爆破状态（attempt 行 + attempt_count
            # 递增）再抛 403——否则 HTTPException 后依赖 teardown 回滚事务，
            # 失败计数永不落库，rate limit / max_attempts 在真实 HTTP 语义下失效。
            db.commit()
            raise HTTPException(status_code=403, detail="该激活码已受限，请联系机构")
        # legacy 回退：external_ref 旧语义（v0.8 退役）
        # 审计 P2-9 修复：加确定性排序（created_at, id 升序取最早命中），
        # 多租户同 external_ref 时命中唯一确定（不再依赖数据库返回顺序）。
        user = db.scalar(
            select(User)
            .where(User.external_ref == code)
            .order_by(User.created_at.asc(), User.id.asc())
            .limit(1)
        )
        if user is None:
            # P0-2 修复：not_found 的失败 attempt 行同样先持久化（IP/device
            # 维度 rate limit 的数据来源），再统一 404。
            db.commit()
            raise HTTPException(status_code=404, detail="无效激活码")
        if user.status != "active":
            raise HTTPException(status_code=403, detail="该激活码已受限，请联系机构")
        append_audit(
            db,
            tenant_id=user.tenant_id,
            actor_type="user",
            actor_id=user.id,
            action="onboarding.verify",
            object_type="user",
            object_id=user.id,
            metadata={"restricted": False, "legacy_external_ref": True},
        )
        db.commit()
        return build_verify_out(db, user)

    user = db.get(User, consumed.user_id)
    if user is None or user.tenant_id != consumed.tenant_id or user.status != "active":
        raise HTTPException(status_code=403, detail="该激活码已受限，请联系机构")
    db.commit()
    return build_verify_out(db, user)
