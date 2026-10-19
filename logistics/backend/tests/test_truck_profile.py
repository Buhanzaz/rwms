"""Unit tests for physical per-leg effective truck profile calculation."""

from __future__ import annotations

from dataclasses import replace
from typing import Any
from uuid import uuid4

import pytest

from app.routing.truck_profile import (
    CargoDimensions,
    CargoPlacement,
    CargoPosition,
    EffectiveTruckProfileCalculator,
    LoadConfiguration,
    OperationalAxleLoadProfile,
    TrailerSpec,
    TruckConfigurationType,
    TruckProfileError,
    TruckProfileErrorCode,
    VehicleRoutingSpec,
)


def _vehicle(**changes: Any) -> VehicleRoutingSpec:
    """Build one complete powered-vehicle routing specification."""

    vehicle = VehicleRoutingSpec(
        vehicle_id=uuid4(),
        is_hgv=True,
        tare_weight_kg=12_000,
        max_gross_weight_kg=22_000,
        length_mm=9_200,
        width_mm=2_500,
        height_mm=3_200,
        axle_count=3,
        max_axle_load_kg=10_000,
        payload_capacity_kg=8_000,
        platform_length_mm=6_000,
        platform_width_mm=2_500,
        platform_height_from_ground_mm=1_300,
        max_platform_payload_kg=7_000,
        max_cargo_length_mm=7_000,
        max_cargo_width_mm=2_600,
        max_cargo_height_mm=3_000,
        max_cargo_weight_kg=6_000,
        can_use_trailer=True,
        combined_length_with_trailer_mm=18_500,
        coupling_length_mm=1_300,
        height_safety_margin_mm=100,
        width_safety_margin_mm=50,
        weight_safety_margin_kg=200,
    )
    return replace(vehicle, **changes)


def _trailer(**changes: Any) -> TrailerSpec:
    """Build one complete compatible trailer routing specification."""

    trailer = TrailerSpec(
        trailer_id=uuid4(),
        tare_weight_kg=3_000,
        max_gross_weight_kg=10_000,
        length_mm=8_000,
        width_mm=2_500,
        height_mm=1_800,
        platform_length_mm=6_000,
        platform_width_mm=2_500,
        platform_height_from_ground_mm=900,
        max_platform_payload_kg=6_000,
        payload_capacity_kg=7_000,
        axle_count=2,
        max_axle_load_kg=10_000,
        max_cargo_length_mm=7_000,
        max_cargo_width_mm=2_600,
        max_cargo_height_mm=3_000,
        max_cargo_weight_kg=6_000,
    )
    return replace(trailer, **changes)


def _cargo(position: CargoPosition, **changes: Any) -> CargoPlacement:
    """Build one identified standard test cargo placement."""

    dimensions = CargoDimensions(
        length_mm=6_000,
        width_mm=2_400,
        height_mm=2_400,
        weight_kg=3_500,
    )
    return CargoPlacement(
        cargo_id=uuid4(),
        position=position,
        dimensions=replace(dimensions, **changes),
    )


def _profiles(**overrides: int) -> tuple[OperationalAxleLoadProfile, ...]:
    """Build exact operational profiles for every supported load configuration."""

    defaults = {
        TruckConfigurationType.EMPTY_TRUCK: 6_000,
        TruckConfigurationType.CARGO_ON_TRUCK: 7_800,
        TruckConfigurationType.EMPTY_COMBINATION: 6_500,
        TruckConfigurationType.CARGO_ON_TRUCK_WITH_TRAILER: 7_900,
        TruckConfigurationType.CARGO_ON_TRAILER_WITH_TRAILER: 7_400,
        TruckConfigurationType.TWO_CARGO_SPLIT: 8_700,
    }
    defaults.update({TruckConfigurationType(key): value for key, value in overrides.items()})
    return tuple(
        OperationalAxleLoadProfile(configuration_type=key, max_actual_axle_load_kg=value)
        for key, value in defaults.items()
    )


def test_one_cargo_uses_platform_height_actual_weight_and_safety_margins() -> None:
    """A loaded truck derives its envelope from platform plus cargo, not cargo alone."""

    vehicle = _vehicle()
    cargo = _cargo(CargoPosition.TRUCK_PLATFORM)

    profile = EffectiveTruckProfileCalculator().calculate(
        vehicle=vehicle,
        load=LoadConfiguration(vehicle_id=vehicle.vehicle_id, cargo_placements=(cargo,)),
        axle_profiles=_profiles(),
    )

    assert profile.configuration_type is TruckConfigurationType.CARGO_ON_TRUCK
    assert profile.height_meters == 3.8
    assert profile.width_meters == 2.55
    assert profile.length_meters == 9.2
    assert profile.actual_weight_tons == 15.7
    assert profile.max_axle_load_tons == 7.8
    assert profile.axle_count == 3
    assert profile.cache_key_data() == profile.cache_key_data()
    snapshot = profile.to_snapshot(provider="valhalla", osm_data_version="2026-08-24")
    assert snapshot["vehicleId"] == str(vehicle.vehicle_id)
    assert snapshot["routingProvider"] == "valhalla"
    assert snapshot["cargoPlacements"] == [
        {
            "cargoId": str(cargo.cargo_id),
            "position": "TRUCK_PLATFORM",
            "lengthMm": 6_000,
            "widthMm": 2_400,
            "heightMm": 2_400,
            "weightKg": 3_500,
        }
    ]


