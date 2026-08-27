import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  api,
  type TravelTimeContourCollection,
} from '../src/api/client';
import { EMPTY_METRICS } from '../src/domain/defaults';
import type { DriverRoute, RouteCycle, RoutePlan, RouteStop, StopType, Warehouse } from '../src/domain/types';
import {
  TRAVEL_TIME_CONTOUR_LAYER_IDS,
  TRAVEL_TIME_CONTOUR_SOURCE_ID,
  deriveTravelTimeContourOrigins,
  scenarioViewportCoordinates,
  travelTimeContourFeaturesForOrigin,
  travelTimeContourLayerSpecifications,
  travelTimeContourStatusText,
} from '../src/map/TravelTimeContours';

const collection: TravelTimeContourCollection = {
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      geometry: {
        type: 'Polygon',
        coordinates: [[[37.5, 55.6], [37.7, 55.6], [37.7, 55.8], [37.5, 55.6]]],
      },
      properties: { contour_minutes: 60 },
    },
  ],
  metadata: {
    source: 'valhalla',
    costing: 'truck',
    origin: { latitude: 55.7, longitude: 37.6 },
    contours_minutes: [60, 120, 180, 240],
    osm_data_version: 'central-2026-08-26',
  },
};

const warehouse: Warehouse = {
  id: 'warehouse-1',
  scenario_id: 'scenario-1',
  name: 'Москва',
  latitude: 55.7,
  longitude: 37.6,
  loading_minutes: 30,
  unloading_minutes: 30,
  turnaround_minutes: 15,
  working_day_start: '09:00',
  working_day_end: '18:00',
};

function stop(
  id: string,
  type: StopType,
  sequence: number,
  plannedArrival: string,
  latitude: number,
  longitude: number,
): RouteStop {
  return {
    id,
    route_cycle_id: `cycle-${id}`,
    sequence,
    task_id: type === 'DELIVERY' || type === 'PICKUP' ? `task-${id}` : null,
    stop_type: type,
    planned_arrival: plannedArrival,
    planned_departure: plannedArrival,
    service_seconds: 0,
    quantity_delta: 0,
    load_before: 0,
    load_after: 0,
    latitude,
    longitude,
  };
}

function cycle(
  id: string,
  driverShiftId: string,
  sequence: number,
  plannedStart: string,
  stops: RouteStop[],
): RouteCycle {
  return {
    id,
    route_plan_id: 'plan-1',
    driver_shift_id: driverShiftId,
    sequence,
    planned_start: plannedStart,
    planned_finish: plannedStart,
    total_distance_meters: 0,
    total_travel_seconds: 0,
    total_service_seconds: 0,
    empty_distance_meters: 0,
    detour_seconds: 0,
    score: 0,
    locked: false,
    stops,
    legs: [],
    explanation: [],
    warnings: [],
  };
}

function driverRoute(driverShiftId: string, driverName: string, cycles: RouteCycle[]): DriverRoute {
  return {
    driver_shift_id: driverShiftId,
    shift_start_at: '2026-08-26T08:00:00Z',
    shift_end_at: '2026-08-26T18:00:00Z',
    driver_id: `driver-${driverShiftId}`,
    driver_name: driverName,
    vehicle_id: `vehicle-${driverShiftId}`,
    vehicle_name: 'Тягач',
    registration_number: 'А001АА77',
    preferred_route_group: 'DEFAULT',
    cycles,
    metrics: { ...EMPTY_METRICS },
  };
}

function plan(driverRoutes: DriverRoute[]): RoutePlan {
  return {
    id: 'plan-1',
    scenario_id: 'scenario-1',
    warehouse_id: warehouse.id,
    date: '2026-08-26',
    version: 1,
    status: 'GENERATED',
    score: 0,
    created_at: '2026-08-26T08:00:00Z',
    updated_at: '2026-08-26T08:00:00Z',
    driver_routes: driverRoutes,
    unassigned: [],
    metrics: { ...EMPTY_METRICS },
  };
}

afterEach(() => vi.unstubAllGlobals());

describe('travel-time contour transport', () => {
  it('requests the read-only origin-coordinate endpoint and forwards cancellation', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>();
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify(collection), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();

    const result = await api.getTravelTimeContours(55.7, 37.6, controller.signal);

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/routing/travel-time-contours?latitude=55.7&longitude=37.6');
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBeUndefined();
    expect(fetchMock.mock.calls[0]?.[1]?.signal).toBe(controller.signal);
    expect(result).toEqual(collection);
  });
});

