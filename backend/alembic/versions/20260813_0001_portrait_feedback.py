"""v0.7 (Portrait Core, Phase 6.6): portrait_feedback — 画像反馈。

- portrait_feedback：端侧「这个描述像今天的你吗？」LIKE/NOT_LIKE 反馈记录；
- 唯一约束 (tenant_id, event_id) 幂等（重复上报返回成功 + idempotent_replay）；
- 与 20260812_0001 相同，使用幂等 DDL（IF NOT EXISTS / 索引存在性检查）。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260813_0001"
down_revision = "20260812_0002"
branch_labels = None
depends_on = None

UPGRADE_SQL = """
CREATE TABLE IF NOT EXISTS portrait_feedback (
    id VARCHAR(80) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    event_id VARCHAR(80) NOT NULL,
    local_date DATE NOT NULL,
    feedback VARCHAR(20) NOT NULL,
    portrait_schema_version VARCHAR(40),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_portrait_feedback_tenant_event UNIQUE (tenant_id, event_id),
    CONSTRAINT fk_portrait_feedback_user FOREIGN KEY (user_id) REFERENCES users (id)
);
"""

INDEXES = [
    ("ix_portrait_feedback_tenant_id", "portrait_feedback", ["tenant_id"]),
    ("ix_portrait_feedback_user_id", "portrait_feedback", ["user_id"]),
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
    op.execute("DROP TABLE IF EXISTS portrait_feedback")
