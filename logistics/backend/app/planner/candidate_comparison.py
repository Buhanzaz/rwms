"""Offline CP-SAT comparison over immutable, already truck-routed candidates.

This module never publishes assignments or replaces the production heuristic.
It compares selection quality within the observed candidate pool, not the whole
vehicle-routing search space. OR-Tools is an optional benchmark dependency.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import asdict, dataclass
from hashlib import sha256
from itertools import combinations
from math import isfinite
from time import perf_counter

from .engine import CandidateRouteEvaluator
from .heuristic import _delivery_coverage_key
from .models import (
    DriverShift,
    PlanningInput,
    PlanningResult,
    PlanningSettings,
    PlanningTask,
    RouteCycle,
    StopType,
    TaskType,
    Vehicle,
)
from .validation import validate_route_plan
from .workload import shift_usable_seconds


def candidate_fingerprint(cycle: RouteCycle) -> str:
    """Include absolute times and proofs: heuristic IDs alone are not unique schedules."""

    encoded = json.dumps(asdict(cycle), sort_keys=True, default=str, separators=(",", ":"))
    return sha256(encoded.encode()).hexdigest()


class RoutedCandidatePool:
    """Observe successful exact evaluations without changing the heuristic's result."""

    def __init__(self, delegate: CandidateRouteEvaluator, *, limit: int = 512) -> None:
        if not 1 <= limit <= 2_048:
            raise ValueError("candidate pool limit must be between 1 and 2048")
        self.delegate = delegate
        self.limit = limit
        self.cycles: dict[str, RouteCycle] = {}
        self.discarded = 0

    async def route_candidate(
        self,
        cycle: RouteCycle,
        *,
        tasks: tuple[PlanningTask, ...],
        vehicle: Vehicle,
        shift: DriverShift,
        settings: PlanningSettings,
    ) -> RouteCycle:
        """Retain only completed evaluations; baseline routes are added separately later."""

        routed = await self.delegate.route_candidate(
            cycle,
            tasks=tasks,
            vehicle=vehicle,
            shift=shift,
            settings=settings,
        )
        key = candidate_fingerprint(routed)
        if key not in self.cycles:
            if len(self.cycles) < self.limit:
                self.cycles[key] = routed
            else:
                self.discarded += 1
        return routed


@dataclass(frozen=True, slots=True)
class CandidateComparison:
    """Diagnostic outcome; a feasible incumbent is not a globally optimal route plan."""

    status: str
    cycles: tuple[RouteCycle, ...] | None
    candidate_count: int
    discarded_candidates: int
    optimal_stages: int
    total_stages: int
    elapsed_seconds: float
    final_objective_bound: float | None


def _option(cycle: RouteCycle) -> str:
    return cycle.resource_option_id or cycle.driver_shift_id


def _conflict(
    first: RouteCycle,
    second: RouteCycle,
    shifts: dict[str, DriverShift],
    input_data: PlanningInput,
    settings: PlanningSettings,
) -> bool:
    """Conservatively protect physical resources, depot continuity and turnaround."""

    if first.driver_shift_id == second.driver_shift_id and _option(first) != _option(second):
        return True
    if first.driver_id != second.driver_id and first.vehicle_id != second.vehicle_id:
        return False
    first_shift, second_shift = shifts[_option(first)], shifts[_option(second)]
    first_depot = first_shift.route_depot or input_data.warehouse
    second_depot = second_shift.route_depot or input_data.warehouse
    # The collector has no separate repositioning proof between these two cycles.
    if first_depot.point.coordinates != second_depot.point.coordinates:
        return True
    earlier, later = sorted((first, second), key=lambda item: item.planned_start)
    turnaround = (first_depot.turnaround_minutes + settings.default_route_buffer_minutes) * 60
    if (later.planned_start - earlier.planned_finish).total_seconds() < turnaround:
        return True
    if first.driver_shift_id == second.driver_shift_id:
        overtime = settings.soft_overtime_limit_minutes * 60 if settings.allow_soft_overtime else 0
        span = (later.planned_finish - earlier.planned_start).total_seconds()
        return span > shift_usable_seconds(first_shift) + overtime
    return False


