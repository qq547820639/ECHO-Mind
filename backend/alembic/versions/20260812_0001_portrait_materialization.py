"""v0.7 (Portrait Core, Phase 2): materialization_state — 画像自动物化状态。

- materialization_state：每次 ingest 聚合更新后标记 dirty 的每日状态行；
- 唯一约束 (tenant_id, user_id, local_date) 保证同一天多次 ingest 合并为一行（debounce）；
- 与 0004/0005/0006/0007 相同，使用幂等 DDL（IF NOT EXISTS / 索引存在性检查）。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260812_0001"
down_revision = "20260810_0007"
branch_labels = None
depends_on = None

UPGRADE_SQL = """
CREATE TABLE IF NOT EXISTS materialization_state (
    id VARCHAR(80) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    local_date DATE NOT NULL,
    dirty BOOLEAN NOT NULL DEFAULT 1,
    last_materialized_at DATETIME,
    materialization_version INTEGER NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_mz_tenant_user_date UNIQUE (tenant_id, user_id, local_date),
    CONSTRAINT fk_mz_user FOREIGN KEY (user_id) REFERENCES users (id)
);
"""

INDEXES = [
    ("ix_mz_tenant_id", "materialization_state", ["tenant_id"]),
    ("ix_mz_user_id", "materialization_state", ["user_id"]),
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
    op.execute("DROP TABLE IF EXISTS materialization_state")
