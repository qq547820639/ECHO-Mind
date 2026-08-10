"""Milestone D 测试：golden 场景 + 确定性断言（直接调 service/engine，不依赖 HTTP）。

场景：
- scenario_001 偏晚/移动减少/屏幕接近 → LATER/LESS/SIMILAR + 偏晚 headline；
- scenario_002 全相似 → VERY_SIMILAR；
- scenario_003 屏幕暴增 → SCREEN_PATTERN LATER（晚屏）；
- scenario_004 低覆盖 → LOW_CONFIDENCE；
- scenario_005 冷启动 → WARMING_UP；
- scenario_006 部分数据 → PARTIAL_DATA；
- scenario_007 早期基线 → EARLY_BASELINE（事实句）。
"""
from datetime import date, timedelta

from app.database import SessionLocal
from app.models import DailyBehaviorAggregate, DerivedFeature
from app.services.aggregates.timezone import local_day_window
from app.services.portrait.engine import generate_portrait

TODAY = date(2026, 8, 10)  # 周一


def _df_row(event_id: str, *, source: str, local_minute: int, utc_start, vector: dict | None = None,
            sources: list[str] | None = None) -> DerivedFeature:
    ws = utc_start + timedelta(minutes=local_minute)
    v = [0.0] * 22
    if vector:
        for idx, val in vector.items():
            v[idx] = val
    return DerivedFeature(
        id=f"df_{event_id}", tenant_id="t_demo", user_id="u_demo", event_id=f"evt_{event_id}",
        schema_version="feat-v1", source=source, window_start=ws,
        window_end=ws + timedelta(minutes=5), summary="s", vector=v,
        sources_present=sources if sources is not None else [source],
    )


def make_today(utc_start, *, active_start: int = 495, movement: float = 1.06,
               screen_minutes: float = 126.0, late_minutes: float = 23.0,
               notif: int = 13, app: int = 43, pad: int = 240) -> list[DerivedFeature]:
    """构造今天的派生特征窗口：pad 个零窗口铺覆盖 + 5 个关键窗口。"""
    feats = []
    for i in range(pad):
        feats.append(_df_row(f"pad_{i}", source="screen", local_minute=5 * i,
                             utc_start=utc_start, sources=[]))
    main_ms = (screen_minutes - late_minutes) * 60000.0
    feats.append(_df_row("main_screen", source="screen", local_minute=active_start,
                         utc_start=utc_start, vector={14: 1, 16: main_ms}, sources=["screen"]))
    if late_minutes > 0:
        feats.append(_df_row("late_screen", source="screen", local_minute=22 * 60,
                             utc_start=utc_start, vector={14: 1, 16: late_minutes * 60000.0},
                             sources=["screen"]))
    feats.append(_df_row("accel", source="accel", local_minute=active_start + 5,
                         utc_start=utc_start, vector={7: movement}, sources=["accel"]))
    feats.append(_df_row("notif", source="notification", local_minute=9 * 60,
                         utc_start=utc_start, vector={17: float(notif)}, sources=["notification"]))
    feats.append(_df_row("app", source="app_activity", local_minute=10 * 60,
                         utc_start=utc_start, vector={20: float(app)}, sources=["app_activity"]))
    return feats


def make_baseline_rows(today: date = TODAY, days: int = 28) -> list[DailyBehaviorAggregate]:
    """28 天确定性模式基线（i%7 阶梯）：med 见下方各测试注释。"""
    rows = []
    start = today - timedelta(days=28)
    for i in range(days):
        k = i % 7
        rows.append(DailyBehaviorAggregate(
            id=f"dag_g_{i}", tenant_id="t_demo", user_id="u_demo",
            local_date=start + timedelta(days=i), timezone="Asia/Shanghai",
            coverage_score=0.8, valid_window_count=230, expected_window_count=288,
            movement_index=1.0 + k * 0.02, movement_variability=0.02,
            screen_on_minutes=120.0 + k * 2.0, screen_open_count=30,
            late_screen_minutes=20.0 + k, app_switch_count=40 + k,
            active_start_minute=480 + k * 5, active_end_minute=1320 + k * 5,
            notification_count=10 + k, rhythm_regularity=0.16 + k * 0.005,
            sources_present=["accel", "gyro", "screen", "notification", "app_activity"],
            missing_sources=[], schema_version="agg-v1",
        ))
    return rows


def _seed(db, today: date, kwargs: dict, baseline_days: int = 28, pad: int = 240):
    db.add_all(make_baseline_rows(today, days=baseline_days))
    utc_start, _ = local_day_window("Asia/Shanghai", today)
    db.add_all(make_today(utc_start, pad=pad, **kwargs))
    db.flush()


