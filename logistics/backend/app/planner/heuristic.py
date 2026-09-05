"""Transparent deterministic heuristic for two-cabin logistics cycles."""

# ruff: noqa: RUF001 -- Russian operator-facing explanations are intentional.

from __future__ import annotations

from asyncio import sleep, timeout
from collections import defaultdict
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, replace
from datetime import date, datetime, timedelta
from hashlib import sha256
from itertools import pairwise
from math import floor
from time import monotonic
from typing import Protocol

from app.routing import (
    GeoJsonLineString,
    GeoPoint,
    RoutingProvider,
    TravelMetric,
)

from .engine import CandidateRouteEvaluator, CandidateRouteRejected, ProgressPublisher
from .models import (
    DriverShift,
    LogisticsRequest,
    PlannedLeg,
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
    TraceEvent,
    TraceEventType,
    TracePhase,
    UnassignedReasonCode,
    UnassignedTask,
    ValidationWarningCode,
    Vehicle,
    Warehouse,
)
from .validation import calculate_plan_metrics, validate_route_plan
from .workload import (
    calculate_driver_workload_cost,
    incremental_shift_workload_cost,
    shift_utilization_percent,
)

_MAX_ROUTING_MATRIX_POINTS = 32
_MAX_TASKS_PER_MATRIX_BATCH = _MAX_ROUTING_MATRIX_POINTS - 1
_TIME_WINDOW_PARTITION_MINUTES = 4 * 60
_SPATIAL_PARTITION_DEGREES = 0.25
_SPATIAL_NEIGHBOR_OVERLAP = 7


class _PlannerMatrix(Protocol):
    """Minimal exact-edge view consumed by the synchronous planner search."""

    @property
    def points(self) -> tuple[GeoPoint, ...]:
        """Return globally indexed depot and task points."""

        ...

    def at(self, from_index: int, to_index: int) -> TravelMetric:
        """Return one exact directed road metric or reject an absent sparse edge."""

        ...


@dataclass(frozen=True, slots=True)
class _SparseTravelMatrix:
    """Global point index backed only by bounded exact routing submatrices."""

    points: tuple[GeoPoint, ...]
    metrics: Mapping[tuple[int, int], TravelMetric]
    loaded_task_ids: frozenset[str]
    batch_count: int
    max_batch_points: int

    def at(self, from_index: int, to_index: int) -> TravelMetric:
        """Return a loaded exact edge without synthesizing a geometric fallback."""

        try:
            return self.metrics[(from_index, to_index)]
        except KeyError as exc:
            raise ValueError(
                f"planner edge {from_index}->{to_index} was not exact-routed"
            ) from exc

    def has_exact_edge(self, from_index: int, to_index: int) -> bool:
        """Return whether a directed edge came from an exact provider batch."""

        return (from_index, to_index) in self.metrics


@dataclass(frozen=True, slots=True)
class _Candidate:
    """Internal feasible cycle plus a stable global-selection key."""

    cycle: RouteCycle
    deliveries: tuple[PlanningTask, ...]
    pickups: tuple[PlanningTask, ...]
    selection_key: tuple[object, ...]
    resource_activation_penalty: float
    driver_workload_penalty: float
    projected_shift_utilization_percent: float

    @property
    def task_ids(self) -> frozenset[str]:
        """Return task IDs consumed by this candidate."""

        return frozenset(task.id for task in self.deliveries + self.pickups)


@dataclass(frozen=True, slots=True)
class _CandidateSpec:
    """Lightweight task combination ordered by the shift-independent rank prefix."""

    deliveries: tuple[PlanningTask, ...]
    pickups: tuple[PlanningTask, ...]
    priority_key: tuple[object, ...]


class _TraceRecorder:
    """Apply deterministic sampling/capping before forwarding progress events."""

    def __init__(
        self,
        publisher: ProgressPublisher,
        settings: PlanningSettings,
    ) -> None:
        self._publisher = publisher
        self._settings = settings
        self._events: list[TraceEvent] = []
        self._candidate_count = 0

    @property
    def events(self) -> tuple[TraceEvent, ...]:
        """Return recorded events in their stable emission order."""

        return tuple(self._events)

    async def emit(
        self,
        phase: TracePhase,
        event_type: TraceEventType,
        payload: Mapping[str, object] | None = None,
    ) -> None:
        """Record and publish an event if it passes sampling and size bounds."""

        if self._settings.max_trace_events == 0:
            return
        is_candidate = event_type in {
            TraceEventType.CANDIDATE_EDGE_CONSIDERED,
            TraceEventType.CANDIDATE_EDGE_REJECTED,
            TraceEventType.CANDIDATE_CYCLE_CREATED,
            TraceEventType.CANDIDATE_CYCLE_REJECTED,
        }
        if is_candidate:
            self._candidate_count += 1
            if self._candidate_count % self._settings.trace_sample_rate:
                return
        if len(self._events) >= self._settings.max_trace_events:
            return
        event = TraceEvent(
            sequence=len(self._events) + 1,
            phase=phase,
            event_type=event_type,
            payload=dict(payload or {}),
        )
        self._events.append(event)
        await self._publisher.publish(event)

    async def start(self, phase: TracePhase) -> None:
        """Emit a phase boundary before its work begins."""

        await self.emit(phase, TraceEventType.PHASE_STARTED, {"phase": phase.value})

    async def complete(self, phase: TracePhase, **payload: object) -> None:
        """Emit a phase boundary after its work finishes."""

        await self.emit(
            phase,
            TraceEventType.PHASE_COMPLETED,
            {"phase": phase.value, **payload},
        )


