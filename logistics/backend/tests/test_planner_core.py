"""Behavioral tests for the deterministic two-cabin heuristic."""

# ruff: noqa: RUF001 -- Russian domain labels are intentional test data.

from __future__ import annotations

import asyncio
from dataclasses import replace
from datetime import date, datetime, timedelta
from itertools import pairwise
from time import perf_counter
from unittest.mock import patch
from zoneinfo import ZoneInfo

import pytest

from app.planner import (
    DriverShift,
    HeuristicPlanner,
    LogisticsRequest,
    NullProgressPublisher,
    PlanningInput,
    PlanningResult,
    PlanningSettings,
    PlanningTask,
    RequestDateOption,
    RequestStatus,
    RouteCycle,
    RouteStop,
    StopType,
    TaskType,
    TracePhase,
    UnassignedReasonCode,
    ValidationWarningCode,
    Vehicle,
    Warehouse,
    split_request,
)
from app.routing import GeoPoint, MockRoutingProvider, RoutingSettings

MOSCOW = ZoneInfo("Europe/Moscow")
PLANNING_DATE = date(2026, 8, 25)


def aware(hour: int, minute: int = 0) -> datetime:
    return datetime(2026, 8, 25, hour, minute, tzinfo=MOSCOW)


def request(
    request_id: str,
    request_type: TaskType,
    *,
    quantity: int = 1,
    lon: float = 37.7,
    lat: float = 55.75,
    priority: int = 1,
    hard: bool = True,
    window_start: datetime | None = None,
    window_end: datetime | None = None,
    status: RequestStatus = RequestStatus.READY,
    split_allowed: bool = True,
    trailer_access_allowed: bool = True,
    travel_zone_hours: int | None = None,
) -> LogisticsRequest:
    return LogisticsRequest(
        id=request_id,
        request_type=request_type,
        name=f"Заявка {request_id}",
        address_label=f"Адрес {request_id}",
        point=GeoPoint(lon, lat, is_city=True),
        quantity=quantity,
        service_minutes=10,
        priority=priority,
        status=status,
        date_options=(
            RequestDateOption(
                PLANNING_DATE,
                priority=1,
                window_start=window_start or aware(8),
                window_end=window_end or aware(19),
                is_hard=hard,
                travel_zone_hours=travel_zone_hours,
            ),
        ),
        created_at=aware(7),
        split_allowed=split_allowed,
        trailer_access_allowed=trailer_access_allowed,
    )


def planning_input(
    requests: tuple[LogisticsRequest, ...],
    *,
    shift_end: datetime | None = None,
    shifts: tuple[DriverShift, ...] | None = None,
) -> PlanningInput:
    vehicle = Vehicle("vehicle-1", "А123БВ", capacity=2)
    default_shifts = (
        DriverShift(
            "shift-1",
            "driver-1",
            "Водитель 1",
            vehicle.id,
            aware(8),
            shift_end or aware(20),
        ),
    )
    return PlanningInput(
        warehouse_id="warehouse-1",
        planning_date=PLANNING_DATE,
        warehouse=Warehouse("warehouse-1", "Склад", GeoPoint(37.6, 55.7, True), 5, 5, 5),
        requests=requests,
        shifts=default_shifts if shifts is None else shifts,
        vehicles=(vehicle,),
    )


def three_shift_input(
    requests: tuple[LogisticsRequest, ...],
    *,
    shift_end: datetime | None = None,
    shift_ends: tuple[datetime, datetime, datetime] | None = None,
) -> PlanningInput:
    """Build three equivalent driver/vehicle resources for fleet-activation tests."""

    vehicles = tuple(
        Vehicle(f"vehicle-{index}", f"Машина {index}", capacity=2) for index in range(1, 4)
    )
    shifts = tuple(
        DriverShift(
            f"shift-{index}",
            f"driver-{index}",
            f"Водитель {index}",
            vehicles[index - 1].id,
            aware(8),
            shift_ends[index - 1] if shift_ends is not None else shift_end or aware(20),
        )
        for index in range(1, 4)
    )
    return replace(planning_input(requests, shifts=shifts), vehicles=vehicles)


def run_plan(
    input_data: PlanningInput,
    settings: PlanningSettings | None = None,
) -> PlanningResult:
    planner = HeuristicPlanner(
        MockRoutingProvider(RoutingSettings(seed=17, deterministic_noise_ratio=0, road_factor=1.1))
    )
    return asyncio.run(
        planner.generate_plan(
            input_data,
            settings or PlanningSettings(seed=17),
            NullProgressPublisher(),
        )
    )


class PassThroughCandidateEvaluator:
    """Exercise the exact-route planner branch without changing mock schedules."""

    async def route_candidate(
        self,
        cycle: RouteCycle,
        *,
        tasks: tuple[PlanningTask, ...],
        vehicle: Vehicle,
        shift: DriverShift,
        settings: PlanningSettings,
    ) -> RouteCycle:
        """Return the already feasible mock candidate unchanged."""

        del tasks, vehicle, shift, settings
        return cycle


def customer_stops(cycle: RouteCycle, stop_type: StopType) -> list[RouteStop]:
    return [stop for stop in cycle.stops if stop.stop_type is stop_type]


def test_quantity_five_splits_as_two_two_one() -> None:
    source = request("delivery-5", TaskType.DELIVERY, quantity=5)
    option = source.date_options[0]

    tasks = split_request(
        source,
        option,
        remaining_date_count=1,
        is_last_available_date=True,
    )

    assert [task.quantity for task in tasks] == [2, 2, 1]
    assert [task.part_number for task in tasks] == [1, 2, 3]


def test_trailer_denied_quantity_two_splits_into_single_cabin_subtasks() -> None:
    """An address access denial prevents a hidden two-cabin trailer visit."""

    source = request(
        "delivery-without-trailer",
        TaskType.DELIVERY,
        quantity=2,
        trailer_access_allowed=False,
    )

    tasks = split_request(
        source,
        source.date_options[0],
        remaining_date_count=1,
        is_last_available_date=True,
    )

    assert [task.quantity for task in tasks] == [1, 1]
    assert all(not task.trailer_access_allowed for task in tasks)


def test_trailer_denied_address_is_not_paired_with_another_single_delivery() -> None:
    """A two-stop load is rejected when either visited address denied trailer access."""

    result = run_plan(
        planning_input(
            (
                request(
                    "no-trailer",
                    TaskType.DELIVERY,
                    trailer_access_allowed=False,
                ),
                request("trailer-ok", TaskType.DELIVERY, lon=37.72),
            )
        )
    )

    assert len(result.cycles) == 2
    assert all(len(customer_stops(cycle, StopType.DELIVERY)) == 1 for cycle in result.cycles)


