"""Behavioral tests for the deterministic two-cabin heuristic."""

# ruff: noqa: RUF001 -- Russian domain labels are intentional test data.

from __future__ import annotations

import asyncio
from dataclasses import replace
from datetime import date, datetime, timedelta
from itertools import pairwise
from zoneinfo import ZoneInfo

from app.planner import (
    DriverShift,
    HeuristicPlanner,
    LogisticsRequest,
    NullProgressPublisher,
    PlanningInput,
    PlanningResult,
    PlanningSettings,
    RelationType,
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
    ZoneRelation,
    ZoneSnapshot,
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
    zone_id: str | None = "z1",
    priority: int = 1,
    hard: bool = True,
    window_start: datetime | None = None,
    window_end: datetime | None = None,
    status: RequestStatus = RequestStatus.READY,
    split_allowed: bool = True,
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
        zone_id=zone_id,
        zone_version=1 if zone_id else None,
        date_options=(
            RequestDateOption(
                PLANNING_DATE,
                priority=1,
                window_start=window_start or aware(8),
                window_end=window_end or aware(19),
                is_hard=hard,
            ),
        ),
        created_at=aware(7),
        split_allowed=split_allowed,
    )


def planning_input(
    requests: tuple[LogisticsRequest, ...],
    *,
    shift_end: datetime | None = None,
    preferred_group: str | None = "WEST",
    relations: tuple[ZoneRelation, ...] | None = None,
    shifts: tuple[DriverShift, ...] | None = None,
) -> PlanningInput:
    default_relations = (
        ZoneRelation(
            "z1",
            "z2",
            RelationType.ADJACENT,
            max_detour_minutes=120,
            max_detour_ratio=5,
            is_bidirectional=True,
        ),
    )
    vehicle = Vehicle("vehicle-1", "А123БВ", capacity=2)
    default_shifts = (
        DriverShift(
            "shift-1",
            "driver-1",
            "Водитель 1",
            vehicle.id,
            aware(8),
            shift_end or aware(20),
            preferred_group,
        ),
    )
    return PlanningInput(
        scenario_id="scenario-1",
        planning_date=PLANNING_DATE,
        warehouse=Warehouse("warehouse-1", "Склад", GeoPoint(37.6, 55.7, True), 5, 5, 5),
        requests=requests,
        zones=(
            ZoneSnapshot("z1", "Z1", "WEST"),
            ZoneSnapshot("z2", "Z2", "EAST"),
        ),
        zone_relations=default_relations if relations is None else relations,
        shifts=default_shifts if shifts is None else shifts,
        vehicles=(vehicle,),
    )


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


def test_two_single_deliveries_are_paired_and_load_never_exceeds_capacity() -> None:
    result = run_plan(
        planning_input(
            (
                request("d1", TaskType.DELIVERY, lon=37.70),
                request("d2", TaskType.DELIVERY, lon=37.72, zone_id="z2"),
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


def test_two_single_pickups_pair_and_quantity_two_pickup_uses_full_capacity() -> None:
    paired = run_plan(
        planning_input(
            (
                request("p1", TaskType.PICKUP, lon=37.70),
                request("p2", TaskType.PICKUP, lon=37.72, zone_id="z2"),
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
                request("d2", TaskType.DELIVERY, lon=38.21, zone_id="z2"),
                request("p1", TaskType.PICKUP, lon=38.0, zone_id="z2"),
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

    relations = (
        ZoneRelation(
            "z1",
            "z2",
            RelationType.ADJACENT,
            max_detour_minutes=35,
            max_detour_ratio=2,
            is_bidirectional=True,
        ),
    )
    result = run_plan(
        planning_input(
            tuple(
                with_second_allowed_date(item)
                for item in (
                    request("d1", TaskType.DELIVERY, lon=37.70, zone_id="z1"),
                    request("d2", TaskType.DELIVERY, lon=37.72, zone_id="z2"),
                    request("p1", TaskType.PICKUP, lon=37.71, zone_id="z2"),
                    request("p2", TaskType.PICKUP, lon=37.69, zone_id="z1"),
                )
            ),
            relations=relations,
        ),
        PlanningSettings(seed=17, max_detour_minutes=35, max_detour_ratio=2),
    )

    assert len(result.cycles) == 1
    assert [stop.load_after for stop in result.cycles[0].stops] == [2, 1, 0, 1, 2, 0]


def test_delivery_prefers_compatible_return_pickup_over_pickup_only_cycle() -> None:
    """A last-date return must be attached after a feasible delivery when possible."""

    delivery = request("d1", TaskType.DELIVERY, lon=37.70, zone_id="z1", hard=False)
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


def test_one_driver_can_receive_multiple_cycles() -> None:
    result = run_plan(
        planning_input(
            tuple(
                request(f"d{index}", TaskType.DELIVERY, quantity=2, lon=37.7 + index / 100)
                for index in range(3)
            )
        )
    )

    assert len(result.cycles) == 3
    assert {cycle.driver_shift_id for cycle in result.cycles} == {"shift-1"}
    for previous, following in pairwise(result.cycles):
        assert previous.planned_finish < following.planned_start


def test_driver_route_group_is_soft_and_cross_group_pickup_can_be_on_return() -> None:
    settings = PlanningSettings(seed=17, max_detour_minutes=240, max_detour_ratio=10)
    result = run_plan(
        planning_input(
            (
                request("east-delivery", TaskType.DELIVERY, lon=38.2, zone_id="z2"),
                request("west-pickup", TaskType.PICKUP, lon=37.9, zone_id="z1"),
            ),
            preferred_group="NORTH",
            relations=(),
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
    assert ValidationWarningCode.CROSS_ROUTE_GROUP in mixed[0].warnings


def test_blocked_transition_or_large_detour_prevents_mixed_cycle() -> None:
    blocked = ZoneRelation("z1", "z2", RelationType.BLOCKED, is_bidirectional=True)
    result = run_plan(
        planning_input(
            (
                request("delivery", TaskType.DELIVERY, lon=37.8, zone_id="z1"),
                request("pickup", TaskType.PICKUP, lon=38.5, zone_id="z2"),
            ),
            relations=(blocked,),
        ),
        PlanningSettings(seed=17, max_detour_minutes=1, max_detour_ratio=0.01),
    )

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
                zone_id="z1" if index % 3 else "z2",
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
            ("WEST", "EAST", "REGION")[index],
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
                zone_id="z1" if index % 4 < 2 else "z2",
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
        if event.phase is TracePhase.BUILDING_CYCLES
        and event.event_type.value == "phase_completed"
    )
    evaluation_count = completed.payload["evaluation_count"]
    assert isinstance(evaluation_count, int)
    assert evaluation_count <= len(requests) * len(shifts) * 2
    assert result.metrics.assigned_tasks == len(requests)
    assert not result.timed_out
