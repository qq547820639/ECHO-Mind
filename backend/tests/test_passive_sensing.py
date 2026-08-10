from datetime import datetime, timedelta, timezone

from app.auth import create_access_token
from app.database import SessionLocal
from app.models import DerivedFeature, Escalation, RiskSignal


def _feature_payload(event_id: str, summary: str, user_id: str = "u_demo", *, source: str = "screen",
                     window_start: datetime | None = None) -> dict:
    # 默认固定在当天 UTC 正午，避免跨日边界导致叙事日期不匹配
    start = window_start or datetime.now(timezone.utc).replace(hour=12, minute=0, second=0, microsecond=0)
    return {
        "event_id": event_id,
        "user_id": user_id,
        "schema_version": "feat-v1",
        "source": source,
        "window_start": start.isoformat(),
        "window_end": (start + timedelta(minutes=30)).isoformat(),
        "summary": summary,
        "vector": [0.1, 0.2, 0.3],
    }


def _grant_passive_consent(client, user_headers, user_id: str = "u_demo") -> None:
    response = client.post("/v1/onboarding/consents", json={
        "user_id": user_id,
        "consent_type": "passive_sensing",
        "version": "test-v1",
        "granted": True,
        "evidence_hash": "a" * 64,
    }, headers=user_headers)
    assert response.status_code == 200


def test_ingest_requires_auth(client):
    response = client.post("/v1/features/ingest", json=_feature_payload("evt_feat_0001", "屏幕使用平稳"))
    assert response.status_code == 401


def test_ingest_requires_passive_consent(client, user_headers):
    response = client.post("/v1/features/ingest",
                           json=_feature_payload("evt_feat_0002", "屏幕使用平稳"),
                           headers=user_headers)
    assert response.status_code == 412


def test_ingest_rejects_wrong_schema_version(client, user_headers):
    payload = _feature_payload("evt_feat_0003", "屏幕使用平稳")
    payload["schema_version"] = "feat-v0"
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 422


def test_cross_tenant_access_returns_404(client):
    outsider_headers = {"Authorization": f"Bearer {create_access_token('u_demo', 't_other', 'user')}"}
    response = client.post("/v1/features/ingest",
                           json=_feature_payload("evt_feat_0004", "屏幕使用平稳"),
                           headers=outsider_headers)
    assert response.status_code == 404


def test_ingest_with_sources_present_persists(client, user_headers):
    """ingest 携带 sources_present → 落库（供 gap_finder 覆盖度判断）。"""
    _grant_passive_consent(client, user_headers)
    payload = _feature_payload("evt_feat_sp_0001", "屏幕使用平稳")
    payload["sources_present"] = ["screen", "notification", "app_activity"]
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 200
    with SessionLocal() as db:
        row = db.query(DerivedFeature).filter(
            DerivedFeature.tenant_id == "t_demo",
            DerivedFeature.event_id == "evt_feat_sp_0001",
        ).one()
        assert sorted(row.sources_present) == ["app_activity", "notification", "screen"]


def test_ingest_rejects_invalid_sources_present(client, user_headers):
    """sources_present 含非白名单值 → 422（extra 校验）。"""
    _grant_passive_consent(client, user_headers)
    payload = _feature_payload("evt_feat_sp_0002", "屏幕使用平稳")
    payload["sources_present"] = ["screen", "camera"]
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 422


def test_ingest_then_narrative_read_only(client, user_headers):
    """ingest 写路径构建叙事；GET /v1/narratives 只读返回已有叙事。"""
    _grant_passive_consent(client, user_headers)
    first = client.post("/v1/features/ingest",
                        json=_feature_payload("evt_feat_0005", "夜间屏幕使用增多，情绪疲惫"),
                        headers=user_headers)
    assert first.status_code == 200
    assert first.json()["idempotent_replay"] is False
    replay = client.post("/v1/features/ingest",
                         json=_feature_payload("evt_feat_0005", "夜间屏幕使用增多，情绪疲惫"),
                         headers=user_headers)
    assert replay.json()["idempotent_replay"] is True

    narrative = client.get("/v1/narratives", params={"user_id": "u_demo"}, headers=user_headers)
    assert narrative.status_code == 200
    body = narrative.json()
    assert "mood_hint" not in body  # PRD 契约点 2：不再输出情绪标签
    assert len(body["events"]) == 1
    assert body["events"][0]["source"] == "screen"


def test_get_profile_read_only_no_side_effect(client, user_headers):
    """GET /profile 只读缓存：version 不变；未重建时 404。"""
    _grant_passive_consent(client, user_headers)
    client.post("/v1/features/ingest",
                json=_feature_payload("evt_feat_ro_0001", "屏幕使用平稳"),
                headers=user_headers)

    # 未显式重建 → 404（读不写库）
    missing = client.get("/v1/profile/u_demo", headers=user_headers)
    assert missing.status_code == 404

    # 显式重建 → version==1
    rebuilt = client.post("/v1/profile/u_demo/rebuild", headers=user_headers)
    assert rebuilt.status_code == 200
    assert rebuilt.json()["version"] == 1
    assert rebuilt.json()["traits"]["observation_days"] >= 1

    # GET 两次：version 不变（读无副作用）
    first = client.get("/v1/profile/u_demo", headers=user_headers)
    second = client.get("/v1/profile/u_demo", headers=user_headers)
    assert first.status_code == 200
    assert first.json()["version"] == 1
    assert second.json()["version"] == first.json()["version"]
    assert second.json()["traits"] == first.json()["traits"]


