"""Property-based checks for planner invariants across generated task mixes."""

from __future__ import annotations

import asyncio
from datetime import date, datetime
from zoneinfo import ZoneInfo

from hypothesis import given, settings
from hypothesis import strategies as st

from app.planner import (
    DriverShift,
    HeuristicPlanner,
    LogisticsRequest,
    NullProgressPublisher,
    PlanningInput,
    PlanningSettings,
    RequestDateOption,
    RequestStatus,
    StopType,
    TaskType,
    Vehicle,
    Warehouse,
    ZoneSnapshot,
    split_request,
)
from app.routing import GeoPoint, MockRoutingProvider, RoutingSettings

MOSCOW = ZoneInfo("Europe/Moscow")
DAY = date(2026, 8, 25)


def at(hour: int) -> datetime:
    return datetime(2026, 8, 25, hour, tzinfo=MOSCOW)


def source_request(quantity: int) -> LogisticsRequest:
    return LogisticsRequest(
        "source",
        TaskType.DELIVERY,
        "Source",
        "Address",
        GeoPoint(37.7, 55.7, True),
        quantity,
        5,
        1,
        RequestStatus.READY,
        "zone",
        1,
        (RequestDateOption(DAY, 1, at(8), at(20), True),),
        at(7),
    )


@given(quantity=st.integers(min_value=1, max_value=100))
def test_split_preserves_quantity_and_transport_part_bounds(quantity: int) -> None:
    source = source_request(quantity)

    parts = split_request(
        source,
        source.date_options[0],
        remaining_date_count=1,
        is_last_available_date=True,
    )

    assert sum(part.quantity for part in parts) == quantity
    assert all(1 <= part.quantity <= 2 for part in parts)


@given(
    task_specs=st.lists(
        st.tuples(
            st.sampled_from([TaskType.DELIVERY, TaskType.PICKUP]),
            st.integers(min_value=1, max_value=2),
            st.integers(min_value=0, max_value=50),
        ),
        min_size=1,
        max_size=12,
    )
)
@settings(max_examples=30, deadline=None)
def test_generated_plans_preserve_all_hard_route_invariants(
    task_specs: list[tuple[TaskType, int, int]],
) -> None:
    requests = tuple(
        LogisticsRequest(
            id=f"r{index}",
            request_type=task_type,
            name=f"R{index}",
            address_label=f"A{index}",
            point=GeoPoint(37.65 + offset / 1000, 55.72 + index / 10000, True),
            quantity=quantity,
            service_minutes=3,
            priority=index % 3,
            status=RequestStatus.READY,
            zone_id="zone",
            zone_version=1,
            date_options=(RequestDateOption(DAY, 1, at(8), at(22), True),),
            created_at=at(7),
        )
        for index, (task_type, quantity, offset) in enumerate(task_specs)
    )
    warehouse = Warehouse("w", "Склад", GeoPoint(37.6, 55.7, True), 2, 2, 2)
    vehicle = Vehicle("v", "Машина", 2)
    shift = DriverShift("s", "d", "Водитель", "v", at(8), at(23))
    data = PlanningInput(
        "scenario",
        DAY,
        warehouse,
        requests,
        (ZoneSnapshot("zone", "Z", "WEST"),),
        (),
        (shift,),
        (vehicle,),
    )
    planner = HeuristicPlanner(
        MockRoutingProvider(RoutingSettings(deterministic_noise_ratio=0, road_factor=1.05))
    )

    result = asyncio.run(
        planner.generate_plan(
            data,
            PlanningSettings(
                max_detour_minutes=240,
                max_detour_ratio=10,
                max_local_search_iterations=10,
            ),
            NullProgressPublisher(),
        )
    )

    assigned_ids: list[str] = []
    for cycle in result.cycles:
        assert cycle.stops[0].stop_type is StopType.DEPOT_LOAD
        assert cycle.stops[-1].stop_type is StopType.DEPOT_RETURN
        assert cycle.stops[0].point.coordinates == warehouse.point.coordinates
        assert cycle.stops[-1].point.coordinates == warehouse.point.coordinates
        delivery_stops = [
            stop for stop in cycle.stops if stop.stop_type is StopType.DELIVERY
        ]
        pickup_stops = [
            stop for stop in cycle.stops if stop.stop_type is StopType.PICKUP
        ]
        assert cycle.stops[0].load_after == sum(
            -stop.quantity_delta for stop in delivery_stops
        )
        assert all(stop.quantity_delta < 0 for stop in delivery_stops)
        assert all(stop.quantity_delta > 0 for stop in pickup_stops)
        assert all(0 <= stop.load_before <= 2 for stop in cycle.stops)
        assert all(0 <= stop.load_after <= 2 for stop in cycle.stops)
        customer_types = [
            stop.stop_type
            for stop in cycle.stops
            if stop.stop_type in {StopType.DELIVERY, StopType.PICKUP}
        ]
        pickup_indexes = [
            index for index, stop_type in enumerate(customer_types) if stop_type is StopType.PICKUP
        ]
        delivery_indexes = [
            index
            for index, stop_type in enumerate(customer_types)
            if stop_type is StopType.DELIVERY
        ]
        if pickup_indexes and delivery_indexes:
            assert max(delivery_indexes) < min(pickup_indexes)
        assigned_ids.extend(cycle.task_ids)
    driver_customer_cycles: dict[str, list[list[StopType]]] = {}
    for cycle in result.cycles:
        driver_customer_cycles.setdefault(cycle.driver_shift_id, []).append(
            [
                stop.stop_type
                for stop in cycle.stops
                if stop.stop_type in {StopType.DELIVERY, StopType.PICKUP}
            ]
        )
    for customer_cycles in driver_customer_cycles.values():
        for customer_types in customer_cycles:
            pickup_indexes = [
                index
                for index, stop_type in enumerate(customer_types)
                if stop_type is StopType.PICKUP
            ]
            delivery_indexes = [
                index
                for index, stop_type in enumerate(customer_types)
                if stop_type is StopType.DELIVERY
            ]
            if pickup_indexes and delivery_indexes:
                assert max(delivery_indexes) < min(pickup_indexes)
    assert len(assigned_ids) == len(set(assigned_ids))
