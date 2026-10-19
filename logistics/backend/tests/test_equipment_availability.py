"""One equipment-availability policy across slots, day routes, and publication."""

from dataclasses import fields
from uuid import uuid4

import pytest

from app.models import Trailer, Vehicle
from app.services.planner_runtime import RuntimePlannerFacade
from app.slot_planning.configuration import (
    effective_vehicle_cabin_capacity,
    trailer_routing_spec,
    vehicle_equipment_snapshot,
    vehicle_has_available_trailer,
    vehicle_routing_spec,
)


@pytest.mark.parametrize("active", (True, False))
@pytest.mark.parametrize("can_use_trailer", (True, False, None))
def test_every_equipment_projection_excludes_unavailable_trailer(
    active: bool,
    can_use_trailer: bool | None,
) -> None:
    """An inactive trailer cannot reach exact routing even through an existing assignment."""

    trailer = Trailer(id=uuid4(), active=active)
    vehicle = Vehicle(
        id=uuid4(),
        capacity=2,
        can_use_trailer=can_use_trailer,
        default_trailer=trailer,
        load_profiles=[],
    )
    available = active and can_use_trailer is True

    assert vehicle_has_available_trailer(vehicle) is available
    assert effective_vehicle_cabin_capacity(vehicle) == (2 if available else 1)
    assert (vehicle_equipment_snapshot(vehicle, {}).trailer is not None) is available
    assert (RuntimePlannerFacade._core_vehicle(vehicle).default_trailer is not None) is available


def test_equipment_specs_are_identical_at_slot_and_day_planner_boundaries() -> None:
    """Both routing callers retain every nullable persisted equipment value."""

    trailer = Trailer(
        id=uuid4(),
        active=True,
        tare_weight_kg=2_101,
        max_gross_weight_kg=8_102,
        length_mm=7_103,
        width_mm=2_104,
        height_mm=None,
        platform_length_mm=6_106,
        platform_width_mm=2_107,
        platform_height_from_ground_mm=1_108,
        max_platform_payload_kg=5_109,
        payload_capacity_kg=5_110,
        axle_count=2,
        max_axle_load_kg=4_112,
        max_cargo_length_mm=6_113,
        max_cargo_width_mm=2_114,
        max_cargo_height_mm=2_115,
        max_cargo_weight_kg=5_116,
    )
    vehicle = Vehicle(
        id=uuid4(),
        name="Mapped truck",
        capacity=2,
        active=True,
        is_hgv=True,
        tare_weight_kg=7_201,
        max_gross_weight_kg=18_202,
        length_mm=9_203,
        width_mm=2_204,
        height_mm=None,
        axle_count=3,
        max_axle_load_kg=7_207,
        payload_capacity_kg=10_208,
        platform_length_mm=6_209,
        platform_width_mm=2_210,
        platform_height_from_ground_mm=1_211,
        max_platform_payload_kg=9_212,
        max_cargo_length_mm=6_213,
        max_cargo_width_mm=2_214,
        max_cargo_height_mm=2_215,
        max_cargo_weight_kg=9_216,
        can_use_trailer=True,
        combined_length_with_trailer_mm=16_218,
        coupling_length_mm=1_219,
        height_safety_margin_mm=220,
        width_safety_margin_mm=221,
        weight_safety_margin_kg=222,
        default_trailer=trailer,
        load_profiles=[],
    )

    vehicle_spec = vehicle_routing_spec(vehicle)
    trailer_spec = trailer_routing_spec(trailer)
    slot_snapshot = vehicle_equipment_snapshot(vehicle, {})
    planner_vehicle = RuntimePlannerFacade._core_vehicle(vehicle)

    assert {
        field.name: getattr(vehicle_spec, field.name) for field in fields(vehicle_spec)
    } == {
        field.name: getattr(vehicle, "id" if field.name == "vehicle_id" else field.name)
        for field in fields(vehicle_spec)
    }
    assert {
        field.name: getattr(trailer_spec, field.name) for field in fields(trailer_spec)
    } == {
        field.name: getattr(trailer, "id" if field.name == "trailer_id" else field.name)
        for field in fields(trailer_spec)
    }
    assert slot_snapshot.vehicle == vehicle_spec == planner_vehicle.routing_spec
    assert slot_snapshot.trailer == trailer_spec == planner_vehicle.default_trailer
    assert vehicle_spec.height_mm is None
    assert vehicle_spec.weight_safety_margin_kg == 222
    assert trailer_spec.height_mm is None
    assert trailer_spec.max_cargo_weight_kg == 5_116


@pytest.mark.parametrize("catalog_capacity", (1, 2, 3, 10))
@pytest.mark.parametrize("incident", (False, True))
def test_each_platform_holds_at_most_one_cabin(catalog_capacity: int, incident: bool) -> None:
    """A catalog number never creates more than one truck and one trailer platform."""

    trailer = Trailer(id=uuid4(), active=True)
    vehicle = Vehicle(
        id=uuid4(), name="Truck", capacity=catalog_capacity, active=True,
        can_use_trailer=True, default_trailer=trailer, load_profiles=[],
    )
    excluded = {str(trailer.id)} if incident else set()
    expected = 1 if incident else min(2, catalog_capacity)
    assert effective_vehicle_cabin_capacity(vehicle, excluded) == expected
    assert RuntimePlannerFacade._core_vehicle(vehicle, excluded).capacity == expected
    assert (vehicle_equipment_snapshot(vehicle, {}, excluded).trailer is None) is incident
