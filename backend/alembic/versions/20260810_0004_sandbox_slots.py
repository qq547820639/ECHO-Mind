"""v0.6.1: sandbox tenant execution slots — 执行期原子并发配额。

- sandbox_tenant_slots：租户级 running_count + heartbeat（worker crash 回收兜底）
- 与 0003 相同，使用幂等 DDL（兼容基线 0001 的 Base.metadata.create_all 模式）。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260810_0004"
down_revision = "20260810_0003"
branch_labels = None
depends_on = None

UPGRADE_SQL = """
CREATE TABLE IF NOT EXISTS sandbox_tenant_slots (
    tenant_id VARCHAR(80) NOT NULL PRIMARY KEY,
    running_count INTEGER NOT NULL DEFAULT 0,
    heartbeat_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
"""


def _existing_tables() -> set[str]:
    return set(sa.inspect(op.get_bind()).get_table_names())


def upgrade() -> None:
    for statement in UPGRADE_SQL.split(";"):
        stmt = statement.strip()
        if stmt:
            op.execute(stmt)


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS sandbox_tenant_slots")
