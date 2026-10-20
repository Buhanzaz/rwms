"""Deterministic end-to-end scenario for a solo truck and a truck with trailer.

The scenario creates both canonical vehicles through the owning catalog service,
serializes them, selects the effective cabin capacity and exact truck profile,
and finally runs the pure simulation engine over one planned cycle per truck.
It requires a PostgreSQL/PostGIS test database and mock routing only; no
Valhalla or network access is used.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from decimal import Decimal
from uuid import UUID
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.domain.vehicle import vehicle_specification
from app.models import Trailer, Vehicle, VehicleLoadProfileType
from app.planner import PlannedLeg, RouteCycle, RouteStop, StopType
from app.routing import GeoPoint
from app.routing.truck_profile import (
    CargoDimensions,
    CargoPlacement,
    CargoPosition,
    EffectiveTruckProfileCalculator,
    LoadConfiguration,
    TruckProfileError,
    TruckProfileErrorCode,
)
from app.schemas.domain import (
    TrailerCreate,
    VehicleConfigurationCreate,
    VehicleCreate,
    VehicleLoadProfileCreate,
    VehicleRead,
)
from app.services import catalog
from app.simulation import SimulationStatus, derive_simulation_state
from app.slot_planning.configuration import (
    effective_vehicle_cabin_capacity,
    vehicle_equipment_snapshot,
)
from tests.factories import make_driver, make_shift, make_warehouse

pytestmark = pytest.mark.integration

MOSCOW = ZoneInfo("Europe/Moscow")
COMPLETE_LOAD_STATES: tuple[tuple[VehicleLoadProfileType, str], ...] = (
    ("EMPTY_TRUCK", "6"),
    ("CARGO_ON_TRUCK", "7.8"),
    ("EMPTY_COMBINATION", "6.5"),
    ("CARGO_ON_TRUCK_WITH_TRAILER", "7.9"),
    ("CARGO_ON_TRAILER_WITH_TRAILER", "7.4"),
    ("TWO_CARGO_SPLIT", "8.7"),
)


async def _make_trailer(session: AsyncSession, warehouse_id: UUID) -> Trailer:
    """Persist one complete warehouse trailer used by the combination scenario."""

    return await catalog.create_trailer(
        session,
        warehouse_id,
        TrailerCreate(
            name="Сценарный прицеп",
            registration_number="SCN-TRL-01",
            active=True,
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
        ),
    )


async def _make_vehicle(
    session: AsyncSession,
    warehouse_id: UUID,
    *,
    name: str,
    registration_number: str,
    trailer: Trailer | None,
) -> Vehicle:
    """Persist one canonical vehicle, optionally with its default trailer."""

    return await catalog.create_vehicle_configuration(
        session,
        warehouse_id,
        VehicleConfigurationCreate(
            vehicle=VehicleCreate(
                name=name,
                registration_number=registration_number,
                capacity=2,
                is_hgv=True,
                tare_weight_t=Decimal("12"),
                max_gross_weight_t=Decimal("22"),
                axle_load_t=Decimal("10"),
                payload_capacity_t=Decimal("8"),
                length_m=Decimal("9.2"),
                width_m=Decimal("2.5"),
                height_m=Decimal("3.2"),
                has_trailer=trailer is not None,
                default_trailer_id=trailer.id if trailer is not None else None,
                axle_count=3,
                platform_length_mm=6_000,
                platform_width_mm=2_500,
                platform_height_from_ground_mm=1_300,
                max_platform_payload_kg=7_000,
                max_cargo_length_mm=7_000,
                max_cargo_width_mm=2_600,
                max_cargo_height_mm=3_000,
                max_cargo_weight_kg=6_000,
                combined_length_with_trailer_mm=18_500 if trailer is not None else None,
                coupling_length_mm=1_300,
                height_safety_margin_mm=100,
                width_safety_margin_mm=50,
                weight_safety_margin_kg=200,
            ),
            load_profiles=[
                VehicleLoadProfileCreate(
                    configuration_type=state,
                    max_actual_axle_load_t=Decimal(tonnes),
                )
                for state, tonnes in COMPLETE_LOAD_STATES
            ],
        ),
    )


def _simulation_cycle(
    *,
    cycle_id: str,
    shift_id: str,
    driver_id: str,
    vehicle_id: str,
    delivery_load: int,
) -> RouteCycle:
    """Build one deterministic depot-delivery-depot cycle for the simulation engine."""

    start = datetime(2026, 9, 14, 8, tzinfo=MOSCOW)
    depot = GeoPoint(30.32, 59.93, True)
    delivery = GeoPoint(30.42, 59.96, True)
    stops = (
        RouteStop(
            0,
            StopType.DEPOT_LOAD,
            depot,
            start,
            start + timedelta(minutes=10),
            600,
            delivery_load,
            0,
            delivery_load,
            address_label="Склад",
        ),
        RouteStop(
            1,
            StopType.DELIVERY,
            delivery,
            start + timedelta(minutes=70),
            start + timedelta(minutes=80),
            600,
            -delivery_load,
            delivery_load,
            0,
            task_id=f"task-{cycle_id}",
            request_id=f"request-{cycle_id}",
            address_label="Бытовка",
        ),
        RouteStop(
            2,
            StopType.DEPOT_RETURN,
            depot,
            start + timedelta(minutes=150),
            start + timedelta(minutes=160),
            600,
            0,
            0,
            0,
            address_label="Склад",
        ),
    )
    geometry = {
        "type": "LineString",
        "coordinates": [list(depot.coordinates), list(delivery.coordinates)],
    }
    legs = (
        PlannedLeg(
            0,
            1,
            stops[0].planned_departure,
            stops[1].planned_arrival,
            10_000,
            3_600,
            geometry,
        ),
        PlannedLeg(
            1,
            2,
            stops[1].planned_departure,
            stops[2].planned_arrival,
            10_000,
            3_600,
            geometry,
        ),
    )
    return RouteCycle(
        id=cycle_id,
        driver_shift_id=shift_id,
        driver_id=driver_id,
        vehicle_id=vehicle_id,
        sequence=1,
        planned_start=start,
        planned_finish=start + timedelta(minutes=160),
        stops=stops,
        legs=legs,
        total_distance_meters=20_000,
        total_travel_seconds=7_200,
        total_service_seconds=1_800,
        waiting_seconds=0,
        empty_distance_meters=10_000,
        detour_seconds=0,
        score=10,
    )


@pytest.mark.asyncio
async def test_canonical_solo_and_trailer_trucks_route_and_simulate(
    db_session: AsyncSession,
) -> None:
    """Tonnes/metres drive equipment selection, limits and deterministic simulation."""

    warehouse = await make_warehouse(db_session)
    driver = await make_driver(db_session, warehouse)
    trailer = await _make_trailer(db_session, warehouse.id)
    solo = await _make_vehicle(
        db_session,
        warehouse.id,
        name="Одиночная машина",
        registration_number="SCN-SOLO-01",
        trailer=None,
    )
    combination = await _make_vehicle(
        db_session,
        warehouse.id,
        name="Автопоезд",
        registration_number="SCN-COMBO-01",
        trailer=trailer,
    )
    await make_shift(db_session, warehouse, driver, solo)

    # 1. Serialization: quantities are exposed in canonical tonnes/metres.
    solo_read = VehicleRead.model_validate(solo).model_dump(mode="json")
    combination_read = VehicleRead.model_validate(combination).model_dump(mode="json")
    assert solo_read["tare_weight_t"] == 12.0
    assert solo_read["length_m"] == 9.2
    assert solo_read["has_trailer"] is False
    assert combination_read["has_trailer"] is True
    assert combination_read["default_trailer_id"] == str(trailer.id)
    assert combination_read["tare_weight_kg"] == 12_000

    # 2. Selection: a trailer-capable combination exposes two cabin places.
    solo_equipment = vehicle_equipment_snapshot(solo, {})
    combination_equipment = vehicle_equipment_snapshot(combination, {})
    assert vehicle_specification(solo).length_m == Decimal("9.2")
    assert effective_vehicle_cabin_capacity(solo) == 1
    assert effective_vehicle_cabin_capacity(combination) == 2
    assert solo_equipment.trailer is None
    assert combination_equipment.trailer is not None

    # 3. Mass and dimension limits: numbers derived from the canonical model.
    calculator = EffectiveTruckProfileCalculator()
    solo_cargo = CargoPlacement(
        cargo_id="cabin-solo",
        position=CargoPosition.TRUCK_PLATFORM,
        dimensions=solo_equipment.cabin,
    )
    solo_profile = calculator.calculate(
        vehicle=solo_equipment.vehicle,
        load=LoadConfiguration(
            vehicle_id=str(solo.id),
            cargo_placements=(solo_cargo,),
        ),
        axle_profiles=solo_equipment.axle_profiles,
    )
    # platform 1.3 + cabin 2.4 + height margin 0.1
    assert solo_profile.height_meters == pytest.approx(3.8)
    assert solo_profile.length_meters == pytest.approx(9.2)
    assert solo_profile.actual_weight_tons == pytest.approx(13.4)  # 12 + 1.2 + 0.2
    assert solo_profile.axle_count == 3

    trailer_cargo = CargoPlacement(
        cargo_id="cabin-trailer",
        position=CargoPosition.TRAILER_PLATFORM,
        dimensions=combination_equipment.cabin,
    )
    combination_profile = calculator.calculate(
        vehicle=combination_equipment.vehicle,
        load=LoadConfiguration(
            vehicle_id=str(combination.id),
            trailer_attached=True,
            trailer_id=str(trailer.id),
            cargo_placements=(solo_cargo, trailer_cargo),
        ),
        axle_profiles=combination_equipment.axle_profiles,
        trailer=combination_equipment.trailer,
    )
    assert combination_profile.length_meters == pytest.approx(18.5)
    assert combination_profile.axle_count == 5
    assert combination_profile.actual_weight_tons == pytest.approx(17.6)
    assert combination_profile.max_axle_load_tons == pytest.approx(8.7)
    with pytest.raises(TruckProfileError) as too_heavy:
        calculator.calculate(
            vehicle=solo_equipment.vehicle,
            load=LoadConfiguration(
                vehicle_id=str(solo.id),
                cargo_placements=(
                    CargoPlacement(
                        cargo_id="oversized",
                        position=CargoPosition.TRUCK_PLATFORM,
                        dimensions=CargoDimensions(
                            length_mm=6_000,
                            width_mm=2_400,
                            height_mm=2_400,
                            weight_kg=20_000,
                        ),
                    ),
                ),
            ),
            axle_profiles=solo_equipment.axle_profiles,
        )
    assert too_heavy.value.code is TruckProfileErrorCode.CARGO_TOO_HEAVY

    # 4. Simulation: both planned trucks are derived deterministically.
    cycles = (
        _simulation_cycle(
            cycle_id="cycle-solo",
            shift_id="shift-solo",
            driver_id=str(driver.id),
            vehicle_id=str(solo.id),
            delivery_load=1,
        ),
        _simulation_cycle(
            cycle_id="cycle-combo",
            shift_id="shift-combo",
            driver_id=str(driver.id),
            vehicle_id=str(combination.id),
            delivery_load=2,
        ),
    )
    timestamp = datetime(2026, 9, 14, 9, 0, tzinfo=MOSCOW)
    first = derive_simulation_state(cycles, timestamp)
    second = derive_simulation_state(cycles, timestamp)
    assert first == second
    assert {vehicle.vehicle_id for vehicle in first.vehicles} == {
        str(solo.id),
        str(combination.id),
    }
    assert all(vehicle.status is SimulationStatus.DRIVING for vehicle in first.vehicles)
    assert {vehicle.current_load for vehicle in first.vehicles} == {1, 2}
