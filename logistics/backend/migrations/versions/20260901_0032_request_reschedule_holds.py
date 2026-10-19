"""Fence owner-backed rescheduling across transaction-free external calls.

Revision ID: 20260901_0032
Revises: 20260901_0031
Create Date: 2026-09-01
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260901_0032"
down_revision: str | None = "20260901_0031"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Create one durable active hold per local request lineage."""

    op.create_table(
        "request_reschedule_holds",
        sa.Column("request_id", sa.Uuid(), nullable=False),
        sa.Column("actor_id", sa.Uuid(), nullable=False),
        sa.Column("idempotency_key", sa.Uuid(), nullable=False),
        sa.Column("request_hash", sa.String(length=64), nullable=False),
        sa.Column("state", sa.String(length=32), nullable=False),
        sa.Column("expected_request_version", sa.Integer(), nullable=False),
        sa.Column("local_warehouse_id", sa.Uuid(), nullable=False),
        sa.Column("external_warehouse_id", sa.Uuid(), nullable=False),
        sa.Column("order_id", sa.Uuid(), nullable=False),
        sa.Column("expected_order_version", sa.BigInteger(), nullable=False),
        sa.Column("expected_session_version", sa.BigInteger(), nullable=False),
        sa.Column("slot_id", sa.Uuid(), nullable=False),
        sa.Column("slot_version", sa.BigInteger(), nullable=False),
        sa.Column("source_plan_id", sa.Uuid(), nullable=False),
        sa.Column("source_plan_version", sa.BigInteger(), nullable=False),
        sa.Column("source_plan_warehouse_id", sa.Uuid(), nullable=False),
        sa.Column("source_scheduled_date", sa.Date(), nullable=False),
        sa.Column("owner_session_id", sa.Uuid(), nullable=True),
        sa.Column("owner_booking_id", sa.Uuid(), nullable=True),
        sa.Column("selected_slot", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("owner_result", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("public_result", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("attempt_count", sa.Integer(), nullable=False),
        sa.Column("quarantine_count", sa.Integer(), nullable=False),
        sa.Column("next_attempt_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("lease_until", sa.DateTime(timezone=True), nullable=True),
        sa.Column("quarantined_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_quarantine_error_code", sa.String(length=128), nullable=True),
        sa.Column("last_retry_requested_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_retry_requested_by", sa.Uuid(), nullable=True),
        sa.Column("error_code", sa.String(length=128), nullable=True),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.CheckConstraint(
            "expected_order_version >= 0",
            name=op.f("ck_request_reschedule_holds_nonnegative_order_version"),
        ),
        sa.CheckConstraint(
            "expected_request_version >= 1",
            name=op.f("ck_request_reschedule_holds_positive_request_version"),
        ),
        sa.CheckConstraint(
            "expected_session_version >= 0",
            name=op.f("ck_request_reschedule_holds_nonnegative_session_version"),
        ),
        sa.CheckConstraint(
            "attempt_count >= 0",
            name=op.f("ck_request_reschedule_holds_nonnegative_attempt_count"),
        ),
        sa.CheckConstraint(
            "quarantine_count >= 0",
            name=op.f("ck_request_reschedule_holds_nonnegative_quarantine_count"),
        ),
        sa.CheckConstraint(
            "(last_retry_requested_at IS NULL) = (last_retry_requested_by IS NULL)",
            name=op.f("ck_request_reschedule_holds_complete_retry_audit"),
        ),
        sa.CheckConstraint(
            "length(request_hash) = 64",
            name=op.f("ck_request_reschedule_holds_valid_request_hash"),
        ),
        sa.CheckConstraint(
            "owner_result IS NULL OR jsonb_typeof(owner_result) = 'object'",
            name=op.f("ck_request_reschedule_holds_owner_result_object"),
        ),
        sa.CheckConstraint(
            "public_result IS NULL OR jsonb_typeof(public_result) = 'object'",
            name=op.f("ck_request_reschedule_holds_public_result_object"),
        ),
        sa.CheckConstraint(
            "(state = 'CLAIMED' AND owner_session_id IS NULL AND "
            "owner_booking_id IS NULL AND selected_slot IS NULL AND owner_result IS NULL "
            "AND public_result IS NULL AND completed_at IS NULL AND error_code IS NULL) OR "
            "(state = 'OWNER_CALLING' AND owner_session_id IS NOT NULL AND "
            "owner_booking_id IS NOT NULL AND selected_slot IS NOT NULL "
            "AND owner_result IS NULL AND public_result IS NULL "
            "AND completed_at IS NULL AND error_code IS NULL) OR "
            "(state = 'COMPLETE' AND owner_session_id IS NOT NULL AND "
            "owner_booking_id IS NOT NULL AND selected_slot IS NOT NULL "
            "AND owner_result IS NOT NULL AND completed_at IS NOT NULL "
            "AND public_result IS NOT NULL AND error_code IS NULL "
            "AND next_attempt_at IS NULL AND lease_until IS NULL) OR "
            "(state = 'SUPERSEDED' AND owner_session_id IS NOT NULL AND "
            "owner_booking_id IS NOT NULL AND selected_slot IS NOT NULL "
            "AND owner_result IS NOT NULL AND public_result IS NULL "
            "AND completed_at IS NOT NULL "
            "AND error_code = 'RWMS_RESCHEDULE_RESULT_SUPERSEDED' "
            "AND next_attempt_at IS NULL AND lease_until IS NULL) OR "
            "(state = 'QUARANTINED' AND owner_session_id IS NOT NULL AND "
            "owner_booking_id IS NOT NULL AND selected_slot IS NOT NULL "
            "AND owner_result IS NULL AND public_result IS NULL AND completed_at IS NOT NULL "
            "AND error_code IS NOT NULL AND next_attempt_at IS NULL "
            "AND lease_until IS NULL AND quarantined_at IS NOT NULL "
            "AND quarantine_count >= 1) OR "
            "(state = 'FAILED' AND owner_result IS NULL AND public_result IS NULL "
            "AND completed_at IS NOT NULL "
            "AND error_code IS NOT NULL AND next_attempt_at IS NULL "
            "AND lease_until IS NULL AND ((owner_session_id IS NULL AND "
            "owner_booking_id IS NULL AND selected_slot IS NULL) OR "
            "(owner_session_id IS NOT NULL AND owner_booking_id IS NOT NULL "
            "AND selected_slot IS NOT NULL)))",
            name=op.f("ck_request_reschedule_holds_phase_consistency"),
        ),
        sa.CheckConstraint(
            "lease_until IS NULL OR state IN ('CLAIMED', 'OWNER_CALLING')",
            name=op.f("ck_request_reschedule_holds_lease_active_phase_only"),
        ),
        sa.CheckConstraint(
            "next_attempt_at IS NULL OR state IN ('CLAIMED', 'OWNER_CALLING')",
            name=op.f("ck_request_reschedule_holds_retry_due_active_phase_only"),
        ),
        sa.CheckConstraint(
            "selected_slot IS NULL OR jsonb_typeof(selected_slot) = 'object'",
            name=op.f("ck_request_reschedule_holds_selected_slot_object"),
        ),
        sa.CheckConstraint(
            "slot_version >= 0",
            name=op.f("ck_request_reschedule_holds_nonnegative_slot_version"),
        ),
        sa.CheckConstraint(
            "source_plan_version >= 1",
            name=op.f("ck_request_reschedule_holds_positive_source_plan_version"),
        ),
        sa.CheckConstraint(
            "state IN ('CLAIMED', 'OWNER_CALLING', 'QUARANTINED', 'COMPLETE', "
            "'SUPERSEDED', 'FAILED')",
            name=op.f("ck_request_reschedule_holds_valid_state"),
        ),
        sa.ForeignKeyConstraint(
            ["local_warehouse_id"],
            ["warehouses.id"],
            name=op.f("fk_request_reschedule_holds_local_warehouse_id_warehouses"),
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["request_id"],
            ["logistics_requests.id"],
            name=op.f("fk_request_reschedule_holds_request_id_logistics_requests"),
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_request_reschedule_holds")),
        sa.UniqueConstraint(
            "actor_id",
            "idempotency_key",
            name="uq_request_reschedule_holds_actor_key",
        ),
    )
    op.create_index(
        "ix_request_reschedule_holds_request_created",
        "request_reschedule_holds",
        ["request_id", "created_at"],
        unique=False,
    )
    op.create_index(
        "uq_request_reschedule_holds_active_request",
        "request_reschedule_holds",
        ["request_id"],
        unique=True,
        postgresql_where=sa.text(
            "state IN ('CLAIMED', 'OWNER_CALLING', 'QUARANTINED')"
        ),
    )
    op.create_index(
        "uq_request_reschedule_holds_active_plan",
        "request_reschedule_holds",
        ["source_plan_id"],
        unique=True,
        postgresql_where=sa.text(
            "source_plan_id IS NOT NULL AND "
            "state IN ('CLAIMED', 'OWNER_CALLING', 'QUARANTINED')"
        ),
    )


def downgrade() -> None:
    """Remove standalone reschedule recovery state."""

    op.drop_index(
        "uq_request_reschedule_holds_active_plan",
        table_name="request_reschedule_holds",
    )
    op.drop_index(
        "uq_request_reschedule_holds_active_request",
        table_name="request_reschedule_holds",
    )
    op.drop_index(
        "ix_request_reschedule_holds_request_created",
        table_name="request_reschedule_holds",
    )
    op.drop_table("request_reschedule_holds")
