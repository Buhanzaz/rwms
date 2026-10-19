"""Retain exact contractor route task identities after a confirmed handoff.

Revision ID: 20260901_0030
Revises: 20260831_0029
Create Date: 2026-09-01
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260901_0030"
down_revision: str | None = "20260831_0029"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add exact task UUIDs and their stable request order within a handoff."""

    op.add_column(
        "logistics_requests",
        sa.Column(
            "contractor_external_task_ids",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
    )
    op.create_check_constraint(
        op.f("ck_logistics_requests_contractor_external_task_ids_array"),
        "logistics_requests",
        "jsonb_typeof(contractor_external_task_ids) = 'array'",
    )
    op.add_column(
        "logistics_requests",
        sa.Column("contractor_handoff_sequence", sa.Integer(), nullable=True),
    )
    op.execute(
        """
        UPDATE logistics_requests AS request
        SET contractor_handoff_sequence = (ordered_request.ordinality - 1)::integer
        FROM contractor_handoff_commands AS command
        CROSS JOIN LATERAL jsonb_array_elements_text(command.request_ids)
            WITH ORDINALITY AS ordered_request(request_id, ordinality)
        WHERE request.contractor_handoff_command_id = command.id
          AND request.id = ordered_request.request_id::uuid
          AND request.assignment_type = 'CONTRACTOR_HANDOFF'
          AND command.status = 'SUCCEEDED'
        """
    )
    op.create_check_constraint(
        op.f("ck_logistics_requests_valid_contractor_handoff_sequence"),
        "logistics_requests",
        "contractor_handoff_sequence IS NULL OR ("
        "contractor_handoff_command_id IS NOT NULL AND "
        "assignment_type = 'CONTRACTOR_HANDOFF' AND "
        "contractor_handoff_sequence >= 0)",
    )


def downgrade() -> None:
    """Remove only the planner-owned contractor task identity and request order."""

    op.drop_constraint(
        op.f("ck_logistics_requests_valid_contractor_handoff_sequence"),
        "logistics_requests",
        type_="check",
    )
    op.drop_column("logistics_requests", "contractor_handoff_sequence")
    op.drop_constraint(
        op.f("ck_logistics_requests_contractor_external_task_ids_array"),
        "logistics_requests",
        type_="check",
    )
    op.drop_column("logistics_requests", "contractor_external_task_ids")
