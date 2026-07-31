"""T09 造工具回路（生成→验证→归纳）测试。

覆盖场景：
- Zero-Skill 冷启动：无任何已归纳 Skill 时，沙箱能基于当日缺口生成首批候选 Tool
- 缺口识别：feature_count==0 → "无感知数据"缺口；缺 source → 对应缺口
- Tool 验证失败回退：guardrails 缺必须条目的 Tool 被拒绝
- Skill 归纳幂等：相同缺口重复运行不产生重复 Skill（按 name+version 去重）
- 完整回路：ingest 特征 → build narrative → schedule run → execute → 验证 Skill 已创建
- 红色关键词 Tool 被拒绝：Tool description 含"自杀" → validate 失败
"""
from datetime import datetime, timedelta, timezone

from app.database import SessionLocal
from app.models import DerivedFeature, Skill, Tool
from app.schemas import DerivedFeatureIn
from app.services.profile import build_daily_narrative, ingest_feature
from app.services.sandbox import (
    audit_day,
    forge_tools,
    find_gaps,
    induct_skills,
    schedule_sandbox_run,
    validate_tools,
)
from app.services.sandbox.runner import SandboxRunner


def _noon() -> datetime:
    """当天 12:00 UTC，用于派生特征时间窗口。"""
    return datetime.now(timezone.utc).replace(hour=12, minute=0, second=0, microsecond=0)


def _make_feature(event_id: str, source: str, summary: str, window_start: datetime | None = None) -> DerivedFeatureIn:
    ws = window_start or _noon()
    return DerivedFeatureIn(
        event_id=event_id,
        user_id="u_demo",
        source=source,
        window_start=ws,
        window_end=ws + timedelta(minutes=30),
        summary=summary,
        vector=[0.1, 0.2],
    )


# ---------- T09.6 Zero-Skill 冷启动 ----------

def test_zero_skill_cold_start_generates_candidate_tools():
    """无任何已归纳 Skill 时，沙箱能基于当日缺口生成首批候选 Tool。"""
    today = datetime.now(timezone.utc).date()
    with SessionLocal() as db:
        # 冷启动前确认无 Skill / Tool
        assert db.query(Skill).filter_by(tenant_id="t_demo", user_id="u_demo").count() == 0
        assert db.query(Tool).filter_by(tenant_id="t_demo", user_id="u_demo").count() == 0

        # 无任何特征数据 → 触发缺口
        summary = audit_day(db, tenant_id="t_demo", user_id="u_demo", run_date=today)
        gaps = find_gaps(db, tenant_id="t_demo", user_id="u_demo", run_date=today, audit_summary=summary)
        assert len(gaps) > 0

        candidates = forge_tools(db, tenant_id="t_demo", user_id="u_demo", gaps=gaps)
        assert len(candidates) > 0
        # 候选 Tool 尚未持久化
        assert db.query(Tool).filter_by(tenant_id="t_demo", user_id="u_demo").count() == 0


# ---------- T09.6 缺口识别 ----------

def test_gap_finder_feature_count_zero():
    """feature_count==0 → "无感知数据"缺口。"""
    today = datetime.now(timezone.utc).date()
    with SessionLocal() as db:
        summary = audit_day(db, tenant_id="t_demo", user_id="u_demo", run_date=today)
        assert summary["feature_count"] == 0
        gaps = find_gaps(db, tenant_id="t_demo", user_id="u_demo", run_date=today, audit_summary=summary)
        descs = [g["description"] for g in gaps]
        assert "无感知数据" in descs
        # 对应缺口 severity=high
        no_data_gap = next(g for g in gaps if g["description"] == "无感知数据")
        assert no_data_gap["severity"] == "high"
        assert no_data_gap["suggested_tool_type"] == "data_check"


def test_gap_finder_missing_source():
    """缺 source → 对应"缺少{source}信号"缺口。"""
    today = datetime.now(timezone.utc).date()
    noon = _noon()
    with SessionLocal() as db:
        # 只入库 screen，缺失 accel/gyro/notification/app_activity
        feat = DerivedFeature(
            tenant_id="t_demo",
            user_id="u_demo",
            event_id="evt_gap_src_0001",
            schema_version="feat-v1",
            source="screen",
            window_start=noon,
            window_end=noon + timedelta(minutes=30),
            summary="屏幕使用正常",
            vector=[0.1],
        )
        db.add(feat)
        db.flush()
        build_daily_narrative(db, tenant_id="t_demo", user_id="u_demo", date=today)
        db.commit()

        summary = audit_day(db, tenant_id="t_demo", user_id="u_demo", run_date=today)
        assert summary["feature_count"] >= 1
        gaps = find_gaps(db, tenant_id="t_demo", user_id="u_demo", run_date=today, audit_summary=summary)
        descs = [g["description"] for g in gaps]
        assert "缺少accel信号" in descs
        assert "缺少gyro信号" in descs
        assert "缺少notification信号" in descs
        assert "缺少app_activity信号" in descs
        # screen 已存在，不应出现
        assert "缺少screen信号" not in descs
        # feature_count>0 不应触发"无感知数据"
        assert "无感知数据" not in descs


