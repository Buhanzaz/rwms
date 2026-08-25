import type { Position } from 'geojson';
import type {
  DriverRoute,
  RouteCycle,
  RoutePlan,
  RouteStop,
  SimulationDerivedState,
  SimulationEvent,
  SimulationOverride,
  SimulationStatus,
  SimulationVehicleState,
  UUID,
  ValidationMessage,
} from '../domain/types';

const EARTH_RADIUS_METERS = 6_371_000;

function toRadians(value: number): number {
  return (value * Math.PI) / 180;
}

export function haversineDistance(a: Position, b: Position): number {
  const [lon1 = 0, lat1 = 0] = a;
  const [lon2 = 0, lat2 = 0] = b;
  const dLat = toRadians(lat2 - lat1);
  const dLon = toRadians(lon2 - lon1);
  const sinLat = Math.sin(dLat / 2);
  const sinLon = Math.sin(dLon / 2);
  const value = sinLat * sinLat + Math.cos(toRadians(lat1)) * Math.cos(toRadians(lat2)) * sinLon * sinLon;
  return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value));
}

export function interpolateLineString(coordinates: Position[], progress: number): Position {
  if (coordinates.length === 0) return [0, 0];
  if (coordinates.length === 1) return [...(coordinates[0] ?? [0, 0])];
  const clamped = Math.max(0, Math.min(1, progress));
  const lengths = coordinates.slice(1).map((point, index) => haversineDistance(coordinates[index] ?? point, point));
  const total = lengths.reduce((sum, length) => sum + length, 0);
  if (total === 0) return [...(coordinates[0] ?? [0, 0])];
  let remaining = total * clamped;
  for (let index = 0; index < lengths.length; index += 1) {
    const length = lengths[index] ?? 0;
    if (remaining <= length || index === lengths.length - 1) {
      const from = coordinates[index] ?? coordinates[0] ?? [0, 0];
      const to = coordinates[index + 1] ?? from;
      const local = length === 0 ? 0 : remaining / length;
      return [
        (from[0] ?? 0) + ((to[0] ?? 0) - (from[0] ?? 0)) * local,
        (from[1] ?? 0) + ((to[1] ?? 0) - (from[1] ?? 0)) * local,
      ];
    }
    remaining -= length;
  }
  return [...(coordinates.at(-1) ?? [0, 0])];
}

function millis(value: string): number {
  return new Date(value).getTime();
}

function iso(value: number): string {
  return new Date(value).toISOString();
}

function applicableDelayMs(
  driverShiftId: UUID,
  plannedTimestamp: number,
  simulationTimestamp: number,
  overrides: SimulationOverride[],
): number {
  return overrides
    .filter(
      (override) =>
        override.kind === 'DELAY' &&
        override.driver_shift_id === driverShiftId &&
        millis(override.effective_at) <= plannedTimestamp &&
        millis(override.effective_at) <= simulationTimestamp,
    )
    .reduce((total, override) => total + override.delay_minutes * 60_000, 0);
}

function adjusted(
  timestamp: string,
  driverShiftId: UUID,
  simulationTimestamp: number,
  overrides: SimulationOverride[],
): number {
  const planned = millis(timestamp);
  return planned + applicableDelayMs(driverShiftId, planned, simulationTimestamp, overrides);
}

function stopStatus(stop: RouteStop): SimulationStatus {
  switch (stop.stop_type) {
    case 'DEPOT_LOAD':
      return 'LOADING';
    case 'DELIVERY':
      return 'DELIVERING';
    case 'PICKUP':
      return 'PICKING_UP';
    case 'DEPOT_UNLOAD':
    case 'DEPOT_RETURN':
      return 'UNLOADING';
  }
}

function allCycles(route: DriverRoute): RouteCycle[] {
  return [...route.cycles].sort((a, b) => millis(a.planned_start) - millis(b.planned_start));
}

