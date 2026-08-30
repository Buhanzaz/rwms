"""Allow an authoritative delivery amount without a historical isochrone tier.

Revision ID: 20260830_0022
Revises: 20260830_0021
Create Date: 2026-08-30
"""

from collections.abc import Sequence

from alembic import op

revision: str = "20260830_0022"
down_revision: str | None = "20260830_0021"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Remove the obsolete all-or-none price/tier constraint."""

    op.drop_constraint(
        op.f("ck_logistics_requests_complete_delivery_price"),
        "logistics_requests",
        type_="check",
    )


def downgrade() -> None:
    """Restore the former all-or-none price/tier constraint."""

    op.create_check_constraint(
        "complete_delivery_price",
        "logistics_requests",
        "(delivery_price_rubles IS NULL) = (price_isochrone_minutes IS NULL)",
    )
