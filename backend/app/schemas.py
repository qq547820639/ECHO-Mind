from datetime import date, datetime
from typing import Any, Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.services.schema_registry import (
    PASSIVE_CORE_V1,
    SCHEMA_REGISTRY,
    validate_schema_source,
)

#: 默认派生特征 schema（Phase 5：由 schema registry 驱动；mic_opt 用 mic-feature-v1）
FEATURE_SCHEMA_VERSION = PASSIVE_CORE_V1


class TenantCreate(BaseModel):
    name: str = Field(min_length=2, max_length=200)


class UserCreate(BaseModel):
    external_ref: str = Field(min_length=2, max_length=160)
    age_band: Literal["18_plus"] = "18_plus"
    timezone: str = "Asia/Shanghai"
    city: str | None = Field(default=None, max_length=120)


class ConsentCreate(BaseModel):
    user_id: str
    consent_type: Literal["psychological_data", "emergency_contact", "voice_features", "research", "passive_sensing"]
    version: str
    granted: bool
    evidence_hash: str = Field(min_length=16, max_length=128)


class VoiceFeaturesConsentCreate(BaseModel):
    """麦克风派生特征专用 consent 入参。

    复用 ConsentCreate 语义但固定 consent_type=voice_features
    与 version=voice-features-consent-2026.07，避免调用方误填其他类型。
    """

    user_id: str
    granted: bool
    evidence_hash: str = Field(min_length=16, max_length=128)
    consent_type: Literal["voice_features"] = "voice_features"
    version: str = "voice-features-consent-2026.07"


class L0ScreeningCreate(BaseModel):
    event_id: str = Field(min_length=8, max_length=80)
    user_id: str
    current_danger: bool = False
    prior_attempt_or_admission: bool = False
    psychosis_or_mania: bool = False
    substance_impairment: bool = False
    has_professional_support: bool = False


class EmergencyContactCreate(BaseModel):
    user_id: str
    name: str = Field(min_length=1, max_length=100)
    phone: str = Field(min_length=5, max_length=40)
    relationship: str = Field(min_length=1, max_length=80)


class OnboardingVerifyIn(BaseModel):
    """激活码交换入参（v0.6 final）。

    仅接受机构分发的激活码/邀请码；不接收 device_id 等内部标识之外的字段。
    code 最小 8 位（如 `XXXX-XXXX-XXXX`），最长 80。
    """
    code: str = Field(min_length=8, max_length=80)


class OnboardingVerifyOut(BaseModel):
    """激活码交换输出契约（v0.6 final）。

    仅返回端侧所需字段；不暴露 tenant_id / role / external_ref / bootstrap 等
    内部字段（JWT 载荷内部字段对用户透明）。
    """
    user_id: str
    access_token: str
    consent_versions: dict[str, str] = Field(default_factory=dict)
    l0_decision: str | None = None
    restricted: bool = False


# ===== v0.6.1 ActivationCode（机构激活码，取代 external_ref 隐式激活语义） =====


class ActivationCodeCreate(BaseModel):
    """admin 签发激活码入参。

    - user_id 可空：空 = 待绑定（兑换时绑定到兑换者用户）；非空 = 预绑定用户。
    - ttl_seconds 可空：缺省用 settings.activation_code_ttl_seconds。
    - max_attempts：该码最大失败尝试次数（防爆破）。
    """
    user_id: str | None = None
    ttl_seconds: int | None = Field(default=None, ge=60, le=365 * 24 * 3600)
    max_attempts: int = Field(default=5, ge=1, le=20)


class ActivationCodeIssueOut(BaseModel):
    """签发响应：明文码仅此一次返回（数据库只存哈希）。"""
    id: str
    code: str
    user_id: str | None = None
    expires_at: datetime | None = None
    max_attempts: int = 5


class ActivationCodeOut(BaseModel):
    """激活码管理视图（不含明文）。"""
    id: str
    user_id: str | None = None
    created_by: str
    created_at: datetime
    expires_at: datetime | None = None
    used_at: datetime | None = None
    revoked_at: datetime | None = None
    attempt_count: int = 0
    max_attempts: int = 5
    revoked: bool = False
    used: bool = False
    expired: bool = False


