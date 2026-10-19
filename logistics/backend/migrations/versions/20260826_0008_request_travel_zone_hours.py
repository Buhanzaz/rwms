"""Persist the CustomerApp depot travel-time zone on request date options.

Revision ID: 20260826_0008
Revises: 20260826_0007
Create Date: 2026-08-26
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260826_0008"
down_revision: str | None = "20260826_0007"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add the nullable one-to-four-hour travel band used by route construction."""

    op.add_column(
        "request_date_options",
        sa.Column("travel_zone_hours", sa.Integer(), nullable=True),
    )
    op.create_check_constraint(
        "valid_travel_zone_hours",
        "request_date_options",
        "travel_zone_hours IS NULL OR travel_zone_hours BETWEEN 1 AND 4",
    )


def downgrade() -> None:
    """Remove the CustomerApp travel band without changing request dates."""

    op.drop_constraint(
        op.f("ck_request_date_options_valid_travel_zone_hours"),
        "request_date_options",
        type_="check",
    )
    op.drop_column("request_date_options", "travel_zone_hours")