class HeuristicPlanner:
    """Build deterministic depot cycles using bounded, explainable candidates."""

    def __init__(
        self,
        routing_provider: RoutingProvider,
        candidate_route_evaluator: CandidateRouteEvaluator | None = None,
    ) -> None:
        self._routing = routing_provider
        self._candidate_route_evaluator = candidate_route_evaluator

    async def _load_sparse_matrix(
        self,
        warehouse: Warehouse,
        tasks: tuple[PlanningTask, ...],
        departure_at: datetime | None,
        *,
        deadline: float,
    ) -> tuple[_SparseTravelMatrix, dict[str, int], bool]:
        """Load bounded exact submatrices for deterministic spatial/window partitions."""

        ordered_tasks = tuple(sorted(tasks, key=lambda task: task.id))
        points = (warehouse.point, *(task.point for task in ordered_tasks))
        matrix_index = {
            task.id: index + 1 for index, task in enumerate(ordered_tasks)
        }
        metrics: dict[tuple[int, int], TravelMetric] = {
            (0, 0): TravelMetric(distance_meters=0, travel_seconds=0)
        }
        loaded_task_ids: set[str] = set()
        batch_count = 0
        max_batch_points = 0
        stopped_by_deadline = False
        for partition in _partition_tasks(ordered_tasks):
            remaining_seconds = deadline - monotonic()
            if remaining_seconds <= 0:
                stopped_by_deadline = True
                break
            batch_points = [warehouse.point, *(task.point for task in partition)]
            if len(batch_points) > _MAX_ROUTING_MATRIX_POINTS:
                raise RuntimeError("planner routing partition exceeds provider batch limit")
            try:
                async with timeout(remaining_seconds):
                    routed = await self._routing.get_matrix(batch_points, departure_at)
            except TimeoutError:
                stopped_by_deadline = True
                break
            if len(routed.points) != len(batch_points):
                raise RuntimeError("routing provider returned an incomplete matrix batch")
            global_indices = [0, *(matrix_index[task.id] for task in partition)]
            for local_from, global_from in enumerate(global_indices):
                for local_to, global_to in enumerate(global_indices):
                    metrics[(global_from, global_to)] = routed.at(
                        local_from,
                        local_to,
                    )
            loaded_task_ids.update(task.id for task in partition)
            batch_count += 1
            max_batch_points = max(max_batch_points, len(batch_points))
        return (
            _SparseTravelMatrix(
                points=points,
                metrics=metrics,
                loaded_task_ids=frozenset(loaded_task_ids),
                batch_count=batch_count,
                max_batch_points=max_batch_points,
            ),
            matrix_index,
            stopped_by_deadline,
        )

    async def generate_plan(
        self,
        input_data: PlanningInput,
        settings: PlanningSettings,
        progress: ProgressPublisher,
    ) -> PlanningResult:
        """Generate a valid best-known plan within deterministic search bounds."""

        phase_budget_seconds = max(0.0, settings.max_optimization_seconds)
        routing_deadline = monotonic() + phase_budget_seconds
        trace = _TraceRecorder(progress, settings)
        await trace.start(TracePhase.VALIDATING_INPUT)
        self._validate_input(input_data)
        await trace.complete(TracePhase.VALIDATING_INPUT)

        await trace.start(TracePhase.CLASSIFYING_ZONES)
        tasks, initial_unassigned = split_requests_for_date(
            input_data.requests,
            input_data.planning_date,
        )
        await trace.complete(
            TracePhase.CLASSIFYING_ZONES,
            task_count=len(tasks),
            rejected_count=len(initial_unassigned),
        )

        locked_task_ids = {
            task_id for cycle in input_data.locked_cycles for task_id in cycle.task_ids
        }
        active_vehicles = {vehicle.id: vehicle for vehicle in input_data.vehicles if vehicle.active}
        active_shifts = tuple(
            sorted(
                (
                    shift
                    for shift in input_data.shifts
                    if shift.active
                    and shift.start_at.date() == input_data.planning_date
                    and shift.vehicle_id in active_vehicles
                ),
                key=lambda shift: (
                    shift.start_at,
                    shift.driver_id,
                    shift.id,
                    _shift_option_id(shift),
                ),
            )
        )

        await trace.start(TracePhase.BUILDING_TRAVEL_MATRIX)
        depot_by_option_id = {
            _shift_option_id(shift): shift.route_depot or input_data.warehouse
            for shift in active_shifts
        }
        depot_key_by_option_id = {
            option_id: _depot_key(depot)
            for option_id, depot in depot_by_option_id.items()
        }
        depots_by_key = {
            _depot_key(depot): depot for depot in depot_by_option_id.values()
        }
        matrices_by_depot: dict[tuple[object, ...], _SparseTravelMatrix] = {}
        matrix_indices_by_depot: dict[tuple[object, ...], dict[str, int]] = {}
        matrix_stopped_by_deadline = False
        for depot_key in sorted(depots_by_key, key=repr):
            depot = depots_by_key[depot_key]
            departure_at = min(
                max(shift.start_at, shift.available_from or shift.start_at)
                for shift in active_shifts
                if depot_key_by_option_id[_shift_option_id(shift)] == depot_key
            )
            matrix, matrix_index, stopped = await self._load_sparse_matrix(
                depot,
                tasks,
                departure_at,
                deadline=routing_deadline,
            )
            matrices_by_depot[depot_key] = matrix
            matrix_indices_by_depot[depot_key] = matrix_index
            if stopped:
                matrix_stopped_by_deadline = True
                break
        matrices_by_option_id = {
            option_id: matrices_by_depot[depot_key]
            for option_id, depot_key in depot_key_by_option_id.items()
            if depot_key in matrices_by_depot
        }
        matrix_indices_by_option_id = {
            option_id: matrix_indices_by_depot[depot_key]
            for option_id, depot_key in depot_key_by_option_id.items()
            if depot_key in matrix_indices_by_depot
        }
        loaded_task_ids = frozenset(
            task_id
            for matrix in matrices_by_depot.values()
            for task_id in matrix.loaded_task_ids
        )
        if not active_shifts:
            # No road edge can be used, but tasks stay in the diagnostic phase so
            # the operator receives the concrete missing-resource reason.
            loaded_task_ids = frozenset(task.id for task in tasks)
        deadline_unassigned = tuple(
            _unassigned_task(task, (UnassignedReasonCode.OPTIMIZATION_TIME_LIMIT,))
            for task in tasks
            if task.id not in loaded_task_ids
            and task.id not in locked_task_ids
        )
        remaining: dict[str, PlanningTask] = {
            task.id: task
            for task in tasks
            if task.id in loaded_task_ids
            and task.id not in locked_task_ids
        }
        await trace.complete(
            TracePhase.BUILDING_TRAVEL_MATRIX,
            point_count=sum(len(matrix.points) for matrix in matrices_by_depot.values()),
            routed_task_count=len(loaded_task_ids),
            depot_count=len(depots_by_key),
            batch_count=sum(matrix.batch_count for matrix in matrices_by_depot.values()),
            max_batch_points=max(
                (matrix.max_batch_points for matrix in matrices_by_depot.values()),
                default=0,
            ),
        )

        cycles: list[RouteCycle] = list(input_data.locked_cycles)
        available_at, next_sequence = _initial_shift_state(
            active_shifts,
            input_data.locked_cycles,
            depot_by_option_id,
            settings.default_route_buffer_minutes,
        )
        cycle_counts = {
            shift.id: sum(
                cycle.driver_shift_id == shift.id for cycle in input_data.locked_cycles
            )
            for shift in _unique_physical_shifts(active_shifts)
        }

        await trace.start(TracePhase.GROUPING_DELIVERIES)
        await trace.complete(
            TracePhase.GROUPING_DELIVERIES,
            delivery_count=sum(task.task_type is TaskType.DELIVERY for task in tasks),
        )
        await trace.start(TracePhase.GENERATING_DELIVERY_PAIRS)
        await trace.complete(TracePhase.GENERATING_DELIVERY_PAIRS)
        await trace.start(TracePhase.MATCHING_PICKUPS)
        await trace.complete(TracePhase.MATCHING_PICKUPS)
        await trace.start(TracePhase.BUILDING_CYCLES)

        # Routing preparation and bounded optimization are separate phases. A slow
        # but successful matrix request must not consume the entire search budget
        # before the first candidate can pass its mandatory exact-route check.
        deadline = monotonic() + phase_budget_seconds
        # The deterministic evaluation budget preserves repeatability on normal
        # runs. The monotonic deadline is an independent cancellation fence that
        # returns the best completely validated result reached before exhaustion.
        evaluation_budget = max(0, round(phase_budget_seconds * 50_000))
        timed_out = evaluation_budget == 0
        evaluation_count = 0
        task_by_id = {task.id: task for task in tasks}
        delivery_reference_cycles = tuple(cycles)
        delivery_reference_available = dict(available_at)
        single_depot_context = (
            next(iter(matrices_by_depot.values())),
            next(iter(matrix_indices_by_depot.values())),
            next(iter(depots_by_key.values())),
        ) if len(matrices_by_depot) == 1 and len(depots_by_key) == 1 else None
        if (
            evaluation_budget
            and self._candidate_route_evaluator is None
            and not matrix_stopped_by_deadline
            and not _deadline_reached(deadline)
            and single_depot_context is not None
        ):
            matrix, matrix_index, reference_depot = single_depot_context
            delivery_references = tuple(
                self._project_delivery_plan(
                    remaining_deliveries=tuple(
                        task for task in remaining.values() if task.task_type is TaskType.DELIVERY
                    ),
                    shifts=active_shifts,
                    available_at=available_at,
                    warehouse=reference_depot,
                    vehicles=active_vehicles,
                    matrix=matrix,
                    matrix_index=matrix_index,
                    settings=settings,
                    cycles=tuple(cycles),
                    deadline=deadline,
                    deadline_first=deadline_first,
                    longest_first=longest_first,
                )
                for deadline_first, longest_first in (
                    (True, False),
                    (False, True),
                    (False, False),
                )
            )
            delivery_reference_cycles, delivery_reference_available, _ = max(
                delivery_references,
                key=lambda reference: (
                    _delivery_coverage_key(
                        _delivery_task_ids(reference[0], task_by_id),
                        task_by_id,
                    ),
                    -len(reference[0]),
                    -sum(cycle.total_travel_seconds for cycle in reference[0]),
                    tuple(cycle.id for cycle in reference[0]),
                ),
            )
        delivery_reference_ids = _delivery_task_ids(
            delivery_reference_cycles,
            task_by_id,
        )
        route_rejections: dict[str, set[UnassignedReasonCode]] = {}
        delivery_phase_complete = False
        while remaining and active_shifts and not timed_out:
            # Candidate construction is CPU-bound. Yield once per assigned cycle so
            # health checks and optimization-status requests remain responsive even
            # for dense generated workloads.
            await sleep(0)
            if _deadline_reached(deadline):
                timed_out = True
                break
            delivery_phase = (
                any(task.task_type is TaskType.DELIVERY for task in remaining.values())
                and not delivery_phase_complete
            )
            activated_shift_ids = frozenset(cycle.driver_shift_id for cycle in cycles)
            fleet_fully_activated = activated_shift_ids == frozenset(
                shift.id for shift in _unique_physical_shifts(active_shifts)
            )
            best_per_shift: list[_Candidate] = []
            wall_deadline_hit = False
            # Specs depend on the depot matrix and eligible demand, not the truck
            # or departure. Reuse only within this immutable remaining-work set;
            # scheduling and exact loaded-truck checks still run for every shift.
            specs_by_context: dict[
                tuple[tuple[object, ...], tuple[str, ...]], tuple[_CandidateSpec, ...]
            ] = {}
            for shift in active_shifts:
                option_id = _shift_option_id(shift)
                selected_option_ids = {
                    cycle.resource_option_id or cycle.driver_shift_id
                    for cycle in cycles
                    if cycle.driver_shift_id == shift.id
                }
                if selected_option_ids and option_id not in selected_option_ids:
                    continue
                option_matrix = matrices_by_option_id.get(option_id)
                option_matrix_index = matrix_indices_by_option_id.get(option_id)
                warehouse = depot_by_option_id[option_id]
                if option_matrix is None or option_matrix_index is None:
                    continue
                eligible_tasks = tuple(
                    task
                    for task in remaining.values()
                    if task.id in option_matrix.loaded_task_ids
                    and _shift_allows_task(shift, task)
                )
                specs_key = (
                    depot_key_by_option_id[option_id],
                    tuple(task.id for task in eligible_tasks),
                )
                if specs_key not in specs_by_context:
                    specs_by_context[specs_key] = _candidate_specs(
                        eligible_tasks,
                        option_matrix,
                        option_matrix_index,
                        settings,
                    )
                all_candidate_specs = specs_by_context[specs_key]
                if delivery_phase:
                    candidate_specs = tuple(
                        spec for spec in all_candidate_specs if spec.deliveries
                    )
                else:
                    candidate_specs = tuple(
                        spec for spec in all_candidate_specs if not spec.deliveries
                    )
                # A geometric candidate is only a prefilter. If its exact truck
                # route fails, try the remaining alternatives before abandoning
                # this shift, keeping one shared evaluation and time budget.
                exhausted = False
                while candidate_specs:
                    candidates, evaluated, exhausted = self._candidates_for_shift(
                        shift=shift,
                        start_at=available_at[shift.id],
                        sequence=next_sequence[shift.id],
                        candidate_specs=candidate_specs,
                        warehouse=warehouse,
                        vehicle=active_vehicles[shift.vehicle_id],
                        matrix=option_matrix,
                        matrix_index=option_matrix_index,
                        settings=settings,
                        existing_cycles=tuple(cycles),
                        cycle_count=cycle_counts[shift.id],
                        activated_shift_ids=activated_shift_ids,
                        prefer_tight_fit=fleet_fully_activated,
                        max_evaluations=evaluation_budget - evaluation_count,
                        deadline=deadline,
                    )
                    evaluation_count += evaluated
                    if evaluated == 0:
                        break
                    candidate_specs = candidate_specs[evaluated:]
                    if candidates:
                        for candidate in candidates:
                            await trace.emit(
                                TracePhase.BUILDING_CYCLES,
                                TraceEventType.CANDIDATE_EDGE_CONSIDERED,
                                {
                                    "driver_shift_id": shift.id,
                                    "task_ids": sorted(candidate.task_ids),
                                    "score": round(candidate.cycle.score, 6),
                                    "resource_activation_penalty": round(
                                        candidate.resource_activation_penalty,
                                        6,
                                    ),
                                    "driver_workload_penalty": round(
                                        candidate.driver_workload_penalty,
                                        6,
                                    ),
                                    "shift_utilization_percent": round(
                                        candidate.projected_shift_utilization_percent,
                                        2,
                                    ),
                                    "resource_option_id": option_id,
                                },
                            )
                        routed_candidates: list[_Candidate] = []
                        for candidate_index, candidate in enumerate(sorted(
                            candidates,
                            key=lambda item: item.selection_key,
                        )):
                            if (
                                candidate_index >= settings.max_candidate_neighbors
                                and routed_candidates
                            ):
                                break
                            if self._candidate_route_evaluator is None:
                                routed_candidates.append(candidate)
                                continue
                            remaining_seconds = deadline - monotonic()
                            if remaining_seconds <= 0:
                                wall_deadline_hit = True
                                break
                            try:
                                async with timeout(remaining_seconds):
                                    routed_cycle = (
                                        await self._candidate_route_evaluator.route_candidate(
                                            candidate.cycle,
                                            tasks=candidate.deliveries + candidate.pickups,
                                            vehicle=active_vehicles[shift.vehicle_id],
                                            shift=shift,
                                            settings=settings,
                                        )
                                    )
                            except TimeoutError:
                                wall_deadline_hit = True
                                break
                            except CandidateRouteRejected as exc:
                                for task_id in candidate.task_ids:
                                    route_rejections.setdefault(task_id, set()).add(exc.reason_code)
                                await trace.emit(
                                    TracePhase.BUILDING_CYCLES,
                                    TraceEventType.CANDIDATE_CYCLE_REJECTED,
                                    {
                                        "driver_shift_id": shift.id,
                                        "task_ids": sorted(candidate.task_ids),
                                        "reason_code": exc.reason_code.value,
                                        "missing_fields": list(exc.missing_fields),
                                    },
                                )
                                continue
                            workload_penalty = incremental_shift_workload_cost(
                                tuple(
                                    cycle for cycle in cycles if cycle.driver_shift_id == shift.id
                                ),
                                routed_cycle,
                                shift,
                                settings,
                            )
                            projected_utilization = shift_utilization_percent(
                                (
                                    *(
                                        cycle
                                        for cycle in cycles
                                        if cycle.driver_shift_id == shift.id
                                    ),
                                    routed_cycle,
                                ),
                                shift,
                            )
                            routed_candidates.append(
                                replace(
                                    candidate,
                                    cycle=routed_cycle,
                                    selection_key=(
                                        *candidate.selection_key[:-7],
                                        routed_cycle.score
                                        + candidate.resource_activation_penalty
                                        + workload_penalty,
                                        routed_cycle.planned_finish,
                                        *candidate.selection_key[-5:],
                                    ),
                                    driver_workload_penalty=workload_penalty,
                                    projected_shift_utilization_percent=projected_utilization,
                                )
                            )
                            if _deadline_reached(deadline):
                                wall_deadline_hit = True
                                break
                        if not routed_candidates:
                            if wall_deadline_hit or exhausted:
                                break
                            continue
                        best = min(
                            routed_candidates,
                            key=lambda candidate: candidate.selection_key,
                        )
                        best_per_shift.append(best)
                        await trace.emit(
                            TracePhase.BUILDING_CYCLES,
                            TraceEventType.CANDIDATE_CYCLE_CREATED,
                            {
                                "driver_shift_id": shift.id,
                                "resource_option_id": option_id,
                                "task_ids": sorted(best.task_ids),
                                "score": round(best.cycle.score, 6),
                            },
                        )
                        break
                    else:
                        await trace.emit(
                            TracePhase.BUILDING_CYCLES,
                            TraceEventType.CANDIDATE_CYCLE_REJECTED,
                            {"driver_shift_id": shift.id},
                        )
                    if exhausted or evaluation_count >= evaluation_budget:
                        break
                if wall_deadline_hit or _deadline_reached(deadline):
                    wall_deadline_hit = True
                    break
                if exhausted or evaluation_count >= evaluation_budget:
                    timed_out = True
                    break
            if wall_deadline_hit:
                timed_out = True
            # Only the interrupted candidate is incomplete. Fully verified choices
            # from this or an earlier shift remain eligible for the final assignment.
            if not best_per_shift:
                if timed_out:
                    break
                if delivery_phase and single_depot_context is not None:
                    current_delivery_ids = _delivery_task_ids(cycles, task_by_id)
                    if _delivery_coverage_key(
                        current_delivery_ids,
                        task_by_id,
                    ) < _delivery_coverage_key(
                        delivery_reference_ids,
                        task_by_id,
                    ):
                        (
                            restored_cycles,
                            restored_available,
                            attachment_evaluations,
                        ) = self._attach_pickups_to_delivery_reference(
                            pickup_tasks=tuple(
                                task for task in tasks if task.task_type is TaskType.PICKUP
                            ),
                            shifts=active_shifts,
                            available_at=delivery_reference_available,
                            warehouse=single_depot_context[2],
                            vehicles=active_vehicles,
                            matrix=single_depot_context[0],
                            matrix_index=single_depot_context[1],
                            settings=settings,
                            cycles=delivery_reference_cycles,
                            task_by_id=task_by_id,
                            max_evaluations=max(
                                0,
                                evaluation_budget - evaluation_count,
                            ),
                            deadline=deadline,
                        )
                        evaluation_count += attachment_evaluations
                        cycles = list(restored_cycles)
                        available_at = dict(restored_available)
                        assigned_ids = {task_id for cycle in cycles for task_id in cycle.task_ids}
                        remaining = {
                            task.id: task
                            for task in tasks
                            if task.id in loaded_task_ids
                            and task.id not in assigned_ids
                            and task.id not in locked_task_ids
                        }
                        next_sequence = {
                            shift.id: max(
                                (
                                    cycle.sequence
                                    for cycle in cycles
                                    if cycle.driver_shift_id == shift.id
                                ),
                                default=0,
                            )
                            + 1
                            for shift in _unique_physical_shifts(active_shifts)
                        }
                        cycle_counts = {
                            shift.id: sum(cycle.driver_shift_id == shift.id for cycle in cycles)
                            for shift in _unique_physical_shifts(active_shifts)
                        }
                        await trace.emit(
                            TracePhase.BUILDING_CYCLES,
                            TraceEventType.ASSIGNMENT_CHANGED,
                            {
                                "reason": "DELIVERY_COVERAGE_RESTORED",
                                "delivery_task_ids": sorted(delivery_reference_ids),
                            },
                        )
                    delivery_phase_complete = True
                    continue
                if delivery_phase:
                    # Multi-depot correctness is owned by the primary candidate
                    # search. The one-depot delivery reference cannot be reused
                    # without relabelling a physical depot, so advance to the
                    # pickup phase only after the validated primary pass.
                    delivery_phase_complete = True
                    continue
                break
            chosen = min(best_per_shift, key=lambda candidate: candidate.selection_key)
            cycles.append(chosen.cycle)
            for task_id in chosen.task_ids:
                remaining.pop(task_id, None)
            shift_id = chosen.cycle.driver_shift_id
            chosen_option_id = chosen.cycle.resource_option_id or shift_id
            resource_decision = (
                "REUSED"
                if shift_id in activated_shift_ids
                else "ADDITIONAL"
                if activated_shift_ids
                else "FIRST"
            )
            turnaround = timedelta(
                minutes=depot_by_option_id[chosen_option_id].turnaround_minutes
                + settings.default_route_buffer_minutes
            )
            available_at[shift_id] = chosen.cycle.planned_finish + turnaround
            next_sequence[shift_id] += 1
            cycle_counts[shift_id] += 1
            await trace.emit(
                TracePhase.BUILDING_CYCLES,
                TraceEventType.CYCLE_ASSIGNED,
                {
                    "cycle_id": chosen.cycle.id,
                    "driver_shift_id": shift_id,
                    "resource_option_id": chosen_option_id,
                    "task_ids": sorted(chosen.task_ids),
                    "resource_decision": resource_decision,
                    "additional_resource_penalty": (
                        settings.additional_resource_activation_penalty
                        if resource_decision == "ADDITIONAL"
                        else 0.0
                    ),
                    "driver_workload_penalty": round(
                        chosen.driver_workload_penalty,
                        6,
                    ),
                    "shift_utilization_percent": round(
                        chosen.projected_shift_utilization_percent,
                        2,
                    ),
                },
            )
            if timed_out:
                break
            if remaining and _deadline_reached(deadline):
                timed_out = True
                break
        timed_out = timed_out or matrix_stopped_by_deadline
        await trace.complete(
            TracePhase.BUILDING_CYCLES,
            cycle_count=len(cycles),
            evaluation_count=evaluation_count,
            delivery_reference_mode=(
                "SINGLE_DEPOT" if single_depot_context is not None else "PRIMARY_MULTI_DEPOT"
            ),
        )

        await trace.start(TracePhase.ASSIGNING_DRIVERS)
        await trace.complete(
            TracePhase.ASSIGNING_DRIVERS,
            assigned_count=(
                len(tasks) - len(remaining) - len(deadline_unassigned)
            ),
        )

        await trace.start(TracePhase.LOCAL_SEARCH)
        cycle_ids_before_search = tuple(cycle.id for cycle in cycles)
        if self._candidate_route_evaluator is None and not timed_out:
            cycles, local_iterations = self._local_improve(
                cycles=cycles,
                task_by_id={task.id: task for task in tasks},
                shifts={_shift_option_id(shift): shift for shift in active_shifts},
                warehouses=depot_by_option_id,
                vehicles=active_vehicles,
                matrices=matrices_by_option_id,
                matrix_indices=matrix_indices_by_option_id,
                settings=settings,
                deadline=deadline,
            )
        else:
            # A local move is accepted only after full exact routing. The current
            # local-search implementation is matrix-only, so truck-safe mode keeps
            # the already evaluated primary assignment instead of fabricating a
            # post-search route.
            local_iterations = 0
        if not timed_out and _deadline_reached(deadline):
            timed_out = True
        cycle_ids_after_search = tuple(cycle.id for cycle in cycles)
        if cycle_ids_after_search != cycle_ids_before_search:
            await trace.emit(
                TracePhase.LOCAL_SEARCH,
                TraceEventType.ASSIGNMENT_CHANGED,
                {
                    "before_cycle_ids": cycle_ids_before_search,
                    "after_cycle_ids": cycle_ids_after_search,
                },
            )
            await trace.emit(
                TracePhase.LOCAL_SEARCH,
                TraceEventType.BEST_SCORE_UPDATED,
                {
                    "cycle_score": _plan_score(
                        cycles,
                        (),
                        settings,
                        active_shifts,
                    )
                },
            )
        await trace.complete(
            TracePhase.LOCAL_SEARCH,
            iterations=local_iterations,
        )

        await trace.start(TracePhase.FINALIZING)
        final_unassigned = [*initial_unassigned, *deadline_unassigned]
        missing_resource_reason = _missing_resource_reason(input_data)
        for task in sorted(remaining.values(), key=_task_priority_key):
            reasons: tuple[UnassignedReasonCode, ...]
            nearest: datetime | None
            if missing_resource_reason:
                reasons = missing_resource_reason
                nearest = None
            elif timed_out:
                reasons = (UnassignedReasonCode.OPTIMIZATION_TIME_LIMIT,)
                nearest = None
            elif task.id in route_rejections:
                reasons = tuple(sorted(route_rejections[task.id], key=lambda item: item.value))
                nearest = None
            else:
                reasons, nearest = self._diagnose_task(
                    task=task,
                    shifts=active_shifts,
                    available_at=available_at,
                    warehouses=depot_by_option_id,
                    vehicles=active_vehicles,
                    matrices=matrices_by_option_id,
                    matrix_indices=matrix_indices_by_option_id,
                    settings=settings,
                    cycles=tuple(cycles),
                )
            final_unassigned.append(_unassigned_task(task, reasons, nearest_possible_at=nearest))

        cycles = sorted(
            cycles,
            key=lambda cycle: (
                cycle.driver_shift_id,
                cycle.planned_start,
                cycle.sequence,
                cycle.id,
            ),
        )
        score = _plan_score(cycles, final_unassigned, settings, active_shifts)
        validation = validate_route_plan(
            cycles,
            warehouse=input_data.warehouse,
            shifts=input_data.shifts,
            vehicles=input_data.vehicles,
            settings=settings,
            total_tasks=len(tasks) + len(initial_unassigned),
            unassigned_tasks=len(final_unassigned),
            score=score,
        )
        if validation.errors:
            error_codes = ", ".join(sorted({issue.code.value for issue in validation.errors}))
            raise ValueError(f"planner produced an invalid plan: {error_codes}")
        metrics = validation.metrics or calculate_plan_metrics(
            cycles,
            total_tasks=len(tasks) + len(initial_unassigned),
            unassigned_tasks=len(final_unassigned),
            score=score,
            shifts=input_data.shifts,
        )
        await trace.complete(
            TracePhase.FINALIZING,
            assigned_tasks=metrics.assigned_tasks,
            unassigned_tasks=metrics.unassigned_tasks,
            score=round(score, 6),
            timed_out=timed_out,
        )
        await trace.start(TracePhase.COMPLETED)
        await trace.complete(TracePhase.COMPLETED)
        return PlanningResult(
            cycles=tuple(cycles),
            unassigned=tuple(final_unassigned),
            tasks=tasks,
            score=score,
            metrics=metrics,
            trace_events=trace.events,
            seed=settings.seed,
            timed_out=timed_out,
            local_search_iterations=local_iterations,
        )

    @staticmethod
    def _validate_input(input_data: PlanningInput) -> None:
        if input_data.warehouse.point is None:
            raise ValueError("planning requires a warehouse")
        for shift in input_data.shifts:
            if shift.start_at.date() != input_data.planning_date:
                raise ValueError("driver shifts must start on the local planning date")
        locked_ids = [task_id for cycle in input_data.locked_cycles for task_id in cycle.task_ids]
        if len(locked_ids) != len(set(locked_ids)):
            raise ValueError("locked cycles assign a task more than once")

    def _candidates_for_shift(
        self,
        *,
        shift: DriverShift,
        start_at: datetime,
        sequence: int,
        candidate_specs: tuple[_CandidateSpec, ...],
        warehouse: Warehouse,
        vehicle: Vehicle,
        matrix: _PlannerMatrix,
        matrix_index: Mapping[str, int],
        settings: PlanningSettings,
        existing_cycles: tuple[RouteCycle, ...],
        cycle_count: int,
        activated_shift_ids: frozenset[str],
        prefer_tight_fit: bool,
        max_evaluations: int,
        deadline: float,
    ) -> tuple[list[_Candidate], int, bool]:
        """Evaluate complete rank buckets until the first feasible bucket is found."""

        if start_at >= shift.end_at or not candidate_specs:
            return [], 0, False
        candidates: list[_Candidate] = []
        evaluated = 0
        current_key = candidate_specs[0].priority_key
        for spec in candidate_specs:
            if evaluated > 0 and _deadline_reached(deadline):
                return candidates, evaluated, True
            if spec.priority_key != current_key:
                if candidates:
                    return candidates, evaluated, False
                current_key = spec.priority_key
            if evaluated >= max_evaluations:
                return candidates, evaluated, True
            evaluated += 1
            reuses_resource = shift.id in activated_shift_ids
            activates_additional_resource = bool(activated_shift_ids) and not reuses_resource
            existing_shift_cycles = tuple(
                cycle for cycle in existing_cycles if cycle.driver_shift_id == shift.id
            )
            candidate = self._schedule_candidate(
                shift=shift,
                start_at=start_at,
                sequence=sequence,
                deliveries=spec.deliveries,
                pickups=spec.pickups,
                warehouse=warehouse,
                vehicle=vehicle,
                matrix=matrix,
                matrix_index=matrix_index,
                settings=settings,
                cycle_count=cycle_count,
                resource_activation_penalty=(
                    (
                        settings.additional_resource_activation_penalty
                        if activates_additional_resource
                        else 0.0
                    )
                    + _support_positioning_cost(shift, settings)
                    if cycle_count == 0
                    else 0.0
                ),
                prefer_shift_reserve=not activated_shift_ids,
                prefer_tight_fit=prefer_tight_fit,
                activates_additional_resource=activates_additional_resource,
                existing_shift_cycles=existing_shift_cycles,
            )
            if candidate is not None and not _resource_overlap(candidate.cycle, existing_cycles):
                candidates.append(candidate)
        return candidates, evaluated, _deadline_reached(deadline)

    def _project_delivery_plan(
        self,
        *,
        remaining_deliveries: tuple[PlanningTask, ...],
        shifts: tuple[DriverShift, ...],
        available_at: Mapping[str, datetime],
        warehouse: Warehouse,
        vehicles: Mapping[str, Vehicle],
        matrix: _PlannerMatrix,
        matrix_index: Mapping[str, int],
        settings: PlanningSettings,
        cycles: tuple[RouteCycle, ...],
        deadline: float,
        deadline_first: bool = False,
        longest_first: bool = True,
    ) -> tuple[tuple[RouteCycle, ...], dict[str, datetime], frozenset[str]]:
        """Build one bounded delivery-only reference in the selected priority order."""

        pending = {task.id: task for task in remaining_deliveries}
        projected_cycles = list(cycles)
        projected_available = dict(available_at)
        assigned: set[str] = set()
        turnaround = timedelta(
            minutes=warehouse.turnaround_minutes + settings.default_route_buffer_minutes
        )
        while pending and not _deadline_reached(deadline):
            ordered = sorted(
                pending.values(),
                key=lambda task: _delivery_reference_priority_key(
                    task,
                    matrix,
                    matrix_index,
                    deadline_first=deadline_first,
                    longest_first=longest_first,
                ),
            )
            anchor = ordered[0]
            groups: list[tuple[PlanningTask, ...]] = [(anchor,)]
            if anchor.quantity == 1:
                # The reference plan is a coverage fence, not a second full
                # optimization pass. Trying every configured neighbor here
                # duplicated the dense-day search and made 15 + 10 workloads
                # CPU-bound. Two nearest compatible alternatives retain a
                # deterministic escape when the closest pair misses a window
                # while keeping the projection linear in practice.
                reference_neighbor_limit = min(
                    2,
                    settings.max_candidate_neighbors,
                )
                neighbors = sorted(
                    (
                        task
                        for task in pending.values()
                        if task.id != anchor.id
                        and task.quantity == 1
                        and _matrix_has_exact_edge(
                            matrix,
                            matrix_index[anchor.id],
                            matrix_index[task.id],
                        )
                    ),
                    key=lambda task: (
                        matrix.at(
                            matrix_index[anchor.id],
                            matrix_index[task.id],
                        ).travel_seconds,
                        _task_priority_key(task),
                    ),
                )[:reference_neighbor_limit]
                for neighbor in neighbors:
                    groups.append((anchor, neighbor))
                    groups.append((neighbor, anchor))

            candidates: list[_Candidate] = []
            activated_shift_ids = frozenset(cycle.driver_shift_id for cycle in projected_cycles)
            for shift in shifts:
                if _deadline_reached(deadline):
                    break
                own_cycles = tuple(
                    cycle for cycle in projected_cycles if cycle.driver_shift_id == shift.id
                )
                for group in groups:
                    if _deadline_reached(deadline):
                        break
                    probe = self._schedule_candidate(
                        shift=shift,
                        start_at=projected_available[shift.id],
                        sequence=max(
                            (cycle.sequence for cycle in own_cycles),
                            default=0,
                        )
                        + 1,
                        deliveries=group,
                        pickups=(),
                        warehouse=warehouse,
                        vehicle=vehicles[shift.vehicle_id],
                        matrix=matrix,
                        matrix_index=matrix_index,
                        settings=settings,
                        cycle_count=len(own_cycles),
                        resource_activation_penalty=(
                            (
                                settings.additional_resource_activation_penalty
                                if activated_shift_ids and shift.id not in activated_shift_ids
                                else 0.0
                            )
                            + _support_positioning_cost(shift, settings)
                            if not own_cycles
                            else 0.0
                        ),
                        prefer_shift_reserve=not activated_shift_ids,
                        activates_additional_resource=(
                            bool(activated_shift_ids) and shift.id not in activated_shift_ids
                        ),
                        existing_shift_cycles=own_cycles,
                    )
                    if probe is not None and not _resource_overlap(
                        probe.cycle,
                        projected_cycles,
                    ):
                        candidates.append(probe)
            if not candidates:
                pending.pop(anchor.id)
                continue
            chosen = min(candidates, key=lambda item: item.selection_key)
            projected_cycles.append(chosen.cycle)
            projected_available[chosen.cycle.driver_shift_id] = (
                chosen.cycle.planned_finish + turnaround
            )
            for task in chosen.deliveries:
                pending.pop(task.id, None)
                assigned.add(task.id)
        return (
            tuple(projected_cycles),
            projected_available,
            frozenset(assigned),
        )

    def _attach_pickups_to_delivery_reference(
        self,
        *,
        pickup_tasks: tuple[PlanningTask, ...],
        shifts: tuple[DriverShift, ...],
        available_at: Mapping[str, datetime],
        warehouse: Warehouse,
        vehicles: Mapping[str, Vehicle],
        matrix: _PlannerMatrix,
        matrix_index: Mapping[str, int],
        settings: PlanningSettings,
        cycles: tuple[RouteCycle, ...],
        task_by_id: Mapping[str, PlanningTask],
        max_evaluations: int,
        deadline: float,
    ) -> tuple[tuple[RouteCycle, ...], dict[str, datetime], int]:
        """Attach pickups without dropping or invalidating reference deliveries.

        Every candidate is accepted only when a full reschedule of that shift's
        suffix preserves every delivery, window and hard shift limit.
        """

        assigned_ids = {task_id for cycle in cycles for task_id in cycle.task_ids}
        pending = {task.id: task for task in pickup_tasks if task.id not in assigned_ids}
        rebuilt_cycles = list(cycles)
        evaluated = 0
        turnaround = timedelta(
            minutes=warehouse.turnaround_minutes + settings.default_route_buffer_minutes
        )
        while (
            pending
            and evaluated < max_evaluations
            and not _deadline_reached(deadline)
        ):
            candidates: list[
                tuple[
                    tuple[object, ...],
                    tuple[RouteCycle, ...],
                    tuple[PlanningTask, ...],
                ]
            ] = []
            budget_exhausted = False
            for target_index, target in enumerate(rebuilt_cycles):
                if _deadline_reached(deadline):
                    budget_exhausted = True
                    break
                if target.locked or any(
                    task_id in task_by_id and task_by_id[task_id].task_type is TaskType.PICKUP
                    for task_id in target.task_ids
                ):
                    continue
                shift = next(
                    (
                        item
                        for item in shifts
                        if _shift_option_id(item)
                        == (target.resource_option_id or target.driver_shift_id)
                    ),
                    None,
                )
                if shift is None:
                    continue
                deliveries = tuple(
                    task_by_id[task_id]
                    for task_id in target.task_ids
                    if task_id in task_by_id and task_by_id[task_id].task_type is TaskType.DELIVERY
                )
                if not deliveries:
                    continue
                last_delivery_index = matrix_index[deliveries[-1].id]
                nearby_pickups = sorted(
                    (
                        task
                        for task in pending.values()
                        if _matrix_has_exact_edge(
                            matrix,
                            last_delivery_index,
                            matrix_index[task.id],
                        )
                    ),
                    key=lambda task: (
                        matrix.at(
                            last_delivery_index,
                            matrix_index[task.id],
                        ).travel_seconds,
                        _task_priority_key(task),
                    ),
                )[: settings.max_candidate_neighbors]
                pickup_groups = _ordered_groups(
                    nearby_pickups,
                    matrix,
                    matrix_index,
                    max_neighbors=settings.max_candidate_neighbors,
                    anchor_limit=settings.max_candidate_neighbors,
                )
                pickup_groups.sort(
                    key=lambda group: _pickup_attachment_rank(
                        deliveries,
                        group,
                        matrix,
                        matrix_index,
                    )
                )
                for pickup_group in pickup_groups[: settings.max_candidate_neighbors]:
                    if evaluated >= max_evaluations or _deadline_reached(deadline):
                        budget_exhausted = True
                        break
                    rebuilt, rebuild_evaluations = self._reschedule_reference_attachment(
                        target_index=target_index,
                        pickup_group=pickup_group,
                        shift=shift,
                        warehouse=warehouse,
                        vehicle=vehicles[shift.vehicle_id],
                        matrix=matrix,
                        matrix_index=matrix_index,
                        settings=settings,
                        cycles=tuple(rebuilt_cycles),
                        task_by_id=task_by_id,
                        max_evaluations=max_evaluations - evaluated,
                    )
                    evaluated += rebuild_evaluations
                    if rebuilt is None:
                        continue
                    candidates.append(
                        (
                            _pickup_attachment_rank(
                                deliveries,
                                pickup_group,
                                matrix,
                                matrix_index,
                            ),
                            rebuilt,
                            pickup_group,
                        )
                    )
                if budget_exhausted:
                    break
            if not candidates:
                break
            _, chosen_cycles, chosen_pickups = min(
                candidates,
                key=lambda item: (
                    item[0],
                    sum(cycle.score for cycle in item[1]),
                    tuple(cycle.id for cycle in item[1]),
                ),
            )
            rebuilt_cycles = list(chosen_cycles)
            for pickup in chosen_pickups:
                pending.pop(pickup.id, None)

        final_available = dict(available_at)
        for shift in shifts:
            final_available[shift.id] = max(
                (
                    cycle.planned_finish + turnaround
                    for cycle in rebuilt_cycles
                    if cycle.driver_shift_id == shift.id
                ),
                default=max(shift.start_at, shift.available_from or shift.start_at),
            )
        return tuple(rebuilt_cycles), final_available, evaluated

    def _reschedule_reference_attachment(
        self,
        *,
        target_index: int,
        pickup_group: tuple[PlanningTask, ...],
        shift: DriverShift,
        warehouse: Warehouse,
        vehicle: Vehicle,
        matrix: _PlannerMatrix,
        matrix_index: Mapping[str, int],
        settings: PlanningSettings,
        cycles: tuple[RouteCycle, ...],
        task_by_id: Mapping[str, PlanningTask],
        max_evaluations: int,
    ) -> tuple[tuple[RouteCycle, ...] | None, int]:
        """Rebuild one shift suffix for a proposed reference pickup attachment."""

        shift_entries = sorted(
            (
                (index, cycle)
                for index, cycle in enumerate(cycles)
                if cycle.driver_shift_id == shift.id
            ),
            key=lambda item: (
                item[1].planned_start,
                item[1].sequence,
                item[1].id,
            ),
        )
        target_position = next(
            (
                position
                for position, (index, _) in enumerate(shift_entries)
                if index == target_index
            ),
            None,
        )
        if target_position is None:
            return None, 0
        suffix_indices = {index for index, _ in shift_entries[target_position:]}
        fixed_cycles = [cycle for index, cycle in enumerate(cycles) if index not in suffix_indices]
        rebuilt = list(cycles)
        cursor = cycles[target_index].planned_start
        turnaround = timedelta(
            minutes=warehouse.turnaround_minutes + settings.default_route_buffer_minutes
        )
        evaluated = 0
        for source_index, source in shift_entries[target_position:]:
            if source.locked:
                if cursor > source.planned_start or _resource_overlap(source, fixed_cycles):
                    return None, evaluated
                fixed_cycles.append(source)
                cursor = source.planned_finish + turnaround
                continue
            if evaluated >= max_evaluations:
                return None, evaluated
            source_tasks = tuple(
                task_by_id[task_id] for task_id in source.task_ids if task_id in task_by_id
            )
            if len(source_tasks) != len(source.task_ids):
                return None, evaluated
            deliveries = tuple(task for task in source_tasks if task.task_type is TaskType.DELIVERY)
            pickups = (
                pickup_group
                if source_index == target_index
                else tuple(task for task in source_tasks if task.task_type is TaskType.PICKUP)
            )
            existing_shift_cycles = tuple(
                cycle for cycle in fixed_cycles if cycle.driver_shift_id == shift.id
            )
            evaluated += 1
            probe = self._schedule_candidate(
                shift=shift,
                start_at=max(cursor, source.planned_start),
                sequence=source.sequence,
                deliveries=deliveries,
                pickups=pickups,
                warehouse=warehouse,
                vehicle=vehicle,
                matrix=matrix,
                matrix_index=matrix_index,
                settings=settings,
                cycle_count=len(existing_shift_cycles),
                existing_shift_cycles=existing_shift_cycles,
            )
            if probe is None or _resource_overlap(probe.cycle, fixed_cycles):
                return None, evaluated
            rebuilt[source_index] = probe.cycle
            fixed_cycles.append(probe.cycle)
            cursor = probe.cycle.planned_finish + turnaround
        return tuple(rebuilt), evaluated

    def _schedule_candidate(
        self,
        *,
        shift: DriverShift,
        start_at: datetime,
        sequence: int,
        deliveries: tuple[PlanningTask, ...],
        pickups: tuple[PlanningTask, ...],
        warehouse: Warehouse,
        vehicle: Vehicle,
        matrix: _PlannerMatrix,
        matrix_index: Mapping[str, int],
        settings: PlanningSettings,
        cycle_count: int,
        resource_activation_penalty: float = 0.0,
        prefer_shift_reserve: bool = False,
        prefer_tight_fit: bool = False,
        activates_additional_resource: bool = False,
        existing_shift_cycles: tuple[RouteCycle, ...] = (),
        ordered_tasks: tuple[PlanningTask, ...] | None = None,
    ) -> _Candidate | None:
        """Schedule one sequence, optionally preserving an explicit operator order.

        A future window first shifts the complete routed prefix, including
        depot loading, as far as earlier customer windows permit. Only the
        bounded residual wait remains at the previous stop; a larger gap makes
        the candidate infeasible so it can be split into another depot cycle.
        """

        task_sequence = ordered_tasks if ordered_tasks is not None else deliveries + pickups
        if not task_sequence:
            return None
        if {task.id for task in task_sequence} != {
            task.id for task in deliveries + pickups
        }:
            raise ValueError("ordered_tasks must contain exactly the candidate tasks")
        if any(
            task.id not in matrix_index or not _shift_allows_task(shift, task)
            for task in task_sequence
        ):
            return None
        route_indices = (
            0,
            *(matrix_index[task.id] for task in task_sequence),
            0,
        )
        if any(
            not _matrix_has_exact_edge(matrix, start, finish)
            for start, finish in pairwise(route_indices)
        ):
            return None
        if (
            len(deliveries) > settings.max_delivery_stops
            or len(pickups) > settings.max_pickup_stops
        ):
            return None
        delivery_load = sum(task.quantity for task in deliveries)
        pickup_load = sum(task.quantity for task in pickups)
        capacity = min(vehicle.capacity, settings.vehicle_capacity)
        if delivery_load > capacity or pickup_load > capacity:
            return None
        if max(delivery_load, pickup_load) > 1 and any(
            not task.trailer_access_allowed for task in deliveries + pickups
        ):
            return None
        if any(task.quantity == 2 for task in deliveries) and len(deliveries) > 1:
            return None
        if any(task.quantity == 2 for task in pickups) and len(pickups) > 1:
            return None

        cursor = max(start_at, shift.start_at, shift.available_from or shift.start_at)
        load_seconds = (
            (warehouse.loading_minutes or settings.default_load_minutes) * 60 if deliveries else 0
        )
        first_task = task_sequence[0]
        first_window_start = first_task.selected_option.window_start
        if first_window_start is not None:
            first_travel_seconds = matrix.at(0, matrix_index[first_task.id]).travel_seconds
            just_in_time_start = first_window_start - timedelta(
                seconds=load_seconds + first_travel_seconds
            )
            cursor = max(cursor, just_in_time_start)
        stops: list[RouteStop] = [
            RouteStop(
                sequence=0,
                stop_type=StopType.DEPOT_LOAD,
                point=warehouse.point,
                planned_arrival=cursor,
                planned_departure=cursor + timedelta(seconds=load_seconds),
                service_seconds=load_seconds,
                quantity_delta=delivery_load,
                load_before=0,
                load_after=delivery_load,
                address_label=warehouse.name,
            )
        ]
        cursor = stops[0].planned_departure
        current_index = 0
        current_load = delivery_load
        legs: list[PlannedLeg] = []
        waiting_seconds = 0
        soft_window_seconds = 0
        empty_distance = 0
        warnings: set[ValidationWarningCode] = set()
        for task in task_sequence:
            next_index = matrix_index[task.id]
            metric = matrix.at(current_index, next_index)
            leg_departure = cursor
            arrival = leg_departure + timedelta(seconds=metric.travel_seconds)
            window = task.selected_option
            if window.window_start is not None and arrival < window.window_start:
                waiting = window.window_start - arrival
                prior_window_slack = tuple(
                    max(timedelta(0), stop.window_end - stop.planned_departure)
                    for stop in stops
                    if stop.task_id is not None and stop.window_end is not None
                )
                warehouse_delay = min((waiting, *prior_window_slack))
                if warehouse_delay:
                    stops = [
                        replace(
                            stop,
                            planned_arrival=stop.planned_arrival + warehouse_delay,
                            planned_departure=stop.planned_departure + warehouse_delay,
                        )
                        for stop in stops
                    ]
                    legs = [
                        replace(
                            leg,
                            departure_at=leg.departure_at + warehouse_delay,
                            arrival_at=leg.arrival_at + warehouse_delay,
                        )
                        for leg in legs
                    ]
                    cursor += warehouse_delay
                    leg_departure += warehouse_delay
                    arrival += warehouse_delay
                residual_wait = max(timedelta(0), window.window_start - arrival)
                if residual_wait > timedelta(minutes=settings.max_customer_wait_minutes):
                    return None
                waiting_seconds += round(residual_wait.total_seconds())
                leg_departure += residual_wait
                arrival = window.window_start
            service_seconds = task.service_minutes * 60
            service_finish = arrival + timedelta(seconds=service_seconds)
            if window.window_end is not None and arrival > window.window_end:
                violation = round((arrival - window.window_end).total_seconds())
                if window.is_hard:
                    return None
                soft_window_seconds += violation
                warnings.add(ValidationWarningCode.SOFT_WINDOW_RISK)
            if current_load == 0:
                empty_distance += metric.distance_meters
            delta = -task.quantity if task.task_type is TaskType.DELIVERY else task.quantity
            next_load = current_load + delta
            if next_load < 0 or next_load > capacity:
                return None
            stop_type = (
                StopType.DELIVERY if task.task_type is TaskType.DELIVERY else StopType.PICKUP
            )
            geometry: GeoJsonLineString = {
                "type": "LineString",
                "coordinates": [
                    list(matrix.points[current_index].coordinates),
                    list(task.point.coordinates),
                ],
            }
            legs.append(
                PlannedLeg(
                    from_stop_sequence=len(stops) - 1,
                    to_stop_sequence=len(stops),
                    departure_at=leg_departure,
                    arrival_at=arrival,
                    distance_meters=metric.distance_meters,
                    travel_seconds=metric.travel_seconds,
                    geometry=geometry,
                )
            )
            stops.append(
                RouteStop(
                    sequence=len(stops),
                    stop_type=stop_type,
                    point=task.point,
                    planned_arrival=arrival,
                    planned_departure=service_finish,
                    service_seconds=service_seconds,
                    quantity_delta=delta,
                    load_before=current_load,
                    load_after=next_load,
                    task_id=task.id,
                    request_id=task.request_id,
                    address_label=task.address_label,
                    window_start=window.window_start,
                    window_end=window.window_end,
                    window_is_hard=window.is_hard,
                    service_warehouse_id=task.service_warehouse_id,
                )
            )
            cursor = service_finish
            current_load = next_load
            current_index = next_index

        return_metric = matrix.at(current_index, 0)
        if current_load == 0:
            empty_distance += return_metric.distance_meters
        return_arrival = cursor + timedelta(seconds=return_metric.travel_seconds)
        unload_seconds = (
            (warehouse.unloading_minutes or settings.default_unload_minutes) * 60
            if current_load
            else 0
        )
        finish = return_arrival + timedelta(seconds=unload_seconds)
        overtime_seconds = max(0, round((finish - shift.end_at).total_seconds()))
        allowed_overtime = (
            settings.allow_soft_overtime
            and overtime_seconds <= settings.soft_overtime_limit_minutes * 60
        )
        if overtime_seconds and not allowed_overtime:
            return None
        if overtime_seconds:
            warnings.add(ValidationWarningCode.OVERTIME_WARNING)
        return_geometry: GeoJsonLineString = {
            "type": "LineString",
            "coordinates": [
                list(matrix.points[current_index].coordinates),
                list(warehouse.point.coordinates),
            ],
        }
        legs.append(
            PlannedLeg(
                from_stop_sequence=len(stops) - 1,
                to_stop_sequence=len(stops),
                departure_at=cursor,
                arrival_at=return_arrival,
                distance_meters=return_metric.distance_meters,
                travel_seconds=return_metric.travel_seconds,
                geometry=return_geometry,
            )
        )
        stops.append(
            RouteStop(
                sequence=len(stops),
                stop_type=StopType.DEPOT_RETURN,
                point=warehouse.point,
                planned_arrival=return_arrival,
                planned_departure=finish,
                service_seconds=unload_seconds,
                quantity_delta=-current_load,
                load_before=current_load,
                load_after=0,
                address_label=warehouse.name,
            )
        )

        detour_seconds, detour_ratio = _pickup_detour(
            deliveries,
            pickups,
            matrix,
            matrix_index,
        )
        if _pickup_detour_exceeds_limit(
            deliveries,
            pickups,
            detour_seconds,
            detour_ratio,
            settings,
        ):
            return None
        travel_seconds = sum(leg.travel_seconds for leg in legs)
        service_seconds_total = sum(stop.service_seconds for stop in stops)
        score = (
            travel_seconds / 60.0
            + (empty_distance / max(1, sum(leg.distance_meters for leg in legs)))
            * (travel_seconds / 60.0)
            * settings.empty_travel_weight
            + detour_seconds / 60.0 * settings.detour_weight
            + waiting_seconds / 60.0 * settings.waiting_weight
            + soft_window_seconds / 60.0 * settings.soft_window_violation_penalty
            + overtime_seconds / 60.0 * settings.overtime_penalty_per_minute
            - (settings.paired_delivery_bonus if len(deliveries) == 2 else 0.0)
            - (settings.paired_pickup_bonus if len(pickups) == 2 else 0.0)
        )
        task_ids = tuple(task.id for task in task_sequence)
        resource_option_id = _shift_option_id(shift)
        cycle_id = (
            f"cycle:{shift.id}:{resource_option_id}:{sequence}:{'|'.join(task_ids)}"
            if shift.resource_option_id is not None
            else f"cycle:{shift.id}:{sequence}:{'|'.join(task_ids)}"
        )
        explanation = _cycle_explanation(
            deliveries,
            pickups,
            shift,
            detour_seconds,
            resource_reused=cycle_count > 0,
            activates_additional_resource=activates_additional_resource,
        )
        cycle = RouteCycle(
            id=cycle_id,
            driver_shift_id=shift.id,
            driver_id=shift.driver_id,
            vehicle_id=shift.vehicle_id,
            sequence=sequence,
            planned_start=stops[0].planned_arrival,
            planned_finish=stops[-1].planned_departure,
            stops=tuple(stops),
            legs=tuple(legs),
            total_distance_meters=sum(leg.distance_meters for leg in legs),
            total_travel_seconds=travel_seconds,
            total_service_seconds=service_seconds_total,
            waiting_seconds=waiting_seconds,
            empty_distance_meters=empty_distance,
            detour_seconds=detour_seconds,
            score=round(score, 6),
            detour_ratio=detour_ratio,
            resource_option_id=shift.resource_option_id,
            explanation=explanation,
            warnings=tuple(sorted(warnings, key=lambda warning: warning.value)),
        )
        workload_penalty = incremental_shift_workload_cost(
            existing_shift_cycles,
            cycle,
            shift,
            settings,
        )
        projected_utilization = shift_utilization_percent(
            (*existing_shift_cycles, cycle),
            shift,
        )
        cycle = replace(
            cycle,
            explanation=(
                *cycle.explanation,
                f"Расчётная загрузка смены после рейса — "
                f"{round(projected_utilization)}%; перерыв {shift.break_minutes} мин "
                f"учтён, мягкая цель — "
                f"{round(settings.preferred_shift_utilization_percent)}%.",
            ),
        )
        priority_key = _candidate_priority_key(
            deliveries,
            pickups,
        )
        stable_rank = _stable_rank(settings.seed, shift.id, resource_option_id, *task_ids)
        warning_rank = (
            int(ValidationWarningCode.SOFT_WINDOW_RISK in warnings),
            int(ValidationWarningCode.OVERTIME_WARNING in warnings),
        )
        remaining_shift_seconds = max(
            0,
            round((shift.end_at - cycle.planned_finish).total_seconds()),
        )
        return _Candidate(
            cycle=cycle,
            deliveries=deliveries,
            pickups=pickups,
            selection_key=(
                *priority_key,
                *warning_rank,
                -int(bool(deliveries and pickups)),
                -sum(task.quantity for task in pickups),
                (
                    remaining_shift_seconds
                    if prefer_tight_fit
                    else -remaining_shift_seconds
                    if prefer_shift_reserve
                    else 0
                ),
                cycle.score + resource_activation_penalty + workload_penalty,
                cycle.planned_finish,
                cycle_count,
                stable_rank,
                shift.id,
                resource_option_id,
                task_ids,
            ),
            resource_activation_penalty=resource_activation_penalty,
            driver_workload_penalty=workload_penalty,
            projected_shift_utilization_percent=projected_utilization,
        )

    def _diagnose_task(
        self,
        *,
        task: PlanningTask,
        shifts: tuple[DriverShift, ...],
        available_at: Mapping[str, datetime],
        warehouses: Mapping[str, Warehouse],
        vehicles: Mapping[str, Vehicle],
        matrices: Mapping[str, _PlannerMatrix],
        matrix_indices: Mapping[str, Mapping[str, int]],
        settings: PlanningSettings,
        cycles: tuple[RouteCycle, ...],
    ) -> tuple[tuple[UnassignedReasonCode, ...], datetime | None]:
        """Determine concrete singleton feasibility reasons for one leftover task."""

        candidates: list[_Candidate] = []
        nearest: datetime | None = None
        eligible_resource_seen = False
        for shift in shifts:
            if not _shift_allows_task(shift, task):
                continue
            option_id = _shift_option_id(shift)
            matrix = matrices.get(option_id)
            matrix_index = matrix_indices.get(option_id)
            warehouse = warehouses.get(option_id)
            if (
                matrix is None
                or matrix_index is None
                or warehouse is None
                or task.id not in matrix_index
                or not _matrix_has_exact_edge(matrix, 0, matrix_index[task.id])
            ):
                continue
            eligible_resource_seen = True
            start = available_at[shift.id]
            arrival = start + timedelta(
                minutes=settings.default_load_minutes if task.task_type is TaskType.DELIVERY else 0,
                seconds=matrix.at(0, matrix_index[task.id]).travel_seconds,
            )
            nearest = arrival if nearest is None else min(nearest, arrival)
            candidate = self._schedule_candidate(
                shift=shift,
                start_at=start,
                sequence=1,
                deliveries=(task,) if task.task_type is TaskType.DELIVERY else (),
                pickups=(task,) if task.task_type is TaskType.PICKUP else (),
                warehouse=warehouse,
                vehicle=vehicles[shift.vehicle_id],
                matrix=matrix,
                matrix_index=matrix_index,
                settings=settings,
                cycle_count=0,
            )
            if candidate is not None and not _resource_overlap(candidate.cycle, cycles):
                candidates.append(candidate)
        if candidates:
            return (UnassignedReasonCode.NO_SHIFT_CAPACITY,), nearest
        if not eligible_resource_seen:
            return (UnassignedReasonCode.NO_SHIFT_CAPACITY,), None
        option = task.selected_option
        if option.window_end is not None and nearest is not None:
            if nearest + timedelta(minutes=task.service_minutes) > option.window_end:
                return (
                    UnassignedReasonCode.TIME_WINDOW_CONFLICT,
                ), nearest
        return (UnassignedReasonCode.SHIFT_LIMIT_EXCEEDED,), nearest

    def _local_improve(
        self,
        *,
        cycles: list[RouteCycle],
        task_by_id: Mapping[str, PlanningTask],
        shifts: Mapping[str, DriverShift],
        warehouses: Mapping[str, Warehouse],
        vehicles: Mapping[str, Vehicle],
        matrices: Mapping[str, _PlannerMatrix],
        matrix_indices: Mapping[str, Mapping[str, int]],
        settings: PlanningSettings,
        deadline: float,
    ) -> tuple[list[RouteCycle], int]:
        """Try bounded delivery/pickup reversals and retain strict improvements."""

        result = list(cycles)
        iterations = 0
        for index, cycle in enumerate(tuple(result)):
            if _deadline_reached(deadline):
                break
            if cycle.locked or iterations >= settings.max_local_search_iterations:
                continue
            deliveries = tuple(
                task
                for stop in cycle.stops
                if stop.stop_type is StopType.DELIVERY
                and (task := task_by_id.get(stop.task_id or "")) is not None
            )
            pickups = tuple(
                task
                for stop in cycle.stops
                if stop.stop_type is StopType.PICKUP
                and (task := task_by_id.get(stop.task_id or "")) is not None
            )
            variants: list[tuple[tuple[PlanningTask, ...], tuple[PlanningTask, ...]]] = []
            if len(deliveries) == 2:
                variants.append((tuple(reversed(deliveries)), pickups))
            if len(pickups) == 2:
                variants.append((deliveries, tuple(reversed(pickups))))
            if len(deliveries) == 2 and len(pickups) == 2:
                variants.append((tuple(reversed(deliveries)), tuple(reversed(pickups))))
            for delivery_variant, pickup_variant in variants:
                if (
                    iterations >= settings.max_local_search_iterations
                    or _deadline_reached(deadline)
                ):
                    break
                iterations += 1
                option_id = cycle.resource_option_id or cycle.driver_shift_id
                shift = shifts.get(option_id)
                vehicle = vehicles.get(cycle.vehicle_id)
                warehouse = warehouses.get(option_id)
                matrix = matrices.get(option_id)
                matrix_index = matrix_indices.get(option_id)
                if (
                    shift is None
                    or vehicle is None
                    or warehouse is None
                    or matrix is None
                    or matrix_index is None
                ):
                    continue
                candidate = self._schedule_candidate(
                    shift=shift,
                    start_at=cycle.planned_start,
                    sequence=cycle.sequence,
                    deliveries=delivery_variant,
                    pickups=pickup_variant,
                    warehouse=warehouse,
                    vehicle=vehicle,
                    matrix=matrix,
                    matrix_index=matrix_index,
                    settings=settings,
                    cycle_count=max(0, cycle.sequence - 1),
                    existing_shift_cycles=tuple(
                        item
                        for item in result
                        if item.id != cycle.id and item.driver_shift_id == cycle.driver_shift_id
                    ),
                )
                other_cycles = tuple(item for item in result if item.id != cycle.id)
                candidate_cycles = (
                    [candidate.cycle if item.id == cycle.id else item for item in result]
                    if candidate is not None
                    else []
                )
                if (
                    candidate is not None
                    and _plan_score(
                        candidate_cycles,
                        (),
                        settings,
                        shifts.values(),
                    )
                    + 1e-9
                    < _plan_score(result, (), settings, shifts.values())
                    and not _resource_overlap(candidate.cycle, other_cycles)
                ):
                    result[index] = candidate.cycle
        return result, iterations


