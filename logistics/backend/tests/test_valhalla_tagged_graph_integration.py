"""Real Valhalla graph checks for OSM truck restrictions and load-dependent paths."""

from __future__ import annotations

import os
from dataclasses import replace
from datetime import UTC, datetime
from typing import cast

import pytest

from app.routing import GeoPoint, ValhallaRoutingProvider
from app.routing.truck_profile import (
    CargoDimensions,
    CargoPlacement,
    CargoPosition,
    EffectiveTruckProfile,
    TruckConfigurationType,
)

pytestmark = pytest.mark.integration

DEPARTURE = datetime(2026, 8, 25, 8, tzinfo=UTC)
CARGO = CargoDimensions(
    length_mm=6_000,
    width_mm=2_400,
    height_mm=2_400,
    weight_kg=2_500,
)


def _provider() -> ValhallaRoutingProvider:
    """Connect only to the explicit disposable tagged-graph test service."""

    base_url = os.getenv("VALHALLA_SYNTHETIC_URL")
    if not base_url:
        pytest.skip("VALHALLA_SYNTHETIC_URL is required for tagged-graph integration")
    return ValhallaRoutingProvider(
        base_url,
        osm_data_version="synthetic-truck-restrictions-v1",
        timeout_seconds=5,
    )


def _one_cargo_profile() -> EffectiveTruckProfile:
    """Represent a nine-metre truck carrying one identified cabin."""

    placement = CargoPlacement("cargo-1", CargoPosition.TRUCK_PLATFORM, CARGO)
    return EffectiveTruckProfile(
        vehicle_id="truck-1",
        trailer_id=None,
        trailer_attached=False,
        is_hgv=True,
        height_meters=3.8,
        width_meters=2.4,
        length_meters=9.0,
        actual_weight_tons=18.0,
        max_axle_load_tons=7.5,
        axle_count=2,
        cargo_count=1,
        cargo_placements=(placement,),
        configuration_type=TruckConfigurationType.CARGO_ON_TRUCK,
    )


def _two_cargo_profile() -> EffectiveTruckProfile:
    """Represent the same truck with a second cabin on its attached trailer."""

    truck = CargoPlacement("cargo-1", CargoPosition.TRUCK_PLATFORM, CARGO)
    trailer = CargoPlacement("cargo-2", CargoPosition.TRAILER_PLATFORM, CARGO)
    return EffectiveTruckProfile(
        vehicle_id="truck-1",
        trailer_id="trailer-1",
        trailer_attached=True,
        is_hgv=True,
        height_meters=3.8,
        width_meters=2.4,
        length_meters=18.0,
        actual_weight_tons=24.0,
        max_axle_load_tons=8.0,
        axle_count=4,
        cargo_count=2,
        cargo_placements=(truck, trailer),
        configuration_type=TruckConfigurationType.TWO_CARGO_SPLIT,
    )


def _endpoints(latitude: float) -> list[GeoPoint]:
    """Return points on unrestricted stubs surrounding one synthetic restriction."""

    return [
        GeoPoint(lon=36.9981, lat=latitude),
        GeoPoint(lon=37.0219, lat=latitude),
    ]


def _uses_detour(route_max_latitude: float, base_latitude: float) -> bool:
    """Identify the deliberately northbound unrestricted alternative."""

    return route_max_latitude > base_latitude + 0.005


async def _route_max_latitude(
    provider: ValhallaRoutingProvider,
    latitude: float,
    profile: EffectiveTruckProfile,
) -> tuple[float, int]:
    """Route one component and return its northernmost coordinate and distance."""

    result = await provider.get_route(
        _endpoints(latitude),
        DEPARTURE,
        profile=profile,
    )
    coordinates = cast(list[list[float]], result.geometry["coordinates"])
    return max(float(coordinate[1]) for coordinate in coordinates), result.total_distance_meters


@pytest.mark.asyncio
async def test_one_cabin_uses_short_path_but_two_cabins_avoid_maxlength() -> None:
    """The same truck gets a different path once its 18 m trailer is attached."""

    provider = _provider()
    one_max_lat, one_distance = await _route_max_latitude(
        provider, 55.00, _one_cargo_profile()
    )
    two_max_lat, two_distance = await _route_max_latitude(
        provider, 55.00, _two_cargo_profile()
    )

    assert not _uses_detour(one_max_lat, 55.00)
    assert _uses_detour(two_max_lat, 55.00)
    assert two_distance > one_distance


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("latitude", "allowed_profile", "restricted_profile"),
    (
        (
            55.03,
            _one_cargo_profile(),
            replace(_one_cargo_profile(), height_meters=4.0),
        ),
        (
            55.06,
            _one_cargo_profile(),
            replace(_one_cargo_profile(), actual_weight_tons=24.0),
        ),
        (
            55.09,
            _one_cargo_profile(),
            replace(_one_cargo_profile(), width_meters=2.55),
        ),
        (
            55.12,
            _one_cargo_profile(),
            replace(_one_cargo_profile(), max_axle_load_tons=8.5),
        ),
    ),
    ids=("maxheight", "maxweight", "maxwidth", "maxaxleload"),
)
async def test_static_dimension_and_weight_restrictions_select_safe_detour(
    latitude: float,
    allowed_profile: EffectiveTruckProfile,
    restricted_profile: EffectiveTruckProfile,
) -> None:
    """Profiles below each OSM limit use the short edge; larger ones cannot."""

    provider = _provider()
    allowed_max_lat, allowed_distance = await _route_max_latitude(
        provider, latitude, allowed_profile
    )
    restricted_max_lat, restricted_distance = await _route_max_latitude(
        provider, latitude, restricted_profile
    )

    assert not _uses_detour(allowed_max_lat, latitude)
    assert _uses_detour(restricted_max_lat, latitude)
    assert restricted_distance > allowed_distance


@pytest.mark.asyncio
async def test_hgv_no_is_not_used_as_a_penalized_truck_shortcut() -> None:
    """The pinned maximum HGV penalty keeps truck access as a hard edge mask."""

    max_latitude, distance = await _route_max_latitude(
        _provider(), 55.15, _one_cargo_profile()
    )

    assert _uses_detour(max_latitude, 55.15)
    assert distance > 3_000
