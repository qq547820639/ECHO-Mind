"""T13.3 后端沙箱 E2E 联调测试。

覆盖完整回路：
- grant passive_sensing → ingest 多条特征（含不同 source）→ GET /v1/narratives 验证叙事生成
  → POST /v1/sandbox/runs 触发沙箱 → GET /v1/sandbox/runs/{id} 验证 status=completed
  + gaps_found 非空 + skills_inducted≥0 → admin transition skill 到 reviewed
  → GET /v1/skills 验证用户能看到已下发 Skill → 验证 Skill 已脱敏

- Zero-Skill 冷启动：新用户首次沙箱运行，无历史 Skill，能生成首批候选 Tool。
"""
from datetime import datetime, timedelta, timezone

from app.database import SessionLocal
from app.models import Skill, Tenant, User
from app.services.sandbox.runner import SandboxRunner
from app.services.sandbox import schedule_sandbox_run


def _feature_payload(event_id: str, summary: str, source: str = "screen") -> dict:
    """构造合法的派生特征 payload。"""
    start = datetime.now(timezone.utc).replace(hour=12, minute=0, second=0, microsecond=0)
    return {
        "event_id": event_id,
        "user_id": "u_demo",
        "schema_version": "feat-v1",
        "source": source,
        "window_start": start.isoformat(),
        "window_end": (start + timedelta(minutes=30)).isoformat(),
        "summary": summary,
        "vector": [0.1, 0.2, 0.3],
    }


# ---------- T13.3 完整回路 ----------

def test_full_sandbox_e2e_loop(
    client, user_headers, passive_sensing_consent, admin_headers
):
    """完整回路：ingest → narratives → sandbox run → transition → skills 下发 → 脱敏验证。"""
    # 1. ingest 多条特征（含不同 source）
    for source, summary, eid in [
        ("screen", "夜间屏幕使用增多，情绪疲惫", "evt_e2e_screen_0001"),
        ("notification", "收到10条通知，社交类居多", "evt_e2e_notif_0001"),
        ("accel", "过去5分钟活动量低", "evt_e2e_accel_0001"),
    ]:
        r = client.post("/v1/features/ingest",
                        json=_feature_payload(eid, summary, source),
                        headers=user_headers)
        assert r.status_code == 200, f"ingest {source} 失败: {r.text}"

    # 2. GET /v1/narratives 验证叙事生成
    narrative = client.get("/v1/narratives", params={"user_id": "u_demo"}, headers=user_headers)
    assert narrative.status_code == 200
    narrative_body = narrative.json()
    assert len(narrative_body["events"]) >= 3  # 至少 3 条事件
    # 至少有一条 mood_hint=偏低（含"疲惫"）
    mood_hints = [e["mood_hint"] for e in narrative_body["events"]]
    assert "偏低" in mood_hints

    # 3. POST /v1/sandbox/runs 触发沙箱
    run_resp = client.post("/v1/sandbox/runs", json={"user_id": "u_demo"}, headers=admin_headers)
    assert run_resp.status_code == 200
    run_id = run_resp.json()["id"]
    assert run_resp.json()["status"] == "pending"

    # 4. 执行沙箱回路（SandboxRunner.execute）
    with SessionLocal() as db:
        runner = SandboxRunner(db, tenant_id="t_demo", user_id="u_demo", run_id=run_id)
        result = runner.execute()
        db.commit()
        assert result.status == "completed"
        assert result.error_message is None
        # gaps_found 非空（缺 gyro / app_activity / health / mic_opt 等 source）
        assert len(result.gaps_found) > 0
        # skills_inducted ≥ 0（至少有 Skill 被归纳）
        assert result.skills_inducted >= 0
        # 实际上完整回路应产生 Skill
        assert result.skills_inducted > 0
        skill_ids = [s.id for s in db.query(Skill).filter_by(
            tenant_id="t_demo", user_id="u_demo").all()]
        assert len(skill_ids) > 0

    # 5. GET /v1/sandbox/runs/{id} 验证状态
    run_get = client.get(f"/v1/sandbox/runs/{run_id}", headers=user_headers)
    assert run_get.status_code == 200
    run_body = run_get.json()
    assert run_body["status"] == "completed"
    assert len(run_body["gaps_found"]) > 0
    assert run_body["skills_inducted"] > 0

    # 6. 用户 GET /v1/skills 此时看不到（draft 不下发）
    skills_before = client.get("/v1/skills", headers=user_headers)
    assert skills_before.status_code == 200
    before_ids = [s["id"] for s in skills_before.json()["skills"]]
    for sid in skill_ids:
        assert sid not in before_ids  # draft 不下发

    # 7. admin transition 第一个 skill 到 reviewed
    trans = client.post(
        f"/v1/skills/{skill_ids[0]}/transition",
        json={"new_status": "reviewed"},
        headers=admin_headers,
    )
    assert trans.status_code == 200
    assert trans.json()["status"] == "reviewed"

    # 8. 用户 GET /v1/skills 现在能看到 reviewed skill
    skills_after = client.get("/v1/skills", headers=user_headers)
    assert skills_after.status_code == 200
    after_items = skills_after.json()["skills"]
    after_ids = [s["id"] for s in after_items]
    assert skill_ids[0] in after_ids

    # 9. 验证 Skill 已脱敏（无其他用户数据 / 无原始特征引用 / 无内部字段）
    target_skill = next(s for s in after_items if s["id"] == skill_ids[0])
    # 不含内部字段
    assert "content_hash" not in target_skill
    assert "tenant_id" not in target_skill
    # 状态正确
    assert target_skill["status"] == "reviewed"
    # trigger_conditions 不含原始特征 summary 引用
    for cond in target_skill.get("trigger_conditions", []):
        field = cond.get("field", "")
        assert not field.startswith("passive_feature.summary")
        assert not field.startswith("derived_feature")
        assert not field.startswith("feature.vector")
    # steps 不含内部引用键
    for step in target_skill.get("steps", []):
        assert "feature_id" not in step
        assert "source_user_id" not in step
        assert "gap_id" not in step
        assert "sandbox_run_id" not in step
    # guardrails 含三条安全底线
    assert len(target_skill["guardrails"]) >= 3


