"""Persist customer delivery purpose independently from physical request direction.

Revision ID: 20260902_0034
Revises: 20260901_0033
Create Date: 2026-09-02
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260902_0034"
down_revision: str | None = "20260901_0033"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Backfill the only historically supported customer delivery purpose."""

    op.add_column(
        "logistics_requests",
        sa.Column("customer_delivery_purpose", sa.String(length=32), nullable=True),
    )
    op.execute(
        """
        UPDATE logistics_requests
        SET customer_delivery_purpose = 'RENTAL_DELIVERY',
            external_payload = jsonb_set(
                external_payload,
                '{customerDeliveryPurpose}',
                '"RENTAL_DELIVERY"'::jsonb,
                true
            )
        WHERE source_system = 'RWMS'
          AND type = 'DELIVERY'
          AND external_payload IS NOT NULL
        """
    )
    op.create_check_constraint(
        op.f("ck_logistics_requests_valid_customer_delivery_purpose"),
        "logistics_requests",
        sa.text(
            "customer_delivery_purpose IS NULL OR ("
            "type = 'DELIVERY' AND customer_delivery_purpose IN ("
            "'RENTAL_DELIVERY', 'SALE_DELIVERY', 'CUSTOMER_RELOCATION'))"
        ),
    )
    op.create_check_constraint(
        op.f("ck_logistics_requests_rwms_delivery_purpose_matches_source"),
        "logistics_requests",
        sa.text(
            "source_system IS DISTINCT FROM 'RWMS' OR type <> 'DELIVERY' OR ("
            "customer_delivery_purpose IS NOT NULL AND external_payload IS NOT NULL AND "
            "external_payload ->> 'customerDeliveryPurpose' = customer_delivery_purpose)"
        ),
    )


def downgrade() -> None:
    """Remove only the additive purpose projection."""

    op.drop_constraint(
        op.f("ck_logistics_requests_rwms_delivery_purpose_matches_source"),
        "logistics_requests",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_logistics_requests_valid_customer_delivery_purpose"),
        "logistics_requests",
        type_="check",
    )
    op.drop_column("logistics_requests", "customer_delivery_purpose")
