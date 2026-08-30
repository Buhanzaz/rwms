"""JSON-safe conversion for persisted PostGIS route geometry."""

from typing import Any

from geoalchemy2.elements import WKBElement
from geoalchemy2.shape import to_shape
from shapely.geometry import mapping


def geometry_to_geojson(value: WKBElement) -> dict[str, Any]:
    """Convert a PostGIS geometry result to a JSON-safe GeoJSON mapping."""

    return dict(mapping(to_shape(value)))
