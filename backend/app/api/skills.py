"""Skill 领域路由（v0.6.1 拆分）：治理状态机 + signed-only 下发 + 幂等完成上报。

不变量（hardening 需求十八）：
- 仅 signed Skill 下发（DELIVERABLE_SKILL_STATUSES）；
- action_type 白名单外一律不下发、不执行（fail closed）；
- completion 幂等（tenant+event_id）；不承载任何可执行内容；
- 治理转换不能跳级、不能逆转。
"""
from __future__ import annotations

from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth import Principal, require_roles
from app.database import get_db
from app.models import Skill, SkillCompletion, UserProfile
from app.schemas import (
    ACTION_TYPE_WHITELIST,
    SkillCompletionCreate,
    SkillOut,
    SkillTransition,
)
from app.services.audit import append_audit
from app.services.sandbox.sanitizer import sanitize_skill, sanitize_skills

from app.api.deps import DB, PRINCIPAL, ensure_user, require_feature_flag

router = APIRouter(prefix="/v1")

# Skill 治理状态机：仅允许相邻正向转换，不能跳级、不能逆转
SKILL_TRANSITIONS: dict[str, str] = {
    "draft": "reviewed",
    "reviewed": "signed",
    "signed": "retired",
}

# 用户可下发的 Skill 状态（v0.6：仅 signed；reviewed 仅供专业人员预览，不下发普通用户）
DELIVERABLE_SKILL_STATUSES: tuple[str, ...] = ("signed",)


def _cold_start_stage(observation_days: int) -> str:
    """根据 observation_days 推荐冷启动文案阶段 key（0/1-3/4-7/7+ 天）。"""
    if observation_days <= 0:
        return "stage_0"
    if observation_days <= 3:
        return "stage_1_3"
    if observation_days <= 7:
        return "stage_4_7"
    return "stage_7_plus"


def _get_owned_skill(db: Session, principal: Principal, skill_id: str) -> Skill:
    """读取 Skill 并校验租户归属；跨租户返回 404。"""
    row = db.get(Skill, skill_id)
    if not row or row.tenant_id != principal.tenant_id:
        raise HTTPException(status_code=404, detail="skill not found")
    return row


@router.get("/skills")
def list_skills(
    db: DB,
    principal: PRINCIPAL,
    _flag: Annotated[None, Depends(require_feature_flag("skills_delivery_enabled"))],
    user_id: str | None = Query(default=None),
):
    """用户拉取已 signed 的 Skill 列表（脱敏后下发；draft/reviewed/retired 不下发）。"""
    target_user_id = user_id or principal.subject
    ensure_user(db, principal, target_user_id)
    rows = db.scalars(
        select(Skill).where(
            Skill.tenant_id == principal.tenant_id,
            Skill.user_id == target_user_id,
            Skill.status.in_(DELIVERABLE_SKILL_STATUSES),
        ).order_by(Skill.updated_at.desc())
    ).all()
    # PRD 契约点 4：action_type 白名单外一律不下发、不执行
    deliverable = [row for row in rows if row.action_type in ACTION_TYPE_WHITELIST]
    sanitized = sanitize_skills(list(deliverable))
    cold_start_hint: str | None = None
    observation_days = 0
    if not sanitized:
        profile = db.scalar(
            select(UserProfile).where(
                UserProfile.tenant_id == principal.tenant_id,
                UserProfile.user_id == target_user_id,
            )
        )
        if profile and profile.traits:
            observation_days = int(profile.traits.get("observation_days", 0) or 0)
        cold_start_hint = _cold_start_stage(observation_days)
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="skill.list",
        object_type="skill",
        object_id=target_user_id,
        metadata={"count": len(sanitized), "user_id": target_user_id},
    )
    db.commit()
    return {
        "skills": sanitized,
        "cold_start_hint": cold_start_hint,
        "observation_days": observation_days,
    }


