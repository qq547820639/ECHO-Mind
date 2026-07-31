"""P1.5 麦克风授权证据闭环：mic_opt 派生特征 voice_features consent 校验。

覆盖 spec 中三场景：
- mic_opt 特征无 voice_features consent → 412
- 有 voice_features consent（granted=true）→ 200（路由返回 200，spec 文案表述 201 实际为 200）
- consent 撤销（granted=false）后 → 412
- 非 mic_opt source（如 motion / screen）不要求 voice_features consent → 200

参考：
- tests/test_e2e_privacy.py 同意撤回模式（grant → revoke → 412）
- tests/conftest.py passive_sensing_consent fixture
- app/api/routes.py require_voice_features_consent
"""
from datetime import datetime, timedelta, timezone


VOICE_FEATURES_VERSION = "voice-features-consent-2026.07"


def _feature_payload(event_id: str, source: str = "mic_opt", user_id: str = "u_demo") -> dict:
    """构造派生特征 payload，source 默认 mic_opt（麦克风派生特征）。"""
    start = datetime.now(timezone.utc).replace(hour=12, minute=0, second=0, microsecond=0)
    return {
        "event_id": event_id,
        "user_id": user_id,
        "schema_version": "feat-v1",
        "source": source,
        "window_start": start.isoformat(),
        "window_end": (start + timedelta(minutes=30)).isoformat(),
        "summary": "音量平稳，语速正常",
        "vector": [0.1, 0.2, 0.3],
    }


def _grant_voice_features_consent(client, headers, user_id: str = "u_demo", granted: bool = True) -> None:
    """写入 voice_features consent（granted=true 或 false）。"""
    response = client.post("/v1/onboarding/consents", json={
        "user_id": user_id,
        "consent_type": "voice_features",
        "version": VOICE_FEATURES_VERSION,
        "granted": granted,
        "evidence_hash": "v" * 64,
    }, headers=headers)
    assert response.status_code == 200, response.text


# ---------- P1.5 mic_opt 无 voice_features consent → 412 ----------

def test_mic_opt_ingest_without_voice_features_consent_returns_412(
    client, user_headers, passive_sensing_consent
):
    """mic_opt 派生特征未授予 voice_features consent → 412。

    passive_sensing_consent fixture 已 grant passive_sensing consent，
    所以本测试聚焦 voice_features 这一层额外校验。
    """
    response = client.post("/v1/features/ingest",
                           json=_feature_payload("evt_voice_noccc_0001", source="mic_opt"),
                           headers=user_headers)
    assert response.status_code == 412
    assert response.json()["detail"] == "active voice-features consent required"


# ---------- P1.5 有 voice_features consent（granted=true）→ 200 ----------

def test_mic_opt_ingest_with_voice_features_consent_returns_200(
    client, user_headers, passive_sensing_consent
):
    """granted=true 后 mic_opt 特征可正常 ingest（路由返回 200）。

    spec 文案表述 201，实际路由返回 200（同 passive_sensing 风格）。
    """
    _grant_voice_features_consent(client, user_headers, granted=True)
    response = client.post("/v1/features/ingest",
                           json=_feature_payload("evt_voice_grant_0001", source="mic_opt"),
                           headers=user_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["idempotent_replay"] is False
    assert body["escalation_id"] is None


# ---------- P1.5 consent 撤销（granted=false）后 → 412 ----------

def test_mic_opt_ingest_after_voice_features_consent_revoked_returns_412(
    client, user_headers, passive_sensing_consent
):
    """先 grant voice_features → ingest 成功 → revoke → ingest 返回 412。"""
    # 1. grant voice_features
    _grant_voice_features_consent(client, user_headers, granted=True)
    # 2. ingest 成功
    first = client.post("/v1/features/ingest",
                        json=_feature_payload("evt_voice_revoke_0001", source="mic_opt"),
                        headers=user_headers)
    assert first.status_code == 200
    # 3. 撤回 voice_features consent
    _grant_voice_features_consent(client, user_headers, granted=False)
    # 4. 撤回后 ingest → 412
    second = client.post("/v1/features/ingest",
                         json=_feature_payload("evt_voice_revoke_0002", source="mic_opt"),
                         headers=user_headers)
    assert second.status_code == 412
    assert second.json()["detail"] == "active voice-features consent required"


# ---------- P1.5 非 mic_opt source 不要求 voice_features consent → 200 ----------

def test_non_mic_opt_source_does_not_require_voice_features_consent(
    client, user_headers, passive_sensing_consent
):
    """motion / screen / notification 等 source 不要求 voice_features consent。

    只要 passive_sensing consent granted=true 即可 ingest 成功。
    """
    # 不 grant voice_features consent
    for source in ("screen", "notification", "accel"):
        response = client.post("/v1/features/ingest",
                               json=_feature_payload(f"evt_voice_other_{source}", source=source),
                               headers=user_headers)
        assert response.status_code == 200, f"source={source} 应不要求 voice_features consent"


# ---------- P1.5 voice_features consent 不影响 passive_sensing 校验顺序 ----------

def test_mic_opt_ingest_without_any_consent_returns_412_passive_first(
    client, user_headers
):
    """无 passive_sensing 也无 voice_features consent 时返回 412（passive_sensing 先校验）。

    验证校验顺序：passive_sensing 先于 voice_features。
    """
    response = client.post("/v1/features/ingest",
                           json=_feature_payload("evt_voice_order_0001", source="mic_opt"),
                           headers=user_headers)
    assert response.status_code == 412
    assert response.json()["detail"] == "active passive-sensing consent required"
