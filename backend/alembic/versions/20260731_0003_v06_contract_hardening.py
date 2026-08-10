"""v0.6 契约收口：窗口索引 + 治理列 + skill_completions 表。

Revision ID: 20260731_0003
Revises: 20260731_0002
Create Date: 2026-07-31

操作（全部为增量/可空，不删不改旧数据，旧代码可继续运行）：
1. derived_features 建复合索引 (tenant_id, user_id, window_start) —— 支撑时间范围查询
2. daily_narratives.last_rebuilt_at / user_profiles.rebuilt_at 可空列
3. skills.signed_by / signed_at 可空列（治理字段）
4. derived_features.sources_present JSON 可空列（窗口内信号源集合）
5. 新增 skill_completions 表（Skill 执行完成/停止上报，幂等键 tenant_id+event_id）

对全新库做 upgrade head 时，基线已通过 Base.metadata.create_all 建出上述结构与
模型新列；此处通过 inspector 检查做幂等跳过，避免对已存在对象重复 ALTER。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260731_0003"
down_revision = "20260731_0002"
branch_labels = None
depends_on = None


def _existing_tables() -> set[str]:
    bind = op.get_bind()
    return set(sa.inspect(bind).get_table_names())


def _existing_columns(table_name: str) -> set[str]:
    bind = op.get_bind()
    return {col["name"] for col in sa.inspect(bind).get_columns(table_name)}


def _existing_indexes(table_name: str) -> set[str]:
    bind = op.get_bind()
    return {idx["name"] for idx in sa.inspect(bind).get_indexes(table_name)}


def _add_column_if_missing(table: str, column: sa.Column) -> None:
    if table not in _existing_tables():
        return
    if column.name in _existing_columns(table):
        return
    op.add_column(table, column)


def upgrade() -> None:
    tables = _existing_tables()

    # 1. 复合窗口索引
    if "derived_features" in tables:
        indexes = _existing_indexes("derived_features")
        if "ix_derived_features_tenant_user_window_start" not in indexes:
            op.create_index(
                "ix_derived_features_tenant_user_window_start",
                "derived_features",
                ["tenant_id", "user_id", "window_start"],
            )

    # 2. 治理时间列（可空）
    _add_column_if_missing(
        "daily_narratives",
        sa.Column("last_rebuilt_at", sa.DateTime(timezone=True), nullable=True),
    )
    _add_column_if_missing(
        "user_profiles",
        sa.Column("rebuilt_at", sa.DateTime(timezone=True), nullable=True),
    )

    # 3. skills 治理字段（可空）
    _add_column_if_missing("skills", sa.Column("signed_by", sa.String(length=120), nullable=True))
    _add_column_if_missing("skills", sa.Column("signed_at", sa.DateTime(timezone=True), nullable=True))

    # 4. derived_features.sources_present（可空 JSON）
    _add_column_if_missing("derived_features", sa.Column("sources_present", sa.JSON(), nullable=True))

    # 5. skill_completions 表
    if "skill_completions" not in tables:
        op.create_table(
            "skill_completions",
            sa.Column("id", sa.String(length=80), nullable=False),
            sa.Column("event_id", sa.String(length=80), nullable=False),
            sa.Column("tenant_id", sa.String(length=80), nullable=False),
            sa.Column("user_id", sa.String(length=80), nullable=False),
            sa.Column("skill_id", sa.String(length=80), nullable=False),
            sa.Column("status", sa.String(length=40), nullable=False),
            sa.Column("duration_seconds", sa.Integer(), nullable=False),
            sa.Column("client_time", sa.DateTime(timezone=True), nullable=False),
            sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
            sa.ForeignKeyConstraint(["user_id"], ["users.id"]),
            sa.ForeignKeyConstraint(["skill_id"], ["skills.id"]),
            sa.PrimaryKeyConstraint("id"),
            sa.UniqueConstraint("tenant_id", "event_id", name="uq_skill_completion_tenant_event"),
        )
        op.create_index("ix_skill_completions_tenant_id", "skill_completions", ["tenant_id"])
        op.create_index("ix_skill_completions_user_id", "skill_completions", ["user_id"])
        op.create_index("ix_skill_completions_skill_id", "skill_completions", ["skill_id"])


def downgrade() -> None:
    tables = _existing_tables()

    if "skill_completions" in tables:
        op.drop_index("ix_skill_completions_skill_id", table_name="skill_completions")
        op.drop_index("ix_skill_completions_user_id", table_name="skill_completions")
        op.drop_index("ix_skill_completions_tenant_id", table_name="skill_completions")
        op.drop_table("skill_completions")

    if "derived_features" in tables:
        indexes = _existing_indexes("derived_features")
        if "ix_derived_features_tenant_user_window_start" in indexes:
            op.drop_index("ix_derived_features_tenant_user_window_start", table_name="derived_features")
        if "sources_present" in _existing_columns("derived_features"):
            op.drop_column("derived_features", "sources_present")

    if "skills" in tables:
        cols = _existing_columns("skills")
        if "signed_at" in cols:
            op.drop_column("skills", "signed_at")
        if "signed_by" in cols:
            op.drop_column("skills", "signed_by")

    if "user_profiles" in tables and "rebuilt_at" in _existing_columns("user_profiles"):
        op.drop_column("user_profiles", "rebuilt_at")

    if "daily_narratives" in tables and "last_rebuilt_at" in _existing_columns("daily_narratives"):
        op.drop_column("daily_narratives", "last_rebuilt_at")
