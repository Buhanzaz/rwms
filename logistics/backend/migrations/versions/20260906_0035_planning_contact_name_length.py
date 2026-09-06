"""Widen the persisted planning contact name to the canonical RWMS limit.

Revision ID: 20260906_0035
Revises: 20260902_0034
Create Date: 2026-09-06
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260906_0035"
down_revision: str | None = "20260902_0034"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Allow the canonical 512-character contact snapshot without changing data."""

    op.alter_column(
        "logistics_requests",
        "contact_name",
        existing_type=sa.String(length=200),
        type_=sa.String(length=512),
        existing_nullable=False,
    )


def downgrade() -> None:
    """Refuse narrowing while any persisted contact would be lost."""

    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                FROM logistics_requests
                WHERE char_length(contact_name) > 200
            ) THEN
                RAISE EXCEPTION
                    'Cannot downgrade: logistics_requests.contact_name exceeds 200 characters';
            END IF;
        END
        $$
        """
    )
    op.alter_column(
        "logistics_requests",
        "contact_name",
        existing_type=sa.String(length=512),
        type_=sa.String(length=200),
        existing_nullable=False,
    )
