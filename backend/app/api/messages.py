"""分析消息路由（v0.7 本地优先）：GET /v1/me/messages —— 拉取式「分析→推送回应用」。

- 只读：从近 7 天 daily_portraits 确定性生成小结（不写库、不物化）；
- user 由 principal.subject 确定（body 无 user_id，无 IDOR 面）；
- 数据不足（<3 天画像）返回 {"message": null}（abstain，不硬编）；
- 幂等：同窗口同内容 → 同 message.id（客户端据此去重、只对新小结发通知）。
"""
from __future__ import annotations
from typing import Any

from datetime import UTC, datetime
from zoneinfo import ZoneInfo

from fastapi import APIRouter

from app.models import User
from app.services.messages import build_weekly_digest, recent_portraits

from app.api.deps import DB, PRINCIPAL, ensure_user, require_active_subscription

router = APIRouter(prefix="/v1")


@router.get("/me/messages")
def me_messages(db: DB, principal: PRINCIPAL) -> dict[str, Any]:
    user: User = ensure_user(db, principal, principal.subject)
    # v0.7 订阅门禁：云端分析消息属订阅能力（对齐 escalations/skills 写法，
    # 订阅显式到期 → 402；端侧镜像兜底本地小结）。
    require_active_subscription(db, user)
    tz_name = user.timezone or "Asia/Shanghai"
    today = datetime.now(UTC).astimezone(ZoneInfo(tz_name)).date()
    rows = recent_portraits(db, tenant_id=principal.tenant_id, user_id=principal.subject, today=today)
    digest = build_weekly_digest(rows)
    return {"message": digest}
