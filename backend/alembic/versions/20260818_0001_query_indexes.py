"""审计 P2-5/P2-10 修复：高频列表/读头查询复合索引。

- escalations(tenant_id, opened_at)：列表按 (opened_at desc, id desc) keyset 分页
  （cursor 亦依赖 opened_at 比较），此前仅 tenant_id/status 单列索引；
- audit_events(tenant_id, occurred_at)：append_audit 读头
  ``ORDER BY occurred_at DESC, id DESC LIMIT 1`` 与 verify_audit_chain 排序
  均按租户前缀 + occurred_at，此前仅 tenant_id 单列索引。

幂等 DDL 风格与 20260816_0001 一致（SQLite/PG 通用：索引存在才建/删）。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260818_0001"
down_revision = "20260816_0001"
branch_labels = None
depends_on = None

INDEXES: list[tuple[str, str, tuple[str, ...]]] = [
    ("ix_escalations_tenant_opened_at", "escalations", ("tenant_id", "opened_at")),
    ("ix_audit_events_tenant_occurred_at", "audit_events", ("tenant_id", "occurred_at")),
]


def _existing_indexes(table: str) -> set[str]:
    return {idx["name"] for idx in sa.inspect(op.get_bind()).get_indexes(table)}


def _existing_tables() -> set[str]:
    return set(sa.inspect(op.get_bind()).get_table_names())


def upgrade() -> None:
    for name, table, columns in INDEXES:
        if table not in _existing_tables():
            continue
        if name not in _existing_indexes(table):
            op.create_index(name, table, columns)


def downgrade() -> None:
    for name, table, _columns in INDEXES:
        if table not in _existing_tables():
            continue
        if name in _existing_indexes(table):
            op.drop_index(name, table_name=table)
