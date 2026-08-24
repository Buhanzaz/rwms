"""Pure delay propagation, event derivation, and route interpolation."""

# ruff: noqa: RUF001 -- Russian operator-facing event text is intentional.

from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable, Sequence
from dataclasses import replace
from datetime import datetime, timedelta
from itertools import pairwise

from app.planner.models import PlannedLeg, RouteCycle, RouteStop, StopType
from app.routing import GeoJsonLineString, GeoPoint, haversine_distance_meters
from app.routing.models import require_aware

from .models import (
    DelayOverride,
    DriverUnavailableOverride,
    SimulationEvent,
    SimulationOverride,
    SimulationSnapshot,
    SimulationStatus,
    VehicleSimulationState,
)


def propagate_delays(
    cycles: Iterable[RouteCycle],
    delays: Iterable[DelayOverride],
) -> tuple[RouteCycle, ...]:
    """Return shifted copies while preserving every source cycle unchanged."""

    result = tuple(cycles)
    ordered_delays = sorted(
        delays,
        key=lambda item: (
            _resolve_delay_cutoff(item, result),
            item.driver_shift_id,
            item.id,
        ),
    )
    for delay in ordered_delays:
        cutoff = _resolve_delay_cutoff(delay, result)
        result = tuple(
            _shift_cycle(cycle, cutoff, delay.delay)
            if cycle.driver_shift_id == delay.driver_shift_id and cycle.planned_finish >= cutoff
            else cycle
            for cycle in result
        )
    return result


def apply_simulation_overrides(
    cycles: Iterable[RouteCycle],
    overrides: Iterable[SimulationOverride],
) -> tuple[RouteCycle, ...]:
    """Apply schedule-changing overrides without persisting a new route plan."""

    return propagate_delays(
        cycles,
        (override for override in overrides if isinstance(override, DelayOverride)),
    )


def derive_simulation_state(
    cycles: Iterable[RouteCycle],
    simulated_timestamp: datetime,
    simulation_overrides: Iterable[SimulationOverride] = (),
) -> SimulationSnapshot:
    """Derive identical vehicle state for identical plan, timestamp, and overrides."""

    require_aware(simulated_timestamp, "simulated_timestamp")
    source_cycles = tuple(cycles)
    overrides = tuple(simulation_overrides)
    effective_cycles = apply_simulation_overrides(source_cycles, overrides)
    unavailable_by_shift = _unavailable_overrides(overrides)
    delays = tuple(item for item in overrides if isinstance(item, DelayOverride))
    grouped: dict[str, list[RouteCycle]] = defaultdict(list)
    for cycle in effective_cycles:
        grouped[cycle.driver_shift_id].append(cycle)
    vehicles: list[VehicleSimulationState] = []
    for shift_id, shift_cycles in sorted(grouped.items()):
        ordered = tuple(
            sorted(shift_cycles, key=lambda cycle: (cycle.planned_start, cycle.sequence, cycle.id))
        )
        unavailable = unavailable_by_shift.get(shift_id)
        state = _derive_vehicle_state(ordered, simulated_timestamp)
        applied_delays = [
            delay
            for delay in delays
            if delay.driver_shift_id == shift_id
            and simulated_timestamp >= _resolve_delay_cutoff(delay, source_cycles)
        ]
        total_delay = sum((delay.delay for delay in applied_delays), timedelta())
        schedule_offset = round(total_delay.total_seconds())
        if unavailable is not None and simulated_timestamp >= unavailable.effective_at:
            frozen_state = _derive_vehicle_state(ordered, unavailable.effective_at)
            state = replace(
                frozen_state,
                status=SimulationStatus.UNAVAILABLE,
                unavailable=True,
                next_address=None,
                eta=None,
            )
        elif applied_delays and state.status not in {
            SimulationStatus.WAITING_SHIFT,
            SimulationStatus.FINISHED,
        }:
            state = replace(state, status=SimulationStatus.DELAYED, delayed=True)
        state = replace(
            state,
            delayed=bool(applied_delays),
            schedule_offset_seconds=schedule_offset,
        )
        vehicles.append(state)

    all_events = simulation_events(
        effective_cycles,
        overrides,
        override_reference_cycles=source_cycles,
    )
    elapsed = tuple(event for event in all_events if event.event_at <= simulated_timestamp)
    previous = elapsed[-1].event_at if elapsed else None
    next_event = next(
        (event.event_at for event in all_events if event.event_at > simulated_timestamp),
        None,
    )
    return SimulationSnapshot(
        timestamp=simulated_timestamp,
        vehicles=tuple(vehicles),
        elapsed_events=elapsed,
        previous_event_at=previous,
        next_event_at=next_event,
    )


