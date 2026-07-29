from datetime import datetime
from typing import Literal
from pydantic import BaseModel, Field, field_validator


class TenantCreate(BaseModel):
    name: str = Field(min_length=2, max_length=200)


class UserCreate(BaseModel):
    external_ref: str = Field(min_length=2, max_length=160)
    age_band: Literal["18_plus"] = "18_plus"
    timezone: str = "Asia/Shanghai"
    city: str | None = Field(default=None, max_length=120)


class ConsentCreate(BaseModel):
    user_id: str
    consent_type: Literal["psychological_data", "emergency_contact", "voice_features", "research"]
    version: str
    granted: bool
    evidence_hash: str = Field(min_length=16, max_length=128)


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
