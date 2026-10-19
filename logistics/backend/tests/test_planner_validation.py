"""Pure validation tests for manual route edits and concurrency fences."""

from __future__ import annotations

import asyncio
from dataclasses import replace
from datetime import date, datetime, timedelta
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
    RequestDateOption,
    RequestStatus,
    RouteCycle,
    StopType,
    TaskType,
    ValidationErrorCode,
    ValidationResult,
    ValidationWarningCode,
    Vehicle,
    Warehouse,
    validate_route_plan,
)
from app.routing import GeoPoint, MockRoutingProvider, RoutingSettings

MOSCOW = ZoneInfo("Europe/Moscow")
DAY = date(2026, 8, 25)


def at(hour: int, minute: int = 0) -> datetime:
    return datetime(2026, 8, 25, hour, minute, tzinfo=MOSCOW)


def generated_cycle(
    request_types: tuple[TaskType, ...],
) -> tuple[RouteCycle, PlanningInput, PlanningResult]:
    warehouse = Warehouse("w", "Склад", GeoPoint(37.6, 55.7, True), 5, 5, 5)
    vehicle = Vehicle("v", "Машина", 2)
    shift = DriverShift("s", "d", "Водитель", "v", at(8), at(20))
    requests = tuple(
        LogisticsRequest(
            id=f"r{index}",
            request_type=request_type,
            name=f"R{index}",
            address_label=f"A{index}",
            point=GeoPoint(37.75 + index / 100, 55.75, True),
            quantity=1,
            service_minutes=10,
            priority=1,
            status=RequestStatus.READY,
            date_options=(RequestDateOption(DAY, 1, at(8), at(19), True),),
            created_at=at(7),
        )
        for index, request_type in enumerate(request_types)
    )
    data = PlanningInput(
        warehouse_id="warehouse",
        planning_date=DAY,
        warehouse=warehouse,
        requests=requests,
        shifts=(shift,),
        vehicles=(vehicle,),
    )
    result = asyncio.run(
        HeuristicPlanner(
            MockRoutingProvider(RoutingSettings(deterministic_noise_ratio=0))
        ).generate_plan(data, PlanningSettings(max_detour_ratio=10), NullProgressPublisher())
    )
    return result.cycles[0], data, result


def error_codes(
    result: ValidationResult,
) -> set[ValidationErrorCode | ValidationWarningCode]:
    return {issue.code for issue in result.errors}


def test_validator_detects_negative_load_and_invalid_task_quantity() -> None:
    cycle, data, _ = generated_cycle((TaskType.DELIVERY,))
    customer = cycle.stops[1]
    bad_customer = replace(
        customer,
        quantity_delta=-3,
        load_after=customer.load_before - 3,
    )
    bad_return = replace(
        cycle.stops[-1],
        load_before=bad_customer.load_after,
        quantity_delta=-bad_customer.load_after,
    )
    bad_cycle = replace(cycle, stops=(cycle.stops[0], bad_customer, bad_return))

    validation = validate_route_plan(
        (bad_cycle,),
        warehouse=data.warehouse,
        shifts=data.shifts,
        vehicles=data.vehicles,
        settings=PlanningSettings(),
    )

    assert ValidationErrorCode.NEGATIVE_LOAD in error_codes(validation)
    assert ValidationErrorCode.INVALID_TASK_QUANTITY in error_codes(validation)


def test_validator_detects_capacity_exceeded() -> None:
    cycle, data, _ = generated_cycle((TaskType.PICKUP,))
    customer = cycle.stops[1]
    bad_customer = replace(customer, quantity_delta=3, load_after=3)
    bad_return = replace(cycle.stops[-1], load_before=3, quantity_delta=-3, load_after=0)
    bad_cycle = replace(cycle, stops=(cycle.stops[0], bad_customer, bad_return))

    validation = validate_route_plan(
        (bad_cycle,),
        warehouse=data.warehouse,
        shifts=data.shifts,
        vehicles=data.vehicles,
        settings=PlanningSettings(),
    )

    assert ValidationErrorCode.CAPACITY_EXCEEDED in error_codes(validation)


def test_domain_and_validator_reject_load_and_aggregate_metric_mismatches() -> None:
    """Manual edits cannot forge the load transition or persisted route totals."""

    cycle, data, _ = generated_cycle((TaskType.DELIVERY,))
    customer = cycle.stops[1]
    with pytest.raises(ValueError, match="load_after must equal"):
        replace(customer, load_after=customer.load_before)
    bad_cycle = replace(
        cycle,
        total_distance_meters=cycle.total_distance_meters + 1,
        waiting_seconds=cycle.waiting_seconds + 60,
    )

    validation = validate_route_plan(
        (bad_cycle,),
        warehouse=data.warehouse,
        shifts=data.shifts,
        vehicles=data.vehicles,
        settings=PlanningSettings(),
    )

    codes = error_codes(validation)
    assert ValidationErrorCode.INVALID_TIME in codes


