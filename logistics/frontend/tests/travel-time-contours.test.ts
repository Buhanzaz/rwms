import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  api,
  type TravelTimeContourCollection,
} from '../src/api/client';
import {
  TRAVEL_TIME_CONTOUR_SOURCE_ID,
  TASK_TRAVEL_TIME_CONTOUR_LAYER_IDS,
  WAREHOUSE_TRAVEL_TIME_CONTOUR_LAYER_IDS,
  deriveTravelTimeContourOrigins,
  travelTimeContourStyles,
  travelTimeContourFeaturesForOrigin,
  travelTimeContourLayerSpecifications,
} from '../src/map/TravelTimeContours';
import { warehouseFixture } from './fixtures';

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

const warehouse = warehouseFixture({ name: 'Москва', latitude: 55.7, longitude: 37.6 });

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
  it('renders all supported dynamic hourly fills from outer to inner behind later overlays', () => {
    const layers = travelTimeContourLayerSpecifications();

    expect(layers).toHaveLength(24);
    expect(layers[0]?.id).toBe(`${TRAVEL_TIME_CONTOUR_SOURCE_ID}-depot-720`);
    expect(layers.at(-1)?.id).toBe(`${TRAVEL_TIME_CONTOUR_SOURCE_ID}-task-60`);
    expect(WAREHOUSE_TRAVEL_TIME_CONTOUR_LAYER_IDS).toEqual(layers.filter((layer) => layer.id.includes('-depot-')).map((layer) => layer.id));
    expect(TASK_TRAVEL_TIME_CONTOUR_LAYER_IDS).toEqual(layers.filter((layer) => layer.id.includes('-task-')).map((layer) => layer.id));
    expect(layers.every((layer) => layer.type === 'fill')).toBe(true);
    expect(layers.every((layer) => layer.source === TRAVEL_TIME_CONTOUR_SOURCE_ID)).toBe(true);
    expect(layers[0]?.filter).toEqual(['all', ['==', ['get', 'contour_minutes'], 720], ['==', ['get', 'origin_kind'], 'DEPOT']]);
    expect(layers[1]?.filter).toEqual(['all', ['==', ['get', 'contour_minutes'], 720], ['==', ['get', 'origin_kind'], 'TASK']]);
    expect(layers.every((layer) => layer.layout?.visibility === 'none')).toBe(true);
    expect(travelTimeContourStyles([60, 120, 300]).map((style) => style.label)).toEqual(['1 час', '2 часа', '5 часов']);
  });

  it('derives origins only for enabled warehouse and selected-task layers', () => {
    const task = {
      kind: 'TASK' as const,
      id: 'delivery-selected',
      name: 'Выбранное задание',
      latitude: 56.1,
      longitude: 38.2,
    };
    const origins = deriveTravelTimeContourOrigins([warehouse], task, true, true);

    expect(origins).toEqual([{
      kind: 'DEPOT',
      id: 'warehouse-1',
      name: 'Москва',
      latitude: 55.7,
      longitude: 37.6,
    }, {
      kind: 'TASK',
      id: 'delivery-selected',
      name: 'Выбранное задание',
      latitude: 56.1,
      longitude: 38.2,
    }]);
    expect(deriveTravelTimeContourOrigins([warehouse], task, false, false)).toEqual([]);
    expect(deriveTravelTimeContourOrigins([warehouse], task, false, true)).toEqual([task]);
  });

  it('keeps every connected warehouse on the common map layer', () => {
    const northWarehouse = warehouseFixture({
      id: 'warehouse-2',
      name: 'Склад СПб',
      latitude: 59.96,
      longitude: 30.49,
    });

    expect(deriveTravelTimeContourOrigins([northWarehouse, warehouse], null, true, false)).toEqual([
      { kind: 'DEPOT', id: 'warehouse-1', name: 'Москва', latitude: 55.7, longitude: 37.6 },
      { kind: 'DEPOT', id: 'warehouse-2', name: 'Склад СПб', latitude: 59.96, longitude: 30.49 },
    ]);
  });

  it('maps provider polygons and adds selected-task identity', () => {
    const origin = {
      kind: 'TASK' as const,
      id: 'delivery-selected',
      name: 'Выбранное задание',
      latitude: 56.1,
      longitude: 38.2,
    };
    const features = travelTimeContourFeaturesForOrigin(collection, origin);

    expect(features).toHaveLength(1);
    expect(features[0]?.geometry).toBe(collection.features[0]?.geometry);
    expect(features[0]?.properties).toEqual({
      contour_minutes: 60,
      origin_kind: 'TASK',
      origin_id: 'delivery-selected',
      origin_name: 'Выбранное задание',
    });
  });
});
