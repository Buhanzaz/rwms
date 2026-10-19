import type { Feature, LineString } from 'geojson';
import type { RoutePlan, SimulationDerivedState } from '../domain/types';

export interface SimulationRouteLayers {
  traveled: Feature<LineString>[];
  active: Feature<LineString>[];
}

/** Derives stable route overlays from completed stops and the active leg. */
export function deriveSimulationRouteLayers(
  plan: RoutePlan,
  simulation: SimulationDerivedState,
): SimulationRouteLayers {
  const completedOrActiveStopIds = new Set([
    ...simulation.completed_stop_ids,
    ...simulation.active_stop_ids,
  ]);
  const traveled: Feature<LineString>[] = [];
  const active: Feature<LineString>[] = [];

  for (const vehicle of simulation.vehicles) {
    const route = plan.driver_routes.find(
      (candidate) => candidate.driver_shift_id === vehicle.driver_shift_id,
    );
    if (!route) continue;

    for (const cycle of route.cycles) {
      const stops = [...cycle.stops].sort((left, right) => left.sequence - right.sequence);
      cycle.legs.forEach((leg, index) => {
        const isActive = cycle.id === vehicle.active_cycle_id && index === vehicle.active_leg_index;
        if (isActive) {
          active.push(leg.geometry);
          return;
        }
        const targetStopId = leg.to_stop_id ?? stops[index + 1]?.id;
        if (targetStopId && completedOrActiveStopIds.has(targetStopId)) traveled.push(leg.geometry);
      });
    }
  }

  return { traveled, active };
}
