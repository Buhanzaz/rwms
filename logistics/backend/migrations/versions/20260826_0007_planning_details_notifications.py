"""Add mandatory planning details and simulated contact notifications.

Revision ID: 20260826_0007
Revises: 20260825_0006
Create Date: 2026-08-26
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260826_0007"
down_revision: str | None = "20260825_0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add dispatcher decisions and an idempotent simulated delivery journal."""

    op.add_column(
        "drivers",
        sa.Column("passport_details", sa.Text(), server_default="", nullable=False),
    )
    op.add_column(
        "logistics_requests",
        sa.Column("trailer_access_allowed", sa.Boolean(), nullable=True),
    )
    op.add_column(
        "logistics_requests",
        sa.Column(
            "include_driver_passport_in_notification",
            sa.Boolean(),
            server_default=sa.false(),
            nullable=False,
        ),
    )
    op.add_column(
        "logistics_requests",
        sa.Column("contact_name", sa.String(length=200), server_default="", nullable=False),
    )
    op.add_column(
        "logistics_requests",
        sa.Column("contact_phone", sa.String(length=64), server_default="", nullable=False),
    )
    op.create_table(
        "plan_notification_logs",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("plan_id", sa.Uuid(), nullable=False),
        sa.Column("request_id", sa.Uuid(), nullable=False),
        sa.Column("recipient_name", sa.String(length=200), server_default="", nullable=False),
        sa.Column("recipient_contact", sa.String(length=200), server_default="", nullable=False),
        sa.Column("message", sa.Text(), nullable=False),
        sa.Column(
            "includes_driver_passport",
            sa.Boolean(),
            server_default=sa.false(),
            nullable=False,
        ),
        sa.Column(
            "status",
            sa.String(length=32),
            server_default="SIMULATED_DELIVERED",
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "status = 'SIMULATED_DELIVERED'",
            name=op.f("ck_plan_notification_logs_simulated_status"),
        ),
        sa.ForeignKeyConstraint(
            ["plan_id"],
            ["route_plans.id"],
            name="fk_plan_notification_logs_plan_id_route_plans",
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["request_id"],
            ["logistics_requests.id"],
            name="fk_plan_notification_logs_request_id_logistics_requests",
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_plan_notification_logs"),
        sa.UniqueConstraint(
            "plan_id",
            "request_id",
            name="uq_plan_notification_logs_plan_request",
        ),
    )
    op.create_index(
        "ix_plan_notification_logs_request_id",
        "plan_notification_logs",
        ["request_id"],
        unique=False,
    )


def downgrade() -> None:
    """Remove simulated notification data and dispatcher-only request fields."""

    op.drop_index(
        "ix_plan_notification_logs_request_id",
        table_name="plan_notification_logs",
    )
    op.drop_table("plan_notification_logs")
    op.drop_column("logistics_requests", "contact_phone")
    op.drop_column("logistics_requests", "contact_name")
    op.drop_column(
        "logistics_requests", "include_driver_passport_in_notification"
    )
    op.drop_column("logistics_requests", "trailer_access_allowed")
    op.drop_column("drivers", "passport_details")
