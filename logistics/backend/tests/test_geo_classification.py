"""Focused tests for zone geometry validation and classification precedence."""

from uuid import UUID, uuid4

import pytest
from hypothesis import given
from hypothesis import strategies as st
from shapely.geometry import Point, Polygon
from sqlalchemy.dialects import postgresql

from app.geo.classification import (
    InMemoryZone,
    build_classification_statement,
    classify_point_in_memory,
    subtract_polygonal_cutout,
)
from app.schemas.domain import GeoJsonGeometry


def _zone(coordinates: list[tuple[float, float]], *, zone_id: UUID | None = None) -> InMemoryZone:
    """Build a compact polygon fixture."""

    return InMemoryZone(
        id=zone_id or uuid4(),
        version=1,
        geometry=Polygon(coordinates),
    )


def test_classification_prefers_smallest_covering_area_then_uuid() -> None:
    """Specificity wins an overlap and UUID is the deterministic equal-area tie break."""

    broad = _zone([(0, 0), (4, 0), (4, 4), (0, 4), (0, 0)])
    narrow = _zone([(1, 1), (2, 1), (2, 2), (1, 2), (1, 1)])

    match = classify_point_in_memory([broad, narrow], latitude=1.5, longitude=1.5)
    assert match is not None
    assert match.zone_id == narrow.id

    first = _zone(
        [(0, 0), (1, 0), (1, 1), (0, 1), (0, 0)],
        zone_id=UUID("00000000-0000-0000-0000-000000000001"),
    )
    second = _zone(
        [(0, 0), (1, 0), (1, 1), (0, 1), (0, 0)],
        zone_id=UUID("00000000-0000-0000-0000-000000000002"),
    )
    match = classify_point_in_memory([second, first], latitude=0.5, longitude=0.5)
    assert match is not None
    assert match.zone_id == first.id


def test_classification_covers_boundary_and_reports_outside() -> None:
    """Boundary points belong to a zone, while uncovered points do not."""

    zone = _zone([(0, 0), (1, 0), (1, 1), (0, 1), (0, 0)])
    assert classify_point_in_memory([zone], latitude=0.5, longitude=0) is not None
    assert classify_point_in_memory([zone], latitude=2, longitude=2) is None


def test_postgis_statement_uses_covers_area_and_uuid() -> None:
    """Production SQL retains the required authoritative PostGIS precedence."""

    warehouse_id = uuid4()
    statement = build_classification_statement(warehouse_id, 55.75, 37.61)
    sql = str(statement.compile(dialect=postgresql.dialect())).upper()
    assert "ST_COVERS" in sql
    assert "ST_AREA" in sql
    assert "ST_AREA(ST_TRANSFORM(ZONES.GEOMETRY" in sql
    assert "ZONES.WAREHOUSE_ID" in sql
    assert "ZONES.ID ASC" in sql


def test_self_intersecting_zone_is_rejected() -> None:
    """The API contract rejects invalid bow-tie polygons before PostGIS."""

    with pytest.raises(ValueError, match="invalid or self-intersecting"):
        GeoJsonGeometry(
            type="Polygon",
            coordinates=[[(0, 0), (2, 2), (0, 2), (2, 0), (0, 0)]],
        )


def test_cutout_creates_hole_excluded_from_point_classification() -> None:
    """A strictly internal subtraction persists as a hole, not an overlapping zone."""

    outer = Polygon([(0, 0), (4, 0), (4, 4), (0, 4), (0, 0)])
    cutout = Polygon([(1, 1), (2, 1), (2, 2), (1, 2), (1, 1)])

    result = subtract_polygonal_cutout(outer, cutout)

    assert len(result.geoms) == 1
    assert len(result.geoms[0].interiors) == 1
    assert result.covers(Point(0.5, 0.5))
    assert not result.covers(Point(1.5, 1.5))


@pytest.mark.parametrize(
    "cutout",
    [
        Polygon([(3, 3), (5, 3), (5, 5), (3, 5), (3, 3)]),
        Polygon([(0, 1), (1, 1), (1, 2), (0, 2), (0, 1)]),
    ],
)
def test_cutout_outside_or_touching_boundary_is_rejected(cutout: Polygon) -> None:
    """A cutout cannot extend or touch the selected zone's outer boundary."""

    outer = Polygon([(0, 0), (4, 0), (4, 4), (0, 4), (0, 0)])

    with pytest.raises(ValueError, match="strictly inside"):
        subtract_polygonal_cutout(outer, cutout)


@given(
    longitude=st.floats(min_value=-1, max_value=1, allow_nan=False),
    latitude=st.floats(min_value=-1, max_value=1, allow_nan=False),
)
def test_covering_square_never_returns_wrong_zone(longitude: float, latitude: float) -> None:
    """Any generated point inside the square deterministically maps to that square."""

    zone = _zone([(-1, -1), (1, -1), (1, 1), (-1, 1), (-1, -1)])
    match = classify_point_in_memory([zone], latitude=latitude, longitude=longitude)
    assert match is not None
    assert match.zone_id == zone.id
