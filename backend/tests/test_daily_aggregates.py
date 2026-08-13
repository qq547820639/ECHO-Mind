"""Milestone B + Phase 5 (C2) 测试：本地日窗口换算 / 聚合计算 / 幂等 upsert / ingest 端到端。"""
from datetime import datetime, timedelta, timezone

from sqlalchemy import select

from app.database import SessionLocal
from app.models import DailyBehaviorAggregate, DerivedFeature
from app.services.aggregates.calculator import (
    compute_daily_aggregate,
    expected_window_count,
    upsert_daily_aggregate,
)
from app.services.aggregates.timezone import local_day_window

TODAY = datetime.now(timezone.utc).date()


def _feature(event_id: str, *, source: str, window_start: datetime, vector: dict | None = None,
             sources_present: list[str] | None = None,
             schema_version: str = "passive-core-v1") -> DerivedFeature:
    v = [0.0] * 22
    if vector:
        for idx, val in vector.items():
            v[idx] = val
    return DerivedFeature(
        id=f"df_{event_id}", tenant_id="t_demo", user_id="u_demo", event_id=f"evt_{event_id}",
        schema_version=schema_version, source=source, window_start=window_start,
        window_end=window_start + timedelta(minutes=5), summary="s", vector=v,
        sources_present=sources_present if sources_present is not None else [source],
    )


# ---- 时区换算 ----

def test_shanghai_day_window():
    start, end = local_day_window("Asia/Shanghai", __import__("datetime").date(2026, 8, 10))
    assert start == datetime(2026, 8, 9, 16, 0, tzinfo=timezone.utc)
    assert end == datetime(2026, 8, 10, 16, 0, tzinfo=timezone.utc)


def test_utc_plus14_day_window():
    start, end = local_day_window("Pacific/Kiritimati", __import__("datetime").date(2026, 8, 10))
    assert start == datetime(2026, 8, 9, 10, 0, tzinfo=timezone.utc)
    assert end == datetime(2026, 8, 10, 10, 0, tzinfo=timezone.utc)


def test_utc_minus12_day_window():
    start, end = local_day_window("Etc/GMT+12", __import__("datetime").date(2026, 8, 10))
    assert start == datetime(2026, 8, 10, 12, 0, tzinfo=timezone.utc)
    assert end == datetime(2026, 8, 11, 12, 0, tzinfo=timezone.utc)


def test_dst_spring_forward_window():
    """America/New_York 2026-03-08（spring forward 02:00→03:00）：窗口 23 小时。"""
    start, end = local_day_window("America/New_York", __import__("datetime").date(2026, 3, 8))
    assert start == datetime(2026, 3, 8, 5, 0, tzinfo=timezone.utc)
    assert end == datetime(2026, 3, 9, 4, 0, tzinfo=timezone.utc)
    assert (end - start).total_seconds() == 23 * 3600


def test_dst_fall_back_window():
    """America/New_York 2026-11-01（fall back 02:00→01:00）：窗口 25 小时。"""
    start, end = local_day_window("America/New_York", __import__("datetime").date(2026, 11, 1))
    assert start == datetime(2026, 11, 1, 4, 0, tzinfo=timezone.utc)
    assert end == datetime(2026, 11, 2, 5, 0, tzinfo=timezone.utc)
    assert (end - start).total_seconds() == 25 * 3600


def test_day_window_crosses_midnight_utc():
    """上海 2026-08-10 本地日的 UTC 窗口跨越 2026-08-09→08-10 的午夜（16:00 UTC 边界）。"""
    start, _end = local_day_window("Asia/Shanghai", __import__("datetime").date(2026, 8, 10))
    assert start.date() == __import__("datetime").date(2026, 8, 9)


# ---- 聚合计算 ----

def test_expected_window_count_dynamic_dst():
    """expected_window_count 动态：普通日 288 / spring-forward 276 / fall-back 300。"""
    assert expected_window_count("Asia/Shanghai", __import__("datetime").date(2026, 8, 10)) == 288
    assert expected_window_count("America/New_York", __import__("datetime").date(2026, 3, 8)) == 276
    assert expected_window_count("America/New_York", __import__("datetime").date(2026, 11, 1)) == 300


