"""Canonical trailer data and routing extensions in tonnes and metres.

Revision ID: 20261020_0039
Revises: 20260908_0038

Legacy kilogram/millimetre columns stay in place as a rollback projection.
Active application code reads and writes only the canonical columns.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20261020_0039"
down_revision: str | None = "20260908_0038"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_NUMERIC = sa.Numeric(12, 3)
_MARGIN_COLUMNS = (
    "height_safety_margin_m",
    "width_safety_margin_m",
    "weight_safety_margin_t",
)
_VEHICLE_EXTENSION_COLUMNS = (
    "platform_length_m",
    "platform_height_from_ground_m",
    "max_platform_payload_t",
    "max_cargo_length_m",
    "max_cargo_width_m",
    "max_cargo_height_m",
    "max_cargo_weight_t",
    "coupling_length_m",
)
_TRAILER_CANONICAL_COLUMNS = (
    "tare_weight_t",
    "max_gross_weight_t",
    "axle_load_t",
    "payload_capacity_t",
    "length_m",
    "width_m",
    "height_m",
    "platform_length_m",
    "platform_height_from_ground_m",
    "max_platform_payload_t",
    "max_cargo_length_m",
    "max_cargo_width_m",
    "max_cargo_height_m",
    "max_cargo_weight_t",
)
_POSITIVE_CANONICAL_VEHICLE_VALUES = (
    "(tare_weight_t IS NULL OR tare_weight_t > 0) AND "
    "(max_gross_weight_t IS NULL OR max_gross_weight_t > 0) AND "
    "(axle_load_t IS NULL OR axle_load_t > 0) AND "
    "(payload_capacity_t IS NULL OR payload_capacity_t > 0) AND "
    "(length_m IS NULL OR length_m > 0) AND "
    "(width_m IS NULL OR width_m > 0) AND "
    "(height_m IS NULL OR height_m > 0) AND "
    "(platform_length_m IS NULL OR platform_length_m > 0) AND "
    "(platform_height_from_ground_m IS NULL OR platform_height_from_ground_m > 0) AND "
    "(max_platform_payload_t IS NULL OR max_platform_payload_t > 0) AND "
    "(max_cargo_length_m IS NULL OR max_cargo_length_m > 0) AND "
    "(max_cargo_width_m IS NULL OR max_cargo_width_m > 0) AND "
    "(max_cargo_height_m IS NULL OR max_cargo_height_m > 0) AND "
    "(max_cargo_weight_t IS NULL OR max_cargo_weight_t > 0) AND "
    "(coupling_length_m IS NULL OR coupling_length_m > 0) AND "
    "height_safety_margin_m >= 0 AND width_safety_margin_m >= 0 AND "
    "weight_safety_margin_t >= 0"
)
_POSITIVE_CANONICAL_TRAILER_VALUES = (
    "(tare_weight_t IS NULL OR tare_weight_t > 0) AND "
    "(max_gross_weight_t IS NULL OR max_gross_weight_t > 0) AND "
    "(axle_load_t IS NULL OR axle_load_t > 0) AND "
    "(payload_capacity_t IS NULL OR payload_capacity_t > 0) AND "
    "(length_m IS NULL OR length_m > 0) AND "
    "(width_m IS NULL OR width_m > 0) AND "
    "(height_m IS NULL OR height_m > 0) AND "
    "(platform_length_m IS NULL OR platform_length_m > 0) AND "
    "(platform_height_from_ground_m IS NULL OR platform_height_from_ground_m > 0) AND "
    "(max_platform_payload_t IS NULL OR max_platform_payload_t > 0) AND "
    "(max_cargo_length_m IS NULL OR max_cargo_length_m > 0) AND "
    "(max_cargo_width_m IS NULL OR max_cargo_width_m > 0) AND "
    "(max_cargo_height_m IS NULL OR max_cargo_height_m > 0) AND "
    "(max_cargo_weight_t IS NULL OR max_cargo_weight_t > 0)"
)
_CANONICAL_TRAILER_GROSS = (
    "max_gross_weight_t IS NULL OR tare_weight_t IS NULL OR "
    "max_gross_weight_t >= tare_weight_t"
)


def upgrade() -> None:
    """Add canonical extensions and trailer columns, then backfill exactly."""

    for column_name in _VEHICLE_EXTENSION_COLUMNS:
        op.add_column("vehicles", sa.Column(column_name, _NUMERIC, nullable=True))
    for column_name in _MARGIN_COLUMNS:
        op.add_column("vehicles", sa.Column(column_name, _NUMERIC, nullable=True))

    for column_name in _TRAILER_CANONICAL_COLUMNS:
        op.add_column("trailers", sa.Column(column_name, _NUMERIC, nullable=True))

    op.execute(
        "UPDATE vehicles SET "
        "platform_length_m = round(platform_length_mm::numeric / 1000, 3), "
        "platform_height_from_ground_m = "
        "round(platform_height_from_ground_mm::numeric / 1000, 3), "
        "max_platform_payload_t = round(max_platform_payload_kg::numeric / 1000, 3), "
        "max_cargo_length_m = round(max_cargo_length_mm::numeric / 1000, 3), "
        "max_cargo_width_m = round(max_cargo_width_mm::numeric / 1000, 3), "
        "max_cargo_height_m = round(max_cargo_height_mm::numeric / 1000, 3), "
        "max_cargo_weight_t = round(max_cargo_weight_kg::numeric / 1000, 3), "
        "coupling_length_m = round(coupling_length_mm::numeric / 1000, 3), "
        "height_safety_margin_m = round(height_safety_margin_mm::numeric / 1000, 3), "
        "width_safety_margin_m = round(width_safety_margin_mm::numeric / 1000, 3), "
        "weight_safety_margin_t = round(weight_safety_margin_kg::numeric / 1000, 3), "
        "has_trailer = has_trailer OR COALESCE(can_use_trailer, false)"
    )
    op.execute(
        "UPDATE trailers SET "
        "tare_weight_t = round(tare_weight_kg::numeric / 1000, 3), "
        "max_gross_weight_t = round(max_gross_weight_kg::numeric / 1000, 3), "
        "axle_load_t = round(max_axle_load_kg::numeric / 1000, 3), "
        "payload_capacity_t = round(payload_capacity_kg::numeric / 1000, 3), "
        "length_m = round(length_mm::numeric / 1000, 3), "
        "width_m = round(width_mm::numeric / 1000, 3), "
        "height_m = round(height_mm::numeric / 1000, 3), "
        "platform_length_m = round(platform_length_mm::numeric / 1000, 3), "
        "platform_height_from_ground_m = "
        "round(platform_height_from_ground_mm::numeric / 1000, 3), "
        "max_platform_payload_t = round(max_platform_payload_kg::numeric / 1000, 3), "
        "max_cargo_length_m = round(max_cargo_length_mm::numeric / 1000, 3), "
        "max_cargo_width_m = round(max_cargo_width_mm::numeric / 1000, 3), "
        "max_cargo_height_m = round(max_cargo_height_mm::numeric / 1000, 3), "
        "max_cargo_weight_t = round(max_cargo_weight_kg::numeric / 1000, 3)"
    )

    for column_name in _MARGIN_COLUMNS:
        op.execute(f"UPDATE vehicles SET {column_name} = 0 WHERE {column_name} IS NULL")
        op.alter_column(
            "vehicles",
            column_name,
            existing_type=_NUMERIC,
            nullable=False,
            server_default="0",
        )

    # The canonical axle load is now required; the kilogram projection is kept
    # nullable for rollback compatibility.
    op.drop_constraint(
        op.f("ck_vehicle_load_profiles_positive_canonical_actual_axle_load"),
        "vehicle_load_profiles",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_vehicle_load_profiles_positive_actual_axle_load"),
        "vehicle_load_profiles",
        type_="check",
    )
    op.alter_column(
        "vehicle_load_profiles",
        "max_actual_axle_load_kg",
        existing_type=sa.Integer(),
        nullable=True,
    )
    op.alter_column(
        "vehicle_load_profiles",
        "max_actual_axle_load_t",
        existing_type=_NUMERIC,
        nullable=False,
    )
    op.create_check_constraint(
        "positive_actual_axle_load",
        "vehicle_load_profiles",
        "max_actual_axle_load_kg IS NULL OR max_actual_axle_load_kg > 0",
    )
    op.create_check_constraint(
        "positive_canonical_actual_axle_load",
        "vehicle_load_profiles",
        "max_actual_axle_load_t > 0",
    )

    op.drop_constraint(
        op.f("ck_vehicles_positive_canonical_vehicle_values"),
        "vehicles",
        type_="check",
    )
    op.create_check_constraint(
        "positive_canonical_vehicle_values",
        "vehicles",
        _POSITIVE_CANONICAL_VEHICLE_VALUES,
    )
    op.create_check_constraint(
        "positive_canonical_trailer_values",
        "trailers",
        _POSITIVE_CANONICAL_TRAILER_VALUES,
    )
    op.create_check_constraint(
        "canonical_trailer_gross_not_below_tare",
        "trailers",
        _CANONICAL_TRAILER_GROSS,
    )


def downgrade() -> None:
    """Restore legacy constraints and remove canonical trailer/extensions."""

    op.drop_constraint(
        op.f("ck_trailers_canonical_trailer_gross_not_below_tare"),
        "trailers",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_trailers_positive_canonical_trailer_values"),
        "trailers",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_vehicles_positive_canonical_vehicle_values"),
        "vehicles",
        type_="check",
    )
    op.create_check_constraint(
        "positive_canonical_vehicle_values",
        "vehicles",
        "(tare_weight_t IS NULL OR tare_weight_t > 0) AND "
        "(max_gross_weight_t IS NULL OR max_gross_weight_t > 0) AND "
        "(axle_load_t IS NULL OR axle_load_t > 0) AND "
        "(payload_capacity_t IS NULL OR payload_capacity_t > 0) AND "
        "(length_m IS NULL OR length_m > 0) AND "
        "(width_m IS NULL OR width_m > 0) AND "
        "(height_m IS NULL OR height_m > 0)",
    )

    op.drop_constraint(
        op.f("ck_vehicle_load_profiles_positive_canonical_actual_axle_load"),
        "vehicle_load_profiles",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_vehicle_load_profiles_positive_actual_axle_load"),
        "vehicle_load_profiles",
        type_="check",
    )
    op.execute(
        "UPDATE vehicle_load_profiles SET max_actual_axle_load_kg = "
        "round(max_actual_axle_load_t * 1000)"
    )
    op.alter_column(
        "vehicle_load_profiles",
        "max_actual_axle_load_kg",
        existing_type=sa.Integer(),
        nullable=False,
    )
    op.alter_column(
        "vehicle_load_profiles",
        "max_actual_axle_load_t",
        existing_type=_NUMERIC,
        nullable=True,
    )
    op.create_check_constraint(
        "positive_actual_axle_load",
        "vehicle_load_profiles",
        "max_actual_axle_load_kg > 0",
    )
    op.create_check_constraint(
        "positive_canonical_actual_axle_load",
        "vehicle_load_profiles",
        "max_actual_axle_load_t IS NULL OR max_actual_axle_load_t > 0",
    )

    for column_name in _MARGIN_COLUMNS:
        op.drop_column("vehicles", column_name)
    for column_name in _VEHICLE_EXTENSION_COLUMNS:
        op.drop_column("vehicles", column_name)
    for column_name in _TRAILER_CANONICAL_COLUMNS:
        op.drop_column("trailers", column_name)
