"""Track the canonical RWMS warehouse directory in the logistics projection.

Revision ID: 20260829_0019
Revises: 20260829_0018
Create Date: 2026-08-29
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260829_0019"
down_revision: str | None = "20260829_0018"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add owner version, representative and routing-readiness projection facts."""

    op.add_column(
        "warehouses",
        sa.Column(
            "external_warehouse_version",
            sa.BigInteger(),
            nullable=False,
            server_default="0",
        ),
    )
    op.create_check_constraint(
        "nonnegative_external_warehouse_version",
        "warehouses",
        "external_warehouse_version >= 0",
    )
    op.add_column(
        "warehouses",
        sa.Column(
            "representative",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
    )
    op.add_column(
        "warehouses",
        sa.Column(
            "routing_ready",
            sa.Boolean(),
            nullable=False,
            server_default=sa.true(),
        ),
    )
    op.alter_column(
        "warehouses",
        "address",
        existing_type=sa.String(length=500),
        nullable=True,
    )


def downgrade() -> None:
    """Remove directory metadata after proving every address remains populated."""

    connection = op.get_bind()
    if connection.scalar(sa.text("SELECT count(*) FROM warehouses WHERE address IS NULL")):
        raise RuntimeError("cannot restore non-null warehouse addresses while null values exist")
    op.alter_column(
        "warehouses",
        "address",
        existing_type=sa.String(length=500),
        nullable=False,
    )
    op.drop_column("warehouses", "routing_ready")
    op.drop_column("warehouses", "representative")
    op.drop_constraint(
        op.f("ck_warehouses_nonnegative_external_warehouse_version"),
        "warehouses",
        type_="check",
    )
    op.drop_column("warehouses", "external_warehouse_version")
