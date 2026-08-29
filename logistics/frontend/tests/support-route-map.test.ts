import { describe, expect, it } from 'vitest';

import { EMPTY_METRICS } from '../src/domain/defaults';
import type { CrossWarehouseServiceContext, RouteCycle } from '../src/domain/types';
import { routeFeatures } from '../src/map/route-features';
import { planFixture } from './fixtures';

const feature = (coordinates: [number, number][]) => ({
  type: 'Feature' as const,
  properties: {},
  geometry: { type: 'LineString' as const, coordinates },
});

describe('support warehouse positioning on the route map', () => {
  it('renders exact outbound and return road geometry around the served-warehouse cycle', () => {
    const context: CrossWarehouseServiceContext = {
      execution_mode: 'CROSS_WAREHOUSE_SERVICE',
      service_warehouse_id: 'served-warehouse',
      resource_origin_warehouse_id: 'support-warehouse',
      resource_origin_warehouse_name: 'Опорный склад',
      support_warehouse_link_id: 'support-link',
      driver_id: 'driver',
      driver_worker_id: 'worker',
      driver_name: 'Петров Алексей',
      vehicle_id: 'vehicle',
      vehicle_name: 'МАЗ',
      vehicle_registration_number: 'А456ВС',
      available_at_served: '2026-08-30T12:00:00Z',
      latest_served_finish: '2026-08-30T17:00:00Z',
      inbound_travel_minutes: 180,
      return_travel_minutes: 190,
      positioning_distance_meters: 380_000,
      inbound_distance_meters: 185_000,
      return_distance_meters: 195_000,
      positioning_outbound_geometry: feature([
        [30, 59],
        [30.5, 58.7],
        [31, 58],
      ]),
      positioning_return_geometry: feature([
        [31, 58],
        [30.4, 58.8],
        [30, 59],
      ]),
      available_transfer_cabin_capacity: 2,
      trailer_available: true,
      outbound_positioning_empty: true,
      empty_positioning_reason_required: true,
      returns_to_origin: true,
      changes_operational_warehouse: false,
      reason_codes: ['SUPPORT_DRIVER_AVAILABLE'],
    };
    const cycle: RouteCycle = {
      id: 'cycle',
      route_plan_id: 'plan-1',
      driver_shift_id: 'shift',
      sequence: 1,
      planned_start: '2026-08-30T12:00:00Z',
      planned_finish: '2026-08-30T15:00:00Z',
      total_distance_meters: 50_000,
      total_travel_seconds: 3_600,
      total_service_seconds: 1_800,
      empty_distance_meters: 0,
      detour_seconds: 0,
      score: 1,
      locked: false,
      stops: [],
      legs: [{
        departure_at: '2026-08-30T12:00:00Z',
        arrival_at: '2026-08-30T13:00:00Z',
        distance_meters: 50_000,
        travel_seconds: 3_600,
        geometry: feature([[31, 58], [31.2, 58.2]]),
      }],
      explanation: [],
      warnings: [],
      cross_warehouse_service: context,
    };
    const plan = planFixture({
      driver_routes: [{
        driver_shift_id: 'shift',
        shift_start_at: '2026-08-30T08:00:00Z',
        shift_end_at: '2026-08-30T20:00:00Z',
        driver_id: 'driver',
        driver_name: 'Петров Алексей',
        vehicle_id: 'vehicle',
        vehicle_name: 'МАЗ',
        registration_number: 'А456ВС',
        cycles: [cycle],
        metrics: { ...EMPTY_METRICS },
        cross_warehouse_service: context,
      }],
    });

    const routes = routeFeatures(plan, null, null);

    expect(routes).toHaveLength(3);
    expect(routes.map((route) => route.properties?.positioning)).toEqual([
      true,
      false,
      true,
    ]);
    expect(routes[0]?.geometry.coordinates).toHaveLength(3);
    expect(routes[2]?.geometry.coordinates).toHaveLength(3);
    expect(routes[0]?.properties?.label).toContain('подача с опорного склада');
    expect(routes[2]?.properties?.label).toContain('возврат на опорный склад');
  });
});
