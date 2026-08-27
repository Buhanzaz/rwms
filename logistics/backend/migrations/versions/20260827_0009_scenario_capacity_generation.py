"""Version deterministic generated-delivery capacity publications.

Revision ID: 20260827_0009
Revises: 20260826_0008
Create Date: 2026-08-27
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260827_0009"
down_revision: str | None = "20260826_0008"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add a monotonic workload generation to every existing scenario."""

    op.add_column(
        "scenarios",
        sa.Column(
            "capacity_generation",
            sa.BigInteger(),
            server_default=sa.text("0"),
            nullable=False,
        ),
    )
    op.create_check_constraint(
        "nonnegative_capacity_generation",
        "scenarios",
        "capacity_generation >= 0",
    )


def downgrade() -> None:
    """Remove only the simulator publication generation metadata."""

    op.drop_constraint(
        op.f("ck_scenarios_nonnegative_capacity_generation"),
        "scenarios",
        type_="check",
    )
    op.drop_column("scenarios", "capacity_generation")