def test_two_cargo_requires_and_changes_to_full_trailer_combination() -> None:
    """Two cargo units occupy separate platforms and produce the long heavy profile."""

    vehicle = _vehicle()
    trailer = _trailer()
    placements = (
        _cargo(CargoPosition.TRUCK_PLATFORM),
        _cargo(CargoPosition.TRAILER_PLATFORM),
    )

    profile = EffectiveTruckProfileCalculator().calculate(
        vehicle=vehicle,
        trailer=trailer,
        load=LoadConfiguration(
            vehicle_id=vehicle.vehicle_id,
            trailer_id=trailer.trailer_id,
            trailer_attached=True,
            cargo_placements=placements,
        ),
        axle_profiles=_profiles(),
    )

    assert profile.configuration_type is TruckConfigurationType.TWO_CARGO_SPLIT
    assert profile.trailer_attached is True
    assert profile.length_meters == 18.5
    assert profile.actual_weight_tons == 22.2
    assert profile.axle_count == 5
    assert profile.max_axle_load_tons == 8.7


def test_empty_trailer_stays_in_profile_after_one_cargo_is_unloaded() -> None:
    """Unloading trailer cargo changes mass but never implicitly detaches the trailer."""

    vehicle = _vehicle()
    trailer = _trailer()
    remaining = _cargo(CargoPosition.TRUCK_PLATFORM)

    profile = EffectiveTruckProfileCalculator().calculate(
        vehicle=vehicle,
        trailer=trailer,
        load=LoadConfiguration(
            vehicle_id=vehicle.vehicle_id,
            trailer_id=trailer.trailer_id,
            trailer_attached=True,
            cargo_placements=(remaining,),
        ),
        axle_profiles=_profiles(),
    )

    assert profile.configuration_type is TruckConfigurationType.CARGO_ON_TRUCK_WITH_TRAILER
    assert profile.has_trailer is True
    assert profile.length_meters == 18.5
    assert profile.actual_weight_tons == 18.7
    assert profile.cargo_count == 1


def test_combined_length_can_be_computed_from_explicit_coupling_length() -> None:
    """An explicit drawbar length is an accepted alternative to pair total length."""

    vehicle = _vehicle(combined_length_with_trailer_mm=None, coupling_length_mm=1_300)
    trailer = _trailer()

    profile = EffectiveTruckProfileCalculator().calculate(
        vehicle=vehicle,
        trailer=trailer,
        load=LoadConfiguration(
            vehicle_id=vehicle.vehicle_id,
            trailer_id=trailer.trailer_id,
            trailer_attached=True,
        ),
        axle_profiles=_profiles(),
    )

    assert profile.length_meters == 18.5


@pytest.mark.parametrize(
    "positions",
    (
        (CargoPosition.TRUCK_PLATFORM, CargoPosition.TRUCK_PLATFORM),
        (CargoPosition.TRAILER_PLATFORM, CargoPosition.TRAILER_PLATFORM),
        (
            CargoPosition.TRUCK_PLATFORM,
            CargoPosition.TRAILER_PLATFORM,
            CargoPosition.TRUCK_PLATFORM,
        ),
    ),
)
def test_each_attached_platform_rejects_a_second_cabin(
    positions: tuple[CargoPosition, ...],
) -> None:
    """An attached trailer never permits stacking two cabins on either platform or a third cabin."""

    vehicle, trailer = _vehicle(), _trailer()
    with pytest.raises(TruckProfileError) as captured:
        EffectiveTruckProfileCalculator().calculate(
            vehicle=vehicle,
            trailer=trailer,
            load=LoadConfiguration(
                vehicle_id=vehicle.vehicle_id,
                trailer_id=trailer.trailer_id,
                trailer_attached=True,
                cargo_placements=tuple(_cargo(position) for position in positions),
            ),
            axle_profiles=_profiles(),
        )
    assert captured.value.code is TruckProfileErrorCode.NO_COMPATIBLE_TRAILER


def test_two_cargo_without_trailer_is_rejected_before_routing() -> None:
    """Two physical units cannot be placed on the supported truck-only configuration."""

    vehicle = _vehicle()
    with pytest.raises(TruckProfileError) as captured:
        EffectiveTruckProfileCalculator().calculate(
            vehicle=vehicle,
            load=LoadConfiguration(
                vehicle_id=vehicle.vehicle_id,
                cargo_placements=(
                    _cargo(CargoPosition.TRUCK_PLATFORM),
                    _cargo(CargoPosition.TRUCK_PLATFORM),
                ),
            ),
            axle_profiles=_profiles(),
        )

    assert captured.value.code is TruckProfileErrorCode.TRAILER_REQUIRED