# legacy: v0.8 removal target — CheckinCreate 仅历史兼容（POST /v1/checkins 410 存根）。
class CheckinCreate(BaseModel):
    event_id: str = Field(min_length=8, max_length=80)
    user_id: str
    mood: int = Field(ge=1, le=5)
    stress: int = Field(ge=1, le=5)
    energy: int = Field(ge=1, le=5)
    sleep_recovery: int = Field(ge=1, le=5)
    event_flag: bool = False
    help_requested: bool = False
    note: str | None = Field(default=None, max_length=4000)
    client_time: datetime
    device_timezone: str = Field(min_length=1, max_length=80)


class JournalCreate(BaseModel):
    event_id: str = Field(min_length=8, max_length=80)
    user_id: str
    logical_id: str = Field(min_length=8, max_length=80)
    body: str = Field(min_length=1, max_length=8000)
    event_tags: list[str] = Field(default_factory=list, max_length=20)
    client_time: datetime


class JournalRevise(BaseModel):
    event_id: str = Field(min_length=8, max_length=80)
    body: str = Field(min_length=1, max_length=8000)
    event_tags: list[str] = Field(default_factory=list, max_length=20)
    client_time: datetime


# legacy: v0.8 removal target — QuestionnaireCreate 仅历史兼容（POST /v1/questionnaires/... 410 存根）。
class QuestionnaireCreate(BaseModel):
    event_id: str = Field(min_length=8, max_length=80)
    user_id: str
    version: str = "1.0"
    answers: list[int]

    @field_validator("answers")
    @classmethod
    def validate_answers(cls, values: list[int]) -> list[int]:
        if any(value < 0 or value > 3 for value in values):
            raise ValueError("answers must be in range 0..3")
        return values


# legacy: v0.8 removal target — PracticeCompletionCreate 仅历史兼容（POST /v1/practices/completions 410 存根）。
class PracticeCompletionCreate(BaseModel):
    event_id: str = Field(min_length=8, max_length=80)
    user_id: str
    practice_id: str = Field(min_length=2, max_length=80)
    content_version: str = Field(min_length=1, max_length=40)
    status: Literal["started", "completed", "stopped"]
    duration_seconds: int = Field(default=0, ge=0, le=86400)
    client_time: datetime


class FreeTextSafetyCheck(BaseModel):
    user_id: str
    text: str = Field(min_length=1, max_length=8000)
    session_id: str | None = None


class EscalationCreate(BaseModel):
    event_id: str = Field(min_length=8, max_length=80)
    user_id: str
    level: Literal["L2", "L3"] = "L3"
    trigger: str = Field(min_length=2, max_length=80)
    evidence_summary: str = Field(min_length=2, max_length=2000)


class EscalationClose(BaseModel):
    # 全部字段可空提交，由 close 端点统一校验必填并指出缺失字段（422）。
    disposition: str | None = Field(default=None, min_length=2, max_length=4000)
    contact_method: str | None = Field(default=None, min_length=2, max_length=80)
    contact_succeeded: bool | None = None
    safety_status: str | None = Field(default=None, min_length=2, max_length=200)
    emergency_contact_called: bool | None = None
    referred_12356: bool | None = None
    called_emergency_services: bool | None = None
    follow_up_plan: str | None = Field(default=None, min_length=2, max_length=4000)
    operator_signature: str | None = Field(default=None, min_length=2, max_length=120)


class EscalationReview(BaseModel):
    review_notes: str = Field(min_length=2, max_length=4000)


class DataSubjectRequestCreate(BaseModel):
    event_id: str = Field(min_length=8, max_length=80)
    user_id: str
    request_type: Literal["export", "delete", "revoke_service"]


class DataSubjectRequestComplete(BaseModel):
    result_summary: str | None = Field(default=None, max_length=4000)


#: 派生特征窗口内可声明的信号源集合（与 Android FeatureExtractor.sources_present 对齐）
SOURCES_PRESENT_VALUES = ("accel", "gyro", "screen", "notification", "app_activity", "mic_opt", "health")


