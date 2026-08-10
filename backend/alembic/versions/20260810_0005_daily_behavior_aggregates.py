"""v0.7 (Portrait Core, Milestone B): daily_behavior_aggregates — 当日行为聚合。

- daily_behavior_aggregates：用户本地日（User.timezone 日界线）行为聚合指标行；
- 与 0004 相同，使用幂等 DDL（兼容基线 0001 的 Base.metadata.create_all 模式）；
- 产品契约：本表不含任何情绪/心理字段（mood/anxiety/stress/depression/loneliness/risk）。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260810_0005"
down_revision = "20260810_0004"
branch_labels = None
depends_on = None

UPGRADE_SQL = """
CREATE TABLE IF NOT EXISTS daily_behavior_aggregates (
    id VARCHAR(80) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    local_date DATE NOT NULL,
    timezone VARCHAR(80) NOT NULL,
    coverage_score FLOAT NOT NULL DEFAULT 0,
    valid_window_count INTEGER NOT NULL DEFAULT 0,
    expected_window_count INTEGER NOT NULL DEFAULT 288,
    movement_index FLOAT,
    movement_variability FLOAT,
    screen_on_minutes FLOAT NOT NULL DEFAULT 0,
    screen_open_count INTEGER NOT NULL DEFAULT 0,
    late_screen_minutes FLOAT NOT NULL DEFAULT 0,
    app_switch_count INTEGER NOT NULL DEFAULT 0,
    active_start_minute INTEGER,
    active_end_minute INTEGER,
    notification_count INTEGER NOT NULL DEFAULT 0,
    rhythm_regularity FLOAT,
    sources_present JSON NOT NULL DEFAULT '[]',
    missing_sources JSON NOT NULL DEFAULT '[]',
    schema_version VARCHAR(40) NOT NULL DEFAULT 'agg-v1',
    generated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finalized_at DATETIME,
    CONSTRAINT uq_dag_tenant_user_date UNIQUE (tenant_id, user_id, local_date),
    CONSTRAINT fk_dag_user FOREIGN KEY (user_id) REFERENCES users (id)
);
"""

INDEXES = [
    ("ix_dag_tenant_id", "daily_behavior_aggregates", ["tenant_id"]),
    ("ix_dag_user_id", "daily_behavior_aggregates", ["user_id"]),
]


def _existing_tables() -> set[str]:
    return set(sa.inspect(op.get_bind()).get_table_names())


def _existing_indexes(table: str) -> set[str]:
    return {ix["name"] for ix in sa.inspect(op.get_bind()).get_indexes(table)}


def upgrade() -> None:
    for statement in UPGRADE_SQL.split(";"):
        stmt = statement.strip()
        if stmt:
            op.execute(stmt)
    for name, table, columns in INDEXES:
        if table in _existing_tables() and name not in _existing_indexes(table):
            op.create_index(name, table, columns)


def downgrade() -> None:
    for name, table, _columns in reversed(INDEXES):
        if table in _existing_tables() and name in _existing_indexes(table):
            op.drop_index(name, table_name=table)
    op.execute("DROP TABLE IF EXISTS daily_behavior_aggregates")
