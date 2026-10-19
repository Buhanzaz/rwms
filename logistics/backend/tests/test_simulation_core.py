"""Tests for pure timestamp-derived movement and disruption overrides."""

from __future__ import annotations

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pytest

from app.planner import PlannedLeg, RouteCycle, RouteStop, StopType
from app.routing import GeoPoint
from app.simulation import (
    DelayOverride,
    DriverUnavailableOverride,
    SimulationStatus,
    derive_simulation_state,
    interpolate_route_position,
    propagate_delays,
)

MOSCOW = ZoneInfo("Europe/Moscow")


def at(hour: int, minute: int = 0) -> datetime:
    return datetime(2026, 8, 25, hour, minute, tzinfo=MOSCOW)


def line(*points: GeoPoint) -> dict[str, object]:
    return {
        "type": "LineString",
        "coordinates": [list(point.coordinates) for point in points],
    }


def sample_cycle() -> RouteCycle:
    depot = GeoPoint(37.60, 55.70, True)
    delivery = GeoPoint(37.80, 55.75, True)
    pickup = GeoPoint(37.70, 55.73, True)
    stops = (
        RouteStop(
            0,
            StopType.DEPOT_LOAD,
            depot,
            at(8),
            at(8, 10),
            600,
            2,
            0,
            2,
            address_label="Склад",
        ),
        RouteStop(
            1,
            StopType.DELIVERY,
            delivery,
            at(9, 10),
            at(9, 20),
            600,
            -1,
            2,
            1,
            task_id="delivery-task",
            request_id="delivery",
            address_label="Доставка",
        ),
        RouteStop(
            2,
            StopType.PICKUP,
            pickup,
            at(10),
            at(10, 10),
            600,
            1,
            1,
            2,
            task_id="pickup-task",
            request_id="pickup",
            address_label="Вывоз",
        ),
        RouteStop(
            3,
            StopType.DEPOT_RETURN,
            depot,
            at(11),
            at(11, 10),
            600,
            -2,
            2,
            0,
            address_label="Склад",
        ),
    )
    legs = (
        PlannedLeg(0, 1, at(8, 10), at(9, 10), 20_000, 3_600, line(depot, delivery)),
        PlannedLeg(1, 2, at(9, 20), at(10), 10_000, 2_400, line(delivery, pickup)),
        PlannedLeg(2, 3, at(10, 10), at(11), 12_000, 3_000, line(pickup, depot)),
    )
    return RouteCycle(
        id="cycle-1",
        driver_shift_id="shift-1",
        driver_id="driver-1",
        vehicle_id="vehicle-1",
        sequence=1,
        planned_start=at(8),
        planned_finish=at(11, 10),
        stops=stops,
        legs=legs,
        total_distance_meters=42_000,
        total_travel_seconds=9_000,
        total_service_seconds=2_400,
        waiting_seconds=0,
        empty_distance_meters=10_000,
        detour_seconds=600,
        score=100,
    )


def test_interpolation_uses_segment_distance_not_vertex_index() -> None:
    geometry = line(
        GeoPoint(0.0, 0.0),
        GeoPoint(0.1, 0.0),
        GeoPoint(1.0, 0.0),
    )

    halfway = interpolate_route_position(geometry, 0.5)

    assert halfway.lon == pytest.approx(0.5, abs=0.001)
    assert halfway.lat == pytest.approx(0.0)


def test_vehicle_moves_and_load_matches_last_completed_transition() -> None:
    cycle = sample_cycle()

    driving_out = derive_simulation_state((cycle,), at(8, 40)).vehicles[0]
    servicing = derive_simulation_state((cycle,), at(9, 15)).vehicles[0]
    driving_to_pickup = derive_simulation_state((cycle,), at(9, 30)).vehicles[0]

    assert driving_out.status is SimulationStatus.DRIVING
    assert depot_lon(cycle) < driving_out.position.lon < cycle.stops[1].point.lon
    assert driving_out.current_load == 2
    assert servicing.status is SimulationStatus.DELIVERING
    assert servicing.current_load == 2
    assert driving_to_pickup.current_load == 1
    assert driving_to_pickup.next_address == "Вывоз"


def test_backward_then_forward_seek_returns_identical_state() -> None:
    cycle = sample_cycle()

    later_first = derive_simulation_state((cycle,), at(10, 30))
    earlier = derive_simulation_state((cycle,), at(8, 30))
    later_second = derive_simulation_state((cycle,), at(10, 30))

    assert earlier != later_first
    assert later_second == later_first


def test_delay_shifts_selected_departure_and_every_subsequent_eta() -> None:
    cycle = sample_cycle()
    delay = DelayOverride(
        "delay-1",
        "shift-1",
        timedelta(minutes=30),
        cycle_id="cycle-1",
        stop_sequence=1,
        reason="Погрузка затянулась",
    )

    shifted = propagate_delays((cycle,), (delay,))[0]

    assert cycle.stops[1].planned_departure == at(9, 20)
    assert shifted.stops[1].planned_arrival == at(9, 10)
    assert shifted.stops[1].planned_departure == at(9, 50)
    assert shifted.stops[2].planned_arrival == at(10, 30)
    assert shifted.planned_finish == at(11, 40)
    snapshot = derive_simulation_state((cycle,), at(9, 30), (delay,))
    assert snapshot.vehicles[0].status is SimulationStatus.DELAYED
    assert snapshot.vehicles[0].schedule_offset_seconds == 1_800


def test_delay_inside_active_leg_extends_arrival_without_moving_departure() -> None:
    cycle = sample_cycle()
    delay = DelayOverride(
        "traffic",
        "shift-1",
        timedelta(minutes=20),
        effective_at=at(8, 40),
    )

    shifted = propagate_delays((cycle,), (delay,))[0]

    assert shifted.legs[0].departure_at == at(8, 10)
    assert shifted.legs[0].arrival_at == at(9, 30)
    assert shifted.stops[1].planned_arrival == at(9, 30)
    assert shifted.planned_finish == at(11, 30)


def test_driver_unavailability_freezes_position_at_event_time() -> None:
    cycle = sample_cycle()
    unavailable = DriverUnavailableOverride(
        "unavailable-1",
        "shift-1",
        at(9, 30),
        "Поломка",
    )

    at_event = derive_simulation_state((cycle,), at(9, 30)).vehicles[0]
    much_later = derive_simulation_state((cycle,), at(11), (unavailable,)).vehicles[0]

    assert much_later.status is SimulationStatus.UNAVAILABLE
    assert much_later.unavailable
    assert much_later.position == at_event.position
    assert much_later.current_load == at_event.current_load


def test_event_navigation_boundaries_are_derived_from_schedule() -> None:
    snapshot = derive_simulation_state((sample_cycle(),), at(9, 15))

    assert snapshot.previous_event_at == at(9, 10)
    assert snapshot.next_event_at == at(9, 20)
    assert snapshot.elapsed_events[-1].task_id == "delivery-task"


def depot_lon(cycle: RouteCycle) -> float:
    return cycle.stops[0].point.lon
