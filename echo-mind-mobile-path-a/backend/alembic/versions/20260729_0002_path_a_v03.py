"""Path A v0.3 append-only hardening for risk events and audit log.

Adds PostgreSQL triggers that forbid UPDATE/DELETE on the append-only tables
(risk_signals, audit_events) and forbid DELETE plus tampering with immutable
fact fields on escalations (its ack/takeover/close/review lifecycle columns
remain updatable). On SQLite there is no PL/pgSQL, so the migration is a no-op
and the application-layer ORM guard (app.services.immutability) provides the
equivalent protection.

Revision ID: 20260729_0002
Revises: 20260729_0001
Create Date: 2026-07-29
"""
from alembic import op

revision = "20260729_0002"
down_revision = "20260729_0001"
branch_labels = None
depends_on = None

APPEND_ONLY_TABLES = ("risk_signals", "audit_events")

REJECT_APPEND_ONLY_FUNCTION = """
CREATE OR REPLACE FUNCTION reject_append_only_mutation() RETURNS trigger AS $func$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$func$ LANGUAGE plpgsql;
"""

REJECT_ESCALATION_TAMPER_FUNCTION = """
CREATE OR REPLACE FUNCTION reject_escalation_tamper() RETURNS trigger AS $func$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'escalations is append-only: DELETE is not allowed';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.event_id IS DISTINCT FROM OLD.event_id
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.user_id IS DISTINCT FROM OLD.user_id
        OR NEW.level IS DISTINCT FROM OLD.level
        OR NEW."trigger" IS DISTINCT FROM OLD."trigger"
        OR NEW.evidence_summary IS DISTINCT FROM OLD.evidence_summary
        OR NEW.opened_at IS DISTINCT FROM OLD.opened_at THEN
        RAISE EXCEPTION 'escalations immutable fields cannot be modified';
    END IF;
    RETURN NEW;
END;
$func$ LANGUAGE plpgsql;
"""


def _dialect_name() -> str:
    bind = op.get_bind()
    if bind is not None:
        return bind.dialect.name
    return op.get_context().dialect.name


def upgrade() -> None:
    if _dialect_name() != "postgresql":
        # SQLite (tests/demo): application-layer ORM guard is the fallback.
        return
    op.execute(REJECT_APPEND_ONLY_FUNCTION)
    for table in APPEND_ONLY_TABLES:
        op.execute(f"""
            CREATE TRIGGER trg_{table}_append_only
            BEFORE UPDATE OR DELETE ON {table}
            FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
        """)
    op.execute(REJECT_ESCALATION_TAMPER_FUNCTION)
    op.execute("""
        CREATE TRIGGER trg_escalations_append_only
        BEFORE UPDATE OR DELETE ON escalations
        FOR EACH ROW EXECUTE FUNCTION reject_escalation_tamper();
    """)


def downgrade() -> None:
    if _dialect_name() != "postgresql":
        return
    op.execute("DROP TRIGGER IF EXISTS trg_escalations_append_only ON escalations;")
    for table in APPEND_ONLY_TABLES:
        op.execute(f"DROP TRIGGER IF EXISTS trg_{table}_append_only ON {table};")
    op.execute("DROP FUNCTION IF EXISTS reject_escalation_tamper();")
    op.execute("DROP FUNCTION IF EXISTS reject_append_only_mutation();")
