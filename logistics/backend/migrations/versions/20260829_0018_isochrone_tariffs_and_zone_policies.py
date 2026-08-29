"""Make isochrone bands the default tariff and type exceptional zones.

Revision ID: 20260829_0018
Revises: 20260828_0017
Create Date: 2026-08-29
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260829_0018"
down_revision: str | None = "20260828_0017"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Backfill current zones as special tariffs and add four warehouse price bands."""

    op.add_column(
        "warehouses",
        sa.Column(
            "isochrone_price_60_minutes",
            sa.Integer(),
            nullable=False,
            server_default="10000",
        ),
    )
    op.add_column(
        "warehouses",
        sa.Column(
            "isochrone_price_120_minutes",
            sa.Integer(),
            nullable=False,
            server_default="15000",
        ),
    )
    op.add_column(
        "warehouses",
        sa.Column(
            "isochrone_price_180_minutes",
            sa.Integer(),
            nullable=False,
            server_default="20000",
        ),
    )
    op.add_column(
        "warehouses",
        sa.Column(
            "isochrone_price_240_minutes",
            sa.Integer(),
            nullable=False,
            server_default="25000",
        ),
    )
    op.create_check_constraint(
        "nonnegative_isochrone_prices",
        "warehouses",
        "isochrone_price_60_minutes >= 0 AND "
        "isochrone_price_120_minutes >= 0 AND "
        "isochrone_price_180_minutes >= 0 AND "
        "isochrone_price_240_minutes >= 0",
    )
    op.add_column(
        "zones",
        sa.Column(
            "kind",
            sa.String(length=32),
            nullable=False,
            server_default="SPECIAL_PRICE",
        ),
    )
    op.create_check_constraint(
        "supported_kind",
        "zones",
        "kind IN ('FORBIDDEN', 'NO_TRAILER', 'SPECIAL_PRICE')",
    )
    op.add_column(
        "slot_holds",
        sa.Column("price_isochrone_minutes", sa.Integer(), nullable=True),
    )
    op.create_check_constraint(
        "supported_price_isochrone",
        "slot_holds",
        "price_isochrone_minutes IS NULL OR "
        "price_isochrone_minutes IN (60, 120, 180, 240)",
    )


def downgrade() -> None:
    """Remove policy metadata without deleting any warehouse or zone geometry."""

    op.drop_constraint(
        op.f("ck_slot_holds_supported_price_isochrone"),
        "slot_holds",
        type_="check",
    )
    op.drop_column("slot_holds", "price_isochrone_minutes")
    op.drop_constraint(op.f("ck_zones_supported_kind"), "zones", type_="check")
    op.drop_column("zones", "kind")
    op.drop_constraint(
        op.f("ck_warehouses_nonnegative_isochrone_prices"),
        "warehouses",
        type_="check",
    )
    op.drop_column("warehouses", "isochrone_price_240_minutes")
    op.drop_column("warehouses", "isochrone_price_180_minutes")
    op.drop_column("warehouses", "isochrone_price_120_minutes")
    op.drop_column("warehouses", "isochrone_price_60_minutes")
