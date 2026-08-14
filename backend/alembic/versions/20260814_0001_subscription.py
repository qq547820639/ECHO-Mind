"""v0.7 订阅生命周期：users 订阅字段 + activation_codes.subscription_days。

- users.subscription_expires_at（可空 DateTime）：订阅到期时间；
  NULL = 永不过期（机构旧用户/未纳入订阅制的存量数据保持向后兼容）；
- users.subscription_plan（默认 standard）：订阅档位；
- activation_codes.subscription_days（可空 Integer）：该码兑换成功后授予的
  订阅天数（NULL = 不改变订阅状态，兼容机构旧码语义）。

幂等 DDL 风格与 20260812_0002 一致（SQLite/PG 通用：列存在才加）。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260814_0001"
down_revision = "20260813_0001"
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
    _add_column("users", sa.Column("subscription_expires_at", sa.DateTime(timezone=True), nullable=True))
    _add_column("users", sa.Column("subscription_plan", sa.String(40), nullable=True))
    _add_column("activation_codes", sa.Column("subscription_days", sa.Integer(), nullable=True))


def downgrade() -> None:
    def _drop(table: str, name: str) -> None:
        if table not in _existing_tables():
            return
        if name in _existing_columns(table):
            op.drop_column(table, name)

    _drop("users", "subscription_expires_at")
    _drop("users", "subscription_plan")
    _drop("activation_codes", "subscription_days")
