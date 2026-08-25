"""Contract tests for truck-only routing through the private Valhalla adapter."""

from __future__ import annotations

import asyncio
import json
from collections.abc import Mapping
from datetime import datetime
from typing import cast
from zoneinfo import ZoneInfo

import httpx
import pytest

from app.routing.capabilities import OSM_TRUCK_RESTRICTIONS, RestrictionSupport
from app.routing.models import GeoPoint
from app.routing.truck_profile import EffectiveTruckProfile, TruckConfigurationType
from app.routing.valhalla import (
    NoSafeRouteError,
    RoutingProfileIncompleteError,
    RoutingProviderUnavailableError,
    ValhallaRoadSnapNotFoundError,
    ValhallaRoutingProvider,
)


def _profile(*, trailer: bool = False) -> EffectiveTruckProfile:
    """Build one already-calculated profile at the provider boundary."""

    return EffectiveTruckProfile(
        vehicle_id="vehicle-1",
        trailer_id="trailer-1" if trailer else None,
        trailer_attached=trailer,
        is_hgv=True,
        height_meters=4.05,
        width_meters=2.55,
        length_meters=18.5 if trailer else 9.0,
        actual_weight_tons=24.8 if trailer else 18.2,
        max_axle_load_tons=8.4 if trailer else 7.8,
        axle_count=4 if trailer else 2,
        cargo_count=2 if trailer else 1,
        cargo_placements=(),
        configuration_type=(
            TruckConfigurationType.TWO_CARGO_SPLIT
            if trailer
            else TruckConfigurationType.CARGO_ON_TRUCK
        ),
    )


def _route_response(*, length_km: float = 1.4, seconds: float = 150) -> dict[str, object]:
    """Return the native Valhalla trip shape used by the production parser."""

    line = {
        "type": "LineString",
        "coordinates": [[37.6, 55.7], [37.605, 55.705], [37.61, 55.71]],
    }
    return {
        "trip": {
            "summary": {"length": length_km, "time": seconds},
            "legs": [
                {
                    "summary": {"length": length_km, "time": seconds},
                    "shape": line,
                }
            ],
        }
    }


def _provider(handler: httpx.MockTransport) -> ValhallaRoutingProvider:
    """Create a provider with an in-memory Valhalla HTTP boundary."""

    return ValhallaRoutingProvider(
        "http://valhalla:8002",
        osm_data_version="central-2026-08-22",
        transport=handler,
    )


