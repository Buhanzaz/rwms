"""Pure plan validation and metric calculation shared by automatic/manual flows."""

# ruff: noqa: RUF001 -- Russian operator-facing validation text is intentional.

from __future__ import annotations

from collections import Counter, defaultdict
from collections.abc import Iterable
from datetime import timedelta
from itertools import pairwise

from .models import (
    DriverShift,
    PlanMetrics,
    PlanningSettings,
    RouteCycle,
    StopType,
    ValidationErrorCode,
    ValidationIssue,
    ValidationResult,
    ValidationWarningCode,
    Vehicle,
    Warehouse,
)
from .workload import shift_duty_seconds, shift_usable_seconds


def calculate_plan_metrics(
    cycles: Iterable[RouteCycle],
    *,
    total_tasks: int,
    unassigned_tasks: int,
    score: float,
    shifts: Iterable[DriverShift] = (),
) -> PlanMetrics:
    """Calculate stable aggregate metrics from final cycle snapshots."""

    cycle_list = tuple(cycles)
    shift_list = tuple(shifts)
    shift_by_id = {shift.id: shift for shift in shift_list}
    assigned = max(0, total_tasks - unassigned_tasks)
    distance = sum(cycle.total_distance_meters for cycle in cycle_list)
    empty_distance = sum(cycle.empty_distance_meters for cycle in cycle_list)
    delivery_pairs = 0
    pickup_pairs = 0
    loads: list[int] = []
    overtime_seconds = 0
    buffers: list[int] = []
    for cycle in cycle_list:
        delivery_count = sum(stop.stop_type is StopType.DELIVERY for stop in cycle.stops)
        pickup_count = sum(stop.stop_type is StopType.PICKUP for stop in cycle.stops)
        delivery_pairs += int(delivery_count == 2)
        pickup_pairs += int(pickup_count == 2)
        loads.extend(stop.load_after for stop in cycle.stops[:-1])
        shift = shift_by_id.get(cycle.driver_shift_id)
        if shift is not None:
            buffer_seconds = round((shift.end_at - cycle.planned_finish).total_seconds())
            buffers.append(buffer_seconds)
            overtime_seconds += max(0, -buffer_seconds)
    used_shifts = tuple(
        shift
        for shift in shift_list
        if any(cycle.driver_shift_id == shift.id for cycle in cycle_list)
    )
    total_usable_seconds = sum(shift_usable_seconds(shift) for shift in used_shifts)
    total_duty_seconds = sum(shift_duty_seconds(cycle_list, shift) for shift in used_shifts)
    return PlanMetrics(
        total_tasks=total_tasks,
        assigned_tasks=assigned,
        unassigned_tasks=unassigned_tasks,
        assignment_percent=(assigned / total_tasks * 100.0 if total_tasks else 100.0),
        cycle_count=len(cycle_list),
        active_shift_count=len(used_shifts),
        total_distance_meters=distance,
        empty_distance_meters=empty_distance,
        empty_distance_percent=(empty_distance / distance * 100.0 if distance else 0.0),
        total_travel_seconds=sum(cycle.total_travel_seconds for cycle in cycle_list),
        total_service_seconds=sum(cycle.total_service_seconds for cycle in cycle_list),
        total_waiting_seconds=sum(cycle.waiting_seconds for cycle in cycle_list),
        total_detour_seconds=sum(cycle.detour_seconds for cycle in cycle_list),
        paired_delivery_count=delivery_pairs,
        paired_pickup_count=pickup_pairs,
        average_vehicle_load=(sum(loads) / len(loads) if loads else 0.0),
        shift_utilization_percent=(
            total_duty_seconds / total_usable_seconds * 100.0 if total_usable_seconds else 0.0
        ),
        overtime_seconds=overtime_seconds,
        minimum_buffer_seconds=min(buffers, default=0),
        score=score,
    )


