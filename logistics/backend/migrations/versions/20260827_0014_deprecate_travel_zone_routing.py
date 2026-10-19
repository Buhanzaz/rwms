"""Remove the obsolete travel-zone upper bound from saved date options.

Revision ID: 20260827_0014
Revises: 20260827_0013
Create Date: 2026-08-27
"""

from collections.abc import Sequence

from alembic import op

revision: str = "20260827_0014"
down_revision: str | None = "20260827_0013"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Keep only positivity because the saved band no longer constrains routing."""

    op.drop_constraint(
        op.f("ck_request_date_options_valid_travel_zone_hours"),
        "request_date_options",
        type_="check",
    )
    op.create_check_constraint(
        "valid_travel_zone_hours",
        "request_date_options",
        "travel_zone_hours IS NULL OR travel_zone_hours >= 1",
    )


def downgrade() -> None:
    """Restore the historical one-to-four-hour display-band constraint."""

    op.drop_constraint(
        op.f("ck_request_date_options_valid_travel_zone_hours"),
        "request_date_options",
        type_="check",
    )
    op.create_check_constraint(
        "valid_travel_zone_hours",
        "request_date_options",
        "travel_zone_hours IS NULL OR travel_zone_hours BETWEEN 1 AND 4",
    )