def test_two_single_deliveries_are_paired_and_load_never_exceeds_capacity() -> None:
    result = run_plan(
        planning_input(
            (
                request("d1", TaskType.DELIVERY, lon=37.70),
                request("d2", TaskType.DELIVERY, lon=37.72),
            )
        )
    )

    assert len(result.cycles) == 1
    cycle = result.cycles[0]
    assert len(customer_stops(cycle, StopType.DELIVERY)) == 2
    assert [stop.load_after for stop in cycle.stops] == [2, 1, 0, 0]
    assert all(0 <= stop.load_after <= 2 for stop in cycle.stops)
    assert cycle.stops[0].stop_type is StopType.DEPOT_LOAD
    assert cycle.stops[-1].stop_type is StopType.DEPOT_RETURN


def test_travel_zone_band_does_not_change_planning() -> None:
    """Customer travel bands cannot influence exact road and schedule feasibility."""

    def paired_request_ids(result: PlanningResult) -> set[str]:
        paired_cycle = next(
            cycle for cycle in result.cycles if len(customer_stops(cycle, StopType.DELIVERY)) == 2
        )
        return {stop.request_id or "" for stop in customer_stops(paired_cycle, StopType.DELIVERY)}

    near_pair = run_plan(
        planning_input(
            (
                request("zone-a", TaskType.DELIVERY, lon=37.70, travel_zone_hours=1),
                request("zone-b", TaskType.DELIVERY, lon=37.71, travel_zone_hours=1),
                request("zone-c", TaskType.DELIVERY, lon=37.85, travel_zone_hours=2),
            )
        )
    )
    regrouped = run_plan(
        planning_input(
            (
                request("zone-a", TaskType.DELIVERY, lon=37.70, travel_zone_hours=1),
                request("zone-b", TaskType.DELIVERY, lon=37.71, travel_zone_hours=2),
                request("zone-c", TaskType.DELIVERY, lon=37.85, travel_zone_hours=1),
            )
        )
    )

    assert not near_pair.unassigned
    assert not regrouped.unassigned
    assert paired_request_ids(near_pair) == {"zone-a", "zone-b"}
    assert paired_request_ids(regrouped) == paired_request_ids(near_pair)


def test_legacy_travel_zone_has_no_upper_routing_limit_but_requires_hard_window() -> None:
    """A retained display band cannot impose a one-to-four-hour route restriction."""

    option = RequestDateOption(
        PLANNING_DATE,
        window_start=aware(9),
        window_end=aware(12),
        is_hard=True,
        travel_zone_hours=99,
    )
    assert option.travel_zone_hours == 99
    with pytest.raises(ValueError, match="complete hard time window"):
        RequestDateOption(
            PLANNING_DATE,
            window_start=aware(9),
            window_end=aware(12),
            is_hard=False,
            travel_zone_hours=1,
        )


def test_quantity_two_delivery_never_pairs_with_another_delivery() -> None:
    result = run_plan(
        planning_input(
            (
                request("full", TaskType.DELIVERY, quantity=2),
                request("single", TaskType.DELIVERY, quantity=1, lon=37.71),
            )
        )
    )

    assert len(result.cycles) == 2
    assert all(len(customer_stops(cycle, StopType.DELIVERY)) == 1 for cycle in result.cycles)


def test_quantity_two_delivery_is_one_task_one_stop_and_one_driver() -> None:
    """A full-capacity source request is not split into duplicate visits."""

    result = run_plan(three_shift_input((request("full-address", TaskType.DELIVERY, quantity=2),)))

    delivery_stops = [
        (cycle.driver_shift_id, stop)
        for cycle in result.cycles
        for stop in customer_stops(cycle, StopType.DELIVERY)
    ]
    assert len(delivery_stops) == 1
    assert delivery_stops[0][1].request_id == "full-address"
    assert delivery_stops[0][1].quantity_delta == -2
    assert (
        sum(
            stop.task_id == "full-address:part:1" for cycle in result.cycles for stop in cycle.stops
        )
        == 1
    )


def test_quantity_three_delivery_uses_exactly_two_vehicle_sized_visits() -> None:
    """Only a source quantity above capacity may legitimately revisit one address."""

    result = run_plan(
        three_shift_input((request("three-at-address", TaskType.DELIVERY, quantity=3),))
    )

    delivery_stops = [
        stop for cycle in result.cycles for stop in customer_stops(cycle, StopType.DELIVERY)
    ]
    assert len(delivery_stops) == 2
    assert {stop.request_id for stop in delivery_stops} == {"three-at-address"}
    assert sorted(-stop.quantity_delta for stop in delivery_stops) == [1, 2]
    assert len({stop.task_id for stop in delivery_stops}) == 2


def test_duplicate_generated_source_is_reported_instead_of_visited_twice() -> None:
    """Legacy repeated generator rows cannot send two drivers to one logical order."""

    first = replace(
        request("generated-original", TaskType.DELIVERY, quantity=2),
        source_key="WAREHOUSE_WORKLOAD_GENERATOR:20260822:2026-08-25:DELIVERY:1",
    )
    repeated = replace(
        first,
        id="generated-repeat",
        created_at=first.created_at + timedelta(seconds=1),
    )

    result = run_plan(three_shift_input((first, repeated)))

    delivery_stops = [
        stop for cycle in result.cycles for stop in customer_stops(cycle, StopType.DELIVERY)
    ]
    assert len(delivery_stops) == 1
    assert delivery_stops[0].request_id == "generated-original"
    assert len(result.unassigned) == 1
    assert result.unassigned[0].task.id == "generated-repeat"
    assert result.unassigned[0].reason_codes == (
        UnassignedReasonCode.DUPLICATE_ASSIGNMENT_CONFLICT,
    )


def test_two_single_pickups_pair_and_quantity_two_pickup_uses_full_capacity() -> None:
    paired = run_plan(
        planning_input(
            (
                request("p1", TaskType.PICKUP, lon=37.70),
                request("p2", TaskType.PICKUP, lon=37.72),
            )
        )
    )
    full = run_plan(planning_input((request("p-full", TaskType.PICKUP, quantity=2),)))

    assert len(paired.cycles) == 1
    assert [stop.load_after for stop in paired.cycles[0].stops] == [0, 1, 2, 0]
    assert [stop.load_after for stop in full.cycles[0].stops] == [0, 2, 0]


def test_all_deliveries_precede_pickups_in_mixed_cycle() -> None:
    settings = PlanningSettings(seed=17, max_detour_minutes=240, max_detour_ratio=10)
    result = run_plan(
        planning_input(
            (
                request("d1", TaskType.DELIVERY, lon=38.2),
                request("d2", TaskType.DELIVERY, lon=38.21),
                request("p1", TaskType.PICKUP, lon=38.0),
                request("p2", TaskType.PICKUP, lon=37.9),
            )
        ),
        settings,
    )

    mixed = next(
        cycle
        for cycle in result.cycles
        if customer_stops(cycle, StopType.DELIVERY) and customer_stops(cycle, StopType.PICKUP)
    )
    customer_types = [
        stop.stop_type
        for stop in mixed.stops
        if stop.stop_type in {StopType.DELIVERY, StopType.PICKUP}
    ]
    first_pickup = customer_types.index(StopType.PICKUP)
    assert all(stop_type is StopType.DELIVERY for stop_type in customer_types[:first_pickup])
    assert all(stop_type is StopType.PICKUP for stop_type in customer_types[first_pickup:])