function vehicleBase(route: DriverRoute, position: Position): Omit<SimulationVehicleState, 'status'> {
  return {
    driver_shift_id: route.driver_shift_id,
    driver_name: route.driver_name,
    vehicle_name: route.vehicle_name,
    registration_number: route.registration_number,
    position: { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: position } },
    load: 0,
    next_stop_label: null,
    eta: null,
    active_cycle_id: null,
    active_leg_index: null,
    delayed_by_minutes: 0,
  };
}

function routeStateAt(
  route: DriverRoute,
  timestamp: number,
  delayOverrides: SimulationOverride[],
): SimulationVehicleState {
  const cycles = allCycles(route);
  const firstStop = cycles[0]?.stops[0];
  const fallback: Position = firstStop ? [firstStop.longitude, firstStop.latitude] : [0, 0];
  const base = vehicleBase(route, fallback);
  if (cycles.length === 0) return { ...base, status: 'FINISHED' };

  const delayedMinutes = delayOverrides
    .filter(
      (override) =>
        override.kind === 'DELAY' &&
        override.driver_shift_id === route.driver_shift_id &&
        millis(override.effective_at) <= timestamp,
    )
    .reduce((sum, override) => sum + override.delay_minutes, 0);

  const firstStart = adjusted(cycles[0]?.planned_start ?? '', route.driver_shift_id, timestamp, delayOverrides);
  if (timestamp < firstStart) {
    return {
      ...base,
      status: 'WAITING_SHIFT',
      next_stop_label: firstStop?.label ?? firstStop?.stop_type ?? null,
      eta: firstStop ? iso(adjusted(firstStop.planned_arrival, route.driver_shift_id, timestamp, delayOverrides)) : iso(firstStart),
      delayed_by_minutes: delayedMinutes,
    };
  }

  for (const cycle of cycles) {
    const cycleStart = adjusted(cycle.planned_start, route.driver_shift_id, timestamp, delayOverrides);
    const cycleFinish = adjusted(cycle.planned_finish, route.driver_shift_id, timestamp, delayOverrides);
    if (timestamp < cycleStart) {
      const prior = cycles.filter((candidate) => millis(candidate.planned_finish) <= millis(cycle.planned_start)).at(-1);
      const lastStop = prior?.stops.at(-1) ?? cycle.stops[0];
      const nextStop = [...cycle.stops].sort((a, b) => a.sequence - b.sequence)[0];
      const point: Position = lastStop ? [lastStop.longitude, lastStop.latitude] : fallback;
      return {
        ...vehicleBase(route, point),
        status: 'BREAK',
        load: lastStop?.load_after ?? 0,
        next_stop_label: nextStop?.label ?? nextStop?.stop_type ?? null,
        eta: nextStop ? iso(adjusted(nextStop.planned_arrival, route.driver_shift_id, timestamp, delayOverrides)) : iso(cycleStart),
        delayed_by_minutes: delayedMinutes,
      };
    }
    if (timestamp > cycleFinish) continue;

    const stops = [...cycle.stops].sort((a, b) => a.sequence - b.sequence);
    for (const stop of stops) {
      const arrival = adjusted(stop.planned_arrival, route.driver_shift_id, timestamp, delayOverrides);
      const departure = adjusted(stop.planned_departure, route.driver_shift_id, timestamp, delayOverrides);
      if (timestamp >= arrival && timestamp <= departure) {
        return {
          ...vehicleBase(route, [stop.longitude, stop.latitude]),
          status: stopStatus(stop),
          load: timestamp < departure ? stop.load_before : stop.load_after,
          next_stop_label: stop.label ?? stop.stop_type,
          eta: iso(arrival),
          active_cycle_id: cycle.id,
          active_leg_index: null,
          delayed_by_minutes: delayedMinutes,
        };
      }
    }

    for (let index = 0; index < cycle.legs.length; index += 1) {
      const leg = cycle.legs[index];
      if (!leg) continue;
      const departure = adjusted(leg.departure_at, route.driver_shift_id, timestamp, delayOverrides);
      const arrival = adjusted(leg.arrival_at, route.driver_shift_id, timestamp, delayOverrides);
      if (timestamp < departure || timestamp > arrival) continue;
      const progress = arrival <= departure ? 1 : (timestamp - departure) / (arrival - departure);
      const position = interpolateLineString(leg.geometry.geometry.coordinates, progress);
      const targetStop = cycle.stops[index + 1] ?? cycle.stops.at(-1);
      const sourceStop = cycle.stops[index] ?? cycle.stops[0];
      return {
        ...vehicleBase(route, position),
        status: targetStop?.stop_type === 'DEPOT_RETURN' || targetStop?.stop_type === 'DEPOT_UNLOAD' ? 'RETURNING' : 'DRIVING',
        load: sourceStop?.load_after ?? 0,
        next_stop_label: targetStop?.label ?? targetStop?.stop_type ?? null,
        eta: iso(arrival),
        active_cycle_id: cycle.id,
        active_leg_index: index,
        delayed_by_minutes: delayedMinutes,
      };
    }

    const previousStop = [...stops]
      .reverse()
      .find((stop) => adjusted(stop.planned_departure, route.driver_shift_id, timestamp, delayOverrides) < timestamp);
    if (previousStop) {
      const nextStop = stops.find((stop) => adjusted(stop.planned_arrival, route.driver_shift_id, timestamp, delayOverrides) > timestamp);
      return {
        ...vehicleBase(route, [previousStop.longitude, previousStop.latitude]),
        status: 'BREAK',
        load: previousStop.load_after,
        next_stop_label: nextStop?.label ?? nextStop?.stop_type ?? null,
        eta: nextStop ? iso(adjusted(nextStop.planned_arrival, route.driver_shift_id, timestamp, delayOverrides)) : null,
        active_cycle_id: cycle.id,
        delayed_by_minutes: delayedMinutes,
      };
    }
  }

  const last = cycles.at(-1)?.stops.at(-1);
  return {
    ...vehicleBase(route, last ? [last.longitude, last.latitude] : fallback),
    status: 'FINISHED',
    load: last?.load_after ?? 0,
    delayed_by_minutes: delayedMinutes,
  };
}

