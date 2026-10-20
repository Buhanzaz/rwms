"""One equipment-availability policy across slots, day routes, and publication."""

from decimal import Decimal
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
@pytest.mark.parametrize("has_trailer", (True, False, None))
def test_every_equipment_projection_excludes_unavailable_trailer(
    active: bool,
    has_trailer: bool | None,
) -> None:
    """An inactive trailer cannot reach exact routing even through an existing assignment."""

    trailer = Trailer(id=uuid4(), active=active)
    vehicle = Vehicle(
        id=uuid4(),
        capacity=2,
        has_trailer=has_trailer,
        default_trailer=trailer,
        load_profiles=[],
    )
    available = active and has_trailer is True

    assert vehicle_has_available_trailer(vehicle) is available
    assert effective_vehicle_cabin_capacity(vehicle) == (2 if available else 1)
    assert (vehicle_equipment_snapshot(vehicle, {}).trailer is not None) is available
    assert (RuntimePlannerFacade._core_vehicle(vehicle).default_trailer is not None) is available


def test_equipment_specs_are_identical_at_slot_and_day_planner_boundaries() -> None:
    """Both routing callers retain every nullable canonical equipment value."""

    trailer = Trailer(
        id=uuid4(),
        active=True,
        tare_weight_t=Decimal("2.101"),
        max_gross_weight_t=Decimal("8.102"),
        length_m=Decimal("7.103"),
        width_m=Decimal("2.104"),
        height_m=None,
        platform_length_m=Decimal("6.106"),
        platform_height_from_ground_m=Decimal("1.108"),
        max_platform_payload_t=Decimal("5.109"),
        payload_capacity_t=Decimal("5.110"),
        axle_count=2,
        axle_load_t=Decimal("4.112"),
        max_cargo_length_m=Decimal("6.113"),
        max_cargo_width_m=Decimal("2.114"),
        max_cargo_height_m=Decimal("2.115"),
        max_cargo_weight_t=Decimal("5.116"),
    )
    vehicle = Vehicle(
        id=uuid4(),
        name="Mapped truck",
        capacity=2,
        active=True,
        is_hgv=True,
        tare_weight_t=Decimal("7.201"),
        max_gross_weight_t=Decimal("18.202"),
        length_m=Decimal("9.203"),
        width_m=Decimal("2.204"),
        height_m=None,
        axle_count=3,
        axle_load_t=Decimal("7.207"),
        payload_capacity_t=Decimal("10.208"),
        platform_length_m=Decimal("6.209"),
        platform_height_from_ground_m=Decimal("1.211"),
        max_platform_payload_t=Decimal("9.212"),
        max_cargo_length_m=Decimal("6.213"),
        max_cargo_width_m=Decimal("2.214"),
        max_cargo_height_m=Decimal("2.215"),
        max_cargo_weight_t=Decimal("9.216"),
        has_trailer=True,
        coupling_length_m=Decimal("1.219"),
        height_safety_margin_m=Decimal("0.220"),
        width_safety_margin_m=Decimal("0.221"),
        weight_safety_margin_t=Decimal("0.222"),
        default_trailer=trailer,
        load_profiles=[],
    )

    vehicle_spec = vehicle_routing_spec(vehicle)
    trailer_spec = trailer_routing_spec(trailer)
    slot_snapshot = vehicle_equipment_snapshot(vehicle, {})
    planner_vehicle = RuntimePlannerFacade._core_vehicle(vehicle)

    assert vehicle_spec.tare_weight_t == Decimal("7.201")
    assert vehicle_spec.height_m is None
    assert vehicle_spec.weight_safety_margin_t == Decimal("0.222")
    assert trailer_spec.height_m is None
    assert trailer_spec.max_cargo_weight_t == Decimal("5.116")
    assert slot_snapshot.vehicle == vehicle_spec == planner_vehicle.routing_spec
    assert slot_snapshot.trailer == trailer_spec == planner_vehicle.default_trailer


@pytest.mark.parametrize("catalog_capacity", (1, 2, 3, 10))
@pytest.mark.parametrize("incident", (False, True))
def test_each_platform_holds_at_most_one_cabin(catalog_capacity: int, incident: bool) -> None:
    """A catalog number never creates more than one truck and one trailer platform."""

    trailer = Trailer(id=uuid4(), active=True)
    vehicle = Vehicle(
        id=uuid4(),
        name="Truck",
        capacity=catalog_capacity,
        active=True,
        has_trailer=True,
        default_trailer=trailer,
        load_profiles=[],
    )
    excluded = {str(trailer.id)} if incident else set()
    expected = 1 if incident else min(2, catalog_capacity)
    assert effective_vehicle_cabin_capacity(vehicle, excluded) == expected
    assert RuntimePlannerFacade._core_vehicle(vehicle, excluded).capacity == expected
    assert (vehicle_equipment_snapshot(vehicle, {}, excluded).trailer is None) is incident
