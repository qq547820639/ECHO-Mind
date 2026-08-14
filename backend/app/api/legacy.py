"""Legacy 兼容路由（v0.6.1 拆分）：410 停用存根 + append-only 不可变守卫。

- 主动录入（签到/日记/量表/练习/文本安全检查）写入口已停用（T12.1）：
  保留路由定义与认证链，有效身份返回 410 Gone 而非 404；
  只读（GET /journals）保留历史查询。
- append-only 资源（escalations / risk-signals / audit-events）DELETE/PATCH
  一律 405 并记录 security.immutable_mutation_attempt 审计。
- 退役周期：以上 410/405 存根在 v0.8 移除（届时一并清理对应 legacy 模型列）。
"""
from __future__ import annotations
from typing import Any

from fastapi import APIRouter, HTTPException, Query
from sqlalchemy import select

from app.models import JournalEntry
from app.schemas import (
    CheckinCreate,
    FreeTextSafetyCheck,
    JournalCreate,
    JournalRevise,
    PracticeCompletionCreate,
    QuestionnaireCreate,
)
from app.services.audit import append_audit
from app.services.crypto import decrypt_text

from sqlalchemy.orm import Session

from app.auth import Principal
from app.api.deps import (
    DB,
    PRINCIPAL,
    ensure_user,
    require_psych_content_role,
)

router = APIRouter(prefix="/v1")


@router.post("/checkins")
def create_checkin(payload: CheckinCreate, db: DB, principal: PRINCIPAL) -> dict[str, Any]:
    # T12.1 主动签到录入入口已停用：保留路由定义与认证链，有效身份返回 410 Gone。
    ensure_user(db, principal, payload.user_id)
    raise HTTPException(status_code=410, detail="此录入入口已停用，请使用被动感知范式。")


@router.post("/journals")
def create_journal(payload: JournalCreate, db: DB, principal: PRINCIPAL) -> dict[str, Any]:
    # T12.1 主动日记录入入口已停用：保留认证链，有效身份返回 410 Gone。
    require_psych_content_role(db, principal, user_id=payload.user_id)
    ensure_user(db, principal, payload.user_id)
    raise HTTPException(status_code=410, detail="此录入入口已停用，请使用被动感知范式。")


@router.post("/journals/{logical_id}/revisions")
def revise_journal(logical_id: str, payload: JournalRevise, db: DB, principal: PRINCIPAL) -> dict[str, Any]:
    # T12.1 日记修订入口已停用：保留路由定义与认证链，有效身份返回 410 Gone。
    raise HTTPException(status_code=410, detail="此录入入口已停用，请使用被动感知范式。")


@router.delete("/journals/{logical_id}")
def delete_journal(logical_id: str, db: DB, principal: PRINCIPAL) -> dict[str, Any]:
    # T12.1 日记删除入口已停用：保留路由定义与认证链，有效身份返回 410 Gone。
    raise HTTPException(status_code=410, detail="此录入入口已停用，请使用被动感知范式。")


@router.get("/journals")
def list_journals(user_id: str, db: DB, principal: PRINCIPAL, limit: int = Query(50, ge=1, le=200)) -> list[dict[str, object]]:
    """历史日记只读查询（T12.1 后保留；写入口已 410）。"""
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
def safety_check(payload: FreeTextSafetyCheck, db: DB, principal: PRINCIPAL) -> dict[str, Any]:
    # T12.1 主动文本安全检查入口已停用：保留认证链，有效身份返回 410。
    require_psych_content_role(db, principal, user_id=payload.user_id)
    ensure_user(db, principal, payload.user_id)
    raise HTTPException(status_code=410, detail="此录入入口已停用，请使用被动感知范式。")


@router.post("/questionnaires/{code}/responses")
def questionnaire(code: str, payload: QuestionnaireCreate, db: DB, principal: PRINCIPAL) -> dict[str, Any]:
    # T12.1 问卷录入入口已停用：保留认证链，有效身份返回 410。
    require_psych_content_role(db, principal, user_id=payload.user_id)
    ensure_user(db, principal, payload.user_id)
    raise HTTPException(status_code=410, detail="此录入入口已停用，请使用被动感知范式。")


@router.post("/practices/completions")
def practice_completion(payload: PracticeCompletionCreate, db: DB, principal: PRINCIPAL) -> dict[str, Any]:
    # T12.1 练习完成录入入口已停用：保留认证链，有效身份返回 410。
    ensure_user(db, principal, payload.user_id)
    raise HTTPException(status_code=410, detail="此录入入口已停用，请使用被动感知范式。")


def reject_immutable_mutation(
    db: Session,
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
    )
    db.commit()
    raise HTTPException(
        status_code=405,
        detail=f"{object_type} records are append-only; {method} is not allowed",
    )


@router.delete("/escalations/{escalation_id}")
def delete_escalation(escalation_id: str, db: DB, principal: PRINCIPAL) -> None:
    reject_immutable_mutation(db, principal, "DELETE", "escalation", escalation_id)


@router.patch("/escalations/{escalation_id}")
def patch_escalation(escalation_id: str, db: DB, principal: PRINCIPAL) -> None:
    reject_immutable_mutation(db, principal, "PATCH", "escalation", escalation_id)


@router.delete("/risk-signals/{signal_id}")
def delete_risk_signal(signal_id: str, db: DB, principal: PRINCIPAL) -> None:
    reject_immutable_mutation(db, principal, "DELETE", "risk_signal", signal_id)


@router.patch("/risk-signals/{signal_id}")
def patch_risk_signal(signal_id: str, db: DB, principal: PRINCIPAL) -> None:
    reject_immutable_mutation(db, principal, "PATCH", "risk_signal", signal_id)


@router.delete("/audit/events/{event_id}")
def delete_audit_event(event_id: str, db: DB, principal: PRINCIPAL) -> None:
    reject_immutable_mutation(db, principal, "DELETE", "audit_event", event_id)


@router.patch("/audit/events/{event_id}")
def patch_audit_event(event_id: str, db: DB, principal: PRINCIPAL) -> None:
    reject_immutable_mutation(db, principal, "PATCH", "audit_event", event_id)
