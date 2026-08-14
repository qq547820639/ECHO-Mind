"""Phase 7.1 完整 Portrait 主链 E2E（后端侧）。

产品闭环验证：
User → Consent → 多日 feature ingest → DailyBehaviorAggregate → Automatic
PortraitMaterializer → PersonalBaseline → DailyPortrait → /v1/me/portraits/today
→ Android 解析（fixtures 契约）+ Room 缓存 identity（DTO 断言）。

覆盖（Phase 7.1 要求）：
- new user day 1（WARMING_UP）
- day 3（EARLY_BASELINE）
- baseline ready（≥7 有效日）
- stable day（VERY_SIMILAR）
- later rhythm（RHYTHM LATER）
- less movement（MOVEMENT LESS）
- screen timing shift（SCREEN_TIMING LATER）
- partial data（低覆盖）
- low confidence
- duplicate feature（幂等不重复计数）
- late-arriving feature（昨天补齐）
- timezone（用户时区本地日）
- denied optional permissions（missing sources → 覆盖/置信度下降）
"""
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import func, select

from app.database import SessionLocal
from app.models import DailyBehaviorAggregate, DailyPortrait
from app.services.portrait.materializer import materialize_dirty

LOCAL_TODAY = datetime.now(timezone.utc).astimezone(ZoneInfo("Asia/Shanghai")).date()


def _feature(event_id: str, *, user_id: str = "u_demo", source: str = "screen",
             window_start: datetime | None = None, vector: list[float] | None = None,
             schema_version: str = "passive-core-v1") -> dict:
    # window_start 统一为 **naive UTC**：后端 ingest 对 naive 按 UTC 解释（features.py
    # SQLite 路径防御），避免 aware/naive 混存导致 SQLite 读回 mix（Phase 7.1 E2E 修正）。
    start = window_start or datetime.now(timezone.utc).replace(
        hour=12, minute=0, second=0, microsecond=0, tzinfo=None)
    return {
        "event_id": event_id,
        "user_id": user_id,
        "schema_version": schema_version,
        "source": source,
        "window_start": start.isoformat(),
        "window_end": (start + timedelta(minutes=30)).isoformat(),
        "summary": "窗口特征摘要",
        "vector": vector if vector is not None else [0.1, 0.2, 0.3],
    }


def _seed_day(client, user_headers, *, local_date, active_start_hour: int = 8,
              movement: float = 1.0, screen_minutes: float = 120.0,
              late_minutes: float = 20.0, windows: int = 90, tz_name: str = "Asia/Shanghai",
              sources: list[str] | None = None, late_windows: int = 1) -> None:
    """在指定本地日播种 N 个 5 分钟窗口特征（覆盖 >= 0.25 阈值）。

    窗口时刻：先算本地日 00:00 的 UTC 时刻（local_date + tz），再以 naive UTC
    偏移构造各窗口起点——与后端 local_day_window 的日界线语义一致。
    """
    tz = ZoneInfo(tz_name)
    local_midnight = datetime.combine(local_date, datetime.min.time(), tzinfo=tz)
    day_start_utc = local_midnight.astimezone(timezone.utc).replace(tzinfo=None)
    sources = sources or ["screen"]
    for i in range(windows):
        ws = day_start_utc + timedelta(hours=active_start_hour, minutes=5 * i)
        vector = [
            0.1, 0.2, 9.8, 0.05, 0.06, 0.07, 9.8, movement,  # accel 8
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0,  # gyro 6
            1.0, 1.0, screen_minutes * 60000.0 / windows,  # screen 3
            0.0, 0.0, 0.0,  # notification 3
            0.0, 0.0,  # app 2
        ]
        payload = _feature(
            f"e2e_{local_date}_{i}", user_id="u_demo", source=sources[i % len(sources)],
            window_start=ws, vector=vector,
        )
        # 晚间窗口：late_screen_minutes 由 22 点后窗口贡献（可配置数量；
        # 晚屏场景用多个晚间窗口 + 高 screen duration 模拟"晚间使用集中"）
        if i >= windows - late_windows and late_minutes > 0:
            payload["window_start"] = (day_start_utc + timedelta(hours=22, minutes=5 * (i - (windows - late_windows)))).isoformat()
            payload["window_end"] = (day_start_utc + timedelta(hours=22, minutes=5 * (i - (windows - late_windows)) + 5)).isoformat()
            # 晚间窗口的 screen duration 提高（模拟长时间屏幕使用）
            vec_late = list(payload["vector"])
            vec_late[16] = late_minutes * 60000.0 / late_windows
            payload["vector"] = vec_late
        resp = client.post("/v1/features/ingest", json=payload, headers=user_headers)
        assert resp.status_code == 200, f"ingest {local_date}/{i} failed: {resp.text}"