describe('travel-time contour map layers', () => {
  it('derives the active scenario viewport from its own depot and zone vertices', () => {
    const saintPetersburgWarehouse: Warehouse = {
      ...warehouse,
      id: 'warehouse-spb',
      scenario_id: 'scenario-spb',
      name: 'Санкт-Петербург',
      latitude: 59.7674,
      longitude: 30.4798,
    };
    const coordinates = scenarioViewportCoordinates([saintPetersburgWarehouse], [{
      id: 'zone-spb',
      scenario_id: 'scenario-spb',
      code: '1',
      name: '1',
      route_group: 'SPB',
      delivery_price: 0,
      pickup_price: 0,
      priority: 1,
      locked: false,
      version: 0,
      created_at: '2026-08-27T00:00:00Z',
      updated_at: '2026-08-27T00:00:00Z',
      geometry: {
        type: 'MultiPolygon',
        coordinates: [[[[30.18, 59.88], [30.42, 59.88], [30.42, 60.0], [30.18, 59.88]]]],
      },
    }]);

    expect(coordinates).toEqual([
      [30.4798, 59.7674],
      [30.18, 59.88],
      [30.42, 59.88],
      [30.42, 60.0],
      [30.18, 59.88],
    ]);
    expect(coordinates.every(([longitude]) => longitude < 31)).toBe(true);
  });

  it('renders fixed translucent fills from outer to inner behind later overlays', () => {
    const layers = travelTimeContourLayerSpecifications();

    expect(layers.map((layer) => layer.id)).toEqual([
      `${TRAVEL_TIME_CONTOUR_SOURCE_ID}-240`,
      `${TRAVEL_TIME_CONTOUR_SOURCE_ID}-180`,
      `${TRAVEL_TIME_CONTOUR_SOURCE_ID}-120`,
      `${TRAVEL_TIME_CONTOUR_SOURCE_ID}-60`,
    ]);
    expect(TRAVEL_TIME_CONTOUR_LAYER_IDS).toEqual(layers.map((layer) => layer.id));
    expect(layers.every((layer) => layer.type === 'fill')).toBe(true);
    expect(layers.every((layer) => layer.source === TRAVEL_TIME_CONTOUR_SOURCE_ID)).toBe(true);
    expect(layers.map((layer) => layer.filter)).toEqual([
      ['==', ['get', 'contour_minutes'], 240],
      ['==', ['get', 'contour_minutes'], 180],
      ['==', ['get', 'contour_minutes'], 120],
      ['==', ['get', 'contour_minutes'], 60],
    ]);
    expect(layers.every((layer) => layer.layout?.visibility === 'none')).toBe(true);
  });

  it('derives one stable origin per depot and latest delivery frontier', () => {
    const latestDelivery = stop('delivery-latest', 'DELIVERY', 2, '2026-08-26T12:00:00Z', 56.1, 38.2);
    const pickupAfterDelivery = stop('pickup-later', 'PICKUP', 3, '2026-08-26T13:00:00Z', 57.1, 39.2);
    const depotReturn = stop('return-latest', 'DEPOT_RETURN', 4, '2026-08-26T14:00:00Z', 55.7, 37.6);
    const oldDelivery = stop('delivery-old', 'DELIVERY', 1, '2026-08-26T09:00:00Z', 55.9, 37.9);
    const sameAsDepotDelivery = stop('delivery-at-depot', 'DELIVERY', 1, '2026-08-26T15:00:00Z', 55.7, 37.6);
    const pickupOnly = stop('pickup-only', 'PICKUP', 1, '2026-08-26T16:00:00Z', 58.1, 40.2);
    const routePlan = plan([
      driverRoute('shift-b', 'Борис', [
        cycle('cycle-latest', 'shift-b', 2, '2026-08-26T11:00:00Z', [depotReturn, latestDelivery, pickupAfterDelivery]),
        cycle('cycle-old', 'shift-b', 1, '2026-08-26T08:00:00Z', [oldDelivery]),
      ]),
      driverRoute('shift-a', 'Анна', [cycle('cycle-depot', 'shift-a', 1, '2026-08-26T14:00:00Z', [sameAsDepotDelivery])]),
      driverRoute('shift-c', 'Сергей', [cycle('cycle-pickup', 'shift-c', 1, '2026-08-26T15:00:00Z', [pickupOnly])]),
    ]);

    const origins = deriveTravelTimeContourOrigins([warehouse], routePlan);

    expect(origins).toEqual([{
      kind: 'DEPOT',
      id: 'warehouse-1',
      name: 'Москва',
      latitude: 55.7,
      longitude: 37.6,
    }, {
      kind: 'ROUTE_FRONT',
      id: 'delivery-latest',
      name: 'Борис · последняя доставка',
      latitude: 56.1,
      longitude: 38.2,
      driverShiftId: 'shift-b',
    }]);
    expect(deriveTravelTimeContourOrigins([warehouse], null)).toHaveLength(1);
  });

  it('maps provider polygons and adds generalized route-front identity', () => {
    const origin = {
      kind: 'ROUTE_FRONT' as const,
      id: 'delivery-latest',
      name: 'Борис · последняя доставка',
      latitude: 56.1,
      longitude: 38.2,
      driverShiftId: 'shift-b',
    };
    const features = travelTimeContourFeaturesForOrigin(collection, origin);

    expect(features).toHaveLength(1);
    expect(features[0]?.geometry).toBe(collection.features[0]?.geometry);
    expect(features[0]?.properties).toEqual({
      contour_minutes: 60,
      origin_kind: 'ROUTE_FRONT',
      origin_id: 'delivery-latest',
      origin_name: 'Борис · последняя доставка',
      driver_shift_id: 'shift-b',
    });
  });

  it('keeps provider unavailability explicit and non-authoritative', () => {
    expect(travelTimeContourStatusText({ status: 'loading' }, true))
      .toBe('Изохроны складов и точек маршрута загружаются');
    expect(travelTimeContourStatusText({ status: 'unavailable', error: 'Valhalla недоступна' }, true))
      .toBe('Изохроны складов и точек маршрута недоступны: Valhalla недоступна');
    expect(travelTimeContourStatusText({ status: 'loaded', depotCount: 2, routeFrontCount: 3 }, true))
      .toBe('Изохроны 1–4 ч · складов: 2 · точек маршрута: 3');
    expect(travelTimeContourStatusText({ status: 'unavailable', error: 'ignored' }, false)).toBeNull();
  });
});