def simulation_events(
    cycles: Iterable[RouteCycle],
    overrides: Iterable[SimulationOverride] = (),
    *,
    override_reference_cycles: Iterable[RouteCycle] | None = None,
) -> tuple[SimulationEvent, ...]:
    """Build the complete stable event journal used by timeline navigation."""

    cycle_list = tuple(cycles)
    reference_cycles = tuple(override_reference_cycles or cycle_list)
    events: list[SimulationEvent] = []
    for cycle in sorted(cycle_list, key=lambda item: (item.planned_start, item.id)):
        for stop in cycle.stops:
            if stop.stop_type is StopType.DEPOT_LOAD:
                started = "начал загрузку"
                finished = "выехал со склада"
            elif stop.stop_type is StopType.DELIVERY:
                started = f"прибыл на доставку {stop.request_id or stop.task_id}"
                finished = f"закончил доставку {stop.request_id or stop.task_id}"
            elif stop.stop_type is StopType.PICKUP:
                started = f"прибыл на вывоз {stop.request_id or stop.task_id}"
                finished = f"закончил вывоз {stop.request_id or stop.task_id}"
            else:
                started = "вернулся на склад"
                finished = "закончил выгрузку"
            events.append(
                SimulationEvent(
                    event_at=stop.planned_arrival,
                    driver_shift_id=cycle.driver_shift_id,
                    cycle_id=cycle.id,
                    event_type=f"{stop.stop_type.value.lower()}_started",
                    message_ru=started,
                    task_id=stop.task_id,
                )
            )
            if stop.planned_departure != stop.planned_arrival:
                events.append(
                    SimulationEvent(
                        event_at=stop.planned_departure,
                        driver_shift_id=cycle.driver_shift_id,
                        cycle_id=cycle.id,
                        event_type=f"{stop.stop_type.value.lower()}_completed",
                        message_ru=finished,
                        task_id=stop.task_id,
                    )
                )
    for override in overrides:
        if isinstance(override, DelayOverride):
            events.append(
                SimulationEvent(
                    event_at=_resolve_delay_cutoff(override, reference_cycles),
                    driver_shift_id=override.driver_shift_id,
                    cycle_id=override.cycle_id,
                    event_type="delay_added",
                    message_ru=(
                        f"Добавлена задержка {round(override.delay.total_seconds() / 60)} мин."
                    ),
                )
            )
        elif isinstance(override, DriverUnavailableOverride):
            events.append(
                SimulationEvent(
                    event_at=override.effective_at,
                    driver_shift_id=override.driver_shift_id,
                    cycle_id=None,
                    event_type="driver_unavailable",
                    message_ru="Водитель стал недоступен.",
                )
            )
    return tuple(
        sorted(
            events,
            key=lambda event: (
                event.event_at,
                event.driver_shift_id,
                event.cycle_id or "",
                event.event_type,
                event.task_id or "",
            ),
        )
    )


def interpolate_route_position(
    geometry: GeoJsonLineString,
    progress: float,
) -> GeoPoint:
    """Interpolate a LineString by segment distance rather than vertex index."""

    points = _geometry_points(geometry)
    if not points:
        raise ValueError("LineString geometry must contain coordinates")
    if len(points) == 1:
        return points[0]
    clamped = min(1.0, max(0.0, progress))
    distances = [haversine_distance_meters(first, second) for first, second in pairwise(points)]
    total = sum(distances)
    if total == 0:
        return points[-1]
    target = clamped * total
    traversed = 0.0
    for (first, second), distance in zip(pairwise(points), distances, strict=True):
        if traversed + distance >= target:
            local = (target - traversed) / distance if distance else 1.0
            return GeoPoint(
                lon=first.lon + (second.lon - first.lon) * local,
                lat=first.lat + (second.lat - first.lat) * local,
                is_city=first.is_city or second.is_city,
            )
        traversed += distance
    return points[-1]


