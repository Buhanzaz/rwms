"""Focused contracts for Valhalla truck travel-time contour diagnostics."""

from __future__ import annotations

import asyncio
import json
from collections.abc import Mapping
from typing import cast

import httpx
import pytest
from fastapi.testclient import TestClient

from app.api import routing as routing_api
from app.config import Settings, get_settings
from app.main import create_app
from app.routing.models import GeoPoint
from app.routing.valhalla import (
    RoutingProviderUnavailableError,
    ValhallaRoutingProvider,
)


def _polygon(offset: float) -> dict[str, object]:
    """Build a small closed WGS84 polygon around the test warehouse."""

    return {
        "type": "Polygon",
        "coordinates": [
            [
                [37.6 - offset, 55.7 - offset],
                [37.6 + offset, 55.7 - offset],
                [37.6 + offset, 55.7 + offset],
                [37.6 - offset, 55.7 - offset],
            ]
        ],
    }


def _provider_response() -> dict[str, object]:
    """Return all four requested contours in deliberately non-canonical order."""

    return {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "properties": {"contour": minutes, "fillColor": "ignored"},
                "geometry": _polygon(minutes / 10_000),
            }
            for minutes in (120, 60, 240, 180)
        ],
    }


def _provider(transport: httpx.AsyncBaseTransport) -> ValhallaRoutingProvider:
    """Create one private Valhalla adapter backed by an in-memory transport."""

    return ValhallaRoutingProvider(
        "http://valhalla:8002",
        timeout_seconds=1,
        osm_data_version="central-2026-08-26",
        transport=transport,
    )


def test_isochrone_request_is_truck_only_and_response_is_canonical() -> None:
    """The provider sends fixed truck contours and returns validated outer-first areas."""

    requests: list[dict[str, object]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(cast(dict[str, object], json.loads(request.content)))
        assert request.url.path == "/isochrone"
        return httpx.Response(200, json=_provider_response())

    provider = _provider(httpx.MockTransport(handler))

    async def execute() -> list[dict[str, object]]:
        """Run and close the pooled provider in one event loop."""

        try:
            return await provider.get_truck_travel_time_contours(GeoPoint(37.6, 55.7))
        finally:
            await provider.aclose()

    features = asyncio.run(execute())

    assert requests == [
        {
            "locations": [{"lat": 55.7, "lon": 37.6}],
            "costing": "truck",
            "contours": [{"time": 60}, {"time": 120}, {"time": 180}, {"time": 240}],
            "polygons": True,
        }
    ]
    assert "auto" not in json.dumps(requests)
    assert [
        cast(Mapping[str, int], feature["properties"])["contour_minutes"] for feature in features
    ] == [240, 180, 120, 60]
    assert all(
        cast(Mapping[str, object], feature["geometry"])["type"] == "Polygon" for feature in features
    )


@pytest.mark.parametrize(
    "payload",
    [
        {"type": "FeatureCollection", "features": []},
        {
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "properties": {"contour": minutes},
                    "geometry": {"type": "LineString", "coordinates": []},
                }
                for minutes in (60, 120, 180, 240)
            ],
        },
        {
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "properties": {"contour": minutes},
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[37.5, 55.6], [37.7, 55.6], [37.7, 55.8]]],
                    },
                }
                for minutes in (60, 120, 180, 240)
            ],
        },
    ],
)
def test_malformed_isochrone_response_is_a_typed_provider_failure(
    payload: dict[str, object],
) -> None:
    """Missing contours and non-area/open geometries never reach the map."""

    provider = _provider(httpx.MockTransport(lambda _: httpx.Response(200, json=payload)))

    async def execute() -> None:
        """Run the malformed response through the production parser and close it."""

        try:
            await provider.get_truck_travel_time_contours(GeoPoint(37.6, 55.7))
        finally:
            await provider.aclose()

    with pytest.raises(RoutingProviderUnavailableError) as failure:
        asyncio.run(execute())
    assert failure.value.code == "ROUTING_PROVIDER_UNAVAILABLE"


def test_isochrone_timeout_is_a_typed_provider_failure() -> None:
    """A private Valhalla timeout is distinguishable from an empty contour result."""

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("deadline", request=request)

    provider = _provider(httpx.MockTransport(handler))

    async def execute() -> None:
        """Run the timeout through the production adapter and close it."""

        try:
            await provider.get_truck_travel_time_contours(GeoPoint(37.6, 55.7))
        finally:
            await provider.aclose()

    with pytest.raises(RoutingProviderUnavailableError, match="отведённое время"):
        asyncio.run(execute())


def test_read_only_endpoint_echoes_provenance_and_maps_provider_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """HTTP consumers receive validated GeoJSON or explicit Problem Details, never fake rings."""

    class FakeProvider:
        """Inject one deterministic provider at the endpoint construction boundary."""

        fail = False

        def __init__(self, *_: object, **__: object) -> None:
            """Accept the same constructor surface as the production provider."""

        async def get_truck_travel_time_contours(self, origin: GeoPoint) -> list[dict[str, object]]:
            """Return canonical areas or the typed unavailable failure requested by the test."""

            assert origin == GeoPoint(37.6, 55.7)
            if self.fail:
                raise RoutingProviderUnavailableError("Valhalla test failure.")
            raw = _provider_response()
            provider = _provider(httpx.MockTransport(lambda _: httpx.Response(200, json=raw)))
            try:
                return await provider.get_truck_travel_time_contours(origin)
            finally:
                await provider.aclose()

        async def aclose(self) -> None:
            """Mirror the production provider lifecycle without external resources."""

    monkeypatch.setattr(routing_api, "ValhallaRoutingProvider", FakeProvider)
    application = create_app()
    application.dependency_overrides[get_settings] = lambda: Settings(
        valhalla_enabled=True,
        valhalla_url="http://valhalla:8002",
        osm_data_version="central-2026-08-26",
    )
    client = TestClient(application)

    response = client.get("/api/routing/travel-time-contours?latitude=55.7&longitude=37.6")

    assert response.status_code == 200
    document = response.json()
    assert document["type"] == "FeatureCollection"
    assert [feature["properties"]["contour_minutes"] for feature in document["features"]] == [
        240,
        180,
        120,
        60,
    ]
    assert document["metadata"] == {
        "source": "valhalla",
        "costing": "truck",
        "origin": {"latitude": 55.7, "longitude": 37.6},
        "contours_minutes": [60, 120, 180, 240],
        "osm_data_version": "central-2026-08-26",
    }

    FakeProvider.fail = True
    failure = client.get("/api/routing/travel-time-contours?latitude=55.7&longitude=37.6")
    assert failure.status_code == 503
    assert failure.headers["content-type"].startswith("application/problem+json")
    assert failure.json()["code"] == "ROUTING_PROVIDER_UNAVAILABLE"


def test_read_only_endpoint_rejects_invalid_coordinate_and_disabled_provider() -> None:
    """Invalid origins are 422 and disabled Valhalla is an explicit 503 condition."""

    application = create_app()
    application.dependency_overrides[get_settings] = lambda: Settings(valhalla_enabled=False)
    client = TestClient(application)

    invalid = client.get("/api/routing/travel-time-contours?latitude=95&longitude=37.6")
    disabled = client.get("/api/routing/travel-time-contours?latitude=55.7&longitude=37.6")

    assert invalid.status_code == 422
    assert disabled.status_code == 503
    assert disabled.json()["code"] == "ROUTING_PROVIDER_UNAVAILABLE"
