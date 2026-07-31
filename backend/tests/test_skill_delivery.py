"""T10 Skill 合成 + 脱敏 + 下发测试。

覆盖场景：
- 脱敏断言：sanitize_skill 移除原始特征引用 / 内部 ID / 其他用户数据
- 权限隔离：普通用户只能看自己的 reviewed/signed Skill，看不到 draft
- 状态下发控制：draft 状态的 Skill 不出现在 GET /v1/skills
- 跨租户 404：GET /v1/skills/{id} 跨租户返回 404
- 状态转换：admin 能 draft→reviewed，不能 draft→signed（跳级拒绝）；professional 能 reviewed→signed
- 完整流程：沙箱创建 Skill(draft) → admin transition 到 reviewed → 用户 GET /v1/skills 能看到
"""
from datetime import datetime, timezone

from sqlalchemy import select

from app.auth import create_access_token
from app.database import SessionLocal
from app.models import Skill, UserProfile
from app.services.sandbox import schedule_sandbox_run
from app.services.sandbox.runner import SandboxRunner
from app.services.sandbox.sanitizer import sanitize_skill


def _admin_headers() -> dict:
    return {"Authorization": f"Bearer {create_access_token('admin', 't_demo', 'admin')}"}


def _professional_headers() -> dict:
    return {"Authorization": f"Bearer {create_access_token('pro', 't_demo', 'professional')}"}


def _other_tenant_headers() -> dict:
    return {"Authorization": f"Bearer {create_access_token('admin', 't_other', 'admin')}"}


def _make_skill(
    *,
    tenant_id: str = "t_demo",
    user_id: str = "u_demo",
    name: str = "test_skill",
    version: int = 1,
    status: str = "draft",
    trigger_conditions: list | None = None,
    guardrails: list | None = None,
    steps: list | None = None,
) -> Skill:
    """直接在 DB 创建一个 Skill（不走沙箱回路，便于精确控制字段）。"""
    if trigger_conditions is None:
        trigger_conditions = [
            {
                "field": "narrative.mood_hint",
                "op": "eq",
                "value": "偏低",
                "reason": "由缺口 no_data 触发",  # 含 SandboxRun 内部 gap_id
            },
            {
                "field": "passive_feature.summary",  # 原始特征 summary 引用
                "op": "contains_any",
                "value": ["疲惫", "压力"],
                "reason": "被动特征摘要触发",
            },
        ]
    if guardrails is None:
        guardrails = ["不输出诊断结论", "不替代专业医疗", "命中红色信号立即冻结"]
    if steps is None:
        steps = [
            {
                "key": "scan",
                "description": "扫描当日特征",
                "summary": "用户夜间屏幕使用增多",  # 原始特征 summary 引用
                "feature_id": "df_xxx",  # 原始 DerivedFeature 引用
                "source_user_id": "u_other",  # 其他用户数据引用
            },
            {
                "key": "report",
                "description": "输出报告",
                "gap_id": "no_data",  # SandboxRun 内部 ID
            },
        ]
    with SessionLocal() as db:
        skill = Skill(
            tenant_id=tenant_id,
            user_id=user_id,
            name=name,
            version=version,
            trigger_conditions=trigger_conditions,
            guardrails=guardrails,
            steps=steps,
            status=status,
            content_hash="0" * 64,
        )
        db.add(skill)
        db.commit()
        db.refresh(skill)
        return skill


# ---------- T10.4 脱敏断言 ----------

def test_sanitize_skill_removes_raw_feature_references():
    """sanitize_skill 移除引用 passive_feature.summary 的 trigger_conditions。"""
    skill = _make_skill(name="sanitize_raw_feat")
    with SessionLocal() as db:
        db_skill = db.get(Skill, skill.id)
        result = sanitize_skill(db_skill)

    # passive_feature.summary 条目被过滤掉
    trigger_fields = [c["field"] for c in result["trigger_conditions"]]
    assert "passive_feature.summary" not in trigger_fields
    # narrative.mood_hint 这类能力描述保留
    assert "narrative.mood_hint" in trigger_fields


def test_sanitize_skill_strips_internal_refs_and_other_user_data():
    """sanitize_skill 移除 reason / gap_id / feature_id / summary / source_user_id 等内部引用。"""
    skill = _make_skill(name="sanitize_internal")
    with SessionLocal() as db:
        db_skill = db.get(Skill, skill.id)
        result = sanitize_skill(db_skill)

    # trigger_conditions 不含 reason（SandboxRun 内部 gap_id 引用）
    for cond in result["trigger_conditions"]:
        assert "reason" not in cond

    # steps 不含内部引用键
    for step in result["steps"]:
        assert "summary" not in step
        assert "feature_id" not in step
        assert "source_user_id" not in step
        assert "gap_id" not in step

    # 序列化后整体不含其他用户数据（u_other）
    import json
    blob = json.dumps(result, ensure_ascii=False, default=str)
    assert "u_other" not in blob
    assert "df_xxx" not in blob
    assert "no_data" not in blob