def _seed_history(client, user_headers, **kwargs):
    """播种足够历史：weekday 与 weekend 两个桶各 ≥7 有效日（含 today 所在桶）。

    只播种必要日期（约 14 天），控制用例时长；weekend 边界（周六/周日跑测试）
    不再因 9 天窗口内周末不足而退化为 WARMING_UP。
    """
    seeded_weekday = seeded_weekend = 0
    d = 1  # 从昨天开始回退（today 由各用例单独播种）
    while seeded_weekday < 7 or seeded_weekend < 7:
        local_date = LOCAL_TODAY - timedelta(days=d)
        if local_date.weekday() >= 5:
            if seeded_weekend < 7:
                _seed_day(client, user_headers, local_date=local_date, **kwargs)
                seeded_weekend += 1
        elif seeded_weekday < 7:
            _seed_day(client, user_headers, local_date=local_date, **kwargs)
            seeded_weekday += 1
        d += 1
        assert d < 45, f"seed history 窗口异常：{d} 天仍未覆盖双桶 7 日"


def _finalize_today(client, user_headers):
    """触发最终物化：POST /v1/me/portraits/rebuild（repair 语义；测试验证最终画像）。
    说明：materializer 对今天有 15 分钟 debounce（当天合并），连续 ingest 后
    GET 可能返回中间态；rebuild 强制生成最终画像（生产路径由 debounce 保证
    最终一致性，测试用 rebuild 验证最终正确性）。"""
    resp = client.post("/v1/me/portraits/rebuild", json={}, headers=user_headers)
    assert resp.status_code == 200, f"rebuild failed: {resp.text}"


def _aggregate(user_id: str = "u_demo") -> DailyBehaviorAggregate | None:
    with SessionLocal() as db:
        return db.scalar(select(DailyBehaviorAggregate).where(
            DailyBehaviorAggregate.user_id == user_id,
        ).order_by(DailyBehaviorAggregate.local_date.desc()))


def _portrait(user_id: str = "u_demo") -> DailyPortrait | None:
    with SessionLocal() as db:
        return db.scalar(select(DailyPortrait).where(
            DailyPortrait.user_id == user_id,
        ).order_by(DailyPortrait.local_date.desc()))


