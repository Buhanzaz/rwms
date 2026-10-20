"""Add the canonical vehicle physical model in tonnes and metres.

Revision ID: 20260908_0038
Revises: 20260907_0037

The migration is additive: every legacy kilogram/millimetre column and every
routing-extension column stays in place for backward compatibility.  Canonical
values are backfilled exactly from the legacy integers (1 kg = 0.001 t,
1 mm = 0.001 m) and are afterwards kept in sync by the API layer.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260908_0038"
down_revision: str | None = "20260907_0037"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

POSITIVE_CANONICAL_VEHICLE_VALUES = (
    "(tare_weight_t IS NULL OR tare_weight_t > 0) AND "
    "(max_gross_weight_t IS NULL OR max_gross_weight_t > 0) AND "
    "(axle_load_t IS NULL OR axle_load_t > 0) AND "
    "(payload_capacity_t IS NULL OR payload_capacity_t > 0) AND "
    "(length_m IS NULL OR length_m > 0) AND "
    "(width_m IS NULL OR width_m > 0) AND "
    "(height_m IS NULL OR height_m > 0)"
)
CANONICAL_GROSS_NOT_BELOW_TARE = (
    "max_gross_weight_t IS NULL OR tare_weight_t IS NULL OR "
    "max_gross_weight_t >= tare_weight_t"
)
POSITIVE_CANONICAL_AXLE_LOAD = (
    "max_actual_axle_load_t IS NULL OR max_actual_axle_load_t > 0"
)

_NUMERIC = sa.Numeric(12, 3)


def upgrade() -> None:
    """Add, backfill and constrain the canonical columns without dropping data."""

    for column_name in (
        "tare_weight_t",
        "max_gross_weight_t",
        "axle_load_t",
        "payload_capacity_t",
        "length_m",
        "width_m",
        "height_m",
    ):
        op.add_column("vehicles", sa.Column(column_name, _NUMERIC, nullable=True))

    op.add_column(
        "vehicles",
        sa.Column("has_trailer", sa.Boolean(), nullable=True),
    )
    op.add_column(
        "vehicle_load_profiles",
        sa.Column("max_actual_axle_load_t", _NUMERIC, nullable=True),
    )

    # Exact unit conversion: kilogram -> tonne, millimetre -> metre.
    op.execute(
        "UPDATE vehicles SET "
        "tare_weight_t = round(tare_weight_kg::numeric / 1000, 3), "
        "max_gross_weight_t = round(max_gross_weight_kg::numeric / 1000, 3), "
        "axle_load_t = round(max_axle_load_kg::numeric / 1000, 3), "
        "payload_capacity_t = round(payload_capacity_kg::numeric / 1000, 3), "
        "length_m = round(length_mm::numeric / 1000, 3), "
        "width_m = round(width_mm::numeric / 1000, 3), "
        "height_m = round(height_mm::numeric / 1000, 3)"
    )
    # A missing legacy capability is treated as "no confirmed trailer
    # capability", which matches the existing availability policy and never
    # silently attaches a trailer.  The legacy tri-state column is preserved as
    # validated evidence for external consumers.
    op.execute(
        "UPDATE vehicles SET has_trailer = COALESCE(can_use_trailer, false)"
    )
    op.execute(
        "UPDATE vehicle_load_profiles SET max_actual_axle_load_t = "
        "round(max_actual_axle_load_kg::numeric / 1000, 3)"
    )

    op.alter_column("vehicles", "has_trailer", nullable=False)

    op.create_check_constraint(
        "positive_canonical_vehicle_values",
        "vehicles",
        POSITIVE_CANONICAL_VEHICLE_VALUES,
    )
    op.create_check_constraint(
        "canonical_gross_not_below_tare",
        "vehicles",
        CANONICAL_GROSS_NOT_BELOW_TARE,
    )
    op.create_check_constraint(
        "positive_canonical_actual_axle_load",
        "vehicle_load_profiles",
        POSITIVE_CANONICAL_AXLE_LOAD,
    )


def downgrade() -> None:
    """Remove canonical columns while preserving every legacy value."""

    op.drop_constraint(
        op.f("ck_vehicle_load_profiles_positive_canonical_actual_axle_load"),
        "vehicle_load_profiles",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_vehicles_canonical_gross_not_below_tare"),
        "vehicles",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_vehicles_positive_canonical_vehicle_values"),
        "vehicles",
        type_="check",
    )
    op.drop_column("vehicle_load_profiles", "max_actual_axle_load_t")
    op.drop_column("vehicles", "has_trailer")
    for column_name in (
        "height_m",
        "width_m",
        "length_m",
        "payload_capacity_t",
        "axle_load_t",
        "max_gross_weight_t",
        "tare_weight_t",
    ):
        op.drop_column("vehicles", column_name)
