"""One server-owned map display configuration shared by all planner workspaces."""

from sqlalchemy import CheckConstraint, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, TimestampMixin


class MapDisplaySettings(TimestampMixin, Base):
    """Version-fenced browser map selection; this never changes truck routing."""

    __tablename__ = "map_display_settings"
    __table_args__ = (
        CheckConstraint("id = 1", name="singleton"),
        CheckConstraint("version >= 1", name="positive_version"),
        CheckConstraint("provider IN ('STANDARD', 'YANDEX')", name="supported_provider"),
        CheckConstraint(
            "provider <> 'YANDEX' OR "
            "(yandex_api_key IS NOT NULL AND length(btrim(yandex_api_key)) > 0)",
            name="yandex_requires_key",
        ),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    version: Mapped[int] = mapped_column(Integer, nullable=False)
    provider: Mapped[str] = mapped_column(String(16), nullable=False)
    yandex_api_key: Mapped[str | None] = mapped_column(String(256))
