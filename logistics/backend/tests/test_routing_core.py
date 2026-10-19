"""Focused tests for the deterministic offline routing provider."""

from __future__ import annotations

import asyncio
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from app.routing import (
    GeoPoint,
    MockRoutingProvider,
    RouteGeometry,
    RoutingSettings,
    TravelMatrix,
    haversine_distance_meters,
)

MOSCOW = ZoneInfo("Europe/Moscow")


def test_haversine_uses_lon_lat_and_known_distance() -> None:
    first = GeoPoint(lon=0.0, lat=0.0)
    second = GeoPoint(lon=1.0, lat=0.0)

    assert haversine_distance_meters(first, second) == pytest.approx(111_195, rel=0.001)


def test_mock_matrix_and_route_are_seed_deterministic() -> None:
    points = [
        GeoPoint(lon=37.60, lat=55.75, is_city=True),
        GeoPoint(lon=37.75, lat=55.77, is_city=True),
        GeoPoint(lon=38.10, lat=55.90),
    ]
    departure = datetime(2026, 8, 25, 11, tzinfo=MOSCOW)

    async def execute() -> tuple[TravelMatrix, RouteGeometry]:
        provider = MockRoutingProvider(RoutingSettings(seed=142))
        return (
            await provider.get_matrix(points, departure),
            await provider.get_route(points, departure),
        )

    first = asyncio.run(execute())
    second = asyncio.run(execute())

    assert first == second
    route = first[1]
    assert route.geometry["type"] == "LineString"
    assert route.geometry["coordinates"] == [
        [37.6, 55.75],
        [37.75, 55.77],
        [38.1, 55.9],
    ]
    assert len(route.legs) == 2
    assert route.total_distance_meters == sum(leg.distance_meters for leg in route.legs)


def test_seed_changes_only_stable_road_noise() -> None:
    points = [GeoPoint(37.6, 55.7), GeoPoint(38.1, 55.9)]

    async def distance(seed: int) -> int:
        provider = MockRoutingProvider(RoutingSettings(seed=seed, deterministic_noise_ratio=0.1))
        matrix = await provider.get_matrix(points, None)
        return matrix.at(0, 1).distance_meters

    assert asyncio.run(distance(1)) != asyncio.run(distance(2))
    assert asyncio.run(distance(1)) == asyncio.run(distance(1))


def test_city_speed_and_peak_multiplier_increase_duration() -> None:
    points = [GeoPoint(37.6, 55.7, is_city=True), GeoPoint(37.8, 55.8, is_city=True)]
    provider = MockRoutingProvider(
        RoutingSettings(
            city_speed_kmh=30,
            region_speed_kmh=60,
            morning_traffic_multiplier=2,
            deterministic_noise_ratio=0,
        )
    )

    off_peak = asyncio.run(provider.get_matrix(points, datetime(2026, 8, 25, 12, tzinfo=MOSCOW)))
    peak = asyncio.run(provider.get_matrix(points, datetime(2026, 8, 25, 8, tzinfo=MOSCOW)))

    assert peak.at(0, 1).travel_seconds == pytest.approx(
        off_peak.at(0, 1).travel_seconds * 2,
        abs=1,
    )


def test_provider_rejects_naive_departure() -> None:
    provider = MockRoutingProvider()

    with pytest.raises(ValueError, match="timezone-aware"):
        asyncio.run(
            provider.get_route(
                [GeoPoint(37.6, 55.7), GeoPoint(37.7, 55.8)],
                datetime(2026, 8, 25, 8),
            )
        )
