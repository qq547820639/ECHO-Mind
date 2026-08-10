"""派生特征摄入路由（v0.6.1 拆分）：被动感知特征 ingest（幂等 + consent 门控）。

写路径：入库后同步构建当日叙事与当日行为聚合（Milestone B）；
不触发任何被动危机链路（行为派生特征不得用于推断危机/自杀意图，PRD v0.6 契约点 1）。
"""
from __future__ import annotations

from datetime import timezone
from typing import Annotated
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.models import User
from app.schemas import DerivedFeatureIn
from app.services.aggregates.calculator import upsert_daily_aggregate
from app.services.audit import append_audit
from app.services.profile import build_daily_narrative, ingest_feature

from app.api.deps import (
    DB,
    PRINCIPAL,
    ensure_user,
    require_feature_flag,
    require_passive_sensing_consent,
    require_voice_features_consent,
)

router = APIRouter(prefix="/v1")


@router.post("/features/ingest")
def ingest_derived_feature(
    payload: DerivedFeatureIn,
    db: DB,
    principal: PRINCIPAL,
    _flag: Annotated[None, Depends(require_feature_flag("passive_sensing_enabled"))],
):
    from fastapi import HTTPException as _HTTPException
    from app.services.telemetry import log_feature_ingest_reject, count_event

    try:
        ensure_user(db, principal, payload.user_id)
        require_passive_sensing_consent(db, principal, payload.user_id)
        if payload.source == "mic_opt":
            # 麦克风派生特征需额外校验 voice_features consent 有效（撤销则 412）
            require_voice_features_consent(db, principal, payload.user_id)
    except _HTTPException as exc:
        # v0.6.1 P2-16：ingest 拒绝原因分类遥测（不记录特征内容本身）
        if exc.status_code == 412:
            category = "consent" if payload.source != "mic_opt" else "voice_features_consent"
            log_feature_ingest_reject(request_id="", category=category, reason="consent_revoked_or_missing")
            count_event(f"ingest_reject_412_{category}")
        elif exc.status_code == 410:
            log_feature_ingest_reject(request_id="", category="flag_off", reason="passive_sensing_disabled")
            count_event("ingest_reject_410_flag_off")
        raise
    row, replay = ingest_feature(db, tenant_id=principal.tenant_id, user_id=payload.user_id, feature=payload)
    if replay:
        return {"id": row.id, "idempotent_replay": True, "escalation_id": None}
    # 写路径：入库后同步构建当日叙事（GET 不再读时生成）
    build_daily_narrative(
        db,
        tenant_id=principal.tenant_id,
        user_id=payload.user_id,
        date=payload.window_start.date(),
    )
    # Milestone B：非幂等重放时同步更新当日行为聚合（日界线用用户本地时区）
    user = db.get(User, payload.user_id)
    if user is not None:
        tz_name = user.timezone or "Asia/Shanghai"
        ws = payload.window_start
        if ws.tzinfo is None:  # SQLite 路径防御：naive 一律按 UTC 解释
            ws = ws.replace(tzinfo=timezone.utc)
        local_date = ws.astimezone(ZoneInfo(tz_name)).date()
        upsert_daily_aggregate(
            db,
            tenant_id=principal.tenant_id,
            user_id=payload.user_id,
            local_date=local_date,
            tz_name=tz_name,
        )
    # PRD v0.6 契约点 1：被动行为数据不得用于推断危机/自杀意图，
    # ingest 不触发任何被动 RED 危机链路（escalation_id 恒为 None）。
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="feature.ingest", object_type="derived_feature", object_id=row.id,
                 metadata={"source": payload.source})
    db.commit()
    return {"id": row.id, "idempotent_replay": False, "escalation_id": None}
