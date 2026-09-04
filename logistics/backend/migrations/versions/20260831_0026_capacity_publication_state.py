"""Persist retryable anonymous-capacity publication state.

Revision ID: 20260831_0026
Revises: 20260831_0025
Create Date: 2026-08-31
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260831_0026"
down_revision: str | None = "20260831_0025"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add a rebuildable, leased publication cursor to every warehouse."""

    op.add_column(
        "warehouses",
        sa.Column(
            "capacity_published_generation",
            sa.BigInteger(),
            nullable=False,
            server_default="0",
        ),
    )
    op.add_column(
        "warehouses",
        sa.Column(
            "capacity_publish_status",
            sa.String(length=32),
            nullable=False,
            server_default="NOT_REQUESTED",
        ),
    )
    op.add_column(
        "warehouses",
        sa.Column(
            "capacity_publish_attempts",
            sa.Integer(),
            nullable=False,
            server_default="0",
        ),
    )
    op.add_column(
        "warehouses",
        sa.Column("capacity_publish_error_code", sa.String(length=128), nullable=True),
    )
    op.add_column(
        "warehouses",
        sa.Column("capacity_publish_next_attempt_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "warehouses",
        sa.Column("capacity_publish_lease_until", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_check_constraint(
        "nonnegative_capacity_published_generation",
        "warehouses",
        "capacity_published_generation >= 0",
    )
    op.create_check_constraint(
        "published_capacity_not_ahead",
        "warehouses",
        "capacity_published_generation <= capacity_generation",
    )
    op.create_check_constraint(
        "valid_capacity_publish_status",
        "warehouses",
        "capacity_publish_status IN ('NOT_REQUESTED', 'PENDING', 'PUBLISHED', 'FAILED')",
    )
    op.create_check_constraint(
        "nonnegative_capacity_attempts",
        "warehouses",
        "capacity_publish_attempts >= 0",
    )
    op.create_index(
        "ix_warehouses_capacity_publish_due",
        "warehouses",
        ["capacity_publish_status", "capacity_publish_next_attempt_at"],
        postgresql_where=sa.text(
            "capacity_publish_status IN ('PENDING', 'FAILED') "
            "AND capacity_generation > capacity_published_generation"
        ),
    )


def downgrade() -> None:
    """Remove publication recovery metadata without changing warehouse capacity facts."""

    op.drop_index("ix_warehouses_capacity_publish_due", table_name="warehouses")
    op.drop_constraint(
        op.f("ck_warehouses_nonnegative_capacity_attempts"),
        "warehouses",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_warehouses_valid_capacity_publish_status"),
        "warehouses",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_warehouses_published_capacity_not_ahead"),
        "warehouses",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_warehouses_nonnegative_capacity_published_generation"),
        "warehouses",
        type_="check",
    )
    op.drop_column("warehouses", "capacity_publish_lease_until")
    op.drop_column("warehouses", "capacity_publish_next_attempt_at")
    op.drop_column("warehouses", "capacity_publish_error_code")
    op.drop_column("warehouses", "capacity_publish_attempts")
    op.drop_column("warehouses", "capacity_publish_status")
    op.drop_column("warehouses", "capacity_published_generation")
