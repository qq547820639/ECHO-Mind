"""Phase 2 测试：Automatic PortraitMaterializer。

覆盖：ingest 后 dirty 标记 + 自动 materialize（无需手动 rebuild）/ 同一天重复 ingest 幂等 /
late feature（昨天）补齐 / 失败重试（dirty 保留 → 重试成功）/ GET 无写副作用 /
用户与租户隔离 / 多日期一次补齐（catch-up）。
"""
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import func, select

from app.auth import create_access_token
from app.database import SessionLocal
from app.models import DailyPortrait, MaterializationState
from app.services.portrait import materializer

LOCAL_TODAY = datetime.now(timezone.utc).astimezone(ZoneInfo("Asia/Shanghai")).date()


def _feature_payload(event_id: str, *, user_id: str = "u_demo", source: str = "screen",
                     window_start: datetime | None = None) -> dict:
    # 默认 window_start 固定为「LOCAL_TODAY 正午（Asia/Shanghai）」并转 UTC，
    # 确保 local_date 恒等于 LOCAL_TODAY。原「UTC 正午」在 UTC 16:00 之后会落入
    # 上海「昨天」，与 materializer 的 today 判定漂移，导致 same_day/debounce 用例
    # 在 UTC 傍晚时段不稳定失败。
    if window_start is None:
        shanghai_noon = datetime(
            LOCAL_TODAY.year, LOCAL_TODAY.month, LOCAL_TODAY.day, 12, 0, 0,
            tzinfo=ZoneInfo("Asia/Shanghai"),
        )
        start = shanghai_noon.astimezone(timezone.utc)
    else:
        start = window_start
    return {
        "event_id": event_id,
        "user_id": user_id,
        "schema_version": "passive-core-v1",
        "source": source,
        "window_start": start.isoformat(),
        "window_end": (start + timedelta(minutes=30)).isoformat(),
        "summary": "屏幕使用平稳",
        "vector": [0.1, 0.2, 0.3],
    }


def _portrait_count(user_id: str = "u_demo") -> int:
    with SessionLocal() as db:
        return db.scalar(select(func.count()).select_from(DailyPortrait).where(
            DailyPortrait.user_id == user_id)) or 0


def _state_rows(user_id: str = "u_demo") -> list[MaterializationState]:
    with SessionLocal() as db:
        return db.scalars(select(MaterializationState).where(
            MaterializationState.user_id == user_id,
        ).order_by(MaterializationState.local_date)).all()


def test_ingest_auto_materializes_portrait(client, user_headers, passive_sensing_consent):
    """ingest 成功后自动生成 portrait（无需手动 rebuild），dirty 清除 + 版本递增。"""
    response = client.post("/v1/features/ingest", json=_feature_payload("evt_mz_auto_0001"),
                           headers=user_headers)
    assert response.status_code == 200
    assert response.json()["idempotent_replay"] is False
    assert _portrait_count("u_demo") == 1
    states = _state_rows("u_demo")
    assert len(states) == 1
    assert states[0].dirty is False
    assert states[0].last_materialized_at is not None
    assert states[0].materialization_version >= 1
    with SessionLocal() as db:
        row = db.scalar(select(DailyPortrait).where(DailyPortrait.user_id == "u_demo"))
        assert row is not None
        assert row.status in ("WARMING_UP", "EARLY_BASELINE", "READY", "PARTIAL_DATA", "LOW_CONFIDENCE")


def test_same_day_repeat_ingest_idempotent(client, user_headers, passive_sensing_consent):
    """同一天多次 ingest → dirty 合并为一行；portrait 幂等只一行。

    Phase 5（D）debounce：当天第二次 ingest 距上次物化 < 15 分钟 → 跳过物化
    （保留 dirty=true，下次触发再物化）；materialization_version 不递增。
    """
    r1 = client.post("/v1/features/ingest", json=_feature_payload("evt_mz_dup_0001"),
                     headers=user_headers)
    r2 = client.post("/v1/features/ingest", json=_feature_payload("evt_mz_dup_0002"),
                     headers=user_headers)
    assert r1.status_code == 200 and r2.status_code == 200
    assert _portrait_count("u_demo") == 1  # 幂等 upsert（同一天只一行画像）
    states = _state_rows("u_demo")
    assert len(states) == 1  # debounce：同一天合并为一行 dirty 状态
    # 第二次 ingest 在间隔内被合并：dirty 保留 true，等待下次触发
    assert states[0].dirty is True
    assert states[0].last_materialized_at is not None
    assert states[0].materialization_version == 1  # 间隔内未重复全量重建


