"""Persist the global map provider without changing existing warehouse settings.

Revision ID: 20260907_0037
Revises: 20260906_0036
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260907_0037"
down_revision: str | None = "20260906_0036"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Keep the current standard map as the initial choice and never seed credentials."""

    op.create_table(
        "map_display_settings",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("provider", sa.String(16), nullable=False),
        sa.Column("yandex_api_key", sa.String(256), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint("id = 1", name=op.f("ck_map_display_settings_singleton")),
        sa.CheckConstraint("version >= 1", name=op.f("ck_map_display_settings_positive_version")),
        sa.CheckConstraint(
            "provider IN ('STANDARD', 'YANDEX')",
            name=op.f("ck_map_display_settings_supported_provider"),
        ),
        sa.CheckConstraint(
            "provider <> 'YANDEX' OR "
            "(yandex_api_key IS NOT NULL AND length(btrim(yandex_api_key)) > 0)",
            name=op.f("ck_map_display_settings_yandex_requires_key"),
        ),
    )
    op.execute("INSERT INTO map_display_settings (id, version, provider) VALUES (1, 1, 'STANDARD')")


def downgrade() -> None:
    """Remove only the presentation configuration introduced by this revision."""

    op.drop_table("map_display_settings")
