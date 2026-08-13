"""Milestone C 测试：基线构建 16 场景（直接构造 DailyBehaviorAggregate 行，不经 ingest）。

场景清单：完全稳定 / 缓慢变化 / 单日异常 / 极端异常 / 缺失日 / 某 sensor 缺失 /
低覆盖 / 全零 / 28天 / 7天 / 3天 / weekend / weekday / timezone / DST / travel=日期不连续。
"""
from datetime import date, timedelta


from app.database import SessionLocal
from app.models import DailyBehaviorAggregate
from app.services.baseline.calculator import baseline_state, build_baseline
from app.services.baseline.circular import circular_mad, circular_median
from app.services.baseline.metrics import compute_stats, mad, median, percentile

TODAY = date(2026, 8, 10)  # 周一
START = TODAY - timedelta(days=28)  # 2026-07-13


def _agg(i: int, *, local_date: date | None = None, coverage: float = 0.8,
         movement_index: float | None = 1.0, screen_on_minutes: float = 120.0,
         late_screen_minutes: float = 20.0, app_switch_count: int = 40,
         notification_count: int = 10, active_start_minute: int = 480,
         active_end_minute: int = 1380, active_hour_spread: float = 0.5,
         timezone: str = "Asia/Shanghai", screen_open_count: int = 30) -> DailyBehaviorAggregate:
    return DailyBehaviorAggregate(
        id=f"dag_b_{i}", tenant_id="t_demo", user_id="u_demo",
        local_date=local_date if local_date is not None else START + timedelta(days=i),
        timezone=timezone, coverage_score=coverage, valid_window_count=230,
        expected_window_count=288, movement_index=movement_index,
        movement_variability=None, screen_on_minutes=screen_on_minutes,
        screen_open_count=screen_open_count, late_screen_minutes=late_screen_minutes,
        app_switch_count=app_switch_count, active_start_minute=active_start_minute,
        active_end_minute=active_end_minute, notification_count=notification_count,
        active_hour_spread=active_hour_spread,
        sources_present=["accel", "gyro", "screen", "notification", "app_activity"],
        missing_sources=[], schema_version="agg-v1",
    )


def _build(rows: list[DailyBehaviorAggregate], today: date = TODAY) -> dict:
    with SessionLocal() as db:
        db.add_all(rows)
        db.flush()
        snapshot = build_baseline(db, tenant_id="t_demo", user_id="u_demo", today_local=today)
        return {"snapshot": snapshot, "db": db}


# ---- robust 统计纯函数 ----

def test_robust_stats_pure_functions():
    values = [1.0, 2.0, 3.0, 4.0, 100.0]
    assert median(values) == 3.0
    assert mad(values) == 1.0  # |1-3|,|2-3|,|3-3|,|4-3|,|100-3| → median([1,1,0,1,97]) = 1
    assert percentile(values, 50) == 3.0
    assert percentile([1.0, 2.0], 50) == 1.5  # 线性插值
    stats = compute_stats(values)
    assert stats["median"] == 3.0
    assert stats["valid_days"] == 5
    empty = compute_stats([])
    assert empty["median"] is None and empty["valid_days"] == 0
    zeros = compute_stats([0.0, 0.0, 0.0])
    assert zeros["median"] == 0.0 and zeros["p90"] == 0.0


def test_baseline_state_boundaries():
    assert baseline_state(0) == "WARMING_UP"
    assert baseline_state(2) == "WARMING_UP"
    assert baseline_state(3) == "EARLY_BASELINE"
    assert baseline_state(6) == "EARLY_BASELINE"
    assert baseline_state(7) == "BASELINE_READY"
    assert baseline_state(28) == "BASELINE_READY"


# ---- Phase 5 (C3)：circular 时间统计 ----

def test_circular_median_across_midnight():
    """23:55 与 00:05 正确接近：圆周中位数落在 00:05 而非 23:55。"""
    # 1435 = 23:55, 5 = 00:05, 10 = 00:10
    med = circular_median([1435.0, 5.0, 10.0])
    assert med == 5.0  # 线性视角会选 1435，圆周视角选 00:05
    # 圆周 MAD 小（5 分钟），证明"接近"
    assert circular_mad([1435.0, 5.0, 10.0]) == 5.0


def test_circular_median_single_and_empty():
    assert circular_median([]) is None
    assert circular_mad([]) is None
    assert circular_median([720.0]) == 720.0
    assert circular_mad([720.0]) == 0.0