def test_route_payload_uses_exact_one_and_two_cargo_profiles() -> None:
    """One vehicle produces different truck payloads when its trailer/load changes."""

    requests: list[dict[str, object]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/route"
        requests.append(json.loads(request.content))
        return httpx.Response(200, json=_route_response())

    provider = _provider(httpx.MockTransport(handler))
    points = [GeoPoint(37.6, 55.7), GeoPoint(37.61, 55.71)]
    departure = datetime(2026, 8, 25, 8, 45, tzinfo=ZoneInfo("Europe/Moscow"))

    asyncio.run(provider.get_route(points, departure, profile=_profile()))
    asyncio.run(provider.get_route(points, departure, profile=_profile(trailer=True)))

    assert len(requests) == 2
    assert requests[0]["costing"] == "truck"
    assert requests[0]["shape_format"] == "geojson"
    assert requests[0]["date_time"] == {"type": 1, "value": "2026-08-25T08:45"}
    first_options = cast(Mapping[str, object], requests[0]["costing_options"])
    second_options = cast(Mapping[str, object], requests[1]["costing_options"])
    first_costing = cast(Mapping[str, object], first_options["truck"])
    second_costing = cast(Mapping[str, object], second_options["truck"])
    assert first_costing == {
        "height": 4.05,
        "width": 2.55,
        "length": 9.0,
        "weight": 18.2,
        "axle_load": 7.8,
        "axle_count": 2,
        "hgv_no_access_penalty": 43_200,
        "ignore_restrictions": False,
        "ignore_access": False,
        "ignore_closures": False,
    }
    assert second_costing["length"] == 18.5
    assert second_costing["weight"] == 24.8
    assert second_costing["axle_count"] == 4
    assert all("auto" not in json.dumps(payload) for payload in requests)


def test_route_parses_native_trip_metrics_and_geojson_legs() -> None:
    """Valhalla's kilometer summaries become exact meter/second simulation legs."""

    provider = _provider(
        httpx.MockTransport(lambda _: httpx.Response(200, json=_route_response()))
    )
    route = asyncio.run(
        provider.get_route(
            [GeoPoint(37.6, 55.7), GeoPoint(37.61, 55.71)],
            None,
            profile=_profile(),
        )
    )

    assert route.total_distance_meters == 1400
    assert route.total_travel_seconds == 150
    assert route.legs[0].distance_meters == 1400
    assert route.legs[0].geometry["coordinates"] == [
        [37.6, 55.7],
        [37.605, 55.705],
        [37.61, 55.71],
    ]


def test_matrix_uses_truck_costing_and_parses_directed_metrics() -> None:
    """The square sources-to-targets response remains directed and profile-specific."""

    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/sources_to_targets"
        captured.update(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                "units": "kilometers",
                "sources_to_targets": [
                    [
                        {"from_index": 0, "to_index": 0, "distance": 0, "time": 0},
                        {"from_index": 0, "to_index": 1, "distance": 1.25, "time": 131.2},
                    ],
                    [
                        {"from_index": 1, "to_index": 0, "distance": 1.4, "time": 149.7},
                        {"from_index": 1, "to_index": 1, "distance": 0, "time": 0},
                    ],
                ],
            },
        )

    points = [GeoPoint(37.6, 55.7), GeoPoint(37.61, 55.71)]
    matrix = asyncio.run(
        _provider(httpx.MockTransport(handler)).get_matrix(
            points,
            datetime(2026, 8, 25, 8, tzinfo=ZoneInfo("Europe/Moscow")),
            profile=_profile(trailer=True),
        )
    )

    assert captured["costing"] == "truck"
    assert captured["shape_format"] == "no_shape"
    assert matrix.at(0, 1).distance_meters == 1250
    assert matrix.at(0, 1).travel_seconds == 131
    assert matrix.at(1, 0).distance_meters == 1400
    assert matrix.at(1, 0).travel_seconds == 150


def test_locate_snaps_candidate_without_claiming_a_truck_route() -> None:
    """Road seeding uses correlated coordinates and never sends car/truck costing."""

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/locate"
        payload = json.loads(request.content)
        assert "costing" not in payload
        return httpx.Response(
            200,
            json=[
                {
                    "edges": [
                        {
                            "correlated_lat": 55.7005,
                            "correlated_lon": 37.60025,
                            "edge_info": {
                                "way_id": 142,
                                "names": ["  Тестовая   дорога  "],
                            },
                        }
                    ]
                }
            ],
        )

    snapped = asyncio.run(
        _provider(httpx.MockTransport(handler)).snap_point(
            GeoPoint(37.6, 55.7, is_city=True)
        )
    )

    assert snapped.point == GeoPoint(37.60025, 55.7005, is_city=True)
    assert snapped.distance_meters > 0
    assert snapped.name == "Тестовая дорога"


def test_route_requires_a_complete_profile_before_http() -> None:
    """Critical physical data is never replaced with Valhalla's generic defaults."""

    calls = 0

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json=_route_response())

    provider = _provider(httpx.MockTransport(handler))
    with pytest.raises(RoutingProfileIncompleteError) as failure:
        asyncio.run(
            provider.get_route(
                [GeoPoint(37.6, 55.7), GeoPoint(37.61, 55.71)],
                None,
            )
        )

    assert failure.value.code == "ROUTING_PROFILE_INCOMPLETE"
    assert failure.value.missing_fields == ("effective_truck_profile",)
    assert calls == 0


