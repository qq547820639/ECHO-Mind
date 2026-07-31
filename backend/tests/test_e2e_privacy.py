"""T13.2 + T13.5 E2E 隐私验收测试。

覆盖：
- passive_sensing 同意撤回后 ingest 返回 412
- 被动 RED 审计哈希链跨多事件验证（safety.passive_red + escalation.open + feature.ingest）
- 跨租户 audit 链隔离（t_demo 不返回 t_other 的事件）
- DSR delete 对被动感知数据清理（DerivedFeature + RiskSignal）
- 隐私断言：ingest 请求体 schema 限定字段，不含原始 payload
- 隐私抓包：非法额外字段 / summary 超长 / vector 超维 → 422
- 隐私抓包：DerivedFeature 表不存原始 payload（只有 summary+vector）
"""
from datetime import datetime, timedelta, timezone

from app.database import SessionLocal
from app.models import AuditEvent, DerivedFeature, RiskSignal, Tenant, User


def _feature_payload(event_id: str, summary: str, user_id: str = "u_demo", source: str = "screen") -> dict:
    """构造合法的派生特征 payload（不含任何原始传感字段）。"""
    start = datetime.now(timezone.utc).replace(hour=12, minute=0, second=0, microsecond=0)
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


# ---------- T13.2 同意撤回 ----------

def test_passive_consent_revocation_blocks_ingest(client, user_headers, passive_sensing_consent):
    """passive_sensing consent 撤回后 ingest 返回 412。

    流程：grant passive_sensing → ingest 成功 → revoke → ingest 返回 412。
    """
    # 1. grant 后 ingest 成功（passive_sensing_consent fixture 已 grant）
    first = client.post("/v1/features/ingest",
                        json=_feature_payload("evt_priv_revoke_0001", "屏幕使用平稳"),
                        headers=user_headers)
    assert first.status_code == 200

    # 2. 撤回 passive_sensing 同意
    revoke = client.post("/v1/onboarding/consents", json={
        "user_id": "u_demo",
        "consent_type": "passive_sensing",
        "version": "test-v1",
        "granted": False,
        "evidence_hash": "b" * 64,
    }, headers=user_headers)
    assert revoke.status_code == 200

    # 3. 撤回后 ingest 返回 412
    second = client.post("/v1/features/ingest",
                         json=_feature_payload("evt_priv_revoke_0002", "屏幕使用平稳"),
                         headers=user_headers)
    assert second.status_code == 412


# ---------- T13.2 被动 RED 审计哈希链跨多事件验证 ----------

def test_passive_red_audit_chain_across_multiple_events(client, user_headers, passive_sensing_consent, auditor_headers):
    """连续 ingest 3 条特征（第 2 条含红色 summary），验证审计哈希链。

    事件序列应含：safety.passive_red + escalation.open + feature.ingest。
    verify_audit_chain 返回 valid=True + head_hash 非空 + events 数量正确。
    """
    # 1. 第一条：普通特征（无红色）
    r1 = client.post("/v1/features/ingest",
                     json=_feature_payload("evt_chain_0001", "屏幕使用平稳"),
                     headers=user_headers)
    assert r1.status_code == 200

    # 2. 第二条：红色 summary（含"结束生命"）
    r2 = client.post("/v1/features/ingest",
                     json=_feature_payload("evt_chain_0002", "输入内容多次提及结束生命"),
                     headers=user_headers)
    assert r2.status_code == 200
    assert r2.json()["escalation_id"]  # 触发升级

    # 3. 第三条：普通特征
    r3 = client.post("/v1/features/ingest",
                     json=_feature_payload("evt_chain_0003", "屏幕使用平稳", source="notification"),
                     headers=user_headers)
    assert r3.status_code == 200

    # 4. 校验审计链
    verify = client.get("/v1/audit/verify", headers=auditor_headers)
    assert verify.status_code == 200
    body = verify.json()
    assert body["valid"] is True
    assert body["head_hash"]  # 非空
    assert body["events"] >= 3  # 至少 3 条 ingest + 红色附加事件

    # 5. 事件序列含 safety.passive_red + escalation.open + feature.ingest
    with SessionLocal() as db:
        actions = [e.action for e in db.query(AuditEvent).filter(
            AuditEvent.tenant_id == "t_demo"
        ).order_by(AuditEvent.occurred_at.asc()).all()]
    assert "feature.ingest" in actions
    assert "safety.passive_red" in actions
    assert "escalation.open" in actions


