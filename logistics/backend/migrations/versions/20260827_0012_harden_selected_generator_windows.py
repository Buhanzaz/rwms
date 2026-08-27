"""Normalize existing generator-owned selected windows for capacity publication.

Revision ID: 20260827_0012
Revises: 20260827_0011
Create Date: 2026-08-27
"""

from collections.abc import Sequence

from alembic import op

revision: str = "20260827_0012"
down_revision: str | None = "20260827_0011"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Harden selected generated deliveries and make generated pickups all-day backhaul."""

    op.execute(
        """
        UPDATE request_date_options AS option
        SET is_hard = true
        FROM logistics_requests AS request
        WHERE option.request_id = request.id
          AND option.date = request.scheduled_date
          AND request.source_system = 'SIMULATOR_GENERATOR'
          AND request.type = 'DELIVERY'
          AND option.window_start IS NOT NULL
          AND option.window_end IS NOT NULL
          AND option.window_start < option.window_end
          AND option.is_hard = false
        """
    )
    op.execute(
        """
        UPDATE request_date_options AS option
        SET window_start = TIME '09:00:00',
            window_end = TIME '18:00:00',
            is_hard = true
        FROM logistics_requests AS request
        WHERE option.request_id = request.id
          AND option.date = request.scheduled_date
          AND request.source_system = 'SIMULATOR_GENERATOR'
          AND request.type = 'PICKUP'
          AND (
              option.window_start IS DISTINCT FROM TIME '09:00:00'
              OR option.window_end IS DISTINCT FROM TIME '18:00:00'
              OR option.is_hard = false
          )
        """
    )


def downgrade() -> None:
    """Retain normalized business facts because their prior hard intent is not recoverable."""

    pass