@router.get("/skills/{skill_id}", response_model=SkillOut)
def get_skill(
    skill_id: str,
    db: DB,
    principal: PRINCIPAL,
    _flag: Annotated[None, Depends(require_feature_flag("skills_delivery_enabled"))],
):
    """用户拉取单个 Skill 详情（脱敏后下发；signed-only + action_type 白名单）。"""
    row = _get_owned_skill(db, principal, skill_id)
    ensure_user(db, principal, row.user_id)
    if row.status not in DELIVERABLE_SKILL_STATUSES:
        raise HTTPException(status_code=404, detail="skill not deliverable")
    if row.action_type not in ACTION_TYPE_WHITELIST:
        raise HTTPException(status_code=404, detail="skill not deliverable")
    sanitized = sanitize_skill(row)
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="skill.detail",
        object_type="skill",
        object_id=row.id,
        metadata={"status": row.status},
    )
    db.commit()
    return sanitized


@router.post("/skills/completions")
def create_skill_completion(
    payload: SkillCompletionCreate,
    db: DB,
    principal: PRINCIPAL,
    _flag: Annotated[None, Depends(require_feature_flag("skills_delivery_enabled"))],
):
    """Skill 执行完成/停止上报（幂等：同 tenant+event_id 返回同一记录）。

    - 校验 skill 属于当前用户且 status=='signed'
    - audit action=skill.completion
    - 服务端只记录执行状态，不承载可执行内容
    """
    ensure_user(db, principal, payload.user_id)
    existing = db.scalar(select(SkillCompletion).where(
        SkillCompletion.tenant_id == principal.tenant_id,
        SkillCompletion.event_id == payload.event_id,
    ))
    if existing:
        return {"id": existing.id, "idempotent_replay": True}
    skill = db.get(Skill, payload.skill_id)
    if not skill or skill.tenant_id != principal.tenant_id or skill.user_id != payload.user_id:
        raise HTTPException(status_code=404, detail="skill not found")
    if skill.status != "signed":
        raise HTTPException(status_code=409, detail="skill completion requires a signed skill")
    row = SkillCompletion(
        event_id=payload.event_id,
        tenant_id=principal.tenant_id,
        user_id=payload.user_id,
        skill_id=payload.skill_id,
        status=payload.status,
        duration_seconds=payload.duration_seconds,
        client_time=payload.client_time,
    )
    db.add(row)
    db.flush()
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="skill.completion",
        object_type="skill_completion",
        object_id=row.id,
        metadata={"skill_id": payload.skill_id, "status": payload.status},
    )
    db.commit()
    return {"id": row.id, "idempotent_replay": False}


@router.post("/skills/{skill_id}/transition")
def transition_skill(
    skill_id: str,
    payload: SkillTransition,
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("admin", "professional"))],
):
    """Skill 治理状态机转换：draft→reviewed→signed→retired（不能跳级、不能逆转）。

    转入 signed 时（PRD 契约点 5）必须提供 policy_version 与 review_evidence（缺失 → 422）；
    content_hash 缺失视为无效内容（不可签署）。
    """
    row = _get_owned_skill(db, principal, skill_id)
    old_status = row.status
    new_status = payload.new_status
    expected = SKILL_TRANSITIONS.get(old_status)
    if expected is None or expected != new_status:
        raise HTTPException(
            status_code=409,
            detail=f"invalid transition: {old_status} -> {new_status}",
        )
    if new_status == "signed":
        if not payload.policy_version or payload.review_evidence is None:
            raise HTTPException(
                status_code=422,
                detail="policy_version and review_evidence are required to sign a skill",
            )
        if not row.content_hash:
            raise HTTPException(status_code=422, detail="skill content missing; cannot sign")
        row.signed_by = f"{principal.role}:{principal.subject}"
        row.signed_at = datetime.now(UTC)
        row.policy_version = payload.policy_version
        row.review_evidence = payload.review_evidence
        row.revision = (row.revision or 1)
    row.status = new_status
    row.updated_at = datetime.now(UTC)
    db.flush()
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="skill.transition",
        object_type="skill",
        object_id=row.id,
        metadata={"old_status": old_status, "new_status": new_status},
    )
    db.commit()
    db.refresh(row)
    return {"id": row.id, "status": row.status, "previous_status": old_status}