function buildEvents(
  route: DriverRoute,
  timestamp: number,
  overrides: SimulationOverride[],
): SimulationEvent[] {
  const events: SimulationEvent[] = [];
  for (const cycle of allCycles(route)) {
    for (const stop of [...cycle.stops].sort((a, b) => a.sequence - b.sequence)) {
      const arrival = adjusted(stop.planned_arrival, route.driver_shift_id, timestamp, overrides);
      const departure = adjusted(stop.planned_departure, route.driver_shift_id, timestamp, overrides);
      if (arrival <= timestamp) {
        events.push({
          id: `${stop.id}-arrival`,
          timestamp: iso(arrival),
          driver_shift_id: route.driver_shift_id,
          label: `${route.driver_name}: прибытие — ${stop.label ?? stop.stop_type}`,
          status: stopStatus(stop),
        });
      }
      if (departure <= timestamp) {
        events.push({
          id: `${stop.id}-departure`,
          timestamp: iso(departure),
          driver_shift_id: route.driver_shift_id,
          label: `${route.driver_name}: завершено — ${stop.label ?? stop.stop_type}`,
          status: stop.stop_type === 'DEPOT_RETURN' ? 'FINISHED' : 'DRIVING',
        });
      }
    }
  }
  return events;
}

function findUnavailable(
  driverShiftId: UUID,
  timestamp: number,
  overrides: SimulationOverride[],
): SimulationOverride | undefined {
  return overrides
    .filter(
      (override) =>
        override.kind === 'DRIVER_UNAVAILABLE' &&
        override.driver_shift_id === driverShiftId &&
        millis(override.effective_at) <= timestamp,
    )
    .sort((a, b) => millis(a.effective_at) - millis(b.effective_at))[0];
}

