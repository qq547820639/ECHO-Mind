"""P5 灰度回滚：Tenant 表新增 feature_flags JSON 字段。

Revision ID: 20260731_0002
Revises: 20260731_0001
Create Date: 2026-07-31

说明：为 tenants 表追加 feature_flags JSON 列，默认三开关全开
（passive_sensing_enabled / sandbox_enabled / skills_delivery_enabled）。
对全新库做 upgrade head 时，基线迁移已通过 Base.metadata.create_all
建出含 feature_flags 的表结构；此处通过 inspector 检查做幂等跳过，
避免对已存在列重复 ALTER 引发冲突。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260731_0002"
down_revision = "20260731_0001"
branch_labels = None
depends_on = None


_DEFAULT_FLAGS_JSON = (
    '{"passive_sensing_enabled": true, '
    '"sandbox_enabled": true, '
    '"skills_delivery_enabled": true}'
)


def _existing_tables() -> set[str]:
    bind = op.get_bind()
    return set(sa.inspect(bind).get_table_names())


def _existing_columns(table_name: str) -> set[str]:
    bind = op.get_bind()
    return {col["name"] for col in sa.inspect(bind).get_columns(table_name)}


def upgrade() -> None:
    existing = _existing_tables()
    if "tenants" not in existing:
        # 全新库的基线迁移会建表；此处仅处理增量补列场景
        return
    cols = _existing_columns("tenants")
    if "feature_flags" in cols:
        return
    op.add_column(
        "tenants",
        sa.Column(
            "feature_flags",
            sa.JSON(),
            nullable=False,
            server_default=_DEFAULT_FLAGS_JSON,
        ),
    )


def downgrade() -> None:
    existing = _existing_tables()
    if "tenants" not in existing:
        return
    cols = _existing_columns("tenants")
    if "feature_flags" not in cols:
        return
    op.drop_column("tenants", "feature_flags")
