"""管理域路由（v0.6.1 拆分）：审计 / 灰度 flag / 机构画像 / 激活码管理 / 批量回滚。

权限声明靠近 endpoint；聚合指标尽量下推 SQL。
"""
from __future__ import annotations

from datetime import UTC, datetime
from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select

from app.auth import Principal, require_roles
from app.models import AuditEvent
from app.schemas import (
    ActivationCodeCreate,
    ActivationCodeIssueOut,
    ActivationCodeOut,
    SkillBatchRetire,
    TenantFlagUpdate,
    TenantPortraitOut,
)
from app.services.audit import append_audit, verify_audit_chain
from app.services.feature_flags import get_tenant_flags, set_tenant_flag
from app.services.tenant_portrait import build_tenant_portrait
from app.services.activation import issue_code

from app.api.deps import DB, PRINCIPAL, ensure_user

router = APIRouter(prefix="/v1")


@router.get("/audit/events")
def audit_events(
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("auditor", "admin", "security_auditor"))],
    limit: int = Query(100, ge=1, le=1000),
) -> list[dict[str, datetime | str | None]]:
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
) -> dict[str, Any]:
    return verify_audit_chain(db, principal.tenant_id)


@router.get("/config/flags")
def get_config_flags(db: DB, principal: PRINCIPAL) -> dict[str, Any]:
    """用户拉取本租户的 feature flags（端侧灰度联动；无缓存 fail-closed 由端侧承担）。"""
    return get_tenant_flags(db, principal.tenant_id)


@router.put("/tenant/flags")
def update_tenant_flags(
    payload: TenantFlagUpdate,
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("admin"))],
) -> dict[str, Any]:
    """admin 修改本租户的 feature flag（灰度回滚入口）。"""
    try:
        updated = set_tenant_flag(db, principal.tenant_id, payload.flag_key, payload.value)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="tenant.flags.update",
        object_type="tenant",
        object_id=principal.tenant_id,
        metadata={"flag_key": payload.flag_key, "value": payload.value},
    )
    db.commit()
    return updated


@router.get("/tenant/portrait", response_model=TenantPortraitOut)
def tenant_portrait(
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("admin", "professional", "auditor"))],
) -> dict[str, Any]:
    """机构去标识群体画像（小桶 <5 suppression；不返回单个用户 ID/特征）。"""
    portrait = build_tenant_portrait(db, principal.tenant_id)
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="tenant.portrait.view",
        object_type="tenant",
        object_id=principal.tenant_id,
    )
    db.commit()
    return portrait


@router.post("/admin/activation-codes", response_model=ActivationCodeIssueOut)
def issue_activation_code(
    payload: ActivationCodeCreate,
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("admin"))],
) -> ActivationCodeIssueOut:
    """admin 签发激活码：明文码仅在响应中出现一次；数据库只存 SHA-256 哈希。"""
    if payload.user_id is not None:
        ensure_user(db, principal, payload.user_id)
    code, raw = issue_code(
        db,
        tenant_id=principal.tenant_id,
        created_by=f"{principal.role}:{principal.subject}",
        user_id=payload.user_id,
        ttl_seconds=payload.ttl_seconds,
        max_attempts=payload.max_attempts,
        subscription_days=payload.subscription_days,
    )
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="activation_code.issue",
        object_type="activation_code",
        object_id=code.id,
        metadata={"user_id": payload.user_id, "ttl_seconds": payload.ttl_seconds},
    )
    db.commit()
    db.refresh(code)
    return ActivationCodeIssueOut(
        id=code.id,
        code=raw,
        user_id=code.user_id,
        expires_at=code.expires_at,
        max_attempts=code.max_attempts,
    )


@router.get("/admin/activation-codes", response_model=list[ActivationCodeOut])
def list_activation_codes(
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("admin"))],
    limit: int = Query(100, ge=1, le=500),
    include_consumed: bool = Query(default=False),
) -> list[ActivationCodeOut]:
    """admin 查看本租户激活码（不含明文；已消费码默认隐藏）。"""
    from app.models import ActivationCode as ActivationCodeModel
    from app.services.activation import _is_expired

    query = select(ActivationCodeModel).where(
        ActivationCodeModel.tenant_id == principal.tenant_id,
    )
    if not include_consumed:
        query = query.where(ActivationCodeModel.used_at.is_(None))
    rows = db.scalars(query.order_by(ActivationCodeModel.created_at.desc()).limit(limit)).all()
    return [
        ActivationCodeOut(
            id=r.id,
            user_id=r.user_id,
            created_by=r.created_by,
            created_at=r.created_at,
            expires_at=r.expires_at,
            used_at=r.used_at,
            revoked_at=r.revoked_at,
            attempt_count=r.attempt_count,
            max_attempts=r.max_attempts,
            revoked=r.revoked_at is not None,
            used=r.used_at is not None,
            expired=_is_expired(r.expires_at),
        )
        for r in rows
    ]


@router.post("/admin/activation-codes/{code_id}/revoke")
def revoke_activation_code(
    code_id: str,
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("admin"))],
) -> dict[str, Any]:
    """admin 吊销激活码（幂等：已吊销重复调用返回同一结果）。"""
    from app.models import ActivationCode as ActivationCodeModel

    row = db.get(ActivationCodeModel, code_id)
    if not row or row.tenant_id != principal.tenant_id:
        raise HTTPException(status_code=404, detail="activation code not found")
    if row.used_at is not None:
        raise HTTPException(status_code=409, detail="activation code already consumed")
    if row.revoked_at is None:
        row.revoked_at = datetime.now(UTC)
        append_audit(
            db,
            tenant_id=principal.tenant_id,
            actor_type=principal.role,
            actor_id=principal.subject,
            action="activation_code.revoke",
            object_type="activation_code",
            object_id=row.id,
        )
        db.commit()
    return {"id": row.id, "revoked": True}


@router.post("/skills/batch-retire")
def batch_retire_skills(
    payload: SkillBatchRetire,
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("admin"))],
) -> dict[str, Any]:
    """admin 批量回滚 Skill（仅本租户受影响；已 retired 幂等跳过）。"""
    from app.models import Skill

    rows = db.scalars(
        select(Skill).where(
            Skill.tenant_id == principal.tenant_id,
            Skill.id.in_(payload.skill_ids),
        )
    ).all()
    retired_count = 0
    retired_ids: list[str] = []
    previous_statuses: dict[str, str] = {}
    for row in rows:
        if row.status == "retired":
            continue
        previous_statuses[row.id] = row.status
        row.status = "retired"
        row.updated_at = datetime.now(UTC)
        retired_ids.append(row.id)
        retired_count += 1
    db.flush()
    if retired_count > 0:
        append_audit(
            db,
            tenant_id=principal.tenant_id,
            actor_type=principal.role,
            actor_id=principal.subject,
            action="skill.batch_retire",
            object_type="skill",
            object_id=",".join(retired_ids),
            metadata={
                "skill_ids": retired_ids,
                "previous_statuses": previous_statuses,
                "new_status": "retired",
            },
        )
    db.commit()
    return {"retired": retired_count}
