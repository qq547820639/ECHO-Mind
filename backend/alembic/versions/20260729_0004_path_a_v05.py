"""Path A v0.5 workbench fields: user city and escalation takeover record.

Adds the nullable users.city column (机构工作台队列展示属地信息) and the
takeover-record lifecycle columns on escalations (contact_method,
contact_succeeded, safety_status, emergency_contact_called, referred_12356,
called_emergency_services, follow_up_plan, operator_signature) that the close
flow validates and writes. All new escalation columns are lifecycle fields, so
the PostgreSQL reject_escalation_tamper trigger (revision 20260729_0002), which
blocklists only the immutable fact columns, needs no change. On SQLite the
migration is a no-op: the 0001 baseline creates tables from current metadata
and demo databases are disposable; the ORM guard covers enforcement there.

Revision ID: 20260729_0004
Revises: 20260729_0003
Create Date: 2026-07-29
"""
import sqlalchemy as sa
from alembic import op

revision = "20260729_0004"
down_revision = "20260729_0003"
branch_labels = None
depends_on = None

ESCALATION_COLUMNS = (
    "contact_method",
    "contact_succeeded",
    "safety_status",
    "emergency_contact_called",
    "referred_12356",
    "called_emergency_services",
    "follow_up_plan",
    "operator_signature",
)


def _dialect_name() -> str:
    bind = op.get_bind()
    if bind is not None:
        return bind.dialect.name
    return op.get_context().dialect.name


def upgrade() -> None:
    if _dialect_name() != "postgresql":
        # SQLite (tests/demo): 0001 baseline already builds current metadata.
        return
    op.add_column("users", sa.Column("city", sa.String(length=120), nullable=True))
    op.add_column("escalations", sa.Column("contact_method", sa.String(length=80), nullable=True))
    op.add_column("escalations", sa.Column("contact_succeeded", sa.Boolean(), nullable=True))
    op.add_column("escalations", sa.Column("safety_status", sa.String(length=200), nullable=True))
    op.add_column("escalations", sa.Column("emergency_contact_called", sa.Boolean(), nullable=True))
    op.add_column("escalations", sa.Column("referred_12356", sa.Boolean(), nullable=True))
    op.add_column("escalations", sa.Column("called_emergency_services", sa.Boolean(), nullable=True))
    op.add_column("escalations", sa.Column("follow_up_plan", sa.Text(), nullable=True))
    op.add_column("escalations", sa.Column("operator_signature", sa.String(length=120), nullable=True))


def downgrade() -> None:
    if _dialect_name() != "postgresql":
        return
    for column in reversed(ESCALATION_COLUMNS):
        op.drop_column("escalations", column)
    op.drop_column("users", "city")
