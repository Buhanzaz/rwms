"""Fence catalog retries, lost updates, shift overlaps, and overnight intervals.

Revision ID: 20260831_0027
Revises: 20260831_0026
Create Date: 2026-08-31
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260831_0027"
down_revision: str | None = "20260831_0026"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_VERSIONED_TABLES = (
    "warehouses",
    "drivers",
    "vehicles",
    "trailers",
    "driver_shifts",
    "logistics_requests",
)
_OVERNIGHT_DURATION_SECONDS = (
    "CASE WHEN end_time > start_time "
    "THEN EXTRACT(EPOCH FROM (end_time - start_time)) "
    "WHEN end_time < start_time "
    "THEN 86400 + EXTRACT(EPOCH FROM (end_time - start_time)) ELSE 0 END"
)


def upgrade() -> None:
    """Add durable command receipts and database constraints matching service fences."""

    op.create_table(
        "catalog_command_receipts",
        sa.Column("operation", sa.String(length=64), nullable=False),
        sa.Column("actor_id", sa.Uuid(), nullable=False),
        sa.Column("idempotency_key", sa.String(length=200), nullable=False),
        sa.Column("request_hash", sa.String(length=64), nullable=False),
        sa.Column("resource_type", sa.String(length=64), nullable=True),
        sa.Column("resource_id", sa.Uuid(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.CheckConstraint(
            "(resource_type IS NULL) = (resource_id IS NULL)",
            name=op.f("ck_catalog_command_receipts_complete_resource_pointer"),
        ),
        sa.CheckConstraint(
            "length(idempotency_key) > 0",
            name=op.f("ck_catalog_command_receipts_nonempty_idempotency_key"),
        ),
        sa.CheckConstraint(
            "length(request_hash) = 64",
            name=op.f("ck_catalog_command_receipts_valid_request_hash"),
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_catalog_command_receipts")),
        sa.UniqueConstraint(
            "operation",
            "actor_id",
            "idempotency_key",
            name="uq_catalog_command_receipts_operation_actor_key",
        ),
    )
    op.create_index(
        "ix_catalog_command_receipts_resource",
        "catalog_command_receipts",
        ["resource_type", "resource_id"],
        unique=False,
    )

    for table_name in _VERSIONED_TABLES:
        op.add_column(
            table_name,
            sa.Column("version", sa.Integer(), server_default="1", nullable=False),
        )
        op.create_check_constraint("positive_version", table_name, "version >= 1")

    op.drop_constraint(
        op.f("ck_driver_shifts_break_shorter_than_duration"),
        "driver_shifts",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_driver_shifts_positive_daily_duration"),
        "driver_shifts",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_driver_shifts_single_month_range"),
        "driver_shifts",
        type_="check",
    )
    op.create_check_constraint(
        "nonzero_daily_duration",
        "driver_shifts",
        "end_time <> start_time",
    )
    op.create_check_constraint(
        "break_shorter_than_duration",
        "driver_shifts",
        f"break_minutes * 60 < {_OVERNIGHT_DURATION_SECONDS}",
    )


def downgrade() -> None:
    """Restore the former same-month daytime shift model and remove catalog fences."""

    op.drop_constraint(
        op.f("ck_driver_shifts_break_shorter_than_duration"),
        "driver_shifts",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_driver_shifts_nonzero_daily_duration"),
        "driver_shifts",
        type_="check",
    )
    op.create_check_constraint(
        "single_month_range",
        "driver_shifts",
        "date_trunc('month', date_from) = date_trunc('month', date_to)",
    )
    op.create_check_constraint(
        "positive_daily_duration",
        "driver_shifts",
        "end_time > start_time",
    )
    op.create_check_constraint(
        "break_shorter_than_duration",
        "driver_shifts",
        "break_minutes * 60 < EXTRACT(EPOCH FROM (end_time - start_time))",
    )

    for table_name in reversed(_VERSIONED_TABLES):
        op.drop_constraint(
            op.f(f"ck_{table_name}_positive_version"),
            table_name,
            type_="check",
        )
        op.drop_column(table_name, "version")

    op.drop_index(
        "ix_catalog_command_receipts_resource",
        table_name="catalog_command_receipts",
    )
    op.drop_table("catalog_command_receipts")
