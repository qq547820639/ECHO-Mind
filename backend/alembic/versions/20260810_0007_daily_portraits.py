"""v0.7 (Portrait Core, Milestone D/E): daily_portraits — 当日画像。

- daily_portraits：基线就绪后的 5 维度确定性画像（status/confidence/dimensions/…）；
- timezone 列承载 timezone_used（生成画像时实际使用的用户时区）；
- 与 0004/0005/0006 相同，使用幂等 DDL。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260810_0007"
down_revision = "20260810_0006"
branch_labels = None
depends_on = None

UPGRADE_SQL = """
CREATE TABLE IF NOT EXISTS daily_portraits (
    id VARCHAR(80) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    local_date DATE NOT NULL,
    timezone VARCHAR(80) NOT NULL,
    status VARCHAR(30) NOT NULL,
    confidence VARCHAR(10) NOT NULL,
    baseline_start DATE,
    baseline_end DATE,
    baseline_valid_days INTEGER NOT NULL DEFAULT 0,
    baseline_version VARCHAR(40),
    coverage JSON NOT NULL DEFAULT '{}',
    dimensions JSON NOT NULL DEFAULT '{}',
    highlights JSON NOT NULL DEFAULT '[]',
    summary TEXT NOT NULL DEFAULT '',
    facts JSON NOT NULL DEFAULT '[]',
    schema_version VARCHAR(40) NOT NULL DEFAULT 'portrait-v1',
    generated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finalized_at DATETIME,
    CONSTRAINT uq_dp_tenant_user_date UNIQUE (tenant_id, user_id, local_date),
    CONSTRAINT fk_dp_user FOREIGN KEY (user_id) REFERENCES users (id)
);
"""

INDEXES = [
    ("ix_dp_tenant_id", "daily_portraits", ["tenant_id"]),
    ("ix_dp_user_id", "daily_portraits", ["user_id"]),
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
    op.execute("DROP TABLE IF EXISTS daily_portraits")
