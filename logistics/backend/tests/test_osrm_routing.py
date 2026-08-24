"""Contract tests for the private OpenStreetMap-backed OSRM adapter."""

from __future__ import annotations

import asyncio
from datetime import datetime
from zoneinfo import ZoneInfo

import httpx
import pytest

from app.routing import GeoPoint, OsrmRoutingProvider, OsrmRoutingProviderError


def _provider(handler: httpx.MockTransport) -> OsrmRoutingProvider:
    """Create one provider using an in-memory OSRM HTTP boundary."""

    return OsrmRoutingProvider(
        "http://osrm:5000",
        transport=handler,
    )


def test_osrm_matrix_maps_directed_distance_and_duration() -> None:
    """The table API feeds the exact typed matrix used by the heuristic."""

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.startswith("/table/v1/driving/")
        assert request.url.params["annotations"] == "distance,duration"
        return httpx.Response(
            200,
            json={
                "code": "Ok",
                "distances": [[0, 1250.4], [1375.6, 0]],
                "durations": [[0, 131.2], [149.7, 0]],
            },
        )

    provider = _provider(httpx.MockTransport(handler))
    matrix = asyncio.run(
        provider.get_matrix(
            [GeoPoint(37.6, 55.7), GeoPoint(37.61, 55.71)],
            datetime(2026, 8, 25, 8, tzinfo=ZoneInfo("Europe/Moscow")),
        )
    )

    assert matrix.at(0, 1).distance_meters == 1250
    assert matrix.at(0, 1).travel_seconds == 131
    assert matrix.at(1, 0).distance_meters == 1376
    assert matrix.at(1, 0).travel_seconds == 150


def test_osrm_route_uses_road_geometry_for_simulation_leg() -> None:
    """A returned OSRM road polyline is retained instead of a straight fallback line."""

    road_line = [[37.6, 55.7], [37.605, 55.705], [37.61, 55.71]]

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.startswith("/route/v1/driving/")
        assert request.url.params["geometries"] == "geojson"
        return httpx.Response(
            200,
            json={
                "code": "Ok",
                "routes": [
                    {
                        "distance": 1400.0,
                        "duration": 150.0,
                        "geometry": {"type": "LineString", "coordinates": road_line},
                        "legs": [
                            {
                                "distance": 1400.0,
                                "duration": 150.0,
                                "steps": [
                                    {
                                        "geometry": {
                                            "type": "LineString",
                                            "coordinates": road_line,
                                        }
                                    }
                                ],
                            }
                        ],
                    }
                ],
            },
        )

    provider = _provider(httpx.MockTransport(handler))
    route = asyncio.run(provider.get_route([GeoPoint(37.6, 55.7), GeoPoint(37.61, 55.71)], None))

    assert route.total_distance_meters == 1400
    assert route.legs[0].geometry["coordinates"] == road_line


def test_osrm_rejects_unroutable_matrix_cells() -> None:
    """A missing road connection cannot be converted into fabricated travel metrics."""

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "code": "Ok",
                "distances": [[0, None], [None, 0]],
                "durations": [[0, None], [None, 0]],
            },
        )

    provider = _provider(httpx.MockTransport(handler))
    with pytest.raises(OsrmRoutingProviderError, match="unavailable"):
        asyncio.run(provider.get_matrix([GeoPoint(37.6, 55.7), GeoPoint(37.61, 55.71)], None))
