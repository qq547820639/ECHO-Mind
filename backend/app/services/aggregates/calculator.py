"""当日行为聚合计算（Milestone B）：确定性纯函数 + 幂等 upsert 写路径。

向量布局（DerivedFeatureIn.vector，22 维固定，越界下标防御读 0）：
  0-7  accel: mean_x,mean_y,mean_z,std_x,std_y,std_z,magnitude_mean,magnitude_std
  8-13 gyro: mean_x,mean_y,mean_z,std_x,std_y,std_z
  14-16 screen: on_count, off_count, on_duration_ms
  17-19 notification: total_count, social_count, other_count
  20-21 app: switch_count, top_app_duration_ms

产品契约：本模块绝不产出任何情绪/心理语义字段（PRD 契约点 2）。
"""
from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from statistics import median

from sqlalchemy import select
from sqlalchemy.orm import Session
from zoneinfo import ZoneInfo

from app.models import DailyBehaviorAggregate, DerivedFeature, utcnow

from app.services.aggregates.timezone import local_day_window

#: 固定窗口数：24h / 5min 窗口
EXPECTED_WINDOW_COUNT = 288
#: 期望存在的信号源集合（缺失计算 missing_sources 用）
EXPECTED_SOURCES = ("accel", "gyro", "screen", "notification", "app_activity")
AGG_SCHEMA_VERSION = "agg-v1"


def _v(feature: DerivedFeature, idx: int) -> float:
    """读取向量第 idx 维；越界（缺省 0 填充的窗口）防御返回 0。"""
    vector = feature.vector or []
    return float(vector[idx]) if len(vector) > idx else 0.0


def _to_local(dt: datetime, tz: ZoneInfo) -> datetime:
    """SQLite 的 DateTime(timezone=True) 读回 naive UTC：先补 UTC 再转用户时区。

    PostgreSQL 返回 aware datetime 直接转换；naive 分支仅 SQLite 路径触发。
    """
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(tz)


def _mean(values: list[float]) -> float:
    return sum(values) / len(values)


def _mad(values: list[float]) -> float:
    med = median(values)
    return median([abs(x - med) for x in values])


def compute_daily_aggregate(features: list[DerivedFeature], tz_name: str, local_date: date) -> dict:
    """把用户本地日窗口内的派生特征聚合力单一指标 dict（确定性）。"""
    tz = ZoneInfo(tz_name)
    valid = len(features)
    coverage_score = round(min(valid / EXPECTED_WINDOW_COUNT, 1.0), 4)

    # 移动：仅统计 accel 幅度均值（vector[7] > 0 的窗口）
    accel_vals = [_v(f, 7) for f in features if _v(f, 7) > 0]
    movement_index = _mean(accel_vals) if accel_vals else None
    movement_variability = _mad(accel_vals) if accel_vals else None

    screen_on_minutes = sum(_v(f, 16) for f in features) / 60000.0
    screen_open_count = int(sum(_v(f, 14) for f in features))
    late = [f for f in features if _to_local(f.window_start, tz).hour >= 21]
    late_screen_minutes = sum(_v(f, 16) for f in late) / 60000.0
    app_switch_count = int(sum(_v(f, 20) for f in features))
    notification_count = int(sum(_v(f, 17) for f in features))

    # active 窗口：screen on / notification / app switch 任一非零
    active = sorted(
        [f for f in features if _v(f, 14) > 0 or _v(f, 17) > 0 or _v(f, 20) > 0],
        key=lambda f: f.window_start,
    )
    if active:
        starts = [_to_local(f.window_start, tz) for f in active]
        active_start_minute = starts[0].hour * 60 + starts[0].minute
        last_end = starts[-1] + timedelta(minutes=5)  # 窗口时长 5 分钟
        active_end_minute = last_end.hour * 60 + last_end.minute
        rhythm_regularity = round(len({s.hour for s in starts}) / 24.0, 4)
    else:
        active_start_minute = None
        active_end_minute = None
        rhythm_regularity = None

    sources: set[str] = set()
    for f in features:
        sources.update(f.sources_present or [])
    sources_present = sorted(sources)
    missing_sources = sorted(set(EXPECTED_SOURCES) - sources)

    return {
        "valid_window_count": valid,
        "expected_window_count": EXPECTED_WINDOW_COUNT,
        "coverage_score": coverage_score,
        "movement_index": movement_index,
        "movement_variability": movement_variability,
        "screen_on_minutes": screen_on_minutes,
        "screen_open_count": screen_open_count,
        "late_screen_minutes": late_screen_minutes,
        "app_switch_count": app_switch_count,
        "notification_count": notification_count,
        "active_start_minute": active_start_minute,
        "active_end_minute": active_end_minute,
        "rhythm_regularity": rhythm_regularity,
        "sources_present": sources_present,
        "missing_sources": missing_sources,
        "schema_version": AGG_SCHEMA_VERSION,
        "timezone": tz_name,
        "local_date": local_date,
    }


def upsert_daily_aggregate(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    local_date: date,
    tz_name: str,
) -> DailyBehaviorAggregate:
    """范围查询该日全部 DerivedFeature 后幂等 upsert 当日聚合行。

    写路径：仅由 ingest / 画像重建调用；GET 不触发。调用方负责 commit。
    """
    utc_start, utc_end = local_day_window(tz_name, local_date)
    features = db.scalars(
        select(DerivedFeature).where(
            DerivedFeature.tenant_id == tenant_id,
            DerivedFeature.user_id == user_id,
            DerivedFeature.window_start >= utc_start,
            DerivedFeature.window_start < utc_end,
        ).order_by(DerivedFeature.window_start)
    ).all()
    data = compute_daily_aggregate(features, tz_name, local_date)
    now = utcnow()

    existing = db.scalar(select(DailyBehaviorAggregate).where(
        DailyBehaviorAggregate.tenant_id == tenant_id,
        DailyBehaviorAggregate.user_id == user_id,
        DailyBehaviorAggregate.local_date == local_date,
    ))
    if existing:
        for key in ("coverage_score", "valid_window_count", "expected_window_count",
                    "movement_index", "movement_variability", "screen_on_minutes",
                    "screen_open_count", "late_screen_minutes", "app_switch_count",
                    "active_start_minute", "active_end_minute", "notification_count",
                    "rhythm_regularity", "sources_present", "missing_sources",
                    "schema_version", "timezone"):
            setattr(existing, key, data[key])
        existing.updated_at = now
        existing.finalized_at = now
        db.flush()
        return existing

    row = DailyBehaviorAggregate(
        tenant_id=tenant_id,
        user_id=user_id,
        local_date=local_date,
        timezone=data["timezone"],
        coverage_score=data["coverage_score"],
        valid_window_count=data["valid_window_count"],
        expected_window_count=data["expected_window_count"],
        movement_index=data["movement_index"],
        movement_variability=data["movement_variability"],
        screen_on_minutes=data["screen_on_minutes"],
        screen_open_count=data["screen_open_count"],
        late_screen_minutes=data["late_screen_minutes"],
        app_switch_count=data["app_switch_count"],
        active_start_minute=data["active_start_minute"],
        active_end_minute=data["active_end_minute"],
        notification_count=data["notification_count"],
        rhythm_regularity=data["rhythm_regularity"],
        sources_present=data["sources_present"],
        missing_sources=data["missing_sources"],
        schema_version=data["schema_version"],
        generated_at=now,
        updated_at=now,
        finalized_at=now,
    )
    db.add(row)
    db.flush()
    return row
