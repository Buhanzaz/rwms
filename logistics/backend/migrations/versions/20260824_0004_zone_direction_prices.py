"""Add direction-specific delivery and pickup prices to logistics zones.

Revision ID: 20260824_0004
Revises: 20260824_0003
Create Date: 2026-08-24
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260824_0004"
down_revision: str | None = "20260824_0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add zero-valued integer-ruble tariffs without changing existing zones."""

    op.add_column(
        "zones",
        sa.Column("delivery_price", sa.Integer(), server_default="0", nullable=False),
    )
    op.add_column(
        "zones",
        sa.Column("pickup_price", sa.Integer(), server_default="0", nullable=False),
    )
    op.create_check_constraint(
        "nonnegative_delivery_price",
        "zones",
        "delivery_price >= 0",
    )
    op.create_check_constraint(
        "nonnegative_pickup_price",
        "zones",
        "pickup_price >= 0",
    )


def downgrade() -> None:
    """Remove only the zone tariffs introduced by this revision."""

    op.drop_constraint(
        op.f("ck_zones_nonnegative_pickup_price"),
        "zones",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_zones_nonnegative_delivery_price"),
        "zones",
        type_="check",
    )
    op.drop_column("zones", "pickup_price")
    op.drop_column("zones", "delivery_price")