# ---------- T13.2 跨租户 audit 链隔离 ----------

def test_audit_chain_cross_tenant_isolation(client, user_headers, passive_sensing_consent, auditor_headers):
    """t_demo 租户的 verify_audit_chain 不返回 t_other 租户的事件。"""
    # 1. 在 t_demo 租户产生若干审计事件
    client.post("/v1/features/ingest",
                json=_feature_payload("evt_iso_demo_0001", "屏幕使用平稳"),
                headers=user_headers)

    # 2. 在 t_other 租户播种审计事件（直接写库）
    with SessionLocal() as db:
        if not db.get(Tenant, "t_other"):
            db.add(Tenant(id="t_other", name="Other"))
            db.add(User(id="u_other_t", tenant_id="t_other", external_ref="other_t"))
            db.commit()
        from app.services.audit import append_audit
        append_audit(db, tenant_id="t_other", actor_type="user", actor_id="u_other_t",
                     action="feature.ingest", object_type="derived_feature", object_id="df_other")
        db.commit()

    # 3. t_demo 租户的审计链不包含 t_other 的事件
    verify = client.get("/v1/audit/verify", headers=auditor_headers)
    assert verify.status_code == 200
    body = verify.json()
    assert body["tenant_id"] == "t_demo"
    assert body["valid"] is True

    with SessionLocal() as db:
        demo_events = db.query(AuditEvent).filter(AuditEvent.tenant_id == "t_demo").count()
        other_events = db.query(AuditEvent).filter(AuditEvent.tenant_id == "t_other").count()
    assert other_events > 0  # t_other 有事件
    assert body["events"] == demo_events  # t_demo 审计链只统计 t_demo 事件
    assert body["events"] != other_events  # 与 t_other 事件数不同（隔离）


# ---------- T13.2 DSR delete 对被动感知数据清理 ----------

def test_dsr_delete_cleans_passive_sensing_data(client, user_headers, passive_sensing_consent, admin_headers):
    """DSR delete 完成后 DerivedFeature + RiskSignal 已清理。

    流程：ingest 特征（含红色）→ 发起 DSR delete → complete → 验证数据已清理。
    审计链（AuditEvent）按合规要求保留不可删除。
    """
    # 1. ingest 一条红色特征（产生 DerivedFeature + RiskSignal）
    ingest = client.post("/v1/features/ingest",
                         json=_feature_payload("evt_dsr_delete_0001", "输入内容多次提及结束生命"),
                         headers=user_headers)
    assert ingest.status_code == 200
    assert ingest.json()["escalation_id"]

    # 2. 验证数据已落库
    with SessionLocal() as db:
        assert db.query(DerivedFeature).filter_by(
            tenant_id="t_demo", user_id="u_demo").count() >= 1
        assert db.query(RiskSignal).filter_by(
            tenant_id="t_demo", user_id="u_demo").count() >= 1

    # 3. 发起 DSR delete
    dsr = client.post("/v1/data-subject-requests", json={
        "event_id": "evt_dsr_delete_req_0001",
        "user_id": "u_demo",
        "request_type": "delete",
    }, headers=user_headers)
    assert dsr.status_code == 200
    dsr_id = dsr.json()["id"]

    # 4. admin complete DSR
    done = client.post(f"/v1/data-subject-requests/{dsr_id}/complete", json={
        "result_summary": "已清理被动感知数据",
    }, headers=admin_headers)
    assert done.status_code == 200
    assert done.json()["status"] == "completed"

    # 5. 验证 DerivedFeature + RiskSignal 已清理
    with SessionLocal() as db:
        assert db.query(DerivedFeature).filter_by(
            tenant_id="t_demo", user_id="u_demo").count() == 0
        assert db.query(RiskSignal).filter_by(
            tenant_id="t_demo", user_id="u_demo").count() == 0
        # 审计链保留（合规要求）
        assert db.query(AuditEvent).filter_by(
            tenant_id="t_demo").count() > 0


