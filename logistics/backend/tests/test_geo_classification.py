"""Focused tests for zone geometry validation and classification precedence."""

from uuid import uuid4

import pytest
from hypothesis import given
from hypothesis import strategies as st
from shapely.geometry import Polygon
from sqlalchemy.dialects import postgresql

from app.geo.classification import (
    InMemoryZone,
    build_classification_statement,
    classify_point_in_memory,
)
from app.schemas.domain import GeoJsonGeometry


def _zone(code: str, priority: int, coordinates: list[tuple[float, float]]) -> InMemoryZone:
    """Build a compact polygon fixture."""

    return InMemoryZone(
        id=uuid4(),
        version=1,
        code=code,
        priority=priority,
        geometry=Polygon(coordinates),
    )


def test_classification_prefers_priority_then_smallest_covering_area() -> None:
    """Priority dominates area, while area resolves equal-priority overlaps."""

    broad = _zone("BROAD", 10, [(0, 0), (4, 0), (4, 4), (0, 4), (0, 0)])
    narrow = _zone("NARROW", 10, [(1, 1), (2, 1), (2, 2), (1, 2), (1, 1)])
    priority = _zone("PRIORITY", 20, [(0, 0), (4, 0), (4, 4), (0, 4), (0, 0)])

    match = classify_point_in_memory([broad, narrow], latitude=1.5, longitude=1.5)
    assert match is not None
    assert match.zone_code == "NARROW"

    match = classify_point_in_memory([broad, narrow, priority], latitude=1.5, longitude=1.5)
    assert match is not None
    assert match.zone_code == "PRIORITY"


def test_classification_covers_boundary_and_reports_outside() -> None:
    """Boundary points belong to a zone, while uncovered points do not."""

    zone = _zone("Z1", 1, [(0, 0), (1, 0), (1, 1), (0, 1), (0, 0)])
    assert classify_point_in_memory([zone], latitude=0.5, longitude=0) is not None
    assert classify_point_in_memory([zone], latitude=2, longitude=2) is None


def test_postgis_statement_uses_covers_priority_and_area() -> None:
    """Production SQL retains the required authoritative PostGIS precedence."""

    statement = build_classification_statement(uuid4(), 55.75, 37.61)
    sql = str(statement.compile(dialect=postgresql.dialect())).upper()
    assert "ST_COVERS" in sql
    assert "ST_AREA" in sql
    assert "PRIORITY DESC" in sql


def test_self_intersecting_zone_is_rejected() -> None:
    """The API contract rejects invalid bow-tie polygons before PostGIS."""

    with pytest.raises(ValueError, match="invalid or self-intersecting"):
        GeoJsonGeometry(
            type="Polygon",
            coordinates=[[(0, 0), (2, 2), (0, 2), (2, 0), (0, 0)]],
        )


@given(
    longitude=st.floats(min_value=-1, max_value=1, allow_nan=False),
    latitude=st.floats(min_value=-1, max_value=1, allow_nan=False),
)
def test_covering_square_never_returns_wrong_zone(longitude: float, latitude: float) -> None:
    """Any generated point inside the square deterministically maps to that square."""

    zone = _zone("SQUARE", 1, [(-1, -1), (1, -1), (1, 1), (-1, 1), (-1, -1)])
    match = classify_point_in_memory([zone], latitude=latitude, longitude=longitude)
    assert match is not None
    assert match.zone_id == zone.id
