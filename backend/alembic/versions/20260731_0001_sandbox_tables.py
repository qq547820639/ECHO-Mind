"""T08 自进化沙箱骨架：skills / tools / sandbox_runs 三表。

Revision ID: 20260731_0001
Revises: 20260729_0004
Create Date: 2026-07-31

说明：基线迁移 20260729_0001 用 Base.metadata.create_all 一次性建出 models.py
中全部表；本迁移面向“已升级到基线的存量库”增量补建沙箱三表。对全新库做
upgrade head 时，基线已建出三表，此处通过 inspector 检查做幂等跳过，避免冲突。
"""
import sqlalchemy as sa
from alembic import op

revision = "20260731_0001"
down_revision = "20260729_0004"
branch_labels = None
depends_on = None


def _existing_tables() -> set[str]:
    bind = op.get_bind()
    return set(sa.inspect(bind).get_table_names())


def upgrade() -> None:
    existing = _existing_tables()

    # skills：技能包，draft/reviewed/signed/retired 生命周期
    if "skills" not in existing:
        op.create_table(
            "skills",
            sa.Column("id", sa.String(length=80), nullable=False),
            sa.Column("tenant_id", sa.String(length=80), nullable=False),
            sa.Column("user_id", sa.String(length=80), nullable=False),
            sa.Column("name", sa.String(length=160), nullable=False),
            sa.Column("version", sa.Integer(), nullable=False),
            sa.Column("trigger_conditions", sa.JSON(), nullable=False),
            sa.Column("guardrails", sa.JSON(), nullable=False),
            sa.Column("steps", sa.JSON(), nullable=False),
            sa.Column("status", sa.String(length=40), nullable=False),
            sa.Column("content_hash", sa.String(length=128), nullable=True),
            sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
            sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
            sa.ForeignKeyConstraint(["user_id"], ["users.id"]),
            sa.PrimaryKeyConstraint("id"),
            sa.UniqueConstraint(
                "tenant_id", "user_id", "name", "version",
                name="uq_skill_tenant_user_name_version",
            ),
        )
        op.create_index("ix_skills_tenant_id", "skills", ["tenant_id"])
        op.create_index("ix_skills_user_id", "skills", ["user_id"])

    # tools：工具调用契约，可绑定到某个 skill
    if "tools" not in existing:
        op.create_table(
            "tools",
            sa.Column("id", sa.String(length=80), nullable=False),
            sa.Column("tenant_id", sa.String(length=80), nullable=False),
            sa.Column("user_id", sa.String(length=80), nullable=False),
            sa.Column("skill_id", sa.String(length=80), nullable=True),
            sa.Column("name", sa.String(length=160), nullable=False),
            sa.Column("description", sa.Text(), nullable=False),
            sa.Column("parameters_schema", sa.JSON(), nullable=False),
            sa.Column("returns_schema", sa.JSON(), nullable=False),
            sa.Column("status", sa.String(length=40), nullable=False),
            sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
            sa.ForeignKeyConstraint(["user_id"], ["users.id"]),
            sa.ForeignKeyConstraint(["skill_id"], ["skills.id"]),
            sa.PrimaryKeyConstraint("id"),
            sa.UniqueConstraint("tenant_id", "user_id", "name", name="uq_tool_tenant_user_name"),
        )
        op.create_index("ix_tools_tenant_id", "tools", ["tenant_id"])
        op.create_index("ix_tools_user_id", "tools", ["user_id"])

    # sandbox_runs：每日沙箱运行记录
    if "sandbox_runs" not in existing:
        op.create_table(
            "sandbox_runs",
            sa.Column("id", sa.String(length=80), nullable=False),
            sa.Column("tenant_id", sa.String(length=80), nullable=False),
            sa.Column("user_id", sa.String(length=80), nullable=False),
            sa.Column("run_date", sa.Date(), nullable=False),
            sa.Column("status", sa.String(length=40), nullable=False),
            sa.Column("gaps_found", sa.JSON(), nullable=False),
            sa.Column("tools_generated", sa.Integer(), nullable=False),
            sa.Column("tools_validated", sa.Integer(), nullable=False),
            sa.Column("skills_inducted", sa.Integer(), nullable=False),
            sa.Column("error_message", sa.Text(), nullable=True),
            sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
            sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
            sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
            sa.ForeignKeyConstraint(["user_id"], ["users.id"]),
            sa.PrimaryKeyConstraint("id"),
            sa.UniqueConstraint(
                "tenant_id", "user_id", "run_date",
                name="uq_sandbox_tenant_user_date",
            ),
        )
        op.create_index("ix_sandbox_runs_tenant_id", "sandbox_runs", ["tenant_id"])
        op.create_index("ix_sandbox_runs_user_id", "sandbox_runs", ["user_id"])


def downgrade() -> None:
    op.drop_index("ix_sandbox_runs_user_id", table_name="sandbox_runs", if_exists=True)
    op.drop_index("ix_sandbox_runs_tenant_id", table_name="sandbox_runs", if_exists=True)
    op.drop_table("sandbox_runs")
    op.drop_index("ix_tools_user_id", table_name="tools", if_exists=True)
    op.drop_index("ix_tools_tenant_id", table_name="tools", if_exists=True)
    op.drop_table("tools")
    op.drop_index("ix_skills_user_id", table_name="skills", if_exists=True)
    op.drop_index("ix_skills_tenant_id", table_name="skills", if_exists=True)
    op.drop_table("skills")
