"""Persistence aggregate for exceptional warehouse pricing and access polygons."""

from __future__ import annotations

from enum import StrEnum
from typing import Any
from uuid import UUID

from geoalchemy2 import Geometry
from sqlalchemy import BigInteger, CheckConstraint, ForeignKey, Index, Integer, String, Uuid
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, TimestampMixin, UuidPrimaryKeyMixin


class PolicyZoneKind(StrEnum):
    """Supported exceptional policies layered over ordinary isochrone coverage."""

    SPECIAL_PRICE = "SPECIAL_PRICE"
    FORBIDDEN = "FORBIDDEN"
    NO_TRAILER = "NO_TRAILER"


class WarehousePolicyZone(UuidPrimaryKeyMixin, TimestampMixin, Base):
    """One versioned warehouse polygon that changes price or route feasibility only."""

    __tablename__ = "warehouse_policy_zones"
    __table_args__ = (
        CheckConstraint("length(btrim(name)) > 0", name="nonempty_name"),
        CheckConstraint(
            "kind IN ('SPECIAL_PRICE', 'FORBIDDEN', 'NO_TRAILER')",
            name="supported_kind",
        ),
        CheckConstraint("color ~ '^#[0-9A-Fa-f]{6}$'", name="valid_color"),
        CheckConstraint("version >= 1", name="positive_version"),
        CheckConstraint(
            "(kind = 'SPECIAL_PRICE' "
            "AND delivery_price_rubles IS NOT NULL "
            "AND pickup_price_rubles IS NOT NULL "
            "AND delivery_price_rubles >= 0 "
            "AND pickup_price_rubles >= 0) OR "
            "(kind IN ('FORBIDDEN', 'NO_TRAILER') "
            "AND delivery_price_rubles IS NULL "
            "AND pickup_price_rubles IS NULL)",
            name="kind_values",
        ),
        Index("ix_warehouse_policy_zones_warehouse_id", "warehouse_id"),
        Index(
            "ix_warehouse_policy_zones_geometry_gist",
            "geometry",
            postgresql_using="gist",
        ),
    )

    warehouse_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("warehouses.id", ondelete="CASCADE"),
        nullable=False,
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    kind: Mapped[str] = mapped_column(String(32), nullable=False)
    color: Mapped[str] = mapped_column(String(7), nullable=False)
    geometry: Mapped[Any] = mapped_column(
        Geometry("MULTIPOLYGON", srid=4326, spatial_index=False),
        nullable=False,
    )
    version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    delivery_price_rubles: Mapped[int | None] = mapped_column(BigInteger)
    pickup_price_rubles: Mapped[int | None] = mapped_column(BigInteger)
