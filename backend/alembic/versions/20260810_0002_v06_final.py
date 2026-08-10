"""v0.6 final 收口：users.external_ref 索引（支撑 Onboarding verify-code 跨租户扫描）。

Revision ID: 20260810_0002
Revises: 20260810_0001
Create Date: 2026-08-10

操作（纯增量，幂等）：
- users 表新增非唯一索引 ix_users_external_ref（external_ref），
  支撑 POST /v1/onboarding/verify-code 的跨租户激活码扫描。
  不删列、不改列、不影响既有唯一约束 uq_user_external（tenant_id, external_ref）。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260810_0002"
down_revision = "20260810_0001"
branch_labels = None
depends_on = None

INDEX_NAME = "ix_users_external_ref"


def _existing_indexes() -> set[str]:
    bind = op.get_bind()
    return {ix["name"] for ix in sa.inspect(bind).get_indexes("users")}


def _existing_tables() -> set[str]:
    bind = op.get_bind()
    return set(sa.inspect(bind).get_table_names())


def upgrade() -> None:
    if "users" not in _existing_tables():
        return
    if INDEX_NAME in _existing_indexes():
        return
    op.create_index(INDEX_NAME, "users", ["external_ref"])


def downgrade() -> None:
    if "users" not in _existing_tables():
        return
    if INDEX_NAME in _existing_indexes():
        op.drop_index(INDEX_NAME, table_name="users")