def compare_candidate_selection(
    input_data: PlanningInput,
    settings: PlanningSettings,
    baseline: PlanningResult,
    pool: RoutedCandidatePool,
    *,
    verify_route: Callable[[RouteCycle], None],
    max_seconds: float = 3.0,
) -> CandidateComparison:
    """Compare native selection with fixed delivery commitments and bounded CPU time.

    Every objective stage is fixed before proceeding to the next, only after an
    OPTIMAL verdict. Timeout keeps an explicitly labelled incumbent, never a fake
    success. The caller must independently verify current per-leg truck proofs.
    """

    if not isfinite(max_seconds) or not 0 < max_seconds <= 60:
        raise ValueError("comparison budget must be in (0, 60] seconds")
    try:
        from ortools.sat.python import cp_model
    except ImportError as exc:
        raise RuntimeError("Install the solver-benchmark extra to compare native OR-Tools") from exc

    started = perf_counter()
    baseline_keys = {candidate_fingerprint(cycle) for cycle in baseline.cycles}
    candidates_by_key = {candidate_fingerprint(cycle): cycle for cycle in baseline.cycles}
    candidates_by_key.update(pool.cycles)
    candidates = tuple(candidates_by_key[key] for key in sorted(candidates_by_key))
    shifts = {shift.resource_option_id or shift.id: shift for shift in input_data.shifts}
    vehicles = {vehicle.id: vehicle for vehicle in input_data.vehicles}
    tasks = {task.id: task for task in baseline.tasks}
    locked_keys = {candidate_fingerprint(cycle) for cycle in input_data.locked_cycles}
    if not locked_keys.issubset(candidates_by_key):
        raise ValueError("baseline must retain every immutable locked cycle")

    for cycle in candidates:
        shift = shifts.get(_option(cycle))
        vehicle = vehicles.get(cycle.vehicle_id)
        if (
            shift is None
            or not shift.active
            or vehicle is None
            or not vehicle.active
            or shift.vehicle_id != cycle.vehicle_id
            or shift.driver_id != cycle.driver_id
            or not cycle.task_ids
            or any(task_id not in tasks for task_id in cycle.task_ids)
        ):
            raise ValueError("candidate resource or task is absent from the immutable input")
        pickup_seen = False
        for stop in cycle.stops:
            pickup_seen |= stop.stop_type is StopType.PICKUP
            if pickup_seen and stop.stop_type is StopType.DELIVERY:
                raise ValueError("candidate delivers after starting pickups")
            if stop.task_id is not None and (
                abs(stop.quantity_delta) != tasks[stop.task_id].quantity
                or stop.point.coordinates != tasks[stop.task_id].point.coordinates
            ):
                raise ValueError("candidate changes task quantity or coordinates")
        verify_route(cycle)
        validation = validate_route_plan(
            (cycle,),
            warehouse=input_data.warehouse,
            shifts=input_data.shifts,
            vehicles=input_data.vehicles,
            settings=settings,
            total_tasks=len(tasks),
            unassigned_tasks=len(tasks) - len(cycle.task_ids),
            score=cycle.score,
        )
        if not validation.valid:
            raise ValueError("candidate fails the shared route validator")

    model = cp_model.CpModel()
    chosen = [model.new_bool_var(f"candidate_{index}") for index in range(len(candidates))]
    task_indices = {
        task_id: [index for index, cycle in enumerate(candidates) if task_id in cycle.task_ids]
        for task_id in tasks
    }
    baseline_tasks = {task_id for cycle in baseline.cycles for task_id in cycle.task_ids}
    for task_id, indices in task_indices.items():
        coverage = sum(chosen[index] for index in indices)
        model.add(coverage <= 1)
        if task_id in baseline_tasks and (
            tasks[task_id].task_type is TaskType.DELIVERY or tasks[task_id].mandatory
        ):
            model.add(coverage == 1)
    for index, cycle in enumerate(candidates):
        key = candidate_fingerprint(cycle)
        model.add_hint(chosen[index], int(key in baseline_keys))
        if key in locked_keys:
            model.add(chosen[index] == 1)
        shift = shifts[_option(cycle)]
        overtime = settings.soft_overtime_limit_minutes * 60 if settings.allow_soft_overtime else 0
        if (cycle.planned_finish - cycle.planned_start).total_seconds() > (
            shift_usable_seconds(shift) + overtime
        ):
            model.add(chosen[index] == 0)
    for first, second in combinations(range(len(candidates)), 2):
        if _conflict(candidates[first], candidates[second], shifts, input_data, settings):
            model.add(chosen[first] + chosen[second] <= 1)

    activated = []
    for vehicle_id in sorted({cycle.vehicle_id for cycle in candidates}):
        used = model.new_bool_var(f"vehicle_{vehicle_id}")
        model.add_max_equality(
            used,
            [
                chosen[index]
                for index, cycle in enumerate(candidates)
                if cycle.vehicle_id == vehicle_id
            ],
        )
        activated.append(used)
    delivery_keys = [
        _delivery_coverage_key(
            (
                task_id
                for task_id in cycle.task_ids
                if tasks[task_id].task_type is TaskType.DELIVERY
            ),
            tasks,
        )
        for cycle in candidates
    ]
    objectives = [
        sum(chosen[index] * key[stage] for index, key in enumerate(delivery_keys))
        for stage in range(7)
    ]
    for mandatory_only in (True, False):
        objectives.append(
            sum(
                chosen[index]
                * sum(
                    tasks[task_id].quantity
                    for task_id in cycle.task_ids
                    if tasks[task_id].task_type is TaskType.PICKUP
                    and (not mandatory_only or tasks[task_id].mandatory)
                )
                for index, cycle in enumerate(candidates)
            )
        )
    objectives.extend(
        (
            -sum(activated),
            -sum(
                chosen[index] * cycle.total_distance_meters
                for index, cycle in enumerate(candidates)
            ),
            -sum(
                chosen[index] * cycle.empty_distance_meters
                for index, cycle in enumerate(candidates)
            ),
        )
    )
    selected: tuple[RouteCycle, ...] | None = None
    optimal_stages = 0
    status_name = "UNKNOWN"
    bound = None
    for objective in objectives:
        remaining = max_seconds - (perf_counter() - started)
        if remaining <= 0:
            status_name = "FEASIBLE" if selected is not None else "UNKNOWN"
            break
        model.maximize(objective)
        solver = cp_model.CpSolver()
        solver.parameters.max_time_in_seconds = remaining
        solver.parameters.num_search_workers = 1
        solver.parameters.random_seed = settings.seed % 2_147_483_647
        status = solver.solve(model)
        status_name = solver.status_name(status)
        if status not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
            if selected is not None and status == cp_model.UNKNOWN:
                status_name = "FEASIBLE"
            break
        selected = tuple(
            cycle for index, cycle in enumerate(candidates) if solver.value(chosen[index])
        )
        bound = solver.best_objective_bound
        if status != cp_model.OPTIMAL:
            break
        optimal_stages += 1
        model.add(objective == round(solver.objective_value))
    if selected is not None:
        validation = validate_route_plan(
            selected,
            warehouse=input_data.warehouse,
            shifts=input_data.shifts,
            vehicles=input_data.vehicles,
            settings=settings,
            total_tasks=len(tasks),
            unassigned_tasks=len(tasks) - sum(len(cycle.task_ids) for cycle in selected),
            score=0,
        )
        if not validation.valid:
            raise ValueError("native selection failed final shared validation")
        for cycle in selected:
            verify_route(cycle)
    return CandidateComparison(
        status_name,
        selected,
        len(candidates),
        pool.discarded,
        optimal_stages,
        len(objectives),
        perf_counter() - started,
        bound,
    )
