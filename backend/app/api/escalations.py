"""Escalation（人工支持/危机处置）路由（v0.6.1 拆分）。

状态机：open → acknowledged → taken_over → closed → reviewed。
不变量（hardening 需求十八）：
- 未收到服务端 ACK 前不得向用户宣称"人工已收到"（user-status 只暴露
  delivery_confirmed / human_acknowledged，后者仅由显式 ack/takeover 决定）；
- 升级链路（escalation_level / notified_*）不向普通用户暴露；
- append-only：DELETE/PATCH 一律 405（见 legacy.py 的 immutable 守卫）。

v0.6.1（hardening 需求十四）：列表 cursor 分页 + 状态/负责人过滤 +
metrics 聚合下推 SQL（避免 dashboard 全表加载到 Python）。

v0.6.2：metrics 延迟改为真实 P50/P95（Python 线性插值，兼容 percentile_cont
语义）；状态分布/总数仍 SQL 聚合。
"""
from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from sqlalchemy import func, select

from app.auth import Principal, require_roles
from app.config import get_settings
from app.models import EmergencyContact, Escalation, User
from app.schemas import EscalationClose, EscalationCreate, EscalationReview
from app.services.audit import append_audit
from app.services.escalation import scan_sla_breaches

from app.api.deps import (
    DB,
    PRINCIPAL,
    RISK_TYPE_LABELS,
    ensure_user,
    forbid,
    get_escalation,
    open_escalation,
    require_active_subscription,
    require_step_up,
    require_write_role,
)

router = APIRouter(prefix="/v1")

WORKBENCH_ROLES = ("on_call", "professional", "admin", "auditor", "quality_reviewer", "security_auditor")

# 个案复核证据链仅对复核类角色开放；on_call 在此映射 spec 中的 clinical_lead。
CASE_REVIEW_ROLES = ("professional", "on_call", "quality_reviewer")


def _age_seconds(opened_at: datetime, now: datetime) -> float:
    aware = opened_at if opened_at.tzinfo else opened_at.replace(tzinfo=UTC)
    return (now - aware).total_seconds()


