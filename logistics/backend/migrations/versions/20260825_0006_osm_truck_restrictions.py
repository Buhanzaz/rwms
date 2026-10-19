"""Add the global spatial index of OpenStreetMap truck restrictions.

Revision ID: 20260825_0006
Revises: 20260825_0005
Create Date: 2026-08-25
"""

from collections.abc import Sequence

import geoalchemy2
import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260825_0006"
down_revision: str | None = "20260825_0005"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Create versioned global restriction and completed-import tables."""

    op.create_table(
        "osm_restriction_imports",
        sa.Column("osm_data_version", sa.String(length=128), nullable=False),
        sa.Column("source_file", sa.String(length=255), nullable=False),
        sa.Column("restriction_count", sa.Integer(), nullable=False),
        sa.Column(
            "imported_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "restriction_count >= 0",
            name=op.f("ck_osm_restriction_imports_nonnegative_count"),
        ),
        sa.PrimaryKeyConstraint("osm_data_version", name="pk_osm_restriction_imports"),
    )
    op.create_table(
        "osm_truck_restrictions",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("osm_data_version", sa.String(length=128), nullable=False),
        sa.Column("osm_type", sa.String(length=8), nullable=False),
        sa.Column("osm_id", sa.BigInteger(), nullable=False),
        sa.Column("category", sa.String(length=32), nullable=False),
        sa.Column("primary_tag", sa.String(length=64), nullable=False),
        sa.Column("value", sa.Text(), nullable=False),
        sa.Column(
            "tags",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=False,
        ),
        sa.Column("support_status", sa.String(length=16), nullable=False),
        sa.Column(
            "geometry",
            geoalchemy2.types.Geometry(
                geometry_type="GEOMETRY",
                srid=4326,
                from_text="ST_GeomFromEWKT",
                name="geometry",
                spatial_index=False,
            ),
            nullable=False,
        ),
        sa.CheckConstraint(
            "category IN ('HGV_ACCESS', 'MAX_HEIGHT', 'MAX_WIDTH', 'MAX_LENGTH', "
            "'MAX_WEIGHT', 'MAX_AXLE_LOAD', 'CONDITIONAL', 'TRAILER_ACCESS')",
            name=op.f("ck_osm_truck_restrictions_supported_category"),
        ),
        sa.CheckConstraint(
            "osm_type IN ('node', 'way')",
            name=op.f("ck_osm_truck_restrictions_supported_osm_type"),
        ),
        sa.CheckConstraint(
            "support_status IN ('SUPPORTED', 'PARTIAL', 'UNSUPPORTED')",
            name=op.f("ck_osm_truck_restrictions_supported_status"),
        ),
        sa.ForeignKeyConstraint(
            ["osm_data_version"],
            ["osm_restriction_imports.osm_data_version"],
            name="fk_osm_truck_restrictions_import_version",
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_osm_truck_restrictions"),
        sa.UniqueConstraint(
            "osm_data_version",
            "osm_type",
            "osm_id",
            name="uq_osm_truck_restrictions_version_object",
        ),
    )
    op.create_index(
        "ix_osm_truck_restrictions_geometry_gist",
        "osm_truck_restrictions",
        ["geometry"],
        unique=False,
        postgresql_using="gist",
    )
    op.create_index(
        "ix_osm_truck_restrictions_version_category",
        "osm_truck_restrictions",
        ["osm_data_version", "category"],
        unique=False,
    )


def downgrade() -> None:
    """Remove the derived OSM restriction index without touching the source PBF."""

    op.drop_index(
        "ix_osm_truck_restrictions_version_category",
        table_name="osm_truck_restrictions",
    )
    op.drop_index(
        "ix_osm_truck_restrictions_geometry_gist",
        table_name="osm_truck_restrictions",
        postgresql_using="gist",
    )
    op.drop_table("osm_truck_restrictions")
    op.drop_table("osm_restriction_imports")