# ---------- T09.6 Tool 验证失败回退 ----------

def test_tool_validator_rejects_missing_guardrail():
    """guardrails 缺必须条目的 Tool 被拒绝。"""
    with SessionLocal() as db:
        bad_tool = {
            "name": "bad_tool_missing_guardrail",
            "description": "测试工具",
            "parameters_schema": {"type": "object", "properties": {}},
            "returns_schema": {"type": "object", "properties": {}},
            "guardrails": ["不输出诊断结论"],  # 缺"不替代专业医疗"和"命中红色信号立即冻结"
            "steps": [{"key": "step1", "description": "做点什么"}],
        }
        results = validate_tools(
            db, tenant_id="t_demo", user_id="u_demo", candidate_tools=[bad_tool]
        )
        assert len(results) == 1
        tool, is_valid, reason = results[0]
        assert not is_valid
        assert "guardrail" in reason


def test_tool_validator_rejects_empty_steps():
    """steps 为空的 Tool 被拒绝。"""
    with SessionLocal() as db:
        bad_tool = {
            "name": "bad_tool_empty_steps",
            "description": "测试工具",
            "parameters_schema": {"type": "object", "properties": {}},
            "returns_schema": {"type": "object", "properties": {}},
            "guardrails": ["不输出诊断结论", "不替代专业医疗", "命中红色信号立即冻结"],
            "steps": [],
        }
        results = validate_tools(
            db, tenant_id="t_demo", user_id="u_demo", candidate_tools=[bad_tool]
        )
        assert len(results) == 1
        _, is_valid, reason = results[0]
        assert not is_valid
        assert "steps" in reason


def test_tool_validator_rejects_invalid_schema():
    """parameters_schema 不是合法 JSON Schema 的 Tool 被拒绝。"""
    with SessionLocal() as db:
        bad_tool = {
            "name": "bad_tool_bad_schema",
            "description": "测试工具",
            "parameters_schema": "not-a-dict",
            "returns_schema": {"type": "object", "properties": {}},
            "guardrails": ["不输出诊断结论", "不替代专业医疗", "命中红色信号立即冻结"],
            "steps": [{"key": "s1", "description": "step"}],
        }
        results = validate_tools(
            db, tenant_id="t_demo", user_id="u_demo", candidate_tools=[bad_tool]
        )
        assert len(results) == 1
        _, is_valid, reason = results[0]
        assert not is_valid
        assert "parameters_schema" in reason


# ---------- T09.6 红色关键词 Tool 被拒绝 ----------

def test_tool_validator_rejects_red_keyword():
    """Tool description 含"自杀" → validate 失败。"""
    with SessionLocal() as db:
        red_tool = {
            "name": "red_keyword_tool",
            "description": "这个工具会讨论自杀相关话题",
            "parameters_schema": {"type": "object", "properties": {}},
            "returns_schema": {"type": "object", "properties": {}},
            "guardrails": ["不输出诊断结论", "不替代专业医疗", "命中红色信号立即冻结"],
            "steps": [{"key": "s1", "description": "执行检查"}],
        }
        results = validate_tools(
            db, tenant_id="t_demo", user_id="u_demo", candidate_tools=[red_tool]
        )
        assert len(results) == 1
        tool, is_valid, reason = results[0]
        assert not is_valid
        assert "红色" in reason or "自杀" in reason


# ---------- T09.6 Skill 归纳幂等 ----------

def test_skill_induct_idempotent_on_rerun():
    """相同缺口重复运行不产生重复 Skill（按 name+version 去重）。"""
    today = datetime.now(timezone.utc).date()
    with SessionLocal() as db:
        # 第一次运行
        summary = audit_day(db, tenant_id="t_demo", user_id="u_demo", run_date=today)
        gaps = find_gaps(db, tenant_id="t_demo", user_id="u_demo", run_date=today, audit_summary=summary)
        candidates = forge_tools(db, tenant_id="t_demo", user_id="u_demo", gaps=gaps)
        validated = validate_tools(
            db, tenant_id="t_demo", user_id="u_demo", candidate_tools=candidates
        )
        valid_tools = [t for t, is_valid, _ in validated if is_valid]
        assert len(valid_tools) > 0

        skills_run1 = induct_skills(
            db, tenant_id="t_demo", user_id="u_demo", validated_tools=valid_tools
        )
        db.commit()
        count_run1 = len(skills_run1)
        assert count_run1 > 0

        # 记录第一次的 (name, version) 集合
        skills_after_run1 = db.query(Skill).filter_by(tenant_id="t_demo", user_id="u_demo").all()
        nv_set_1 = {(s.name, s.version) for s in skills_after_run1}

        # 第二次运行：相同缺口 → 相同内容 → 应复用已有 Skill
        skills_run2 = induct_skills(
            db, tenant_id="t_demo", user_id="u_demo", validated_tools=valid_tools
        )
        db.commit()
        count_run2 = len(skills_run2)
        assert count_run2 == count_run1

        # 数据库中 (name, version) 无重复
        skills_after_run2 = db.query(Skill).filter_by(tenant_id="t_demo", user_id="u_demo").all()
        nv_set_2 = {(s.name, s.version) for s in skills_after_run2}
        assert len(nv_set_2) == len(skills_after_run2)
        # 集合不变（未产生新版本）
        assert nv_set_2 == nv_set_1


