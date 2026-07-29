"""Path A v0.2 initial pilot schema.

Revision ID: 20260729_0001
Revises:
Create Date: 2026-07-29
"""
from alembic import op
from app.database import Base
from app import models  # noqa: F401

revision = "20260729_0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Baseline migration for a new pilot database. Existing v0.1 demo SQLite databases
    # are disposable and must not be upgraded in place.
    Base.metadata.create_all(bind=op.get_bind())


def downgrade() -> None:
    Base.metadata.drop_all(bind=op.get_bind())