# ---------- T13.2 隐私断言：ingest 请求体 schema 限定字段 ----------

def test_ingest_schema_only_allows_defined_fields(client, user_headers, passive_sensing_consent):
    """POST /v1/features/ingest 请求体 schema 只含定义字段，不含原始传感 payload。

    合法字段集合：{schema_version, source, window_start, window_end, summary, vector, event_id, user_id}
    不含：audio_buffer / raw_samples / payload / sensor_data 等原始传感字段。
    """
    from app.schemas import DerivedFeatureIn

    # Pydantic schema 字段集合
    schema_fields = set(DerivedFeatureIn.model_fields.keys())
    expected_fields = {
        "schema_version", "source", "window_start", "window_end",
        "summary", "vector", "event_id", "user_id",
    }
    assert schema_fields == expected_fields, (
        f"DerivedFeatureIn 字段集合应为 {expected_fields}，实际为 {schema_fields}"
    )

    # 原始传感字段不在 schema 中
    forbidden_fields = {"audio_buffer", "raw_samples", "payload", "sensor_data", "mic_recording"}
    assert not (schema_fields & forbidden_fields), (
        f"DerivedFeatureIn 不应包含原始传感字段：{schema_fields & forbidden_fields}"
    )


# ---------- T13.5 隐私抓包验证 ----------

def test_ingest_rejects_raw_audio_buffer_field(client, user_headers, passive_sensing_consent):
    """含"原始音频 buffer"字段的非法请求 → 422（Pydantic 拒绝额外字段）。"""
    payload = _feature_payload("evt_priv_audio_0001", "屏幕使用平稳")
    payload["audio_buffer"] = [0.1, 0.2, 0.3] * 1000  # 原始音频 buffer
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 422


def test_ingest_rejects_raw_samples_field(client, user_headers, passive_sensing_consent):
    """含"raw_samples"字段的非法请求 → 422。"""
    payload = _feature_payload("evt_priv_raw_0001", "屏幕使用平稳")
    payload["raw_samples"] = [{"x": 0.1, "y": 0.2, "z": 9.8}] * 500
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 422


def test_ingest_rejects_oversized_summary(client, user_headers, passive_sensing_consent):
    """summary 超长（>4000 字）→ 422。"""
    payload = _feature_payload("evt_priv_long_0001", "长" * 4001)
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 422


def test_ingest_rejects_oversized_vector(client, user_headers, passive_sensing_consent):
    """vector 超长（>256 维）→ 422。"""
    payload = _feature_payload("evt_priv_vec_0001", "屏幕使用平稳")
    payload["vector"] = [0.1] * 257
    response = client.post("/v1/features/ingest", json=payload, headers=user_headers)
    assert response.status_code == 422


def test_derived_feature_table_stores_no_raw_payload(client, user_headers, passive_sensing_consent):
    """DerivedFeature 表不存原始 payload（只有 summary + vector）。

    ingest 后查询数据库，确认 DerivedFeature 行只有 summary + vector，
    不含 audio_buffer / raw_samples 等原始字段。
    """
    response = client.post("/v1/features/ingest",
                           json=_feature_payload("evt_priv_db_0001", "屏幕使用平稳"),
                           headers=user_headers)
    assert response.status_code == 200

    with SessionLocal() as db:
        rows = db.query(DerivedFeature).filter_by(
            tenant_id="t_demo", user_id="u_demo"
        ).all()
        assert len(rows) >= 1
        for row in rows:
            # DerivedFeature 模型字段集合
            columns = {c.name for c in row.__table__.columns}
            # 不应存在原始 payload 字段
            forbidden_columns = {"audio_buffer", "raw_samples", "payload", "sensor_data"}
            assert not (columns & forbidden_columns), (
                f"DerivedFeature 表不应包含原始 payload 字段：{columns & forbidden_columns}"
            )
            # 应存在 summary + vector
            assert hasattr(row, "summary")
            assert hasattr(row, "vector")
            assert row.summary  # 非空
            assert row.vector  # 非空
