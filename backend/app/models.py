from __future__ import annotations
from datetime import date, datetime, timezone
from typing import Any
from uuid import uuid4

from sqlalchemy import Boolean, Date, DateTime, Float, ForeignKey, Index, Integer, JSON, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column
from app.database import Base


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex}"


class Tenant(Base):
    __tablename__ = "tenants"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("t"))
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    # P5 灰度回滚：租户级 feature flag，控制被动感知范式的启停。
    # 默认三开关全开，admin 可通过 PUT /v1/tenant/flags 调整用于灰度回滚。
    feature_flags: Mapped[dict[str, Any]] = mapped_column(
        JSON,
        default=lambda: {
            "passive_sensing_enabled": True,
            "sandbox_enabled": True,
            "skills_delivery_enabled": True,
        },
        nullable=False,
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("u"))
    tenant_id: Mapped[str] = mapped_column(ForeignKey("tenants.id"), index=True)
    external_ref: Mapped[str] = mapped_column(String(160), nullable=False)
    age_band: Mapped[str] = mapped_column(String(40), default="18_plus")
    timezone: Mapped[str] = mapped_column(String(80), default="Asia/Shanghai")
    # 用户所在城市（可选登记），用于机构工作台调度属地资源。
    city: Mapped[str | None] = mapped_column(String(120), nullable=True)
    status: Mapped[str] = mapped_column(String(40), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (UniqueConstraint("tenant_id", "external_ref", name="uq_user_external"),)


class Consent(Base):
    __tablename__ = "consents"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("c"))
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    consent_type: Mapped[str] = mapped_column(String(80), nullable=False)
    version: Mapped[str] = mapped_column(String(80), nullable=False)
    granted: Mapped[bool] = mapped_column(Boolean, nullable=False)
    granted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    evidence_hash: Mapped[str] = mapped_column(String(128), nullable=False)


class OnboardingScreening(Base):
    __tablename__ = "onboarding_screenings"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("l0"))
    event_id: Mapped[str] = mapped_column(String(80), nullable=False)
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    current_danger: Mapped[bool] = mapped_column(Boolean, default=False)
    prior_attempt_or_admission: Mapped[bool] = mapped_column(Boolean, default=False)
    psychosis_or_mania: Mapped[bool] = mapped_column(Boolean, default=False)
    substance_impairment: Mapped[bool] = mapped_column(Boolean, default=False)
    has_professional_support: Mapped[bool] = mapped_column(Boolean, default=False)
    decision: Mapped[str] = mapped_column(String(40), default="eligible")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (UniqueConstraint("tenant_id", "event_id", name="uq_l0_tenant_event"),)


class EmergencyContact(Base):
    __tablename__ = "emergency_contacts"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("ec"))
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    name_ciphertext: Mapped[str] = mapped_column(Text)
    phone_ciphertext: Mapped[str] = mapped_column(Text)
    relationship: Mapped[str] = mapped_column(String(80))
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


# legacy: v0.8 removal target — Checkin 仅历史只读兼容（case-review 直接证据、
# DSR delete 矩阵、内部 build_trend 仍读）；写入口 POST /v1/checkins 维持 410 存根。
class Checkin(Base):
    __tablename__ = "checkins"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("chk"))
    event_id: Mapped[str] = mapped_column(String(80), nullable=False)
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    mood: Mapped[int] = mapped_column(Integer)
    stress: Mapped[int] = mapped_column(Integer)
    energy: Mapped[int] = mapped_column(Integer)
    sleep_recovery: Mapped[int] = mapped_column(Integer)
    event_flag: Mapped[bool] = mapped_column(Boolean, default=False)
    help_requested: Mapped[bool] = mapped_column(Boolean, default=False)
    note_ciphertext: Mapped[str | None] = mapped_column(Text, nullable=True)
    client_time: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    device_timezone: Mapped[str] = mapped_column(String(80))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (UniqueConstraint("tenant_id", "event_id", name="uq_checkin_tenant_event"),)