@pytest.mark.parametrize(
    ("dimension_change", "expected_code"),
    [
        ({"length_mm": 7_100}, TruckProfileErrorCode.CARGO_TOO_LONG),
        ({"width_mm": 2_700}, TruckProfileErrorCode.CARGO_TOO_WIDE),
        ({"height_mm": 3_100}, TruckProfileErrorCode.CARGO_TOO_HIGH),
        ({"weight_kg": 6_100}, TruckProfileErrorCode.CARGO_TOO_HEAVY),
    ],
)
def test_explicit_cargo_limits_return_specific_errors(
    dimension_change: dict[str, Any], expected_code: TruckProfileErrorCode
) -> None:
    """Each carrier compatibility failure retains its actionable domain code."""

    vehicle = _vehicle()
    with pytest.raises(TruckProfileError) as captured:
        EffectiveTruckProfileCalculator().calculate(
            vehicle=vehicle,
            load=LoadConfiguration(
                vehicle_id=vehicle.vehicle_id,
                cargo_placements=(_cargo(CargoPosition.TRUCK_PLATFORM, **dimension_change),),
            ),
            axle_profiles=_profiles(),
        )

    assert captured.value.code is expected_code


def test_peak_axle_load_is_configured_and_never_averaged() -> None:
    """A measured peak above equipment capability fails instead of using weight per axle."""

    vehicle = _vehicle(max_axle_load_kg=8_000)
    with pytest.raises(TruckProfileError) as captured:
        EffectiveTruckProfileCalculator().calculate(
            vehicle=vehicle,
            load=LoadConfiguration(
                vehicle_id=vehicle.vehicle_id,
                cargo_placements=(_cargo(CargoPosition.TRUCK_PLATFORM),),
            ),
            axle_profiles=_profiles(CARGO_ON_TRUCK=8_500),
        )

    assert captured.value.code is TruckProfileErrorCode.AXLE_LOAD_EXCEEDED


def test_combination_peak_is_not_compared_to_every_trailer_axle_rating() -> None:
    """A measured truck peak can exceed a lighter trailer's own axle rating."""

    vehicle = _vehicle()
    trailer = _trailer(
        tare_weight_kg=1_080,
        max_gross_weight_kg=3_500,
        payload_capacity_kg=2_420,
        max_platform_payload_kg=2_420,
        max_axle_load_kg=1_675,
        max_cargo_weight_kg=2_420,
    )
    placements = (
        _cargo(CargoPosition.TRUCK_PLATFORM, weight_kg=1_200),
        _cargo(CargoPosition.TRAILER_PLATFORM, weight_kg=1_200),
    )

    profile = EffectiveTruckProfileCalculator().calculate(
        vehicle=vehicle,
        trailer=trailer,
        load=LoadConfiguration(
            vehicle_id=vehicle.vehicle_id,
            trailer_id=trailer.trailer_id,
            trailer_attached=True,
            cargo_placements=placements,
        ),
        axle_profiles=_profiles(TWO_CARGO_SPLIT=7_400),
    )

    assert profile.max_axle_load_tons == 7.4


def test_missing_physical_values_report_exact_fields() -> None:
    """Incomplete legacy vehicles fail safely and identify every critical missing value."""

    vehicle = _vehicle(height_mm=None, platform_height_from_ground_mm=None)
    with pytest.raises(TruckProfileError) as captured:
        EffectiveTruckProfileCalculator().calculate(
            vehicle=vehicle,
            load=LoadConfiguration(
                vehicle_id=vehicle.vehicle_id,
                cargo_placements=(_cargo(CargoPosition.TRUCK_PLATFORM),),
            ),
            axle_profiles=_profiles(),
        )

    assert captured.value.code is TruckProfileErrorCode.ROUTING_PROFILE_INCOMPLETE
    assert captured.value.missing_fields == (
        "vehicle.height_mm",
        "vehicle.platform_height_from_ground_mm",
    )


def test_attached_trailer_without_combination_length_is_incomplete() -> None:
    """The calculator never invents a truck-and-trailer road length."""

    vehicle = _vehicle(
        combined_length_with_trailer_mm=None,
        coupling_length_mm=None,
    )
    trailer = _trailer()
    with pytest.raises(TruckProfileError) as captured:
        EffectiveTruckProfileCalculator().calculate(
            vehicle=vehicle,
            trailer=trailer,
            load=LoadConfiguration(
                vehicle_id=vehicle.vehicle_id,
                trailer_id=trailer.trailer_id,
                trailer_attached=True,
            ),
            axle_profiles=_profiles(),
        )

    assert captured.value.code is TruckProfileErrorCode.ROUTING_PROFILE_INCOMPLETE
    assert captured.value.missing_fields == ("vehicle.coupling_length_mm",)
