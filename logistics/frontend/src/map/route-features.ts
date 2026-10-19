import type { Feature, LineString } from 'geojson';

import type { RoutePlan, UUID } from '../domain/types';

export const ROUTE_COLORS = ['#5ee2b2', '#60a5fa', '#fb923c', '#c084fc', '#facc15', '#22d3ee'] as const;

/** Presentation properties attached to every rendered route section. */
export interface RouteFeatureProperties {
  cycleId: UUID;
  driver: string;
  driverIndex: number;
  color: string;
  isSelected: boolean;
  hasSelectedCycle: boolean;
  label: string;
  legIndex: number;
  positioning: boolean;
}

/** Builds road sections, including support-warehouse positioning, without changing a plan. */
export function routeFeatures(
  plan: RoutePlan | null,
  selectedCycleId: UUID | null,
  selectedDriverShiftId: UUID | null,
): Array<Feature<LineString, RouteFeatureProperties>> {
  if (!plan) return [];
  return plan.driver_routes.flatMap((driverRoute, driverIndex) =>
    driverRoute.cycles.flatMap((cycle, cycleIndex) => {
      const positioning = cycle.cross_warehouse_service;
      const routeParts: Array<{
        geometry: Feature<LineString>;
        label: string;
        positioning: boolean;
      }> = [
        ...(positioning?.positioning_outbound_geometry
          ? [{
              geometry: positioning.positioning_outbound_geometry,
              label: 'подача с опорного склада',
              positioning: true,
            }]
          : []),
        ...cycle.legs.map((leg, legIndex) => ({
          geometry: leg.geometry,
          label: `участок ${legIndex + 1}/${cycle.legs.length}`,
          positioning: false,
        })),
        ...(positioning?.positioning_return_geometry
          ? [{
              geometry: positioning.positioning_return_geometry,
              label: 'возврат на опорный склад',
              positioning: true,
            }]
          : []),
      ];
      return routeParts.map((part, legIndex): Feature<LineString, RouteFeatureProperties> => ({
        ...part.geometry,
        properties: {
          cycleId: cycle.id,
          driver: driverRoute.driver_name,
          driverIndex,
          color: ROUTE_COLORS[(driverIndex * 3 + cycleIndex) % ROUTE_COLORS.length] ?? ROUTE_COLORS[0],
          isSelected: selectedCycleId === cycle.id || selectedDriverShiftId === driverRoute.driver_shift_id,
          hasSelectedCycle: selectedCycleId !== null || selectedDriverShiftId !== null,
          label: `${driverRoute.driver_name} · рейс ${cycle.sequence} · ${part.label}`,
          legIndex,
          positioning: part.positioning,
        },
      }));
    }),
  );
}
