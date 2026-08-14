"""自进化沙箱路由（v0.6.1 拆分）：调度 / 查询。

并发语义（v0.6.1，hardening 需求十）：
- schedule 阶段保留 running 计数快速失败（对调度 API 友好返回 429）；
- 真正不突破上限由执行期数据库原子租户槽（services.sandbox.slots）保证
  （多 worker 并发启动不可能 check-then-act 突破）。
"""
from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func, select

from app.auth import Principal, require_roles
from app.models import SandboxRun
from app.schemas import SandboxRunCreate, SandboxRunOut
from app.services.audit import append_audit
from app.services.sandbox import schedule_sandbox_run

from app.api.deps import DB, PRINCIPAL, ensure_user, require_feature_flag
from sqlalchemy.orm import Session

router = APIRouter(prefix="/v1")

#: 沙箱速率限制窗口（秒）：同 tenant+user 最近 1 小时创建数上限。
_SANDBOX_RATE_WINDOW_SECONDS = 3600.0


def _check_sandbox_concurrency(db: Session, tenant_id: str) -> bool:
    """调度期快速失败：租户当前 running 数是否低于并发上限（执行期由槽保证）。"""
    from app.config import get_settings

    running = db.scalar(
        select(func.count()).select_from(SandboxRun).where(
            SandboxRun.tenant_id == tenant_id,
            SandboxRun.status == "running",
        )
    )
    return (running or 0) < get_settings().sandbox_max_concurrent


def _check_sandbox_rate(db: Session, tenant_id: str, user_id: str) -> bool:
    """租户+用户最近 1 小时创建的 run 数是否低于速率上限。"""
    from app.config import get_settings

    cutoff = datetime.now(UTC) - timedelta(seconds=_SANDBOX_RATE_WINDOW_SECONDS)
    recent = db.scalar(
        select(func.count()).select_from(SandboxRun).where(
            SandboxRun.tenant_id == tenant_id,
            SandboxRun.user_id == user_id,
            SandboxRun.created_at >= cutoff,
        )
    )
    return (recent or 0) < get_settings().sandbox_rate_limit_per_hour


def _sandbox_run_to_out(run: SandboxRun) -> SandboxRunOut:
    return SandboxRunOut(
        id=run.id,
        user_id=run.user_id,
        run_date=run.run_date,
        status=run.status,
        gaps_found=list(run.gaps_found or []),
        tools_generated=run.tools_generated or 0,
        tools_validated=run.tools_validated or 0,
        skills_inducted=run.skills_inducted or 0,
        error_message=run.error_message,
        started_at=run.started_at,
        completed_at=run.completed_at,
    )


@router.post("/sandbox/runs")
def schedule_sandbox(
    payload: SandboxRunCreate,
    db: DB,
    principal: Annotated[Principal, Depends(require_roles("admin", "professional"))],
    _flag: Annotated[None, Depends(require_feature_flag("sandbox_enabled"))],
) -> SandboxRunOut:
    """触发一次自进化沙箱运行（幂等：同 tenant+user+date 返回同一记录）。

    并发配额：调度期 running 计数快速失败 + 执行期原子租户槽（双保险）。
    """
    ensure_user(db, principal, payload.user_id)
    if not _check_sandbox_concurrency(db, principal.tenant_id):
        raise HTTPException(status_code=429, detail="sandbox concurrency limit reached")
    if not _check_sandbox_rate(db, principal.tenant_id, payload.user_id):
        raise HTTPException(status_code=429, detail="sandbox rate limit exceeded")
    run = schedule_sandbox_run(
        db,
        tenant_id=principal.tenant_id,
        user_id=payload.user_id,
        run_date=payload.run_date,
    )
    if run is None:
        raise HTTPException(status_code=429, detail="sandbox concurrency limit reached")
    append_audit(
        db,
        tenant_id=principal.tenant_id,
        actor_type=principal.role,
        actor_id=principal.subject,
        action="sandbox.schedule",
        object_type="sandbox_run",
        object_id=run.id,
        metadata={"user_id": payload.user_id, "run_date": str(run.run_date), "status": run.status},
    )
    db.commit()
    db.refresh(run)
    return _sandbox_run_to_out(run)


@router.get("/sandbox/runs/{run_id}")
def get_sandbox_run(
    run_id: str,
    db: DB,
    principal: PRINCIPAL,
    _flag: Annotated[None, Depends(require_feature_flag("sandbox_enabled"))],
) -> SandboxRunOut:
    """查询单个沙箱运行记录。受 ensure_user 校验目标用户归属。"""
    run = db.get(SandboxRun, run_id)
    if not run or run.tenant_id != principal.tenant_id:
        raise HTTPException(status_code=404, detail="sandbox run not found")
    ensure_user(db, principal, run.user_id)
    return _sandbox_run_to_out(run)
