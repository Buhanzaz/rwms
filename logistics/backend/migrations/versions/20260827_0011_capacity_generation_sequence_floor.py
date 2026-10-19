"""Keep the global capacity sequence above every pre-sequence generation.

Revision ID: 20260827_0011
Revises: 20260827_0010
Create Date: 2026-08-27
"""

from collections.abc import Sequence

from alembic import op

revision: str = "20260827_0011"
down_revision: str | None = "20260827_0010"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Advance the next value without moving an already-used sequence backwards."""

    op.execute(
        """
        SELECT setval(
            'scenario_capacity_generation_seq',
            GREATEST(
                (
                    SELECT COALESCE(MAX(capacity_generation), 0) + 1
                    FROM scenarios
                ),
                (
                    SELECT CASE WHEN is_called THEN last_value + 1 ELSE last_value END
                    FROM scenario_capacity_generation_seq
                )
            ),
            false
        )
        """
    )


def downgrade() -> None:
    """Retain the advanced value because decreasing a fencing sequence is unsafe."""

    pass
