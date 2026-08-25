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


def test_osrm_large_matrix_is_partitioned_into_bounded_rectangular_tables() -> None:
    """A workload above OSRM's coordinate limit is reconstructed from directed blocks."""

    requests: list[tuple[int, int]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        encoded_points = request.url.path.rsplit("/", maxsplit=1)[-1].split(";")
        sources = [int(value) for value in request.url.params["sources"].split(";")]
        destinations = [
            int(value) for value in request.url.params["destinations"].split(";")
        ]
        assert len(encoded_points) <= 4
        assert len(sources) <= 2
        assert len(destinations) <= 2
        global_indexes = [round(float(value.split(",")[0]) - 37) for value in encoded_points]
        requests.append((len(sources), len(destinations)))

        def metric(source: int, destination: int, multiplier: int) -> int:
            source_index = global_indexes[source]
            destination_index = global_indexes[destination]
            if source_index == destination_index:
                return 0
            return source_index * multiplier + destination_index

        return httpx.Response(
            200,
            json={
                "code": "Ok",
                "distances": [
                    [metric(source, destination, 100) for destination in destinations]
                    for source in sources
                ],
                "durations": [
                    [metric(source, destination, 10) for destination in destinations]
                    for source in sources
                ],
            },
        )

    provider = OsrmRoutingProvider(
        "http://osrm:5000",
        max_table_points=4,
        transport=httpx.MockTransport(handler),
    )
    points = [GeoPoint(37 + index, 55.7) for index in range(5)]

    matrix = asyncio.run(provider.get_matrix(points, None))

    assert len(requests) == 9
    assert matrix.at(1, 4).distance_meters == 104
    assert matrix.at(4, 1).distance_meters == 401
    assert matrix.at(2, 3).travel_seconds == 23
    assert matrix.at(3, 2).travel_seconds == 32
    assert matrix.at(4, 4).distance_meters == 0


def test_osrm_http_error_is_sanitized_without_internal_url() -> None:
    """Operator-facing failures retain the vendor reason without leaking a long URL."""

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            400,
            json={"code": "TooBig", "message": "Too many table coordinates"},
        )

    provider = _provider(httpx.MockTransport(handler))
    with pytest.raises(OsrmRoutingProviderError) as failure:
        asyncio.run(provider.get_matrix([GeoPoint(37.6, 55.7), GeoPoint(37.61, 55.71)], None))

    message = str(failure.value)
    assert "HTTP 400" in message
    assert "TooBig" in message
    assert "Too many table coordinates" in message
    assert "http://" not in message


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
