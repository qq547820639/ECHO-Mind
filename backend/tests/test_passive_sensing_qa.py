"""QA 独立补充边界用例：被动感知 → 画像（Phase 1）。"""
from datetime import datetime, timedelta, timezone

from app.auth import create_access_token
from app.database import SessionLocal
from app.models import DerivedFeature, RiskSignal


def _feature_payload(event_id: str, summary: str, user_id: str = "u_demo") -> dict:
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


def test_vector_too_long_returns_422(client, user_headers):
    # case A: vector 长度 > 256 → 422
    _grant_passive_consent(client, user_headers)
    payload = _feature_payload("evt_qa_0001", "屏幕使用平稳")
    payload["vector"] = [0.1] * 257
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 422


def test_summary_too_long_returns_422(client, user_headers):
    # case B: summary 长度 > 4000 → 422
    _grant_passive_consent(client, user_headers)
    payload = _feature_payload("evt_qa_0002", "长" * 4001)
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 422


def test_duplicate_event_id_is_idempotent(client, user_headers):
    # case C: 同一 event_id 两次 ingest → 第二次 idempotent_replay=True 且仅 1 行
    _grant_passive_consent(client, user_headers)
    first = client.post("/v1/features/ingest",
                        json=_feature_payload("evt_qa_0003", "屏幕使用平稳"),
                        headers=user_headers)
    assert first.status_code == 200
    assert first.json()["idempotent_replay"] is False
    second = client.post("/v1/features/ingest",
                         json=_feature_payload("evt_qa_0003", "屏幕使用平稳"),
                         headers=user_headers)
    assert second.status_code == 200
    assert second.json()["idempotent_replay"] is True
    assert second.json()["id"] == first.json()["id"]
    with SessionLocal() as db:
        rows = db.query(DerivedFeature).filter(
            DerivedFeature.tenant_id == "t_demo",
            DerivedFeature.event_id == "evt_qa_0003",
        ).all()
        assert len(rows) == 1


def test_cross_tenant_profile_returns_404(client):
    # case D: tenant A 的 token 访问 tenant B 用户的 profile → 404
    outsider_headers = {"Authorization": f"Bearer {create_access_token('u_demo', 't_other', 'user')}"}
    response = client.get("/v1/profile/u_demo", headers=outsider_headers)
    assert response.status_code == 404


def test_red_ingest_keeps_audit_chain_valid_and_single_risk_signal(client, user_headers):
    # case E: 红色 summary ingest 后审计链有效且 RiskSignal 恰好 1 条
    _grant_passive_consent(client, user_headers)
    response = client.post("/v1/features/ingest",
                           json=_feature_payload("evt_qa_0005", "输入内容多次提及结束生命"),
                           headers=user_headers)
    assert response.status_code == 200
    assert response.json()["escalation_id"]
    auditor_headers = {"Authorization": f"Bearer {create_access_token('aud', 't_demo', 'auditor')}"}
    verify = client.get("/v1/audit/verify", headers=auditor_headers)
    assert verify.status_code == 200
    assert verify.json()["valid"] is True
    with SessionLocal() as db:
        signals = db.query(RiskSignal).filter(
            RiskSignal.tenant_id == "t_demo",
            RiskSignal.user_id == "u_demo",
        ).all()
        assert len(signals) == 1
        assert signals[0].source == "passive_feature"
        assert signals[0].severity == "red"
