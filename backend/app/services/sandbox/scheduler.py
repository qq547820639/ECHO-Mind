"""沙箱运行调度器：幂等创建当日 SandboxRun。

不写审计，由路由层负责；不 commit，由调用方负责。

v0.6.1 并发语义：
- **执行期原子配额**由 [app.services.sandbox.slots] 承担（数据库原子 permit，
  消除 check-then-act race）；本模块不再持有任何信号量或计数函数。
- 路由层（routes.schedule_sandbox）仍保留 schedule 阶段的 running 计数快速失败
  （对调度 API 返回 429 更友好），但最终不突破上限由执行槽保证。
- 本模块仅负责幂等创建/返回 SandboxRun 记录。
"""
from __future__ import annotations

from datetime import date as date_cls, datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import SandboxRun


def schedule_sandbox_run(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    run_date: date_cls | None = None,
) -> SandboxRun | None:
    """幂等创建/返回某用户某天的 SandboxRun。

    - 同 tenant+user+date 已存在则直接返回已有记录（幂等）
    - 否则创建 status=pending 的新记录
    - 并发/速率限制由路由层 + 执行槽（slots）承担（本模块不返回 None 哨兵）
    """
    target_date = run_date or datetime.now(timezone.utc).date()
    existing = db.scalar(
        select(SandboxRun).where(
            SandboxRun.tenant_id == tenant_id,
            SandboxRun.user_id == user_id,
            SandboxRun.run_date == target_date,
        )
    )
    if existing:
        return existing
    run = SandboxRun(
        tenant_id=tenant_id,
        user_id=user_id,
        run_date=target_date,
        status="pending",
    )
    db.add(run)
    db.flush()
    return run