def test_profile_rebuild_is_explicit_and_increments_version(client, user_headers):
    """POST /profile/rebuild 显式重建并 version+1。"""
    _grant_passive_consent(client, user_headers)
    client.post("/v1/features/ingest",
                json=_feature_payload("evt_feat_rb_0001", "屏幕使用平稳"),
                headers=user_headers)
    r1 = client.post("/v1/profile/u_demo/rebuild", headers=user_headers)
    assert r1.status_code == 200
    assert r1.json()["version"] == 1
    r2 = client.post("/v1/profile/u_demo/rebuild", headers=user_headers)
    assert r2.status_code == 200
    assert r2.json()["version"] == 2


def test_bulk_narratives_from_to(client, user_headers):
    """GET /v1/narratives?from=&to= 批量：ordered narratives + coverage + missing dates。"""
    _grant_passive_consent(client, user_headers)
    today = datetime.now(timezone.utc).date()
    day1 = today - timedelta(days=2)
    day2 = today - timedelta(days=1)
    # 只入库 day1 与 day2 的特征（day3 缺）
    client.post("/v1/features/ingest",
                json=_feature_payload("evt_feat_bulk_0001", "屏幕使用平稳",
                                      window_start=datetime.combine(day1, datetime.min.time(), tzinfo=timezone.utc)),
                headers=user_headers)
    client.post("/v1/features/ingest",
                json=_feature_payload("evt_feat_bulk_0002", "夜间屏幕使用增多，情绪疲惫",
                                      window_start=datetime.combine(day2, datetime.min.time(), tzinfo=timezone.utc)),
                headers=user_headers)

    response = client.get("/v1/narratives", params={
        "user_id": "u_demo",
        "from": day1.isoformat(),
        "to": today.isoformat(),
    }, headers=user_headers)
    assert response.status_code == 200
    body = response.json()
    dates = [n["date"] for n in body["narratives"]]
    assert dates == sorted(dates)
    assert day1.isoformat() in dates
    assert day2.isoformat() in dates
    assert body["coverage"]["requested_days"] == 3
    assert body["coverage"]["present_days"] == 2
    assert body["coverage"]["coverage"] == round(2 / 3, 3)
    assert today.isoformat() in body["coverage"]["missing_dates"]
    # 事件不含情绪标签（PRD 契约点 2）
    for n in body["narratives"]:
        assert "mood_hint" not in n
        for e in n["events"]:
            assert "mood_hint" not in e


def test_bulk_narratives_invalid_range(client, user_headers):
    """from > to → 422。"""
    response = client.get("/v1/narratives", params={
        "user_id": "u_demo",
        "from": "2026-08-05",
        "to": "2026-08-01",
    }, headers=user_headers)
    assert response.status_code == 422


def test_red_summary_never_triggers_passive_escalation(client, user_headers):
    """PRD 契约点 1：ingest 对含 PASSIVE_RED_TERMS 的派生特征不触发危机链路。"""
    _grant_passive_consent(client, user_headers)
    response = client.post("/v1/features/ingest",
                           json=_feature_payload("evt_feat_0006", "输入内容多次提及结束生命"),
                           headers=user_headers)
    assert response.status_code == 200
    assert response.json()["escalation_id"] is None
    with SessionLocal() as db:
        signals = db.query(RiskSignal).filter(
            RiskSignal.tenant_id == "t_demo",
            RiskSignal.user_id == "u_demo",
        ).all()
        assert len(signals) == 0
        escalations = db.query(Escalation).filter(
            Escalation.tenant_id == "t_demo",
            Escalation.user_id == "u_demo",
        ).all()
        assert all(e.trigger != "passive_red_signal" for e in escalations)


def test_ingest_never_creates_risk_signal_or_escalation(client, user_headers):
    """结构性边界：/v1/features/ingest 永不创建 RiskSignal/Escalation（被动 RED 零容忍）。"""
    _grant_passive_consent(client, user_headers)
    for idx, source in enumerate(("accel", "gyro", "screen", "notification", "app_activity", "health")):
        payload = _feature_payload(f"evt_no_signal_{idx:02d}", "平稳", source=source)
        response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
        assert response.status_code == 200
        assert response.json()["escalation_id"] is None
    with SessionLocal() as db:
        assert db.query(RiskSignal).filter(
            RiskSignal.tenant_id == "t_demo", RiskSignal.user_id == "u_demo",
        ).count() == 0
        assert db.query(Escalation).filter(
            Escalation.tenant_id == "t_demo", Escalation.user_id == "u_demo",
        ).count() == 0


def test_audit_chain_remains_valid(client, user_headers):
    _grant_passive_consent(client, user_headers)
    client.post("/v1/features/ingest",
                json=_feature_payload("evt_feat_0007", "输入内容多次提及结束生命"),
                headers=user_headers)
    client.get("/v1/narratives", params={"user_id": "u_demo"}, headers=user_headers)
    client.get("/v1/profile/u_demo", headers=user_headers)
    auditor_headers = {"Authorization": f"Bearer {create_access_token('aud', 't_demo', 'auditor')}"}
    verify = client.get("/v1/audit/verify", headers=auditor_headers)
    assert verify.status_code == 200
    assert verify.json()["valid"] is True
