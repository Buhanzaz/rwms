import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  api,
  ApiError,
  type TruckRestrictionCollection,
  type TruckRestrictionFeature,
} from '../src/api/client';
import {
  buildTruckRestrictionPopupContent,
  truckRestrictionMapData,
} from '../src/map/TruckRestrictions';
import { TruckRestrictionLayerMenuItem } from '../src/map/TruckRestrictionsLayer';
import { useUiStore } from '../src/stores/ui-store';

const feature: TruckRestrictionFeature = {
  type: 'Feature',
  geometry: { type: 'Point', coordinates: [37.6, 55.7] },
  properties: {
    osm_type: 'node',
    osm_id: 142,
    category: 'MAX_HEIGHT',
    primary_tag: 'maxheight',
    value: '3.9',
    tags: { maxheight: '3.9', 'hgv:conditional': 'no @ (Mo-Fr 08:00-10:00)' },
    support_status: 'PARTIAL',
  },
};

const collection: TruckRestrictionCollection = {
  type: 'FeatureCollection',
  features: [feature],
  metadata: {
    osm_data_version: 'central-fed-district-2026-08-22',
    count: 1,
    truncated: false,
    generated_at: '2026-08-25T10:00:00Z',
  },
};

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  useUiStore.setState((state) => ({
    layers: { ...state.layers, truckRestrictions: false },
  }));
});

describe('truck restriction layer state', () => {
  it('is disabled by default and can be toggled independently', () => {
    useUiStore.setState((state) => ({ layers: { ...state.layers, truckRestrictions: false } }));
    expect(useUiStore.getState().layers.truckRestrictions).toBe(false);

    useUiStore.getState().toggleLayer('truckRestrictions');
    expect(useUiStore.getState().layers.truckRestrictions).toBe(true);

    useUiStore.getState().toggleLayer('truckRestrictions');
    expect(useUiStore.getState().layers.truckRestrictions).toBe(false);
  });

  it('shows the switch, loaded count, truncation warning and non-color legend', () => {
    const onChange = vi.fn();
    render(
      <TruckRestrictionLayerMenuItem
        checked
        state={{ status: 'loaded', count: 2000, truncated: true, error: null }}
        onChange={onChange}
      />,
    );

    expect(screen.getByLabelText('Ограничения грузового транспорта')).toBeChecked();
    expect(screen.getByRole('status')).toHaveTextContent('Показано ограничений: 2000');
    expect(screen.getByRole('status')).toHaveTextContent('показаны первые 2000');
    expect(screen.getByLabelText('Легенда грузовых ограничений')).toHaveTextContent('HGV');
    expect(screen.getByLabelText('Легенда грузовых ограничений')).toHaveTextContent('Ограничение нагрузки на ось');

    fireEvent.click(screen.getByLabelText('Ограничения грузового транспорта'));
    expect(onChange).toHaveBeenCalledOnce();
  });

  it('shows zoom guidance and API failures inline', () => {
    const { rerender } = render(
      <TruckRestrictionLayerMenuItem
        checked
        state={{ status: 'zoom', count: 0, truncated: false, error: null }}
        onChange={() => undefined}
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('масштаба 8');

    rerender(
      <TruckRestrictionLayerMenuItem
        checked
        state={{ status: 'error', count: 0, truncated: false, error: 'Valhalla недоступна' }}
        onChange={() => undefined}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Ограничения не загрузились');
    expect(screen.getByRole('alert')).toHaveTextContent('Valhalla недоступна');
  });
});

describe('truck restriction API', () => {
  it('sends the current bbox, limit and AbortSignal', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>();
    fetchMock.mockResolvedValue(jsonResponse(collection));
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();

    const result = await api.getTruckRestrictions({
      west: 37.1,
      south: 55.2,
      east: 38.3,
      north: 56.4,
    }, controller.signal, 2000);

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/routing/truck-restrictions?west=37.1&south=55.2&east=38.3&north=56.4&limit=2000');
    expect(fetchMock.mock.calls[0]?.[1]?.signal).toBe(controller.signal);
    expect(result.metadata.osm_data_version).toBe('central-fed-district-2026-08-22');
  });

  it('preserves cancellation and maps an API failure to ApiError', async () => {
    const abortingFetch = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
    }));
    vi.stubGlobal('fetch', abortingFetch);
    const controller = new AbortController();
    const pending = api.getTruckRestrictions({ west: 37, south: 55, east: 38, north: 56 }, controller.signal);
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });

    const failingFetch = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>();
    failingFetch.mockResolvedValue(jsonResponse({ title: 'Слой недоступен', detail: 'OSM index unavailable' }, 503));
    vi.stubGlobal('fetch', failingFetch);
    const failure = await api.getTruckRestrictions({ west: 37, south: 55, east: 38, north: 56 }).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({ status: 503, message: 'OSM index unavailable' });
  });
});

describe('truck restriction rendering helpers', () => {
  it('adds readable marker properties without passing nested OSM tags to MapLibre', () => {
    const mapData = truckRestrictionMapData(collection);
    expect(mapData.features[0]?.properties).toEqual({
      osm_type: 'node',
      osm_id: 142,
      category: 'MAX_HEIGHT',
      marker: 'H',
      color: '#f97316',
    });
  });

  it('formats popup diagnostics as text and never interprets OSM values as HTML', () => {
    const hostileFeature: TruckRestrictionFeature = {
      ...feature,
      properties: {
        ...feature.properties,
        value: '<img src=x onerror=alert(1)>',
        tags: { maxheight: '<script>unsafe()</script>', hgv: 'no' },
      },
    };
    const popup = buildTruckRestrictionPopupContent(hostileFeature, collection.metadata);

    expect(popup.textContent).toContain('Ограничение высоты');
    expect(popup.textContent).toContain('частично');
    expect(popup.textContent).toContain('node 142');
    expect(popup.textContent).toContain('central-fed-district-2026-08-22');
    expect(popup.textContent).toContain('<script>unsafe()</script>');
    expect(popup.querySelector('script')).toBeNull();
    expect(popup.querySelector('img')).toBeNull();
  });
});