def test_circular_stats_used_for_time_metrics_in_baseline():
    """active_start_minute 使用圆周统计：跨午夜样本的 median/mad 正确。"""
    rows = [_agg(i, active_start_minute=[1435, 5, 10][i % 3]) for i in range(28)]
    out = _build(rows)
    stats = out["snapshot"].metrics["active_start_minute"]
    assert stats["median"] == 5.0  # 00:05（圆周中位数）
    assert stats["mad"] == 5.0
    assert stats["p10"] is None and stats["p25"] is None  # 圆周指标不输出线性分位


# ---- Phase 5 (C4)：near-zero baseline 不产生巨大 z ----

def test_near_zero_baseline_no_huge_z():
    """movement_index 近零基线：|v-med| 小于最小有意义差 → SIMILAR（z=0 而非巨大）。"""
    from app.services.portrait.dimensions import _z

    stats = {"median": 0.001, "mad": 0.0, "p25": 0.0, "p75": 0.0}
    # 0.01 与 med 0.001 相差 0.009 < MIN_ABS_DELTA(movement_index)=0.02 → z=0
    assert _z(0.01, stats, "movement_index") == 0.0
    # 全零基线 + 极小 today 值同样不产生巨大 z
    zero_stats = {"median": 0.0, "mad": 0.0, "p25": 0.0, "p75": 0.0}
    assert _z(0.01, zero_stats, "movement_index") == 0.0


# ---- 16 场景 ----

def test_scene_completely_stable():
    rows = [_agg(i) for i in range(28)]  # 每天完全相同
    out = _build(rows)
    snap = out["snapshot"]
    assert snap.bucket == "weekday"
    assert snap.valid_days == 20  # 28 天中 20 个工作日
    assert snap.metrics["movement_index"]["median"] == 1.0
    assert snap.metrics["screen_on_minutes"]["median"] == 120.0
    assert snap.metrics["movement_index"]["mad"] == 0.0


def test_scene_slowly_changing():
    rows = [_agg(i, movement_index=0.5 + i * 0.02, screen_on_minutes=60.0 + i * 2.0) for i in range(28)]
    out = _build(rows)
    snap = out["snapshot"]
    # 均匀递增 → 中位数接近中间值（i=13.5 → movement≈0.77）
    assert abs(snap.metrics["movement_index"]["median"] - 0.77) < 0.05
    assert snap.metrics["screen_on_minutes"]["p25"] < snap.metrics["screen_on_minutes"]["p75"]


def test_scene_single_day_anomaly():
    rows = [_agg(i) for i in range(28)]
    rows[10].screen_on_minutes = 1000.0  # 单日异常：不影响中位数
    out = _build(rows)
    assert out["snapshot"].metrics["screen_on_minutes"]["median"] == 120.0


def test_scene_extreme_anomaly():
    rows = [_agg(i) for i in range(28)]
    rows[0].movement_index = 999.0  # 极端异常：robust 中位数不受影响
    out = _build(rows)
    assert out["snapshot"].metrics["movement_index"]["median"] == 1.0


def test_scene_missing_days():
    # 10 个工作日（7-13..7-17 + 7-20..7-24）：只有部分天有数据
    workdays = [date(2026, 7, 13) + timedelta(days=k) for k in range(5)] + \
               [date(2026, 7, 20) + timedelta(days=k) for k in range(5)]
    rows = [_agg(i, local_date=d) for i, d in enumerate(workdays)]
    out = _build(rows)
    assert out["snapshot"].valid_days == 10


def test_scene_sensor_missing():
    rows = [_agg(i, movement_index=None, active_hour_spread=None) for i in range(28)]
    out = _build(rows)
    assert out["snapshot"].metrics["movement_index"]["median"] is None
    assert out["snapshot"].metrics["movement_index"]["valid_days"] == 0
    assert out["snapshot"].metrics["active_hour_spread"]["median"] is None
    # 其他指标正常
    assert out["snapshot"].metrics["screen_on_minutes"]["median"] == 120.0


def test_scene_low_coverage():
    rows = [_agg(i, coverage=0.1) for i in range(28)]  # 低于 0.25 → 全部排除
    out = _build(rows)
    assert out["snapshot"].valid_days == 0
    assert out["snapshot"].bucket == "all_days"


def test_scene_all_zero():
    rows = [_agg(i, movement_index=0.0, screen_on_minutes=0.0) for i in range(28)]
    out = _build(rows)
    assert out["snapshot"].metrics["movement_index"]["median"] == 0.0
    assert out["snapshot"].metrics["screen_on_minutes"]["median"] == 0.0