class DerivedFeatureIn(BaseModel):
    """端侧派生特征契约：只接收摘要/向量，绝不携带原始传感 payload。"""

    # 隐私强约束：拒绝任何额外字段（如 audio_buffer / raw_samples / payload），
    # 防止端侧误传原始传感数据到云端。
    model_config = ConfigDict(extra="forbid")

    event_id: str = Field(min_length=8, max_length=80)
    user_id: str
    # Phase 5：schema registry 驱动；Literal 让 OpenAPI 表达枚举契约
    # （passive-core-v1 核心 22 维 / mic-feature-v1 麦克风外围）
    schema_version: Literal["passive-core-v1", "mic-feature-v1"] = PASSIVE_CORE_V1
    source: Literal["accel", "gyro", "screen", "notification", "app_activity", "health", "mic_opt"]
    window_start: datetime
    window_end: datetime
    summary: str = Field(max_length=4000)
    vector: list[float] = Field(default_factory=list, max_length=256)
    # v0.6：窗口内实际存在的信号源（供 gap_finder 覆盖度判断；可选，缺省空）
    sources_present: list[Literal["accel", "gyro", "screen", "notification", "app_activity", "mic_opt", "health"]] = (
        Field(default_factory=list, max_length=16)
    )

    @field_validator("schema_version")
    @classmethod
    def validate_schema_version(cls, value: str) -> str:
        if value not in SCHEMA_REGISTRY:
            raise ValueError(f"unsupported feature schema version: {value}")
        return value

    @model_validator(mode="after")
    def validate_schema_source_combo(self) -> "DerivedFeatureIn":
        """schema/source 组合必须匹配 registry（如 mic_opt 必须 mic-feature-v1）。

        不匹配 → 422 拒绝 + 遥测计数 ingest_reject_schema_mismatch（隐私安全：
        不记录特征内容本身）。
        """
        error = validate_schema_source(self.schema_version, self.source)
        if error is not None:
            from app.services.telemetry import count_event

            count_event("ingest_reject_schema_mismatch")
            raise ValueError(error)
        return self


#: Skill action_type 白名单（PRD 契约点 4）：白名单外一律不校验通过、不下发、不执行。
ACTION_TYPE_WHITELIST: tuple[str, ...] = (
    "guided_steps",
    "reflection_prompt",
    "breathing",
    "journaling",
    "checklist",
)


class SkillOut(BaseModel):
    """技能包输出契约（PRD 契约点 4/5）。"""
    id: str
    user_id: str
    name: str
    version: int
    trigger_conditions: list[dict[str, Any]] = Field(default_factory=list)
    guardrails: list[str] = Field(default_factory=list)
    steps: list[dict[str, Any]] = Field(default_factory=list)
    status: str
    # 执行契约字段（契约点 4）
    action_type: str = "guided_steps"
    estimated_duration: int | None = None
    completion_schema: dict[str, Any] | None = None
    safety_constraints: list[str] | None = None
    # 治理字段（契约点 5）
    signed_by: str | None = None
    signed_at: datetime | None = None
    policy_version: str | None = None
    review_evidence: dict[str, Any] | None = None
    revision: int = 1
    supersedes_skill_id: str | None = None
    created_at: datetime
    updated_at: datetime

    @field_validator("action_type")
    @classmethod
    def validate_action_type(cls, value: str) -> str:
        if value not in ACTION_TYPE_WHITELIST:
            raise ValueError(f"action_type must be one of {ACTION_TYPE_WHITELIST}")
        return value


class SkillCompletionCreate(BaseModel):
    """Skill 执行完成/停止上报入参（v0.6）。"""
    event_id: str = Field(min_length=8, max_length=80)
    user_id: str
    skill_id: str
    status: Literal["started", "completed", "stopped"]
    duration_seconds: int = Field(default=0, ge=0, le=86400)
    client_time: datetime


class SkillCompletionOut(BaseModel):
    """Skill 执行完成/停止上报输出。"""
    id: str
    user_id: str
    skill_id: str
    status: str
    duration_seconds: int = 0
    client_time: datetime
    created_at: datetime


class ToolOut(BaseModel):
    """工具调用契约输出。"""
    id: str
    user_id: str
    name: str
    description: str = ""
    parameters_schema: dict[str, Any] = Field(default_factory=dict)
    returns_schema: dict[str, Any] = Field(default_factory=dict)
    status: str
    created_at: datetime