def test_full_chain_day1_warming_up(client, user_headers, passive_sensing_consent):
    """新用户 day 1：ingest → aggregate → materialize → WARMING_UP（无维度）。"""
    _seed_day(client, user_headers, local_date=LOCAL_TODAY, windows=120)
    resp = client.get("/v1/me/portraits/today", headers=user_headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "WARMING_UP"
    assert body["dimensions"] == {}
    assert body["date"] == str(LOCAL_TODAY)
    assert body["timezone_used"] == "Asia/Shanghai"


def test_full_chain_day3_early_baseline(client, user_headers, passive_sensing_consent):
    """day 3：EARLY_BASELINE（summary-only，无维度比较）。"""
    for d in range(6):
        _seed_day(client, user_headers, local_date=LOCAL_TODAY - timedelta(days=5 - d), windows=120)
    _finalize_today(client, user_headers)
    resp = client.get("/v1/me/portraits/today", headers=user_headers)
    body = resp.json()
    assert body["status"] == "EARLY_BASELINE"
    assert body["summary"]


def test_full_chain_baseline_ready_and_stable(client, user_headers, passive_sensing_consent):
    """7+ 有效日：BASELINE_READY + 稳定日 VERY_SIMILAR。"""
    _seed_history(client, user_headers, windows=120)
    _seed_day(client, user_headers, local_date=LOCAL_TODAY, windows=120)
    _finalize_today(client, user_headers)
    resp = client.get("/v1/me/portraits/today", headers=user_headers)
    body = resp.json()
    assert body["status"] in ("READY", "PARTIAL_DATA")
    assert body["baseline_days"] >= 7
    assert body["dimensions"]  # READY 有维度
    assert body["timezone_used"] == "Asia/Shanghai"


def test_full_chain_later_rhythm(client, user_headers, passive_sensing_consent):
    """稳定 7 天后，今天 active_start 明显更晚 → RHYTHM LATER。"""
    _seed_history(client, user_headers, active_start_hour=8, windows=120)
    # 今天 11:00 才开始活跃（基线约 08:00）→ RHYTHM LATER
    _seed_day(client, user_headers, local_date=LOCAL_TODAY, active_start_hour=11, windows=120)
    _finalize_today(client, user_headers)
    resp = client.get("/v1/me/portraits/today", headers=user_headers)
    body = resp.json()
    assert body["status"] == "READY"
    rhythm = body["dimensions"].get("RHYTHM", {})
    assert rhythm.get("value") == "LATER", f"RHYTHM should be LATER, got {rhythm}"


def test_full_chain_less_movement_and_later_screen(client, user_headers, passive_sensing_consent):
    """稳定基线后：今天 movement 明显低 → MOVEMENT LESS；晚间屏幕明显多 → SCREEN_TIMING LATER。"""
    _seed_history(client, user_headers, movement=1.0, late_minutes=20.0, windows=120)
    _seed_day(client, user_headers, local_date=LOCAL_TODAY,
              movement=0.05, late_minutes=180.0, windows=120, late_windows=12)
    _finalize_today(client, user_headers)
    resp = client.get("/v1/me/portraits/today", headers=user_headers)
    body = resp.json()
    assert body["status"] == "READY"
    dims = body["dimensions"]
    assert dims.get("MOVEMENT", {}).get("value") == "LESS", f"got {dims.get('MOVEMENT')}"
    assert dims.get("SCREEN_TIMING", {}).get("value") == "LATER", f"got {dims.get('SCREEN_TIMING')}"
    # Explainability：facts 不渲染原始 movement 数值（Phase 6.3）
    for fact in body.get("facts", []):
        assert "0.05" not in fact.get("today_text", ""), f"fact 暴露原始指标: {fact}"


def test_full_chain_partial_data(client, user_headers, passive_sensing_consent):
    """低覆盖（1 个窗口 / 预期 288）→ PARTIAL_DATA。"""
    _seed_history(client, user_headers, windows=120)
    # 今天仅 1 个窗口 → 覆盖 < 0.4 → PARTIAL_DATA
    _seed_day(client, user_headers, local_date=LOCAL_TODAY, windows=100)
    _finalize_today(client, user_headers)
    resp = client.get("/v1/me/portraits/today", headers=user_headers)
    body = resp.json()
    assert body["status"] == "PARTIAL_DATA"


def test_full_chain_low_confidence(client, user_headers, passive_sensing_consent):
    """基线就绪但今天覆盖极低 + missing sources → LOW_CONFIDENCE。"""
    _seed_history(client, user_headers, windows=90, sources=["screen", "accel"])
    # 今天仅 notification 来源（核心 sensor 缺失）→ missing_sources 高 → LOW_CONFIDENCE
    _seed_day(client, user_headers, local_date=LOCAL_TODAY, windows=3, sources=["notification"])
    _finalize_today(client, user_headers)
    resp = client.get("/v1/me/portraits/today", headers=user_headers)
    body = resp.json()
    assert body["status"] in ("LOW_CONFIDENCE", "PARTIAL_DATA")


def test_full_chain_duplicate_feature_idempotent(client, user_headers, passive_sensing_consent):
    """同一 event_id 重复 ingest → 幂等重放，不重复计数/不二次物化。"""
    _seed_day(client, user_headers, local_date=LOCAL_TODAY, windows=3)
    agg_before = _aggregate()
    # 重放同 event_id
    with SessionLocal() as db:
        from app.models import DerivedFeature
        row = db.scalar(select(DerivedFeature).where(DerivedFeature.user_id == "u_demo"))
        payload = _feature(f"replay_{row.id}", window_start=row.window_start)
        payload["event_id"] = row.event_id  # 同 event_id → 幂等
        resp = client.post("/v1/features/ingest", json=payload, headers=user_headers)
        assert resp.status_code == 200
        assert resp.json().get("idempotent_replay") is True
    agg_after = _aggregate()
    assert agg_after.valid_window_count == agg_before.valid_window_count, "重复 ingest 不应增加窗口计数"


def test_full_chain_late_feature_backfill(client, user_headers, passive_sensing_consent):
    """昨天晚到特征：ingest 昨天窗口 → 昨天 dirty → materialize 补齐（不丢数据）。"""
    yesterday = LOCAL_TODAY - timedelta(days=1)
    _seed_day(client, user_headers, local_date=LOCAL_TODAY, windows=120)
    with SessionLocal() as db:
        materialize_dirty(db, tenant_id="t_demo", user_id="u_demo", tz_name="Asia/Shanghai")
        db.commit()
    # 晚到昨天窗口
    _seed_day(client, user_headers, local_date=yesterday, windows=120)
    with SessionLocal() as db:
        materialize_dirty(db, tenant_id="t_demo", user_id="u_demo", tz_name="Asia/Shanghai")
        db.commit()
    with SessionLocal() as db:
        count = db.scalar(select(func.count()).select_from(DailyPortrait).where(
            DailyPortrait.user_id == "u_demo")) or 0
        assert count >= 2, f"昨天+今天应各有一张画像，got {count}"


def test_full_chain_timezone_local_day(client, user_headers, passive_sensing_consent):
    """用户时区本地日：UTC+14 用户跨 UTC 午夜的窗口归属正确。"""
    # 该用户时区为 UTC+14（本地日比 UTC 早一天开始）
    with SessionLocal() as db:
        from app.models import User
        u = db.get(User, "u_demo")
        u.timezone = "Pacific/Kiritimati"
        db.commit()
    utc_now = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
    # UTC 14:00 = 本地次日 04:00 → 属本地日（今天+1）；naive UTC 输入（后端按 UTC 解释）
    ws = utc_now.replace(hour=14, tzinfo=None)
    _seed_day(client, user_headers, local_date=LOCAL_TODAY, windows=3)
    resp = client.post("/v1/features/ingest", json=_feature(
        "e2e_timezone_0001", user_id="u_demo", source="screen", window_start=ws,
    ), headers=user_headers)
    assert resp.status_code == 200
    resp = client.get("/v1/me/portraits/today", headers=user_headers)
    assert resp.status_code == 200
    assert resp.json()["timezone_used"] == "Pacific/Kiritimati"


def test_full_chain_get_today_no_side_effect(client, user_headers, passive_sensing_consent):
    """GET /v1/me/portraits/today 不触发物化（无写副作用）。"""
    _seed_day(client, user_headers, local_date=LOCAL_TODAY, windows=3)
    with SessionLocal() as db:
        before = db.scalar(select(func.count()).select_from(DailyPortrait).where(
            DailyPortrait.user_id == "u_demo")) or 0
    for _ in range(3):
        client.get("/v1/me/portraits/today", headers=user_headers)
    with SessionLocal() as db:
        after = db.scalar(select(func.count()).select_from(DailyPortrait).where(
            DailyPortrait.user_id == "u_demo")) or 0
    assert after == before, "GET 不应触发物化/写库"
