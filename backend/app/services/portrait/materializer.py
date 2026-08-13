"""画像自动物化服务（Portrait Core Phase 2 + Phase 5, D）：DB-backed dirty 状态机。

产品承诺"每天自动生成画像"：ingest 聚合更新后标记 daily dirty，
本服务在 ingest 成功后（独立事务）对 dirty 日期执行
  build_baseline（generate_portrait 内部） → generate_portrait，
成功后清 dirty、更新 last_materialized_at / last_baseline_rebuild_at、
递增 materialization_version。

设计要点：
- debounce/coalescing：唯一约束 (tenant_id, user_id, local_date) 保证同一天
  多次 ingest 只合并为一行 dirty；materialization 粒度按 local_date；
- Phase 5（D）debounce：每天数据到达后**最多每 MATERIALIZE_MIN_INTERVAL 物化一次**
  —— 对 local_date == today 且距上次物化不足间隔的 dirty 日期跳过（保留 dirty，
  下次触发再物化）；**晚到日期（< today）立即物化**（backfill 不延迟）；
  首次物化（last_materialized_at is null）始终立即执行；
- late feature 安全：晚到窗口 → 该 local_date 置 dirty → 下次触发补齐；
- 失败可重试：materialize 抛异常时 dirty 保持 true（claim 回滚或显式恢复），
  下次触发重试；
- 并发安全：materialize 前用原子 UPDATE ... WHERE dirty=true 占位（同一事务内
  写 portrait），SQLite/PG 均安全；竞争方 rowcount=0 直接跳过。
"""
from __future__ import annotations

from datetime import UTC, date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.models import DailyPortrait, MaterializationState, utcnow

from app.services.portrait.engine import generate_portrait

#: 当天数据到达后的物化最小间隔（15 分钟）：间隔内多次 ingest 合并为一次物化，
#: 避免每 5 分钟一次 ingest 导致频繁全量重算 28 天 baseline。
MATERIALIZE_MIN_INTERVAL = timedelta(minutes=15)


def _as_utc(dt: datetime) -> datetime:
    """SQLite 读回的 naive datetime 一律按 UTC 解释。"""
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt


def mark_dirty(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    local_date: date,
) -> None:
    """幂等 upsert materialization_state：置 dirty=true（同一天合并为一行）。

    写路径：仅由 ingest 聚合更新后调用；调用方负责 commit。
    """
    now = utcnow()
    existing = db.scalar(select(MaterializationState).where(
        MaterializationState.tenant_id == tenant_id,
        MaterializationState.user_id == user_id,
        MaterializationState.local_date == local_date,
    ))
    if existing is not None:
        existing.dirty = True
        existing.updated_at = now
        db.flush()
        return
    db.add(MaterializationState(
        tenant_id=tenant_id,
        user_id=user_id,
        local_date=local_date,
        dirty=True,
        materialization_version=0,
        updated_at=now,
    ))
    db.flush()


def _should_materialize(state: MaterializationState, today: date, now: datetime) -> bool:
    """debounce 判定：返回 False 表示本次跳过（保留 dirty，下次触发再物化）。

    - 晚到日期（< today）→ 立即物化（backfill 不延迟）；
    - 首次物化（last_materialized_at is None）→ 立即物化；
    - 当天且距上次物化 < MATERIALIZE_MIN_INTERVAL → 跳过（合并到下次）。
    """
    if state.local_date < today:
        return True
    if state.last_materialized_at is None:
        return True
    elapsed = now - _as_utc(state.last_materialized_at)
    return elapsed >= MATERIALIZE_MIN_INTERVAL


def _claim_dirty(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    local_date: date,
) -> bool:
    """原子占位：仅当该行 dirty=true 时置 dirty=false（防并发重复物化）。

    返回 True 表示本调用方拿到该日期物化权；False 表示已被并发方处理/不存在。
    """
    result = db.execute(
        update(MaterializationState)
        .where(
            MaterializationState.tenant_id == tenant_id,
            MaterializationState.user_id == user_id,
            MaterializationState.local_date == local_date,
            MaterializationState.dirty.is_(True),
        )
        .values(dirty=False, updated_at=utcnow())
    )
    # CursorResult.rowcount：0=不存在/已被并发方处理，1=本调用方拿到物化权
    rowcount = result.rowcount or 0  # type: ignore[attr-defined]
    return rowcount == 1


def _materialize_one(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    local_date: date,
) -> DailyPortrait | None:
    """物化单个 dirty 日期；成功返回画像行，未占位（并发跳过）返回 None。"""
    if not _claim_dirty(db, tenant_id=tenant_id, user_id=user_id, local_date=local_date):
        return None
    try:
        row = generate_portrait(db, tenant_id=tenant_id, user_id=user_id, local_date=local_date)
    except Exception:
        # 失败可重试：恢复 dirty=true（即使调用方 catch 后 commit 也保持可重试）。
        db.execute(
            update(MaterializationState)
            .where(
                MaterializationState.tenant_id == tenant_id,
                MaterializationState.user_id == user_id,
                MaterializationState.local_date == local_date,
            )
            .values(dirty=True, updated_at=utcnow())
        )
        raise
    now = utcnow()
    db.execute(
        update(MaterializationState)
        .where(
            MaterializationState.tenant_id == tenant_id,
            MaterializationState.user_id == user_id,
            MaterializationState.local_date == local_date,
        )
        .values(
            dirty=False,
            last_materialized_at=now,
            last_baseline_rebuild_at=now,
            materialization_version=MaterializationState.materialization_version + 1,
            updated_at=now,
        )
    )
    return row


def materialize_dirty(
    db: Session,
    *,
    tenant_id: str,
    user_id: str,
    tz_name: str,
    today: date | None = None,
) -> list[DailyPortrait]:
    """处理该用户全部 dirty 日期（<= today，升序，晚到日期先补齐）。

    - 对每个 dirty 日期：generate_portrait（内部 upsert 聚合 + build_baseline +
      upsert 画像，幂等）；
    - Phase 5（D）debounce：当天日期距上次物化不足间隔则跳过（保留 dirty，
      下次触发再物化）；晚到日期立即物化；
    - 成功后清 dirty / 更新 last_materialized_at / 递增 materialization_version；
    - 失败抛异常且 dirty 保持 true（调用方可重试）；调用方负责 commit/rollback。
    """
    if today is None:
        today = datetime.now(UTC).astimezone(ZoneInfo(tz_name)).date()
    now = utcnow()
    dirty_rows = db.scalars(
        select(MaterializationState).where(
            MaterializationState.tenant_id == tenant_id,
            MaterializationState.user_id == user_id,
            MaterializationState.dirty.is_(True),
            MaterializationState.local_date <= today,
        ).order_by(MaterializationState.local_date.asc())
    ).all()
    materialized: list[DailyPortrait] = []
    for state in dirty_rows:
        if not _should_materialize(state, today, now):
            # 间隔内合并：保留 dirty=true，下次 ingest 触发再物化
            continue
        row = _materialize_one(db, tenant_id=tenant_id, user_id=user_id, local_date=state.local_date)
        if row is not None:
            materialized.append(row)
    return materialized