def test_compute_daily_aggregate():
    tz = "Asia/Shanghai"
    local_date = __import__("datetime").date(2026, 8, 10)
    utc_start, _ = local_day_window(tz, local_date)

    def local_minute(minute: int) -> datetime:
        return utc_start + timedelta(minutes=minute)

    features = [
        _feature("w1", source="screen", window_start=local_minute(8 * 60), vector={14: 1, 16: 60000}),
        _feature("w2", source="screen", window_start=local_minute(22 * 60), vector={14: 1, 16: 120000}),
        _feature("w3", source="accel", window_start=local_minute(9 * 60), vector={7: 2.0}),
        _feature("w4", source="accel", window_start=local_minute(10 * 60), vector={7: 4.0}),
        _feature("w5", source="notification", window_start=local_minute(11 * 60), vector={17: 5}),
        _feature("w6", source="app_activity", window_start=local_minute(12 * 60), vector={20: 8}),
    ]
    data = compute_daily_aggregate(features, tz, local_date)
    assert data["valid_window_count"] == 6
    assert data["expected_window_count"] == 288  # 普通日动态仍为 288
    assert data["coverage_score"] == round(6 / 288, 4)
    assert data["movement_index"] == 3.0
    assert data["movement_variability"] == 1.0
    assert data["screen_on_minutes"] == 3.0
    assert data["screen_open_count"] == 2
    assert data["late_screen_minutes"] == 2.0  # 仅 22:00 窗口
    assert data["app_switch_count"] == 8
    assert data["notification_count"] == 5
    assert data["active_start_minute"] == 8 * 60
    assert data["active_end_minute"] == 22 * 60 + 5  # 最后一个 active 窗口是 22:00（晚间屏幕）
    assert data["active_hour_spread"] == round(4 / 24, 4)
    assert data["sources_present"] == ["accel", "app_activity", "notification", "screen"]
    assert data["missing_sources"] == ["gyro"]
    assert data["schema_version"] == "agg-v1"


def test_coverage_unique_windows_ignores_duplicates():
    """coverage 只计 unique (window_start, schema, source)：重复/重试不重复计数。"""
    tz = "Asia/Shanghai"
    local_date = __import__("datetime").date(2026, 8, 10)
    utc_start, _ = local_day_window(tz, local_date)

    def local_minute(minute: int) -> datetime:
        return utc_start + timedelta(minutes=minute)

    features = [
        _feature("d1", source="screen", window_start=local_minute(8 * 60), vector={14: 1, 16: 60000}),
        # 同一 window_start + schema + source 的重复行（retry）→ 不重复计数
        _feature("d2", source="screen", window_start=local_minute(8 * 60), vector={14: 1, 16: 60000}),
        _feature("d3", source="screen", window_start=local_minute(8 * 60), vector={14: 1, 16: 60000}),
        _feature("d4", source="accel", window_start=local_minute(9 * 60), vector={7: 2.0}),
        _feature("d5", source="accel", window_start=local_minute(9 * 60), vector={7: 4.0}),
    ]
    data = compute_daily_aggregate(features, tz, local_date)
    # 3 个 screen 重复 + 2 个 accel 重复 → 去重后 2 个 unique 窗口
    assert data["valid_window_count"] == 2
    assert data["coverage_score"] == round(2 / 288, 4)


def test_mic_and_health_not_in_aggregate():
    """mic_opt（mic-feature-v1）与 health 不进入聚合统计（外围特征）。"""
    tz = "Asia/Shanghai"
    local_date = __import__("datetime").date(2026, 8, 10)
    utc_start, _ = local_day_window(tz, local_date)

    def local_minute(minute: int) -> datetime:
        return utc_start + timedelta(minutes=minute)

    features = [
        _feature("m1", source="screen", window_start=local_minute(8 * 60), vector={14: 1, 16: 60000}),
        # mic_opt：mic-feature-v1（aggregate_eligible=False）→ 不参与
        _feature("m2", source="mic_opt", window_start=local_minute(9 * 60), vector={7: 99.0},
                 schema_version="mic-feature-v1"),
        # health：passive-core-v1 但无统计槽位 → 不参与
        _feature("m3", source="health", window_start=local_minute(10 * 60), vector={16: 999999},
                 sources_present=["health"]),
    ]
    data = compute_daily_aggregate(features, tz, local_date)
    assert data["valid_window_count"] == 1
    assert data["screen_on_minutes"] == 1.0
    assert data["movement_index"] is None
    assert data["sources_present"] == ["screen"]  # health 仅经核心窗口 sources_present 进入
    assert data["missing_sources"] == ["accel", "app_activity", "gyro", "notification"]


