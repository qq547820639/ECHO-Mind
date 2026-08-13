"""Phase 5 (C1) 测试：Feature Schema Registry + ingest schema/source 组合校验 + mic 过滤。

覆盖：
- 注册表内容断言（schema_id / vector_length / aggregate_eligible / field_indices）；
- ingest 未知 schema → 422；
- ingest schema/source 组合不匹配 → 422（mic_opt 必须 mic-feature-v1；
  screen 不能 mic-feature-v1）；
- mic_opt 可 ingest 存储（外围），但**不进入 DailyBehaviorAggregate**；
- 遥测计数 ingest_reject_schema_mismatch。
"""
from datetime import datetime, timedelta, timezone

from sqlalchemy import select

from app.database import SessionLocal
from app.models import DailyBehaviorAggregate, DerivedFeature
from app.services.schema_registry import (
    AGGREGATE_ELIGIBLE_SCHEMA_IDS,
    MIC_FEATURE_V1,
    PASSIVE_CORE_V1,
    SCHEMA_REGISTRY,
    is_aggregate_eligible,
    schema_for_source,
    validate_schema_source,
)
from app.services.telemetry import snapshot_counts


# ---- 注册表内容 ----

def test_registry_has_two_phase5_schemas():
    assert set(SCHEMA_REGISTRY) == {PASSIVE_CORE_V1, MIC_FEATURE_V1}

    passive = SCHEMA_REGISTRY[PASSIVE_CORE_V1]
    assert passive.vector_length == 22
    assert passive.aggregate_eligible is True
    assert "accel" in passive.sources and "health" in passive.sources
    assert passive.field_indices[7] == ("accel_magnitude_std", "m/s²")
    assert passive.field_indices[16] == ("screen_on_duration_ms", "ms")
    assert passive.field_indices[20] == ("app_switch_count", "count")

    mic = SCHEMA_REGISTRY[MIC_FEATURE_V1]
    assert mic.vector_length == 256  # 与 Android MicFeatureExtractor.VECTOR_DIM 对齐
    assert mic.aggregate_eligible is False
    assert mic.sources == ("mic_opt",)
    assert mic.privacy_classification == "voice_features"


def test_aggregate_eligible_schema_ids_only_passive_core():
    assert AGGREGATE_ELIGIBLE_SCHEMA_IDS == (PASSIVE_CORE_V1,)
    assert is_aggregate_eligible(PASSIVE_CORE_V1) is True
    assert is_aggregate_eligible(MIC_FEATURE_V1) is False
    assert is_aggregate_eligible("unknown-v1") is False


def test_schema_for_source_and_validation():
    assert schema_for_source("mic_opt").schema_id == MIC_FEATURE_V1
    assert schema_for_source("screen").schema_id == PASSIVE_CORE_V1
    assert validate_schema_source(PASSIVE_CORE_V1, "screen") is None
    assert validate_schema_source(MIC_FEATURE_V1, "mic_opt") is None
    # 不匹配组合
    assert validate_schema_source(MIC_FEATURE_V1, "screen") is not None
    assert validate_schema_source(PASSIVE_CORE_V1, "mic_opt") is not None
    # 未知 schema
    assert validate_schema_source("feat-v9", "screen") is not None


# ---- ingest 校验（API 层） ----

def _mic_payload(event_id: str, *, schema_version: str = MIC_FEATURE_V1) -> dict:
    start = datetime.now(timezone.utc).replace(hour=12, minute=0, second=0, microsecond=0)
    return {
        "event_id": event_id,
        "user_id": "u_demo",
        "schema_version": schema_version,
        "source": "mic_opt",
        "window_start": start.isoformat(),
        "window_end": (start + timedelta(minutes=30)).isoformat(),
        "summary": "音量平稳",
        "vector": [0.0] * 256,
    }