def test_late_feature_yesterday_materialized(client, user_headers, passive_sensing_consent):
    """晚到 feature（昨天窗口）→ 昨天标记 dirty → materializer 补齐昨天画像。"""
    yesterday_noon_utc = (datetime.now(timezone.utc) - timedelta(days=1)).replace(
        hour=12, minute=0, second=0, microsecond=0)
    response = client.post("/v1/features/ingest",
                           json=_feature_payload("evt_mz_late_0001", window_start=yesterday_noon_utc),
                           headers=user_headers)
    assert response.status_code == 200
    expected = yesterday_noon_utc.astimezone(ZoneInfo("Asia/Shanghai")).date()
    with SessionLocal() as db:
        rows = db.scalars(select(DailyPortrait).where(DailyPortrait.user_id == "u_demo")).all()
        assert len(rows) == 1
        assert rows[0].local_date == expected
    states = _state_rows("u_demo")
    assert len(states) == 1
    assert states[0].local_date == expected
    assert states[0].dirty is False


def test_materialize_failure_keeps_dirty_then_retry(client, user_headers, passive_sensing_consent,
                                                    monkeypatch):
    """materialize 抛异常 → dirty 保留 true（可重试）；重试成功后清除。"""
    real_generate = materializer.generate_portrait
    calls = {"n": 0}

    def flaky(*args, **kwargs):
        calls["n"] += 1
        if calls["n"] == 1:
            raise RuntimeError("boom")
        return real_generate(*args, **kwargs)

    monkeypatch.setattr(materializer, "generate_portrait", flaky)
    first = client.post("/v1/features/ingest", json=_feature_payload("evt_mz_fail_0001"),
                        headers=user_headers)
    assert first.status_code == 200  # ingest 成功，materialize 失败被吞掉（独立事务）
    assert _portrait_count("u_demo") == 0
    states = _state_rows("u_demo")
    assert len(states) == 1
    assert states[0].dirty is True  # 失败 → dirty 保留，下次重试
    assert states[0].last_materialized_at is None

    # 重试：第二次 ingest（同一天另一窗口）触发成功
    second = client.post("/v1/features/ingest", json=_feature_payload("evt_mz_fail_0002"),
                         headers=user_headers)
    assert second.status_code == 200
    assert _portrait_count("u_demo") == 1
    states = _state_rows("u_demo")
    assert len(states) == 1
    assert states[0].dirty is False
    assert states[0].last_materialized_at is not None
    assert states[0].materialization_version >= 1


def test_me_today_get_does_not_trigger_materialize(client, user_headers, passive_sensing_consent):
    """GET /v1/me/portraits/today 无写副作用：不生成 portrait、不清除 dirty。"""
    client.post("/v1/features/ingest", json=_feature_payload("evt_mz_get_0001"), headers=user_headers)
    # 人为重新标记 dirty（模拟物化失败遗留状态）
    with SessionLocal() as db:
        materializer.mark_dirty(db, tenant_id="t_demo", user_id="u_demo", local_date=LOCAL_TODAY)
        db.commit()
    before = _portrait_count("u_demo")
    response = client.get("/v1/me/portraits/today", headers=user_headers)
    assert response.status_code == 200
    after = _portrait_count("u_demo")
    assert before == after
    states = _state_rows("u_demo")
    assert len(states) == 1
    assert states[0].dirty is True  # GET 不触发 materialize


def test_user_isolation_dirty_not_materialized_for_other(client, user_headers, passive_sensing_consent):
    """user A 的 ingest 只 materialize user A；user B 的 dirty 保持 true。"""
    with SessionLocal() as db:
        materializer.mark_dirty(db, tenant_id="t_demo", user_id="u_other", local_date=LOCAL_TODAY)
        db.commit()
    response = client.post("/v1/features/ingest", json=_feature_payload("evt_mz_iso_0001"),
                           headers=user_headers)
    assert response.status_code == 200
    assert _portrait_count("u_demo") == 1
    assert _portrait_count("u_other") == 0  # u_other 未被触发 materialize
    other_states = _state_rows("u_other")
    assert len(other_states) == 1
    assert other_states[0].dirty is True
    demo_states = _state_rows("u_demo")
    assert demo_states[0].dirty is False


def test_cross_tenant_ingest_does_not_materialize(client, passive_sensing_consent):
    """跨租户 principal 无法 ingest → 不产生 dirty / portrait（tenant isolation）。"""
    outsider = {"Authorization": f"Bearer {create_access_token('u_demo', 't_other', 'user')}"}
    response = client.post("/v1/features/ingest",
                           json=_feature_payload("evt_mz_xten_0001"), headers=outsider)
    assert response.status_code == 404
    assert _portrait_count("u_demo") == 0
    assert _state_rows("u_demo") == []


def test_materialize_dirty_multiple_dates_one_call():
    """一次 materialize_dirty 补齐多个 dirty 日期（catch-up / late features 合并）。"""
    today = LOCAL_TODAY
    yesterday = today - timedelta(days=1)
    with SessionLocal() as db:
        materializer.mark_dirty(db, tenant_id="t_demo", user_id="u_demo", local_date=yesterday)
        materializer.mark_dirty(db, tenant_id="t_demo", user_id="u_demo", local_date=today)
        db.commit()
    with SessionLocal() as db:
        rows = materializer.materialize_dirty(db, tenant_id="t_demo", user_id="u_demo",
                                              tz_name="Asia/Shanghai", today=today)
        db.commit()
        assert len(rows) == 2
    states = _state_rows("u_demo")
    assert len(states) == 2
    assert all(not s.dirty for s in states)
    with SessionLocal() as db:
        dates = db.scalars(select(DailyPortrait.local_date).where(
            DailyPortrait.user_id == "u_demo")).all()
        assert sorted(dates) == sorted([yesterday, today])


