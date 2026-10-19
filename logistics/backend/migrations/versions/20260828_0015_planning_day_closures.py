"""Persist one-way closure of customer demand for a depot planning date.

Revision ID: 20260828_0015
Revises: 20260827_0014
Create Date: 2026-08-28
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260828_0015"
down_revision: str | None = "20260827_0014"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Create the irreversible per-warehouse planning-date closure record."""

    op.create_table(
        "planning_day_closures",
        sa.Column("scenario_id", sa.Uuid(), nullable=False),
        sa.Column("warehouse_id", sa.Uuid(), nullable=False),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column("closed_by", sa.String(length=100), nullable=False),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["scenario_id"],
            ["scenarios.id"],
            name=op.f("fk_planning_day_closures_scenario_id_scenarios"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["warehouse_id"],
            ["warehouses.id"],
            name=op.f("fk_planning_day_closures_warehouse_id_warehouses"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_planning_day_closures")),
        sa.UniqueConstraint(
            "scenario_id",
            "warehouse_id",
            "date",
            name=op.f("uq_planning_day_closures_scenario_warehouse_date"),
        ),
    )
    op.create_index(
        "ix_planning_day_closures_warehouse_date",
        "planning_day_closures",
        ["warehouse_id", "date"],
        unique=False,
    )


def downgrade() -> None:
    """Remove only the planning-date closure state."""

    op.drop_index(
        "ix_planning_day_closures_warehouse_date",
        table_name="planning_day_closures",
    )
    op.drop_table("planning_day_closures")
