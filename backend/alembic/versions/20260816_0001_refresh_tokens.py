"""ERA 32 R25：轮换式刷新令牌（users.refresh_token_hash / refresh_expires_at）。

- users.refresh_token_hash（可空 String(64)）：refresh token 的 SHA-256 哈希，
  绝不明文存储令牌本身；
- users.refresh_expires_at（可空 DateTime）：刷新令牌过期时间（默认 30 天）。

幂等 DDL 风格与 20260814_0001 一致（SQLite/PG 通用：列存在才加）。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260816_0001"
down_revision = "20260814_0001"
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


def upgrade() -> None:
    _add_column("users", sa.Column("refresh_token_hash", sa.String(64), nullable=True))
    _add_column("users", sa.Column("refresh_expires_at", sa.DateTime(timezone=True), nullable=True))


def downgrade() -> None:
    if "users" not in _existing_tables():
        return
    for name in ("refresh_token_hash", "refresh_expires_at"):
        if name in _existing_columns("users"):
            op.drop_column("users", name)
