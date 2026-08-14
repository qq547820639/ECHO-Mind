"""基线构建（Milestone C）：近 28 天有效日按桶分组的 robust 统计 + 幂等 upsert。

- 有效日 = 该用户存在 aggregate 且 coverage_score >= 0.25 且 local_date 在 [today-28, today-1]；
- 按 weekday/weekend 分桶；某桶有效日 < 2 则 fallback all_days；
- baseline_state(valid_days)：0-2 → WARMING_UP，3-6 → EARLY_BASELINE，>=7 → BASELINE_READY；
- baseline_version 固定 "base-v1"，同 tenant+user+bucket 幂等 upsert（简化方案）。
"""
from __future__ import annotations
from typing import Any

from collections.abc import Sequence
from datetime import date, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import DailyBehaviorAggregate, PersonalBaseline, utcnow

from app.services.baseline.day_type import bucket_for_date, is_weekend
from app.services.baseline.metrics import compute_stats
from app.services.baseline.models import BaselineSnapshot

#: 基线统计窗口：近 28 天
WINDOW_DAYS = 28
#: 有效日覆盖阈值
MIN_COVERAGE = 0.25
#: 桶内最少有效日（不足则 fallback all_days）
MIN_BUCKET_DAYS = 2
#: 基线版本（简化方案：固定版本幂等覆盖，不做 revision 递增）
BASELINE_VERSION = "base-v1"

#: 参与基线统计的行为指标（active_end_minute 仅供 explain/事实使用）
BASELINE_METRICS = (
    "movement_index",
    "screen_on_minutes",
    "screen_open_count",
    "late_screen_minutes",
    "app_switch_count",
    "notification_count",
    "active_start_minute",
    "active_end_minute",
    "active_hour_spread",
)

#: 时间类分钟指标：使用圆周 median/MAD（23:55 与 00:05 正确接近）
CIRCULAR_METRICS = ("active_start_minute", "active_end_minute")


def baseline_state(valid_days: int) -> str:
    """冷启动状态机：0-2 WARMING_UP / 3-6 EARLY_BASELINE / >=7 BASELINE_READY。"""
    if valid_days <= 2:
        return "WARMING_UP"
    if valid_days <= 6:
        return "EARLY_BASELINE"
    return "BASELINE_READY"


def _stats_for(aggs: Sequence[DailyBehaviorAggregate]) -> dict[str, Any]:
    metrics: dict[str, Any] = {}
    for name in BASELINE_METRICS:
        values = [float(getattr(a, name)) for a in aggs if getattr(a, name) is not None]
        metrics[name] = compute_stats(values, circular=(name in CIRCULAR_METRICS))
    return metrics


def _unique_days(aggs: Sequence[DailyBehaviorAggregate]) -> int:
    return len({a.local_date for a in aggs})


def build_baseline(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    today_local: date,
) -> BaselineSnapshot:
    """构建基线并幂等 upsert personal_baselines（写路径：仅由画像重建调用）。

    返回实际使用的桶（fallback 后）对应的快照。
    """
    start = today_local - timedelta(days=WINDOW_DAYS)
    end = today_local - timedelta(days=1)
    aggs = db.scalars(
        select(DailyBehaviorAggregate).where(
            DailyBehaviorAggregate.tenant_id == tenant_id,
            DailyBehaviorAggregate.user_id == user_id,
            DailyBehaviorAggregate.local_date >= start,
            DailyBehaviorAggregate.local_date <= end,
            DailyBehaviorAggregate.coverage_score >= MIN_COVERAGE,
        ).order_by(DailyBehaviorAggregate.local_date)
    ).all()

    weekday_aggs = [a for a in aggs if not is_weekend(a.local_date)]
    weekend_aggs = [a for a in aggs if is_weekend(a.local_date)]
    weekday_days = _unique_days(weekday_aggs)
    weekend_days = _unique_days(weekend_aggs)
    all_days = _unique_days(aggs)

    today_bucket = bucket_for_date(today_local)
    bucket: str
    chosen: Sequence[DailyBehaviorAggregate]
    valid_days: int
    if today_bucket == "weekday":
        if weekday_days >= MIN_BUCKET_DAYS:
            bucket, chosen, valid_days = "weekday", weekday_aggs, weekday_days
        else:
            bucket, chosen, valid_days = "all_days", aggs, all_days
    else:
        if weekend_days >= MIN_BUCKET_DAYS:
            bucket, chosen, valid_days = "weekend", weekend_aggs, weekend_days
        else:
            bucket, chosen, valid_days = "all_days", aggs, all_days

    metrics = _stats_for(chosen)
    now = utcnow()
    existing = db.scalar(select(PersonalBaseline).where(
        PersonalBaseline.tenant_id == tenant_id,
        PersonalBaseline.user_id == user_id,
        PersonalBaseline.bucket == bucket,
        PersonalBaseline.baseline_version == BASELINE_VERSION,
    ))
    if existing:
        existing.window_start = start
        existing.window_end = end
        existing.valid_days = valid_days
        existing.metrics = metrics
        existing.updated_at = now
        db.flush()
    else:
        db.add(PersonalBaseline(
            tenant_id=tenant_id,
            user_id=user_id,
            baseline_version=BASELINE_VERSION,
            bucket=bucket,
            window_start=start,
            window_end=end,
            valid_days=valid_days,
            metrics=metrics,
            generated_at=now,
            updated_at=now,
        ))
        db.flush()

    return BaselineSnapshot(
        bucket=bucket,
        window_start=start,
        window_end=end,
        valid_days=valid_days,
        metrics=metrics,
        version=BASELINE_VERSION,
    )
