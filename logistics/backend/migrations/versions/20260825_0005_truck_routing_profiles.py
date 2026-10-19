"""Add physical truck, trailer, cargo, and per-leg routing profile data.

Revision ID: 20260825_0005
Revises: 20260824_0004
Create Date: 2026-08-25
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260825_0005"
down_revision: str | None = "20260824_0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

UUID = sa.Uuid()
TIMESTAMPTZ = sa.DateTime(timezone=True)
JSON_OBJECT = postgresql.JSONB(astext_type=sa.Text())

POSITIVE_TRAILER_VALUES = (
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

POSITIVE_VEHICLE_VALUES = (
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
    "(combined_length_with_trailer_mm IS NULL OR combined_length_with_trailer_mm > 0) AND "
    "(coupling_length_mm IS NULL OR coupling_length_mm > 0)"
)

COMPLETE_CARGO = (
    "(cargo_length_mm IS NULL AND cargo_width_mm IS NULL AND "
    "cargo_height_mm IS NULL AND cargo_weight_kg IS NULL) OR "
    "(cargo_length_mm > 0 AND cargo_width_mm > 0 AND "
    "cargo_height_mm > 0 AND cargo_weight_kg > 0)"
)


def _optional_positive_integer(name: str) -> sa.Column[object]:
    """Return one nullable positive-valued physical specification column."""

    return sa.Column(name, sa.Integer(), nullable=True)


def upgrade() -> None:
    """Add backward-compatible nullable physical data and audited route profiles."""

    op.create_table(
        "trailers",
        sa.Column("id", UUID, nullable=False),
        sa.Column("scenario_id", UUID, nullable=False),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("registration_number", sa.String(64), nullable=False),
        sa.Column("active", sa.Boolean(), server_default=sa.true(), nullable=False),
        _optional_positive_integer("tare_weight_kg"),
        _optional_positive_integer("max_gross_weight_kg"),
        _optional_positive_integer("length_mm"),
        _optional_positive_integer("width_mm"),
        _optional_positive_integer("height_mm"),
        _optional_positive_integer("platform_length_mm"),
        _optional_positive_integer("platform_width_mm"),
        _optional_positive_integer("platform_height_from_ground_mm"),
        _optional_positive_integer("max_platform_payload_kg"),
        _optional_positive_integer("payload_capacity_kg"),
        _optional_positive_integer("axle_count"),
        _optional_positive_integer("max_axle_load_kg"),
        _optional_positive_integer("max_cargo_length_mm"),
        _optional_positive_integer("max_cargo_width_mm"),
        _optional_positive_integer("max_cargo_height_mm"),
        _optional_positive_integer("max_cargo_weight_kg"),
        sa.Column("notes", sa.Text(), server_default="", nullable=False),
        sa.CheckConstraint(POSITIVE_TRAILER_VALUES, name="positive_optional_routing_values"),
        sa.CheckConstraint(
            "max_gross_weight_kg IS NULL OR tare_weight_kg IS NULL OR "
            "max_gross_weight_kg >= tare_weight_kg",
            name="gross_not_below_tare",
        ),
        sa.ForeignKeyConstraint(
            ["scenario_id"],
            ["scenarios.id"],
            ondelete="CASCADE",
            name="fk_trailers_scenario_id_scenarios",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_trailers"),
        sa.UniqueConstraint(
            "scenario_id", "registration_number", name="uq_trailers_scenario_registration"
        ),
    )
    op.create_index("ix_trailers_scenario_active", "trailers", ["scenario_id", "active"])

    vehicle_columns = (
        sa.Column("vehicle_type", sa.String(64), nullable=True),
        sa.Column("manufacturer", sa.String(100), nullable=True),
        sa.Column("model", sa.String(100), nullable=True),
        sa.Column("is_hgv", sa.Boolean(), nullable=True),
        _optional_positive_integer("tare_weight_kg"),
        _optional_positive_integer("max_gross_weight_kg"),
        _optional_positive_integer("length_mm"),
        _optional_positive_integer("width_mm"),
        _optional_positive_integer("height_mm"),
        _optional_positive_integer("axle_count"),
        _optional_positive_integer("max_axle_load_kg"),
        _optional_positive_integer("payload_capacity_kg"),
        _optional_positive_integer("platform_length_mm"),
        _optional_positive_integer("platform_width_mm"),
        _optional_positive_integer("platform_height_from_ground_mm"),
        _optional_positive_integer("max_platform_payload_kg"),
        _optional_positive_integer("max_cargo_length_mm"),
        _optional_positive_integer("max_cargo_width_mm"),
        _optional_positive_integer("max_cargo_height_mm"),
        _optional_positive_integer("max_cargo_weight_kg"),
        sa.Column("can_use_trailer", sa.Boolean(), nullable=True),
        sa.Column("default_trailer_id", UUID, nullable=True),
        _optional_positive_integer("combined_length_with_trailer_mm"),
        _optional_positive_integer("coupling_length_mm"),
        sa.Column("height_safety_margin_mm", sa.Integer(), server_default="0", nullable=False),
        sa.Column("width_safety_margin_mm", sa.Integer(), server_default="0", nullable=False),
        sa.Column("weight_safety_margin_kg", sa.Integer(), server_default="0", nullable=False),
    )
    for column in vehicle_columns:
        op.add_column("vehicles", column)
    op.create_foreign_key(
        "fk_vehicles_default_trailer_id_trailers",
        "vehicles",
        "trailers",
        ["default_trailer_id"],
        ["id"],
        ondelete="SET NULL",
    )
    op.create_index("ix_vehicles_default_trailer_id", "vehicles", ["default_trailer_id"])
    op.create_check_constraint(
        "positive_optional_routing_values", "vehicles", POSITIVE_VEHICLE_VALUES
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

    op.create_table(
        "vehicle_load_profiles",
        sa.Column("id", UUID, nullable=False),
        sa.Column("vehicle_id", UUID, nullable=False),
        sa.Column("configuration_type", sa.String(64), nullable=False),
        sa.Column("max_actual_axle_load_kg", sa.Integer(), nullable=False),
        sa.CheckConstraint("max_actual_axle_load_kg > 0", name="positive_actual_axle_load"),
        sa.ForeignKeyConstraint(
            ["vehicle_id"],
            ["vehicles.id"],
            ondelete="CASCADE",
            name="fk_vehicle_load_profiles_vehicle_id_vehicles",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_vehicle_load_profiles"),
        sa.UniqueConstraint(
            "vehicle_id",
            "configuration_type",
            name="uq_vehicle_load_profiles_configuration",
        ),
    )
    op.create_index("ix_vehicle_load_profiles_vehicle_id", "vehicle_load_profiles", ["vehicle_id"])

    for table_name in ("logistics_requests", "planning_tasks"):
        op.add_column(table_name, _optional_positive_integer("cargo_length_mm"))
        op.add_column(table_name, _optional_positive_integer("cargo_width_mm"))
        op.add_column(table_name, _optional_positive_integer("cargo_height_mm"))
        op.add_column(table_name, _optional_positive_integer("cargo_weight_kg"))
        op.create_check_constraint("complete_positive_cargo_dimensions", table_name, COMPLETE_CARGO)

    op.add_column(
        "route_segments", sa.Column("routing_profile_snapshot", JSON_OBJECT, nullable=True)
    )
    op.add_column("route_segments", sa.Column("routing_provider", sa.String(64), nullable=True))
    op.add_column("route_segments", sa.Column("osm_data_version", sa.String(100), nullable=True))
    op.add_column("route_segments", sa.Column("routed_at", TIMESTAMPTZ, nullable=True))


def downgrade() -> None:
    """Remove only truck-routing persistence while preserving the original schema."""

    for column_name in (
        "routed_at",
        "osm_data_version",
        "routing_provider",
        "routing_profile_snapshot",
    ):
        op.drop_column("route_segments", column_name)

    for table_name in ("planning_tasks", "logistics_requests"):
        op.drop_constraint(
            op.f(f"ck_{table_name}_complete_positive_cargo_dimensions"),
            table_name,
            type_="check",
        )
        for column_name in (
            "cargo_weight_kg",
            "cargo_height_mm",
            "cargo_width_mm",
            "cargo_length_mm",
        ):
            op.drop_column(table_name, column_name)

    op.drop_index("ix_vehicle_load_profiles_vehicle_id", table_name="vehicle_load_profiles")
    op.drop_table("vehicle_load_profiles")

    op.drop_constraint(
        op.f("ck_vehicles_nonnegative_routing_safety_margins"),
        "vehicles",
        type_="check",
    )
    op.drop_constraint(op.f("ck_vehicles_gross_not_below_tare"), "vehicles", type_="check")
    op.drop_constraint(
        op.f("ck_vehicles_positive_optional_routing_values"),
        "vehicles",
        type_="check",
    )
    op.drop_index("ix_vehicles_default_trailer_id", table_name="vehicles")
    op.drop_constraint("fk_vehicles_default_trailer_id_trailers", "vehicles", type_="foreignkey")
    for column_name in (
        "weight_safety_margin_kg",
        "width_safety_margin_mm",
        "height_safety_margin_mm",
        "coupling_length_mm",
        "combined_length_with_trailer_mm",
        "default_trailer_id",
        "can_use_trailer",
        "max_cargo_weight_kg",
        "max_cargo_height_mm",
        "max_cargo_width_mm",
        "max_cargo_length_mm",
        "max_platform_payload_kg",
        "platform_height_from_ground_mm",
        "platform_width_mm",
        "platform_length_mm",
        "payload_capacity_kg",
        "max_axle_load_kg",
        "axle_count",
        "height_mm",
        "width_mm",
        "length_mm",
        "max_gross_weight_kg",
        "tare_weight_kg",
        "is_hgv",
        "model",
        "manufacturer",
        "vehicle_type",
    ):
        op.drop_column("vehicles", column_name)

    op.drop_index("ix_trailers_scenario_active", table_name="trailers")
    op.drop_table("trailers")
