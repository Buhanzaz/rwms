"""Allow the verified username and subject UUID in planning-day audit actors.

Revision ID: 20260831_0023
Revises: 20260830_0022
Create Date: 2026-08-31
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260831_0023"
down_revision: str | None = "20260830_0022"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Widen the actor snapshot for a 128-character username plus UUID."""

    op.alter_column(
        "planning_day_closures",
        "closed_by",
        existing_type=sa.String(length=100),
        type_=sa.String(length=200),
        existing_nullable=False,
    )


def downgrade() -> None:
    """Restore the former actor length after rejecting oversized existing rows."""

    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                FROM planning_day_closures
                WHERE char_length(closed_by) > 100
            ) THEN
                RAISE EXCEPTION
                    'Cannot downgrade: planning_day_closures.closed_by exceeds 100 characters';
            END IF;
        END
        $$
        """
    )
    op.alter_column(
        "planning_day_closures",
        "closed_by",
        existing_type=sa.String(length=200),
        type_=sa.String(length=100),
        existing_nullable=False,
    )