def _partition_tasks(
    tasks: Sequence[PlanningTask],
) -> tuple[tuple[PlanningTask, ...], ...]:
    """Build bounded time-window batches plus overlapping spatial neighbours.

    Window batches preserve exact edges among tasks competing for the same part of
    the day.  A second globally spatial ordering overlaps adjacent chunks so nearby
    delivery/pickup work across window buckets can still be evaluated with exact
    directed road metrics. Geometry determines only which sparse edges to request;
    it never confirms route feasibility.
    """

    by_window: defaultdict[int, list[PlanningTask]] = defaultdict(list)
    for task in tasks:
        option = task.selected_option
        clock = option.window_start or option.window_end or task.created_at
        minute_of_day = clock.hour * 60 + clock.minute
        by_window[minute_of_day // _TIME_WINDOW_PARTITION_MINUTES].append(task)

    partitions: list[tuple[PlanningTask, ...]] = []
    for window_bucket in sorted(by_window):
        spatially_ordered = sorted(
            by_window[window_bucket],
            key=lambda task: (
                floor(task.point.lat / _SPATIAL_PARTITION_DEGREES),
                floor(task.point.lon / _SPATIAL_PARTITION_DEGREES),
                task.point.lat,
                task.point.lon,
                _task_priority_key(task),
            ),
        )
        partitions.extend(
            tuple(spatially_ordered[index : index + _MAX_TASKS_PER_MATRIX_BATCH])
            for index in range(0, len(spatially_ordered), _MAX_TASKS_PER_MATRIX_BATCH)
        )

    globally_spatial = sorted(
        tasks,
        key=lambda task: (
            floor(task.point.lat / _SPATIAL_PARTITION_DEGREES),
            floor(task.point.lon / _SPATIAL_PARTITION_DEGREES),
            task.point.lat,
            task.point.lon,
            _task_priority_key(task),
        ),
    )
    spatial_stride = _MAX_TASKS_PER_MATRIX_BATCH - _SPATIAL_NEIGHBOR_OVERLAP
    for index in range(0, len(globally_spatial), spatial_stride):
        partitions.append(
            tuple(globally_spatial[index : index + _MAX_TASKS_PER_MATRIX_BATCH])
        )
        if index + _MAX_TASKS_PER_MATRIX_BATCH >= len(globally_spatial):
            break

    unique: list[tuple[PlanningTask, ...]] = []
    seen: set[tuple[str, ...]] = set()
    for partition in partitions:
        identity = tuple(task.id for task in partition)
        if not partition or identity in seen:
            continue
        seen.add(identity)
        unique.append(partition)
    return tuple(unique)


def _matrix_has_exact_edge(
    matrix: _PlannerMatrix,
    from_index: int,
    to_index: int,
) -> bool:
    """Return whether candidate feasibility can use an exact directed provider metric."""

    if isinstance(matrix, _SparseTravelMatrix):
        return matrix.has_exact_edge(from_index, to_index)
    return True


def _deadline_reached(deadline: float) -> bool:
    """Check the real monotonic cancellation fence independently of evaluation count."""

    return monotonic() >= deadline


def _shift_option_id(shift: DriverShift) -> str:
    """Return a demand-aware route option without changing the physical shift ID."""

    return shift.resource_option_id or shift.id


def _shift_allows_task(shift: DriverShift, task: PlanningTask) -> bool:
    """Enforce the dated support-link scope carried by the immutable snapshot."""

    return (
        task.service_warehouse_id is None
        or shift.allowed_service_warehouse_ids is None
        or task.service_warehouse_id in shift.allowed_service_warehouse_ids
    )


def _depot_key(warehouse: Warehouse) -> tuple[object, ...]:
    """Build a stable physical-depot key for matrix reuse across route options."""

    return (
        warehouse.id,
        warehouse.point.lon,
        warehouse.point.lat,
        warehouse.loading_minutes,
        warehouse.unloading_minutes,
        warehouse.turnaround_minutes,
    )


def _unique_physical_shifts(shifts: Iterable[DriverShift]) -> tuple[DriverShift, ...]:
    """Deduplicate route options for physical-shift workload and activation totals."""

    selected: dict[str, DriverShift] = {}
    for shift in sorted(
        shifts,
        key=lambda item: (
            item.id,
            item.start_at,
            item.end_at,
            _shift_option_id(item),
        ),
    ):
        selected.setdefault(shift.id, shift)
    return tuple(selected.values())


def split_request(
    request: LogisticsRequest,
    selected_option: RequestDateOption,
    *,
    remaining_date_count: int,
    is_last_available_date: bool,
) -> tuple[PlanningTask, ...]:
    """Split a request into stable two-cabin parts, for example ``5 -> 2,2,1``."""

    requires_multiple_parts = request.quantity > (1 if not request.trailer_access_allowed else 2)
    if requires_multiple_parts and not request.split_allowed:
        raise ValueError("request quantity exceeds capacity and splitting is disabled")
    quantities: list[int] = list(request.task_quantities or ())
    if not quantities:
        left = request.quantity
        capacity = 1 if not request.trailer_access_allowed else 2
        while left:
            quantity = min(capacity, left)
            quantities.append(quantity)
            left -= quantity
    return tuple(
        PlanningTask(
            id=f"{request.id}:part:{part_number}",
            request_id=request.id,
            part_number=part_number,
            quantity=quantity,
            task_type=request.request_type,
            name=request.name,
            address_label=request.address_label,
            point=request.point,
            service_minutes=request.service_minutes,
            priority=request.priority,
            status=request.status,
            created_at=request.created_at,
            selected_option=selected_option,
            remaining_date_count=remaining_date_count,
            is_last_available_date=is_last_available_date,
            cargo_dimensions=request.cargo_dimensions,
            trailer_access_allowed=request.trailer_access_allowed,
            mandatory=request.mandatory,
            service_warehouse_id=request.service_warehouse_id,
        )
        for part_number, quantity in enumerate(quantities, start=1)
    )


def split_requests_for_date(
    requests: Iterable[LogisticsRequest],
    planning_date: date,
) -> tuple[tuple[PlanningTask, ...], tuple[UnassignedTask, ...]]:
    """Select date options, reject invalid requests, and split eligible quantities."""

    tasks: list[PlanningTask] = []
    unassigned: list[UnassignedTask] = []
    seen_source_keys: set[str] = set()
    for request in sorted(requests, key=lambda item: (item.created_at, item.id)):
        if request.status is not RequestStatus.READY:
            unassigned.append(_unassigned_task(request, (UnassignedReasonCode.REQUEST_NOT_READY,)))
            continue
        matching = [option for option in request.date_options if option.date == planning_date]
        if not matching:
            unassigned.append(_unassigned_task(request, (UnassignedReasonCode.NO_ALLOWED_DATE,)))
            continue
        if request.source_key is not None:
            if request.source_key in seen_source_keys:
                unassigned.append(
                    _unassigned_task(
                        request,
                        (UnassignedReasonCode.DUPLICATE_ASSIGNMENT_CONFLICT,),
                    )
                )
                continue
            seen_source_keys.add(request.source_key)
        option = min(
            matching,
            key=lambda item: (
                not item.is_hard,
                -item.priority,
                item.width,
                item.window_start or request.created_at,
            ),
        )
        available_dates = sorted({item.date for item in request.date_options})
        remaining_count = sum(item >= planning_date for item in available_dates)
        try:
            tasks.extend(
                split_request(
                    request,
                    option,
                    remaining_date_count=max(1, remaining_count),
                    is_last_available_date=planning_date == max(available_dates),
                )
            )
        except ValueError:
            unassigned.append(_unassigned_task(request, (UnassignedReasonCode.NO_SHIFT_CAPACITY,)))
    return tuple(tasks), tuple(unassigned)


def _candidate_specs(
    remaining: tuple[PlanningTask, ...],
    matrix: _PlannerMatrix,
    matrix_index: Mapping[str, int],
    settings: PlanningSettings,
) -> tuple[_CandidateSpec, ...]:
    """Build each bounded task combination once for all shifts in an iteration."""

    ordered = sorted(
        remaining,
        key=lambda task: _task_priority_with_distance(task, matrix, matrix_index),
    )
    anchor_limit = max(12, settings.max_candidate_neighbors * 2)
    deliveries = [task for task in ordered if task.task_type is TaskType.DELIVERY]
    pickups = [task for task in ordered if task.task_type is TaskType.PICKUP]
    delivery_groups = _ordered_groups(
        deliveries,
        matrix,
        matrix_index,
        max_neighbors=settings.max_candidate_neighbors,
        anchor_limit=anchor_limit,
    )
    combinations: list[tuple[tuple[PlanningTask, ...], tuple[PlanningTask, ...]]] = []
    for delivery_group in delivery_groups:
        last_point_index = matrix_index[delivery_group[-1].id]
        eligible_pickups = sorted(
            (
                task
                for task in pickups
                if _matrix_has_exact_edge(
                    matrix,
                    last_point_index,
                    matrix_index[task.id],
                )
            ),
            key=lambda task: (
                matrix.at(last_point_index, matrix_index[task.id]).travel_seconds,
                _task_priority_key(task),
            ),
        )[: settings.max_candidate_neighbors]
        ranked_pickup_groups: list[tuple[tuple[object, ...], tuple[PlanningTask, ...]]] = []
        for pickup_group in _ordered_groups(
            eligible_pickups,
            matrix,
            matrix_index,
            max_neighbors=settings.max_candidate_neighbors,
            anchor_limit=settings.max_candidate_neighbors,
        ):
            detour_seconds, detour_ratio = _pickup_detour(
                delivery_group,
                pickup_group,
                matrix,
                matrix_index,
            )
            if _pickup_detour_exceeds_limit(
                delivery_group,
                pickup_group,
                detour_seconds,
                detour_ratio,
                settings,
            ):
                continue
            ranked_pickup_groups.append(
                (
                    (
                        -sum(task.quantity for task in pickup_group),
                        detour_seconds,
                        detour_ratio,
                        min(_task_priority_key(task) for task in pickup_group),
                        tuple(task.id for task in pickup_group),
                    ),
                    pickup_group,
                )
            )
        ranked_pickup_groups.sort(key=lambda item: item[0])
        pickup_groups = [
            pickup_group
            for _, pickup_group in ranked_pickup_groups[: settings.max_candidate_neighbors]
        ]
        combinations.append((delivery_group, ()))
        combinations.extend((delivery_group, pickup_group) for pickup_group in pickup_groups)

    pickup_only_groups = _ordered_groups(
        pickups,
        matrix,
        matrix_index,
        max_neighbors=settings.max_candidate_neighbors,
        anchor_limit=anchor_limit,
    )
    combinations.extend(((), pickup_group) for pickup_group in pickup_only_groups)
    specs = (
        _CandidateSpec(
            deliveries=delivery_group,
            pickups=pickup_group,
            priority_key=(
                *_candidate_priority_key(
                    delivery_group,
                    pickup_group,
                ),
                *_candidate_route_rank(
                    delivery_group,
                    pickup_group,
                    matrix,
                    matrix_index,
                ),
            ),
        )
        for delivery_group, pickup_group in combinations
    )
    return tuple(
        sorted(
            specs,
            key=lambda spec: (
                spec.priority_key,
                tuple(task.id for task in spec.deliveries),
                tuple(task.id for task in spec.pickups),
            ),
        )
    )


def _candidate_priority_key(
    deliveries: tuple[PlanningTask, ...],
    pickups: tuple[PlanningTask, ...],
) -> tuple[object, ...]:
    """Return the shift-independent prefix of a candidate's final selection key.

    Delivery-bearing candidates are ranked only by delivery urgency and outbound
    packing. Pickup urgency therefore cannot pull a less urgent delivery ahead of
    a mandatory one. Mixed-cycle fill is ranked only after the delivery rank.
    """

    tasks = deliveries or pickups
    anchor = min(tasks, key=_task_priority_key)
    return (
        0 if deliveries else 1,
        not anchor.is_hard,
        not anchor.is_last_available_date,
        -anchor.priority,
        anchor.remaining_date_count,
        anchor.selected_option.window_end is None,
        anchor.selected_option.window_end,
        anchor.selected_option.width,
        -sum(task.quantity for task in tasks),
        -sum(task.is_hard for task in tasks),
        -sum(task.is_last_available_date for task in tasks),
        -sum(task.priority for task in tasks),
        sum(task.remaining_date_count for task in tasks),
        sum(task.selected_option.width.total_seconds() for task in tasks),
    )


def _candidate_route_rank(
    deliveries: tuple[PlanningTask, ...],
    pickups: tuple[PlanningTask, ...],
    matrix: _PlannerMatrix,
    matrix_index: Mapping[str, int],
) -> tuple[object, ...]:
    """Rank equal-urgency combinations by compact, nearby full cycles.

    This shift-independent suffix turns a potentially huge equal-priority
    bucket into small deterministic route buckets. Delivery locality is
    evaluated before any pickup property. Once the delivery sequence is fixed,
    a full nearby backhaul is preferred over a partial or empty return.
    """

    delivery_distance, delivery_travel = _round_trip_metric(
        deliveries or pickups,
        matrix,
        matrix_index,
    )
    full_distance, full_travel = _round_trip_metric(
        deliveries + pickups,
        matrix,
        matrix_index,
    )
    detour_seconds, detour_ratio = _pickup_detour(
        deliveries,
        pickups,
        matrix,
        matrix_index,
    )
    pickup_anchor = min(pickups, key=_task_priority_key) if pickups else None
    return (
        delivery_distance,
        delivery_travel,
        -sum(task.quantity for task in pickups),
        not bool(pickup_anchor and pickup_anchor.is_hard),
        not bool(pickup_anchor and pickup_anchor.is_last_available_date),
        -(pickup_anchor.priority if pickup_anchor is not None else 0),
        pickup_anchor.remaining_date_count if pickup_anchor is not None else 0,
        pickup_anchor.selected_option.width if pickup_anchor is not None else timedelta(0),
        detour_seconds,
        detour_ratio,
        full_distance,
        full_travel,
    )


def _pickup_attachment_rank(
    deliveries: tuple[PlanningTask, ...],
    pickups: tuple[PlanningTask, ...],
    matrix: _PlannerMatrix,
    matrix_index: Mapping[str, int],
) -> tuple[object, ...]:
    """Rank safe reference backhauls by pickup urgency, fill and detour."""

    anchor = min(pickups, key=_task_priority_key)
    detour_seconds, detour_ratio = _pickup_detour(
        deliveries,
        pickups,
        matrix,
        matrix_index,
    )
    full_distance, full_travel = _round_trip_metric(
        deliveries + pickups,
        matrix,
        matrix_index,
    )
    return (
        not anchor.is_hard,
        not anchor.is_last_available_date,
        -anchor.priority,
        anchor.remaining_date_count,
        anchor.selected_option.width,
        -sum(task.quantity for task in pickups),
        detour_seconds,
        detour_ratio,
        full_distance,
        full_travel,
        tuple(task.id for task in pickups),
    )


def _round_trip_metric(
    tasks: Sequence[PlanningTask],
    matrix: _PlannerMatrix,
    matrix_index: Mapping[str, int],
) -> tuple[int, int]:
    """Return matrix distance and travel seconds for depot, tasks, depot."""

    if not tasks:
        return (0, 0)
    distance_meters = 0
    travel_seconds = 0
    current_index = 0
    for task in tasks:
        next_index = matrix_index[task.id]
        metric = matrix.at(current_index, next_index)
        distance_meters += metric.distance_meters
        travel_seconds += metric.travel_seconds
        current_index = next_index
    return_metric = matrix.at(current_index, 0)
    return (
        distance_meters + return_metric.distance_meters,
        travel_seconds + return_metric.travel_seconds,
    )


def _delivery_coverage_key(
    task_ids: Iterable[str],
    task_by_id: Mapping[str, PlanningTask],
) -> tuple[int, ...]:
    """Rank projected delivery coverage with mandatory cabins first."""

    selected = tuple(task_by_id[task_id] for task_id in task_ids)
    return (
        sum(task.quantity for task in selected if task.is_hard),
        sum(task.quantity for task in selected if task.is_last_available_date),
        sum(task.quantity for task in selected),
        len(selected),
        sum(task.priority for task in selected),
        -sum(task.remaining_date_count for task in selected),
        -round(sum(task.selected_option.width.total_seconds() for task in selected)),
    )


def _delivery_task_ids(
    cycles: Iterable[RouteCycle],
    task_by_id: Mapping[str, PlanningTask],
) -> frozenset[str]:
    """Return assigned delivery task IDs from a cycle collection."""

    return frozenset(
        task_id
        for cycle in cycles
        for task_id in cycle.task_ids
        if task_id in task_by_id and task_by_id[task_id].task_type is TaskType.DELIVERY
    )


def _ordered_groups(
    tasks: Sequence[PlanningTask],
    matrix: _PlannerMatrix,
    matrix_index: Mapping[str, int],
    *,
    max_neighbors: int,
    anchor_limit: int,
) -> list[tuple[PlanningTask, ...]]:
    """Create stable singleton and compatible one-cabin pair permutations."""

    ordered = sorted(tasks, key=_task_priority_key)[:anchor_limit]
    groups: list[tuple[PlanningTask, ...]] = [(task,) for task in ordered]
    seen: set[tuple[str, ...]] = {(task.id,) for task in ordered}
    for first in ordered:
        if first.quantity != 1:
            continue
        neighbors = sorted(
            (
                second
                for second in tasks
                if second.id != first.id
                and second.quantity == 1
                and _matrix_has_exact_edge(
                    matrix,
                    matrix_index[first.id],
                    matrix_index[second.id],
                )
            ),
            key=lambda second: (
                matrix.at(matrix_index[first.id], matrix_index[second.id]).travel_seconds,
                _task_priority_key(second),
            ),
        )[:max_neighbors]
        for second in neighbors:
            pair = (first, second)
            key = tuple(task.id for task in pair)
            if key not in seen:
                seen.add(key)
                groups.append(pair)
    return groups


def _pickup_detour(
    deliveries: tuple[PlanningTask, ...],
    pickups: tuple[PlanningTask, ...],
    matrix: _PlannerMatrix,
    matrix_index: Mapping[str, int],
) -> tuple[int, float]:
    """Return extra road travel and ratio caused by pickup stops.

    Pickup service is mandatory work, not a geographic detour. It remains in
    cycle duration, shift/window feasibility and service metrics, while the
    detour limit answers only whether the vehicle drives too far off its return
    corridor.
    """

    if not deliveries or not pickups:
        return 0, 0.0
    last_delivery_index = matrix_index[deliveries[-1].id]
    direct = matrix.at(last_delivery_index, 0).travel_seconds
    with_pickup_travel = 0
    current = last_delivery_index
    for pickup in pickups:
        next_index = matrix_index[pickup.id]
        with_pickup_travel += matrix.at(current, next_index).travel_seconds
        current = next_index
    with_pickup_travel += matrix.at(current, 0).travel_seconds
    travel_detour = max(0, with_pickup_travel - direct)
    return travel_detour, travel_detour / max(1, direct)


def _pickup_detour_exceeds_limit(
    deliveries: tuple[PlanningTask, ...],
    pickups: tuple[PlanningTask, ...],
    detour_seconds: int,
    detour_ratio: float,
    settings: PlanningSettings,
) -> bool:
    """Return whether a backhaul exceeds its configured feasibility thresholds."""

    if not deliveries or not pickups:
        return False
    minute_limit = settings.max_detour_minutes
    ratio_limit = settings.max_detour_ratio
    return detour_seconds > minute_limit * 60 or detour_ratio > ratio_limit


def _task_priority_key(task: PlanningTask) -> tuple[object, ...]:
    """Implement mandatory-date-first, earliest-deadline stable task ordering."""

    return (
        not task.is_hard,
        not task.is_last_available_date,
        -task.priority,
        task.remaining_date_count,
        task.selected_option.window_end is None,
        task.selected_option.window_end,
        task.selected_option.width,
        task.created_at,
        task.id,
    )


def _task_priority_with_distance(
    task: PlanningTask,
    matrix: _PlannerMatrix,
    matrix_index: Mapping[str, int],
) -> tuple[object, ...]:
    """Extend deadline-aware business priority with nearest-first batching."""

    return (
        not task.is_hard,
        not task.is_last_available_date,
        -task.priority,
        task.remaining_date_count,
        task.selected_option.window_end is None,
        task.selected_option.window_end,
        task.selected_option.width,
        matrix.at(0, matrix_index[task.id]).distance_meters,
        task.created_at,
        task.id,
    )


def _delivery_reference_priority_key(
    task: PlanningTask,
    matrix: _PlannerMatrix,
    matrix_index: Mapping[str, int],
    *,
    deadline_first: bool,
    longest_first: bool,
) -> tuple[object, ...]:
    """Build one deadline-first or distance-directed delivery coverage order.

    The planner compares an earliest-deadline fence with longest-first protection
    and nearest-first packing. The deadline fence prevents a feasible morning
    delivery from being consumed by later work with an equally narrow window.
    """

    travel_seconds = matrix.at(0, matrix_index[task.id]).travel_seconds

    if deadline_first:
        return (
            not task.is_hard,
            not task.is_last_available_date,
            -task.priority,
            task.remaining_date_count,
            task.selected_option.window_end is None,
            task.selected_option.window_end,
            task.selected_option.window_start,
            task.selected_option.width,
            travel_seconds,
            task.created_at,
            task.id,
        )

    return (
        not task.is_hard,
        not task.is_last_available_date,
        -task.priority,
        task.remaining_date_count,
        task.selected_option.width,
        -travel_seconds if longest_first else travel_seconds,
        task.created_at,
        task.id,
    )


def _initial_shift_state(
    shifts: Iterable[DriverShift],
    locked_cycles: Iterable[RouteCycle],
    warehouses: Mapping[str, Warehouse],
    route_buffer_minutes: int,
) -> tuple[dict[str, datetime], dict[str, int]]:
    """Reserve immutable cycles and return each shift's next append position."""

    locked = tuple(locked_cycles)
    available: dict[str, datetime] = {}
    sequence: dict[str, int] = {}
    shift_options = tuple(shifts)
    for shift in _unique_physical_shifts(shift_options):
        own = [cycle for cycle in locked if cycle.driver_shift_id == shift.id]
        option_starts = tuple(
            max(option.start_at, option.available_from or option.start_at)
            for option in shift_options if option.id == shift.id
        )
        available[shift.id] = max(
            (
                cycle.planned_finish
                + timedelta(
                    minutes=(
                        warehouses[
                            cycle.resource_option_id or cycle.driver_shift_id
                        ].turnaround_minutes
                        + route_buffer_minutes
                    )
                )
                for cycle in own
                if (cycle.resource_option_id or cycle.driver_shift_id) in warehouses
            ),
            default=min(option_starts),
        )
        sequence[shift.id] = max((cycle.sequence for cycle in own), default=0) + 1
        available[shift.id] = max(available[shift.id], min(option_starts))
    return available, sequence


def _resource_overlap(candidate: RouteCycle, cycles: Iterable[RouteCycle]) -> bool:
    """Reject driver or vehicle interval overlap against already accepted cycles."""

    for existing in cycles:
        same_resource = (
            existing.driver_id == candidate.driver_id or existing.vehicle_id == candidate.vehicle_id
        )
        overlaps = (
            candidate.planned_start < existing.planned_finish
            and existing.planned_start < candidate.planned_finish
        )
        if same_resource and overlaps:
            return True
    return False


def _cycle_explanation(
    deliveries: tuple[PlanningTask, ...],
    pickups: tuple[PlanningTask, ...],
    shift: DriverShift,
    detour_seconds: int,
    *,
    resource_reused: bool,
    activates_additional_resource: bool,
) -> tuple[str, ...]:
    """Build persisted Russian reasoning from decisions made by the heuristic."""

    reasons: list[str] = []
    if len(deliveries) == 2:
        reasons.append(
            f"Доставки {deliveries[0].request_id} и {deliveries[1].request_id} "
            "объединены: обе части по одной бытовке, дорожный маршрут и окна совместимы."
        )
    elif deliveries:
        reasons.append(
            f"Доставка {deliveries[0].request_id} назначена допустимым исходящим рейсом."
        )
    if pickups and deliveries:
        reasons.append(
            f"Вывозы {', '.join(task.request_id for task in pickups)} добавлены после "
            f"всех доставок; расчётный крюк {round(detour_seconds / 60)} мин "
            "не превышает настроенный лимит."
        )
        reasons.append(
            "Смешанный цикл убирает отдельный рейс за вывозом и дополнительный возврат на склад."
        )
    elif pickups:
        reasons.append("Создан отдельный pickup-only цикл с учётом пустого пробега.")
    if resource_reused:
        reasons.append(
            f"Водитель {shift.driver_name} продолжает свою смену: рейс помещается в "
            "оставшееся время, поэтому дополнительная машина не подключается."
        )
    elif activates_additional_resource:
        reasons.append(
            f"Дополнительная смена водителя {shift.driver_name} подключена только после "
            "учёта временных окон, вместимости, совместных точек и стоимости активации."
        )
    reasons.append(
        f"Водитель {shift.driver_name} свободен в рассчитанное время по точному "
        "дорожному расчёту."
    )
    return tuple(reasons)


def _stable_rank(seed: int, *parts: str) -> int:
    """Return a seed-dependent cryptographic tie-break rank."""

    material = "|".join((str(seed), *parts)).encode()
    return int.from_bytes(sha256(material).digest()[:8], "big")


def _missing_resource_reason(
    input_data: PlanningInput,
) -> tuple[UnassignedReasonCode, ...]:
    """Return global active-resource failures before per-task diagnosis."""

    active_shifts = [shift for shift in input_data.shifts if shift.active]
    if not active_shifts:
        return (UnassignedReasonCode.NO_ACTIVE_DRIVER,)
    active_vehicle_ids = {vehicle.id for vehicle in input_data.vehicles if vehicle.active}
    if not any(shift.vehicle_id in active_vehicle_ids for shift in active_shifts):
        return (UnassignedReasonCode.NO_ACTIVE_VEHICLE,)
    if not any(shift.start_at.date() == input_data.planning_date for shift in active_shifts):
        return (UnassignedReasonCode.NO_SHIFT_CAPACITY,)
    return ()


def _unassigned_task(
    task: PlanningTask | LogisticsRequest,
    reasons: tuple[UnassignedReasonCode, ...],
    *,
    nearest_possible_at: datetime | None = None,
) -> UnassignedTask:
    """Map stable reason codes to concise explanations and recommendations."""

    messages = {
        UnassignedReasonCode.NO_ACTIVE_DRIVER: (
            "Нет активного водителя или смены на выбранную дату."
        ),
        UnassignedReasonCode.NO_ACTIVE_VEHICLE: "Нет активной машины, доступной в смене.",
        UnassignedReasonCode.NO_SHIFT_CAPACITY: "В доступных сменах не осталось времени для рейса.",
        UnassignedReasonCode.TIME_WINDOW_CONFLICT: (
            "Временное окно не помещается ни в одну доступную смену."
        ),
        UnassignedReasonCode.SHIFT_LIMIT_EXCEEDED: (
            "Возврат на склад выходит за допустимый конец смены."
        ),
        UnassignedReasonCode.DETOUR_TOO_LARGE: "Крюк за точкой превышает допустимый лимит.",
        UnassignedReasonCode.OUTSIDE_ZONES: "Точка находится вне настроенных логистических зон.",
        UnassignedReasonCode.REQUEST_NOT_READY: "Заявка не находится в статусе READY.",
        UnassignedReasonCode.NO_ALLOWED_DATE: "Выбранная дата отсутствует среди допустимых.",
        UnassignedReasonCode.DUPLICATE_ASSIGNMENT_CONFLICT: (
            "Заявка повторяет уже учтённый внешний или сгенерированный источник."
        ),
        UnassignedReasonCode.NO_FEASIBLE_DELIVERY_PAIR: "Не найдена допустимая пара доставок.",
        UnassignedReasonCode.NO_FEASIBLE_PICKUP_PAIR: "Не найдена допустимая пара вывозов.",
        UnassignedReasonCode.TRAILER_ACCESS_NOT_ALLOWED: (
            "Логист не согласовал проезд автомобиля с прицепом к этой точке."
        ),
        UnassignedReasonCode.CARGO_TOO_HEAVY: "Груз превышает допустимую массу конфигурации.",
        UnassignedReasonCode.CARGO_TOO_LONG: "Груз превышает допустимую длину платформы.",
        UnassignedReasonCode.CARGO_TOO_WIDE: "Груз превышает допустимую ширину платформы.",
        UnassignedReasonCode.CARGO_TOO_HIGH: "Груз превышает допустимую высоту конфигурации.",
        UnassignedReasonCode.TRAILER_REQUIRED: "Для этой загрузки требуется прицеп.",
        UnassignedReasonCode.NO_COMPATIBLE_TRAILER: (
            "У машины нет совместимого прицепа для этой загрузки."
        ),
        UnassignedReasonCode.AXLE_LOAD_EXCEEDED: (
            "Настроенная фактическая нагрузка на ось превышает допустимую."
        ),
        UnassignedReasonCode.NO_SAFE_ROUTE: (
            "Для текущей конфигурации автомобиля безопасный грузовой маршрут не найден."
        ),
        UnassignedReasonCode.ROUTING_PROVIDER_UNAVAILABLE: (
            "Сервис безопасной грузовой маршрутизации временно недоступен."
        ),
        UnassignedReasonCode.ROUTING_PROFILE_INCOMPLETE: (
            "Для машины, прицепа или груза не заполнены обязательные параметры маршрутизации."
        ),
        UnassignedReasonCode.OPTIMIZATION_TIME_LIMIT: (
            "Проверка маршрутов не завершена за отведённое время."
        ),
        UnassignedReasonCode.UNKNOWN: "Не удалось построить допустимый рейс.",
    }
    recommendations: list[str] = []
    if UnassignedReasonCode.OPTIMIZATION_TIME_LIMIT in reasons:
        recommendations.append("Повторите расчёт или увеличьте лимит планирования.")
    if UnassignedReasonCode.TIME_WINDOW_CONFLICT in reasons:
        recommendations.append("Расширьте временное окно или добавьте более раннюю смену.")
    if any(
        reason in reasons
        for reason in (
            UnassignedReasonCode.NO_ACTIVE_DRIVER,
            UnassignedReasonCode.NO_ACTIVE_VEHICLE,
            UnassignedReasonCode.NO_SHIFT_CAPACITY,
        )
    ):
        recommendations.append("Добавьте смену или перенесите заявку на другую допустимую дату.")
    if UnassignedReasonCode.OUTSIDE_ZONES in reasons:
        recommendations.append("Добавьте зону или осознанно пересчитайте принадлежность точки.")
    if UnassignedReasonCode.DETOUR_TOO_LARGE in reasons:
        recommendations.append("Увеличьте допустимый крюк или оставьте отдельный pickup-only рейс.")
    if UnassignedReasonCode.ROUTING_PROFILE_INCOMPLETE in reasons:
        recommendations.append(
            "Заполните физические параметры машины, груза, прицепа и осевой нагрузки."
        )
    if any(
        reason in reasons
        for reason in (
            UnassignedReasonCode.TRAILER_REQUIRED,
            UnassignedReasonCode.NO_COMPATIBLE_TRAILER,
        )
    ):
        recommendations.append("Назначьте машине совместимый прицеп или разделите загрузку.")
    if UnassignedReasonCode.TRAILER_ACCESS_NOT_ALLOWED in reasons:
        recommendations.append(
            "Разделите загрузку на одиночные рейсы или отдельно согласуйте проезд с прицепом."
        )
    if UnassignedReasonCode.NO_SAFE_ROUTE in reasons:
        recommendations.append(
            "Измените конфигурацию автопоезда или проверьте дорожные ограничения."
        )
    return UnassignedTask(
        task=task,
        reason_codes=tuple(dict.fromkeys(reasons)) or (UnassignedReasonCode.UNKNOWN,),
        explanation_ru=tuple(messages[reason] for reason in dict.fromkeys(reasons))
        or (messages[UnassignedReasonCode.UNKNOWN],),
        nearest_possible_at=nearest_possible_at,
        recommendation_ru=tuple(recommendations),
    )


def calculate_resource_activation_cost(
    cycles: Iterable[RouteCycle],
    settings: PlanningSettings,
) -> float:
    """Return the fixed objective cost for resources beyond the first used shift."""

    active_shift_count = len({cycle.driver_shift_id for cycle in cycles})
    return max(0, active_shift_count - 1) * settings.additional_resource_activation_penalty


def _support_positioning_cost(
    shift: DriverShift,
    settings: PlanningSettings,
) -> float:
    """Score one external depot visit as high-cost empty road plus a soft link priority."""

    if shift.support_link_id is None:
        return 0.0
    return round(
        shift.positioning_travel_minutes * settings.empty_travel_weight
        + max(shift.support_priority - 1, 0),
        6,
    )


def _plan_support_positioning_cost(
    cycles: Iterable[RouteCycle],
    shifts: Iterable[DriverShift],
    settings: PlanningSettings,
) -> float:
    """Charge the full support positioning exactly once for every activated shift."""

    active_option_ids = {
        cycle.resource_option_id or cycle.driver_shift_id for cycle in cycles
    }
    return sum(
        _support_positioning_cost(shift, settings)
        for shift in shifts
        if _shift_option_id(shift) in active_option_ids
    )


def _plan_score(
    cycles: Iterable[RouteCycle],
    unassigned: Iterable[UnassignedTask],
    settings: PlanningSettings,
    shifts: Iterable[DriverShift],
) -> float:
    """Calculate the documented weighted objective without hiding hard failures."""

    cycle_list = tuple(cycles)
    shift_list = tuple(shifts)
    score = sum(cycle.score for cycle in cycle_list)
    score += calculate_resource_activation_cost(cycle_list, settings)
    score += _plan_support_positioning_cost(cycle_list, shift_list, settings)
    score += calculate_driver_workload_cost(
        cycle_list,
        _unique_physical_shifts(shift_list),
        settings,
    )
    for item in unassigned:
        task = item.task
        is_hard = isinstance(task, PlanningTask) and task.is_hard
        is_last = isinstance(task, PlanningTask) and task.is_last_available_date
        score += (
            settings.unassigned_hard_task_penalty if is_hard else settings.unassigned_task_penalty
        )
        if is_last:
            score += settings.last_available_date_penalty
    return round(score, 6)