class SandboxRunOut(BaseModel):
    """沙箱运行记录输出。"""
    id: str
    user_id: str
    run_date: date
    status: str
    gaps_found: list[str] = Field(default_factory=list)
    tools_generated: int = 0
    tools_validated: int = 0
    skills_inducted: int = 0
    error_message: str | None = None
    started_at: datetime | None = None
    completed_at: datetime | None = None


class SandboxRunCreate(BaseModel):
    """触发沙箱运行的入参。run_date 缺省为当天（UTC）。"""
    user_id: str
    run_date: date | None = None


class SkillTransition(BaseModel):
    """Skill 治理状态机转换入参。new_status 仅允许 reviewed/signed/retired。

    转入 signed 时必须提供 policy_version 与 review_evidence（契约点 5 重签校验）。
    """
    new_status: Literal["reviewed", "signed", "retired"]
    policy_version: str | None = Field(default=None, min_length=1, max_length=80)
    review_evidence: dict[str, Any] | None = None


class TenantPortraitOut(BaseModel):
    """机构去标识群体画像输出契约。

    仅返回聚合统计，不含单个用户 ID/特征；小桶（<5）已合并到 "other"（且 other<5 时不输出）。
    suppression 标记各敏感维度的抑制状态（"ok" 或 "suppressed"）。
    """
    # legacy: v0.8 removal target — 情绪维度已废弃（PRD 契约点 2），字段保留恒空 + suppressed
    # 仅为 OpenAPI 兼容历史消费者；不再有任何写入路径。
    mood_distribution: dict[str, int] = Field(default_factory=dict)
    observation_stats: dict[str, float] = Field(default_factory=dict)
    active_users_7d: int = 0
    escalation_metrics: dict[str, int] = Field(default_factory=dict)
    skill_count: dict[str, int] = Field(default_factory=dict)
    suppression: dict[str, str] = Field(default_factory=dict)


class TenantFlagUpdate(BaseModel):
    """P5 灰度回滚：admin 修改租户 feature flag 入参。

    flag_key 仅允许 passive_sensing_enabled / sandbox_enabled / skills_delivery_enabled。
    """
    flag_key: Literal["passive_sensing_enabled", "sandbox_enabled", "skills_delivery_enabled"]
    value: bool


class SkillBatchRetire(BaseModel):
    """P5 灰度回滚：批量回滚 Skill 入参。"""
    skill_ids: list[str] = Field(min_length=1, max_length=200)


# ===== Portrait Core（v0.7，Milestone D/E）=====


class PortraitOut(BaseModel):
    """当日画像输出契约。

    - headline 为最多 3 个两字标签；
    - dimensions 取值禁止 GOOD/BAD/HEALTHY/NORMAL/ABNORMAL（产品契约）；
    - timezone_used 为生成画像时实际使用的用户时区。
    """

    date: date
    status: str
    confidence: str
    baseline_days: int = 0
    baseline_version: str | None = None
    baseline_snapshot_digest: str | None = None
    headline: list[str] = Field(default_factory=list)
    summary: str = ""
    dimensions: dict[str, Any] = Field(default_factory=dict)
    coverage: dict[str, Any] = Field(default_factory=dict)
    facts: list[dict[str, Any]] = Field(default_factory=list)
    timezone_used: str = "Asia/Shanghai"


class PortraitListOut(BaseModel):
    """最近 N 天画像列表（按 local_date 升序）。"""

    user_id: str
    days: int
    portraits: list[PortraitOut] = Field(default_factory=list)


class BaselineStatusOut(BaseModel):
    """基线状态只读视图（GET 无写副作用）。"""

    status: str
    baseline_days: int = 0
    baseline_version: str | None = None
    window_start: date | None = None
    window_end: date | None = None
    bucket_usage: str = "all_days"
    today_coverage: float = 0.0


class PortraitRebuildIn(BaseModel):
    """显式重建当日画像入参。local_date 缺省为今天（用户本地日期）。"""

    user_id: str
    local_date: date | None = None


class MePortraitRebuildIn(BaseModel):
    """当前用户（principal.subject）显式重建画像入参。

    - 不接收 user_id：认证用户由 JWT principal.subject 确定（/v1/me/* 契约）；
    - local_date 可选，缺省为今天（用户本地日期）。
    """

    local_date: date | None = None
