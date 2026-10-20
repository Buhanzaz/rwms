"""Remove the transitional kilogram/millimetre vehicle and trailer projection.

Revision ID: 20261020_0040
Revises: 20261020_0039

The canonical tonne/metre model introduced by 0038/0039 is the only supported
schema.  This migration drops every legacy kilogram/millimetre column, the
tri-state ``can_use_trailer`` flag, the derived ``combined_length_with_trailer_mm``
and the unused ``is_hgv`` flag.  A clean build therefore ends with the canonical
columns only.

Downgrade restores the nullable legacy columns, their historical CHECK
constraints and derives the kilogram/millimetre values back from the canonical
data.  ``platform_width_mm``, ``combined_length_with_trailer_mm`` and ``is_hgv``
have no canonical counterpart, so they are restored as NULL.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20261020_0040"
down_revision: str | None = "20261020_0039"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_VEHICLE_LEGACY_COLUMNS = (
    "tare_weight_kg",
    "max_gross_weight_kg",
    "length_mm",
    "width_mm",
    "height_mm",
    "max_axle_load_kg",
    "payload_capacity_kg",
    "platform_length_mm",
    "platform_width_mm",
    "platform_height_from_ground_mm",
    "max_platform_payload_kg",
    "max_cargo_length_mm",
    "max_cargo_width_mm",
    "max_cargo_height_mm",
    "max_cargo_weight_kg",
    "combined_length_with_trailer_mm",
    "coupling_length_mm",
    "height_safety_margin_mm",
    "width_safety_margin_mm",
    "weight_safety_margin_kg",
)
_VEHICLE_LEGACY_BOOLEAN_COLUMNS = ("can_use_trailer", "is_hgv")
_TRAILER_LEGACY_COLUMNS = (
    "tare_weight_kg",
    "max_gross_weight_kg",
    "length_mm",
    "width_mm",
    "height_mm",
    "platform_length_mm",
    "platform_width_mm",
    "platform_height_from_ground_mm",
    "max_platform_payload_kg",
    "payload_capacity_kg",
    "max_axle_load_kg",
    "max_cargo_length_mm",
    "max_cargo_width_mm",
    "max_cargo_height_mm",
    "max_cargo_weight_kg",
)

_VEHICLE_POSITIVE_LEGACY_VALUES = (
    "(tare_weight_kg IS NULL OR tare_weight_kg > 0) AND "
    "(max_gross_weight_kg IS NULL OR max_gross_weight_kg > 0) AND "
    "(length_mm IS NULL OR length_mm > 0) AND "
    "(width_mm IS NULL OR width_mm > 0) AND "
    "(height_mm IS NULL OR height_mm > 0) AND "
    "(axle_count IS NULL OR axle_count > 0) AND "
    "(max_axle_load_kg IS NULL OR max_axle_load_kg > 0) AND "
    "(payload_capacity_kg IS NULL OR payload_capacity_kg > 0) AND "
    "(platform_length_mm IS NULL OR platform_length_mm > 0) AND "
    "(platform_width_mm IS NULL OR platform_width_mm > 0) AND "
    "(platform_height_from_ground_mm IS NULL OR platform_height_from_ground_mm > 0) AND "
    "(max_platform_payload_kg IS NULL OR max_platform_payload_kg > 0) AND "
    "(max_cargo_length_mm IS NULL OR max_cargo_length_mm > 0) AND "
    "(max_cargo_width_mm IS NULL OR max_cargo_width_mm > 0) AND "
    "(max_cargo_height_mm IS NULL OR max_cargo_height_mm > 0) AND "
    "(max_cargo_weight_kg IS NULL OR max_cargo_weight_kg > 0) AND "
    "(combined_length_with_trailer_mm IS NULL OR "
    "combined_length_with_trailer_mm > 0) AND "
    "(coupling_length_mm IS NULL OR coupling_length_mm > 0)"
)
_TRAILER_POSITIVE_LEGACY_VALUES = (
    "(tare_weight_kg IS NULL OR tare_weight_kg > 0) AND "
    "(max_gross_weight_kg IS NULL OR max_gross_weight_kg > 0) AND "
    "(length_mm IS NULL OR length_mm > 0) AND "
    "(width_mm IS NULL OR width_mm > 0) AND "
    "(height_mm IS NULL OR height_mm > 0) AND "
    "(platform_length_mm IS NULL OR platform_length_mm > 0) AND "
    "(platform_width_mm IS NULL OR platform_width_mm > 0) AND "
    "(platform_height_from_ground_mm IS NULL OR platform_height_from_ground_mm > 0) AND "
    "(max_platform_payload_kg IS NULL OR max_platform_payload_kg > 0) AND "
    "(payload_capacity_kg IS NULL OR payload_capacity_kg > 0) AND "
    "(axle_count IS NULL OR axle_count > 0) AND "
    "(max_axle_load_kg IS NULL OR max_axle_load_kg > 0) AND "
    "(max_cargo_length_mm IS NULL OR max_cargo_length_mm > 0) AND "
    "(max_cargo_width_mm IS NULL OR max_cargo_width_mm > 0) AND "
    "(max_cargo_height_mm IS NULL OR max_cargo_height_mm > 0) AND "
    "(max_cargo_weight_kg IS NULL OR max_cargo_weight_kg > 0)"
)


def upgrade() -> None:
    """Drop the legacy projection and the unused HGV flag."""

    for column_name in _VEHICLE_LEGACY_COLUMNS:
        op.drop_column("vehicles", column_name)
    for column_name in _VEHICLE_LEGACY_BOOLEAN_COLUMNS:
        op.drop_column("vehicles", column_name)
    for column_name in _TRAILER_LEGACY_COLUMNS:
        op.drop_column("trailers", column_name)
    op.drop_column("vehicle_load_profiles", "max_actual_axle_load_kg")


def downgrade() -> None:
    """Restore the legacy projection and backfill it from canonical values."""

    for column_name in _VEHICLE_LEGACY_COLUMNS:
        op.add_column("vehicles", sa.Column(column_name, sa.Integer(), nullable=True))
    for column_name in _VEHICLE_LEGACY_BOOLEAN_COLUMNS:
        op.add_column("vehicles", sa.Column(column_name, sa.Boolean(), nullable=True))
    for column_name in _TRAILER_LEGACY_COLUMNS:
        op.add_column("trailers", sa.Column(column_name, sa.Integer(), nullable=True))
    op.add_column(
        "vehicle_load_profiles",
        sa.Column("max_actual_axle_load_kg", sa.Integer(), nullable=True),
    )

    # Derive the legacy kilogram/millimetre projection from canonical values.
    op.execute(
        "UPDATE vehicles SET "
        "tare_weight_kg = round(tare_weight_t * 1000), "
        "max_gross_weight_kg = round(max_gross_weight_t * 1000), "
        "length_mm = round(length_m * 1000), "
        "width_mm = round(width_m * 1000), "
        "height_mm = round(height_m * 1000), "
        "max_axle_load_kg = round(axle_load_t * 1000), "
        "payload_capacity_kg = round(payload_capacity_t * 1000), "
        "platform_length_mm = round(platform_length_m * 1000), "
        "platform_height_from_ground_mm = "
        "round(platform_height_from_ground_m * 1000), "
        "max_platform_payload_kg = round(max_platform_payload_t * 1000), "
        "max_cargo_length_mm = round(max_cargo_length_m * 1000), "
        "max_cargo_width_mm = round(max_cargo_width_m * 1000), "
        "max_cargo_height_mm = round(max_cargo_height_m * 1000), "
        "max_cargo_weight_kg = round(max_cargo_weight_t * 1000), "
        "can_use_trailer = has_trailer, "
        "coupling_length_mm = round(coupling_length_m * 1000), "
        "height_safety_margin_mm = round(height_safety_margin_m * 1000), "
        "width_safety_margin_mm = round(width_safety_margin_m * 1000), "
        "weight_safety_margin_kg = round(weight_safety_margin_t * 1000)"
    )
    op.execute(
        "UPDATE vehicles AS vehicle SET "
        "combined_length_with_trailer_mm = round(("
        "vehicle.length_m + vehicle.coupling_length_m + trailer.length_m"
        ") * 1000) "
        "FROM trailers AS trailer "
        "WHERE vehicle.default_trailer_id = trailer.id "
        "AND vehicle.length_m IS NOT NULL "
        "AND vehicle.coupling_length_m IS NOT NULL "
        "AND trailer.length_m IS NOT NULL"
    )
    op.execute(
        "UPDATE trailers SET "
        "tare_weight_kg = round(tare_weight_t * 1000), "
        "max_gross_weight_kg = round(max_gross_weight_t * 1000), "
        "length_mm = round(length_m * 1000), "
        "width_mm = round(width_m * 1000), "
        "height_mm = round(height_m * 1000), "
        "max_axle_load_kg = round(axle_load_t * 1000), "
        "payload_capacity_kg = round(payload_capacity_t * 1000), "
        "platform_length_mm = round(platform_length_m * 1000), "
        "platform_height_from_ground_mm = "
        "round(platform_height_from_ground_m * 1000), "
        "max_platform_payload_kg = round(max_platform_payload_t * 1000), "
        "max_cargo_length_mm = round(max_cargo_length_m * 1000), "
        "max_cargo_width_mm = round(max_cargo_width_m * 1000), "
        "max_cargo_height_mm = round(max_cargo_height_m * 1000), "
        "max_cargo_weight_kg = round(max_cargo_weight_t * 1000)"
    )
    op.execute(
        "UPDATE vehicle_load_profiles SET max_actual_axle_load_kg = "
        "round(max_actual_axle_load_t * 1000)"
    )

    for column_name in (
        "height_safety_margin_mm",
        "width_safety_margin_mm",
        "weight_safety_margin_kg",
    ):
        op.alter_column(
            "vehicles",
            column_name,
            existing_type=sa.Integer(),
            nullable=False,
            server_default="0",
        )
    op.alter_column(
        "vehicle_load_profiles",
        "max_actual_axle_load_kg",
        existing_type=sa.Integer(),
        nullable=False,
    )
    op.create_check_constraint(
        "positive_optional_routing_values",
        "vehicles",
        _VEHICLE_POSITIVE_LEGACY_VALUES,
    )
    op.create_check_constraint(
        "gross_not_below_tare",
        "vehicles",
        "max_gross_weight_kg IS NULL OR tare_weight_kg IS NULL OR "
        "max_gross_weight_kg >= tare_weight_kg",
    )
    op.create_check_constraint(
        "nonnegative_routing_safety_margins",
        "vehicles",
        "height_safety_margin_mm >= 0 AND width_safety_margin_mm >= 0 AND "
        "weight_safety_margin_kg >= 0",
    )
    op.create_check_constraint(
        "positive_optional_routing_values",
        "trailers",
        _TRAILER_POSITIVE_LEGACY_VALUES,
    )
    op.create_check_constraint(
        "gross_not_below_tare",
        "trailers",
        "max_gross_weight_kg IS NULL OR tare_weight_kg IS NULL OR "
        "max_gross_weight_kg >= tare_weight_kg",
    )
    op.create_check_constraint(
        "positive_actual_axle_load",
        "vehicle_load_profiles",
        "max_actual_axle_load_kg > 0",
    )
