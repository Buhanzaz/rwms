"""Normalize warehouse isochrone tariffs and remove obsolete runtime zones.

Revision ID: 20260830_0020
Revises: 20260829_0019
Create Date: 2026-08-30
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260830_0020"
down_revision: str | None = "20260829_0019"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Backfill ordered tariffs, preserve calculated prices, and remove zone state."""

    op.create_table(
        "warehouse_isochrone_tariffs",
        sa.Column("warehouse_id", sa.Uuid(), nullable=False),
        sa.Column("travel_minutes", sa.Integer(), nullable=False),
        sa.Column("price_rubles", sa.Integer(), nullable=False),
        sa.CheckConstraint(
            "travel_minutes BETWEEN 60 AND 720 AND travel_minutes % 60 = 0",
            name="valid_travel_minutes",
        ),
        sa.CheckConstraint("price_rubles >= 0", name="nonnegative_price"),
        sa.ForeignKeyConstraint(
            ["warehouse_id"],
            ["warehouses.id"],
            ondelete="CASCADE",
            name="fk_warehouse_isochrone_tariffs_warehouse_id_warehouses",
        ),
        sa.PrimaryKeyConstraint(
            "warehouse_id",
            "travel_minutes",
            name="pk_warehouse_isochrone_tariffs",
        ),
    )
    op.execute(
        """
        INSERT INTO warehouse_isochrone_tariffs (warehouse_id, travel_minutes, price_rubles)
        SELECT id, tier.travel_minutes, tier.price_rubles
        FROM warehouses
        CROSS JOIN LATERAL (
            VALUES
                (60, isochrone_price_60_minutes),
                (120, isochrone_price_120_minutes),
                (180, isochrone_price_180_minutes),
                (240, isochrone_price_240_minutes)
        ) AS tier(travel_minutes, price_rubles)
        """
    )

    op.drop_constraint(
        op.f("ck_slot_holds_supported_price_isochrone"),
        "slot_holds",
        type_="check",
    )
    op.create_check_constraint(
        "supported_price_isochrone",
        "slot_holds",
        "price_isochrone_minutes IS NULL OR ("
        "price_isochrone_minutes BETWEEN 60 AND 720 AND "
        "price_isochrone_minutes % 60 = 0)",
    )
    op.drop_constraint(
        "fk_slot_holds_price_zone_id_zones",
        "slot_holds",
        type_="foreignkey",
    )
    op.drop_column("slot_holds", "price_zone_id")

    op.drop_index("ix_logistics_requests_zone_version", table_name="logistics_requests")
    op.drop_constraint("fk_requests_zone", "logistics_requests", type_="foreignkey")
    op.drop_column("logistics_requests", "zone_classification_status")
    op.drop_column("logistics_requests", "zone_version")
    op.drop_column("logistics_requests", "zone_id")

    op.drop_index("ix_planning_tasks_zone_status", table_name="planning_tasks")
    op.drop_constraint("fk_tasks_zone", "planning_tasks", type_="foreignkey")
    op.drop_column("planning_tasks", "zone_version")
    op.drop_column("planning_tasks", "zone_id")

    op.drop_table("zones")

    op.drop_constraint(
        op.f("ck_warehouses_nonnegative_isochrone_prices"),
        "warehouses",
        type_="check",
    )
    op.drop_column("warehouses", "isochrone_price_240_minutes")
    op.drop_column("warehouses", "isochrone_price_180_minutes")
    op.drop_column("warehouses", "isochrone_price_120_minutes")
    op.drop_column("warehouses", "isochrone_price_60_minutes")


def downgrade() -> None:
    """Refuse to fabricate intentionally removed exceptional-zone geometry."""

    raise RuntimeError(
        "20260830_0020 intentionally removes runtime zones and cannot be downgraded"
    )
