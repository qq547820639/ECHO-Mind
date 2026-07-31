from datetime import datetime, timedelta, timezone

from app.auth import create_access_token
from app.database import SessionLocal
from app.models import RiskSignal


def _feature_payload(event_id: str, summary: str, user_id: str = "u_demo") -> dict:
    # 固定在当天 UTC 正午，避免跨日边界导致叙事日期不匹配
    start = datetime.now(timezone.utc).replace(hour=12, minute=0, second=0, microsecond=0)
    return {
        "event_id": event_id,
        "user_id": user_id,
        "schema_version": "feat-v1",
        "source": "screen",
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


def test_ingest_then_profile_and_narrative(client, user_headers):
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
    assert body["mood_hint"] == "偏低"
    assert len(body["events"]) == 1
    assert body["events"][0]["source"] == "screen"

    profile = client.get("/v1/profile/u_demo", headers=user_headers)
    assert profile.status_code == 200
    traits = profile.json()["traits"]
    assert traits["observation_days"] >= 1
    assert profile.json()["version"] >= 1


def test_red_summary_creates_escalation_and_risk_signal(client, user_headers):
    _grant_passive_consent(client, user_headers)
    response = client.post("/v1/features/ingest",
                           json=_feature_payload("evt_feat_0006", "输入内容多次提及结束生命"),
                           headers=user_headers)
    assert response.status_code == 200
    assert response.json()["escalation_id"]
    with SessionLocal() as db:
        signals = db.query(RiskSignal).filter(
            RiskSignal.tenant_id == "t_demo",
            RiskSignal.user_id == "u_demo",
            RiskSignal.source == "passive_feature",
        ).all()
        assert len(signals) == 1
        assert signals[0].severity == "red"


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
