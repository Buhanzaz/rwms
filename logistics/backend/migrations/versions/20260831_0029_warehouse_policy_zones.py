"""Restore warehouse-owned exceptional pricing and access policy polygons.

Revision ID: 20260831_0029
Revises: 20260831_0028
Create Date: 2026-08-31
"""

from collections.abc import Sequence

import geoalchemy2
import sqlalchemy as sa
from alembic import op

revision: str = "20260831_0029"
down_revision: str | None = "20260831_0028"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add only exceptional warehouse policies; isochrone tariffs retain normal coverage."""

    op.create_table(
        "warehouse_policy_zones",
        sa.Column("warehouse_id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(length=200), nullable=False),
        sa.Column("kind", sa.String(length=32), nullable=False),
        sa.Column("color", sa.String(length=7), nullable=False),
        sa.Column(
            "geometry",
            geoalchemy2.Geometry(
                geometry_type="MULTIPOLYGON",
                srid=4326,
                spatial_index=False,
            ),
            nullable=False,
        ),
        sa.Column("version", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("delivery_price_rubles", sa.BigInteger(), nullable=True),
        sa.Column("pickup_price_rubles", sa.BigInteger(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.CheckConstraint(
            "length(btrim(name)) > 0",
            name=op.f("ck_warehouse_policy_zones_nonempty_name"),
        ),
        sa.CheckConstraint(
            "kind IN ('SPECIAL_PRICE', 'FORBIDDEN', 'NO_TRAILER')",
            name=op.f("ck_warehouse_policy_zones_supported_kind"),
        ),
        sa.CheckConstraint(
            "color ~ '^#[0-9A-Fa-f]{6}$'",
            name=op.f("ck_warehouse_policy_zones_valid_color"),
        ),
        sa.CheckConstraint(
            "version >= 1",
            name=op.f("ck_warehouse_policy_zones_positive_version"),
        ),
        sa.CheckConstraint(
            "(kind = 'SPECIAL_PRICE' "
            "AND delivery_price_rubles IS NOT NULL "
            "AND pickup_price_rubles IS NOT NULL "
            "AND delivery_price_rubles >= 0 "
            "AND pickup_price_rubles >= 0) OR "
            "(kind IN ('FORBIDDEN', 'NO_TRAILER') "
            "AND delivery_price_rubles IS NULL "
            "AND pickup_price_rubles IS NULL)",
            name=op.f("ck_warehouse_policy_zones_kind_values"),
        ),
        sa.ForeignKeyConstraint(
            ["warehouse_id"],
            ["warehouses.id"],
            name=op.f("fk_warehouse_policy_zones_warehouse_id_warehouses"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_warehouse_policy_zones")),
    )
    op.create_index(
        "ix_warehouse_policy_zones_warehouse_id",
        "warehouse_policy_zones",
        ["warehouse_id"],
        unique=False,
    )
    op.create_index(
        "ix_warehouse_policy_zones_geometry_gist",
        "warehouse_policy_zones",
        ["geometry"],
        unique=False,
        postgresql_using="gist",
    )


def downgrade() -> None:
    """Remove exceptional policy polygons without altering warehouse tariffs or demand."""

    op.drop_index(
        "ix_warehouse_policy_zones_geometry_gist",
        table_name="warehouse_policy_zones",
    )
    op.drop_index(
        "ix_warehouse_policy_zones_warehouse_id",
        table_name="warehouse_policy_zones",
    )
    op.drop_table("warehouse_policy_zones")
