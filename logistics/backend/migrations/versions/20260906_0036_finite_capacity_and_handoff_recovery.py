"""Bound automatic RWMS recovery while retaining reviewed command evidence.

Revision ID: 20260906_0036
Revises: 20260906_0035
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260906_0036"
down_revision: str | None = "20260906_0035"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add fenced leases and review-only states without releasing live reservations."""

    op.add_column("contractor_handoff_commands", sa.Column("lease_token", sa.Uuid(), nullable=True))
    op.execute(
        "update contractor_handoff_commands set lease_token=gen_random_uuid() "
        "where status='APPLYING'"
    )
    op.drop_constraint(
        op.f("ck_contractor_handoff_commands_lease_matches_applying"),
        "contractor_handoff_commands",
        type_="check",
    )
    op.create_check_constraint(
        op.f("ck_contractor_handoff_commands_lease_matches_applying"),
        "contractor_handoff_commands",
        "(status = 'APPLYING' AND lease_until IS NOT NULL AND lease_token IS NOT NULL) "
        "OR (status <> 'APPLYING' AND lease_until IS NULL AND lease_token IS NULL)",
    )
    op.drop_constraint(
        op.f("ck_contractor_handoff_commands_valid_status"),
        "contractor_handoff_commands",
        type_="check",
    )
    op.create_check_constraint(
        op.f("ck_contractor_handoff_commands_valid_status"),
        "contractor_handoff_commands",
        "status IN ('PENDING', 'APPLYING', 'SUCCEEDED', 'REJECTED', 'REVIEW_REQUIRED')",
    )
    op.drop_constraint(
        op.f("ck_warehouses_valid_capacity_publish_status"), "warehouses", type_="check"
    )
    op.create_check_constraint(
        op.f("ck_warehouses_valid_capacity_publish_status"),
        "warehouses",
        "capacity_publish_status IN "
        "('NOT_REQUESTED', 'PENDING', 'PUBLISHED', 'FAILED', 'REVIEW_REQUIRED')",
    )


def downgrade() -> None:
    """Refuse a downgrade that would discard reviewed recovery state."""

    raise RuntimeError("cannot downgrade finite RWMS recovery while review-required rows may exist")
