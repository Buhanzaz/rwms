"""Add version-fenced expiring customer slot holds.

Revision ID: 20260827_0013
Revises: 20260827_0012
Create Date: 2026-08-27
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260827_0013"
down_revision: str | None = "20260827_0012"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Create the day-version fence and expiring hold records."""

    op.create_table(
        "slot_day_plans",
        sa.Column("scenario_id", sa.Uuid(), nullable=False),
        sa.Column("warehouse_id", sa.Uuid(), nullable=False),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column("version", sa.BigInteger(), nullable=False),
        sa.Column("source_revision", sa.String(length=64), nullable=False),
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
        sa.CheckConstraint("version >= 1", name=op.f("ck_slot_day_plans_positive_version")),
        sa.CheckConstraint(
            "length(source_revision) = 64",
            name=op.f("ck_slot_day_plans_valid_source_revision"),
        ),
        sa.ForeignKeyConstraint(
            ["scenario_id"],
            ["scenarios.id"],
            name=op.f("fk_slot_day_plans_scenario_id_scenarios"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["warehouse_id"],
            ["warehouses.id"],
            name=op.f("fk_slot_day_plans_warehouse_id_warehouses"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_slot_day_plans")),
        sa.UniqueConstraint(
            "scenario_id",
            "warehouse_id",
            "date",
            name=op.f("uq_slot_day_plans_scenario_warehouse_date"),
        ),
    )
    op.create_index(
        "ix_slot_day_plans_warehouse_date",
        "slot_day_plans",
        ["warehouse_id", "date"],
        unique=False,
    )
    op.create_table(
        "slot_holds",
        sa.Column("day_plan_id", sa.Uuid(), nullable=False),
        sa.Column("plan_version", sa.BigInteger(), nullable=False),
        sa.Column("source_revision", sa.String(length=64), nullable=False),
        sa.Column("client_session_id", sa.String(length=200), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("request_snapshot", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("candidate_snapshot", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("delivery_price_rubles", sa.Integer(), nullable=True),
        sa.Column("price_zone_code", sa.String(length=64), nullable=True),
        sa.Column("confirmation_key", sa.Uuid(), nullable=True),
        sa.Column("confirmed_request_id", sa.Uuid(), nullable=True),
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
        sa.CheckConstraint(
            "expires_at > created_at", name=op.f("ck_slot_holds_positive_expiration")
        ),
        sa.CheckConstraint(
            "plan_version >= 1", name=op.f("ck_slot_holds_positive_plan_version")
        ),
        sa.CheckConstraint(
            "length(source_revision) = 64",
            name=op.f("ck_slot_holds_valid_source_revision"),
        ),
        sa.ForeignKeyConstraint(
            ["confirmed_request_id"],
            ["logistics_requests.id"],
            name=op.f("fk_slot_holds_confirmed_request_id_logistics_requests"),
            ondelete="SET NULL",
        ),
        sa.ForeignKeyConstraint(
            ["day_plan_id"],
            ["slot_day_plans.id"],
            name=op.f("fk_slot_holds_day_plan_id_slot_day_plans"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_slot_holds")),
        sa.UniqueConstraint("confirmation_key", name=op.f("uq_slot_holds_confirmation_key")),
    )
    op.create_index(
        "ix_slot_holds_day_status_expiration",
        "slot_holds",
        ["day_plan_id", "status", "expires_at"],
        unique=False,
    )


def downgrade() -> None:
    """Remove only dynamic-slot hold state and its date version fence."""

    op.drop_index("ix_slot_holds_day_status_expiration", table_name="slot_holds")
    op.drop_table("slot_holds")
    op.drop_index("ix_slot_day_plans_warehouse_date", table_name="slot_day_plans")
    op.drop_table("slot_day_plans")
