"""Transparent deterministic heuristic for two-cabin logistics cycles."""

# ruff: noqa: RUF001 -- Russian operator-facing explanations are intentional.

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, replace
from datetime import date, datetime, timedelta
from hashlib import sha256

from app.routing import GeoJsonLineString, RoutingProvider, TravelMatrix

from .engine import ProgressPublisher
from .models import (
    DriverShift,
    LogisticsRequest,
    PlannedLeg,
    PlanningInput,
    PlanningResult,
    PlanningSettings,
    PlanningTask,
    RelationType,
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
    ZoneRelation,
    ZoneSnapshot,
)
from .validation import calculate_plan_metrics, validate_route_plan
from .workload import (
    calculate_driver_workload_cost,
    incremental_shift_workload_cost,
    shift_utilization_percent,
)


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

    def __init__(self, routing_provider: RoutingProvider) -> None:
        self._routing = routing_provider

    async def generate_plan(
        self,
        input_data: PlanningInput,
        settings: PlanningSettings,
        progress: ProgressPublisher,
    ) -> PlanningResult:
        """Generate a valid best-known plan within deterministic search bounds."""

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
        remaining: dict[str, PlanningTask] = {
            task.id: task for task in tasks if task.id not in locked_task_ids
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
                key=lambda shift: (shift.start_at, shift.driver_id, shift.id),
            )
        )

        await trace.start(TracePhase.BUILDING_TRAVEL_MATRIX)
        ordered_tasks = tuple(sorted(tasks, key=lambda task: task.id))
        matrix_points = [input_data.warehouse.point] + [task.point for task in ordered_tasks]
        departure_at = min(
            (shift.start_at for shift in active_shifts),
            default=_fallback_departure(input_data.requests, input_data.planning_date),
        )
        matrix = await self._routing.get_matrix(matrix_points, departure_at)
        matrix_index = {task.id: index + 1 for index, task in enumerate(ordered_tasks)}
        await trace.complete(
            TracePhase.BUILDING_TRAVEL_MATRIX,
            point_count=len(matrix_points),
        )

        zone_by_id = {zone.id: zone for zone in input_data.zones}
        relation_index = _build_relation_index(input_data.zone_relations)
        cycles: list[RouteCycle] = list(input_data.locked_cycles)
        available_at, next_sequence = _initial_shift_state(
            active_shifts,
            input_data.locked_cycles,
            input_data.warehouse.turnaround_minutes + settings.default_route_buffer_minutes,
        )
        cycle_counts = {
            shift.id: sum(cycle.driver_shift_id == shift.id for cycle in input_data.locked_cycles)
            for shift in active_shifts
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

        # A deterministic evaluation budget is derived from the operator-facing
        # seconds setting. A wall-clock cutoff would make identical snapshots
        # produce different plans on faster and slower hosts. Deployment adapters
        # may still enforce a larger cancellation watchdog around this pure run.
        evaluation_budget = max(0, round(settings.max_optimization_seconds * 50_000))
        timed_out = evaluation_budget == 0
        evaluation_count = 0
        delivery_phase_complete = False
        while remaining and active_shifts and not timed_out:
            all_candidate_specs = _candidate_specs(
                tuple(remaining.values()),
                matrix,
                matrix_index,
                relation_index,
                settings,
            )
            delivery_phase = (
                any(task.task_type is TaskType.DELIVERY for task in remaining.values())
                and not delivery_phase_complete
            )
            if delivery_phase:
                candidate_specs = tuple(
                    spec for spec in all_candidate_specs if spec.deliveries
                )
            else:
                candidate_specs = tuple(
                    spec for spec in all_candidate_specs if not spec.deliveries
                )
            activated_shift_ids = frozenset(cycle.driver_shift_id for cycle in cycles)
            best_per_shift: list[_Candidate] = []
            for shift in active_shifts:
                candidates, evaluated, exhausted = self._candidates_for_shift(
                    shift=shift,
                    start_at=available_at[shift.id],
                    sequence=next_sequence[shift.id],
                    candidate_specs=candidate_specs,
                    warehouse=input_data.warehouse,
                    vehicle=active_vehicles[shift.vehicle_id],
                    matrix=matrix,
                    matrix_index=matrix_index,
                    zones=zone_by_id,
                    relations=relation_index,
                    settings=settings,
                    existing_cycles=tuple(cycles),
                    cycle_count=cycle_counts[shift.id],
                    activated_shift_ids=activated_shift_ids,
                    max_evaluations=evaluation_budget - evaluation_count,
                )
                evaluation_count += evaluated
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
                            },
                        )
                    best = min(candidates, key=lambda candidate: candidate.selection_key)
                    best_per_shift.append(best)
                    await trace.emit(
                        TracePhase.BUILDING_CYCLES,
                        TraceEventType.CANDIDATE_CYCLE_CREATED,
                        {
                            "driver_shift_id": shift.id,
                            "task_ids": sorted(best.task_ids),
                            "score": round(best.cycle.score, 6),
                        },
                    )
                else:
                    await trace.emit(
                        TracePhase.BUILDING_CYCLES,
                        TraceEventType.CANDIDATE_CYCLE_REJECTED,
                        {"driver_shift_id": shift.id},
                    )
                if exhausted or evaluation_count >= evaluation_budget:
                    timed_out = True
                    break
            if not best_per_shift:
                if delivery_phase:
                    delivery_phase_complete = True
                    continue
                break
            chosen = min(best_per_shift, key=lambda candidate: candidate.selection_key)
            cycles.append(chosen.cycle)
            for task_id in chosen.task_ids:
                remaining.pop(task_id, None)
            shift_id = chosen.cycle.driver_shift_id
            resource_decision = (
                "REUSED"
                if shift_id in activated_shift_ids
                else "ADDITIONAL"
                if activated_shift_ids
                else "FIRST"
            )
            turnaround = timedelta(
                minutes=input_data.warehouse.turnaround_minutes
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
        await trace.complete(
            TracePhase.BUILDING_CYCLES,
            cycle_count=len(cycles),
            evaluation_count=evaluation_count,
        )

        await trace.start(TracePhase.ASSIGNING_DRIVERS)
        await trace.complete(
            TracePhase.ASSIGNING_DRIVERS,
            assigned_count=len(tasks) - len(remaining),
        )

        await trace.start(TracePhase.LOCAL_SEARCH)
        cycle_ids_before_search = tuple(cycle.id for cycle in cycles)
        cycles, local_iterations = self._local_improve(
            cycles=cycles,
            task_by_id={task.id: task for task in tasks},
            shifts={shift.id: shift for shift in active_shifts},
            warehouse=input_data.warehouse,
            vehicles=active_vehicles,
            matrix=matrix,
            matrix_index=matrix_index,
            zones=zone_by_id,
            relations=relation_index,
            settings=settings,
        )
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
        final_unassigned = list(initial_unassigned)
        missing_resource_reason = _missing_resource_reason(input_data)
        for task in sorted(remaining.values(), key=_task_priority_key):
            if missing_resource_reason:
                reasons = missing_resource_reason
                nearest = None
            else:
                reasons, nearest = self._diagnose_task(
                    task=task,
                    shifts=active_shifts,
                    available_at=available_at,
                    warehouse=input_data.warehouse,
                    vehicles=active_vehicles,
                    matrix=matrix,
                    matrix_index=matrix_index,
                    zones=zone_by_id,
                    relations=relation_index,
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
            if shift.start_at.date() != shift.end_at.date():
                raise ValueError("MVP driver shifts must start and end on one local date")
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
        matrix: TravelMatrix,
        matrix_index: Mapping[str, int],
        zones: Mapping[str, ZoneSnapshot],
        relations: Mapping[tuple[str, str], ZoneRelation],
        settings: PlanningSettings,
        existing_cycles: tuple[RouteCycle, ...],
        cycle_count: int,
        activated_shift_ids: frozenset[str],
        max_evaluations: int,
    ) -> tuple[list[_Candidate], int, bool]:
        """Evaluate complete rank buckets until the first feasible bucket is found."""

        if start_at >= shift.end_at or not candidate_specs:
            return [], 0, False
        candidates: list[_Candidate] = []
        evaluated = 0
        current_key = candidate_specs[0].priority_key
        for spec in candidate_specs:
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
                zones=zones,
                relations=relations,
                settings=settings,
                cycle_count=cycle_count,
                resource_activation_penalty=(
                    settings.additional_resource_activation_penalty
                    if activates_additional_resource
                    else 0.0
                ),
                prefer_shift_reserve=not activated_shift_ids,
                activates_additional_resource=activates_additional_resource,
                existing_shift_cycles=existing_shift_cycles,
            )
            if candidate is not None and not _resource_overlap(candidate.cycle, existing_cycles):
                candidates.append(candidate)
        return candidates, evaluated, False

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
        matrix: TravelMatrix,
        matrix_index: Mapping[str, int],
        zones: Mapping[str, ZoneSnapshot],
        relations: Mapping[tuple[str, str], ZoneRelation],
        settings: PlanningSettings,
        cycle_count: int,
        resource_activation_penalty: float = 0.0,
        prefer_shift_reserve: bool = False,
        activates_additional_resource: bool = False,
        existing_shift_cycles: tuple[RouteCycle, ...] = (),
    ) -> _Candidate | None:
        """Schedule one ordered task sequence and reject every hard violation."""

        if not deliveries and not pickups:
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
        if any(task.quantity == 2 for task in deliveries) and len(deliveries) > 1:
            return None
        if any(task.quantity == 2 for task in pickups) and len(pickups) > 1:
            return None

        cursor = max(start_at, shift.start_at)
        load_seconds = (
            (warehouse.loading_minutes or settings.default_load_minutes) * 60 if deliveries else 0
        )
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
        relation_penalty = 0.0
        warnings: set[ValidationWarningCode] = set()
        task_sequence = deliveries + pickups
        pickup_started = False
        for task in task_sequence:
            if pickup_started and task.task_type is TaskType.DELIVERY:
                return None
            pickup_started = pickup_started or task.task_type is TaskType.PICKUP
            next_index = matrix_index[task.id]
            metric = matrix.at(current_index, next_index)
            arrival = cursor + timedelta(seconds=metric.travel_seconds)
            window = task.selected_option
            service_start = arrival
            if window.window_start is not None and service_start < window.window_start:
                waiting_seconds += round((window.window_start - service_start).total_seconds())
                service_start = window.window_start
            service_seconds = task.service_minutes * 60
            service_finish = service_start + timedelta(seconds=service_seconds)
            if window.window_end is not None and service_finish > window.window_end:
                violation = round((service_finish - window.window_end).total_seconds())
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
            zone = zones.get(task.zone_id or "")
            route_group = zone.route_group if zone is not None else None
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
                    departure_at=cursor,
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
                    zone_id=task.zone_id,
                    route_group=route_group,
                    window_start=window.window_start,
                    window_end=window.window_end,
                    window_is_hard=window.is_hard,
                )
            )
            if len(stops) > 2:
                previous_zone = stops[-2].zone_id
                if previous_zone and task.zone_id and previous_zone != task.zone_id:
                    relation = relations.get((previous_zone, task.zone_id))
                    if relation is not None:
                        if relation.relation_type is RelationType.BLOCKED:
                            return None
                        relation_penalty += relation.penalty
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
        if not _pickup_transition_allowed(
            deliveries,
            pickups,
            relations,
        ):
            return None
        high_detour = _pickup_detour_exceeds_recommended_limit(
            deliveries,
            pickups,
            detour_seconds,
            detour_ratio,
            relations,
            settings,
        )
        if high_detour:
            warnings.add(ValidationWarningCode.HIGH_DETOUR)
        route_groups = tuple(
            zone.route_group
            for task in task_sequence
            if (zone := zones.get(task.zone_id or "")) is not None
        )
        cross_group_count = max(0, len(set(route_groups)) - 1)
        if cross_group_count:
            warnings.add(ValidationWarningCode.CROSS_ROUTE_GROUP)
        preferred_match = bool(
            shift.preferred_route_group and shift.preferred_route_group in route_groups
        )
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
            + cross_group_count * settings.cross_group_penalty
            + relation_penalty
            - (settings.paired_delivery_bonus if len(deliveries) == 2 else 0.0)
            - (settings.paired_pickup_bonus if len(pickups) == 2 else 0.0)
            - (settings.driver_preference_bonus if preferred_match else 0.0)
        )
        all_tasks = deliveries + pickups
        task_ids = tuple(task.id for task in all_tasks)
        cycle_id = f"cycle:{shift.id}:{sequence}:{'|'.join(task_ids)}"
        explanation = _cycle_explanation(
            deliveries,
            pickups,
            shift,
            detour_seconds,
            preferred_match,
            high_detour=high_detour,
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
            matrix,
            matrix_index,
        )
        stable_rank = _stable_rank(settings.seed, shift.id, *task_ids)
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
                -remaining_shift_seconds if prefer_shift_reserve else 0,
                cycle.score + resource_activation_penalty + workload_penalty,
                cycle.planned_finish,
                cycle_count,
                stable_rank,
                shift.id,
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
        warehouse: Warehouse,
        vehicles: Mapping[str, Vehicle],
        matrix: TravelMatrix,
        matrix_index: Mapping[str, int],
        zones: Mapping[str, ZoneSnapshot],
        relations: Mapping[tuple[str, str], ZoneRelation],
        settings: PlanningSettings,
        cycles: tuple[RouteCycle, ...],
    ) -> tuple[tuple[UnassignedReasonCode, ...], datetime | None]:
        """Determine concrete singleton feasibility reasons for one leftover task."""

        candidates: list[_Candidate] = []
        nearest: datetime | None = None
        for shift in shifts:
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
                zones=zones,
                relations=relations,
                settings=settings,
                cycle_count=0,
            )
            if candidate is not None and not _resource_overlap(candidate.cycle, cycles):
                candidates.append(candidate)
        if candidates:
            return (UnassignedReasonCode.NO_SHIFT_CAPACITY,), nearest
        attachment_reasons = _pickup_attachment_failures(
            task,
            cycles,
            relations,
        )
        option = task.selected_option
        if option.window_end is not None and nearest is not None:
            if nearest + timedelta(minutes=task.service_minutes) > option.window_end:
                return (
                    UnassignedReasonCode.TIME_WINDOW_CONFLICT,
                    *attachment_reasons,
                ), nearest
        return (UnassignedReasonCode.SHIFT_LIMIT_EXCEEDED, *attachment_reasons), nearest

    def _local_improve(
        self,
        *,
        cycles: list[RouteCycle],
        task_by_id: Mapping[str, PlanningTask],
        shifts: Mapping[str, DriverShift],
        warehouse: Warehouse,
        vehicles: Mapping[str, Vehicle],
        matrix: TravelMatrix,
        matrix_index: Mapping[str, int],
        zones: Mapping[str, ZoneSnapshot],
        relations: Mapping[tuple[str, str], ZoneRelation],
        settings: PlanningSettings,
    ) -> tuple[list[RouteCycle], int]:
        """Try bounded delivery/pickup reversals and retain strict improvements."""

        result = list(cycles)
        iterations = 0
        for index, cycle in enumerate(tuple(result)):
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
                if iterations >= settings.max_local_search_iterations:
                    break
                iterations += 1
                shift = shifts.get(cycle.driver_shift_id)
                vehicle = vehicles.get(cycle.vehicle_id)
                if shift is None or vehicle is None:
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
                    zones=zones,
                    relations=relations,
                    settings=settings,
                    cycle_count=max(0, cycle.sequence - 1),
                    existing_shift_cycles=tuple(
                        item
                        for item in result
                        if item.id != cycle.id
                        and item.driver_shift_id == cycle.driver_shift_id
                    ),
                )
                other_cycles = tuple(item for item in result if item.id != cycle.id)
                candidate_cycles = [
                    candidate.cycle if item.id == cycle.id else item
                    for item in result
                ] if candidate is not None else []
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