def test_scenario_001_later_less_screen_similar():
    """偏晚（active_start 540 vs med 495）+ 移动减少（0.5 vs 1.06）+ 屏幕接近（126 vs 126）。"""
    today = TODAY
    with SessionLocal() as db:
        _seed(db, today, dict(active_start=540, movement=0.5, screen_minutes=126.0, late_minutes=23.0))
        row = generate_portrait(db, tenant_id="t_demo", user_id="u_demo", local_date=today)
        assert row.status == "READY"
        assert row.dimensions["RHYTHM"]["value"] == "LATER"
        assert row.dimensions["MOVEMENT"]["value"] == "LESS"
        assert row.dimensions["SCREEN_PATTERN"]["value"] == "SIMILAR"
        assert row.dimensions["STABILITY"]["value"] == "CLEARLY_DIFFERENT"
        assert "稍晚" in row.summary
        assert "少了一些" in row.summary
        assert "比较接近" in row.summary
        assert "偏晚" in row.highlights
        assert row.confidence in ("HIGH", "MEDIUM")


def test_scenario_002_all_similar():
    """全部取基线中位值 → 全 SIMILAR → VERY_SIMILAR。"""
    today = TODAY
    with SessionLocal() as db:
        _seed(db, today, dict(active_start=495, movement=1.06, screen_minutes=126.0,
                              late_minutes=23.0, notif=13, app=43))
        row = generate_portrait(db, tenant_id="t_demo", user_id="u_demo", local_date=today)
        assert row.status == "READY"
        assert row.dimensions["STABILITY"]["value"] == "VERY_SIMILAR"
        assert "非常接近" in row.summary
        assert "稳定" in row.highlights


def test_scenario_003_screen_surge():
    """屏幕暴增（300 vs 126）且晚间也高（120 vs 23）→ SCREEN_PATTERN LATER（晚屏）。"""
    today = TODAY
    with SessionLocal() as db:
        _seed(db, today, dict(active_start=495, movement=1.06, screen_minutes=300.0, late_minutes=120.0))
        row = generate_portrait(db, tenant_id="t_demo", user_id="u_demo", local_date=today)
        assert row.status == "READY"
        assert row.dimensions["SCREEN_PATTERN"]["value"] == "LATER"
        assert "晚间屏幕互动比通常集中" in row.summary
        assert "晚屏" in row.highlights


def test_scenario_004_low_coverage():
    """今天只有 5 个窗口（coverage 0.017）→ LOW_CONFIDENCE。"""
    today = TODAY
    with SessionLocal() as db:
        _seed(db, today, dict(active_start=540, movement=0.5), pad=0)
        row = generate_portrait(db, tenant_id="t_demo", user_id="u_demo", local_date=today)
        assert row.status == "LOW_CONFIDENCE"
        assert row.confidence == "LOW"
        assert "还不够完整" in row.summary
        assert row.dimensions == {}
        assert row.highlights == []


def test_scenario_005_cold_start():
    """无任何基线数据 → WARMING_UP（固定文案，无维度比较）。"""
    today = TODAY
    with SessionLocal() as db:
        utc_start, _ = local_day_window("Asia/Shanghai", today)
        db.add_all(make_today(utc_start, pad=50))
        db.flush()
        row = generate_portrait(db, tenant_id="t_demo", user_id="u_demo", local_date=today)
        assert row.status == "WARMING_UP"
        assert row.confidence == "LOW"
        assert "正在慢慢了解" in row.summary
        assert row.dimensions == {}
        assert row.highlights == []


def test_scenario_006_partial_data():
    """coverage 0.347（100 窗口）→ MEDIUM 置信度 + PARTIAL_DATA 前缀。"""
    today = TODAY
    with SessionLocal() as db:
        _seed(db, today, dict(active_start=540, movement=0.5), pad=95)
        row = generate_portrait(db, tenant_id="t_demo", user_id="u_demo", local_date=today)
        assert row.status == "PARTIAL_DATA"
        assert row.summary.startswith("今天的数据还不完整，以下画像仅反映已经采集到的部分。 ")
        assert row.dimensions != {}


def test_scenario_007_early_baseline():
    """仅 5 个有效日 → EARLY_BASELINE：只输出当天事实句，不输出"比平常"。"""
    today = TODAY
    with SessionLocal() as db:
        _seed(db, today, dict(active_start=495, movement=1.06), baseline_days=5)
        row = generate_portrait(db, tenant_id="t_demo", user_id="u_demo", local_date=today)
        assert row.status == "EARLY_BASELINE"
        assert row.confidence == "LOW"
        assert "今天累计屏幕互动" in row.summary
        assert "比平常" not in row.summary
        assert row.dimensions == {}


def test_golden_deterministic_output():
    """同输入两次生成结果一致（确定性）。"""
    today = TODAY
    with SessionLocal() as db:
        _seed(db, today, dict(active_start=540, movement=0.5, screen_minutes=126.0, late_minutes=23.0))
        r1 = generate_portrait(db, tenant_id="t_demo", user_id="u_demo", local_date=today)
        r2 = generate_portrait(db, tenant_id="t_demo", user_id="u_demo", local_date=today)
        assert r1.summary == r2.summary
        assert r1.highlights == r2.highlights
        assert r1.dimensions == r2.dimensions
        assert r1.facts == r2.facts
        assert r1.status == r2.status
        # 幂等：同一 local_date 只有一行
        from sqlalchemy import select
        from app.models import DailyPortrait

        rows = db.scalars(select(DailyPortrait).where(
            DailyPortrait.tenant_id == "t_demo",
            DailyPortrait.user_id == "u_demo",
            DailyPortrait.local_date == today,
        )).all()
        assert len(rows) == 1
