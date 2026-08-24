"""PostGIS-backed point classification and safe GeoJSON conversion."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any
from uuid import UUID

from geoalchemy2 import functions as geofunc
from geoalchemy2.elements import WKBElement
from geoalchemy2.shape import from_shape, to_shape
from shapely.geometry import MultiPolygon, Polygon, mapping
from shapely.geometry.base import BaseGeometry
from sqlalchemy import Select, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Zone
from app.schemas.domain import GeoJsonGeometry


@dataclass(frozen=True, slots=True)
class ZoneMatch:
    """Minimal authoritative classification result persisted on a request."""

    zone_id: UUID
    zone_version: int
    zone_code: str


@dataclass(frozen=True, slots=True)
class InMemoryZone:
    """Small pure-test representation of zone precedence inputs."""

    id: UUID
    version: int
    code: str
    priority: int
    geometry: Polygon | MultiPolygon


def geometry_from_geojson(geometry: GeoJsonGeometry) -> WKBElement:
    """Normalize valid Polygon input to the persisted MultiPolygon SRID 4326 shape."""

    polygon = geometry.to_shapely()
    normalized = MultiPolygon([polygon]) if isinstance(polygon, Polygon) else polygon
    return from_shape(normalized, srid=4326, extended=True)


def geometry_to_geojson(value: WKBElement) -> dict[str, Any]:
    """Convert a PostGIS geometry result to a JSON-safe GeoJSON mapping."""

    return dict(mapping(to_shape(value)))


def build_classification_statement(
    scenario_id: UUID, latitude: float, longitude: float
) -> Select[tuple[Zone]]:
    """Build priority-first, smallest-area PostGIS classification query.

    ``ST_Covers`` intentionally includes polygon boundaries. Priority is the
    primary discriminator; projected area provides the specified specificity
    tie-breaker, followed by UUID for deterministic results.
    """

    point = geofunc.ST_SetSRID(geofunc.ST_Point(longitude, latitude), 4326)
    area = func.ST_Area(func.ST_Transform(Zone.geometry, 3857))
    return (
        select(Zone)
        .where(Zone.scenario_id == scenario_id, geofunc.ST_Covers(Zone.geometry, point))
        .order_by(Zone.priority.desc(), area.asc(), Zone.id.asc())
        .limit(1)
    )


async def classify_point(
    session: AsyncSession, scenario_id: UUID, latitude: float, longitude: float
) -> ZoneMatch | None:
    """Classify a coordinate using PostGIS as the production source of truth."""

    zone = await session.scalar(build_classification_statement(scenario_id, latitude, longitude))
    if zone is None:
        return None
    return ZoneMatch(zone_id=zone.id, zone_version=zone.version, zone_code=zone.code)


def classify_point_in_memory(
    zones: Iterable[InMemoryZone], latitude: float, longitude: float
) -> ZoneMatch | None:
    """Mirror precedence rules purely for focused invariant tests.

    Runtime request classification always uses :func:`classify_point`; this
    helper makes boundary, priority, and specificity behavior testable without
    weakening the PostGIS production path.
    """

    from shapely.geometry import Point

    point = Point(longitude, latitude)
    covering = [zone for zone in zones if zone.geometry.covers(point)]
    if not covering:
        return None
    winner = min(covering, key=lambda zone: (-zone.priority, zone.geometry.area, str(zone.id)))
    return ZoneMatch(winner.id, winner.version, winner.code)


def ensure_polygonal(value: BaseGeometry) -> MultiPolygon:
    """Normalize a Shapely polygonal value or reject a non-polygonal shape."""

    if isinstance(value, Polygon):
        return MultiPolygon([value])
    if isinstance(value, MultiPolygon):
        return value
    raise ValueError("expected Polygon or MultiPolygon")


def subtract_polygonal_cutout(
    zone_geometry: BaseGeometry,
    cutout_geometry: BaseGeometry,
) -> MultiPolygon:
    """Subtract a strictly contained cutout while preserving valid polygonal output.

    Contact with either the outer boundary or an existing interior ring is
    rejected. This keeps every edit an unambiguous new hole instead of silently
    splitting, extending, or repairing the selected zone.
    """

    zone = ensure_polygonal(zone_geometry)
    cutout = ensure_polygonal(cutout_geometry)
    if (
        cutout.is_empty
        or not cutout.is_valid
        or not cutout.within(zone)
        or not zone.boundary.disjoint(cutout)
    ):
        raise ValueError("cutout must be strictly inside the existing zone")
    result = zone.difference(cutout)
    normalized = ensure_polygonal(result)
    if normalized.is_empty or not normalized.is_valid:
        raise ValueError("cutout produced invalid zone geometry")
    return normalized