def _derive_vehicle_state(
    cycles: tuple[RouteCycle, ...],
    timestamp: datetime,
) -> VehicleSimulationState:
    """Derive one vehicle state without relying on previous simulation ticks."""

    first = cycles[0]
    completed_task_ids = tuple(
        stop.task_id
        for cycle in cycles
        for stop in cycle.stops
        if stop.task_id is not None and stop.planned_departure <= timestamp
    )
    if timestamp < first.planned_start:
        return _base_state(
            first,
            first.stops[0].point,
            SimulationStatus.WAITING_SHIFT,
            0,
            first.stops[1].address_label if len(first.stops) > 1 else None,
            first.planned_start,
            None,
            None,
            completed_task_ids,
        )

    for cycle_index, cycle in enumerate(cycles):
        if timestamp < cycle.planned_start:
            previous = cycles[cycle_index - 1]
            return _base_state(
                previous,
                previous.stops[-1].point,
                SimulationStatus.BREAK,
                0,
                cycle.stops[1].address_label if len(cycle.stops) > 1 else None,
                cycle.planned_start,
                None,
                None,
                completed_task_ids,
            )
        if timestamp < cycle.planned_finish:
            for stop in cycle.stops:
                if stop.planned_arrival <= timestamp < stop.planned_departure:
                    return _state_at_stop(cycle, stop, timestamp, completed_task_ids)
            for leg_index, leg in enumerate(cycle.legs):
                if leg.departure_at <= timestamp < leg.arrival_at:
                    return _state_on_leg(
                        cycle,
                        leg,
                        leg_index,
                        timestamp,
                        completed_task_ids,
                    )
    last = cycles[-1]
    return _base_state(
        last,
        last.stops[-1].point,
        SimulationStatus.FINISHED,
        0,
        None,
        None,
        None,
        None,
        completed_task_ids,
    )


def _state_at_stop(
    cycle: RouteCycle,
    stop: RouteStop,
    timestamp: datetime,
    completed_task_ids: tuple[str, ...],
) -> VehicleSimulationState:
    """Derive a stationary service state at one stop."""

    del timestamp
    status = {
        StopType.DEPOT_LOAD: SimulationStatus.LOADING,
        StopType.DELIVERY: SimulationStatus.DELIVERING,
        StopType.PICKUP: SimulationStatus.PICKING_UP,
        StopType.DEPOT_UNLOAD: SimulationStatus.UNLOADING,
        StopType.DEPOT_RETURN: SimulationStatus.UNLOADING,
    }[stop.stop_type]
    next_stop = next(
        (candidate for candidate in cycle.stops if candidate.sequence > stop.sequence),
        None,
    )
    return _base_state(
        cycle,
        stop.point,
        status,
        stop.load_before,
        next_stop.address_label if next_stop is not None else None,
        next_stop.planned_arrival if next_stop is not None else None,
        cycle.id,
        None,
        completed_task_ids,
        geometry_parts=_geometry_parts(cycle, stop.sequence, None),
    )


def _state_on_leg(
    cycle: RouteCycle,
    leg: PlannedLeg,
    leg_index: int,
    timestamp: datetime,
    completed_task_ids: tuple[str, ...],
) -> VehicleSimulationState:
    """Derive moving position, load, ETA, and route-part geometries."""

    duration = max(1.0, (leg.arrival_at - leg.departure_at).total_seconds())
    progress = (timestamp - leg.departure_at).total_seconds() / duration
    position = interpolate_route_position(leg.geometry, progress)
    from_stop = cycle.stops[leg.from_stop_sequence]
    to_stop = cycle.stops[leg.to_stop_sequence]
    status = (
        SimulationStatus.RETURNING
        if to_stop.stop_type in {StopType.DEPOT_RETURN, StopType.DEPOT_UNLOAD}
        else SimulationStatus.DRIVING
    )
    traversed, active, remaining = _geometry_parts(cycle, leg_index, progress)
    return _base_state(
        cycle,
        position,
        status,
        from_stop.load_after,
        to_stop.address_label,
        leg.arrival_at,
        cycle.id,
        leg_index,
        completed_task_ids,
        geometry_parts=(traversed, active, remaining),
    )


