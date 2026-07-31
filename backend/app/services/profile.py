"""被动感知画像服务：派生特征入库、每日叙事、画像聚合。

原则：后端只处理端侧派生特征（摘要/向量），不接触原始传感数据。
审计写入由路由层负责，本模块不写审计。
"""
from __future__ import annotations

from datetime import date as date_cls, datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import DailyNarrative, DerivedFeature, UserProfile, utcnow
from app.schemas import DerivedFeatureIn

NEGATIVE_HINT_WORDS = ("低落", "焦虑", "疲惫", "压力", "难过", "失眠", "烦躁", "孤独")
POSITIVE_HINT_WORDS = ("开心", "平静", "放松", "愉快", "积极", "充实")


def _mood_hint_from_summary(summary: str) -> str:
    if any(word in summary for word in NEGATIVE_HINT_WORDS):
        return "偏低"
    if any(word in summary for word in POSITIVE_HINT_WORDS):
        return "平稳偏积极"
    return "平稳"


def ingest_feature(
    db: Session, *, tenant_id: str, user_id: str, feature: DerivedFeatureIn
) -> tuple[DerivedFeature, bool]:
    """幂等入库派生特征。返回 (row, idempotent_replay)。"""
    existing = db.scalar(select(DerivedFeature).where(
        DerivedFeature.tenant_id == tenant_id,
        DerivedFeature.event_id == feature.event_id,
    ))
    if existing:
        return existing, True
    row = DerivedFeature(
        tenant_id=tenant_id,
        user_id=user_id,
        event_id=feature.event_id,
        schema_version=feature.schema_version,
        source=feature.source,
        window_start=feature.window_start,
        window_end=feature.window_end,
        summary=feature.summary,
        vector=feature.vector,
    )
    db.add(row)
    db.flush()
    return row, False


def build_daily_narrative(
    db: Session, *, tenant_id: str, user_id: str, date: date_cls
) -> DailyNarrative:
    """按 tenant+user+date 幂等生成/更新每日叙事。"""
    features = db.scalars(select(DerivedFeature).where(
        DerivedFeature.tenant_id == tenant_id,
        DerivedFeature.user_id == user_id,
    ).order_by(DerivedFeature.window_start)).all()
    day_features = [f for f in features if f.window_start.date() == date]

    events = [{
        "source": f.source,
        "summary": f.summary,
        "mood_hint": _mood_hint_from_summary(f.summary),
    } for f in day_features]
    hints = [e["mood_hint"] for e in events]
    if "偏低" in hints:
        mood_hint = "偏低"
    elif "平稳偏积极" in hints:
        mood_hint = "平稳偏积极"
    else:
        mood_hint = "平稳"
    gaps: list[str] = []  # 预留：后续标记感知覆盖缺口

    existing = db.scalar(select(DailyNarrative).where(
        DailyNarrative.tenant_id == tenant_id,
        DailyNarrative.user_id == user_id,
        DailyNarrative.date == date,
    ))
    if existing:
        existing.events = events
        existing.mood_hint = mood_hint
        existing.gaps = gaps
        db.flush()
        return existing
    row = DailyNarrative(
        tenant_id=tenant_id,
        user_id=user_id,
        date=date,
        events=events,
        mood_hint=mood_hint,
        gaps=gaps,
    )
    db.add(row)
    db.flush()
    return row


def update_profile(db: Session, *, tenant_id: str, user_id: str) -> UserProfile:
    """基于近 7 天叙事聚合画像；每次调用刷新 traits 并递增 version。"""
    since = datetime.now(timezone.utc).date() - timedelta(days=7)
    narratives = db.scalars(select(DailyNarrative).where(
        DailyNarrative.tenant_id == tenant_id,
        DailyNarrative.user_id == user_id,
        DailyNarrative.date >= since,
    ).order_by(DailyNarrative.date.desc())).all()
    features = db.scalars(select(DerivedFeature).where(
        DerivedFeature.tenant_id == tenant_id,
        DerivedFeature.user_id == user_id,
    )).all()
    observation_days = len({f.window_start.date() for f in features})
    traits = {
        "observation_days": observation_days,
        "narrative_days_last_7": len(narratives),
        "recent_mood_hint": narratives[0].mood_hint if narratives else "未知",
    }

    existing = db.scalar(select(UserProfile).where(
        UserProfile.tenant_id == tenant_id,
        UserProfile.user_id == user_id,
    ))
    if existing:
        existing.traits = traits
        existing.version = existing.version + 1
        existing.updated_at = utcnow()
        db.flush()
        return existing
    row = UserProfile(tenant_id=tenant_id, user_id=user_id, traits=traits, version=1)
    db.add(row)
    db.flush()
    return row
