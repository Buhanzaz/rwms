"""Add stable RWMS identities and source metadata for planning synchronization.

Revision ID: 20260823_0002
Revises: 20260822_0001
Create Date: 2026-08-23
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260823_0002"
down_revision: str | None = "20260822_0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add nullable external mappings without changing existing simulator records."""

    op.add_column("warehouses", sa.Column("external_warehouse_id", sa.Uuid(), nullable=True))
    op.create_unique_constraint(
        "uq_warehouses_scenario_external_warehouse",
        "warehouses",
        ["scenario_id", "external_warehouse_id"],
    )

    op.add_column("drivers", sa.Column("external_worker_id", sa.Uuid(), nullable=True))
    op.create_unique_constraint(
        "uq_drivers_scenario_external_worker",
        "drivers",
        ["scenario_id", "external_worker_id"],
    )

    op.add_column("logistics_requests", sa.Column("source_system", sa.String(64), nullable=True))
    op.add_column("logistics_requests", sa.Column("external_id", sa.Uuid(), nullable=True))
    op.add_column("logistics_requests", sa.Column("external_version", sa.Integer(), nullable=True))
    op.add_column(
        "logistics_requests",
        sa.Column(
            "external_payload",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=True,
        ),
    )
    op.create_check_constraint(
        "nonnegative_external_version",
        "logistics_requests",
        "external_version IS NULL OR external_version >= 0",
    )
    op.create_unique_constraint(
        "uq_logistics_requests_external_source",
        "logistics_requests",
        ["scenario_id", "source_system", "external_id"],
    )


def downgrade() -> None:
    """Remove only the RWMS synchronization metadata introduced here."""

    op.drop_constraint(
        "uq_logistics_requests_external_source", "logistics_requests", type_="unique"
    )
    op.drop_constraint(
        op.f("ck_logistics_requests_nonnegative_external_version"),
        "logistics_requests",
        type_="check",
    )
    op.drop_column("logistics_requests", "external_payload")
    op.drop_column("logistics_requests", "external_version")
    op.drop_column("logistics_requests", "external_id")
    op.drop_column("logistics_requests", "source_system")

    op.drop_constraint("uq_drivers_scenario_external_worker", "drivers", type_="unique")
    op.drop_column("drivers", "external_worker_id")

    op.drop_constraint("uq_warehouses_scenario_external_warehouse", "warehouses", type_="unique")
    op.drop_column("warehouses", "external_warehouse_id")