def test_open_day_attaches_pickups_after_single_cabin_delivery() -> None:
    """Open request intake does not block compatible return work on a delivery cycle."""

    source = replace(
        planning_input(
            (
                request("single-delivery", TaskType.DELIVERY, lon=37.70),
                request("return-1", TaskType.PICKUP, lon=37.705),
                request("return-2", TaskType.PICKUP, lon=37.71),
            )
        ),
        accepting_requests=True,
    )

    result = run_plan(
        source,
        PlanningSettings(seed=17, max_detour_minutes=120, max_detour_ratio=5),
    )

    assert not result.unassigned
    assert len(result.cycles) == 1
    assert [stop.request_id for stop in customer_stops(result.cycles[0], StopType.DELIVERY)] == [
        "single-delivery"
    ]
    assert [
        stop.request_id for stop in customer_stops(result.cycles[0], StopType.PICKUP)
    ] == ["return-1", "return-2"]
    assert [stop.load_after for stop in result.cycles[0].stops] == [1, 0, 1, 2, 0]


def test_open_day_builds_pickup_only_cycle() -> None:
    """Pickup-only work remains plannable while new delivery requests are accepted."""

    source = replace(
        planning_input(
            (
                request("return-1", TaskType.PICKUP, lon=37.705),
                request("return-2", TaskType.PICKUP, lon=37.71),
            )
        ),
        accepting_requests=True,
    )

    result = run_plan(
        source,
        PlanningSettings(seed=17, max_detour_minutes=120, max_detour_ratio=5),
    )

    assert not result.unassigned
    assert len(result.cycles) == 1
    assert not customer_stops(result.cycles[0], StopType.DELIVERY)
    assert [
        stop.request_id for stop in customer_stops(result.cycles[0], StopType.PICKUP)
    ] == ["return-1", "return-2"]
    assert [stop.load_after for stop in result.cycles[0].stops] == [0, 1, 2, 0]


def test_open_day_attaches_pickups_after_full_two_cabin_outbound_load() -> None:
    """Selection prefers a full two-delivery and two-pickup compatible cycle."""

    source = replace(
        planning_input(
            (
                request("delivery-1", TaskType.DELIVERY, lon=37.70),
                request("delivery-2", TaskType.DELIVERY, lon=37.71),
                request("return-1", TaskType.PICKUP, lon=37.705),
                request("return-2", TaskType.PICKUP, lon=37.70),
            )
        ),
        accepting_requests=True,
    )

    result = run_plan(
        source,
        PlanningSettings(seed=17, max_detour_minutes=120, max_detour_ratio=5),
    )

    assert not result.unassigned
    assert len(result.cycles) == 1
    assert [stop.load_after for stop in result.cycles[0].stops] == [2, 1, 0, 1, 2, 0]


def test_open_day_quantity_two_delivery_to_one_address_unlocks_return_pickups() -> None:
    """Two cabins for one address are a full outbound load, not a singleton trip."""

    source = replace(
        planning_input(
            (
                request("delivery-two", TaskType.DELIVERY, quantity=2, lon=37.70),
                request("return-1", TaskType.PICKUP, lon=37.705),
                request("return-2", TaskType.PICKUP, lon=37.71),
            )
        ),
        accepting_requests=True,
    )

    result = run_plan(
        source,
        PlanningSettings(seed=17, max_detour_minutes=120, max_detour_ratio=5),
    )

    assert not result.unassigned
    assert len(result.cycles) == 1
    assert [stop.load_after for stop in result.cycles[0].stops] == [2, 0, 1, 2, 0]


def test_open_day_builds_later_mixed_cycle_only_after_depot_turnaround() -> None:
    """A later full cycle starts after the prior backhaul is unloaded at the depot."""

    settings = PlanningSettings(seed=17, max_detour_minutes=120, max_detour_ratio=5)
    source = replace(
        planning_input(
            (
                request("delivery-a", TaskType.DELIVERY, quantity=2, lon=37.70),
                request("delivery-b", TaskType.DELIVERY, quantity=2, lon=37.72),
                request("return-a", TaskType.PICKUP, quantity=2, lon=37.705),
                request("return-b", TaskType.PICKUP, quantity=2, lon=37.725),
            )
        ),
        accepting_requests=True,
    )

    result = run_plan(source, settings)

    assert not result.unassigned
    assert len(result.cycles) == 2
    for cycle in result.cycles:
        delivered = sum(
            stop.quantity_delta * -1
            for stop in customer_stops(cycle, StopType.DELIVERY)
        )
        assert delivered == 2
        assert sum(stop.quantity_delta for stop in customer_stops(cycle, StopType.PICKUP)) == 2
    for previous, following in pairwise(result.cycles):
        assert following.planned_start >= previous.planned_finish + timedelta(
            minutes=(
                source.warehouse.turnaround_minutes
                + settings.default_route_buffer_minutes
            )
        )


def test_closed_day_finalizes_pickups_after_single_cabin_delivery() -> None:
    """Closing acceptance permits the best return work even after a singleton delivery."""

    result = run_plan(
        planning_input(
            (
                request("single-delivery", TaskType.DELIVERY, lon=37.70),
                request("return-1", TaskType.PICKUP, lon=37.705),
                request("return-2", TaskType.PICKUP, lon=37.71),
            )
        ),
        PlanningSettings(seed=17, max_detour_minutes=120, max_detour_ratio=5),
    )

    assert not result.unassigned
    assert len(result.cycles) == 1
    assert len(customer_stops(result.cycles[0], StopType.DELIVERY)) == 1
    assert len(customer_stops(result.cycles[0], StopType.PICKUP)) == 2


def test_feasible_delivery_and_pickup_pairs_prefer_one_combined_cycle() -> None:
    """Candidate ranking must not penalize a bundle for aggregate date counts."""

    def with_second_allowed_date(source: LogisticsRequest) -> LogisticsRequest:
        selected = source.date_options[0]
        return replace(
            source,
            priority=0,
            date_options=(
                replace(selected, priority=0, is_hard=False),
                replace(
                    selected,
                    date=PLANNING_DATE + timedelta(days=1),
                    priority=0,
                    window_start=aware(8) + timedelta(days=1),
                    window_end=aware(19) + timedelta(days=1),
                    is_hard=False,
                ),
            ),
        )

    result = run_plan(
        planning_input(
            tuple(
                with_second_allowed_date(item)
                for item in (
                    request("d1", TaskType.DELIVERY, lon=37.70),
                    request("d2", TaskType.DELIVERY, lon=37.72),
                    request("p1", TaskType.PICKUP, lon=37.71),
                    request("p2", TaskType.PICKUP, lon=37.69),
                )
            ),
        ),
        PlanningSettings(seed=17),
    )

    assert len(result.cycles) == 1
    assert [stop.load_after for stop in result.cycles[0].stops] == [2, 1, 0, 1, 2, 0]