def test_validator_allows_capacity_safe_manual_pickup_delivery_interleaving() -> None:
    """A pickup can precede a later delivery when every load transition remains safe."""

    cycle, data, _ = generated_cycle(
        (TaskType.DELIVERY, TaskType.DELIVERY, TaskType.PICKUP)
    )
    depot, first_delivery, second_delivery, pickup, depot_return = cycle.stops
    interleaved = replace(
        cycle,
        stops=(
            depot,
            first_delivery,
            replace(
                second_delivery,
                stop_type=StopType.PICKUP,
                task_id=pickup.task_id,
                quantity_delta=1,
                load_before=1,
                load_after=2,
            ),
            replace(
                pickup,
                stop_type=StopType.DELIVERY,
                task_id=second_delivery.task_id,
                quantity_delta=-1,
                load_before=2,
                load_after=1,
            ),
            replace(depot_return, load_before=1, quantity_delta=-1),
        ),
    )

    validation = validate_route_plan(
        (interleaved,),
        warehouse=data.warehouse,
        shifts=data.shifts,
        vehicles=data.vehicles,
        settings=PlanningSettings(max_detour_ratio=10),
    )

    assert validation.valid


def test_validator_rejects_interleaving_that_exceeds_capacity() -> None:
    """Removing the blanket ordering rule does not weaken dynamic load validation."""

    cycle, data, _ = generated_cycle(
        (
            TaskType.DELIVERY,
            TaskType.DELIVERY,
            TaskType.PICKUP,
            TaskType.PICKUP,
        )
    )
    depot, delivery_one, delivery_two, pickup_one, pickup_two, depot_return = cycle.stops
    overloaded = replace(
        cycle,
        stops=(
            depot,
            delivery_one,
            replace(
                delivery_two,
                stop_type=StopType.PICKUP,
                task_id=pickup_one.task_id,
                quantity_delta=1,
                load_before=1,
                load_after=2,
            ),
            replace(
                pickup_one,
                task_id=pickup_two.task_id,
                quantity_delta=1,
                load_before=2,
                load_after=3,
            ),
            replace(
                pickup_two,
                stop_type=StopType.DELIVERY,
                task_id=delivery_two.task_id,
                quantity_delta=-1,
                load_before=3,
                load_after=2,
            ),
            replace(depot_return, load_before=2, quantity_delta=-2),
        ),
    )

    validation = validate_route_plan(
        (overloaded,),
        warehouse=data.warehouse,
        shifts=data.shifts,
        vehicles=data.vehicles,
        settings=PlanningSettings(max_detour_ratio=10),
    )

    assert ValidationErrorCode.CAPACITY_EXCEEDED in error_codes(validation)


def test_validator_rejects_pickup_load_disguised_as_delivery() -> None:
    """A delivery cannot increase load and consume a cabin collected later."""

    cycle, data, _ = generated_cycle((TaskType.DELIVERY, TaskType.PICKUP))
    depot_load, delivery, pickup, depot_return = cycle.stops
    forged_cycle = replace(
        cycle,
        stops=(
            replace(depot_load, quantity_delta=0, load_after=0),
            replace(delivery, quantity_delta=1, load_before=0, load_after=1),
            replace(pickup, quantity_delta=-1, load_before=1, load_after=0),
            replace(depot_return, quantity_delta=0, load_before=0, load_after=0),
        ),
    )

    validation = validate_route_plan(
        (forged_cycle,),
        warehouse=data.warehouse,
        shifts=data.shifts,
        vehicles=data.vehicles,
        settings=PlanningSettings(max_detour_ratio=10),
    )

    assert ValidationErrorCode.LOAD_DISCONTINUITY in error_codes(validation)


def test_validator_requires_every_delivery_cabin_to_be_loaded_at_depot() -> None:
    cycle, data, _ = generated_cycle((TaskType.DELIVERY,))
    depot_load, delivery, depot_return = cycle.stops
    bad_cycle = replace(
        cycle,
        stops=(
            replace(depot_load, quantity_delta=2, load_after=2),
            replace(delivery, load_before=2, load_after=1),
            replace(depot_return, quantity_delta=-1, load_before=1),
        ),
    )

    validation = validate_route_plan(
        (bad_cycle,),
        warehouse=data.warehouse,
        shifts=data.shifts,
        vehicles=data.vehicles,
        settings=PlanningSettings(),
    )

    assert ValidationErrorCode.LOAD_DISCONTINUITY in error_codes(validation)