def _base_state(
    cycle: RouteCycle,
    position: GeoPoint,
    status: SimulationStatus,
    load: int,
    next_address: str | None,
    eta: datetime | None,
    active_cycle_id: str | None,
    active_leg_index: int | None,
    completed_task_ids: tuple[str, ...],
    geometry_parts: tuple[GeoJsonLineString, GeoJsonLineString, GeoJsonLineString] | None = None,
) -> VehicleSimulationState:
    """Construct a vehicle state with consistent empty route geometries."""

    empty = _line_string(())
    traversed, active, remaining = geometry_parts or (empty, empty, empty)
    return VehicleSimulationState(
        driver_shift_id=cycle.driver_shift_id,
        driver_id=cycle.driver_id,
        vehicle_id=cycle.vehicle_id,
        position=position,
        status=status,
        current_load=load,
        next_address=next_address,
        eta=eta,
        active_cycle_id=active_cycle_id,
        active_leg_index=active_leg_index,
        completed_task_ids=completed_task_ids,
        traversed_geometry=traversed,
        active_geometry=active,
        remaining_geometry=remaining,
    )


def _geometry_parts(
    cycle: RouteCycle,
    active_leg_index: int,
    progress: float | None,
) -> tuple[GeoJsonLineString, GeoJsonLineString, GeoJsonLineString]:
    """Split cycle geometry into traversed, active, and remaining LineStrings."""

    leg_points = [_geometry_points(leg.geometry) for leg in cycle.legs]
    traversed_points: list[GeoPoint] = []
    remaining_points: list[GeoPoint] = []
    active_points: list[GeoPoint] = []
    for index, points in enumerate(leg_points):
        if index < active_leg_index:
            _extend_unique(traversed_points, points)
        elif index > active_leg_index:
            _extend_unique(remaining_points, points)
        elif progress is None:
            _extend_unique(remaining_points, points)
        else:
            completed_part, future_part = _split_points_by_distance(points, progress)
            _extend_unique(traversed_points, completed_part)
            _extend_unique(active_points, future_part)
            _extend_unique(remaining_points, future_part)
    return (
        _line_string(traversed_points),
        _line_string(active_points),
        _line_string(remaining_points),
    )


def _shift_cycle(cycle: RouteCycle, cutoff: datetime, delay: timedelta) -> RouteCycle:
    """Shift all affected times in a cycle while retaining identifiers and geometry."""

    shifted_stops = tuple(_shift_stop(stop, cutoff, delay) for stop in cycle.stops)
    shifted_legs = tuple(_shift_leg(leg, cutoff, delay) for leg in cycle.legs)
    return replace(
        cycle,
        planned_start=shifted_stops[0].planned_arrival,
        planned_finish=shifted_stops[-1].planned_departure,
        stops=shifted_stops,
        legs=shifted_legs,
        total_travel_seconds=sum(
            round((leg.arrival_at - leg.departure_at).total_seconds()) for leg in shifted_legs
        ),
    )


def _shift_stop(stop: RouteStop, cutoff: datetime, delay: timedelta) -> RouteStop:
    """Shift both stop times, or only its departure when delay starts at the stop."""

    if stop.planned_arrival >= cutoff:
        return replace(
            stop,
            planned_arrival=stop.planned_arrival + delay,
            planned_departure=stop.planned_departure + delay,
        )
    if stop.planned_departure >= cutoff:
        return replace(stop, planned_departure=stop.planned_departure + delay)
    return stop