class JournalEntry(Base):
    __tablename__ = "journal_entries"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("jnl"))
    event_id: Mapped[str] = mapped_column(String(80), nullable=False)
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    logical_id: Mapped[str] = mapped_column(String(80), index=True)
    revision: Mapped[int] = mapped_column(Integer, default=1)
    body_ciphertext: Mapped[str | None] = mapped_column(Text, nullable=True)
    event_tags: Mapped[list[str]] = mapped_column(JSON, default=list)
    deleted: Mapped[bool] = mapped_column(Boolean, default=False)
    client_time: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    supersedes_id: Mapped[str | None] = mapped_column(String(80), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (
        UniqueConstraint("tenant_id", "event_id", name="uq_journal_tenant_event"),
        UniqueConstraint("tenant_id", "logical_id", "revision", name="uq_journal_revision"),
    )


# legacy: v0.8 removal target — QuestionnaireResult 仅历史只读兼容（case-review、DSR delete 矩阵）；
# 写入口 POST /v1/questionnaires/... 维持 410 存根。
class QuestionnaireResult(Base):
    __tablename__ = "questionnaire_results"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("qr"))
    event_id: Mapped[str] = mapped_column(String(80), nullable=False)
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    instrument: Mapped[str] = mapped_column(String(30))
    version: Mapped[str] = mapped_column(String(30))
    answers: Mapped[list[int]] = mapped_column(JSON)
    score: Mapped[int] = mapped_column(Integer)
    interpretation: Mapped[str] = mapped_column(String(120))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (UniqueConstraint("tenant_id", "event_id", name="uq_questionnaire_tenant_event"),)


# legacy: v0.8 removal target — PracticeCompletion 仅历史只读兼容（case-review、DSR delete 矩阵）；
# 写入口 POST /v1/practices/completions 维持 410 存根。
class PracticeCompletion(Base):
    __tablename__ = "practice_completions"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("pc"))
    event_id: Mapped[str] = mapped_column(String(80), nullable=False)
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    practice_id: Mapped[str] = mapped_column(String(80))
    content_version: Mapped[str] = mapped_column(String(40))
    status: Mapped[str] = mapped_column(String(40))
    duration_seconds: Mapped[int] = mapped_column(Integer, default=0)
    client_time: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (UniqueConstraint("tenant_id", "event_id", name="uq_practice_tenant_event"),)


class ActivationCode(Base):
    """机构激活码（v0.6.1）：生产级激活凭证，不再让 User.external_ref 隐式承担。

    - code_hash 为 SHA-256 明文码，全库唯一（跨租户不可歧义），数据库不存明文；
    - 一次性消费：used_at 置位即不可再兑换（并发下由原子 UPDATE 保证只成功一次）；
    - TTL：expires_at 过期即失效；
    - 防爆破：attempt_count / max_attempts + IP/device/code 维度 rate limit（services.activation）；
    - 不通过响应差异泄露 tenant/user 存在性（统一 404/403 文案）。
    """

    __tablename__ = "activation_codes"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("ac"))
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    # 激活码签发给哪个用户（可为空 = 待绑定）；兑换成功后写入。
    user_id: Mapped[str | None] = mapped_column(ForeignKey("users.id"), nullable=True, index=True)
    # 明文码的 SHA-256（服务端加盐派生，见 services/activation.hash_code）。
    code_hash: Mapped[str] = mapped_column(String(128), nullable=False, unique=True)
    created_by: Mapped[str] = mapped_column(String(120), default="system")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    attempt_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    max_attempts: Mapped[int] = mapped_column(Integer, default=5, nullable=False)
    __table_args__ = (
        Index("ix_activation_codes_tenant_created", "tenant_id", "created_at"),
    )


