"""Assign every tariff zone to exactly one warehouse workspace.

Revision ID: 20260828_0017
Revises: 20260828_0016
Create Date: 2026-08-28
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260828_0017"
down_revision: str | None = "20260828_0016"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Backfill nearest unambiguous owners, validate references, and enforce ownership."""

    op.add_column("zones", sa.Column("warehouse_id", sa.Uuid(), nullable=True))
    op.execute(
        """
        DO $$
        BEGIN
          IF EXISTS (SELECT 1 FROM zones)
             AND NOT EXISTS (SELECT 1 FROM warehouses) THEN
            RAISE EXCEPTION
              'warehouse-owned zone migration found zones but no warehouses';
          END IF;
        END $$
        """
    )
    op.execute(
        """
        CREATE TEMP TABLE zone_warehouse_assignment ON COMMIT DROP AS
        WITH distances AS (
          SELECT zone.id AS zone_id,
                 warehouse.id AS warehouse_id,
                 ST_Distance(
                   ST_PointOnSurface(zone.geometry)::geography,
                   ST_SetSRID(
                     ST_Point(warehouse.longitude, warehouse.latitude),
                     4326
                   )::geography
                 ) AS distance_m
          FROM zones zone
          CROSS JOIN warehouses warehouse
        ), ranked AS (
          SELECT distances.*,
                 row_number() OVER (
                   PARTITION BY zone_id
                   ORDER BY distance_m, warehouse_id
                 ) AS distance_rank,
                 lead(distance_m) OVER (
                   PARTITION BY zone_id
                   ORDER BY distance_m, warehouse_id
                 ) AS next_distance_m
          FROM distances
        )
        SELECT zone_id, warehouse_id, distance_m, next_distance_m
        FROM ranked
        WHERE distance_rank = 1
        """
    )
    op.execute(
        """
        DO $$
        BEGIN
          IF EXISTS (
            SELECT 1
            FROM zone_warehouse_assignment
            WHERE distance_m IS NULL
          ) THEN
            RAISE EXCEPTION
              'warehouse-owned zone migration found a zone without a surface point';
          END IF;
          IF EXISTS (
            SELECT 1
            FROM zone_warehouse_assignment
            WHERE next_distance_m IS NOT NULL
              AND next_distance_m - distance_m <= 1.0
          ) THEN
            RAISE EXCEPTION
              'warehouse-owned zone migration found a nearest-warehouse tie within one metre';
          END IF;
          IF (SELECT count(*) FROM zone_warehouse_assignment)
             <> (SELECT count(*) FROM zones) THEN
            RAISE EXCEPTION
              'warehouse-owned zone migration could not assign every zone';
          END IF;
        END $$
        """
    )
    op.execute(
        """
        UPDATE zones zone
        SET warehouse_id = assignment.warehouse_id
        FROM zone_warehouse_assignment assignment
        WHERE assignment.zone_id = zone.id
        """
    )
    op.execute(
        """
        DO $$
        BEGIN
          IF EXISTS (SELECT 1 FROM zones WHERE warehouse_id IS NULL) THEN
            RAISE EXCEPTION
              'warehouse-owned zone migration left a zone without an owner';
          END IF;
          IF EXISTS (
            SELECT 1
            FROM logistics_requests request
            JOIN zones zone ON zone.id = request.zone_id
            WHERE request.warehouse_id <> zone.warehouse_id
          ) THEN
            RAISE EXCEPTION
              'warehouse-owned zone migration found a cross-warehouse request classification';
          END IF;
          IF EXISTS (
            SELECT 1
            FROM planning_tasks task
            JOIN logistics_requests request ON request.id = task.request_id
            JOIN zones zone ON zone.id = task.zone_id
            WHERE request.warehouse_id <> zone.warehouse_id
          ) THEN
            RAISE EXCEPTION
              'warehouse-owned zone migration found a cross-warehouse task classification';
          END IF;
          IF EXISTS (
            SELECT 1
            FROM slot_holds hold
            JOIN slot_day_plans day_plan ON day_plan.id = hold.day_plan_id
            JOIN zones zone ON zone.id = hold.price_zone_id
            WHERE day_plan.warehouse_id <> zone.warehouse_id
          ) THEN
            RAISE EXCEPTION
              'warehouse-owned zone migration found a cross-warehouse slot hold';
          END IF;
          IF EXISTS (
            SELECT 1
            FROM warehouses warehouse
            WHERE NOT EXISTS (
              SELECT 1
              FROM zones zone
              WHERE zone.warehouse_id = warehouse.id
                AND ST_Covers(
                  zone.geometry,
                  ST_SetSRID(
                    ST_Point(warehouse.longitude, warehouse.latitude),
                    4326
                  )
                )
            )
          ) THEN
            RAISE EXCEPTION
              'warehouse-owned zone migration left a warehouse outside all assigned zones';
          END IF;
        END $$
        """
    )
    op.alter_column("zones", "warehouse_id", nullable=False)
    op.create_foreign_key(
        "fk_zones_warehouse_id_warehouses",
        "zones",
        "warehouses",
        ["warehouse_id"],
        ["id"],
        ondelete="CASCADE",
    )
    op.create_index("ix_zones_warehouse_id", "zones", ["warehouse_id"])


def downgrade() -> None:
    """Return to global zones without deleting or rewriting any persisted row."""

    op.drop_index("ix_zones_warehouse_id", table_name="zones")
    op.drop_constraint(
        "fk_zones_warehouse_id_warehouses",
        "zones",
        type_="foreignkey",
    )
    op.drop_column("zones", "warehouse_id")