def test_ingest_unknown_schema_rejected(client, user_headers, passive_sensing_consent):
    payload = {
        "event_id": "evt_schema_unknown_0001",
        "user_id": "u_demo",
        "schema_version": "feat-v0",
        "source": "screen",
        "window_start": datetime.now(timezone.utc).isoformat(),
        "window_end": (datetime.now(timezone.utc) + timedelta(minutes=30)).isoformat(),
        "summary": "屏幕使用平稳",
        "vector": [0.0] * 22,
    }
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 422


def test_ingest_schema_source_mismatch_rejected_422(client, user_headers, passive_sensing_consent):
    """mic_opt 用 passive-core-v1 → 422（源 schema/source 组合必须匹配 registry）。"""
    payload = _mic_payload("evt_schema_mismatch_0001", schema_version=PASSIVE_CORE_V1)
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 422


def test_ingest_core_source_with_mic_schema_rejected_422(client, user_headers, passive_sensing_consent):
    """screen 用 mic-feature-v1 → 422。"""
    payload = {
        "event_id": "evt_schema_mismatch_0002",
        "user_id": "u_demo",
        "schema_version": MIC_FEATURE_V1,
        "source": "screen",
        "window_start": datetime.now(timezone.utc).isoformat(),
        "window_end": (datetime.now(timezone.utc) + timedelta(minutes=30)).isoformat(),
        "summary": "屏幕使用平稳",
        "vector": [0.0] * 22,
    }
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 422


def test_ingest_reject_counts_telemetry(client, user_headers, passive_sensing_consent):
    """schema/source 不匹配 → 遥测计数 ingest_reject_schema_mismatch（不记录特征内容）。"""
    before = snapshot_counts().get("ingest_reject_schema_mismatch", 0)
    payload = _mic_payload("evt_schema_tel_0001", schema_version=PASSIVE_CORE_V1)
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 422
    after = snapshot_counts().get("ingest_reject_schema_mismatch", 0)
    assert after == before + 1


# ---- mic 外围：可存储但不进入 aggregate ----

def test_mic_ingest_stored_but_not_in_aggregate(client, user_headers, passive_sensing_consent):
    """mic_opt 合法 ingest（存储为外围），但聚合统计不含 mic 行。"""
    # 先 grant voice_features consent
    grant = client.post("/v1/onboarding/consents", json={
        "user_id": "u_demo",
        "consent_type": "voice_features",
        "version": "voice-features-consent-2026.07",
        "granted": True,
        "evidence_hash": "v" * 64,
    }, headers=user_headers)
    assert grant.status_code == 200, grant.text

    mic_payload = _mic_payload("evt_schema_mic_0001")
    response = client.post("/v1/features/ingest", json=mic_payload, headers=user_headers)
    assert response.status_code == 200

    # screen 核心特征
    screen_payload = {
        "event_id": "evt_schema_screen_0001",
        "user_id": "u_demo",
        "schema_version": PASSIVE_CORE_V1,
        "source": "screen",
        "window_start": datetime.now(timezone.utc).isoformat(),
        "window_end": (datetime.now(timezone.utc) + timedelta(minutes=30)).isoformat(),
        "summary": "屏幕使用平稳",
        "vector": [0.0] * 22,
    }
    response2 = client.post("/v1/features/ingest", json=screen_payload, headers=user_headers)
    assert response2.status_code == 200

    with SessionLocal() as db:
        # mic 已存储
        mic_rows = db.scalars(select(DerivedFeature).where(
            DerivedFeature.schema_version == MIC_FEATURE_V1,
        )).all()
        assert len(mic_rows) == 1
        # 聚合行存在且只统计核心特征：valid_window_count 不含 mic
        agg = db.scalar(select(DailyBehaviorAggregate).where(
            DailyBehaviorAggregate.tenant_id == "t_demo",
            DailyBehaviorAggregate.user_id == "u_demo",
        ))
        assert agg is not None
        assert agg.valid_window_count == 1  # 只有 screen 窗口（mic 不进入聚合）