class ActivationAttempt(Base):
    """激活码兑换失败审计（v0.6.1）：IP/device/code 维度 rate limit 的数据来源。

    - 每次失败兑换写一行（actor_ip / device_id / code_hash），成功兑换也写一行（result=success）
      —— 提供最小必要安全审计；
    - 不记录明文码，不记录任何心理内容；
    - 查询窗口由 services/activation 的 lookback 常量决定（默认 15 分钟）。
    """

    __tablename__ = "activation_attempts"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("aa"))
    tenant_id: Mapped[str | None] = mapped_column(String(80), nullable=True, index=True)
    code_hash: Mapped[str] = mapped_column(String(128), index=True)
    actor_ip: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    device_id: Mapped[str | None] = mapped_column(String(128), nullable=True, index=True)
    result: Mapped[str] = mapped_column(String(32), default="failure")  # failure / success
    attempted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, index=True)
    __table_args__ = (
        Index("ix_activation_attempts_lookup", "code_hash", "actor_ip", "attempted_at"),
    )


class RiskSignal(Base):
    __tablename__ = "risk_signals"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("risk"))
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    source: Mapped[str] = mapped_column(String(60))
    severity: Mapped[str] = mapped_column(String(20))
    rule_pack_version: Mapped[str] = mapped_column(String(40))
    evidence_refs: Mapped[list[str]] = mapped_column(JSON, default=list)
    labels: Mapped[list[str]] = mapped_column(JSON, default=list)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Escalation(Base):
    __tablename__ = "escalations"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("esc"))
    event_id: Mapped[str] = mapped_column(String(80), nullable=False)
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    level: Mapped[str] = mapped_column(String(20), default="L3")
    status: Mapped[str] = mapped_column(String(30), default="open", index=True)
    trigger: Mapped[str] = mapped_column(String(80))
    evidence_summary: Mapped[str] = mapped_column(Text)
    opened_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    ack_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    takeover_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    reviewed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # SLA 自动升级状态机（lifecycle 字段，允许更新；通知不等于接管）：
    # 0=第一值班人 1=第二值班人 2=机构负责人；notified_* 仅表示系统已通知对应层级。
    escalation_level: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    notified_l1_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    notified_l2_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    chain_broken_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # 服务端已确认接收事件的时间；与人工接管（ack/takeover）严格区分。
    delivery_confirmed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    assigned_to: Mapped[str | None] = mapped_column(String(120), nullable=True)
    disposition: Mapped[str | None] = mapped_column(Text, nullable=True)
    review_notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    # 接管处置记录（lifecycle 字段，close 时必填并一次性写入）：
    contact_method: Mapped[str | None] = mapped_column(String(80), nullable=True)
    contact_succeeded: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    safety_status: Mapped[str | None] = mapped_column(String(200), nullable=True)
    emergency_contact_called: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    referred_12356: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    called_emergency_services: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    follow_up_plan: Mapped[str | None] = mapped_column(Text, nullable=True)
    operator_signature: Mapped[str | None] = mapped_column(String(120), nullable=True)
    __table_args__ = (UniqueConstraint("tenant_id", "event_id", name="uq_escalation_tenant_event"),)


class DataSubjectRequest(Base):
    __tablename__ = "data_subject_requests"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("dsr"))
    event_id: Mapped[str] = mapped_column(String(80), nullable=False)
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    request_type: Mapped[str] = mapped_column(String(40))
    status: Mapped[str] = mapped_column(String(40), default="open")
    requested_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    result_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    __table_args__ = (UniqueConstraint("tenant_id", "event_id", name="uq_dsr_tenant_event"),)