def test_default_settings_minimize_returns_with_two_full_mixed_cycles() -> None:
    """Four outbound and four return cabins need two, not three, depot cycles."""

    source = three_shift_input(
        (
            request("d-west", TaskType.DELIVERY, lon=37.50, lat=55.75, priority=100),
            request("d-east", TaskType.DELIVERY, lon=37.66, lat=55.75, priority=90),
            request(
                "d-far",
                TaskType.DELIVERY,
                quantity=2,
                lon=37.74,
                lat=55.77,
                priority=50,
            ),
            request("p-east", TaskType.PICKUP, lon=37.69, lat=55.73, priority=80),
            request("p-west", TaskType.PICKUP, lon=37.54, lat=55.72, priority=70),
            request(
                "p-south",
                TaskType.PICKUP,
                quantity=2,
                lon=37.57,
                lat=55.67,
                priority=40,
            ),
        )
    )
    source = replace(
        source,
        warehouse=Warehouse(
            "warehouse-1",
            "Склад",
            GeoPoint(37.39, 55.75, True),
            30,
            30,
            15,
        ),
    )

    result = run_plan(source)

    assert not result.unassigned
    assert len(result.cycles) == 2
    assert len({cycle.driver_shift_id for cycle in result.cycles}) == 1
    assert all(
        customer_stops(cycle, StopType.DELIVERY) and customer_stops(cycle, StopType.PICKUP)
        for cycle in result.cycles
    )
    assert all(
        sum(-stop.quantity_delta for stop in customer_stops(cycle, StopType.DELIVERY)) == 2
        and sum(stop.quantity_delta for stop in customer_stops(cycle, StopType.PICKUP)) == 2
        for cycle in result.cycles
    )
    delivery_ids = {
        stop.task_id
        for cycle in result.cycles
        for stop in cycle.stops
        if stop.stop_type is StopType.DELIVERY
    }
    pickup_ids = {
        stop.task_id
        for cycle in result.cycles
        for stop in cycle.stops
        if stop.stop_type is StopType.PICKUP
    }
    assert delivery_ids == {"d-west:part:1", "d-east:part:1", "d-far:part:1"}
    assert pickup_ids == {"p-east:part:1", "p-west:part:1", "p-south:part:1"}
    assert any(
        "убирает отдельный рейс" in explanation
        for cycle in result.cycles
        for explanation in cycle.explanation
    )


def test_delivery_prefers_compatible_return_pickup_over_pickup_only_cycle() -> None:
    """A last-date return must be attached after a feasible delivery when possible."""

    delivery = request("d1", TaskType.DELIVERY, lon=37.70, hard=False)
    selected = delivery.date_options[0]
    delivery_with_alternative = replace(
        delivery,
        date_options=(
            selected,
            replace(
                selected,
                date=PLANNING_DATE + timedelta(days=1),
                window_start=aware(8) + timedelta(days=1),
                window_end=aware(19) + timedelta(days=1),
            ),
        ),
    )
    result = run_plan(
        planning_input(
            (
                delivery_with_alternative,
                request("p1", TaskType.PICKUP, lon=37.705, hard=False),
                request("p2", TaskType.PICKUP, lon=37.71, hard=False),
            )
        ),
        PlanningSettings(seed=17, max_detour_minutes=120, max_detour_ratio=5),
    )

    assert len(result.cycles) == 1
    assert [stop.stop_type for stop in result.cycles[0].stops] == [
        StopType.DEPOT_LOAD,
        StopType.DELIVERY,
        StopType.PICKUP,
        StopType.PICKUP,
        StopType.DEPOT_RETURN,
    ]


def test_full_delivery_takes_nearby_full_pickup_before_returning_to_depot() -> None:
    """A nearby backhaul fills the empty vehicle even with realistic service time."""

    delivery = replace(
        request("full-delivery", TaskType.DELIVERY, quantity=2, lon=37.80),
        service_minutes=30,
    )
    pickup = replace(
        request("near-full-pickup", TaskType.PICKUP, quantity=2, lon=37.805),
        service_minutes=30,
    )

    result = run_plan(
        three_shift_input((delivery, pickup)),
        PlanningSettings(seed=17, max_detour_minutes=15),
    )

    assert not result.unassigned
    assert len(result.cycles) == 1
    assert [stop.stop_type for stop in result.cycles[0].stops] == [
        StopType.DEPOT_LOAD,
        StopType.DELIVERY,
        StopType.PICKUP,
        StopType.DEPOT_RETURN,
    ]
    assert [stop.load_after for stop in result.cycles[0].stops] == [2, 0, 2, 0]
    assert result.cycles[0].detour_seconds < 15 * 60


def test_one_driver_can_run_multiple_mixed_cycles_with_per_cycle_ordering() -> None:
    """Returning to depot starts a new load, so a later cycle may deliver again."""

    result = run_plan(
        planning_input(
            (
                request("d1", TaskType.DELIVERY, lon=37.70),
                request("d2", TaskType.DELIVERY, lon=37.72),
                request("d3", TaskType.DELIVERY, lon=37.74),
                request("p1", TaskType.PICKUP, lon=37.71),
                request("p2", TaskType.PICKUP, lon=37.73),
                request("p3", TaskType.PICKUP, lon=37.75),
            )
        ),
        PlanningSettings(seed=17, max_detour_minutes=240, max_detour_ratio=10),
    )

    assert not result.unassigned
    assert len(result.cycles) == 2
    assert all(
        customer_stops(cycle, StopType.DELIVERY) and customer_stops(cycle, StopType.PICKUP)
        for cycle in result.cycles
    )
    for cycle in result.cycles:
        customer_types = [
            stop.stop_type
            for stop in cycle.stops
            if stop.stop_type in {StopType.DELIVERY, StopType.PICKUP}
        ]
        first_pickup = customer_types.index(StopType.PICKUP)
        assert all(stop_type is StopType.DELIVERY for stop_type in customer_types[:first_pickup])
        assert all(stop_type is StopType.PICKUP for stop_type in customer_types[first_pickup:])


def test_one_driver_can_receive_multiple_cycles() -> None:
    settings = PlanningSettings(seed=17)
    source = planning_input(
        tuple(
            request(f"d{index}", TaskType.DELIVERY, quantity=2, lon=37.7 + index / 100)
            for index in range(3)
        )
    )
    result = run_plan(
        source,
        settings,
    )

    assert len(result.cycles) == 3
    assert {cycle.driver_shift_id for cycle in result.cycles} == {"shift-1"}
    for previous, following in pairwise(result.cycles):
        assert following.planned_start >= previous.planned_finish + timedelta(
            minutes=(
                source.warehouse.turnaround_minutes
                + settings.default_route_buffer_minutes
            )
        )