# ---------- T09.6 完整回路 ----------

def test_full_sandbox_loop_creates_skills():
    """ingest 特征 → build narrative → schedule run → execute → 验证 Skill 已创建。"""
    today = datetime.now(timezone.utc).date()
    with SessionLocal() as db:
        # 1. ingest 派生特征
        feat = _make_feature("evt_loop_screen_0001", "screen", "屏幕使用平稳")
        ingest_feature(db, tenant_id="t_demo", user_id="u_demo", feature=feat)
        # 2. build narrative
        build_daily_narrative(db, tenant_id="t_demo", user_id="u_demo", date=today)
        # 3. schedule run
        run = schedule_sandbox_run(db, tenant_id="t_demo", user_id="u_demo", run_date=today)
        db.commit()
        assert run.status == "pending"

        # 4. execute
        runner = SandboxRunner(db, tenant_id="t_demo", user_id="u_demo", run_id=run.id)
        result = runner.execute()
        db.commit()

        # 5. 验证回路产物
        assert result.status == "completed"
        assert result.started_at is not None
        assert result.completed_at is not None
        assert result.error_message is None
        # 只有 screen → 缺 4 个 source，且 observation_days<3 → 缺口>0
        assert len(result.gaps_found) > 0
        assert result.tools_generated > 0
        assert result.tools_validated > 0
        assert result.skills_inducted > 0

        # Skill 已创建
        skills = db.query(Skill).filter_by(tenant_id="t_demo", user_id="u_demo").all()
        assert len(skills) > 0
        for skill in skills:
            assert skill.status == "draft"
            assert skill.content_hash is not None
            assert len(skill.content_hash) == 64  # sha256 hex
            assert len(skill.guardrails) >= 3  # 三条安全底线
            assert len(skill.steps) > 0
            assert len(skill.trigger_conditions) > 0

        # Tool 已创建并绑定到 Skill
        tools = db.query(Tool).filter_by(tenant_id="t_demo", user_id="u_demo").all()
        assert len(tools) > 0
        for tool in tools:
            assert tool.status == "draft"
            assert tool.skill_id is not None


def test_full_loop_idempotent_rerun():
    """完整回路重复执行不产生重复 Skill/Tool。"""
    today = datetime.now(timezone.utc).date()
    with SessionLocal() as db:
        feat = _make_feature("evt_loop_idem_0001", "screen", "屏幕使用平稳")
        ingest_feature(db, tenant_id="t_demo", user_id="u_demo", feature=feat)
        build_daily_narrative(db, tenant_id="t_demo", user_id="u_demo", date=today)
        run = schedule_sandbox_run(db, tenant_id="t_demo", user_id="u_demo", run_date=today)
        db.commit()

        # 第一次执行
        runner = SandboxRunner(db, tenant_id="t_demo", user_id="u_demo", run_id=run.id)
        runner.execute()
        db.commit()
        skills_count_1 = db.query(Skill).filter_by(tenant_id="t_demo", user_id="u_demo").count()
        tools_count_1 = db.query(Tool).filter_by(tenant_id="t_demo", user_id="u_demo").count()
        assert skills_count_1 > 0
        assert tools_count_1 > 0

        # 第二次执行（同一天，相同缺口）
        runner2 = SandboxRunner(db, tenant_id="t_demo", user_id="u_demo", run_id=run.id)
        runner2.execute()
        db.commit()
        skills_count_2 = db.query(Skill).filter_by(tenant_id="t_demo", user_id="u_demo").count()
        tools_count_2 = db.query(Tool).filter_by(tenant_id="t_demo", user_id="u_demo").count()
        # Skill 和 Tool 数量不变
        assert skills_count_2 == skills_count_1
        assert tools_count_2 == tools_count_1


def test_full_loop_no_data_cold_start():
    """无任何特征数据时完整回路仍能完成并产生 Skill。"""
    today = datetime.now(timezone.utc).date()
    with SessionLocal() as db:
        run = schedule_sandbox_run(db, tenant_id="t_demo", user_id="u_demo", run_date=today)
        db.commit()

        runner = SandboxRunner(db, tenant_id="t_demo", user_id="u_demo", run_id=run.id)
        result = runner.execute()
        db.commit()

        assert result.status == "completed"
        assert "无感知数据" in result.gaps_found
        assert result.tools_generated > 0
        assert result.skills_inducted > 0
        # Skill 已创建
        assert db.query(Skill).filter_by(tenant_id="t_demo", user_id="u_demo").count() > 0