def validate_route_plan(
    cycles: Iterable[RouteCycle],
    *,
    warehouse: Warehouse,
    shifts: Iterable[DriverShift],
    vehicles: Iterable[Vehicle],
    settings: PlanningSettings,
    total_tasks: int | None = None,
    unassigned_tasks: int = 0,
    score: float = 0.0,
) -> ValidationResult:
    """Validate hard invariants, including source-side waiting between legs."""

    cycle_list = tuple(cycles)
    shift_by_id = {shift.id: shift for shift in shifts}
    vehicle_by_id = {vehicle.id: vehicle for vehicle in vehicles}
    errors: list[ValidationIssue] = []
    warnings: list[ValidationIssue] = []
    assigned_tasks: list[tuple[str, str]] = []

    for cycle in cycle_list:
        vehicle = vehicle_by_id.get(cycle.vehicle_id)
        capacity = vehicle.capacity if vehicle is not None else settings.vehicle_capacity
        if (
            len(cycle.stops) < 2
            or cycle.stops[0].stop_type is not StopType.DEPOT_LOAD
            or cycle.stops[-1].stop_type is not StopType.DEPOT_RETURN
            or cycle.stops[0].point.coordinates != warehouse.point.coordinates
            or cycle.stops[-1].point.coordinates != warehouse.point.coordinates
        ):
            errors.append(
                ValidationIssue(
                    ValidationErrorCode.ROUTE_NOT_RETURNED_TO_DEPOT,
                    "Рейсовый цикл должен начинаться и заканчиваться на складе.",
                    cycle.id,
                )
            )
        if cycle.stops and (
            cycle.planned_start != cycle.stops[0].planned_arrival
            or cycle.planned_finish != cycle.stops[-1].planned_departure
        ):
            errors.append(
                ValidationIssue(
                    ValidationErrorCode.INVALID_TIME,
                    "Границы рейса не совпадают со временем первой и последней остановки.",
                    cycle.id,
                )
            )
        if len(cycle.legs) != max(0, len(cycle.stops) - 1):
            errors.append(
                ValidationIssue(
                    ValidationErrorCode.ROUTE_NOT_RETURNED_TO_DEPOT,
                    "Маршрут не содержит участок между каждой парой остановок.",
                    cycle.id,
                )
            )
        calculated_waiting_seconds = 0
        for leg_index, leg in enumerate(cycle.legs):
            if leg_index + 1 >= len(cycle.stops):
                break
            from_stop = cycle.stops[leg_index]
            to_stop = cycle.stops[leg_index + 1]
            routed_seconds = round((leg.arrival_at - leg.departure_at).total_seconds())
            source_waiting_seconds = max(
                0,
                round((leg.departure_at - from_stop.planned_departure).total_seconds()),
            )
            calculated_waiting_seconds += source_waiting_seconds
            if (
                from_stop.task_id is not None
                and source_waiting_seconds > settings.max_customer_wait_minutes * 60
            ):
                errors.append(
                    ValidationIssue(
                        ValidationErrorCode.TIME_WINDOW_VIOLATION,
                        (
                            "Ожидание у клиентской точки превышает лимит "
                            "планирования склада; задания нужно разделить на отдельные рейсы."
                        ),
                        cycle.id,
                        from_stop.task_id,
                    )
                )
            if (
                leg.from_stop_sequence != from_stop.sequence
                or leg.to_stop_sequence != to_stop.sequence
                or leg.departure_at < from_stop.planned_departure
                or leg.arrival_at != to_stop.planned_arrival
                or routed_seconds != leg.travel_seconds
            ):
                errors.append(
                    ValidationIssue(
                        ValidationErrorCode.INVALID_TIME,
                        (
                            "Время участка не согласовано с остановками или фактической "
                            "длительностью движения."
                        ),
                        cycle.id,
                    )
                )
        if (
            cycle.total_distance_meters != sum(leg.distance_meters for leg in cycle.legs)
            or cycle.total_travel_seconds != sum(leg.travel_seconds for leg in cycle.legs)
            or cycle.total_service_seconds != sum(stop.service_seconds for stop in cycle.stops)
            or cycle.waiting_seconds != calculated_waiting_seconds
        ):
            errors.append(
                ValidationIssue(
                    ValidationErrorCode.INVALID_TIME,
                    "Итоговые метрики рейса не совпадают с его участками и остановками.",
                    cycle.id,
                )
            )
        if not 0 <= cycle.empty_distance_meters <= cycle.total_distance_meters:
            errors.append(
                ValidationIssue(
                    ValidationErrorCode.LOAD_DISCONTINUITY,
                    "Пустой пробег должен находиться в пределах общего пробега рейса.",
                    cycle.id,
                )
            )

        previous_departure = None
        previous_load_after: int | None = None
        for expected_sequence, stop in enumerate(cycle.stops):
            if stop.sequence != expected_sequence:
                errors.append(
                    ValidationIssue(
                        ValidationErrorCode.INVALID_TIME,
                        "Последовательность остановок содержит пропуск или дубликат.",
                        cycle.id,
                        stop.task_id,
                    )
                )
            expected_departure = stop.planned_arrival + timedelta(seconds=stop.service_seconds)
            if stop.planned_departure != expected_departure:
                errors.append(
                    ValidationIssue(
                        ValidationErrorCode.INVALID_TIME,
                        (
                            "Остановка не может включать ожидание до временного окна: "
                            "ожидание должно происходить до выезда на участок."
                        ),
                        cycle.id,
                        stop.task_id,
                    )
                )
            if previous_departure is not None and stop.planned_arrival < previous_departure:
                errors.append(
                    ValidationIssue(
                        ValidationErrorCode.INVALID_TIME,
                        "Время следующей остановки раньше предыдущего отправления.",
                        cycle.id,
                        stop.task_id,
                    )
                )
            previous_departure = stop.planned_departure
            if previous_load_after is not None and stop.load_before != previous_load_after:
                errors.append(
                    ValidationIssue(
                        ValidationErrorCode.LOAD_DISCONTINUITY,
                        "Загрузка между соседними остановками изменена без операции.",
                        cycle.id,
                        stop.task_id,
                    )
                )
            previous_load_after = stop.load_after
            if stop.load_after != stop.load_before + stop.quantity_delta:
                errors.append(
                    ValidationIssue(
                        ValidationErrorCode.LOAD_DISCONTINUITY,
                        "Изменение загрузки не совпадает с загрузкой до и после остановки.",
                        cycle.id,
                        stop.task_id,
                    )
                )
            if stop.load_after > capacity or stop.load_before > capacity:
                errors.append(
                    ValidationIssue(
                        ValidationErrorCode.CAPACITY_EXCEEDED,
                        f"Загрузка превышает вместимость машины ({capacity}).",
                        cycle.id,
                        stop.task_id,
                    )
                )
            if stop.load_before < 0 or stop.load_after < 0:
                errors.append(
                    ValidationIssue(
                        ValidationErrorCode.NEGATIVE_LOAD,
                        "Загрузка машины не может быть отрицательной.",
                        cycle.id,
                        stop.task_id,
                    )
                )
            if (stop.stop_type is StopType.DELIVERY and stop.quantity_delta >= 0) or (
                stop.stop_type is StopType.PICKUP and stop.quantity_delta <= 0
            ):
                errors.append(
                    ValidationIssue(
                        ValidationErrorCode.LOAD_DISCONTINUITY,
                        (
                            "Доставка должна уменьшать загрузку машины, а вывоз — "
                            "увеличивать её. Бытовки для доставки загружаются только на складе."
                        ),
                        cycle.id,
                        stop.task_id,
                    )
                )
            if stop.task_id is not None:
                assigned_tasks.append((stop.task_id, cycle.id))
                if abs(stop.quantity_delta) not in (1, 2):
                    errors.append(
                        ValidationIssue(
                            ValidationErrorCode.INVALID_TASK_QUANTITY,
                            "Транспортная часть должна содержать одну или две бытовки.",
                            cycle.id,
                            stop.task_id,
                        )
                    )
            if stop.window_start is not None and stop.window_end is not None:
                outside = (
                    stop.planned_arrival < stop.window_start
                    or stop.planned_arrival > stop.window_end
                )
                if outside and stop.window_is_hard:
                    errors.append(
                        ValidationIssue(
                            ValidationErrorCode.TIME_WINDOW_VIOLATION,
                            "Нарушено жёсткое временное окно заявки.",
                            cycle.id,
                            stop.task_id,
                        )
                    )
                elif outside:
                    warnings.append(
                        ValidationIssue(
                            ValidationWarningCode.SOFT_WINDOW_RISK,
                            "План выходит за мягкое временное окно заявки.",
                            cycle.id,
                            stop.task_id,
                        )
                    )

        if cycle.stops and (cycle.stops[0].load_before != 0 or cycle.stops[-1].load_after != 0):
            errors.append(
                ValidationIssue(
                    ValidationErrorCode.LOAD_DISCONTINUITY,
                    "Рейс должен начинаться и завершаться с нулевой загрузкой.",
                    cycle.id,
                )
            )
        if cycle.stops:
            delivery_quantity = sum(
                abs(stop.quantity_delta)
                for stop in cycle.stops
                if stop.stop_type is StopType.DELIVERY
            )
            if cycle.stops[0].load_after != delivery_quantity:
                errors.append(
                    ValidationIssue(
                        ValidationErrorCode.LOAD_DISCONTINUITY,
                        (
                            "Исходящая загрузка со склада должна точно соответствовать "
                            "количеству бытовок во всех доставках этого рейса."
                        ),
                        cycle.id,
                    )
                )

        shift = shift_by_id.get(cycle.driver_shift_id)
        if shift is None:
            errors.append(
                ValidationIssue(
                    ValidationErrorCode.SHIFT_EXCEEDED,
                    "Для рейса не найдена назначенная смена.",
                    cycle.id,
                )
            )
        elif (
            cycle.driver_id != shift.driver_id
            or cycle.vehicle_id != shift.vehicle_id
            or cycle.planned_start < shift.start_at
        ):
            errors.append(
                ValidationIssue(
                    ValidationErrorCode.SHIFT_EXCEEDED,
                    "Рейс не соответствует водителю, машине или началу назначенной смены.",
                    cycle.id,
                )
            )
        if vehicle is None:
            errors.append(
                ValidationIssue(
                    ValidationErrorCode.VEHICLE_OVERLAP,
                    "Для рейса не найдена назначенная машина.",
                    cycle.id,
                )
            )
        if shift is not None and cycle.planned_finish > shift.end_at:
            overtime = cycle.planned_finish - shift.end_at
            allowed = settings.allow_soft_overtime and overtime <= timedelta(
                minutes=settings.soft_overtime_limit_minutes
            )
            issue = ValidationIssue(
                ValidationWarningCode.OVERTIME_WARNING
                if allowed
                else ValidationErrorCode.SHIFT_EXCEEDED,
                f"Рейс заканчивается после смены на {round(overtime.total_seconds() / 60)} мин.",
                cycle.id,
            )
            (warnings if allowed else errors).append(issue)
        if (
            cycle.detour_seconds > settings.max_detour_minutes * 60
            or cycle.detour_ratio > settings.max_detour_ratio
        ):
            warnings.append(
                ValidationIssue(
                    ValidationWarningCode.HIGH_DETOUR,
                    "Крюк за вывозом превышает рекомендуемый лимит.",
                    cycle.id,
                )
            )
        if cycle.empty_distance_meters and not any(
            stop.stop_type is StopType.DELIVERY for stop in cycle.stops
        ):
            warnings.append(
                ValidationIssue(
                    ValidationWarningCode.INEFFICIENT_EMPTY_RUN,
                    "Рейс начинается пустым пробегом к вывозу.",
                    cycle.id,
                )
            )
        if shift is not None and shift.end_at - cycle.planned_finish < timedelta(
            minutes=settings.low_buffer_warning_minutes
        ):
            warnings.append(
                ValidationIssue(
                    ValidationWarningCode.LOW_TIME_BUFFER,
                    "До конца смены остаётся небольшой резерв времени.",
                    cycle.id,
                )
            )

    counts = Counter(task_id for task_id, _ in assigned_tasks)
    for task_id, count in sorted(counts.items()):
        if count > 1:
            errors.append(
                ValidationIssue(
                    ValidationErrorCode.TASK_ALREADY_ASSIGNED,
                    "Транспортная часть назначена более одного раза.",
                    task_id=task_id,
                )
            )

    _append_overlap_issues(
        cycle_list,
        key_name="driver",
        code=ValidationErrorCode.DRIVER_OVERLAP,
        errors=errors,
    )
    _append_overlap_issues(
        cycle_list,
        key_name="vehicle",
        code=ValidationErrorCode.VEHICLE_OVERLAP,
        errors=errors,
    )
    resolved_total = total_tasks if total_tasks is not None else len(counts) + unassigned_tasks
    metrics = calculate_plan_metrics(
        cycle_list,
        total_tasks=resolved_total,
        unassigned_tasks=unassigned_tasks,
        score=score,
        shifts=shift_by_id.values(),
    )
    return ValidationResult(tuple(errors), tuple(warnings), metrics)


def _append_overlap_issues(
    cycles: tuple[RouteCycle, ...],
    *,
    key_name: str,
    code: ValidationErrorCode,
    errors: list[ValidationIssue],
) -> None:
    """Append deterministic overlap issues for one exclusive resource key."""

    grouped: dict[str, list[RouteCycle]] = defaultdict(list)
    for cycle in cycles:
        grouped[getattr(cycle, f"{key_name}_id")].append(cycle)
    for _, resource_cycles in sorted(grouped.items()):
        ordered = sorted(resource_cycles, key=lambda cycle: (cycle.planned_start, cycle.id))
        for previous, current in pairwise(ordered):
            if current.planned_start < previous.planned_finish:
                errors.append(
                    ValidationIssue(
                        code,
                        "Назначенные рейсы пересекаются по времени.",
                        current.id,
                    )
                )