class DerivedFeature(Base):
    """端侧派生特征（向量/摘要）；后端不存任何原始传感 payload。"""
    __tablename__ = "derived_features"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("df"))
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    event_id: Mapped[str] = mapped_column(String(80), nullable=False)
    schema_version: Mapped[str] = mapped_column(String(40))
    source: Mapped[str] = mapped_column(String(40))
    window_start: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    window_end: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    summary: Mapped[str] = mapped_column(Text)
    vector: Mapped[list[float]] = mapped_column(JSON, default=list)
    # v0.6：窗口内实际存在的信号源集合（accel/gyro/screen/notification/app_activity/mic_opt/health）
    sources_present: Mapped[list[str]] = mapped_column(JSON, default=list)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (
        UniqueConstraint("tenant_id", "event_id", name="uq_df_tenant_event"),
        # v0.6：窗口范围查询索引（migration 20260731_0003 建）
        Index(
            "ix_derived_features_tenant_user_window_start",
            "tenant_id",
            "user_id",
            "window_start",
        ),
    )


class DailyNarrative(Base):
    __tablename__ = "daily_narratives"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("dn"))
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    date: Mapped[date] = mapped_column(Date, nullable=False)
    events: Mapped[list[dict[str, Any]]] = mapped_column(JSON, default=list)
    # PRD 契约点 2：情绪语义字段废弃；列保留（可空）兼容历史数据，新写入恒为 None。
    mood_hint: Mapped[str | None] = mapped_column(String(120), nullable=True)
    gaps: Mapped[list[str]] = mapped_column(JSON, default=list)
    # v0.6：最近一次显式重建时间（可空）
    last_rebuilt_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (UniqueConstraint("tenant_id", "user_id", "date", name="uq_dn_tenant_user_date"),)


class UserProfile(Base):
    __tablename__ = "user_profiles"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("up"))
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    traits: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    version: Mapped[int] = mapped_column(Integer, default=1)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    # v0.6：最近一次显式重建时间（可空）
    rebuilt_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class AuditEvent(Base):
    __tablename__ = "audit_events"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("aud"))
    event_id: Mapped[str] = mapped_column(String(80), nullable=False)
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    actor_type: Mapped[str] = mapped_column(String(40))
    actor_id: Mapped[str] = mapped_column(String(120))
    action: Mapped[str] = mapped_column(String(120))
    object_type: Mapped[str] = mapped_column(String(80))
    object_id: Mapped[str] = mapped_column(String(120))
    request_id: Mapped[str | None] = mapped_column(String(120), nullable=True)
    metadata_json: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    previous_event_hash: Mapped[str | None] = mapped_column(String(128), nullable=True)
    event_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    __table_args__ = (UniqueConstraint("tenant_id", "event_id", name="uq_audit_tenant_event"),)


class Skill(Base):
    """自进化沙箱产物：技能包。draft/reviewed/signed/retired 生命周期。"""
    __tablename__ = "skills"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("sk"))
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    name: Mapped[str] = mapped_column(String(160), nullable=False)
    version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    trigger_conditions: Mapped[list[dict[str, Any]]] = mapped_column(JSON, default=list)
    guardrails: Mapped[list[str]] = mapped_column(JSON, default=list)
    steps: Mapped[list[dict[str, Any]]] = mapped_column(JSON, default=list)
    status: Mapped[str] = mapped_column(String(40), default="draft")
    content_hash: Mapped[str | None] = mapped_column(String(128), nullable=True)
    # v0.6 执行契约字段（PRD 契约点 4）：action_type 白名单 / 预计耗时 / 完成上报 schema / 安全边界
    action_type: Mapped[str] = mapped_column(String(40), default="guided_steps", nullable=False)
    estimated_duration_seconds: Mapped[int | None] = mapped_column(Integer, nullable=True)
    completion_schema: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    safety_constraints: Mapped[list[str] | None] = mapped_column(JSON, nullable=True)
    # v0.6 治理字段（PRD 契约点 5）：签署人/签署时间/策略版本/审核证据/修订号/取代的旧 Skill
    signed_by: Mapped[str | None] = mapped_column(String(120), nullable=True)
    signed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    policy_version: Mapped[str | None] = mapped_column(String(80), nullable=True)
    review_evidence: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    revision: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    supersedes_skill_id: Mapped[str | None] = mapped_column(String(80), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (
        UniqueConstraint("tenant_id", "user_id", "name", "version", name="uq_skill_tenant_user_name_version"),
    )


class SkillCompletion(Base):
    """Skill 执行完成/停止上报记录（v0.6）。

    幂等键：tenant_id + event_id；只记录用户主动的执行状态，不承载任何可执行内容。
    """
    __tablename__ = "skill_completions"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("sc"))
    event_id: Mapped[str] = mapped_column(String(80), nullable=False)
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    skill_id: Mapped[str] = mapped_column(ForeignKey("skills.id"), index=True)
    status: Mapped[str] = mapped_column(String(40), nullable=False)
    duration_seconds: Mapped[int] = mapped_column(Integer, default=0)
    client_time: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (
        UniqueConstraint("tenant_id", "event_id", name="uq_skill_completion_tenant_event"),
    )


