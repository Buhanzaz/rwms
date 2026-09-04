"""Prevent a shift break from consuming the complete daily interval.

Revision ID: 20260831_0025
Revises: 20260831_0024
Create Date: 2026-08-31
"""

from collections.abc import Sequence

from alembic import op

revision: str = "20260831_0025"
down_revision: str | None = "20260831_0024"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Reject invalid existing data, then enforce positive usable shift capacity."""

    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                FROM driver_shifts
                WHERE break_minutes * 60 >= EXTRACT(EPOCH FROM (end_time - start_time))
            ) THEN
                RAISE EXCEPTION
                    'Driver shifts with a break consuming the complete interval must be corrected';
            END IF;
        END
        $$
        """
    )
    op.create_check_constraint(
        "break_shorter_than_duration",
        "driver_shifts",
        "break_minutes * 60 < EXTRACT(EPOCH FROM (end_time - start_time))",
    )


def downgrade() -> None:
    """Remove only the usable-capacity constraint."""

    op.drop_constraint(
        op.f("ck_driver_shifts_break_shorter_than_duration"),
        "driver_shifts",
        type_="check",
    )
