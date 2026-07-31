from __future__ import annotations
from datetime import date, datetime, timezone
from typing import Any
from uuid import uuid4

from sqlalchemy import Boolean, Date, DateTime, ForeignKey, Integer, JSON, String, Text, UniqueConstraint
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
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (UniqueConstraint("tenant_id", "event_id", name="uq_df_tenant_event"),)


class DailyNarrative(Base):
    __tablename__ = "daily_narratives"
    id: Mapped[str] = mapped_column(String(80), primary_key=True, default=lambda: new_id("dn"))
    tenant_id: Mapped[str] = mapped_column(String(80), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    date: Mapped[date] = mapped_column(Date, nullable=False)
    events: Mapped[list[dict[str, Any]]] = mapped_column(JSON, default=list)
    mood_hint: Mapped[str] = mapped_column(String(120), default="平稳")
    gaps: Mapped[list[str]] = mapped_column(JSON, default=list)
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
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    __table_args__ = (
        UniqueConstraint("tenant_id", "user_id", "name", "version", name="uq_skill_tenant_user_name_version"),
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