def test_sanitize_skill_keeps_user_own_id_and_capability():
    """sanitize_skill 保留用户自己的 user_id 和能力描述字段。"""
    skill = _make_skill(name="sanitize_keep", user_id="u_demo")
    with SessionLocal() as db:
        db_skill = db.get(Skill, skill.id)
        result = sanitize_skill(db_skill)

    assert result["user_id"] == "u_demo"
    assert result["name"] == "sanitize_keep"
    assert result["status"] == "draft"
    assert len(result["guardrails"]) == 3
    # 移除 content_hash / tenant_id（内部字段）
    assert "content_hash" not in result
    assert "tenant_id" not in result


# ---------- T10.4 权限隔离 + 状态下发控制 ----------

def test_user_lists_only_own_reviewed_signed_skills(client, user_headers):
    """普通用户只能看自己的 reviewed/signed Skill，看不到 draft / retired。"""
    # u_demo: reviewed + draft + retired
    _make_skill(name="own_reviewed", status="reviewed", user_id="u_demo")
    _make_skill(name="own_draft", status="draft", user_id="u_demo")
    _make_skill(name="own_retired", status="retired", user_id="u_demo")
    # u_other: reviewed（其他用户的不应出现）
    _make_skill(name="other_reviewed", status="reviewed", user_id="u_other")

    response = client.get("/v1/skills", headers=user_headers)
    assert response.status_code == 200
    items = response.json()["skills"]
    names = [item["name"] for item in items]
    assert "own_reviewed" in names
    # draft / retired 不下发
    assert "own_draft" not in names
    assert "own_retired" not in names
    # 其他用户的不出现
    assert "other_reviewed" not in names
    # 所有返回项都属于 u_demo
    for item in items:
        assert item["user_id"] == "u_demo"


def test_draft_skill_not_in_delivery(client, user_headers):
    """draft 状态的 Skill 不出现在 GET /v1/skills。"""
    _make_skill(name="hidden_draft", status="draft", user_id="u_demo")
    response = client.get("/v1/skills", headers=user_headers)
    assert response.status_code == 200
    names = [item["name"] for item in response.json()["skills"]]
    assert "hidden_draft" not in names


def test_user_cannot_view_draft_skill_detail(client, user_headers):
    """用户直接 GET /v1/skills/{id} 拉 draft Skill 返回 404（不下发）。"""
    skill = _make_skill(name="secret_draft", status="draft", user_id="u_demo")
    response = client.get(f"/v1/skills/{skill.id}", headers=user_headers)
    assert response.status_code == 404