def _shift_leg(leg: PlannedLeg, cutoff: datetime, delay: timedelta) -> PlannedLeg:
    """Shift a future leg or extend a leg containing the delay timestamp."""

    if leg.departure_at >= cutoff:
        departure = leg.departure_at + delay
        arrival = leg.arrival_at + delay
    elif leg.arrival_at >= cutoff:
        departure = leg.departure_at
        arrival = leg.arrival_at + delay
    else:
        return leg
    return replace(
        leg,
        departure_at=departure,
        arrival_at=arrival,
        travel_seconds=round((arrival - departure).total_seconds()),
    )


def _resolve_delay_cutoff(
    delay: DelayOverride,
    cycles: Sequence[RouteCycle],
) -> datetime:
    """Resolve a delay target to one concrete aware schedule timestamp."""

    if delay.effective_at is not None:
        return delay.effective_at
    for cycle in cycles:
        if cycle.id != delay.cycle_id:
            continue
        if delay.stop_sequence is None:
            return cycle.planned_start
        for stop in cycle.stops:
            if stop.sequence == delay.stop_sequence:
                return stop.planned_departure
        raise ValueError(f"unknown stop sequence {delay.stop_sequence} in {cycle.id}")
    raise ValueError(f"unknown delay cycle {delay.cycle_id}")


def _unavailable_overrides(
    overrides: Iterable[SimulationOverride],
) -> dict[str, DriverUnavailableOverride]:
    """Keep the earliest deterministic unavailability event per driver shift."""

    result: dict[str, DriverUnavailableOverride] = {}
    for override in overrides:
        if not isinstance(override, DriverUnavailableOverride):
            continue
        current = result.get(override.driver_shift_id)
        if current is None or (override.effective_at, override.id) < (
            current.effective_at,
            current.id,
        ):
            result[override.driver_shift_id] = override
    return result


def _geometry_points(geometry: GeoJsonLineString) -> tuple[GeoPoint, ...]:
    """Safely parse the coordinate subset of a GeoJSON LineString."""

    if geometry.get("type") != "LineString":
        raise ValueError("geometry must be a GeoJSON LineString")
    raw_coordinates = geometry.get("coordinates")
    if not isinstance(raw_coordinates, (list, tuple)):
        raise ValueError("LineString coordinates must be a sequence")
    result: list[GeoPoint] = []
    for coordinate in raw_coordinates:
        if (
            not isinstance(coordinate, (list, tuple))
            or len(coordinate) < 2
            or not isinstance(coordinate[0], (int, float))
            or not isinstance(coordinate[1], (int, float))
        ):
            raise ValueError("invalid LineString coordinate")
        result.append(GeoPoint(lon=float(coordinate[0]), lat=float(coordinate[1])))
    return tuple(result)


def _split_points_by_distance(
    points: tuple[GeoPoint, ...],
    progress: float,
) -> tuple[tuple[GeoPoint, ...], tuple[GeoPoint, ...]]:
    """Split a polyline at distance progress while preserving intermediate vertices."""

    if len(points) < 2:
        return points, points
    clamped = min(1.0, max(0.0, progress))
    distances = [haversine_distance_meters(first, second) for first, second in pairwise(points)]
    total = sum(distances)
    if total == 0:
        return points, (points[-1],)
    target = total * clamped
    traversed = 0.0
    for index, ((first, second), distance) in enumerate(
        zip(pairwise(points), distances, strict=True)
    ):
        if traversed + distance >= target:
            local = (target - traversed) / distance if distance else 1.0
            split = GeoPoint(
                lon=first.lon + (second.lon - first.lon) * local,
                lat=first.lat + (second.lat - first.lat) * local,
                is_city=first.is_city or second.is_city,
            )
            before = (*points[: index + 1], split)
            after = (split, *points[index + 1 :])
            return before, after
        traversed += distance
    return points, (points[-1],)


def _extend_unique(target: list[GeoPoint], points: Iterable[GeoPoint]) -> None:
    """Append points while omitting only a repeated segment boundary."""

    for point in points:
        if not target or target[-1].coordinates != point.coordinates:
            target.append(point)


def _line_string(points: Iterable[GeoPoint]) -> GeoJsonLineString:
    """Serialize points as a valid display LineString, including empty state."""

    coordinates = [list(point.coordinates) for point in points]
    return {"type": "LineString", "coordinates": coordinates}
