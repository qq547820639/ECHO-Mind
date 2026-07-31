"""自进化沙箱服务包。

子模块：
- scheduler：幂等调度当日 SandboxRun
- audit_day：汇总当日 DerivedFeature / DailyNarrative / UserProfile
- gap_finder：基于 audit_day 汇总识别感知覆盖缺口
- tool_forge：为缺口生成候选 Tool 描述
- tool_validator：静态验证候选 Tool 契约
- skill_induct：将验证通过的 Tool 归纳为 Skill 草稿
- runner：SandboxRun 生命周期编排（造工具回路：生成→验证→归纳）
"""
from app.services.sandbox.audit_day import audit_day
from app.services.sandbox.gap_finder import find_gaps
from app.services.sandbox.runner import SandboxRunner
from app.services.sandbox.scheduler import schedule_sandbox_run
from app.services.sandbox.skill_induct import induct_skills
from app.services.sandbox.tool_forge import forge_tools
from app.services.sandbox.tool_validator import validate_tools

__all__ = [
    "audit_day",
    "find_gaps",
    "forge_tools",
    "induct_skills",
    "schedule_sandbox_run",
    "SandboxRunner",
    "validate_tools",
]