def test_user_can_view_reviewed_skill_detail(client, user_headers):
    """用户能 GET /v1/skills/{id} 拉 reviewed Skill 详情（脱敏后）。"""
    skill = _make_skill(name="visible_reviewed", status="reviewed", user_id="u_demo")
    response = client.get(f"/v1/skills/{skill.id}", headers=user_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["id"] == skill.id
    assert body["status"] == "reviewed"
    # 脱敏后不含内部字段
    assert "content_hash" not in body
    assert "tenant_id" not in body


# ---------- T10.4 跨租户 404 ----------

def test_get_skill_cross_tenant_404(client):
    """GET /v1/skills/{id} 跨租户返回 404。"""
    skill = _make_skill(name="cross_tenant_skill", status="reviewed", user_id="u_demo")
    response = client.get(f"/v1/skills/{skill.id}", headers=_other_tenant_headers())
    assert response.status_code == 404


def test_list_skills_cross_tenant_isolation(client):
    """跨租户 admin 拉取 /v1/skills 看不到 t_demo 的 Skill。"""
    _make_skill(name="tenant_iso_reviewed", status="reviewed", user_id="u_demo")
    # t_other 租户需要有自己的用户才能调用 ensure_user
    with SessionLocal() as db:
        from app.models import Tenant, User
        if not db.get(Tenant, "t_other"):
            db.add(Tenant(id="t_other", name="Other"))
        if not db.get(User, "u_other_tenant"):
            db.add(User(id="u_other_tenant", tenant_id="t_other", external_ref="other_tenant"))
        db.commit()
    response = client.get(
        "/v1/skills?user_id=u_other_tenant",
        headers=_other_tenant_headers(),
    )
    assert response.status_code == 200
    names = [item["name"] for item in response.json()["skills"]]
    # t_demo 的 Skill 不会泄漏到 t_other
    assert "tenant_iso_reviewed" not in names


# ---------- T10.4 状态转换 ----------

def test_admin_can_transition_draft_to_reviewed(client):
    """admin 能 draft→reviewed。"""
    skill = _make_skill(name="trans_draft_reviewed", status="draft", user_id="u_demo")
    response = client.post(
        f"/v1/skills/{skill.id}/transition",
        json={"new_status": "reviewed"},
        headers=_admin_headers(),
    )
    assert response.status_code == 200
    assert response.json()["status"] == "reviewed"
    assert response.json()["previous_status"] == "draft"


def test_cannot_skip_transition_draft_to_signed(client):
    """draft→signed 跳级拒绝（409）。"""
    skill = _make_skill(name="trans_skip", status="draft", user_id="u_demo")
    response = client.post(
        f"/v1/skills/{skill.id}/transition",
        json={"new_status": "signed"},
        headers=_admin_headers(),
    )
    assert response.status_code == 409


def test_cannot_reverse_transition_signed_to_reviewed(client):
    """signed→reviewed 逆转拒绝（409）。"""
    skill = _make_skill(name="trans_reverse", status="signed", user_id="u_demo")
    response = client.post(
        f"/v1/skills/{skill.id}/transition",
        json={"new_status": "reviewed"},
        headers=_admin_headers(),
    )
    assert response.status_code == 409


def test_professional_can_transition_reviewed_to_signed(client):
    """professional 能 reviewed→signed。"""
    skill = _make_skill(name="trans_pro", status="reviewed", user_id="u_demo")
    response = client.post(
        f"/v1/skills/{skill.id}/transition",
        json={"new_status": "signed"},
        headers=_professional_headers(),
    )
    assert response.status_code == 200
    assert response.json()["status"] == "signed"


def test_plain_user_cannot_transition(client, user_headers):
    """普通 user 角色不能调用 transition（403）。"""
    skill = _make_skill(name="trans_user_block", status="draft", user_id="u_demo")
    response = client.post(
        f"/v1/skills/{skill.id}/transition",
        json={"new_status": "reviewed"},
        headers=user_headers,
    )
    assert response.status_code == 403


def test_transition_cross_tenant_404(client):
    """跨租户 transition 返回 404。"""
    skill = _make_skill(name="trans_cross", status="draft", user_id="u_demo")
    response = client.post(
        f"/v1/skills/{skill.id}/transition",
        json={"new_status": "reviewed"},
        headers=_other_tenant_headers(),
    )
    assert response.status_code == 404


def test_full_transition_chain_draft_to_retired(client):
    """完整状态链：draft→reviewed→signed→retired。"""
    skill = _make_skill(name="full_chain", status="draft", user_id="u_demo")

    # draft → reviewed
    r1 = client.post(
        f"/v1/skills/{skill.id}/transition",
        json={"new_status": "reviewed"},
        headers=_admin_headers(),
    )
    assert r1.status_code == 200
    assert r1.json()["status"] == "reviewed"

    # reviewed → signed
    r2 = client.post(
        f"/v1/skills/{skill.id}/transition",
        json={"new_status": "signed"},
        headers=_professional_headers(),
    )
    assert r2.status_code == 200
    assert r2.json()["status"] == "signed"

    # signed → retired
    r3 = client.post(
        f"/v1/skills/{skill.id}/transition",
        json={"new_status": "retired"},
        headers=_admin_headers(),
    )
    assert r3.status_code == 200
    assert r3.json()["status"] == "retired"

    # retired 后不再下发
    response = client.get("/v1/skills", headers={"Authorization": f"Bearer {create_access_token('u_demo', 't_demo', 'user')}"})
    names = [item["name"] for item in response.json()["skills"]]
    assert "full_chain" not in names


# ---------- T10.4 完整流程 ----------

def test_full_flow_sandbox_to_delivery(client):
    """沙箱创建 Skill(draft) → admin transition 到 reviewed → 用户 GET /v1/skills 能看到。"""
    today = datetime.now(timezone.utc).date()
    with SessionLocal() as db:
        # 1. 沙箱冷启动创建 draft Skill
        run = schedule_sandbox_run(db, tenant_id="t_demo", user_id="u_demo", run_date=today)
        db.commit()
        runner = SandboxRunner(db, tenant_id="t_demo", user_id="u_demo", run_id=run.id)
        result = runner.execute()
        db.commit()
        assert result.status == "completed"
        assert result.skills_inducted > 0

        # 确认 Skill 存在且为 draft
        skills = db.query(Skill).filter_by(tenant_id="t_demo", user_id="u_demo").all()
        assert len(skills) > 0
        for sk in skills:
            assert sk.status == "draft"
        skill_ids = [sk.id for sk in skills]

    # 2. 用户此时 GET /v1/skills 看不到（draft 不下发）
    user_headers = {"Authorization": f"Bearer {create_access_token('u_demo', 't_demo', 'user')}"}
    resp_before = client.get("/v1/skills", headers=user_headers)
    assert resp_before.status_code == 200
    before_ids = [item["id"] for item in resp_before.json()["skills"]]
    for sid in skill_ids:
        assert sid not in before_ids

    # 3. admin 将第一个 draft Skill 转为 reviewed
    resp_trans = client.post(
        f"/v1/skills/{skill_ids[0]}/transition",
        json={"new_status": "reviewed"},
        headers=_admin_headers(),
    )
    assert resp_trans.status_code == 200
    assert resp_trans.json()["status"] == "reviewed"

    # 4. 用户 GET /v1/skills 能看到该 reviewed Skill
    resp_after = client.get("/v1/skills", headers=user_headers)
    assert resp_after.status_code == 200
    after_ids = [item["id"] for item in resp_after.json()["skills"]]
    assert skill_ids[0] in after_ids
    # 脱敏字段检查
    target = next(item for item in resp_after.json()["skills"] if item["id"] == skill_ids[0])
    assert "content_hash" not in target
    assert "tenant_id" not in target
    assert target["status"] == "reviewed"


def test_admin_can_list_skills_for_other_user(client):
    """admin/professional 可以通过 user_id query param 拉取指定用户的 Skill。"""
    _make_skill(name="admin_view_other", status="reviewed", user_id="u_other")
    response = client.get(
        "/v1/skills?user_id=u_other",
        headers=_admin_headers(),
    )
    assert response.status_code == 200
    names = [item["name"] for item in response.json()["skills"]]
    assert "admin_view_other" in names


# ---------- P3 冷启动兜底文案分阶段 ----------

def _set_observation_days(user_id: str, days: int, tenant_id: str = "t_demo"):
    """直接写入/更新 UserProfile.traits.observation_days（不走 update_profile 全链路）。"""
    with SessionLocal() as db:
        row = db.scalar(select(UserProfile).where(
            UserProfile.tenant_id == tenant_id,
            UserProfile.user_id == user_id,
        ))
        if row:
            row.traits = {"observation_days": days, "recent_mood_hint": "未知"}
        else:
            db.add(UserProfile(
                tenant_id=tenant_id,
                user_id=user_id,
                traits={"observation_days": days, "recent_mood_hint": "未知"},
                version=1,
            ))
        db.commit()


def test_empty_skill_list_returns_cold_start_hint(client, user_headers):
    """空列表时响应含 cold_start_hint 字段（非 null）。"""
    response = client.get("/v1/skills", headers=user_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["skills"] == []
    assert body["cold_start_hint"] is not None
    assert body["cold_start_hint"] in {"stage_0", "stage_1_3", "stage_4_7", "stage_7_plus"}


def test_cold_start_hint_stage_0_for_zero_observation_days(client, user_headers):
    """observation_days=0 → stage_0。"""
    _set_observation_days("u_demo", 0)
    response = client.get("/v1/skills", headers=user_headers)
    body = response.json()
    assert body["skills"] == []
    assert body["cold_start_hint"] == "stage_0"
    assert body["observation_days"] == 0


def test_cold_start_hint_stage_1_3_for_one_to_three_days(client, user_headers):
    """observation_days 1-3 → stage_1_3。"""
    for days in (1, 2, 3):
        _set_observation_days("u_demo", days)
        response = client.get("/v1/skills", headers=user_headers)
        body = response.json()
        assert body["cold_start_hint"] == "stage_1_3", f"days={days} 应为 stage_1_3"
        assert body["observation_days"] == days


def test_cold_start_hint_stage_4_7_for_four_to_seven_days(client, user_headers):
    """observation_days 4-7 → stage_4_7。"""
    for days in (4, 5, 6, 7):
        _set_observation_days("u_demo", days)
        response = client.get("/v1/skills", headers=user_headers)
        body = response.json()
        assert body["cold_start_hint"] == "stage_4_7", f"days={days} 应为 stage_4_7"
        assert body["observation_days"] == days


def test_cold_start_hint_stage_7_plus_for_over_seven_days(client, user_headers):
    """observation_days 7+ → stage_7_plus。"""
    for days in (8, 15, 30):
        _set_observation_days("u_demo", days)
        response = client.get("/v1/skills", headers=user_headers)
        body = response.json()
        assert body["cold_start_hint"] == "stage_7_plus", f"days={days} 应为 stage_7_plus"
        assert body["observation_days"] == days


def test_non_empty_skill_list_has_null_cold_start_hint(client, user_headers):
    """非空列表时 cold_start_hint 为 null。"""
    _make_skill(name="hint_null_when_nonempty", status="reviewed", user_id="u_demo")
    response = client.get("/v1/skills", headers=user_headers)
    assert response.status_code == 200
    body = response.json()
    assert len(body["skills"]) > 0
    assert body["cold_start_hint"] is None