def test_validator_allows_delivery_in_later_depot_cycle_after_pickup() -> None:
    pickup_cycle, data, _ = generated_cycle((TaskType.PICKUP,))
    delivery_cycle, _, _ = generated_cycle((TaskType.DELIVERY,))
    delay = timedelta(hours=4)
    later_delivery = replace(
        delivery_cycle,
        id="later-delivery",
        sequence=pickup_cycle.sequence + 1,
        planned_start=delivery_cycle.planned_start + delay,
        planned_finish=delivery_cycle.planned_finish + delay,
        stops=tuple(
            replace(
                stop,
                task_id="delivery-task" if stop.task_id is not None else None,
                planned_arrival=stop.planned_arrival + delay,
                planned_departure=stop.planned_departure + delay,
            )
            for stop in delivery_cycle.stops
        ),
        legs=tuple(
            replace(
                leg,
                departure_at=leg.departure_at + delay,
                arrival_at=leg.arrival_at + delay,
            )
            for leg in delivery_cycle.legs
        ),
    )

    validation = validate_route_plan(
        (pickup_cycle, later_delivery),
        warehouse=data.warehouse,
        shifts=data.shifts,
        vehicles=data.vehicles,
        settings=PlanningSettings(),
    )

    assert validation.valid


def test_validator_keeps_different_driver_shifts_independent() -> None:
    pickup_cycle, data, _ = generated_cycle((TaskType.PICKUP,))
    delivery_cycle, _, _ = generated_cycle((TaskType.DELIVERY,))
    second_vehicle = replace(data.vehicles[0], id="second-vehicle", name="Вторая машина")
    second_shift = replace(
        data.shifts[0],
        id="second-shift",
        driver_id="second-driver",
        driver_name="Второй водитель",
        vehicle_id=second_vehicle.id,
    )
    other_driver_delivery = replace(
        delivery_cycle,
        id="other-driver-delivery",
        driver_shift_id=second_shift.id,
        driver_id=second_shift.driver_id,
        vehicle_id=second_vehicle.id,
        stops=tuple(
            replace(
                stop,
                task_id="other-driver-delivery-task" if stop.task_id is not None else None,
            )
            for stop in delivery_cycle.stops
        ),
    )

    validation = validate_route_plan(
        (pickup_cycle, other_driver_delivery),
        warehouse=data.warehouse,
        shifts=(*data.shifts, second_shift),
        vehicles=(*data.vehicles, second_vehicle),
        settings=PlanningSettings(),
    )

    assert validation.valid


def test_validator_detects_duplicate_assignment_driver_and_vehicle_overlap() -> None:
    cycle, data, _ = generated_cycle((TaskType.DELIVERY,))
    duplicate = replace(cycle, id="duplicate-cycle")

    validation = validate_route_plan(
        (cycle, duplicate),
        warehouse=data.warehouse,
        shifts=data.shifts,
        vehicles=data.vehicles,
        settings=PlanningSettings(),
    )

    codes = error_codes(validation)
    assert ValidationErrorCode.TASK_ALREADY_ASSIGNED in codes
    assert ValidationErrorCode.DRIVER_OVERLAP in codes
    assert ValidationErrorCode.VEHICLE_OVERLAP in codes


def test_validator_detects_route_not_returned_to_depot() -> None:
    cycle, data, _ = generated_cycle((TaskType.DELIVERY,))
    wrong_return = replace(cycle.stops[-1], point=GeoPoint(38.0, 56.0))
    bad_cycle = replace(cycle, stops=(*cycle.stops[:-1], wrong_return))

    validation = validate_route_plan(
        (bad_cycle,),
        warehouse=data.warehouse,
        shifts=data.shifts,
        vehicles=data.vehicles,
        settings=PlanningSettings(),
    )

    assert ValidationErrorCode.ROUTE_NOT_RETURNED_TO_DEPOT in error_codes(validation)


