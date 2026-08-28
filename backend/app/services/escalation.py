"""SLA auto-escalation ladder for unacknowledged red escalations.

State machine per escalation (all timeouts measured from opened_at):

* age > ack_sla_seconds and still unacked      -> escalation_level=1, notify 第二值班人
* age > takeover_sla_seconds and still unacked -> escalation_level=2, notify 机构负责人
* age > org_lead_sla_seconds and still unacked -> chain_broken_at set (机构链路失效)

Invariant: notification is never takeover. This module only writes lifecycle
notification fields (escalation_level / notified_*_at / chain_broken_at) and
audit events; ack_at / takeover_at / status change exclusively through the
explicit ack/takeover endpoints. The scan is idempotent: each tier is guarded
by its own timestamp and fires at most once.
"""
from datetime import datetime, timedelta, timezone

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.models import Escalation, OnboardingScreening, RiskSignal
from app.services.audit import append_audit
from typing import Any

CLOSED_STATUSES = ("closed", "reviewed")

#: ERA 42 安全复核：支持请求创建频率上限（每用户每小时）。
#: 豁免**只**授予服务端可验证的危机证据（见 resolve_rate_limit_exemption）；
#: 客户端提交的 trigger 仅作展示标签，不再具备自证豁免的能力。
#: 幂等重放（同 event_id）不计入窗口。
ESCALATION_CREATE_LIMIT_MAX = 20
ESCALATION_CREATE_LIMIT_WINDOW = timedelta(hours=1)

#: 危机信号标签（队列展示用 + 伪造检测用）。
#: 这些标签历史上曾直接授予限流豁免；2026-08-28 收口后仅作**声明**，
#: 是否豁免由 resolve_rate_limit_exemption 依服务端证据裁定。
#: 注：`help_requested`（Me → 请求人工支持按钮）不在其列——它是用户自助动作，
#: 表达"我想要人工支持"，不是服务端可验证的危机证据，恒计入频控窗口。
CRISIS_SIGNAL_TRIGGERS = frozenset({
    "l0_current_danger",
    "text_red_signal",
    "journal_red_signal",
    "phq9_item9_positive",
})

#: 服务端可验证豁免信号的回溯窗口（信号必须"新鲜"才构成当前危机上下文）。
ESCALATION_EXEMPTION_LOOKBACK = timedelta(minutes=30)

#: 豁免原因常量（写审计，供值班/SOC 区分真伪危机）。
EXEMPTION_L0_CURRENT_DANGER = "l0_current_danger"
EXEMPTION_SERVER_RED_SIGNAL = "server_red_signal"


def resolve_rate_limit_exemption(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    now: datetime | None = None,
) -> str | None:
    """判定该用户当前是否持有**服务端可验证**的危机证据（豁免频控的依据）。

    设计约束（2026-08-28 P0-3 收口）：
    - 证据必须来自服务端自己写入的行（L0 准入筛查 / 服务端红色风险信号），
      客户端提交的字符串一律不作为证据；
    - 证据必须"新鲜"（ESCALATION_EXEMPTION_LOOKBACK 内），避免陈年筛查
      被当作永久免限流通行证；
    - 返回 None 表示该请求必须计入频控窗口。

    Returns:
        豁免原因字符串（写入审计），或 None。
    """
    moment = now or datetime.now(timezone.utc)
    cutoff = moment - ESCALATION_EXEMPTION_LOOKBACK

    l0_hit = db.scalar(
        select(OnboardingScreening.id).where(
            OnboardingScreening.tenant_id == tenant_id,
            OnboardingScreening.user_id == user_id,
            OnboardingScreening.current_danger.is_(True),
            OnboardingScreening.created_at >= cutoff,
        )
    )
    if l0_hit:
        return EXEMPTION_L0_CURRENT_DANGER

    red_hit = db.scalar(
        select(RiskSignal.id).where(
            RiskSignal.tenant_id == tenant_id,
            RiskSignal.user_id == user_id,
            RiskSignal.severity == "red",
            RiskSignal.created_at >= cutoff,
        )
    )
    if red_hit:
        return EXEMPTION_SERVER_RED_SIGNAL

    return None


def count_recent_escalations(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    now: datetime | None = None,
) -> int:
    """窗口内该用户已创建的支持请求数（与激活码防爆破同款 SQL 窗口模式）。"""
    cutoff = (now or datetime.now(timezone.utc)) - ESCALATION_CREATE_LIMIT_WINDOW
    return db.scalar(
        select(func.count()).select_from(Escalation).where(
            Escalation.tenant_id == tenant_id,
            Escalation.user_id == user_id,
            Escalation.opened_at >= cutoff,
        )
    ) or 0


def _age_seconds(opened_at: datetime, now: datetime) -> float:
    aware = opened_at if opened_at.tzinfo else opened_at.replace(tzinfo=timezone.utc)
    return (now - aware).total_seconds()


def scan_sla_breaches(
    db: Session,
    *,
    tenant_id: str | None = None,
    now: datetime | None = None,
    actor_id: str = "sla_scanner",
) -> dict[str, Any]:
    """Advance the escalation ladder for unacked red escalations.

    Returns a summary with the ids that changed tier in this run. Mutations are
    flushed by the caller (endpoint commits; tests may commit or roll back).
    """
    settings = get_settings()
    now = now or datetime.now(timezone.utc)
    query = select(Escalation).where(
        Escalation.level == "L3",
        Escalation.ack_at.is_(None),
        Escalation.status.notin_(CLOSED_STATUSES),
    )
    if tenant_id is not None:
        query = query.where(Escalation.tenant_id == tenant_id)
    rows = db.scalars(query).all()
    scanned = len(rows)
    notified_second_duty: list[str] = []
    notified_org_lead: list[str] = []
    chain_broken: list[str] = []

    def record(action: str, row: Escalation, metadata: dict[str, Any]) -> None:
        append_audit(
            db,
            tenant_id=row.tenant_id,
            actor_type="system",
            actor_id=actor_id,
            action=action,
            object_type="escalation",
            object_id=row.id,
            metadata=metadata,
        )
        # 同一次扫描可能追加多条审计事件；逐条落库保证哈希链前后衔接。
        db.flush()

    for row in rows:
        age = _age_seconds(row.opened_at, now)
        if age <= settings.ack_sla_seconds:
            continue
        if row.notified_l1_at is None:
            row.escalation_level = 1
            row.notified_l1_at = now
            record("notify.second_duty", row, {"escalation_level": 1, "age_seconds": int(age)})
            notified_second_duty.append(row.id)
        if age > settings.takeover_sla_seconds and row.notified_l2_at is None:
            row.escalation_level = 2
            row.notified_l2_at = now
            record("notify.org_lead", row, {"escalation_level": 2, "age_seconds": int(age)})
            notified_org_lead.append(row.id)
        if age > settings.org_lead_sla_seconds and row.chain_broken_at is None:
            row.chain_broken_at = now
            record("escalation.chain_broken", row, {"age_seconds": int(age)})
            chain_broken.append(row.id)
    return {
        "scanned": scanned,
        "notified_second_duty": notified_second_duty,
        "notified_org_lead": notified_org_lead,
        "chain_broken": chain_broken,
    }
