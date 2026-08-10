"""v0.6.1: activation codes — 正式激活码模型，取代 User.external_ref 隐式激活语义。

- activation_codes：机构激活码（hash 存储 / TTL / 一次性消费 / 防爆破计数）
- activation_attempts：兑换尝试审计 + IP/device/code 维度 rate limit 数据源

**幂等说明**：基线迁移 20260729_0001 使用 ``Base.metadata.create_all``
（历史遗留模式），会在全新库上按**当前** models 元数据提前建出本迁移的表。
因此本迁移全部使用 IF NOT EXISTS 幂等 DDL（SQLite / PostgreSQL 均支持），
同时兼容「新库直建」与「旧库增量升级」两条路径。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260810_0003"
down_revision = "20260810_0002"
branch_labels = None
depends_on = None

UPGRADE_SQL = """
CREATE TABLE IF NOT EXISTS activation_codes (
    id VARCHAR(80) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(80),
    code_hash VARCHAR(128) NOT NULL,
    created_by VARCHAR(120) NOT NULL DEFAULT 'system',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME,
    used_at DATETIME,
    revoked_at DATETIME,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    CONSTRAINT uq_activation_code_hash UNIQUE (code_hash),
    CONSTRAINT fk_activation_codes_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE TABLE IF NOT EXISTS activation_attempts (
    id VARCHAR(80) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(80),
    code_hash VARCHAR(128) NOT NULL,
    actor_ip VARCHAR(64),
    device_id VARCHAR(128),
    result VARCHAR(32) NOT NULL DEFAULT 'failure',
    attempted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
"""

INDEXES = [
    ("ix_activation_codes_tenant_created", "activation_codes", ["tenant_id", "created_at"]),
    ("ix_activation_codes_user_id", "activation_codes", ["user_id"]),
    ("ix_activation_attempts_lookup", "activation_attempts", ["code_hash", "actor_ip", "attempted_at"]),
    ("ix_activation_attempts_tenant", "activation_attempts", ["tenant_id"]),
    ("ix_activation_attempts_ip", "activation_attempts", ["actor_ip"]),
    ("ix_activation_attempts_device", "activation_attempts", ["device_id"]),
    ("ix_activation_attempts_time", "activation_attempts", ["attempted_at"]),
]


def _existing_indexes(table: str) -> set[str]:
    return {ix["name"] for ix in sa.inspect(op.get_bind()).get_indexes(table)}


def _existing_tables() -> set[str]:
    return set(sa.inspect(op.get_bind()).get_table_names())


def upgrade() -> None:
    # SQLite driver 一次只能执行一条语句：逐条执行
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
    op.execute("DROP TABLE IF EXISTS activation_attempts")
    op.execute("DROP TABLE IF EXISTS activation_codes")