# ---------- T13.3 Zero-Skill 冷启动 ----------

def test_zero_skill_cold_start_e2e(client, admin_headers):
    """Zero-Skill 冷启动：新用户首次沙箱运行，无历史 Skill，能生成首批候选 Tool。

    流程：
    - 创建新用户 u_coldstart
    - admin 触发 sandbox run（无任何特征数据）
    - SandboxRunner.execute 完成
    - 验证产生首批 Skill（draft）+ Tool
    """
    # 1. 创建新用户 u_coldstart（直接写库，避免 onboarding 流程）
    with SessionLocal() as db:
        if not db.get(User, "u_coldstart"):
            db.add(User(id="u_coldstart", tenant_id="t_demo", external_ref="coldstart"))
            db.commit()

    # 2. 确认无任何历史 Skill / Tool
    with SessionLocal() as db:
        assert db.query(Skill).filter_by(
            tenant_id="t_demo", user_id="u_coldstart").count() == 0

    # 3. admin 触发 sandbox run
    run_resp = client.post(
        "/v1/sandbox/runs",
        json={"user_id": "u_coldstart"},
        headers=admin_headers,
    )
    assert run_resp.status_code == 200
    run_id = run_resp.json()["id"]

    # 4. 执行沙箱回路
    with SessionLocal() as db:
        runner = SandboxRunner(
            db, tenant_id="t_demo", user_id="u_coldstart", run_id=run_id
        )
        result = runner.execute()
        db.commit()

        # 5. 验证冷启动产生首批 Skill + Tool
        assert result.status == "completed"
        assert result.error_message is None
        # 无数据 → "无感知数据" 缺口
        assert "无感知数据" in result.gaps_found
        assert result.tools_generated > 0  # 生成候选 Tool
        assert result.skills_inducted > 0  # 归纳为 Skill

        # 6. 验证 Skill 已创建（draft 状态）
        skills = db.query(Skill).filter_by(
            tenant_id="t_demo", user_id="u_coldstart").all()
        assert len(skills) > 0
        for sk in skills:
            assert sk.status == "draft"
            assert sk.content_hash is not None
            assert len(sk.content_hash) == 64  # sha256 hex
            assert len(sk.guardrails) >= 3  # 三条安全底线
            assert len(sk.steps) > 0
            assert len(sk.trigger_conditions) > 0


def test_zero_skill_cold_start_idempotent(client, admin_headers):
    """Zero-Skill 冷启动幂等：重复运行不产生重复 Skill。"""
    # 1. 创建新用户 u_coldstart_idem
    with SessionLocal() as db:
        if not db.get(User, "u_coldstart_idem"):
            db.add(User(id="u_coldstart_idem", tenant_id="t_demo", external_ref="coldstart_idem"))
            db.commit()

    # 2. 第一次运行
    run1 = client.post(
        "/v1/sandbox/runs",
        json={"user_id": "u_coldstart_idem"},
        headers=admin_headers,
    )
    assert run1.status_code == 200
    run1_id = run1.json()["id"]

    with SessionLocal() as db:
        runner1 = SandboxRunner(
            db, tenant_id="t_demo", user_id="u_coldstart_idem", run_id=run1_id
        )
        runner1.execute()
        db.commit()
        skills_count_1 = db.query(Skill).filter_by(
            tenant_id="t_demo", user_id="u_coldstart_idem").count()

    # 3. 第二次运行（同一天，相同缺口）
    run2 = client.post(
        "/v1/sandbox/runs",
        json={"user_id": "u_coldstart_idem"},
        headers=admin_headers,
    )
    assert run2.status_code == 200
    # 幂等：同 tenant+user+date 返回同一 run
    assert run2.json()["id"] == run1_id

    with SessionLocal() as db:
        runner2 = SandboxRunner(
            db, tenant_id="t_demo", user_id="u_coldstart_idem", run_id=run1_id
        )
        runner2.execute()
        db.commit()
        skills_count_2 = db.query(Skill).filter_by(
            tenant_id="t_demo", user_id="u_coldstart_idem").count()

    # 4. Skill 数量不变（幂等）
    assert skills_count_2 == skills_count_1
    assert skills_count_1 > 0
