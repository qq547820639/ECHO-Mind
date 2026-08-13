"""v0.7 (Portrait Core, Phase 5): 聚合列更名 + materializer debounce + 可复现摘要。

- daily_behavior_aggregates.rhythm_regularity → active_hour_spread
  （语义实为活跃小时覆盖，消除 regularity 命名误导；SQLite/PG 均支持
  ALTER TABLE RENAME COLUMN，幂等：列存在才执行）；
- materialization_state 增加 last_baseline_rebuild_at（可空）：最近一次
  完整画像物化（含 28 天 baseline 重建）时间，用于当天 debounce；
- daily_portraits 增加 baseline_snapshot_digest VARCHAR(64)（可空）：
  基线 metrics 规范化序列化的 SHA-256 摘要（可复现性校验）。

幂等 DDL 风格与 0004/0005/0006/0007/20260812_0001 一致。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260812_0002"
down_revision = "20260812_0001"
branch_labels = None
depends_on = None


def _existing_columns(table: str) -> set[str]:
    return {col["name"] for col in sa.inspect(op.get_bind()).get_columns(table)}


def _existing_tables() -> set[str]:
    return set(sa.inspect(op.get_bind()).get_table_names())


def _add_column(table: str, column: sa.Column) -> None:
    if table not in _existing_tables():
        return
    if column.name not in _existing_columns(table):
        op.add_column(table, column)


def _drop_column(table: str, column_name: str) -> None:
    if table not in _existing_tables():
        return
    if column_name in _existing_columns(table):
        op.drop_column(table, column_name)


def upgrade() -> None:
    # 1. rhythm_regularity → active_hour_spread（幂等：列存在才改名）
    if "daily_behavior_aggregates" in _existing_tables():
        cols = _existing_columns("daily_behavior_aggregates")
        if "rhythm_regularity" in cols and "active_hour_spread" not in cols:
            op.alter_column(
                "daily_behavior_aggregates",
                "rhythm_regularity",
                new_column_name="active_hour_spread",
                existing_type=sa.Float(),
            )

    # 2. materialization_state.last_baseline_rebuild_at（可空）
    _add_column(
        "materialization_state",
        sa.Column("last_baseline_rebuild_at", sa.DateTime(timezone=True), nullable=True),
    )

    # 3. daily_portraits.baseline_snapshot_digest（可空）
    _add_column(
        "daily_portraits",
        sa.Column("baseline_snapshot_digest", sa.String(64), nullable=True),
    )


def downgrade() -> None:
    # 逆序回滚
    _drop_column("daily_portraits", "baseline_snapshot_digest")
    _drop_column("materialization_state", "last_baseline_rebuild_at")
    if "daily_behavior_aggregates" in _existing_tables():
        cols = _existing_columns("daily_behavior_aggregates")
        if "active_hour_spread" in cols and "rhythm_regularity" not in cols:
            op.alter_column(
                "daily_behavior_aggregates",
                "active_hour_spread",
                new_column_name="rhythm_regularity",
                existing_type=sa.Float(),
            )