@router.post("/escalations")
def create_escalation(payload: EscalationCreate, db: DB, principal: PRINCIPAL):
    """用户/服务端创建升级事件（幂等：同 tenant+event_id 返回既有记录）。

    用户侧调用链（v0.6.1 客户端闭环）：Android Outbox → POST /v1/escalations；
    delivery_confirmed_at 在服务端接收时即写入（送达确认，不等于人工已收到）。
    """
    require_write_role(db, principal, object_type="escalation")
    user = ensure_user(db, principal, payload.user_id)
    # v0.7 订阅门禁：显式到期 → 402（人工支持属订阅能力；本地模式端侧已先行拦截）
    require_active_subscription(db, user)
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
    principal: Annotated[Principal, Depends(require_roles(*WORKBENCH_ROLES))],
    response: Response,
    status: str | None = Query(default=None),
    level: str | None = Query(default=None),
    assigned_to: str | None = Query(default=None),
    limit: int = Query(50, ge=1, le=200),
    cursor: str | None = Query(default=None, description="opaque cursor（X-Next-Cursor 响应头回填）"),
):
    """机构工作台队列：cursor 分页 + 过滤（v0.6.1）。

    **契约保持**：响应体仍为数组（与 v0.6 一致）；分页元数据放在响应头
    ``X-Next-Cursor``（还有更多页时非空）与 ``X-Total-Filtered``。
    不变量：本端点仅 workbench 角色；evidence_summary 仅持有 step-up 声明时可见。
    """
    settings = get_settings()
    query = select(Escalation).where(Escalation.tenant_id == principal.tenant_id)
    if status:
        query = query.where(Escalation.status == status)
    if level:
        query = query.where(Escalation.level == level)
    if assigned_to:
        query = query.where(Escalation.assigned_to == assigned_to)
    if cursor:
        # keyset 分页：(opened_at, id) 字典序，稳定且避免 offset 深翻页
        cursor_opened, cursor_id = cursor.split("_", 1)
        query = query.where(
            (Escalation.opened_at < cursor_opened) |
            ((Escalation.opened_at == cursor_opened) & (Escalation.id < cursor_id))
        )
    rows = db.scalars(
        query.order_by(Escalation.opened_at.desc(), Escalation.id.desc()).limit(limit + 1)
    ).all()
    has_more = len(rows) > limit
    rows = rows[:limit]
    now = datetime.now(UTC)
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
            if contact.active:
                contact_status[contact.user_id] = "可用"
            else:
                contact_status.setdefault(contact.user_id, "不可用")
    last = rows[-1] if rows else None
    # v0.7 修复：X-Total-Filtered 必须与过滤条件对齐（此前报未过滤总数，分页语义误导）。
    count_query = select(func.count()).select_from(Escalation).where(Escalation.tenant_id == principal.tenant_id)
    if status:
        count_query = count_query.where(Escalation.status == status)
    if level:
        count_query = count_query.where(Escalation.level == level)
    if assigned_to:
        count_query = count_query.where(Escalation.assigned_to == assigned_to)
    total_filtered = db.scalar(count_query) or 0
    # 分页元数据经响应头透出（保持 body 数组契约不变）
    resp = response
    resp.headers["X-Next-Cursor"] = f"{last.opened_at}_{last.id}" if (has_more and last is not None) else ""
    resp.headers["X-Total-Filtered"] = str(total_filtered)
    return [{
        "id": x.id,
            "user_id": x.user_id,
            "level": x.level,
            "status": x.status,
            "trigger": x.trigger,
            "risk_type": RISK_TYPE_LABELS.get(x.trigger, x.trigger),
            "evidence_summary": x.evidence_summary if principal.step_up else None,
            "opened_at": x.opened_at,
            "waiting_seconds": int(_age_seconds(x.opened_at, now)),
            "ack_at": x.ack_at,
            "takeover_at": x.takeover_at,
            "closed_at": x.closed_at,
            "reviewed_at": x.reviewed_at,
            "assigned_to": x.assigned_to,
            "disposition": x.disposition,
            "escalation_level": x.escalation_level,
            "second_duty_notified": x.notified_l1_at is not None,
            "notified_l1_at": x.notified_l1_at,
            "notified_l2_at": x.notified_l2_at,
            "chain_broken": x.chain_broken_at is not None,
            "delivery_confirmed": x.delivery_confirmed_at is not None,
            "delivery_confirmed_at": x.delivery_confirmed_at,
            "user_city": users[x.user_id].city if x.user_id in users else None,
            "emergency_contact_status": contact_status.get(x.user_id, "未登记"),
            "ack_sla_breached": x.ack_at is None and _age_seconds(x.opened_at, now) > settings.ack_sla_seconds,
            "takeover_sla_breached": x.takeover_at is None and _age_seconds(x.opened_at, now) > settings.takeover_sla_seconds,
        } for x in rows]