def test_scene_28_days():
    rows = [_agg(i) for i in range(28)]
    out = _build(rows)
    assert out["snapshot"].valid_days == 20
    assert baseline_state(out["snapshot"].valid_days) == "BASELINE_READY"


def test_scene_7_days():
    # 7 个工作日 → weekday 桶有效日 7 → BASELINE_READY
    workdays = [date(2026, 7, 13) + timedelta(days=k) for k in range(5)] + \
               [date(2026, 7, 20), date(2026, 7, 21)]
    rows = [_agg(i, local_date=d) for i, d in enumerate(workdays)]
    out = _build(rows)
    assert out["snapshot"].valid_days == 7
    assert baseline_state(out["snapshot"].valid_days) == "BASELINE_READY"


def test_scene_3_days():
    rows = [_agg(i) for i in range(3)]
    out = _build(rows)
    assert out["snapshot"].valid_days == 3
    assert baseline_state(out["snapshot"].valid_days) == "EARLY_BASELINE"


def test_scene_weekend_bucket():
    saturday = date(2026, 8, 8)
    rows = [_agg(i, local_date=saturday - timedelta(days=28) + timedelta(days=i)) for i in range(28)]
    out = _build(rows, today=saturday)
    snap = out["snapshot"]
    assert snap.bucket == "weekend"
    assert snap.valid_days == 8  # 28 天中 8 个周末日


def test_scene_weekday_bucket():
    rows = [_agg(i) for i in range(28)]
    out = _build(rows)
    assert out["snapshot"].bucket == "weekday"


def test_scene_weekday_fallback_to_all_days():
    """工作日桶有效日 <2 → fallback all_days。"""
    rows = [
        _agg(0, local_date=date(2026, 8, 1)),  # 周六
        _agg(1, local_date=date(2026, 8, 2)),  # 周日
        _agg(2, local_date=date(2026, 8, 8)),  # 周六
        _agg(3, local_date=date(2026, 8, 9)),  # 周日
        _agg(4, local_date=date(2026, 8, 3)),  # 周一（唯一工作日）
    ]
    out = _build(rows, today=TODAY)
    snap = out["snapshot"]
    assert snap.valid_days == 5  # 只有 1 个工作日 → weekday 桶不足 → 用 all_days
    assert snap.bucket == "all_days"


def test_scene_timezone():
    rows = [_agg(i, timezone="America/New_York") for i in range(28)]
    out = _build(rows)
    # 聚合行的 timezone 只是记录字段，不影响计算
    assert out["snapshot"].metrics["screen_on_minutes"]["median"] == 120.0


def test_scene_dst_window():
    """基线窗口包含 DST fall-back 日（2026-11-01）：日期照常参与。"""
    today = date(2026, 11, 8)  # 周日
    rows = [_agg(i, local_date=today - timedelta(days=28) + timedelta(days=i)) for i in range(28)]
    out = _build(rows, today=today)
    snap = out["snapshot"]
    assert snap.bucket == "weekend"
    assert snap.valid_days == 8
    assert snap.metrics["screen_on_minutes"]["median"] == 120.0


def test_scene_travel_discontinuous_dates():
    """travel：日期不连续，只按存在行统计（分桶后按实际工作日算）。"""
    rows = []
    d = START
    i = 0
    while d <= TODAY - timedelta(days=1):
        rows.append(_agg(i, local_date=d))
        d += timedelta(days=3)  # 每 3 天一行（不连续）
        i += 1
    out = _build(rows)
    # 10 行：7-13,16,19,22,25,28,31, 8-3,6,9 → 工作日 7 天（今天周一 → weekday 桶）
    assert out["snapshot"].bucket == "weekday"
    assert out["snapshot"].valid_days == 7
    assert out["snapshot"].metrics["screen_on_minutes"]["median"] == 120.0


# ---- 幂等 upsert personal_baselines ----

def test_baseline_upsert_idempotent():
    from sqlalchemy import select
    from app.models import PersonalBaseline

    with SessionLocal() as db:
        db.add_all([_agg(i) for i in range(28)])
        db.flush()
        s1 = build_baseline(db, tenant_id="t_demo", user_id="u_demo", today_local=TODAY)
        s2 = build_baseline(db, tenant_id="t_demo", user_id="u_demo", today_local=TODAY)
        rows = db.scalars(select(PersonalBaseline).where(
            PersonalBaseline.tenant_id == "t_demo",
            PersonalBaseline.user_id == "u_demo",
        )).all()
        assert len(rows) == 1
        assert s1.version == s2.version == "base-v1"
        assert rows[0].bucket == "weekday"
        assert rows[0].valid_days == 20
