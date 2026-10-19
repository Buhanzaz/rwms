"""Add the operator-selected planning date to source logistics requests.

Revision ID: 20260824_0003
Revises: 20260823_0002
Create Date: 2026-08-24
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260824_0003"
down_revision: str | None = "20260823_0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add a nullable explicit date without changing existing request eligibility."""

    op.add_column("logistics_requests", sa.Column("scheduled_date", sa.Date(), nullable=True))
    op.create_index(
        "ix_logistics_requests_scenario_scheduled_date",
        "logistics_requests",
        ["scenario_id", "scheduled_date"],
        unique=False,
    )


def downgrade() -> None:
    """Remove the explicit request date and its lookup index."""

    op.drop_index(
        "ix_logistics_requests_scenario_scheduled_date",
        table_name="logistics_requests",
    )
    op.drop_column("logistics_requests", "scheduled_date")
