"""Unit tests for physical per-leg effective truck profile calculation."""

from __future__ import annotations

from dataclasses import replace
from decimal import Decimal
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


def D(value: str) -> Decimal:
    """Build one exact canonical decimal in tests."""

    return Decimal(value)


def _vehicle(**changes: Any) -> VehicleRoutingSpec:
    """Build one complete powered-vehicle routing specification."""

    vehicle = VehicleRoutingSpec(
        vehicle_id=uuid4(),
        tare_weight_t=D("12"),
        max_gross_weight_t=D("22"),
        length_m=D("9.2"),
        width_m=D("2.5"),
        height_m=D("3.2"),
        axle_count=3,
        axle_load_t=D("10"),
        payload_capacity_t=D("8"),
        platform_length_m=D("6"),
        platform_height_from_ground_m=D("1.3"),
        max_platform_payload_t=D("7"),
        max_cargo_length_m=D("7"),
        max_cargo_width_m=D("2.6"),
        max_cargo_height_m=D("3"),
        max_cargo_weight_t=D("6"),
        has_trailer=True,
        coupling_length_m=D("1.3"),
        height_safety_margin_m=D("0.1"),
        width_safety_margin_m=D("0.05"),
        weight_safety_margin_t=D("0.2"),
    )
    return replace(vehicle, **changes)


def _trailer(**changes: Any) -> TrailerSpec:
    """Build one complete compatible trailer routing specification."""

    trailer = TrailerSpec(
        trailer_id=uuid4(),
        tare_weight_t=D("3"),
        max_gross_weight_t=D("10"),
        length_m=D("8"),
        width_m=D("2.5"),
        height_m=D("1.8"),
        platform_length_m=D("6"),
        platform_height_from_ground_m=D("0.9"),
        max_platform_payload_t=D("6"),
        payload_capacity_t=D("7"),
        axle_count=2,
        axle_load_t=D("10"),
        max_cargo_length_m=D("7"),
        max_cargo_width_m=D("2.6"),
        max_cargo_height_m=D("3"),
        max_cargo_weight_t=D("6"),
    )
    return replace(trailer, **changes)


def _cargo(position: CargoPosition, **changes: Any) -> CargoPlacement:
    """Build one identified standard test cargo placement."""

    dimensions = CargoDimensions(
        length_m=D("6"),
        width_m=D("2.4"),
        height_m=D("2.4"),
        weight_t=D("3.5"),
    )
    return CargoPlacement(
        cargo_id=uuid4(),
        position=position,
        dimensions=replace(dimensions, **changes),
    )


def _profiles(**overrides: str) -> tuple[OperationalAxleLoadProfile, ...]:
    """Build exact operational profiles for every supported load configuration."""

    defaults = {
        TruckConfigurationType.EMPTY_TRUCK: D("6"),
        TruckConfigurationType.CARGO_ON_TRUCK: D("7.8"),
        TruckConfigurationType.EMPTY_COMBINATION: D("6.5"),
        TruckConfigurationType.CARGO_ON_TRUCK_WITH_TRAILER: D("7.9"),
        TruckConfigurationType.CARGO_ON_TRAILER_WITH_TRAILER: D("7.4"),
        TruckConfigurationType.TWO_CARGO_SPLIT: D("8.7"),
    }
    defaults.update({TruckConfigurationType(key): D(value) for key, value in overrides.items()})
    return tuple(
        OperationalAxleLoadProfile(configuration_type=key, max_actual_axle_load_t=value)
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
            "lengthM": 6.0,
            "widthM": 2.4,
            "heightM": 2.4,
            "weightT": 3.5,
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


def test_combination_length_is_computed_from_explicit_coupling_length() -> None:
    """The road length is derived from vehicle, coupling and trailer lengths."""

    vehicle = _vehicle()
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
        ({"length_m": D("7.1")}, TruckProfileErrorCode.CARGO_TOO_LONG),
        ({"width_m": D("2.7")}, TruckProfileErrorCode.CARGO_TOO_WIDE),
        ({"height_m": D("3.1")}, TruckProfileErrorCode.CARGO_TOO_HIGH),
        ({"weight_t": D("6.1")}, TruckProfileErrorCode.CARGO_TOO_HEAVY),
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

    vehicle = _vehicle(axle_load_t=D("8"))
    with pytest.raises(TruckProfileError) as captured:
        EffectiveTruckProfileCalculator().calculate(
            vehicle=vehicle,
            load=LoadConfiguration(
                vehicle_id=vehicle.vehicle_id,
                cargo_placements=(_cargo(CargoPosition.TRUCK_PLATFORM),),
            ),
            axle_profiles=_profiles(CARGO_ON_TRUCK="8.5"),
        )

    assert captured.value.code is TruckProfileErrorCode.AXLE_LOAD_EXCEEDED


def test_combination_peak_is_not_compared_to_every_trailer_axle_rating() -> None:
    """A measured truck peak can exceed a lighter trailer's own axle rating."""

    vehicle = _vehicle()
    trailer = _trailer(
        tare_weight_t=D("1.08"),
        max_gross_weight_t=D("3.5"),
        payload_capacity_t=D("2.42"),
        max_platform_payload_t=D("2.42"),
        axle_load_t=D("1.675"),
        max_cargo_weight_t=D("2.42"),
    )
    placements = (
        _cargo(CargoPosition.TRUCK_PLATFORM, weight_t=D("1.2")),
        _cargo(CargoPosition.TRAILER_PLATFORM, weight_t=D("1.2")),
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
        axle_profiles=_profiles(TWO_CARGO_SPLIT="7.4"),
    )

    assert profile.max_axle_load_tons == 7.4


def test_missing_physical_values_report_exact_fields() -> None:
    """Incomplete vehicles fail safely and identify every critical missing value."""

    vehicle = _vehicle(height_m=None, platform_height_from_ground_m=None)
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
        "vehicle.height_m",
        "vehicle.platform_height_from_ground_m",
    )


def test_attached_trailer_without_coupling_length_is_incomplete() -> None:
    """The calculator never invents a truck-and-trailer road length."""

    vehicle = _vehicle(coupling_length_m=None)
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
    assert captured.value.missing_fields == ("vehicle.coupling_length_m",)
