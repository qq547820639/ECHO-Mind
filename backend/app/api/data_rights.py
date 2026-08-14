"""数据主体请求（DSR）路由（v0.6.1 拆分）：分类删除矩阵 + 撤回服务。

不变量（hardening 需求十八）：
- DSR delete 分类矩阵删除派生产物/主动内容；
- 依法保留：consents（同意证据链）/ risk_signals（危机处置 append-only）/
  escalations（危机处置，关联 User 去标识）/ audit_events（哈希链）/ dsr 记录；
- revoke_service 会把 User 置为 withdrawal_pending（服务端撤回语义）。
"""
from __future__ import annotations

import hashlib
import json
from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth import Principal, require_roles
from app.models import (
    AuditEvent,
    Checkin,
    Consent,
    DailyNarrative,
    DataSubjectRequest,
    DerivedFeature,
    EmergencyContact,
    Escalation,
    JournalEntry,
    PracticeCompletion,
    QuestionnaireResult,
    RiskSignal,
    SandboxRun,
    Skill,
    Tool,
    User,
    UserProfile,
)
from app.schemas import DataSubjectRequestComplete, DataSubjectRequestCreate
from app.services.audit import append_audit

from app.api.deps import DB, PRINCIPAL, ensure_user, require_write_role
from datetime import datetime

router = APIRouter(prefix="/v1")


#: DSR delete 矩阵：删除的派生/主动内容表（按 category -> model 映射）。
#: 值为异构的 SQLAlchemy 模型类（均含 tenant_id/user_id 列，但无公共声明基类），
#: 故值类型用 Any 表达——运行时由 Session.query 按各模型真实映射解析。
DSR_DELETE_MODELS: dict[str, Any] = {
    "derived_features": DerivedFeature,
    "daily_narratives": DailyNarrative,
    "user_profiles": UserProfile,
    "checkins": Checkin,
    "journal_entries": JournalEntry,
    "questionnaire_results": QuestionnaireResult,
    "practice_completions": PracticeCompletion,
    "emergency_contacts": EmergencyContact,
    "tools": Tool,
    "skills": Skill,
    "sandbox_runs": SandboxRun,
}

#: 依法保留（append-only / 危机处置 / 同意证据链 / 数据权利合规），不删除。
DSR_RETAINED_REASONS: dict[str, str] = {
    "consents": "同意/撤回证据链合规审计留存（法定期限）",
    "risk_signals": "危机处置记录 append-only，法定留存",
    "audit_events": "审计哈希链依法永久保留，不可删除",
    "escalations": "危机处置记录法定留存；关联用户已去标识",
    "data_subject_requests": "数据权利请求合规审计记录",
}


def _execute_dsr_delete(db: Session, tenant_id: str, user_id: str) -> dict[str, dict]:
    """执行 DSR delete 分类矩阵删除，返回 per_category 摘要。"""
    per_category: dict[str, dict] = {}
    for category, model in DSR_DELETE_MODELS.items():
        deleted = db.query(model).filter(
            model.tenant_id == tenant_id,
            model.user_id == user_id,
        ).delete(synchronize_session=False)
        per_category[category] = {"action": "delete", "count": deleted, "retained_reason": None}

    consent_count = db.query(Consent).filter(
        Consent.tenant_id == tenant_id,
        Consent.user_id == user_id,
    ).count()
    per_category["consents"] = {
        "action": "retain",
        "count": consent_count,
        "retained_reason": DSR_RETAINED_REASONS["consents"],
    }
    risk_count = db.query(RiskSignal).filter(
        RiskSignal.tenant_id == tenant_id,
        RiskSignal.user_id == user_id,
    ).count()
    per_category["risk_signals"] = {
        "action": "retain",
        "count": risk_count,
        "retained_reason": DSR_RETAINED_REASONS["risk_signals"],
    }
    escalation_count = db.query(Escalation).filter(
        Escalation.tenant_id == tenant_id,
        Escalation.user_id == user_id,
    ).count()
    per_category["escalations"] = {
        "action": "retain",
        "count": escalation_count,
        "retained_reason": DSR_RETAINED_REASONS["escalations"],
    }
    user = db.get(User, user_id)
    if user is not None and user.tenant_id == tenant_id:
        salt = f"{tenant_id}:{user_id}"
        user.external_ref = "dsr_" + hashlib.sha256(salt.encode("utf-8")).hexdigest()[:32]
        user.city = None
        user.timezone = "UTC"

    audit_count = db.query(AuditEvent).filter(
        AuditEvent.tenant_id == tenant_id,
        AuditEvent.actor_id == user_id,
    ).count()
    per_category["audit_events"] = {
        "action": "retain",
        "count": audit_count,
        "retained_reason": DSR_RETAINED_REASONS["audit_events"],
    }
    dsr_count = db.query(DataSubjectRequest).filter(
        DataSubjectRequest.tenant_id == tenant_id,
        DataSubjectRequest.user_id == user_id,
    ).count()
    per_category["data_subject_requests"] = {
        "action": "retain",
        "count": dsr_count,
        "retained_reason": DSR_RETAINED_REASONS["data_subject_requests"],
    }
    return per_category


@router.post("/data-subject-requests")
def create_dsr(payload: DataSubjectRequestCreate, db: DB, principal: PRINCIPAL) -> dict:
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
) -> list[dict[str, str | datetime | None]]:
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
) -> dict:
    row = db.get(DataSubjectRequest, request_id)
    if not row or row.tenant_id != principal.tenant_id:
        raise HTTPException(status_code=404, detail="not found")

    # 幂等：已完成的请求直接返回既有结果，不重复删除/审计
    if row.status == "completed" and row.result_summary:
        try:
            stored = json.loads(row.result_summary)
        except (TypeError, ValueError):
            stored = {"per_category": {}, "summary": row.result_summary}
        return {
            "id": row.id,
            "status": row.status,
            "completed_at": row.completed_at,
            "result_summary": stored,
            "idempotent_replay": True,
        }

    per_category: dict[str, dict] = {}
    if row.request_type == "delete":
        per_category = _execute_dsr_delete(db, principal.tenant_id, row.user_id)
    row.status = "completed"
    row.completed_at = __import__("datetime").datetime.now(__import__("datetime").UTC)
    if row.request_type == "delete":
        summary_text = "按数据分类矩阵执行：派生产物/主动录入已删除；审计与危机处置记录依法保留。"
    else:
        summary_text = f"请求类型 {row.request_type} 已标记完成。"
    summary = {
        "per_category": per_category,
        "summary": summary_text,
    }
    row.result_summary = json.dumps(summary, ensure_ascii=False)
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="dsr.complete", object_type="data_subject_request", object_id=row.id,
                 metadata={"request_type": row.request_type, "per_category": per_category})
    db.commit()
    return {
        "id": row.id,
        "status": row.status,
        "completed_at": row.completed_at,
        "result_summary": summary,
    }