def test_feasible_work_is_consolidated_without_activating_all_three_resources() -> None:
    """Dense cycles stay on one shift when all hard constraints fit its working day."""

    result = run_plan(
        three_shift_input(
            (
                request("d1", TaskType.DELIVERY, lon=37.67),
                request("d2", TaskType.DELIVERY, lon=37.69),
                request("d3", TaskType.DELIVERY, lon=37.71),
                request("d4", TaskType.DELIVERY, lon=37.73),
                request("p1", TaskType.PICKUP, lon=37.70),
                request("p2", TaskType.PICKUP, lon=37.68),
            )
        ),
        PlanningSettings(
            seed=17,
            max_detour_minutes=240,
            max_detour_ratio=10,
            additional_resource_activation_penalty=180,
        ),
    )

    assert not result.unassigned
    assert len(result.cycles) == 2
    assert len({cycle.driver_shift_id for cycle in result.cycles}) == 1
    mixed = next(
        cycle
        for cycle in result.cycles
        if customer_stops(cycle, StopType.DELIVERY) and customer_stops(cycle, StopType.PICKUP)
    )
    assert [stop.load_after for stop in mixed.stops] == [2, 1, 0, 1, 2, 0]
    assert any(
        "дополнительная машина не подключается" in explanation
        for cycle in result.cycles
        for explanation in cycle.explanation
    )
    assert result.score == round(sum(cycle.score for cycle in result.cycles), 6)


