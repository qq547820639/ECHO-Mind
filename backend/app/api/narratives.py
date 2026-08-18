"""叙事查询路由（v0.6.1 拆分）：只读叙事查询（GET 无写副作用）。

- 单日查询（date）向后兼容；批量查询（from/to）返回 coverage + missing dates。
- 不输出情绪标签（mood_hint 字段废弃，PRD 契约点 2）。
"""
from __future__ import annotations
from typing import Any

from datetime import UTC, datetime, timedelta
from datetime import date as date_cls
from zoneinfo import ZoneInfo

from fastapi import APIRouter, HTTPException, Query
from sqlalchemy import select

from app.models import DailyNarrative

from app.api.deps import DB, PRINCIPAL, ensure_user

router = APIRouter(prefix="/v1")


def _narrative_to_dict(narrative: DailyNarrative, user_id: str) -> dict[str, Any]:
    # PRD 契约点 2：不输出情绪标签（mood_hint 字段废弃）
    return {
        "id": narrative.id,
        "user_id": user_id,
        "date": str(narrative.date),
        "events": narrative.events,
        "gaps": narrative.gaps,
    }


@router.get("/narratives")
def get_daily_narrative(
    user_id: str,
    db: DB,
    principal: PRINCIPAL,
    date: date_cls | None = Query(default=None),
    from_: date_cls | None = Query(default=None, alias="from"),
    to: date_cls | None = Query(default=None),
) -> dict[str, Any]:
    """只读叙事查询：GET 不写库、不读时生成。

    - ``date`` 单日查询（向后兼容），缺失返回 404；
    - ``from``/``to`` 批量查询：返回 ordered narratives + data coverage + missing dates。
    """
    user = ensure_user(db, principal, user_id)
    # P1-5 修复：无参默认日与写入侧统一为用户本地日（原 UTC 日期在跨日界线
    # 时区下会与 narrative.date（本地日）错位）。
    tz_name = user.timezone or "Asia/Shanghai"
    today = datetime.now(UTC).astimezone(ZoneInfo(tz_name)).date()

    if date is not None:
        narrative = db.scalar(select(DailyNarrative).where(
            DailyNarrative.tenant_id == principal.tenant_id,
            DailyNarrative.user_id == user_id,
            DailyNarrative.date == date,
        ))
        if narrative is None:
            raise HTTPException(status_code=404, detail="no narrative for date")
        return _narrative_to_dict(narrative, user_id)

    if from_ is not None and to is not None:
        if from_ > to:
            raise HTTPException(status_code=422, detail="from must be <= to")
        start, end = from_, to
    elif from_ is not None:
        start, end = from_, from_
    elif to is not None:
        start, end = to, to
    else:
        # 单日默认：今天（保持既有无参调用语义）
        narrative = db.scalar(select(DailyNarrative).where(
            DailyNarrative.tenant_id == principal.tenant_id,
            DailyNarrative.user_id == user_id,
            DailyNarrative.date == today,
        ))
        if narrative is None:
            raise HTTPException(status_code=404, detail="no narrative for date")
        return _narrative_to_dict(narrative, user_id)

    rows = db.scalars(select(DailyNarrative).where(
        DailyNarrative.tenant_id == principal.tenant_id,
        DailyNarrative.user_id == user_id,
        DailyNarrative.date >= start,
        DailyNarrative.date <= end,
    ).order_by(DailyNarrative.date.asc())).all()
    present_dates = {str(r.date) for r in rows}
    requested_days = (end - start).days + 1
    all_dates = [str(start + timedelta(days=i)) for i in range(requested_days)]
    missing_dates = [d for d in all_dates if d not in present_dates]
    coverage = round(len(rows) / requested_days, 3) if requested_days else 1.0
    return {
        "narratives": [_narrative_to_dict(r, user_id) for r in rows],
        "from": str(start),
        "to": str(end),
        "coverage": {
            "requested_days": requested_days,
            "present_days": len(rows),
            "coverage": coverage,
            "missing_dates": missing_dates,
        },
    }