@router.get("/escalations/metrics")
def escalation_metrics(
    db: DB,
    principal: Annotated[Principal, Depends(require_roles(
        "professional", "admin", "auditor", "quality_reviewer", "security_auditor",
    ))],
):
    """聚合指标（v0.6.2 起延迟为真实百分位）。

    - 状态分布 / 总数在数据库聚合；
    - ACK / 接管延迟按秒列表在 Python 计算真实 P50/P95（线性插值，
      与 SQL percentile_cont 语义一致）；超大表改 percentile_cont 下推；
    - 兼容旧响应字段（total / open / closed / ack_p50 / takeover_p50），
      新增 ack_p95 / takeover_p95（只增不改）。
    """
    settings = get_settings()
    total = db.scalar(select(func.count()).select_from(Escalation)
                      .where(Escalation.tenant_id == principal.tenant_id)) or 0
    by_status: dict[str, int] = {}
    for status, count in db.execute(
        select(Escalation.status, func.count()).where(
            Escalation.tenant_id == principal.tenant_id,
        ).group_by(Escalation.status)
    ).all():
        by_status[status] = count

    # 真实百分位延迟（v0.6.2 修复）：原实现以 AVG 近似 P50，精度不可靠。
    # 现在按 ACK / 接管 latency 秒列表在 Python 计算真实 P50/P95（线性插值，
    # 与 SQL percentile_cont 语义一致）。escalations 数据量可控，全量载入可行；
    # 若未来表增长到超大，应改为 percentile_cont 下推（见 TODO）。
    ack_rows = db.scalars(select(Escalation).where(
        Escalation.tenant_id == principal.tenant_id,
        Escalation.ack_at.is_not(None),
    )).all()
    take_rows = db.scalars(select(Escalation).where(
        Escalation.tenant_id == principal.tenant_id,
        Escalation.takeover_at.is_not(None),
    )).all()

    def _latencies(rows: Sequence[Escalation], end_attr: str) -> list[float]:
        out: list[float] = []
        for row in rows:
            opened = row.opened_at
            ended = getattr(row, end_attr)
            if opened is None or ended is None:
                continue
            # SQLite 返回 naive datetime，统一按 UTC 解释。
            aware_o = opened if opened.tzinfo else opened.replace(tzinfo=UTC)
            aware_e = ended if ended.tzinfo else ended.replace(tzinfo=UTC)
            out.append((aware_e - aware_o).total_seconds())
        return out

    def _percentile(sorted_vals: list[float], p: float) -> float | None:
        """线性插值百分位（与 percentile_cont 一致）；空列表返回 None。"""
        if not sorted_vals:
            return None
        k = (len(sorted_vals) - 1) * p
        lo = math.floor(k)
        hi = math.ceil(k)
        if lo == hi:
            return sorted_vals[lo]
        frac = k - lo
        return sorted_vals[lo] * (1 - frac) + sorted_vals[hi] * frac

    def _round_opt(value: float | None) -> int | None:
        return round(value) if value is not None else None

    ack_secs = sorted(_latencies(ack_rows, "ack_at"))
    take_secs = sorted(_latencies(take_rows, "takeover_at"))
    ack_p50 = _round_opt(_percentile(ack_secs, 0.50))
    ack_p95 = _round_opt(_percentile(ack_secs, 0.95))
    take_p50 = _round_opt(_percentile(take_secs, 0.50))
    take_p95 = _round_opt(_percentile(take_secs, 0.95))

    return {
        "total": total,
        "by_status": by_status,
        "open": sum(by_status.get(s, 0) for s in ("open", "acknowledged", "taken_over")),
        "closed": sum(by_status.get(s, 0) for s in ("closed", "reviewed")),
        # TODO(超大表)：数据量过大时改 percentile_cont 下推，保持响应契约不变。
        "ack_p50_seconds": ack_p50,
        "ack_p95_seconds": ack_p95,
        "takeover_p50_seconds": take_p50,
        "takeover_p95_seconds": take_p95,
        "ack_sla_seconds": settings.ack_sla_seconds,
        "takeover_sla_seconds": settings.takeover_sla_seconds,
    }


@router.post("/escalations/sla-scan")
def sla_scan(
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("on_call", "admin"))],
):
    """运维定时触发的 SLA 扫描：推进未确认红色事件的自动升级链路（幂等）。"""
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
    principal: Annotated[Principal, Depends(require_roles(*WORKBENCH_ROLES))],
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


@router.get("/escalations/{escalation_id}/case-review")
def escalation_case_review(escalation_id: str, db: DB, principal: PRINCIPAL):
    """个案复核证据链视图（仅复核类角色 + step-up）。"""
    row = get_escalation(db, principal, escalation_id)
    if principal.role not in CASE_REVIEW_ROLES:
        forbid(db, principal, action="authz.psych_content_denied", object_type="escalation",
               object_id=row.id, detail="role cannot access case review evidence")
    require_step_up(db, principal, object_type="escalation", object_id=row.id)
    tenant_id = principal.tenant_id
    user_id = row.user_id

    from app.models import (
        AuditEvent,
        Checkin,
        JournalEntry,
        QuestionnaireResult,
        RiskSignal,
    )
    from app.services.safety import RULE_PACK_VERSION, resolve_rule_ids
    from app.services.trends import build_trend
    from app.services.crypto import decrypt_text

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
        row.ack_at = datetime.now(UTC)
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
    now = datetime.now(UTC)
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
    row.closed_at = row.closed_at or datetime.now(UTC)
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
    row.reviewed_at = row.reviewed_at or datetime.now(UTC)
    row.review_notes = payload.review_notes
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="escalation.review", object_type="escalation", object_id=row.id)
    db.commit()
    return {"id": row.id, "status": row.status, "reviewed_at": row.reviewed_at}