class Tool(Base):
    """自进化沙箱产物：工具调用契约。可绑定到某个 Skill 也可独立。"""
    __tablename__ = "tools"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("tl"))
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    skill_id: Mapped[str | None] = mapped_column(ForeignKey("skills.id"), nullable=True)
    name: Mapped[str] = mapped_column(String(160), nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False, default="")
    parameters_schema: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    returns_schema: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    status: Mapped[str] = mapped_column(String(40), default="draft")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (
        UniqueConstraint("tenant_id", "user_id", "name", name="uq_tool_tenant_user_name"),
    )


class SandboxRun(Base):
    """自进化沙箱每日运行记录。状态机 pending→running→completed/failed。"""
    __tablename__ = "sandbox_runs"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("sr"))
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    run_date: Mapped[date] = mapped_column(Date, nullable=False)
    status: Mapped[str] = mapped_column(String(40), default="pending")
    gaps_found: Mapped[list[str]] = mapped_column(JSON, default=list)
    tools_generated: Mapped[int] = mapped_column(Integer, default=0)
    tools_validated: Mapped[int] = mapped_column(Integer, default=0)
    skills_inducted: Mapped[int] = mapped_column(Integer, default=0)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (
        UniqueConstraint("tenant_id", "user_id", "run_date", name="uq_sandbox_tenant_user_date"),
    )


class TenantSandboxSlot(Base):
    """沙箱租户执行槽（v0.6.1）：执行期原子并发配额。

    - running_count 通过行级原子 UPDATE（WHERE running_count < max）增减，
      多 worker 并发启动不可能突破 tenant max concurrency（消除 check-then-act race）；
    - heartbeat_at 由 acquire 刷新；acquire 时回收超过 [lease 超时] 的陈旧槽
      （worker crash / 进程整体死亡后可恢复配额）；
    - pending 数量与 running concurrency 分离：pending 由 sandbox_runs.status 表达，
      running 由本表计数。
    """

    __tablename__ = "sandbox_tenant_slots"
    tenant_id: Mapped[str] = mapped_column(String(80), primary_key=True)
    running_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    heartbeat_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)