export function deriveSimulationState(
  plan: RoutePlan,
  simulatedTimestamp: string | number,
  simulationOverrides: SimulationOverride[],
): SimulationDerivedState {
  const timestamp = typeof simulatedTimestamp === 'number' ? simulatedTimestamp : millis(simulatedTimestamp);
  const delayOverrides = simulationOverrides.filter((override) => override.kind === 'DELAY');
  const warnings: ValidationMessage[] = [];
  const affectedTaskIds = new Set<UUID>();

  const vehicles = plan.driver_routes.map((route) => {
    const unavailable = findUnavailable(route.driver_shift_id, timestamp, simulationOverrides);
    if (unavailable) {
      const state = routeStateAt(route, millis(unavailable.effective_at), delayOverrides);
      route.cycles
        .flatMap((cycle) => cycle.stops)
        .filter((stop) => stop.task_id && adjusted(
          stop.planned_arrival,
          route.driver_shift_id,
          millis(unavailable.effective_at),
          delayOverrides,
        ) >= millis(unavailable.effective_at))
        .forEach((stop) => {
          if (stop.task_id) affectedTaskIds.add(stop.task_id);
        });
      warnings.push({
        code: 'DRIVER_UNAVAILABLE',
        message: `${route.driver_name} недоступен с ${new Date(unavailable.effective_at).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}`,
      });
      return { ...state, status: 'DELAYED' as const, eta: null };
    }
    const state = routeStateAt(route, timestamp, delayOverrides);
    if (state.delayed_by_minutes > 0) {
      route.cycles
        .flatMap((cycle) => cycle.stops)
        .filter((stop) => stop.task_id && millis(stop.planned_arrival) >= timestamp - state.delayed_by_minutes * 60_000)
        .forEach((stop) => {
          if (stop.task_id) affectedTaskIds.add(stop.task_id);
        });
      warnings.push({
        code: 'DELAY_APPLIED',
        message: `${route.driver_name}: расписание сдвинуто на ${state.delayed_by_minutes} мин. Проверьте временные окна и окончание смены.`,
      });
    }
    return state;
  });

  const completedStopIds: UUID[] = [];
  const activeStopIds: UUID[] = [];
  for (const route of plan.driver_routes) {
    const unavailable = findUnavailable(route.driver_shift_id, timestamp, simulationOverrides);
    const routeTimestamp = unavailable ? millis(unavailable.effective_at) : timestamp;
    for (const cycle of route.cycles) {
      for (const stop of cycle.stops) {
        const arrival = adjusted(stop.planned_arrival, route.driver_shift_id, routeTimestamp, delayOverrides);
        const departure = adjusted(stop.planned_departure, route.driver_shift_id, routeTimestamp, delayOverrides);
        if (departure < routeTimestamp) completedStopIds.push(stop.id);
        else if (arrival <= routeTimestamp && routeTimestamp <= departure) activeStopIds.push(stop.id);
      }
    }
  }

  const events = plan.driver_routes
    .flatMap((route) => {
      const unavailable = findUnavailable(route.driver_shift_id, timestamp, simulationOverrides);
      return buildEvents(
        route,
        unavailable ? millis(unavailable.effective_at) : timestamp,
        delayOverrides,
      );
    })
    .sort((a, b) => millis(a.timestamp) - millis(b.timestamp));

  return {
    timestamp: iso(timestamp),
    vehicles,
    events,
    completed_stop_ids: completedStopIds,
    active_stop_ids: activeStopIds,
    affected_task_ids: [...affectedTaskIds],
    warnings,
  };
}

export function planTimeBounds(plan: RoutePlan): { start: number; end: number } | null {
  const cycles = plan.driver_routes.flatMap((route) => route.cycles);
  if (cycles.length === 0) return null;
  return {
    start: Math.min(...cycles.map((cycle) => millis(cycle.planned_start))),
    end: Math.max(...cycles.map((cycle) => millis(cycle.planned_finish))),
  };
}

export function simulationEventTimestamps(plan: RoutePlan, overrides: SimulationOverride[]): number[] {
  const farFuture = Number.MAX_SAFE_INTEGER;
  return plan.driver_routes
    .flatMap((route) =>
      route.cycles.flatMap((cycle) =>
        cycle.stops.flatMap((stop) => [
          adjusted(stop.planned_arrival, route.driver_shift_id, farFuture, overrides),
          adjusted(stop.planned_departure, route.driver_shift_id, farFuture, overrides),
        ]),
      ),
    )
    .sort((a, b) => a - b);
}
