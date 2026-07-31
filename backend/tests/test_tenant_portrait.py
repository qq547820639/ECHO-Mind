"""P4 机构去标识群体画像测试。

覆盖：
- 聚合正确性（10 用户不同 mood_hint，验证分布 + observation_days 统计）
- 小桶合并（3 用户某 mood_hint → 合并到 "other"）
- 跨租户隔离（另一租户用户不出现）
- 角色权限（普通 user → 403；admin/professional/auditor → 200）
- 去标识：响应 body 不含单个 user_id/特征字段
"""
from datetime import datetime, timedelta, timezone

from app.database import SessionLocal
from app.models import DerivedFeature, Escalation, Skill, Tenant, User, UserProfile


def _seed_user(db, tenant_id: str, user_id: str, external_ref: str) -> None:
    db.add(User(id=user_id, tenant_id=tenant_id, external_ref=external_ref))


def _seed_profile(db, tenant_id: str, user_id: str, mood_hint: str, observation_days: int) -> None:
    db.add(UserProfile(
        tenant_id=tenant_id,
        user_id=user_id,
        traits={"recent_mood_hint": mood_hint, "observation_days": observation_days},
    ))


# ---------- 聚合正确性 ----------

def test_aggregation_correctness(client, admin_headers):
    """构造 10 个用户（5 平稳 / 5 偏低），验证 mood 分布与 observation_stats。"""
    with SessionLocal() as db:
        for i in range(5):
            _seed_user(db, "t_demo", f"u_agg_calm_{i}", f"calm_{i}")
            _seed_profile(db, "t_demo", f"u_agg_calm_{i}", "平稳", observation_days=i + 1)
        for i in range(5):
            _seed_user(db, "t_demo", f"u_agg_low_{i}", f"low_{i}")
            _seed_profile(db, "t_demo", f"u_agg_low_{i}", "偏低", observation_days=10 + i)
        db.commit()

    response = client.get("/v1/tenant/portrait", headers=admin_headers)
    assert response.status_code == 200
    body = response.json()

    # mood 分布：两个桶均 >= 5，不合并
    assert body["mood_distribution"] == {"平稳": 5, "偏低": 5}

    # observation_stats：1..14 的 min/max/avg/median
    stats = body["observation_stats"]
    assert stats["min"] == 1.0
    assert stats["max"] == 14.0
    # 1..14 共 14 个值，sum=105, avg=105/14=7.5, median=(7+8)/2=7.5
    assert stats["avg"] == 7.5
    assert stats["median"] == 7.5


# ---------- 小桶合并 ----------

def test_small_bucket_merged_to_other(client, admin_headers):
    """3 用户某 mood_hint（<5）合并到 "other"；5 用户平稳保留。"""
    with SessionLocal() as db:
        for i in range(5):
            _seed_user(db, "t_demo", f"u_sb_calm_{i}", f"sb_calm_{i}")
            _seed_profile(db, "t_demo", f"u_sb_calm_{i}", "平稳", observation_days=3)
        for i in range(3):
            _seed_user(db, "t_demo", f"u_sb_anx_{i}", f"sb_anx_{i}")
            _seed_profile(db, "t_demo", f"u_sb_anx_{i}", "焦虑", observation_days=2)
        db.commit()

    response = client.get("/v1/tenant/portrait", headers=admin_headers)
    assert response.status_code == 200
    dist = response.json()["mood_distribution"]
    # 平稳=5 保留；焦虑=3 < 5 合并到 other
    assert dist == {"平稳": 5, "other": 3}
    assert "焦虑" not in dist


# ---------- 跨租户隔离 ----------

def test_cross_tenant_isolation(client, admin_headers):
    """t_demo 的画像不含 t_other 租户的用户聚合。"""
    with SessionLocal() as db:
        # t_demo：5 平稳
        for i in range(5):
            _seed_user(db, "t_demo", f"u_iso_demo_{i}", f"iso_demo_{i}")
            _seed_profile(db, "t_demo", f"u_iso_demo_{i}", "平稳", observation_days=5)
        # t_other：5 偏低
        db.add(Tenant(id="t_other", name="Other"))
        for i in range(5):
            _seed_user(db, "t_other", f"u_iso_other_{i}", f"iso_other_{i}")
            _seed_profile(db, "t_other", f"u_iso_other_{i}", "偏低", observation_days=9)
        db.commit()

    response = client.get("/v1/tenant/portrait", headers=admin_headers)
    assert response.status_code == 200
    body = response.json()
    # t_demo admin 只看到本租户的平稳，不含 t_other 的偏低
    assert body["mood_distribution"] == {"平稳": 5}
    assert "偏低" not in body["mood_distribution"]


# ---------- 角色权限 ----------

def test_role_user_forbidden(client, user_headers):
    """普通 user 调用 → 403。"""
    response = client.get("/v1/tenant/portrait", headers=user_headers)
    assert response.status_code == 403


