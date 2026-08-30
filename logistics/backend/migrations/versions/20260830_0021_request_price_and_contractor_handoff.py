"""Persist calculated delivery prices and explicit contractor handoffs.

Revision ID: 20260830_0021
Revises: 20260830_0020
Create Date: 2026-08-30
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260830_0021"
down_revision: str | None = "20260830_0020"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add nullable source-owned pricing and all-or-none contractor assignment facts."""

    op.add_column(
        "logistics_requests",
        sa.Column("delivery_price_rubles", sa.BigInteger(), nullable=True),
    )
    op.add_column(
        "logistics_requests",
        sa.Column("price_isochrone_minutes", sa.Integer(), nullable=True),
    )
    op.add_column(
        "logistics_requests",
        sa.Column("assignment_type", sa.String(length=32), nullable=True),
    )
    op.add_column(
        "logistics_requests",
        sa.Column("assigned_contractor_worker_id", sa.Uuid(), nullable=True),
    )
    op.add_column(
        "logistics_requests",
        sa.Column("assigned_contractor_name", sa.String(length=200), nullable=True),
    )
    op.add_column(
        "logistics_requests",
        sa.Column("assigned_contractor_phone", sa.String(length=64), nullable=True),
    )
    op.add_column(
        "logistics_requests",
        sa.Column("assigned_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "logistics_requests",
        sa.Column("assigned_by", sa.String(length=200), nullable=True),
    )
    op.create_check_constraint(
        "nonnegative_delivery_price",
        "logistics_requests",
        "delivery_price_rubles IS NULL OR delivery_price_rubles >= 0",
    )
    op.create_check_constraint(
        "supported_price_isochrone",
        "logistics_requests",
        "price_isochrone_minutes IS NULL OR ("
        "price_isochrone_minutes BETWEEN 60 AND 720 AND "
        "price_isochrone_minutes % 60 = 0)",
    )
    op.create_check_constraint(
        "complete_delivery_price",
        "logistics_requests",
        "(delivery_price_rubles IS NULL) = (price_isochrone_minutes IS NULL)",
    )
    op.create_check_constraint(
        "valid_contractor_assignment",
        "logistics_requests",
        "(assignment_type IS NULL AND assigned_contractor_worker_id IS NULL AND "
        "assigned_contractor_name IS NULL AND assigned_contractor_phone IS NULL AND "
        "assigned_at IS NULL AND assigned_by IS NULL) OR "
        "(assignment_type = 'CONTRACTOR_HANDOFF' AND "
        "assigned_contractor_worker_id IS NOT NULL AND "
        "assigned_contractor_name IS NOT NULL AND assigned_at IS NOT NULL AND "
        "assigned_by IS NOT NULL)",
    )
    op.execute(
        """
        UPDATE logistics_requests
        SET external_payload = external_payload
            || jsonb_build_object(
                'deliveryPriceRubles', NULL,
                'priceIsochroneMinutes', NULL
            )
        WHERE source_system = 'RWMS'
          AND external_payload IS NOT NULL
          AND (
              NOT external_payload ? 'deliveryPriceRubles'
              OR NOT external_payload ? 'priceIsochroneMinutes'
          )
        """
    )


def downgrade() -> None:
    """Remove contractor handoff and calculated-price request facts."""

    op.execute(
        """
        UPDATE logistics_requests
        SET external_payload = external_payload
            - 'deliveryPriceRubles'
            - 'priceIsochroneMinutes'
        WHERE source_system = 'RWMS'
          AND external_payload IS NOT NULL
        """
    )
    op.drop_constraint(
        op.f("ck_logistics_requests_valid_contractor_assignment"),
        "logistics_requests",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_logistics_requests_complete_delivery_price"),
        "logistics_requests",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_logistics_requests_supported_price_isochrone"),
        "logistics_requests",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_logistics_requests_nonnegative_delivery_price"),
        "logistics_requests",
        type_="check",
    )
    op.drop_column("logistics_requests", "assigned_by")
    op.drop_column("logistics_requests", "assigned_at")
    op.drop_column("logistics_requests", "assigned_contractor_phone")
    op.drop_column("logistics_requests", "assigned_contractor_name")
    op.drop_column("logistics_requests", "assigned_contractor_worker_id")
    op.drop_column("logistics_requests", "assignment_type")
    op.drop_column("logistics_requests", "price_isochrone_minutes")
    op.drop_column("logistics_requests", "delivery_price_rubles")