def test_no_safe_route_never_retries_with_car_costing() -> None:
    """A truck restriction failure is terminal for a driver route."""

    payloads: list[dict[str, object]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payloads.append(json.loads(request.content))
        return httpx.Response(
            400,
            json={"error_code": 442, "error": "No path could be found for input"},
        )

    with pytest.raises(NoSafeRouteError) as failure:
        asyncio.run(
            _provider(httpx.MockTransport(handler)).get_route(
                [GeoPoint(37.6, 55.7), GeoPoint(37.61, 55.71)],
                None,
                profile=_profile(trailer=True),
            )
        )

    assert failure.value.code == "NO_SAFE_ROUTE"
    assert len(payloads) == 1
    assert payloads[0]["costing"] == "truck"
    assert "auto" not in json.dumps(payloads[0])


def test_unreachable_matrix_cell_is_no_safe_route() -> None:
    """A partial matrix cannot silently fabricate metrics for a restricted pair."""

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "units": "kilometers",
                "sources_to_targets": [
                    [
                        {"from_index": 0, "to_index": 0, "distance": 0, "time": 0},
                        {"from_index": 0, "to_index": 1, "distance": None, "time": None},
                    ],
                    [
                        {"from_index": 1, "to_index": 0, "distance": 1, "time": 60},
                        {"from_index": 1, "to_index": 1, "distance": 0, "time": 0},
                    ],
                ],
            },
        )

    with pytest.raises(NoSafeRouteError):
        asyncio.run(
            _provider(httpx.MockTransport(handler)).get_matrix(
                [GeoPoint(37.6, 55.7), GeoPoint(37.61, 55.71)],
                None,
                profile=_profile(),
            )
        )


@pytest.mark.parametrize(
    "handler",
    [
        httpx.MockTransport(lambda _: httpx.Response(503, json={"error_code": 442})),
        httpx.MockTransport(lambda _: httpx.Response(200, content=b"not-json")),
        httpx.MockTransport(
            lambda request: (_ for _ in ()).throw(
                httpx.ConnectError("down", request=request)
            )
        ),
    ],
)
def test_provider_failures_are_not_reported_as_safe_route_failures(
    handler: httpx.MockTransport,
) -> None:
    """Transport, 5xx, and malformed responses retain the unavailable error code."""

    with pytest.raises(RoutingProviderUnavailableError) as failure:
        asyncio.run(
            _provider(handler).get_route(
                [GeoPoint(37.6, 55.7), GeoPoint(37.61, 55.71)],
                None,
                profile=_profile(),
            )
        )

    assert failure.value.code == "ROUTING_PROVIDER_UNAVAILABLE"


def test_locate_without_edges_has_narrow_retryable_snap_error() -> None:
    """The workload generator may retry another point without hiding service outages."""

    with pytest.raises(ValhallaRoadSnapNotFoundError):
        asyncio.run(
            _provider(
                httpx.MockTransport(lambda _: httpx.Response(200, json=[{"edges": []}]))
            ).snap_point(GeoPoint(37.6, 55.7))
        )


def test_departure_time_must_be_timezone_aware() -> None:
    """Conditional OSM access is never evaluated against an ambiguous naive timestamp."""

    with pytest.raises(ValueError, match="timezone-aware"):
        asyncio.run(
            _provider(
                httpx.MockTransport(lambda _: httpx.Response(200, json=_route_response()))
            ).get_route(
                [GeoPoint(37.6, 55.7), GeoPoint(37.61, 55.71)],
                datetime(2026, 8, 25, 8),
                profile=_profile(),
            )
        )


def test_cache_identity_changes_with_profile_time_and_osm_version() -> None:
    """Origin/destination alone can never collide across truck configurations."""

    points = [GeoPoint(37.6, 55.7), GeoPoint(37.61, 55.71)]
    provider = ValhallaRoutingProvider(
        "http://valhalla:8002",
        osm_data_version="central-2026-08-22",
    )
    departure = datetime(2026, 8, 25, 8, tzinfo=ZoneInfo("Europe/Moscow"))

    one = provider.route_cache_key(points, departure, _profile())
    two = provider.route_cache_key(points, departure, _profile(trailer=True))
    later = provider.route_cache_key(
        points,
        datetime(2026, 8, 25, 9, tzinfo=ZoneInfo("Europe/Moscow")),
        _profile(),
    )

    assert one != two
    assert one != later
    assert "central-2026-08-22" in one
    assert "3.8.3" in one


def test_capability_table_does_not_overclaim_conditional_dimensions() -> None:
    """The machine-readable capability layer matches the audited 3.8.3 boundary."""

    claims = {item.osm_tag: item.support for item in OSM_TRUCK_RESTRICTIONS}
    assert claims["hgv / access / motor_vehicle / vehicle"] is RestrictionSupport.SUPPORTED
    conditional = next(
        support
        for tag, support in claims.items()
        if tag.startswith("maxweight:conditional")
    )
    assert conditional is RestrictionSupport.UNSUPPORTED
    assert claims["hgv_articulated / trailer"] is RestrictionSupport.UNSUPPORTED