def test_overtime_is_error_unless_explicitly_allowed_as_soft() -> None:
    cycle, data, _ = generated_cycle((TaskType.DELIVERY,))
    short_shift = replace(
        data.shifts[0],
        end_at=cycle.planned_finish - timedelta(minutes=5),
    )

    hard = validate_route_plan(
        (cycle,),
        warehouse=data.warehouse,
        shifts=(short_shift,),
        vehicles=data.vehicles,
        settings=PlanningSettings(),
    )
    soft = validate_route_plan(
        (cycle,),
        warehouse=data.warehouse,
        shifts=(short_shift,),
        vehicles=data.vehicles,
        settings=PlanningSettings(allow_soft_overtime=True, soft_overtime_limit_minutes=10),
    )

    assert ValidationErrorCode.SHIFT_EXCEEDED in error_codes(hard)
    assert not soft.errors
    assert ValidationWarningCode.OVERTIME_WARNING in {issue.code for issue in soft.warnings}


def test_start_within_window_allows_service_completion_after_window_end() -> None:
    """START_WITHIN_SLOT checks arrival/service start, not the end of unloading."""

    cycle, data, _ = generated_cycle((TaskType.DELIVERY,))
    stop = cycle.stops[1]
    constrained = replace(
        stop,
        window_start=stop.planned_arrival,
        window_end=stop.planned_departure - timedelta(seconds=1),
        window_is_hard=True,
    )
    valid_cycle = replace(cycle, stops=(cycle.stops[0], constrained, cycle.stops[-1]))

    validation = validate_route_plan(
        (valid_cycle,),
        warehouse=data.warehouse,
        shifts=data.shifts,
        vehicles=data.vehicles,
        settings=PlanningSettings(),
    )

    assert ValidationErrorCode.TIME_WINDOW_VIOLATION not in error_codes(validation)


def test_validator_rejects_early_customer_arrival_hidden_inside_long_stop() -> None:
    """Waiting until an appointment cannot be disguised as customer service time."""

    cycle, data, _ = generated_cycle((TaskType.DELIVERY,))
    depot, customer, depot_return = cycle.stops
    window_start = customer.planned_arrival + timedelta(minutes=30)
    stretched_customer = replace(
        customer,
        planned_departure=window_start + timedelta(seconds=customer.service_seconds),
        window_start=window_start,
        window_end=window_start + timedelta(hours=2),
        window_is_hard=True,
    )
    shifted_return = replace(
        depot_return,
        planned_arrival=depot_return.planned_arrival + timedelta(minutes=30),
        planned_departure=depot_return.planned_departure + timedelta(minutes=30),
    )
    bad_cycle = replace(
        cycle,
        planned_finish=shifted_return.planned_departure,
        stops=(depot, stretched_customer, shifted_return),
        legs=(
            cycle.legs[0],
            replace(
                cycle.legs[1],
                departure_at=stretched_customer.planned_departure,
                arrival_at=shifted_return.planned_arrival,
            ),
        ),
    )

    validation = validate_route_plan(
        (bad_cycle,),
        warehouse=data.warehouse,
        shifts=data.shifts,
        vehicles=data.vehicles,
        settings=PlanningSettings(),
    )

    assert ValidationErrorCode.INVALID_TIME in error_codes(validation)
    assert ValidationErrorCode.TIME_WINDOW_VIOLATION in error_codes(validation)


def test_validator_allows_waiting_gap_before_a_route_leg() -> None:
    """A bounded source wait is valid, while the configured limit remains hard."""

    cycle, data, _ = generated_cycle((TaskType.DELIVERY,))
    delay = timedelta(minutes=10)
    depot, customer, depot_return = cycle.stops
    shifted_return = replace(
        depot_return,
        planned_arrival=depot_return.planned_arrival + delay,
        planned_departure=depot_return.planned_departure + delay,
    )
    waiting_cycle = replace(
        cycle,
        planned_finish=shifted_return.planned_departure,
        waiting_seconds=cycle.waiting_seconds + round(delay.total_seconds()),
        stops=(depot, customer, shifted_return),
        legs=(
            cycle.legs[0],
            replace(
                cycle.legs[1],
                departure_at=cycle.legs[1].departure_at + delay,
                arrival_at=cycle.legs[1].arrival_at + delay,
            ),
        ),
    )

    validation = validate_route_plan(
        (waiting_cycle,),
        warehouse=data.warehouse,
        shifts=data.shifts,
        vehicles=data.vehicles,
        settings=PlanningSettings(),
    )
    excessive = validate_route_plan(
        (waiting_cycle,),
        warehouse=data.warehouse,
        shifts=data.shifts,
        vehicles=data.vehicles,
        settings=PlanningSettings(max_customer_wait_minutes=5),
    )

    assert ValidationErrorCode.INVALID_TIME not in error_codes(validation)
    assert ValidationErrorCode.TIME_WINDOW_VIOLATION in error_codes(excessive)
