"""Replace scenario containers with RWMS-bound warehouse workspaces.

Revision ID: 20260828_0016
Revises: 20260828_0015
Create Date: 2026-08-28
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260828_0016"
down_revision: str | None = "20260828_0015"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Preserve one-warehouse workspaces and remove the obsolete scenario aggregate."""

    op.execute(
        """
        DELETE FROM scenarios
        WHERE description =
          'Демонстрационный сценарий: четыре зоны, три смены и парные заявки.'
           OR (
             name = 'Многодневный тестовый стенд · 3 водителя'
             AND description =
               'Три дня, шесть зон, три параллельные смены и по 10 заявок на каждую дату. '
               'Часть клиентов согласна на соседний день. Исходный demo не изменяется.'
           )
        """
    )
    op.execute(
        """
        DO $$
        BEGIN
          IF EXISTS (
            SELECT 1
            FROM scenarios s
            LEFT JOIN warehouses w ON w.scenario_id = s.id
            GROUP BY s.id
            HAVING count(w.id) <> 1
          ) THEN
            RAISE EXCEPTION
              'warehouse workspace migration requires exactly one warehouse per scenario';
          END IF;
          IF EXISTS (SELECT 1 FROM warehouses WHERE external_warehouse_id IS NULL) THEN
            RAISE EXCEPTION
              'warehouse workspace migration requires every warehouse to have an RWMS identity';
          END IF;
          IF EXISTS (
            SELECT 1 FROM warehouses
            GROUP BY external_warehouse_id HAVING count(*) > 1
          ) THEN
            RAISE EXCEPTION
              'warehouse workspace migration found duplicate RWMS warehouse identities';
          END IF;
        END $$
        """
    )

    for column in (
        sa.Column("city", sa.String(200), nullable=True),
        sa.Column("address", sa.String(500), nullable=True),
        sa.Column("timezone", sa.String(100), nullable=True),
        sa.Column("default_planning_date", sa.Date(), nullable=True),
        sa.Column("seed", sa.Integer(), nullable=True),
        sa.Column(
            "settings",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=True,
        ),
        sa.Column("capacity_generation", sa.BigInteger(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=True),
    ):
        op.add_column("warehouses", column)
    op.execute(
        """
        UPDATE warehouses w
        SET address = w.name,
            timezone = s.timezone,
            default_planning_date = s.default_planning_date,
            seed = s.seed,
            settings = s.settings - 'cross_group_penalty' - 'driver_preference_bonus',
            capacity_generation = s.capacity_generation,
            created_at = s.created_at,
            updated_at = s.updated_at
        FROM scenarios s
        WHERE s.id = w.scenario_id
        """
    )
    for column in (
        "external_warehouse_id",
        "address",
        "timezone",
        "seed",
        "settings",
        "capacity_generation",
    ):
        op.alter_column("warehouses", column, nullable=False)
    for column in ("created_at", "updated_at"):
        op.alter_column(
            "warehouses",
            column,
            nullable=False,
            server_default=sa.text("now()"),
        )

    op.add_column(
        "drivers",
        sa.Column(
            "rwms_assignment_mode",
            sa.String(32),
            server_default="WAREHOUSE_DRIVERS",
            nullable=False,
        ),
    )
    op.add_column(
        "logistics_requests",
        sa.Column("mandatory", sa.Boolean(), server_default=sa.false(), nullable=False),
    )
    op.add_column(
        "planning_tasks",
        sa.Column("mandatory", sa.Boolean(), server_default=sa.false(), nullable=False),
    )
    op.execute(
        """
        UPDATE drivers
        SET rwms_assignment_mode = CASE
          WHEN external_worker_id IS NULL THEN 'WAREHOUSE_DRIVERS'
          ELSE 'ASSIGNED_DRIVER'
        END
        """
    )
    op.execute(
        """
        UPDATE logistics_requests
        SET source_system = 'WAREHOUSE_WORKLOAD_GENERATOR'
        WHERE source_system = 'SIMULATOR_GENERATOR'
           OR (
             source_system IS NULL
             AND external_id IS NULL
             AND notes LIKE 'Детерминированная нагрузка, seed=%'
           )
        """
    )

    for table in ("drivers", "vehicles", "trailers", "driver_shifts", "logistics_requests"):
        op.add_column(table, sa.Column("warehouse_id", sa.Uuid(), nullable=True))
        op.execute(
            sa.text(
                f"""
                UPDATE {table} owned
                SET warehouse_id = w.id
                FROM warehouses w
                WHERE w.scenario_id = owned.scenario_id
                """
            )
        )
        op.alter_column(table, "warehouse_id", nullable=False)

    op.add_column("optimization_runs", sa.Column("warehouse_id", sa.Uuid(), nullable=True))
    op.execute(
        """
        UPDATE optimization_runs run
        SET warehouse_id = COALESCE(
          (SELECT plan.warehouse_id FROM route_plans plan WHERE plan.id = run.plan_id),
          (SELECT workspace.id
           FROM warehouses workspace
           WHERE workspace.scenario_id = run.scenario_id)
        )
        """
    )
    op.alter_column("optimization_runs", "warehouse_id", nullable=False)

    op.add_column("zones", sa.Column("color", sa.String(7), nullable=True))
    op.execute(
        """
        UPDATE zones
        SET color = (ARRAY[
          '#22C55E', '#3B82F6', '#A855F7', '#F97316',
          '#EAB308', '#14B8A6', '#EC4899', '#6366F1'
        ])[1 + mod(abs(hashtext(id::text)::bigint), 8)]
        """
    )
    op.alter_column("zones", "color", nullable=False)

    op.add_column("driver_shifts", sa.Column("date_from", sa.Date(), nullable=True))
    op.add_column("driver_shifts", sa.Column("date_to", sa.Date(), nullable=True))
    op.add_column("driver_shifts", sa.Column("start_time", sa.Time(), nullable=True))
    op.add_column("driver_shifts", sa.Column("end_time", sa.Time(), nullable=True))
    op.execute(
        """
        UPDATE driver_shifts shift
        SET date_from = shift.date,
            date_to = shift.date,
            start_time = (shift.start_at AT TIME ZONE workspace.timezone)::time,
            end_time = (shift.end_at AT TIME ZONE workspace.timezone)::time
        FROM warehouses workspace
        WHERE workspace.id = shift.warehouse_id
        """
    )
    for column in ("date_from", "date_to", "start_time", "end_time"):
        op.alter_column("driver_shifts", column, nullable=False)

    op.execute(
        """
        CREATE TEMP TABLE mergeable_shift_periods ON COMMIT DROP AS
        WITH eligible AS (
          SELECT shift.*,
                 shift.date - (
                   row_number() OVER (
                     PARTITION BY shift.warehouse_id, shift.driver_id, shift.vehicle_id,
                                  shift.start_time, shift.end_time, shift.break_minutes,
                                  shift.active, date_trunc('month', shift.date)
                     ORDER BY shift.date, shift.id
                   )::integer
                 ) AS period_key
          FROM driver_shifts shift
          WHERE NOT EXISTS (
            SELECT 1 FROM route_cycles cycle WHERE cycle.driver_shift_id = shift.id
          )
        )
        SELECT (array_agg(id ORDER BY date, id))[1] AS keeper_id,
               array_agg(id ORDER BY date, id) AS member_ids,
               min(date) AS date_from,
               max(date) AS date_to
        FROM eligible
        GROUP BY warehouse_id, driver_id, vehicle_id, start_time, end_time,
                 break_minutes, active, date_trunc('month', date), period_key
        HAVING count(*) > 1
        """
    )
    op.execute(
        """
        UPDATE driver_shifts shift
        SET date_from = period.date_from,
            date_to = period.date_to
        FROM mergeable_shift_periods period
        WHERE shift.id = period.keeper_id
        """
    )
    op.execute(
        """
        DELETE FROM driver_shifts shift
        USING mergeable_shift_periods period
        WHERE shift.id = ANY(period.member_ids)
          AND shift.id <> period.keeper_id
        """
    )

    op.add_column("slot_holds", sa.Column("price_zone_id", sa.Uuid(), nullable=True))
    op.execute(
        """
        UPDATE slot_holds hold
        SET price_zone_id = zone.id
        FROM slot_day_plans day_plan, zones zone
        WHERE day_plan.id = hold.day_plan_id
          AND zone.scenario_id = day_plan.scenario_id
          AND zone.code = hold.price_zone_code
        """
    )

    op.drop_table("zone_relations")

    op.execute(
        """
        DO $$
        DECLARE dependency record;
        BEGIN
          FOR dependency IN
            SELECT conrelid::regclass::text AS table_name, conname
            FROM pg_constraint
            WHERE confrelid = 'scenarios'::regclass
          LOOP
            EXECUTE format(
              'ALTER TABLE %I DROP CONSTRAINT %I',
              dependency.table_name,
              dependency.conname
            );
          END LOOP;
        END $$
        """
    )

    for table, constraints in {
        "warehouses": ("uq_warehouses_scenario_external_warehouse",),
        "drivers": ("uq_drivers_scenario_external_worker",),
        "vehicles": ("uq_vehicles_scenario_registration",),
        "trailers": ("uq_trailers_scenario_registration",),
        "logistics_requests": ("uq_logistics_requests_external_source",),
        "slot_day_plans": ("uq_slot_day_plans_scenario_warehouse_date",),
        "planning_day_closures": ("uq_planning_day_closures_scenario_warehouse_date",),
        "zones": ("uq_zones_scenario_code",),
    }.items():
        for constraint in constraints:
            op.execute(
                sa.text(f'ALTER TABLE {table} DROP CONSTRAINT IF EXISTS "{constraint}"')
            )

    for index in (
        "ix_warehouses_scenario_id",
        "ix_zones_scenario_priority",
        "ix_drivers_scenario_active",
        "ix_vehicles_scenario_active",
        "ix_trailers_scenario_active",
        "ix_driver_shifts_scenario_date",
        "ix_driver_shifts_driver_interval",
        "ix_driver_shifts_vehicle_interval",
        "ix_logistics_requests_scenario_status",
        "ix_logistics_requests_scenario_scheduled_date",
        "ix_route_plans_scenario_date",
        "ix_optimization_runs_scenario_started",
    ):
        op.execute(sa.text(f'DROP INDEX IF EXISTS "{index}"'))

    op.execute(
        "ALTER TABLE driver_shifts DROP CONSTRAINT IF EXISTS ex_driver_shifts_driver_overlap"
    )
    op.execute(
        "ALTER TABLE driver_shifts DROP CONSTRAINT IF EXISTS ex_driver_shifts_vehicle_overlap"
    )

    for table in (
        "drivers",
        "vehicles",
        "trailers",
        "driver_shifts",
        "logistics_requests",
        "route_plans",
        "optimization_runs",
        "slot_day_plans",
        "planning_day_closures",
    ):
        op.drop_column(table, "scenario_id")
    op.drop_column("warehouses", "scenario_id")

    for column in ("code", "route_group", "priority", "scenario_id"):
        op.drop_column("zones", column)
    op.drop_column("drivers", "preferred_route_group")
    op.drop_column("driver_shifts", "preferred_route_group")
    for column in ("date", "start_at", "end_at"):
        op.drop_column("driver_shifts", column)
    op.drop_column("slot_holds", "price_zone_code")

    op.create_unique_constraint(
        "uq_warehouses_external_warehouse", "warehouses", ["external_warehouse_id"]
    )
    op.create_check_constraint(
        "nonnegative_capacity_generation", "warehouses", "capacity_generation >= 0"
    )
    op.create_check_constraint(
        "valid_color", "zones", "color ~ '^#[0-9A-Fa-f]{6}$'"
    )
    op.create_unique_constraint(
        "uq_drivers_warehouse_external_worker",
        "drivers",
        ["warehouse_id", "external_worker_id"],
    )
    op.create_check_constraint(
        "valid_rwms_assignment",
        "drivers",
        "(rwms_assignment_mode = 'ASSIGNED_DRIVER' AND external_worker_id IS NOT NULL) OR "
        "(rwms_assignment_mode = 'WAREHOUSE_DRIVERS' AND external_worker_id IS NULL)",
    )
    op.create_unique_constraint(
        "uq_vehicles_warehouse_registration",
        "vehicles",
        ["warehouse_id", "registration_number"],
    )
    op.create_unique_constraint(
        "uq_trailers_warehouse_registration",
        "trailers",
        ["warehouse_id", "registration_number"],
    )
    op.create_unique_constraint(
        "uq_logistics_requests_external_source",
        "logistics_requests",
        ["warehouse_id", "source_system", "external_id"],
    )
    op.create_unique_constraint(
        "uq_slot_day_plans_warehouse_date", "slot_day_plans", ["warehouse_id", "date"]
    )
    op.create_unique_constraint(
        "uq_planning_day_closures_warehouse_date",
        "planning_day_closures",
        ["warehouse_id", "date"],
    )

    for table in (
        "drivers",
        "vehicles",
        "trailers",
        "driver_shifts",
        "logistics_requests",
        "optimization_runs",
    ):
        op.create_foreign_key(
            f"fk_{table}_warehouse_id_warehouses",
            table,
            "warehouses",
            ["warehouse_id"],
            ["id"],
            ondelete="CASCADE",
        )
    op.create_foreign_key(
        "fk_slot_holds_price_zone_id_zones",
        "slot_holds",
        "zones",
        ["price_zone_id"],
        ["id"],
        ondelete="SET NULL",
    )

    op.create_index("ix_drivers_warehouse_active", "drivers", ["warehouse_id", "active"])
    op.create_index("ix_vehicles_warehouse_active", "vehicles", ["warehouse_id", "active"])
    op.create_index("ix_trailers_warehouse_active", "trailers", ["warehouse_id", "active"])
    op.create_index(
        "ix_driver_shifts_warehouse_dates",
        "driver_shifts",
        ["warehouse_id", "date_from", "date_to"],
    )
    op.create_index(
        "ix_driver_shifts_driver_dates", "driver_shifts", ["driver_id", "date_from", "date_to"]
    )
    op.create_index(
        "ix_driver_shifts_vehicle_dates",
        "driver_shifts",
        ["vehicle_id", "date_from", "date_to"],
    )
    op.create_index(
        "ix_logistics_requests_warehouse_status",
        "logistics_requests",
        ["warehouse_id", "status"],
    )
    op.create_index(
        "ix_logistics_requests_warehouse_scheduled_date",
        "logistics_requests",
        ["warehouse_id", "scheduled_date"],
    )
    op.create_index("ix_route_plans_warehouse_date", "route_plans", ["warehouse_id", "date"])
    op.create_index(
        "ix_optimization_runs_warehouse_started",
        "optimization_runs",
        ["warehouse_id", "started_at"],
    )

    op.create_check_constraint(
        "positive_date_range", "driver_shifts", "date_to >= date_from"
    )
    op.create_check_constraint(
        "bounded_date_range", "driver_shifts", "date_to - date_from <= 30"
    )
    op.create_check_constraint(
        "single_month_range",
        "driver_shifts",
        "EXTRACT(YEAR FROM date_from) = EXTRACT(YEAR FROM date_to) AND "
        "EXTRACT(MONTH FROM date_from) = EXTRACT(MONTH FROM date_to)",
    )
    op.create_check_constraint(
        "positive_daily_duration", "driver_shifts", "end_time > start_time"
    )
    op.execute(
        "ALTER TABLE driver_shifts ADD CONSTRAINT ex_driver_shifts_driver_dates "
        "EXCLUDE USING gist (driver_id WITH =, daterange(date_from, date_to, '[]') WITH &&) "
        "WHERE (active)"
    )
    op.execute(
        "ALTER TABLE driver_shifts ADD CONSTRAINT ex_driver_shifts_vehicle_dates "
        "EXCLUDE USING gist (vehicle_id WITH =, daterange(date_from, date_to, '[]') WITH &&) "
        "WHERE (active)"
    )

    op.drop_table("scenarios")
    op.execute(
        "ALTER SEQUENCE scenario_capacity_generation_seq "
        "RENAME TO warehouse_capacity_generation_seq"
    )


def downgrade() -> None:
    """Reject recreation of the removed scenario aggregate and its ambiguous ownership."""

    raise RuntimeError("warehouse workspaces cannot be downgraded to scenario containers safely")
