"""v0.6 契约点 4/5 收口：Skill 执行契约 + 治理字段、DailyNarrative.mood_hint 置空。

Revision ID: 20260810_0001
Revises: 20260731_0003
Create Date: 2026-08-10

操作（全部为增量/可空或带默认值，不删不改旧数据，旧代码可继续运行）：
1. skills 新增执行契约列：action_type（白名单，默认 guided_steps）、
   estimated_duration_seconds / completion_schema / safety_constraints（可空）
2. skills 新增治理列：policy_version / review_evidence / supersedes_skill_id（可空）、
   revision（默认 1）
3. daily_narratives.mood_hint 改为可空（PRD 契约点 2：情绪语义废弃，新写入为 None）；
   PostgreSQL 直接 ALTER，SQLite 全新库经 create_all 已是可空（跳过）

对全新库做 upgrade head 时，基线 0001 已通过 Base.metadata.create_all 建出上述结构；
此处通过 inspector 检查做幂等跳过。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260810_0001"
down_revision = "20260731_0003"
branch_labels = None
depends_on = None


def _existing_tables() -> set[str]:
    bind = op.get_bind()
    return set(sa.inspect(bind).get_table_names())


def _existing_columns(table_name: str) -> set[str]:
    bind = op.get_bind()
    return {col["name"] for col in sa.inspect(bind).get_columns(table_name)}


def _add_column_if_missing(table: str, column: sa.Column) -> None:
    if table not in _existing_tables():
        return
    if column.name in _existing_columns(table):
        return
    op.add_column(table, column)


def upgrade() -> None:
    tables = _existing_tables()

    # 1+2. skills 执行契约 + 治理列（幂等）
    if "skills" in tables:
        _add_column_if_missing(
            "skills",
            sa.Column("action_type", sa.String(length=40), nullable=False,
                      server_default="guided_steps"),
        )
        _add_column_if_missing(
            "skills",
            sa.Column("estimated_duration_seconds", sa.Integer(), nullable=True),
        )
        _add_column_if_missing(
            "skills",
            sa.Column("completion_schema", sa.JSON(), nullable=True),
        )
        _add_column_if_missing(
            "skills",
            sa.Column("safety_constraints", sa.JSON(), nullable=True),
        )
        _add_column_if_missing(
            "skills",
            sa.Column("policy_version", sa.String(length=80), nullable=True),
        )
        _add_column_if_missing(
            "skills",
            sa.Column("review_evidence", sa.JSON(), nullable=True),
        )
        _add_column_if_missing(
            "skills",
            sa.Column("revision", sa.Integer(), nullable=False, server_default="1"),
        )
        _add_column_if_missing(
            "skills",
            sa.Column("supersedes_skill_id", sa.String(length=80), nullable=True),
        )

    # 3. daily_narratives.mood_hint → 可空（PRD 契约点 2）
    # PostgreSQL 直接 ALTER；SQLite 全新库经 create_all 已按当前模型建为可空，跳过。
    bind = op.get_bind()
    if bind.dialect.name == "postgresql" and "daily_narratives" in tables:
        cols = _existing_columns("daily_narratives")
        if "mood_hint" in cols:
            op.alter_column(
                "daily_narratives",
                "mood_hint",
                existing_type=sa.String(length=120),
                nullable=True,
            )


def downgrade() -> None:
    tables = _existing_tables()
    bind = op.get_bind()

    if bind.dialect.name == "postgresql" and "daily_narratives" in tables:
        cols = _existing_columns("daily_narratives")
        if "mood_hint" in cols:
            op.alter_column(
                "daily_narratives",
                "mood_hint",
                existing_type=sa.String(length=120),
                nullable=False,
                existing_server_default=sa.text("'平稳'"),
            )

    if "skills" in tables:
        cols = _existing_columns("skills")
        for column_name in (
            "supersedes_skill_id",
            "revision",
            "review_evidence",
            "policy_version",
            "safety_constraints",
            "completion_schema",
            "estimated_duration_seconds",
            "action_type",
        ):
            if column_name in cols:
                op.drop_column("skills", column_name)
