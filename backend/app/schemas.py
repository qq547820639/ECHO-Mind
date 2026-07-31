from datetime import date, datetime
from typing import Any, Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator

FEATURE_SCHEMA_VERSION = "feat-v1"


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
    result_summary: str = Field(min_length=2, max_length=4000)


class DerivedFeatureIn(BaseModel):
    """端侧派生特征契约：只接收摘要/向量，绝不携带原始传感 payload。"""

    # 隐私强约束：拒绝任何额外字段（如 audio_buffer / raw_samples / payload），
    # 防止端侧误传原始传感数据到云端。
    model_config = ConfigDict(extra="forbid")

    event_id: str = Field(min_length=8, max_length=80)
    user_id: str
    schema_version: str = FEATURE_SCHEMA_VERSION
    source: Literal["accel", "gyro", "screen", "notification", "app_activity", "health", "mic_opt"]
    window_start: datetime
    window_end: datetime
    summary: str = Field(max_length=4000)
    vector: list[float] = Field(default_factory=list, max_length=256)

    @field_validator("schema_version")
    @classmethod
    def validate_schema_version(cls, value: str) -> str:
        if value != FEATURE_SCHEMA_VERSION:
            raise ValueError("unsupported feature schema version")
        return value


class SkillOut(BaseModel):
    """技能包输出契约。"""
    id: str
    user_id: str
    name: str
    version: int
    trigger_conditions: list[dict[str, Any]] = Field(default_factory=list)
    guardrails: list[str] = Field(default_factory=list)
    steps: list[dict[str, Any]] = Field(default_factory=list)
    status: str
    created_at: datetime
    updated_at: datetime


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
    """Skill 治理状态机转换入参。new_status 仅允许 reviewed/signed/retired。"""
    new_status: Literal["reviewed", "signed", "retired"]


class TenantPortraitOut(BaseModel):
    """机构去标识群体画像输出契约。

    仅返回聚合统计，不含单个用户 ID/特征；小桶（<5）已合并到 "other"。
    """
    mood_distribution: dict[str, int] = Field(default_factory=dict)
    observation_stats: dict[str, float] = Field(default_factory=dict)
    active_users_7d: int = 0
    escalation_metrics: dict[str, int] = Field(default_factory=dict)
    skill_count: dict[str, int] = Field(default_factory=dict)


class TenantFlagUpdate(BaseModel):
    """P5 灰度回滚：admin 修改租户 feature flag 入参。

    flag_key 仅允许 passive_sensing_enabled / sandbox_enabled / skills_delivery_enabled。
    """
    flag_key: Literal["passive_sensing_enabled", "sandbox_enabled", "skills_delivery_enabled"]
    value: bool


class SkillBatchRetire(BaseModel):
    """P5 灰度回滚：批量回滚 Skill 入参。"""
    skill_ids: list[str] = Field(min_length=1, max_length=200)
