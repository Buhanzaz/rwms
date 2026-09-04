"""PostGIS classification for exceptional warehouse policy polygons."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from math import isfinite
from uuid import UUID

from geoalchemy2 import functions as geofunc
from geoalchemy2.elements import WKBElement
from geoalchemy2.shape import from_shape
from shapely.geometry import MultiPolygon, Point
from sqlalchemy import Float, Select, Uuid, column, func, select, values
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.policy_zone import PolicyZoneKind, WarehousePolicyZone
from app.schemas.policy_zones import GeoJsonMultiPolygon


@dataclass(frozen=True, slots=True)
class PolicyZoneClassification:
    """Most specific covering polygon for each independent exceptional policy."""

    forbidden: WarehousePolicyZone | None = None
    no_trailer: WarehousePolicyZone | None = None
    special_price: WarehousePolicyZone | None = None


@dataclass(frozen=True, slots=True)
class InMemoryPolicyZone:
    """Pure geometry representation used to test boundary and precedence semantics."""

    id: UUID
    kind: PolicyZoneKind
    geometry: MultiPolygon


def geometry_from_geojson(geometry: GeoJsonMultiPolygon) -> WKBElement:
    """Convert validated WGS84 JSON into an extended PostGIS MultiPolygon value."""

    return from_shape(geometry.to_shapely(), srid=4326, extended=True)


def build_policy_classification_statement(
    warehouse_id: UUID,
    latitude: float,
    longitude: float,
) -> Select[tuple[WarehousePolicyZone]]:
    """Select covering owner policies by WGS84 area and stable UUID tie-break."""

    _validate_point(latitude, longitude)
    point = geofunc.ST_SetSRID(geofunc.ST_Point(longitude, latitude), 4326)
    geometry_area = func.ST_Area(WarehousePolicyZone.geometry)
    return (
        select(WarehousePolicyZone)
        .where(
            WarehousePolicyZone.warehouse_id == warehouse_id,
            geofunc.ST_Covers(WarehousePolicyZone.geometry, point),
        )
        .order_by(geometry_area.asc(), WarehousePolicyZone.id.asc())
    )


async def classify_policy_point(
    session: AsyncSession,
    warehouse_id: UUID,
    latitude: float,
    longitude: float,
) -> PolicyZoneClassification:
    """Resolve independent policies for one point inside one warehouse boundary."""

    covering = list(
        await session.scalars(
            build_policy_classification_statement(
                warehouse_id,
                latitude,
                longitude,
            )
        )
    )
    return _classification(covering)


async def classify_policy_points(
    session: AsyncSession,
    points: Iterable[tuple[UUID, UUID, float, float]],
) -> dict[UUID, PolicyZoneClassification]:
    """Classify a bounded request set with one PostGIS query and exact SQL precedence."""

    rows = tuple(points)
    if not rows:
        return {}
    for _, _, latitude, longitude in rows:
        _validate_point(latitude, longitude)
    request_points = (
        values(
            column("request_id", Uuid(as_uuid=True)),
            column("warehouse_id", Uuid(as_uuid=True)),
            column("latitude", Float),
            column("longitude", Float),
            name="policy_request_points",
        )
        .data(rows)
        .alias("policy_request_points")
    )
    point = geofunc.ST_SetSRID(
        geofunc.ST_Point(
            request_points.c.longitude,
            request_points.c.latitude,
        ),
        4326,
    )
    geometry_area = func.ST_Area(WarehousePolicyZone.geometry)
    result = await session.execute(
        select(request_points.c.request_id, WarehousePolicyZone)
        .join(
            WarehousePolicyZone,
            WarehousePolicyZone.warehouse_id == request_points.c.warehouse_id,
        )
        .where(geofunc.ST_Covers(WarehousePolicyZone.geometry, point))
        .order_by(
            request_points.c.request_id,
            geometry_area.asc(),
            WarehousePolicyZone.id.asc(),
        )
    )
    covering: dict[UUID, list[WarehousePolicyZone]] = {
        request_id: [] for request_id, *_ in rows
    }
    for request_id, zone in result:
        covering[request_id].append(zone)
    return {
        request_id: _classification(zones)
        for request_id, zones in covering.items()
    }


def classify_policy_shapes(
    zones: Iterable[InMemoryPolicyZone],
    latitude: float,
    longitude: float,
) -> tuple[UUID | None, UUID | None, UUID | None]:
    """Mirror runtime boundary inclusion and per-kind specificity for pure tests."""

    _validate_point(latitude, longitude)
    point = Point(longitude, latitude)
    covering = sorted(
        (zone for zone in zones if zone.geometry.covers(point)),
        key=lambda zone: (zone.geometry.area, str(zone.id)),
    )
    by_kind: dict[PolicyZoneKind, UUID] = {}
    for zone in covering:
        by_kind.setdefault(zone.kind, zone.id)
    return (
        by_kind.get(PolicyZoneKind.FORBIDDEN),
        by_kind.get(PolicyZoneKind.NO_TRAILER),
        by_kind.get(PolicyZoneKind.SPECIAL_PRICE),
    )


def _classification(
    zones: Iterable[WarehousePolicyZone],
) -> PolicyZoneClassification:
    """Retain only the first, most-specific covering row of each policy kind."""

    by_kind: dict[PolicyZoneKind, WarehousePolicyZone] = {}
    for zone in zones:
        by_kind.setdefault(PolicyZoneKind(zone.kind), zone)
    return PolicyZoneClassification(
        forbidden=by_kind.get(PolicyZoneKind.FORBIDDEN),
        no_trailer=by_kind.get(PolicyZoneKind.NO_TRAILER),
        special_price=by_kind.get(PolicyZoneKind.SPECIAL_PRICE),
    )


def _validate_point(latitude: float, longitude: float) -> None:
    """Reject non-finite or out-of-range coordinates before constructing SQL geometry."""

    if (
        not isfinite(latitude)
        or not isfinite(longitude)
        or not -90 <= latitude <= 90
        or not -180 <= longitude <= 180
    ):
        raise ValueError("policy classification point must be valid WGS84")
