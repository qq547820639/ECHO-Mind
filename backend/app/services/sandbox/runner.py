"""沙箱运行编排器：SandboxRun 生命周期 + 造工具回路（生成→验证→归纳）。

状态机：pending → running → completed / failed。
回路步骤：audit_day → find_gaps → forge_tools → validate_tools → induct_skills。
"""
from __future__ import annotations

import concurrent.futures
from datetime import datetime, timezone

from sqlalchemy.orm import Session

from app.config import get_settings
from app.models import SandboxRun
from app.services.audit import append_audit
from app.services.sandbox.audit_day import audit_day
from app.services.sandbox.gap_finder import find_gaps
from app.services.sandbox.skill_induct import induct_skills
from app.services.sandbox.tool_forge import forge_tools
from app.services.sandbox.tool_validator import validate_tools

settings = get_settings()


class SandboxRunner:
    """单个 SandboxRun 的执行器。"""

    def __init__(self, db: Session, *, tenant_id: str, user_id: str, run_id: str) -> None:
        self.db = db
        self.tenant_id = tenant_id
        self.user_id = user_id
        self.run_id = run_id
        run = db.get(SandboxRun, run_id)
        if run is None or run.tenant_id != tenant_id or run.user_id != user_id:
            raise ValueError("sandbox run does not belong to the given tenant/user")
        self.run = run

    def _record_failure(self, message: str) -> None:
        """将 run 状态转为 failed 并写审计（失败）。"""
        self.run.status = "failed"
        self.run.error_message = message
        self.run.completed_at = datetime.now(timezone.utc)
        self.db.flush()
        append_audit(
            self.db,
            tenant_id=self.tenant_id,
            actor_type="sandbox",
            actor_id="runner",
            action="sandbox.run",
            object_type="sandbox_run",
            object_id=self.run.id,
            metadata={
                "run_date": str(self.run.run_date),
                "status": "failed",
                "error_message": self.run.error_message,
            },
        )
        self.db.flush()

    def _run_loop(self) -> None:
        """实际造工具回路，在 worker 线程中执行。

        内部捕获所有异常并转为 failed；不向上抛出（future.result 正常返回）。
        """
        try:
            # 1. 当日审计汇总
            audit_summary = audit_day(
                self.db,
                tenant_id=self.tenant_id,
                user_id=self.user_id,
                run_date=self.run.run_date,
            )

            # 2. 缺口识别
            gaps = find_gaps(
                self.db,
                tenant_id=self.tenant_id,
                user_id=self.user_id,
                run_date=self.run.run_date,
                audit_summary=audit_summary,
            )

            # 3. 工具锻造
            candidates = forge_tools(
                self.db,
                tenant_id=self.tenant_id,
                user_id=self.user_id,
                gaps=gaps,
            )

            # 4. 工具验证
            validated = validate_tools(
                self.db,
                tenant_id=self.tenant_id,
                user_id=self.user_id,
                candidate_tools=candidates,
            )
            valid_tools = [tool for tool, is_valid, _ in validated if is_valid]

            # 5. 技能归纳
            skills = induct_skills(
                self.db,
                tenant_id=self.tenant_id,
                user_id=self.user_id,
                validated_tools=valid_tools,
            )

            # 更新运行记录
            self.run.gaps_found = [g["description"] for g in gaps]
            self.run.tools_generated = len(candidates)
            self.run.tools_validated = len(validated)
            self.run.skills_inducted = len(skills)
            self.run.status = "completed"
            self.run.completed_at = datetime.now(timezone.utc)
            self.db.flush()

            # 写审计（成功）
            append_audit(
                self.db,
                tenant_id=self.tenant_id,
                actor_type="sandbox",
                actor_id="runner",
                action="sandbox.run",
                object_type="sandbox_run",
                object_id=self.run.id,
                metadata={
                    "run_date": str(self.run.run_date),
                    "status": self.run.status,
                    "gaps_found": list(self.run.gaps_found or []),
                    "tools_generated": self.run.tools_generated,
                    "tools_validated": self.run.tools_validated,
                    "skills_inducted": self.run.skills_inducted,
                },
            )
            self.db.flush()
        except Exception as exc:  # noqa: BLE001 沙箱内部捕获所有异常，记录后不抛出
            self._record_failure(str(exc))

    def execute(self) -> SandboxRun:
        """执行造工具回路：pending → running → completed/failed。

        回路：
            1. audit_day 汇总当日感知数据
            2. find_gaps 识别感知覆盖缺口
            3. forge_tools 为缺口生成候选 Tool
            4. validate_tools 静态验证候选 Tool
            5. induct_skills 将通过验证的 Tool 归纳为 Skill 草稿
        超时（超过 sandbox_timeout_seconds）转 failed；其它异常也转 failed。
        不向上抛出（调用方可检查 run.status）。
        """
        now = datetime.now(timezone.utc)
        self.run.status = "running"
        self.run.started_at = now
        self.run.error_message = None
        self.db.flush()

        timeout_seconds = settings.sandbox_timeout_seconds
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
                future = executor.submit(self._run_loop)
                future.result(timeout=timeout_seconds)
        except concurrent.futures.TimeoutError:
            # 主线程等待超时：标记 failed。worker 线程仍在运行，但 `with` 块
            # 的 shutdown(wait=True) 会在异常处理前等待 worker 结束，避免并发
            # DB 写入；最终由主线程覆盖状态。
            self._record_failure(f"timeout after {timeout_seconds}s")

        return self.run