def test_dst_dynamic_expected_and_coverage():
    """DST 日 expected 动态（23h→276 / 25h→300），coverage 用动态分母。"""
    # spring-forward 23h 日
    tz = "America/New_York"
    local_date = __import__("datetime").date(2026, 3, 8)
    utc_start, _ = local_day_window(tz, local_date)
    data = compute_daily_aggregate(
        [_feature("sf1", source="screen", window_start=utc_start, vector={14: 1, 16: 60000})],
        tz, local_date)
    assert data["expected_window_count"] == 276
    assert data["coverage_score"] == round(1 / 276, 4)
    # fall-back 25h 日
    local_date2 = __import__("datetime").date(2026, 11, 1)
    utc_start2, _ = local_day_window(tz, local_date2)
    data2 = compute_daily_aggregate(
        [_feature("fb1", source="screen", window_start=utc_start2, vector={14: 1, 16: 60000})],
        tz, local_date2)
    assert data2["expected_window_count"] == 300
    assert data2["coverage_score"] == round(1 / 300, 4)


def test_compute_daily_aggregate_no_active_windows():
    tz = "Asia/Shanghai"
    local_date = __import__("datetime").date(2026, 8, 10)
    utc_start, _ = local_day_window(tz, local_date)
    features = [_feature("a1", source="accel", window_start=utc_start, vector={7: 1.0})]
    data = compute_daily_aggregate(features, tz, local_date)
    assert data["active_start_minute"] is None
    assert data["active_end_minute"] is None
    assert data["active_hour_spread"] is None


def test_compute_daily_aggregate_defensive_vector_bounds():
    """越界下标防御：短向量窗口按 0 处理。"""
    tz = "Asia/Shanghai"
    local_date = __import__("datetime").date(2026, 8, 10)
    utc_start, _ = local_day_window(tz, local_date)
    features = [_feature("s1", source="screen", window_start=utc_start, vector={0: 1.0})]
    data = compute_daily_aggregate(features, tz, local_date)
    assert data["screen_on_minutes"] == 0.0
    assert data["movement_index"] is None


# ---- 幂等 upsert ----

def test_upsert_daily_aggregate_idempotent():
    with SessionLocal() as db:
        tz = "Asia/Shanghai"
        local_date = TODAY
        utc_start, _ = local_day_window(tz, local_date)
        db.add(_feature("u1", source="screen", window_start=utc_start, vector={14: 1, 16: 60000}))
        db.flush()
        row1 = upsert_daily_aggregate(db, tenant_id="t_demo", user_id="u_demo",
                                      local_date=local_date, tz_name=tz)
        row2 = upsert_daily_aggregate(db, tenant_id="t_demo", user_id="u_demo",
                                      local_date=local_date, tz_name=tz)
        rows = db.scalars(select(DailyBehaviorAggregate).where(
            DailyBehaviorAggregate.tenant_id == "t_demo",
            DailyBehaviorAggregate.user_id == "u_demo",
            DailyBehaviorAggregate.local_date == local_date,
        )).all()
        assert len(rows) == 1
        assert row1.id == row2.id
        assert row1.screen_on_minutes == 1.0
        assert row1.valid_window_count == 1


# ---- ingest 端到端 ----

def test_ingest_triggers_aggregate(client, user_headers, passive_sensing_consent):
    payload = {
        "event_id": "evt_dag_e2e_0001",
        "user_id": "u_demo",
        "schema_version": "passive-core-v1",
        "source": "screen",
        "window_start": datetime.now(timezone.utc).isoformat(),
        "window_end": (datetime.now(timezone.utc) + timedelta(minutes=30)).isoformat(),
        "summary": "屏幕使用平稳",
        "vector": [0.0] * 22,
    }
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 200
    assert response.json()["idempotent_replay"] is False
    with SessionLocal() as db:
        rows = db.scalars(select(DailyBehaviorAggregate).where(
            DailyBehaviorAggregate.tenant_id == "t_demo",
            DailyBehaviorAggregate.user_id == "u_demo",
        )).all()
        assert len(rows) == 1
        assert rows[0].timezone == "Asia/Shanghai"
        assert rows[0].coverage_score == round(1 / 288, 4)


def test_ingest_replay_does_not_touch_aggregate_twice(client, user_headers, passive_sensing_consent):
    payload = {
        "event_id": "evt_dag_e2e_0002",
        "user_id": "u_demo",
        "schema_version": "passive-core-v1",
        "source": "screen",
        "window_start": datetime.now(timezone.utc).isoformat(),
        "window_end": (datetime.now(timezone.utc) + timedelta(minutes=30)).isoformat(),
        "summary": "屏幕使用平稳",
        "vector": [0.0] * 22,
    }
    first = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    second = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert first.status_code == 200
    assert second.json()["idempotent_replay"] is True
    with SessionLocal() as db:
        rows = db.scalars(select(DailyBehaviorAggregate).where(
            DailyBehaviorAggregate.tenant_id == "t_demo",
            DailyBehaviorAggregate.user_id == "u_demo",
        )).all()
        assert len(rows) == 1
