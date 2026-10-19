"""Enforce one active route-plan revision per warehouse-local date.

Revision ID: 20260831_0024
Revises: 20260831_0023
Create Date: 2026-08-31
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260831_0024"
down_revision: str | None = "20260831_0023"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Archive safe duplicate heads, add revision lineage, and fence future writes."""

    op.add_column(
        "route_plans",
        sa.Column("supersedes_plan_id", sa.Uuid(), nullable=True),
    )
    op.create_foreign_key(
        "fk_route_plans_supersedes_plan_id",
        "route_plans",
        "route_plans",
        ["supersedes_plan_id"],
        ["id"],
        ondelete="SET NULL",
    )
    op.create_index(
        "ix_route_plans_supersedes_plan_id",
        "route_plans",
        ["supersedes_plan_id"],
    )
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                FROM route_plans
                WHERE status = 'CONFIRMED'
                GROUP BY warehouse_id, date
                HAVING count(*) > 1
            ) THEN
                RAISE EXCEPTION
                    'Multiple confirmed route plans exist for one warehouse date';
            END IF;
        END
        $$
        """
    )
    op.execute(
        """
        WITH ranked AS (
            SELECT id,
                   row_number() OVER (
                       PARTITION BY warehouse_id, date
                       ORDER BY (status = 'CONFIRMED') DESC, updated_at DESC, id DESC
                   ) AS head_rank
            FROM route_plans
            WHERE status <> 'ARCHIVED'
        )
        UPDATE route_plans AS plan
        SET status = 'ARCHIVED', version = plan.version + 1
        FROM ranked
        WHERE plan.id = ranked.id
          AND ranked.head_rank > 1
        """
    )
    op.create_index(
        "uq_route_plans_active_warehouse_date",
        "route_plans",
        ["warehouse_id", "date"],
        unique=True,
        postgresql_where=sa.text("status <> 'ARCHIVED'"),
    )


def downgrade() -> None:
    """Remove the active-head fence and revision lineage without restoring old ambiguity."""

    op.drop_index("uq_route_plans_active_warehouse_date", table_name="route_plans")
    op.drop_index("ix_route_plans_supersedes_plan_id", table_name="route_plans")
    op.drop_constraint(
        "fk_route_plans_supersedes_plan_id",
        "route_plans",
        type_="foreignkey",
    )
    op.drop_column("route_plans", "supersedes_plan_id")
