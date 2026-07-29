"""Path A v0.4 SLA auto-escalation ladder fields on escalations.

Adds the lifecycle columns backing the SLA auto-escalation state machine:
escalation_level (0=第一值班人/1=第二值班人/2=机构负责人), per-tier notification
timestamps (notified_l1_at/notified_l2_at), the org-chain broken marker
(chain_broken_at) and the server-side delivery confirmation
(delivery_confirmed_at). All new columns are lifecycle fields, so the
PostgreSQL reject_escalation_tamper trigger (revision 20260729_0002), which
blocklists only the immutable fact columns, needs no change. On SQLite the
migration is a no-op: the 0001 baseline creates tables from current metadata
and demo databases are disposable; the ORM guard covers enforcement there.

Revision ID: 20260729_0003
Revises: 20260729_0002
Create Date: 2026-07-29
"""
import sqlalchemy as sa
from alembic import op

revision = "20260729_0003"
down_revision = "20260729_0002"
branch_labels = None
depends_on = None

NEW_COLUMNS = (
    "escalation_level",
    "notified_l1_at",
    "notified_l2_at",
    "chain_broken_at",
    "delivery_confirmed_at",
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
    op.add_column(
        "escalations",
        sa.Column("escalation_level", sa.Integer(), server_default="0", nullable=False),
    )
    op.add_column("escalations", sa.Column("notified_l1_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("escalations", sa.Column("notified_l2_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("escalations", sa.Column("chain_broken_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column(
        "escalations",
        sa.Column("delivery_confirmed_at", sa.DateTime(timezone=True), nullable=True),
    )


def downgrade() -> None:
    if _dialect_name() != "postgresql":
        return
    for column in reversed(NEW_COLUMNS):
        op.drop_column("escalations", column)
