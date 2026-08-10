"""v0.7 (Portrait Core, Milestone C): personal_baselines — 个人行为基线。

- personal_baselines：近 28 天有效日的 robust 统计分桶（weekday/weekend/all_days）；
- 与 0004/0005 相同，使用幂等 DDL；
- 产品契约：metrics 只含行为指标，不含任何情绪/心理字段。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260810_0006"
down_revision = "20260810_0005"
branch_labels = None
depends_on = None

UPGRADE_SQL = """
CREATE TABLE IF NOT EXISTS personal_baselines (
    id VARCHAR(80) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    baseline_version VARCHAR(40) NOT NULL DEFAULT 'base-v1',
    bucket VARCHAR(20) NOT NULL,
    window_start DATE NOT NULL,
    window_end DATE NOT NULL,
    valid_days INTEGER NOT NULL DEFAULT 0,
    metrics JSON NOT NULL DEFAULT '{}',
    generated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_pb_tenant_user_bucket_version UNIQUE (tenant_id, user_id, bucket, baseline_version),
    CONSTRAINT fk_pb_user FOREIGN KEY (user_id) REFERENCES users (id)
);
"""

INDEXES = [
    ("ix_pb_tenant_id", "personal_baselines", ["tenant_id"]),
    ("ix_pb_user_id", "personal_baselines", ["user_id"]),
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
    op.execute("DROP TABLE IF EXISTS personal_baselines")
