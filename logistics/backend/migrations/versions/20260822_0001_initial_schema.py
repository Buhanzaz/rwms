"""Create the standalone logistics simulator persistence schema.

Revision ID: 20260822_0001
Revises: None
Create Date: 2026-08-22
"""

from collections.abc import Sequence

import geoalchemy2
import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260822_0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

UUID = sa.Uuid()
TIMESTAMPTZ = sa.DateTime(timezone=True)
JSON_OBJECT = postgresql.JSONB(astext_type=sa.Text())


def _uuid_pk() -> sa.Column[object]:
    """Return the repeated UUID primary-key column definition."""

    return sa.Column("id", UUID, nullable=False)


def _timestamps() -> tuple[sa.Column[object], sa.Column[object]]:
    """Return repeated timezone-aware audit timestamp columns."""

    return (
        sa.Column("created_at", TIMESTAMPTZ, server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", TIMESTAMPTZ, server_default=sa.text("now()"), nullable=False),
    )


def upgrade() -> None:
    """Create extensions, tables, constraints, and query-path indexes."""

    op.execute("CREATE EXTENSION IF NOT EXISTS postgis")
    op.execute("CREATE EXTENSION IF NOT EXISTS btree_gist")

    op.create_table(
        "scenarios",
        _uuid_pk(),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("description", sa.Text(), nullable=False),
        sa.Column("timezone", sa.String(100), nullable=False),
        sa.Column("default_planning_date", sa.Date(), nullable=True),
        sa.Column("seed", sa.Integer(), nullable=False),
        sa.Column("settings", JSON_OBJECT, nullable=False),
        *_timestamps(),
        sa.PrimaryKeyConstraint("id", name="pk_scenarios"),
    )
    op.create_table(
        "warehouses",
        _uuid_pk(),
        sa.Column("scenario_id", UUID, nullable=False),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("latitude", sa.Float(), nullable=False),
        sa.Column("longitude", sa.Float(), nullable=False),
        sa.Column("loading_minutes", sa.Integer(), nullable=False),
        sa.Column("unloading_minutes", sa.Integer(), nullable=False),
        sa.Column("turnaround_minutes", sa.Integer(), nullable=False),
        sa.Column("working_day_start", sa.Time(), nullable=False),
        sa.Column("working_day_end", sa.Time(), nullable=False),
        sa.CheckConstraint("latitude BETWEEN -90 AND 90", name="valid_latitude"),
        sa.CheckConstraint("longitude BETWEEN -180 AND 180", name="valid_longitude"),
        sa.CheckConstraint("loading_minutes >= 0", name="nonnegative_loading"),
        sa.CheckConstraint("unloading_minutes >= 0", name="nonnegative_unloading"),
        sa.CheckConstraint("turnaround_minutes >= 0", name="nonnegative_turnaround"),
        sa.ForeignKeyConstraint(
            ["scenario_id"], ["scenarios.id"], ondelete="CASCADE", name="fk_warehouses_scenario"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_warehouses"),
    )
    op.create_index("ix_warehouses_scenario_id", "warehouses", ["scenario_id"])
    op.create_table(
        "zones",
        _uuid_pk(),
        sa.Column("scenario_id", UUID, nullable=False),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("code", sa.String(64), nullable=False),
        sa.Column("route_group", sa.String(100), nullable=False),
        sa.Column(
            "geometry",
            geoalchemy2.Geometry(geometry_type="MULTIPOLYGON", srid=4326, spatial_index=False),
            nullable=False,
        ),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("priority", sa.Integer(), nullable=False),
        sa.Column("locked", sa.Boolean(), nullable=False),
        *_timestamps(),
        sa.CheckConstraint("version >= 1", name="positive_version"),
        sa.ForeignKeyConstraint(
            ["scenario_id"], ["scenarios.id"], ondelete="CASCADE", name="fk_zones_scenario"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_zones"),
        sa.UniqueConstraint("scenario_id", "code", name="uq_zones_scenario_code"),
    )
    op.create_index("ix_zones_scenario_priority", "zones", ["scenario_id", "priority"])
    op.create_index(
        "ix_zones_geometry_gist", "zones", ["geometry"], unique=False, postgresql_using="gist"
    )
    op.create_table(
        "zone_relations",
        _uuid_pk(),
        sa.Column("scenario_id", UUID, nullable=False),
        sa.Column("from_zone_id", UUID, nullable=False),
        sa.Column("to_zone_id", UUID, nullable=False),
        sa.Column("relation_type", sa.String(32), nullable=False),
        sa.Column("delivery_pair_allowed", sa.Boolean(), nullable=False),
        sa.Column("pickup_allowed", sa.Boolean(), nullable=False),
        sa.Column("max_detour_minutes", sa.Integer(), nullable=False),
        sa.Column("max_detour_ratio", sa.Float(), nullable=False),
        sa.Column("penalty", sa.Float(), nullable=False),
        sa.Column("is_bidirectional", sa.Boolean(), nullable=False),
        sa.CheckConstraint("from_zone_id <> to_zone_id", name="different_zones"),
        sa.CheckConstraint("max_detour_minutes >= 0", name="nonnegative_detour_minutes"),
        sa.CheckConstraint("max_detour_ratio >= 0", name="nonnegative_detour_ratio"),
        sa.ForeignKeyConstraint(
            ["scenario_id"], ["scenarios.id"], ondelete="CASCADE", name="fk_relations_scenario"
        ),
        sa.ForeignKeyConstraint(
            ["from_zone_id"], ["zones.id"], ondelete="CASCADE", name="fk_relations_from_zone"
        ),
        sa.ForeignKeyConstraint(
            ["to_zone_id"], ["zones.id"], ondelete="CASCADE", name="fk_relations_to_zone"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_zone_relations"),
        sa.UniqueConstraint(
            "scenario_id", "from_zone_id", "to_zone_id", name="uq_zone_relations_direction"
        ),
    )
    op.create_index("ix_zone_relations_scenario_id", "zone_relations", ["scenario_id"])
    op.create_table(
        "drivers",
        _uuid_pk(),
        sa.Column("scenario_id", UUID, nullable=False),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("preferred_route_group", sa.String(100), nullable=True),
        sa.Column("active", sa.Boolean(), nullable=False),
        sa.Column("notes", sa.Text(), nullable=False),
        sa.ForeignKeyConstraint(
            ["scenario_id"], ["scenarios.id"], ondelete="CASCADE", name="fk_drivers_scenario"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_drivers"),
    )
    op.create_index("ix_drivers_scenario_active", "drivers", ["scenario_id", "active"])
    op.create_table(
        "vehicles",
        _uuid_pk(),
        sa.Column("scenario_id", UUID, nullable=False),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("registration_number", sa.String(64), nullable=False),
        sa.Column("capacity", sa.Integer(), nullable=False),
        sa.Column("active", sa.Boolean(), nullable=False),
        sa.Column("average_speed_city", sa.Float(), nullable=False),
        sa.Column("average_speed_region", sa.Float(), nullable=False),
        sa.Column("notes", sa.Text(), nullable=False),
        sa.CheckConstraint("capacity BETWEEN 1 AND 2", name="valid_capacity"),
        sa.CheckConstraint("average_speed_city > 0", name="positive_city_speed"),
        sa.CheckConstraint("average_speed_region > 0", name="positive_region_speed"),
        sa.ForeignKeyConstraint(
            ["scenario_id"], ["scenarios.id"], ondelete="CASCADE", name="fk_vehicles_scenario"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_vehicles"),
        sa.UniqueConstraint(
            "scenario_id", "registration_number", name="uq_vehicles_scenario_registration"
        ),
    )
    op.create_index("ix_vehicles_scenario_active", "vehicles", ["scenario_id", "active"])
    op.create_table(
        "driver_shifts",
        _uuid_pk(),
        sa.Column("scenario_id", UUID, nullable=False),
        sa.Column("driver_id", UUID, nullable=False),
        sa.Column("vehicle_id", UUID, nullable=False),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column("start_at", TIMESTAMPTZ, nullable=False),
        sa.Column("end_at", TIMESTAMPTZ, nullable=False),
        sa.Column("break_minutes", sa.Integer(), nullable=False),
        sa.Column("preferred_route_group", sa.String(100), nullable=True),
        sa.Column("active", sa.Boolean(), nullable=False),
        sa.CheckConstraint("end_at > start_at", name="positive_duration"),
        sa.CheckConstraint("break_minutes >= 0", name="nonnegative_break"),
        sa.ForeignKeyConstraint(
            ["scenario_id"], ["scenarios.id"], ondelete="CASCADE", name="fk_shifts_scenario"
        ),
        sa.ForeignKeyConstraint(
            ["driver_id"], ["drivers.id"], ondelete="CASCADE", name="fk_shifts_driver"
        ),
        sa.ForeignKeyConstraint(
            ["vehicle_id"], ["vehicles.id"], ondelete="CASCADE", name="fk_shifts_vehicle"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_driver_shifts"),
    )
    op.create_index("ix_driver_shifts_scenario_date", "driver_shifts", ["scenario_id", "date"])
    op.create_index(
        "ix_driver_shifts_driver_interval", "driver_shifts", ["driver_id", "start_at", "end_at"]
    )
    op.create_index(
        "ix_driver_shifts_vehicle_interval",
        "driver_shifts",
        ["vehicle_id", "start_at", "end_at"],
    )
    op.execute(
        "ALTER TABLE driver_shifts ADD CONSTRAINT ex_driver_shifts_driver_overlap "
        "EXCLUDE USING gist (driver_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&) "
        "WHERE (active)"
    )
    op.execute(
        "ALTER TABLE driver_shifts ADD CONSTRAINT ex_driver_shifts_vehicle_overlap "
        "EXCLUDE USING gist (vehicle_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&) "
        "WHERE (active)"
    )
    op.create_table(
        "logistics_requests",
        _uuid_pk(),
        sa.Column("scenario_id", UUID, nullable=False),
        sa.Column("type", sa.String(16), nullable=False),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("address_label", sa.String(500), nullable=False),
        sa.Column("latitude", sa.Float(), nullable=False),
        sa.Column("longitude", sa.Float(), nullable=False),
        sa.Column("quantity", sa.Integer(), nullable=False),
        sa.Column("service_minutes", sa.Integer(), nullable=False),
        sa.Column("priority", sa.Integer(), nullable=False),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("zone_id", UUID, nullable=True),
        sa.Column("zone_version", sa.Integer(), nullable=True),
        sa.Column("zone_classification_status", sa.String(32), nullable=False),
        sa.Column("split_allowed", sa.Boolean(), nullable=False),
        sa.Column("notes", sa.Text(), nullable=False),
        *_timestamps(),
        sa.CheckConstraint("latitude BETWEEN -90 AND 90", name="valid_latitude"),
        sa.CheckConstraint("longitude BETWEEN -180 AND 180", name="valid_longitude"),
        sa.CheckConstraint("quantity > 0", name="positive_quantity"),
        sa.CheckConstraint("service_minutes >= 0", name="nonnegative_service"),
        sa.ForeignKeyConstraint(
            ["scenario_id"], ["scenarios.id"], ondelete="CASCADE", name="fk_requests_scenario"
        ),
        sa.ForeignKeyConstraint(
            ["zone_id"], ["zones.id"], ondelete="SET NULL", name="fk_requests_zone"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_logistics_requests"),
    )
    op.create_index(
        "ix_logistics_requests_scenario_status", "logistics_requests", ["scenario_id", "status"]
    )
    op.create_index(
        "ix_logistics_requests_zone_version", "logistics_requests", ["zone_id", "zone_version"]
    )
    op.create_table(
        "request_date_options",
        _uuid_pk(),
        sa.Column("request_id", UUID, nullable=False),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column("priority", sa.Integer(), nullable=False),
        sa.Column("window_start", sa.Time(), nullable=True),
        sa.Column("window_end", sa.Time(), nullable=True),
        sa.Column("is_hard", sa.Boolean(), nullable=False),
        sa.CheckConstraint(
            "window_start IS NULL OR window_end IS NULL OR window_end > window_start",
            name="valid_window",
        ),
        sa.ForeignKeyConstraint(
            ["request_id"],
            ["logistics_requests.id"],
            ondelete="CASCADE",
            name="fk_date_options_request",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_request_date_options"),
        sa.UniqueConstraint("request_id", "date", name="uq_request_date_options_request_date"),
    )
    op.create_index("ix_request_date_options_date", "request_date_options", ["date"])
    op.create_table(
        "planning_tasks",
        _uuid_pk(),
        sa.Column("request_id", UUID, nullable=False),
        sa.Column("part_number", sa.Integer(), nullable=False),
        sa.Column("quantity", sa.Integer(), nullable=False),
        sa.Column("type", sa.String(16), nullable=False),
        sa.Column("latitude", sa.Float(), nullable=False),
        sa.Column("longitude", sa.Float(), nullable=False),
        sa.Column("zone_id", UUID, nullable=True),
        sa.Column("zone_version", sa.Integer(), nullable=True),
        sa.Column("service_minutes", sa.Integer(), nullable=False),
        sa.Column("priority", sa.Integer(), nullable=False),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("locked", sa.Boolean(), nullable=False),
        sa.CheckConstraint("quantity BETWEEN 1 AND 2", name="valid_quantity"),
        sa.CheckConstraint("service_minutes >= 0", name="nonnegative_service"),
        sa.ForeignKeyConstraint(
            ["request_id"],
            ["logistics_requests.id"],
            ondelete="CASCADE",
            name="fk_tasks_request",
        ),
        sa.ForeignKeyConstraint(
            ["zone_id"], ["zones.id"], ondelete="SET NULL", name="fk_tasks_zone"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_planning_tasks"),
        sa.UniqueConstraint("request_id", "part_number", name="uq_planning_tasks_request_part"),
    )
    op.create_index("ix_planning_tasks_zone_status", "planning_tasks", ["zone_id", "status"])
    op.create_table(
        "route_plans",
        _uuid_pk(),
        sa.Column("scenario_id", UUID, nullable=False),
        sa.Column("warehouse_id", UUID, nullable=False),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("score", sa.Float(), nullable=False),
        sa.Column("metrics", JSON_OBJECT, nullable=False),
        sa.Column("validation_errors", JSON_OBJECT, nullable=False),
        sa.Column("validation_warnings", JSON_OBJECT, nullable=False),
        sa.Column("manually_changed", sa.Boolean(), nullable=False),
        *_timestamps(),
        sa.CheckConstraint("version >= 1", name="positive_version"),
        sa.ForeignKeyConstraint(
            ["scenario_id"], ["scenarios.id"], ondelete="CASCADE", name="fk_plans_scenario"
        ),
        sa.ForeignKeyConstraint(
            ["warehouse_id"], ["warehouses.id"], ondelete="RESTRICT", name="fk_plans_warehouse"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_route_plans"),
    )
    op.create_index("ix_route_plans_scenario_date", "route_plans", ["scenario_id", "date"])
    op.create_table(
        "route_cycles",
        _uuid_pk(),
        sa.Column("route_plan_id", UUID, nullable=False),
        sa.Column("driver_shift_id", UUID, nullable=False),
        sa.Column("sequence", sa.Integer(), nullable=False),
        sa.Column("planned_start", TIMESTAMPTZ, nullable=False),
        sa.Column("planned_finish", TIMESTAMPTZ, nullable=False),
        sa.Column("total_distance_meters", sa.Float(), nullable=False),
        sa.Column("total_travel_seconds", sa.Integer(), nullable=False),
        sa.Column("total_service_seconds", sa.Integer(), nullable=False),
        sa.Column("empty_distance_meters", sa.Float(), nullable=False),
        sa.Column("detour_seconds", sa.Integer(), nullable=False),
        sa.Column("score", sa.Float(), nullable=False),
        sa.Column("locked", sa.Boolean(), nullable=False),
        sa.Column("manually_changed", sa.Boolean(), nullable=False),
        sa.Column("metrics", JSON_OBJECT, nullable=False),
        sa.CheckConstraint("planned_finish >= planned_start", name="valid_interval"),
        sa.CheckConstraint("total_distance_meters >= 0", name="nonnegative_distance"),
        sa.CheckConstraint("total_travel_seconds >= 0", name="nonnegative_travel"),
        sa.CheckConstraint("total_service_seconds >= 0", name="nonnegative_service"),
        sa.CheckConstraint("empty_distance_meters >= 0", name="nonnegative_empty_distance"),
        sa.CheckConstraint("detour_seconds >= 0", name="nonnegative_detour"),
        sa.ForeignKeyConstraint(
            ["route_plan_id"],
            ["route_plans.id"],
            ondelete="CASCADE",
            name="fk_cycles_plan",
        ),
        sa.ForeignKeyConstraint(
            ["driver_shift_id"],
            ["driver_shifts.id"],
            ondelete="RESTRICT",
            name="fk_cycles_shift",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_route_cycles"),
        sa.UniqueConstraint(
            "route_plan_id",
            "driver_shift_id",
            "sequence",
            name="uq_route_cycles_plan_shift_sequence",
        ),
    )
    op.create_index(
        "ix_route_cycles_shift_interval",
        "route_cycles",
        ["driver_shift_id", "planned_start", "planned_finish"],
    )
    op.create_index(
        "ix_route_cycles_plan_shift_sequence",
        "route_cycles",
        ["route_plan_id", "driver_shift_id", "sequence"],
    )
    op.create_table(
        "route_stops",
        _uuid_pk(),
        sa.Column("route_cycle_id", UUID, nullable=False),
        sa.Column("sequence", sa.Integer(), nullable=False),
        sa.Column("task_id", UUID, nullable=True),
        sa.Column("stop_type", sa.String(32), nullable=False),
        sa.Column("planned_arrival", TIMESTAMPTZ, nullable=False),
        sa.Column("planned_departure", TIMESTAMPTZ, nullable=False),
        sa.Column("service_seconds", sa.Integer(), nullable=False),
        sa.Column("quantity_delta", sa.Integer(), nullable=False),
        sa.Column("load_before", sa.Integer(), nullable=False),
        sa.Column("load_after", sa.Integer(), nullable=False),
        sa.Column("latitude", sa.Float(), nullable=False),
        sa.Column("longitude", sa.Float(), nullable=False),
        sa.Column("warnings", JSON_OBJECT, nullable=False),
        sa.Column("locked", sa.Boolean(), nullable=False),
        sa.CheckConstraint("planned_departure >= planned_arrival", name="valid_interval"),
        sa.CheckConstraint("service_seconds >= 0", name="nonnegative_service"),
        sa.CheckConstraint("load_before >= 0", name="nonnegative_load_before"),
        sa.CheckConstraint("load_after >= 0", name="nonnegative_load_after"),
        sa.CheckConstraint("latitude BETWEEN -90 AND 90", name="valid_latitude"),
        sa.CheckConstraint("longitude BETWEEN -180 AND 180", name="valid_longitude"),
        sa.ForeignKeyConstraint(
            ["route_cycle_id"],
            ["route_cycles.id"],
            ondelete="CASCADE",
            name="fk_stops_cycle",
        ),
        sa.ForeignKeyConstraint(
            ["task_id"], ["planning_tasks.id"], ondelete="RESTRICT", name="fk_stops_task"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_route_stops"),
        sa.UniqueConstraint("route_cycle_id", "sequence", name="uq_route_stops_cycle_sequence"),
    )
    op.create_table(
        "route_segments",
        _uuid_pk(),
        sa.Column("route_cycle_id", UUID, nullable=False),
        sa.Column("sequence", sa.Integer(), nullable=False),
        sa.Column("from_stop_id", UUID, nullable=False),
        sa.Column("to_stop_id", UUID, nullable=False),
        sa.Column("departure_at", TIMESTAMPTZ, nullable=False),
        sa.Column("arrival_at", TIMESTAMPTZ, nullable=False),
        sa.Column("distance_meters", sa.Float(), nullable=False),
        sa.Column("travel_seconds", sa.Integer(), nullable=False),
        sa.Column(
            "geometry",
            geoalchemy2.Geometry(geometry_type="LINESTRING", srid=4326, spatial_index=False),
            nullable=False,
        ),
        sa.CheckConstraint("arrival_at >= departure_at", name="valid_interval"),
        sa.CheckConstraint("distance_meters >= 0", name="nonnegative_distance"),
        sa.CheckConstraint("travel_seconds >= 0", name="nonnegative_travel"),
        sa.ForeignKeyConstraint(
            ["route_cycle_id"],
            ["route_cycles.id"],
            ondelete="CASCADE",
            name="fk_segments_cycle",
        ),
        sa.ForeignKeyConstraint(
            ["from_stop_id"],
            ["route_stops.id"],
            ondelete="CASCADE",
            name="fk_segments_from_stop",
        ),
        sa.ForeignKeyConstraint(
            ["to_stop_id"],
            ["route_stops.id"],
            ondelete="CASCADE",
            name="fk_segments_to_stop",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_route_segments"),
        sa.UniqueConstraint("route_cycle_id", "sequence", name="uq_route_segments_cycle_sequence"),
    )
    op.create_table(
        "route_explanations",
        _uuid_pk(),
        sa.Column("route_cycle_id", UUID, nullable=False),
        sa.Column("explanation_type", sa.String(64), nullable=False),
        sa.Column("summary_ru", sa.Text(), nullable=False),
        sa.Column("facts", JSON_OBJECT, nullable=False),
        sa.ForeignKeyConstraint(
            ["route_cycle_id"],
            ["route_cycles.id"],
            ondelete="CASCADE",
            name="fk_explanations_cycle",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_route_explanations"),
    )
    op.create_table(
        "unassigned_tasks",
        _uuid_pk(),
        sa.Column("route_plan_id", UUID, nullable=False),
        sa.Column("task_id", UUID, nullable=False),
        sa.Column("reason_codes", JSON_OBJECT, nullable=False),
        sa.Column("descriptions_ru", JSON_OBJECT, nullable=False),
        sa.Column("nearest_option", JSON_OBJECT, nullable=True),
        sa.Column("recommendation_ru", sa.Text(), nullable=True),
        sa.ForeignKeyConstraint(
            ["route_plan_id"],
            ["route_plans.id"],
            ondelete="CASCADE",
            name="fk_unassigned_plan",
        ),
        sa.ForeignKeyConstraint(
            ["task_id"],
            ["planning_tasks.id"],
            ondelete="RESTRICT",
            name="fk_unassigned_task",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_unassigned_tasks"),
        sa.UniqueConstraint("route_plan_id", "task_id", name="uq_unassigned_tasks_plan_task"),
    )
    op.create_table(
        "optimization_runs",
        _uuid_pk(),
        sa.Column("scenario_id", UUID, nullable=False),
        sa.Column("plan_id", UUID, nullable=True),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("started_at", TIMESTAMPTZ, nullable=True),
        sa.Column("finished_at", TIMESTAMPTZ, nullable=True),
        sa.Column("seed", sa.Integer(), nullable=False),
        sa.Column("settings_snapshot", JSON_OBJECT, nullable=False),
        sa.Column("initial_score", sa.Float(), nullable=True),
        sa.Column("final_score", sa.Float(), nullable=True),
        sa.Column("error_message", sa.Text(), nullable=True),
        sa.Column("stopped_by_limit", sa.Boolean(), nullable=False),
        sa.Column("cancel_requested", sa.Boolean(), nullable=False),
        sa.ForeignKeyConstraint(
            ["scenario_id"], ["scenarios.id"], ondelete="CASCADE", name="fk_runs_scenario"
        ),
        sa.ForeignKeyConstraint(
            ["plan_id"], ["route_plans.id"], ondelete="SET NULL", name="fk_runs_plan"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_optimization_runs"),
    )
    op.create_index(
        "ix_optimization_runs_scenario_started",
        "optimization_runs",
        ["scenario_id", "started_at"],
    )
    op.create_table(
        "optimization_trace_events",
        _uuid_pk(),
        sa.Column("optimization_run_id", UUID, nullable=False),
        sa.Column("sequence", sa.Integer(), nullable=False),
        sa.Column("event_type", sa.String(64), nullable=False),
        sa.Column("payload", JSON_OBJECT, nullable=False),
        sa.Column("created_at", TIMESTAMPTZ, server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(
            ["optimization_run_id"],
            ["optimization_runs.id"],
            ondelete="CASCADE",
            name="fk_trace_run",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_optimization_trace_events"),
        sa.UniqueConstraint("optimization_run_id", "sequence", name="uq_trace_run_sequence"),
    )
    op.create_index(
        "ix_trace_run_sequence",
        "optimization_trace_events",
        ["optimization_run_id", "sequence"],
    )
    op.create_table(
        "manual_change_audits",
        _uuid_pk(),
        sa.Column("route_plan_id", UUID, nullable=False),
        sa.Column("changed_by", sa.String(200), nullable=False),
        sa.Column("changed_at", TIMESTAMPTZ, server_default=sa.text("now()"), nullable=False),
        sa.Column("change_type", sa.String(64), nullable=False),
        sa.Column("previous_value", JSON_OBJECT, nullable=True),
        sa.Column("new_value", JSON_OBJECT, nullable=True),
        sa.Column("reason", sa.Text(), nullable=False),
        sa.Column("plan_version_before", sa.Integer(), nullable=False),
        sa.Column("plan_version_after", sa.Integer(), nullable=False),
        sa.ForeignKeyConstraint(
            ["route_plan_id"],
            ["route_plans.id"],
            ondelete="CASCADE",
            name="fk_manual_changes_plan",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_manual_change_audits"),
    )
    op.create_index(
        "ix_manual_changes_plan_changed",
        "manual_change_audits",
        ["route_plan_id", "changed_at"],
    )


def downgrade() -> None:
    """Drop simulator-owned objects without dropping shared PostgreSQL extensions."""

    for table in (
        "manual_change_audits",
        "optimization_trace_events",
        "optimization_runs",
        "unassigned_tasks",
        "route_explanations",
        "route_segments",
        "route_stops",
        "route_cycles",
        "route_plans",
        "planning_tasks",
        "request_date_options",
        "logistics_requests",
        "driver_shifts",
        "vehicles",
        "drivers",
        "zone_relations",
        "zones",
        "warehouses",
        "scenarios",
    ):
        op.drop_table(table)