def test_dense_fifteen_delivery_ten_pickup_day_stays_bounded_and_delivery_first() -> None:
    """A generated-size day packs work without fanning it out to all resources."""

    deliveries = tuple(
        request(
            f"dense-d-{index:02}",
            TaskType.DELIVERY,
            lon=37.61 + (index % 5) * 0.004,
            lat=55.70 + (index // 5) * 0.004,
        )
        for index in range(15)
    )
    pickups = tuple(
        request(
            f"dense-p-{index:02}",
            TaskType.PICKUP,
            lon=37.612 + (index % 5) * 0.004,
            lat=55.702 + (index // 5) * 0.004,
        )
        for index in range(10)
    )

    started = perf_counter()
    result = run_plan(
        three_shift_input((*deliveries, *pickups), shift_end=aware(23)),
        PlanningSettings(seed=17),
    )
    elapsed = perf_counter() - started

    delivery_stops = [
        stop
        for cycle in result.cycles
        for stop in cycle.stops
        if stop.stop_type is StopType.DELIVERY
    ]
    pickup_stops = [
        stop for cycle in result.cycles for stop in cycle.stops if stop.stop_type is StopType.PICKUP
    ]
    assert len(delivery_stops) == 15
    assert len(pickup_stops) == 10
    assert not result.unassigned
    assert len(result.cycles) == 8
    assert len({cycle.driver_shift_id for cycle in result.cycles}) == 1
    assert elapsed < 5


def test_pickup_never_consumes_the_last_feasible_delivery_slot() -> None:
    """A return is sacrificed when its extra service would strand a delivery."""

    result = run_plan(
        planning_input(
            (
                request("must-deliver-1", TaskType.DELIVERY, quantity=2, lon=37.61),
                request("must-deliver-2", TaskType.DELIVERY, quantity=2, lon=37.62),
                request("optional-return", TaskType.PICKUP, quantity=2, lon=37.615),
            ),
            shift_end=aware(9, 55),
        ),
        PlanningSettings(seed=17, max_detour_minutes=120, max_detour_ratio=5),
    )

    assigned_delivery_ids = {
        stop.request_id
        for cycle in result.cycles
        for stop in cycle.stops
        if stop.stop_type is StopType.DELIVERY
    }
    assigned_pickup_ids = {
        stop.request_id
        for cycle in result.cycles
        for stop in cycle.stops
        if stop.stop_type is StopType.PICKUP
    }
    assert assigned_delivery_ids == {"must-deliver-1", "must-deliver-2"}
    assert assigned_pickup_ids == set()
    assert {item.task.request_id for item in result.unassigned} == {"optional-return"}


def test_delivery_reference_attaches_near_pickup_without_dropping_later_delivery() -> None:
    """A safe backhaul may shift a suffix while preserving all delivery work."""

    deliveries = (
        request("near-delivery", TaskType.DELIVERY, quantity=2, lon=37.62),
        request("far-delivery", TaskType.DELIVERY, quantity=2, lon=38.10),
    )
    pickup = request("near-return", TaskType.PICKUP, quantity=2, lon=37.625)
    source = planning_input(deliveries)
    settings = PlanningSettings(
        seed=17,
        max_detour_minutes=30,
        max_detour_ratio=5,
    )
    delivery_result = run_plan(source, settings)
    tasks = tuple(
        task
        for item in (*deliveries, pickup)
        for task in split_request(
            item,
            item.date_options[0],
            remaining_date_count=1,
            is_last_available_date=True,
        )
    )
    ordered_tasks = tuple(sorted(tasks, key=lambda task: task.id))
    provider = MockRoutingProvider(
        RoutingSettings(
            seed=17,
            deterministic_noise_ratio=0,
            road_factor=1.1,
        )
    )
    matrix = asyncio.run(
        provider.get_matrix(
            [source.warehouse.point, *(task.point for task in ordered_tasks)],
            aware(8),
        )
    )
    matrix_index = {task.id: index + 1 for index, task in enumerate(ordered_tasks)}
    planner = HeuristicPlanner(provider)

    restored, _, evaluated = planner._attach_pickups_to_delivery_reference(
        pickup_tasks=tuple(task for task in tasks if task.task_type is TaskType.PICKUP),
        shifts=source.shifts,
        available_at={
            source.shifts[0].id: delivery_result.cycles[-1].planned_finish
            + timedelta(
                minutes=source.warehouse.turnaround_minutes + settings.default_route_buffer_minutes
            )
        },
        warehouse=source.warehouse,
        vehicles={vehicle.id: vehicle for vehicle in source.vehicles},
        matrix=matrix,
        matrix_index=matrix_index,
        settings=settings,
        cycles=delivery_result.cycles,
        task_by_id={task.id: task for task in tasks},
        max_evaluations=1_000,
    )

    assert evaluated > 0
    assert {
        stop.request_id
        for cycle in restored
        for stop in cycle.stops
        if stop.stop_type is StopType.DELIVERY
    } == {"near-delivery", "far-delivery"}
    assert {
        stop.request_id
        for cycle in restored
        for stop in cycle.stops
        if stop.stop_type is StopType.PICKUP
    } == {"near-return"}
    assert all(cycle.planned_finish <= source.shifts[0].end_at for cycle in restored)


def test_delivery_reference_restores_coverage_when_main_search_has_no_candidate() -> None:
    """The delivery coverage fence is a working fallback, not an untested dead branch."""

    source = planning_input((request("reference-delivery", TaskType.DELIVERY, quantity=2),))
    provider = MockRoutingProvider(
        RoutingSettings(seed=17, deterministic_noise_ratio=0, road_factor=1.1)
    )
    planner = HeuristicPlanner(provider)

    with patch.object(
        planner,
        "_candidates_for_shift",
        return_value=([], 0, False),
    ):
        result = asyncio.run(
            planner.generate_plan(
                source,
                PlanningSettings(seed=17),
                NullProgressPublisher(),
            )
        )

    assert not result.unassigned
    assert len(result.cycles) == 1
    assert result.cycles[0].task_ids == ("reference-delivery:part:1",)


@pytest.mark.parametrize("with_route_evaluator", (False, True))
def test_delivery_reference_protects_an_earlier_window_from_later_equal_width_work(
    with_route_evaluator: bool,
) -> None:
    """Later equal-width cycles cannot consume a feasible earlier full-load delivery."""

    source = planning_input(
        (
            request(
                "morning-full-load",
                TaskType.DELIVERY,
                quantity=2,
                lon=37.80,
                window_start=aware(8),
                window_end=aware(11),
            ),
            request(
                "late-near-full-load",
                TaskType.DELIVERY,
                quantity=2,
                lon=37.61,
                window_start=aware(11),
                window_end=aware(14),
            ),
            request(
                "late-far-full-load",
                TaskType.DELIVERY,
                quantity=2,
                lon=38.00,
                window_start=aware(11),
                window_end=aware(14),
            ),
        ),
        shift_end=aware(16),
    )
    provider = MockRoutingProvider(
        RoutingSettings(seed=17, deterministic_noise_ratio=0, road_factor=1.1)
    )
    planner = HeuristicPlanner(
        provider,
        PassThroughCandidateEvaluator() if with_route_evaluator else None,
    )
    result = asyncio.run(
        planner.generate_plan(
            source,
            PlanningSettings(seed=17),
            NullProgressPublisher(),
        )
    )

    assert not result.unassigned
    assert {
        stop.request_id
        for cycle in result.cycles
        for stop in customer_stops(cycle, StopType.DELIVERY)
    } == {
        "morning-full-load",
        "late-near-full-load",
        "late-far-full-load",
    }
    first_delivery = next(
        stop
        for cycle in sorted(result.cycles, key=lambda item: item.planned_start)
        for stop in customer_stops(cycle, StopType.DELIVERY)
    )
    assert first_delivery.request_id == "morning-full-load"


def test_windowed_delivery_precedes_unbounded_equal_priority_work() -> None:
    """Missing optional windows sort after bounded work without incomparable values."""

    bounded = request(
        "bounded",
        TaskType.DELIVERY,
        quantity=1,
        lon=37.75,
        window_start=aware(8),
        window_end=aware(11),
    )
    unbounded = replace(
        request("unbounded", TaskType.DELIVERY, quantity=1, lon=37.65),
        date_options=(RequestDateOption(date=PLANNING_DATE, priority=100),),
    )

    result = run_plan(planning_input((unbounded, bounded), shift_end=aware(16)))

    assert not result.unassigned
    first_delivery = next(
        stop
        for cycle in sorted(result.cycles, key=lambda item: item.planned_start)
        for stop in customer_stops(cycle, StopType.DELIVERY)
    )
    assert first_delivery.request_id == "bounded"


def test_additional_driver_is_activated_when_hard_windows_require_parallel_work() -> None:
    """Resource consolidation never makes a feasible hard-window request late."""

    penalty = 10_000.0
    result = run_plan(
        three_shift_input(
            (
                request(
                    "hard-1",
                    TaskType.DELIVERY,
                    quantity=2,
                    lon=37.70,
                    window_end=aware(9),
                ),
                request(
                    "hard-2",
                    TaskType.DELIVERY,
                    quantity=2,
                    lon=37.72,
                    window_end=aware(9),
                ),
            )
        ),
        PlanningSettings(
            seed=17,
            additional_resource_activation_penalty=penalty,
        ),
    )

    assert not result.unassigned
    assert len(result.cycles) == 2
    assert len({cycle.driver_shift_id for cycle in result.cycles}) == 2
    assert {cycle.planned_start for cycle in result.cycles} == {aware(8)}
    assert result.score == round(sum(cycle.score for cycle in result.cycles) + penalty, 6)
    assert any(
        "Дополнительная смена" in explanation
        for cycle in result.cycles
        for explanation in cycle.explanation
    )


def test_multi_stop_cycles_are_distributed_between_drivers_for_parallel_windows() -> None:
    """Four cabin tasks form two multi-point routes instead of one driver per request."""

    result = run_plan(
        three_shift_input(
            tuple(
                request(
                    f"parallel-{index}",
                    TaskType.DELIVERY,
                    lon=37.67 + index / 100,
                    window_end=aware(9),
                    travel_zone_hours=1,
                )
                for index in range(4)
            )
        ),
        PlanningSettings(seed=17, additional_resource_activation_penalty=10_000),
    )

    assert not result.unassigned
    assert len(result.cycles) == 2
    assert len({cycle.driver_shift_id for cycle in result.cycles}) == 2
    assert all(len(customer_stops(cycle, StopType.DELIVERY)) == 2 for cycle in result.cycles)
    assert {
        stop.request_id
        for cycle in result.cycles
        for stop in customer_stops(cycle, StopType.DELIVERY)
    } == {f"parallel-{index}" for index in range(4)}


def test_along_route_delivery_is_inserted_before_a_cross_route_detour() -> None:
    """Multi-stop ranking uses the latest delivery front, not depot rings alone."""

    result = run_plan(
        planning_input(
            (
                request("east-near", TaskType.DELIVERY, lon=37.70, lat=55.70),
                request("east-far", TaskType.DELIVERY, lon=37.80, lat=55.70),
                request("north", TaskType.DELIVERY, lon=37.60, lat=55.82),
            )
        )
    )

    paired_cycle = next(
        cycle for cycle in result.cycles if len(customer_stops(cycle, StopType.DELIVERY)) == 2
    )
    paired_ids = {
        stop.request_id for stop in customer_stops(paired_cycle, StopType.DELIVERY)
    }
    delivery_sequences = [
        stop.sequence for stop in customer_stops(paired_cycle, StopType.DELIVERY)
    ]

    assert not result.unassigned
    assert paired_ids == {"east-near", "east-far"}
    assert any(
        leg.from_stop_sequence == delivery_sequences[0]
        and leg.to_stop_sequence == delivery_sequences[1]
        for leg in paired_cycle.legs
    )


def test_first_resource_prefers_enough_shift_reserve_for_all_feasible_cycles() -> None:
    """A short shift is not activated first when one long shift can finish all work."""

    result = run_plan(
        three_shift_input(
            tuple(
                request(
                    f"reserve-{index}",
                    TaskType.DELIVERY,
                    quantity=2,
                    lon=37.68 + index / 100,
                )
                for index in range(3)
            ),
            shift_ends=(aware(9), aware(20), aware(20)),
        )
    )

    used_shift_ids = {cycle.driver_shift_id for cycle in result.cycles}
    assert not result.unassigned
    assert len(result.cycles) == 3
    assert len(used_shift_ids) == 1
    assert "shift-1" not in used_shift_ids


def test_active_fleet_preserves_the_later_shift_for_work_only_it_can_finish() -> None:
    """A flexible late vehicle remains free when a shorter active shift fits current work."""

    source = three_shift_input(
        (
            request(
                "parallel-morning-1",
                TaskType.DELIVERY,
                quantity=2,
                lon=37.70,
                window_end=aware(9),
            ),
            request(
                "parallel-morning-2",
                TaskType.DELIVERY,
                quantity=2,
                lon=37.72,
                window_end=aware(9),
            ),
            request(
                "afternoon-delivery",
                TaskType.DELIVERY,
                quantity=2,
                lon=37.75,
                window_start=aware(14),
                window_end=aware(16),
            ),
            request(
                "late-return",
                TaskType.PICKUP,
                quantity=2,
                lon=38.00,
                window_start=aware(17, 50),
                window_end=aware(18),
            ),
        ),
        shift_ends=(aware(18), aware(20), aware(20)),
    )
    source = replace(
        source,
        shifts=(source.shifts[0], source.shifts[1], replace(source.shifts[2], active=False)),
    )

    result = run_plan(source)

    assert not result.unassigned
    afternoon_cycle = next(
        cycle for cycle in result.cycles if "afternoon-delivery:part:1" in cycle.task_ids
    )
    late_cycle = next(cycle for cycle in result.cycles if "late-return:part:1" in cycle.task_ids)
    assert afternoon_cycle.driver_shift_id == "shift-1"
    assert late_cycle.driver_shift_id == "shift-2"
    assert late_cycle.planned_finish > source.shifts[0].end_at
    assert late_cycle.planned_finish <= source.shifts[1].end_at


def test_driver_workload_can_activate_a_second_shift_before_the_hard_end() -> None:
    """A convex overload choice balances long duty without fanning work to all drivers."""

    requests = tuple(
        request(
            f"workload-{index}",
            TaskType.DELIVERY,
            quantity=2,
            lon=37.68 + index / 100,
        )
        for index in range(4)
    )
    source = three_shift_input(requests)
    source = replace(
        source,
        shifts=tuple(replace(shift, break_minutes=30) for shift in source.shifts),
    )
    common = PlanningSettings(
        seed=17,
        preferred_shift_utilization_percent=25,
        additional_resource_activation_penalty=180,
    )

    consolidated = run_plan(source, replace(common, driver_workload_weight=0))
    balanced = run_plan(source, replace(common, driver_workload_weight=10))

    assert not consolidated.unassigned
    assert not balanced.unassigned
    assert len({cycle.driver_shift_id for cycle in consolidated.cycles}) == 1
    assert len({cycle.driver_shift_id for cycle in balanced.cycles}) == 2
    assert balanced.metrics.active_shift_count == 2
    assert balanced.metrics.shift_utilization_percent > 0
    assert any(
        "перерыв 30 мин учтён" in explanation
        for cycle in balanced.cycles
        for explanation in cycle.explanation
    )


def test_pickup_on_return_leg_remains_feasible() -> None:
    settings = PlanningSettings(seed=17, max_detour_minutes=240, max_detour_ratio=10)
    result = run_plan(
        planning_input(
            (
                request("east-delivery", TaskType.DELIVERY, lon=38.2),
                request("west-pickup", TaskType.PICKUP, lon=37.9),
            ),
        ),
        settings,
    )

    assert not result.unassigned
    mixed = [
        cycle
        for cycle in result.cycles
        if customer_stops(cycle, StopType.DELIVERY) and customer_stops(cycle, StopType.PICKUP)
    ]
    assert mixed


def test_large_detour_forces_a_separate_pickup_cycle() -> None:
    """A cycle saving never overrides the configured detour feasibility limit."""

    result = run_plan(
        planning_input(
            (
                request("delivery", TaskType.DELIVERY, lon=37.8),
                request("pickup", TaskType.PICKUP, lon=38.5),
            ),
        ),
        PlanningSettings(seed=17, max_detour_minutes=1, max_detour_ratio=0.01),
    )

    assert not result.unassigned
    assert len(result.cycles) == 2
    assert not any(
        customer_stops(cycle, StopType.DELIVERY) and customer_stops(cycle, StopType.PICKUP)
        for cycle in result.cycles
    )


def test_hard_window_is_infeasible_but_soft_window_yields_warning() -> None:
    hard_request = request(
        "hard",
        TaskType.DELIVERY,
        window_start=aware(8),
        window_end=aware(8, 5),
    )
    soft_request = replace(
        hard_request,
        id="soft",
        date_options=(replace(hard_request.date_options[0], is_hard=False),),
    )

    hard_result = run_plan(planning_input((hard_request,)))
    soft_result = run_plan(planning_input((soft_request,)))

    assert hard_result.unassigned[0].reason_codes == (UnassignedReasonCode.TIME_WINDOW_CONFLICT,)
    assert ValidationWarningCode.SOFT_WINDOW_RISK in soft_result.cycles[0].warnings


def test_late_first_window_delays_depot_start_and_arrives_just_in_time() -> None:
    """A 15:00 appointment must not park the loaded vehicle at the client from 09:00."""

    result = run_plan(
        planning_input(
            (
                request(
                    "late-delivery",
                    TaskType.DELIVERY,
                    window_start=aware(15),
                    window_end=aware(18),
                    hard=False,
                ),
            )
        )
    )

    assert not result.unassigned
    cycle = result.cycles[0]
    depot, delivery, _ = cycle.stops
    outbound = cycle.legs[0]
    assert cycle.planned_start > aware(8)
    assert delivery.planned_arrival == aware(15)
    assert delivery.planned_departure == aware(15, 10)
    assert delivery.planned_departure - delivery.planned_arrival == timedelta(minutes=10)
    assert outbound.departure_at == depot.planned_departure
    assert outbound.arrival_at == delivery.planned_arrival
    assert cycle.waiting_seconds == 0


def test_later_window_shifts_the_complete_cycle_at_the_warehouse_when_possible() -> None:
    """Compatible windows keep the whole cycle at the depot until it is needed."""

    result = run_plan(
        planning_input(
            (
                request(
                    "morning-delivery",
                    TaskType.DELIVERY,
                    lon=37.70,
                    window_start=aware(8),
                    window_end=aware(18),
                    hard=False,
                ),
                request(
                    "afternoon-pickup",
                    TaskType.PICKUP,
                    lon=37.705,
                    window_start=aware(15),
                    window_end=aware(18),
                    hard=False,
                ),
            )
        ),
        PlanningSettings(seed=17, max_detour_minutes=240, max_detour_ratio=10),
    )

    mixed = next(
        cycle
        for cycle in result.cycles
        if customer_stops(cycle, StopType.DELIVERY) and customer_stops(cycle, StopType.PICKUP)
    )
    delivery = customer_stops(mixed, StopType.DELIVERY)[0]
    pickup = customer_stops(mixed, StopType.PICKUP)[0]
    pickup_leg = mixed.legs[1]
    assert mixed.planned_start > aware(8)
    assert pickup.planned_arrival == aware(15)
    assert pickup.planned_departure == aware(15, 10)
    assert pickup_leg.departure_at == delivery.planned_departure
    assert pickup_leg.arrival_at == pickup.planned_arrival
    assert mixed.waiting_seconds == 0


def test_multi_hour_incompatible_windows_split_into_separate_depot_cycles() -> None:
    """A long forced gap returns work to the depot instead of parking at a client."""

    result = run_plan(
        planning_input(
            (
                request(
                    "morning-delivery",
                    TaskType.DELIVERY,
                    lon=37.70,
                    window_start=aware(8),
                    window_end=aware(12),
                    hard=True,
                ),
                request(
                    "afternoon-pickup",
                    TaskType.PICKUP,
                    lon=37.705,
                    window_start=aware(15),
                    window_end=aware(18),
                    hard=True,
                ),
            )
        ),
        PlanningSettings(seed=17, max_detour_minutes=240, max_detour_ratio=10),
    )

    assert not result.unassigned
    assert len(result.cycles) == 2
    assert not any(
        customer_stops(cycle, StopType.DELIVERY) and customer_stops(cycle, StopType.PICKUP)
        for cycle in result.cycles
    )
    assert all(cycle.waiting_seconds == 0 for cycle in result.cycles)
    assert all(cycle.stops[0].stop_type is StopType.DEPOT_LOAD for cycle in result.cycles)
    assert all(cycle.stops[-1].stop_type is StopType.DEPOT_RETURN for cycle in result.cycles)


def test_unassigned_contains_specific_resource_reason_and_russian_text() -> None:
    data = planning_input((request("d1", TaskType.DELIVERY),), shifts=())

    result = run_plan(data)

    assert result.unassigned[0].reason_codes == (UnassignedReasonCode.NO_ACTIVE_DRIVER,)
    assert "водителя" in result.unassigned[0].explanation_ru[0]


def test_invalid_request_is_in_metrics_denominator() -> None:
    draft = request("draft", TaskType.DELIVERY, status=RequestStatus.DRAFT)

    result = run_plan(planning_input((draft,)))

    assert result.metrics.total_tasks == 1
    assert result.metrics.assigned_tasks == 0
    assert result.metrics.unassigned_tasks == 1
    assert result.metrics.assignment_percent == 0


def test_same_snapshot_settings_and_seed_return_identical_semantics() -> None:
    data = planning_input(
        tuple(
            request(
                f"request-{index}",
                TaskType.DELIVERY if index % 2 == 0 else TaskType.PICKUP,
                lon=37.7 + index / 100,
            )
            for index in range(8)
        )
    )
    settings = PlanningSettings(seed=945)

    assert run_plan(data, settings) == run_plan(data, settings)


def test_zero_search_budget_is_deterministic_and_returns_best_completed_plan() -> None:
    data = planning_input((request("d1", TaskType.DELIVERY),))
    settings = PlanningSettings(seed=9, max_optimization_seconds=0)

    first = run_plan(data, settings)
    second = run_plan(data, settings)

    assert first == second
    assert first.timed_out
    assert not first.cycles
    assert first.unassigned


def test_exhausted_search_budget_keeps_the_best_candidate_already_found() -> None:
    data = planning_input((request("d1", TaskType.DELIVERY),))
    settings = PlanningSettings(seed=9, max_optimization_seconds=1 / 50_000)

    result = run_plan(data, settings)

    assert result.timed_out
    assert len(result.cycles) == 1
    assert result.cycles[0].task_ids == ("d1:part:1",)
    assert not result.unassigned


def test_locked_cycle_is_byte_for_byte_unchanged_during_reoptimization() -> None:
    data = planning_input((request("d1", TaskType.DELIVERY, quantity=2),))
    first = run_plan(data)
    locked = replace(first.cycles[0], locked=True)
    reoptimization_input = replace(data, locked_cycles=(locked,))

    result = run_plan(reoptimization_input)

    assert locked in result.cycles
    assert result.cycles[0] == locked


def test_trace_contains_real_phases_and_assignment_events() -> None:
    result = run_plan(planning_input((request("d1", TaskType.DELIVERY),)))

    phases = {event.phase for event in result.trace_events}
    assert TracePhase.BUILDING_TRAVEL_MATRIX in phases
    assert TracePhase.ASSIGNING_DRIVERS in phases
    assert TracePhase.COMPLETED in phases


def test_candidate_evaluations_stay_bounded_across_driver_shifts() -> None:
    """The same lightweight combination set must be shared by all free shifts."""

    vehicles = tuple(Vehicle(f"vehicle-{index}", f"Машина {index}", 2) for index in range(3))
    shifts = tuple(
        DriverShift(
            f"shift-{index}",
            f"driver-{index}",
            f"Водитель {index}",
            vehicles[index].id,
            aware(8),
            aware(20),
        )
        for index in range(3)
    )
    requests = tuple(
        replace(
            request(
                f"perf-{index:03}",
                TaskType.DELIVERY if index % 2 == 0 else TaskType.PICKUP,
                lon=37.61 + (index % 8) * 0.008,
                lat=55.70 + (index // 8) * 0.008,
                priority=100 - index,
                hard=False,
            ),
            service_minutes=20,
        )
        for index in range(24)
    )
    data = replace(planning_input(requests), vehicles=vehicles, shifts=shifts)

    result = run_plan(
        data,
        PlanningSettings(
            seed=17,
            max_candidate_neighbors=8,
            max_local_search_iterations=20,
            max_trace_events=10_000,
        ),
    )

    completed = next(
        event
        for event in result.trace_events
        if event.phase is TracePhase.BUILDING_CYCLES and event.event_type.value == "phase_completed"
    )
    evaluation_count = completed.payload["evaluation_count"]
    assert isinstance(evaluation_count, int)
    assert evaluation_count <= len(requests) * len(shifts) * 4
    assert result.metrics.assigned_tasks == len(requests)
    assert not result.timed_out
