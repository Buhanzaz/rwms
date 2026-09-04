"""Persist leased, retryable RWMS contractor handoff commands.

Revision ID: 20260831_0028
Revises: 20260831_0027
Create Date: 2026-08-31
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260831_0028"
down_revision: str | None = "20260831_0027"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add immutable command snapshots, recovery state, and request reservations."""

    op.create_table(
        "contractor_handoff_commands",
        sa.Column("warehouse_id", sa.Uuid(), nullable=False),
        sa.Column("external_warehouse_id", sa.Uuid(), nullable=False),
        sa.Column("planning_date", sa.Date(), nullable=False),
        sa.Column("mode", sa.String(length=16), nullable=False),
        sa.Column("request_ids", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("command_payload", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("contractor_worker_id", sa.Uuid(), nullable=False),
        sa.Column("contractor_name", sa.String(length=200), nullable=False),
        sa.Column("contractor_phone", sa.String(length=64), nullable=False),
        sa.Column("assigned_by", sa.String(length=200), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("attempts", sa.Integer(), nullable=False),
        sa.Column("next_attempt_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("lease_until", sa.DateTime(timezone=True), nullable=True),
        sa.Column("error_code", sa.String(length=128), nullable=True),
        sa.Column(
            "rejection_codes",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=False,
        ),
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
            "(status IN ('SUCCEEDED', 'REJECTED')) = (completed_at IS NOT NULL)",
            name=op.f("ck_contractor_handoff_commands_terminal_completion"),
        ),
        sa.CheckConstraint(
            "(status = 'APPLYING') = (lease_until IS NOT NULL)",
            name=op.f("ck_contractor_handoff_commands_lease_matches_applying"),
        ),
        sa.CheckConstraint(
            "length(assigned_by) > 0",
            name=op.f("ck_contractor_handoff_commands_nonempty_assigned_by"),
        ),
        sa.CheckConstraint(
            "jsonb_array_length(request_ids) > 0",
            name=op.f("ck_contractor_handoff_commands_nonempty_requests"),
        ),
        sa.CheckConstraint(
            "attempts >= 0",
            name=op.f("ck_contractor_handoff_commands_nonnegative_attempts"),
        ),
        sa.CheckConstraint(
            "status IN ('PENDING', 'APPLYING', 'SUCCEEDED', 'REJECTED')",
            name=op.f("ck_contractor_handoff_commands_valid_status"),
        ),
        sa.ForeignKeyConstraint(
            ["warehouse_id"],
            ["warehouses.id"],
            name=op.f("fk_contractor_handoff_commands_warehouse_id_warehouses"),
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_contractor_handoff_commands")),
    )
    op.create_index(
        "ix_contractor_handoff_commands_due",
        "contractor_handoff_commands",
        ["status", "next_attempt_at", "lease_until"],
        unique=False,
    )
    op.add_column(
        "logistics_requests",
        sa.Column("contractor_handoff_command_id", sa.Uuid(), nullable=True),
    )
    op.create_foreign_key(
        op.f("fk_logistics_requests_contractor_handoff_command_id_contractor_handoff_commands"),
        "logistics_requests",
        "contractor_handoff_commands",
        ["contractor_handoff_command_id"],
        ["id"],
        ondelete="SET NULL",
    )
    op.create_index(
        "ix_logistics_requests_contractor_handoff_command_id",
        "logistics_requests",
        ["contractor_handoff_command_id"],
        unique=False,
    )


def downgrade() -> None:
    """Remove contractor recovery state while retaining completed request assignments."""

    op.drop_index(
        "ix_logistics_requests_contractor_handoff_command_id",
        table_name="logistics_requests",
    )
    op.drop_constraint(
        op.f(
            "fk_logistics_requests_contractor_handoff_command_id_contractor_handoff_commands"
        ),
        "logistics_requests",
        type_="foreignkey",
    )
    op.drop_column("logistics_requests", "contractor_handoff_command_id")
    op.drop_index(
        "ix_contractor_handoff_commands_due",
        table_name="contractor_handoff_commands",
    )
    op.drop_table("contractor_handoff_commands")