class DailyBehaviorAggregate(Base):
    """被动感知当日行为聚合（Milestone B）。

    把用户本地日（以 User.timezone 换算日界线）内的派生特征窗口聚合为单一指标行。
    产品契约：本表绝不包含 mood/anxiety/stress/depression/loneliness/risk 字段
    （行为特征不得用于推断情绪/心理状态，PRD 契约点 2）。
    """

    __tablename__ = "daily_behavior_aggregates"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("dag"))
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    local_date: Mapped[date] = mapped_column(Date, nullable=False)
    timezone: Mapped[str] = mapped_column(String(80))
    coverage_score: Mapped[float] = mapped_column(Float, default=0.0)
    valid_window_count: Mapped[int] = mapped_column(Integer, default=0)
    expected_window_count: Mapped[int] = mapped_column(Integer, default=288)
    movement_index: Mapped[float | None] = mapped_column(Float, nullable=True)
    movement_variability: Mapped[float | None] = mapped_column(Float, nullable=True)
    screen_on_minutes: Mapped[float] = mapped_column(Float, default=0.0)
    screen_open_count: Mapped[int] = mapped_column(Integer, default=0)
    late_screen_minutes: Mapped[float] = mapped_column(Float, default=0.0)
    app_switch_count: Mapped[int] = mapped_column(Integer, default=0)
    active_start_minute: Mapped[int | None] = mapped_column(Integer, nullable=True)
    active_end_minute: Mapped[int | None] = mapped_column(Integer, nullable=True)
    notification_count: Mapped[int] = mapped_column(Integer, default=0)
    rhythm_regularity: Mapped[float | None] = mapped_column(Float, nullable=True)
    sources_present: Mapped[list[str]] = mapped_column(JSON, default=list)
    missing_sources: Mapped[list[str]] = mapped_column(JSON, default=list)
    schema_version: Mapped[str] = mapped_column(String(40), default="agg-v1")
    generated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    finalized_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    __table_args__ = (
        UniqueConstraint("tenant_id", "user_id", "local_date", name="uq_dag_tenant_user_date"),
    )


class PersonalBaseline(Base):
    """个人行为基线（Milestone C）：近 28 天有效日的 robust 统计分桶。

    - bucket：weekday（周一至周五）/ weekend（周六日）/ all_days（fallback 兜底）；
    - metrics: {metric: {median, mad, p10, p25, p75, p90, valid_days}}；
    - baseline_version 固定 "base-v1"，同 tenant+user+bucket 幂等 upsert 覆盖。
    """

    __tablename__ = "personal_baselines"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("pb"))
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    baseline_version: Mapped[str] = mapped_column(String(40), default="base-v1")
    bucket: Mapped[str] = mapped_column(String(20))
    window_start: Mapped[date] = mapped_column(Date)
    window_end: Mapped[date] = mapped_column(Date)
    valid_days: Mapped[int] = mapped_column(Integer, default=0)
    metrics: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    generated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (
        UniqueConstraint(
            "tenant_id", "user_id", "bucket", "baseline_version",
            name="uq_pb_tenant_user_bucket_version",
        ),
    )


class DailyPortrait(Base):
    """当日画像（Milestone D/E）：基线就绪后的 5 维度确定性画像。

    - status：WARMING_UP / EARLY_BASELINE / READY / PARTIAL_DATA / LOW_CONFIDENCE；
    - timezone 字段承载 timezone_used（生成画像时实际使用的用户时区）；
    - dimensions 取值禁止 GOOD/BAD/HEALTHY/NORMAL/ABNORMAL（产品契约）。
    """

    __tablename__ = "daily_portraits"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("dp"))
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    local_date: Mapped[date] = mapped_column(Date, nullable=False)
    timezone: Mapped[str] = mapped_column(String(80))
    status: Mapped[str] = mapped_column(String(30))
    confidence: Mapped[str] = mapped_column(String(10))
    baseline_start: Mapped[date | None] = mapped_column(Date, nullable=True)
    baseline_end: Mapped[date | None] = mapped_column(Date, nullable=True)
    baseline_valid_days: Mapped[int] = mapped_column(Integer, default=0)
    baseline_version: Mapped[str | None] = mapped_column(String(40), nullable=True)
    coverage: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    dimensions: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    highlights: Mapped[list[str]] = mapped_column(JSON, default=list)
    summary: Mapped[str] = mapped_column(Text, default="")
    facts: Mapped[list[dict[str, Any]]] = mapped_column(JSON, default=list)
    schema_version: Mapped[str] = mapped_column(String(40), default="portrait-v1")
    generated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    finalized_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    __table_args__ = (
        UniqueConstraint("tenant_id", "user_id", "local_date", name="uq_dp_tenant_user_date"),
    )
