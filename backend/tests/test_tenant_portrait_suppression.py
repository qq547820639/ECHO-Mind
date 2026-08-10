"""T04 租户画像小样本抑制测试（v0.6）。

覆盖 1/2/4/5/6 人边界：
- cohort 总人数 <5 → observation_stats 整维度 suppressed（隐藏 min/max 等可间接识别单人的统计）
- cohort >=5 → observation_stats 正常输出（min/max/avg/median），suppression=ok
- PRD 契约点 2：情绪语义废弃，mood_distribution 恒为空并标记 suppressed
- 不泄漏单用户标识
"""
from app.database import SessionLocal
from app.models import User, UserProfile


def _seed_profiles(entries: list[tuple[str, int]]) -> None:
    """entries: [(user_id, observation_days)]。"""
    with SessionLocal() as db:
        for user_id, observation_days in entries:
            db.add(User(id=user_id, tenant_id="t_demo", external_ref=user_id))
            db.add(UserProfile(
                tenant_id="t_demo",
                user_id=user_id,
                traits={"observation_days": observation_days},
            ))
        db.commit()


def _assert_suppressed(client, admin_headers, prefix: str, count: int) -> None:
    """cohort count(<5) → 敏感维度 suppressed。"""
    entries = [(f"{prefix}_{i}", i + 1) for i in range(count)]
    _seed_profiles(entries)
    response = client.get("/v1/tenant/portrait", headers=admin_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["observation_stats"] == {}, f"count={count} 应抑制 observation_stats"
    assert body["suppression"]["observation_stats"] == "suppressed"
    assert body["mood_distribution"] == {}  # 情绪语义废弃
    assert body["suppression"]["mood_distribution"] == "suppressed"


def test_cohort_1_suppresses_sensitive_dimensions(client, admin_headers):
    """1 人：敏感维度 suppressed。"""
    _assert_suppressed(client, admin_headers, "u_sup_1", 1)


def test_cohort_2_suppresses_sensitive_dimensions(client, admin_headers):
    """2 人：敏感维度 suppressed。"""
    _assert_suppressed(client, admin_headers, "u_sup_2", 2)


def test_cohort_4_suppresses_sensitive_dimensions(client, admin_headers):
    """4 人：敏感维度 suppressed。"""
    _assert_suppressed(client, admin_headers, "u_sup_4", 4)


def test_cohort_5_shows_observation_stats(client, admin_headers):
    """5 人：observation_stats 正常输出，suppression=ok；情绪维度仍 suppressed。"""
    _seed_profiles([(f"u_five_{i}", i + 1) for i in range(5)])
    response = client.get("/v1/tenant/portrait", headers=admin_headers)
    assert response.status_code == 200
    body = response.json()
    stats = body["observation_stats"]
    assert stats["min"] == 1.0
    assert stats["max"] == 5.0
    assert body["suppression"]["observation_stats"] == "ok"
    assert body["mood_distribution"] == {}
    assert body["suppression"]["mood_distribution"] == "suppressed"


def test_cohort_6_shows_observation_stats(client, admin_headers):
    """6 人：observation_stats 正常输出（min/max 不再隐藏）。"""
    _seed_profiles([(f"u_six_{i}", i * 2 + 1) for i in range(6)])
    response = client.get("/v1/tenant/portrait", headers=admin_headers)
    assert response.status_code == 200
    stats = response.json()["observation_stats"]
    assert stats["min"] == 1.0
    assert stats["max"] == 11.0
    assert response.json()["suppression"]["observation_stats"] == "ok"


def test_no_single_user_identifiers_in_suppressed_portrait(client, admin_headers):
    """抑制场景同样不泄漏单用户标识。"""
    _seed_profiles([("u_single", 1)])
    response = client.get("/v1/tenant/portrait", headers=admin_headers)
    assert response.status_code == 200
    text = response.text
    assert "u_single" not in text
    assert "user_id" not in text