def split_request(
    request: LogisticsRequest,
    selected_option: RequestDateOption,
    *,
    remaining_date_count: int,
    is_last_available_date: bool,
) -> tuple[PlanningTask, ...]:
    """Split a request into stable two-cabin parts, for example ``5 -> 2,2,1``."""

    if request.quantity > 2 and not request.split_allowed:
        raise ValueError("request quantity exceeds capacity and splitting is disabled")
    quantities: list[int] = []
    left = request.quantity
    while left:
        quantity = min(2, left)
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
            zone_id=request.zone_id,
            zone_version=request.zone_version,
            service_minutes=request.service_minutes,
            priority=request.priority,
            status=request.status,
            created_at=request.created_at,
            selected_option=selected_option,
            remaining_date_count=remaining_date_count,
            is_last_available_date=is_last_available_date,
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
    for request in sorted(requests, key=lambda item: (item.created_at, item.id)):
        if request.status is not RequestStatus.READY:
            unassigned.append(_unassigned_task(request, (UnassignedReasonCode.REQUEST_NOT_READY,)))
            continue
        if request.zone_id is None:
            unassigned.append(_unassigned_task(request, (UnassignedReasonCode.OUTSIDE_ZONES,)))
            continue
        matching = [option for option in request.date_options if option.date == planning_date]
        if not matching:
            unassigned.append(_unassigned_task(request, (UnassignedReasonCode.NO_ALLOWED_DATE,)))
            continue
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
    matrix: TravelMatrix,
    matrix_index: Mapping[str, int],
    relations: Mapping[tuple[str, str], ZoneRelation],
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
        relations,
        for_delivery=True,
        max_neighbors=settings.max_candidate_neighbors,
        anchor_limit=anchor_limit,
    )
    combinations: list[tuple[tuple[PlanningTask, ...], tuple[PlanningTask, ...]]] = []
    for delivery_group in delivery_groups:
        last_point_index = matrix_index[delivery_group[-1].id]
        eligible_pickups = sorted(
            pickups,
            key=lambda task: (
                matrix.at(last_point_index, matrix_index[task.id]).travel_seconds,
                _task_priority_key(task),
            ),
        )[: settings.max_candidate_neighbors]
        pickup_groups: list[tuple[PlanningTask, ...]] = [()]
        pickup_groups.extend(
            _ordered_groups(
                eligible_pickups,
                matrix,
                matrix_index,
                relations,
                for_delivery=False,
                max_neighbors=settings.max_candidate_neighbors,
                anchor_limit=settings.max_candidate_neighbors,
            )
        )
        combinations.extend(
            (delivery_group, pickup_group)
            for pickup_group in pickup_groups
            if _pickup_transition_allowed(
                delivery_group,
                pickup_group,
                relations,
            )
        )

    pickup_only_groups = _ordered_groups(
        pickups,
        matrix,
        matrix_index,
        relations,
        for_delivery=False,
        max_neighbors=settings.max_candidate_neighbors,
        anchor_limit=anchor_limit,
    )
    combinations.extend(((), pickup_group) for pickup_group in pickup_only_groups)
    specs = (
        _CandidateSpec(
            deliveries=delivery_group,
            pickups=pickup_group,
            priority_key=_candidate_priority_key(
                delivery_group,
                pickup_group,
                matrix,
                matrix_index,
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
    matrix: TravelMatrix,
    matrix_index: Mapping[str, int],
) -> tuple[object, ...]:
    """Return the shift-independent prefix of a candidate's final selection key.

    A cycle that can bring cabins back after its deliveries is preferred over a
    separate pickup-only cycle. Within the same urgency bucket, full outbound
    and return loads rank ahead of a partially loaded mixed cycle. That
    lexicographic packing rule minimizes depot returns before weighted travel
    and detour cost, while hard-task urgency still remains the first rule.
    """

    all_tasks = deliveries + pickups
    return (
        -sum(task.is_hard for task in all_tasks),
        -int(any(task.is_last_available_date for task in all_tasks)),
        -int(bool(deliveries and pickups)),
        -sum(task.quantity for task in deliveries),
        -sum(task.quantity for task in pickups),
        -len(all_tasks),
        -sum(task.priority for task in all_tasks),
        sum(task.remaining_date_count for task in all_tasks),
        sum(task.selected_option.width.total_seconds() for task in all_tasks),
        -max(matrix.at(0, matrix_index[task.id]).distance_meters for task in all_tasks),
    )


def _ordered_groups(
    tasks: Sequence[PlanningTask],
    matrix: TravelMatrix,
    matrix_index: Mapping[str, int],
    relations: Mapping[tuple[str, str], ZoneRelation],
    *,
    for_delivery: bool,
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
                and _pair_zones_allowed(first, second, relations, for_delivery)
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


def _pair_zones_allowed(
    first: PlanningTask,
    second: PlanningTask,
    relations: Mapping[tuple[str, str], ZoneRelation],
    for_delivery: bool,
) -> bool:
    """Use directed zone policy as a cheap prefilter before route timing."""

    if first.zone_id == second.zone_id:
        return True
    if first.zone_id is None or second.zone_id is None:
        return False
    relation = relations.get((first.zone_id, second.zone_id))
    if relation is None or relation.relation_type is RelationType.BLOCKED:
        return False
    return relation.delivery_pair_allowed if for_delivery else relation.pickup_allowed


def _build_relation_index(
    relations: Iterable[ZoneRelation],
) -> dict[tuple[str, str], ZoneRelation]:
    """Expand explicitly bidirectional relations without overriding directed ones."""

    result: dict[tuple[str, str], ZoneRelation] = {}
    ordered = sorted(relations, key=lambda item: (item.from_zone_id, item.to_zone_id))
    for relation in ordered:
        result[(relation.from_zone_id, relation.to_zone_id)] = relation
    for relation in ordered:
        reverse_key = (relation.to_zone_id, relation.from_zone_id)
        if relation.is_bidirectional and reverse_key not in result:
            result[reverse_key] = replace(
                relation,
                from_zone_id=relation.to_zone_id,
                to_zone_id=relation.from_zone_id,
            )
    return result


def _pickup_detour(
    deliveries: tuple[PlanningTask, ...],
    pickups: tuple[PlanningTask, ...],
    matrix: TravelMatrix,
    matrix_index: Mapping[str, int],
) -> tuple[int, float]:
    """Return actual extra return duration and ratio caused by pickup stops."""

    if not deliveries or not pickups:
        return 0, 0.0
    last_delivery_index = matrix_index[deliveries[-1].id]
    direct = matrix.at(last_delivery_index, 0).travel_seconds
    with_pickup = 0
    current = last_delivery_index
    for pickup in pickups:
        next_index = matrix_index[pickup.id]
        with_pickup += matrix.at(current, next_index).travel_seconds
        with_pickup += pickup.service_minutes * 60
        current = next_index
    with_pickup += matrix.at(current, 0).travel_seconds
    detour = max(0, with_pickup - direct)
    return detour, detour / max(1, direct)


def _pickup_transition_allowed(
    deliveries: tuple[PlanningTask, ...],
    pickups: tuple[PlanningTask, ...],
    relations: Mapping[tuple[str, str], ZoneRelation],
) -> bool:
    """Apply only hard directed-zone policy to a delivery-to-pickup transition."""

    if not deliveries or not pickups:
        return True
    transition_relation = relations.get(
        (deliveries[-1].zone_id or "", pickups[0].zone_id or "")
    )
    return not (
        transition_relation is not None
        and (
            transition_relation.relation_type is RelationType.BLOCKED
            or not transition_relation.pickup_allowed
        )
    )


def _pickup_detour_exceeds_recommended_limit(
    deliveries: tuple[PlanningTask, ...],
    pickups: tuple[PlanningTask, ...],
    detour_seconds: int,
    detour_ratio: float,
    relations: Mapping[tuple[str, str], ZoneRelation],
    settings: PlanningSettings,
) -> bool:
    """Return whether a cycle-saving backhaul exceeds its warning thresholds."""

    if not deliveries or not pickups:
        return False
    minute_limit = settings.max_detour_minutes
    ratio_limit = settings.max_detour_ratio
    transition_relation = relations.get(
        (deliveries[-1].zone_id or "", pickups[0].zone_id or "")
    )
    if transition_relation is not None:
        if transition_relation.max_detour_minutes is not None:
            minute_limit = min(minute_limit, transition_relation.max_detour_minutes)
        if transition_relation.max_detour_ratio is not None:
            ratio_limit = min(ratio_limit, transition_relation.max_detour_ratio)
    return detour_seconds > minute_limit * 60 or detour_ratio > ratio_limit


def _task_priority_key(task: PlanningTask) -> tuple[object, ...]:
    """Implement mandatory-date-first stable task ordering."""

    return (
        not task.is_hard,
        not task.is_last_available_date,
        -task.priority,
        task.remaining_date_count,
        task.selected_option.width,
        task.created_at,
        task.id,
    )


def _task_priority_with_distance(
    task: PlanningTask,
    matrix: TravelMatrix,
    matrix_index: Mapping[str, int],
) -> tuple[object, ...]:
    """Extend business priority with the documented farther-first criterion."""

    return (
        not task.is_hard,
        not task.is_last_available_date,
        -task.priority,
        task.remaining_date_count,
        task.selected_option.width,
        -matrix.at(0, matrix_index[task.id]).distance_meters,
        task.created_at,
        task.id,
    )


def _pickup_attachment_failures(
    task: PlanningTask,
    cycles: Iterable[RouteCycle],
    relations: Mapping[tuple[str, str], ZoneRelation],
) -> tuple[UnassignedReasonCode, ...]:
    """Explain why a leftover pickup could not join a completed delivery return."""

    if task.task_type is not TaskType.PICKUP:
        return ()
    considered = 0
    blocked = 0
    for cycle in cycles:
        deliveries = [stop for stop in cycle.stops if stop.stop_type is StopType.DELIVERY]
        pickups = [stop for stop in cycle.stops if stop.stop_type is StopType.PICKUP]
        if not deliveries or pickups:
            continue
        last_delivery = deliveries[-1]
        considered += 1
        relation = relations.get((last_delivery.zone_id or "", task.zone_id or ""))
        if relation is not None and (
            relation.relation_type is RelationType.BLOCKED or not relation.pickup_allowed
        ):
            blocked += 1
            continue
        return ()
    if considered and blocked == considered:
        return (UnassignedReasonCode.ZONE_RELATION_BLOCKED,)
    return ()


def _initial_shift_state(
    shifts: Iterable[DriverShift],
    locked_cycles: Iterable[RouteCycle],
    turnaround_minutes: int,
) -> tuple[dict[str, datetime], dict[str, int]]:
    """Reserve immutable cycles and return each shift's next append position."""

    locked = tuple(locked_cycles)
    available: dict[str, datetime] = {}
    sequence: dict[str, int] = {}
    for shift in shifts:
        own = [cycle for cycle in locked if cycle.driver_shift_id == shift.id]
        available[shift.id] = max(
            (cycle.planned_finish + timedelta(minutes=turnaround_minutes) for cycle in own),
            default=shift.start_at,
        )
        sequence[shift.id] = max((cycle.sequence for cycle in own), default=0) + 1
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
    preferred_match: bool,
    *,
    high_detour: bool,
    resource_reused: bool,
    activates_additional_resource: bool,
) -> tuple[str, ...]:
    """Build persisted Russian reasoning from decisions made by the heuristic."""

    reasons: list[str] = []
    if len(deliveries) == 2:
        reasons.append(
            f"Доставки {deliveries[0].request_id} и {deliveries[1].request_id} "
            "объединены: обе части по одной бытовке, зоны и окна совместимы."
        )
    elif deliveries:
        reasons.append(
            f"Доставка {deliveries[0].request_id} назначена допустимым исходящим рейсом."
        )
    if pickups and deliveries:
        reasons.append(
            f"Вывозы {', '.join(task.request_id for task in pickups)} добавлены после "
            f"всех доставок; расчётный крюк {round(detour_seconds / 60)} мин."
        )
        reasons.append(
            "Смешанный цикл убирает отдельный рейс за вывозом и дополнительный "
            "возврат на склад."
        )
        if high_detour:
            reasons.append(
                "Крюк выше рекомендуемого порога, но сохранён ради меньшего "
                "числа циклов; вместимость, окна и смена не нарушены."
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
    if preferred_match:
        reasons.append(
            f"Водитель {shift.driver_name} выбран с учётом предпочтительной группы "
            f"{shift.preferred_route_group}."
        )
    else:
        reasons.append(
            f"Водитель {shift.driver_name} свободен в рассчитанное время; предпочтение "
            "группы использовано как мягкий фактор."
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
        UnassignedReasonCode.ZONE_RELATION_BLOCKED: "Переход между зонами запрещён настройками.",
        UnassignedReasonCode.DETOUR_TOO_LARGE: "Крюк за точкой превышает допустимый лимит.",
        UnassignedReasonCode.OUTSIDE_ZONES: "Точка находится вне настроенных логистических зон.",
        UnassignedReasonCode.REQUEST_NOT_READY: "Заявка не находится в статусе READY.",
        UnassignedReasonCode.NO_ALLOWED_DATE: "Выбранная дата отсутствует среди допустимых.",
        UnassignedReasonCode.DUPLICATE_ASSIGNMENT_CONFLICT: "Транспортная часть уже назначена.",
        UnassignedReasonCode.NO_FEASIBLE_DELIVERY_PAIR: "Не найдена допустимая пара доставок.",
        UnassignedReasonCode.NO_FEASIBLE_PICKUP_PAIR: "Не найдена допустимая пара вывозов.",
        UnassignedReasonCode.UNKNOWN: "Не удалось построить допустимый рейс.",
    }
    recommendations: list[str] = []
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
    if UnassignedReasonCode.ZONE_RELATION_BLOCKED in reasons:
        recommendations.append("Проверьте направленную связь между логистическими зонами.")
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


def _plan_score(
    cycles: Iterable[RouteCycle],
    unassigned: Iterable[UnassignedTask],
    settings: PlanningSettings,
    shifts: Iterable[DriverShift],
) -> float:
    """Calculate the documented weighted objective without hiding hard failures."""

    cycle_list = tuple(cycles)
    score = sum(cycle.score for cycle in cycle_list)
    score += calculate_resource_activation_cost(cycle_list, settings)
    score += calculate_driver_workload_cost(cycle_list, shifts, settings)
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


def _fallback_departure(
    requests: Iterable[LogisticsRequest],
    planning_date: date,
) -> datetime | None:
    """Create an aware deterministic matrix timestamp when no active shift exists."""

    request_list = tuple(requests)
    if not request_list:
        return None
    timezone = request_list[0].created_at.tzinfo
    return datetime.combine(planning_date, datetime.min.time(), tzinfo=timezone)