def test_mark_dirty_idempotent_coalesces_same_date():
    """mark_dirty 同一天调用多次只保留一行（dirty 合并）。"""
    with SessionLocal() as db:
        materializer.mark_dirty(db, tenant_id="t_demo", user_id="u_demo", local_date=LOCAL_TODAY)
        materializer.mark_dirty(db, tenant_id="t_demo", user_id="u_demo", local_date=LOCAL_TODAY)
        db.commit()
    states = _state_rows("u_demo")
    assert len(states) == 1
    assert states[0].dirty is True
    assert states[0].materialization_version == 0


# ---- Phase 5 (D)：materializer debounce ----

def test_same_day_debounce_limits_materializations():
    """当天间隔内重复触发 → 只物化一次（第二次跳过，保留 dirty）。"""
    with SessionLocal() as db:
        materializer.mark_dirty(db, tenant_id="t_demo", user_id="u_demo", local_date=LOCAL_TODAY)
        db.commit()
    # 首次物化（last_materialized_at None）→ 立即
    with SessionLocal() as db:
        rows = materializer.materialize_dirty(db, tenant_id="t_demo", user_id="u_demo",
                                              tz_name="Asia/Shanghai", today=LOCAL_TODAY)
        db.commit()
        assert len(rows) == 1
    # 间隔内再次置 dirty → 跳过（不重算 baseline），dirty 保留
    with SessionLocal() as db:
        materializer.mark_dirty(db, tenant_id="t_demo", user_id="u_demo", local_date=LOCAL_TODAY)
        db.commit()
    with SessionLocal() as db:
        rows = materializer.materialize_dirty(db, tenant_id="t_demo", user_id="u_demo",
                                              tz_name="Asia/Shanghai", today=LOCAL_TODAY)
        db.commit()
        assert rows == []
    states = _state_rows("u_demo")
    assert len(states) == 1
    assert states[0].dirty is True  # 间隔内合并 → 保留 dirty，下次触发再物化
    assert states[0].materialization_version == 1  # 物化次数未递增


def test_late_date_materializes_immediately_despite_recent():
    """晚到日期（< today）不受 debounce 限制：每次触发立即物化（backfill 不延迟）。"""
    yesterday = LOCAL_TODAY - timedelta(days=1)
    with SessionLocal() as db:
        materializer.mark_dirty(db, tenant_id="t_demo", user_id="u_demo", local_date=yesterday)
        db.commit()
    with SessionLocal() as db:
        rows = materializer.materialize_dirty(db, tenant_id="t_demo", user_id="u_demo",
                                              tz_name="Asia/Shanghai", today=LOCAL_TODAY)
        db.commit()
        assert len(rows) == 1
    # 再次置 dirty（晚到 backfill）→ 立即再物化（版本递增），不等待 15 分钟
    with SessionLocal() as db:
        materializer.mark_dirty(db, tenant_id="t_demo", user_id="u_demo", local_date=yesterday)
        db.commit()
    with SessionLocal() as db:
        rows = materializer.materialize_dirty(db, tenant_id="t_demo", user_id="u_demo",
                                              tz_name="Asia/Shanghai", today=LOCAL_TODAY)
        db.commit()
        assert len(rows) == 1
    states = _state_rows("u_demo")
    assert len(states) == 1
    assert states[0].dirty is False
    assert states[0].materialization_version == 2
    assert states[0].last_baseline_rebuild_at is not None


def test_mic_ingest_does_not_materialize(client, user_headers, passive_sensing_consent):
    """mic_opt（外围特征，aggregate_eligible=False）ingest 不触发聚合/物化。"""
    # grant voice_features consent（mic_opt ingest 的前置条件）
    grant = client.post("/v1/onboarding/consents", json={
        "user_id": "u_demo",
        "consent_type": "voice_features",
        "version": "voice-features-consent-2026.07",
        "granted": True,
        "evidence_hash": "v" * 64,
    }, headers=user_headers)
    assert grant.status_code == 200, grant.text
    start = datetime.now(timezone.utc).replace(hour=12, minute=0, second=0, microsecond=0)
    payload = {
        "event_id": "evt_mz_mic_0001",
        "user_id": "u_demo",
        "schema_version": "mic-feature-v1",
        "source": "mic_opt",
        "window_start": start.isoformat(),
        "window_end": (start + timedelta(minutes=30)).isoformat(),
        "summary": "音量平稳",
        "vector": [0.0] * 256,
    }
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 200
    assert _portrait_count("u_demo") == 0
    assert _state_rows("u_demo") == []