def test_role_admin_professional_auditor_allowed(client, admin_headers, professional_headers, auditor_headers):
    """admin / professional / auditor 调用 → 200。"""
    with SessionLocal() as db:
        for i in range(5):
            _seed_user(db, "t_demo", f"u_role_{i}", f"role_{i}")
            _seed_profile(db, "t_demo", f"u_role_{i}", "平稳", observation_days=4)
        db.commit()

    for headers in (admin_headers, professional_headers, auditor_headers):
        response = client.get("/v1/tenant/portrait", headers=headers)
        assert response.status_code == 200, f"role failed: {headers}"


# ---------- 去标识：不返回单个用户 ID/特征 ----------

def test_no_single_user_identifier_leaked(client, admin_headers):
    """响应 body 不含 user_id / traits / external_ref 等单用户字段。"""
    with SessionLocal() as db:
        for i in range(5):
            _seed_user(db, "t_demo", f"u_leak_{i}", f"leak_{i}")
            _seed_profile(db, "t_demo", f"u_leak_{i}", "平稳", observation_days=6)
        # 造一些活跃特征与 escalation（确保聚合非空但不含用户级字段）
        now = datetime.now(timezone.utc)
        db.add(DerivedFeature(
            tenant_id="t_demo", user_id="u_leak_0", event_id="evt_leak_df_0",
            schema_version="feat-v1", source="screen",
            window_start=now, window_end=now, summary="平稳",
        ))
        db.add(Escalation(
            tenant_id="t_demo", user_id="u_leak_0", event_id="evt_leak_esc_0",
            level="L3", status="open", trigger="test", evidence_summary="test",
        ))
        db.add(Skill(
            tenant_id="t_demo", user_id="u_leak_0", name="skill-leak", version=1,
            status="signed",
        ))
        db.commit()

    response = client.get("/v1/tenant/portrait", headers=admin_headers)
    assert response.status_code == 200
    body_text = response.text

    # 顶层 keys 应仅为 schema 定义的 5 个聚合字段
    body = response.json()
    assert set(body.keys()) == {
        "mood_distribution", "observation_stats",
        "active_users_7d", "escalation_metrics", "skill_count",
    }
    # 不应出现任何单用户标识
    for forbidden in ("user_id", "user_ids", "external_ref", "traits", "u_leak"):
        assert forbidden not in body_text, f"响应泄漏了单用户字段: {forbidden}"


# ---------- 辅助：active_users_7d / escalation / skill 聚合正确 ----------

def test_active_users_escalation_skill_aggregation(client, admin_headers):
    """验证 active_users_7d / escalation_metrics / skill_count 聚合与跨租户隔离。"""
    now = datetime.now(timezone.utc)
    old = now - timedelta(days=30)
    with SessionLocal() as db:
        # 3 个近 7 天活跃用户 + 1 个超期用户（不计入）
        for i in range(3):
            _seed_user(db, "t_demo", f"u_act_{i}", f"act_{i}")
            db.add(DerivedFeature(
                tenant_id="t_demo", user_id=f"u_act_{i}", event_id=f"evt_act_{i}",
                schema_version="feat-v1", source="screen",
                window_start=now, window_end=now, summary="平稳",
            ))
        _seed_user(db, "t_demo", "u_act_old", "act_old")
        db.add(DerivedFeature(
            tenant_id="t_demo", user_id="u_act_old", event_id="evt_act_old",
            schema_version="feat-v1", source="screen",
            window_start=old, window_end=old, summary="平稳",
        ))
        # 2 条 escalation（1 open L3 + 1 closed L2）
        db.add(Escalation(
            tenant_id="t_demo", user_id="u_act_0", event_id="evt_esc_open",
            level="L3", status="open", trigger="t", evidence_summary="s",
        ))
        db.add(Escalation(
            tenant_id="t_demo", user_id="u_act_1", event_id="evt_esc_closed",
            level="L2", status="closed", trigger="t", evidence_summary="s",
        ))
        # Skill：1 signed + 1 retired（draft 不计）
        db.add(Skill(
            tenant_id="t_demo", user_id="u_act_0", name="skill-a", version=1, status="signed",
        ))
        db.add(Skill(
            tenant_id="t_demo", user_id="u_act_1", name="skill-b", version=1, status="retired",
        ))
        db.add(Skill(
            tenant_id="t_demo", user_id="u_act_2", name="skill-c", version=1, status="draft",
        ))
        db.commit()

    response = client.get("/v1/tenant/portrait", headers=admin_headers)
    assert response.status_code == 200
    body = response.json()

    assert body["active_users_7d"] == 3  # 超期用户不计
    esc = body["escalation_metrics"]
    assert esc["total"] == 2
    assert esc["open"] == 1
    assert esc["closed"] == 1
    assert esc["level_l3"] == 1
    assert esc["level_l2"] == 1
    assert body["skill_count"] == {"reviewed": 0, "signed": 1, "retired": 1}
