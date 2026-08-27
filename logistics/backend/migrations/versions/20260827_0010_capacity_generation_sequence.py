"""Allocate simulator-wide monotonic capacity generations.

Revision ID: 20260827_0010
Revises: 20260827_0009
Create Date: 2026-08-27
"""

from collections.abc import Sequence

from alembic import op

revision: str = "20260827_0010"
down_revision: str | None = "20260827_0009"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Create the database-global sequence assigned under each scenario lock."""

    op.execute("CREATE SEQUENCE scenario_capacity_generation_seq START WITH 1")


def downgrade() -> None:
    """Remove only the global publication sequence."""

    op.execute("DROP SEQUENCE scenario_capacity_generation_seq")
