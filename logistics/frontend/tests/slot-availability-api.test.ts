import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../src/api/client';

afterEach(() => vi.unstubAllGlobals());

describe('slot availability transport', () => {
  it('posts the frozen snake-case request and preserves server-owned route evidence', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>().mockResolvedValue(new Response(JSON.stringify({
      date: '2026-08-29',
      plan_version: 17,
      delivery_price_rubles: 12500,
      price_isochrone_minutes: 300,
      trailer_access_allowed: false,
      slots: [{
        start: '09:00',
        end: '12:00',
        status: 'AVAILABLE',
        candidate_count: 2,
        reasons: [],
        explanation: ['Помещается перед существующей доставкой'],
        best_candidate: {
          driver_id: 'driver-1',
          shift_id: 'shift-1',
          estimated_service_start: '2026-08-29T09:20:00+03:00',
          incremental_distance_meters: 18400,
          route_after: { type: 'LineString', coordinates: [[30.1, 59.8], [30.3, 59.9]] },
          pickup_candidates_geojson: { type: 'FeatureCollection', features: [{ type: 'Feature', geometry: { type: 'Point', coordinates: [30.25, 59.85] }, properties: { task_id: 'pickup-1', selection_state: 'SELECTED' } }] },
          timeline: [{
            id: 'delivery-new',
            stop_type: 'DELIVERY',
            task_id: 'task-new',
            arrival: '2026-08-29T09:10:00+03:00',
            service_start: '2026-08-29T09:20:00+03:00',
            service_end: '2026-08-29T10:20:00+03:00',
            departure: '2026-08-29T10:20:00+03:00',
            load_before: 2,
            load_after: 1,
          }],
        },
      }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    const response = await api.calculateSlotAvailability({
      warehouse_id: 'warehouse-spb',
      date: '2026-08-29',
      address: 'Санкт-Петербург, Невский проспект, 1',
      latitude: 59.93428,
      longitude: 30.335099,
      cabin_count: 2,
      site_cabin_capacity: 1,
    });

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/planning/slot-availability');
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1]?.body as string)).toEqual({
      warehouse_id: 'warehouse-spb',
      date: '2026-08-29',
      address: 'Санкт-Петербург, Невский проспект, 1',
      latitude: 59.93428,
      longitude: 30.335099,
      cabin_count: 2,
      site_cabin_capacity: 1,
    });
    expect(response.delivery_price_rubles).toBe(12500);
    expect(response.price_isochrone_minutes).toBe(300);
    expect(response.trailer_access_allowed).toBe(false);
    expect(response.slots[0]?.best_candidate?.route_after_geojson?.type).toBe('LineString');
    expect(response.slots[0]?.best_candidate).toMatchObject({
      driver_shift_id: 'shift-1',
      incremental_distance: 18400,
      pickup_candidates_geojson: { type: 'FeatureCollection' },
    });
    expect(response.slots[0]?.best_candidate?.timeline[0]).toMatchObject({
      stop_id: 'delivery-new',
      type: 'DELIVERY',
      arrival_at: '2026-08-29T09:10:00+03:00',
      departure_at: '2026-08-29T10:20:00+03:00',
      load_before: 2,
      load_after: 1,
    });
  });

  it('never turns an unknown backend status into an available slot', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      date: '2026-08-29',
      plan_version: 18,
      slots: [{ start: '12:00', end: '15:00', status: 'MAYBE', candidate_count: 5 }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })));

    const response = await api.calculateSlotAvailability({
      warehouse_id: 'warehouse-spb', date: '2026-08-29',
      address: 'Адрес', latitude: 59.9, longitude: 30.3, cabin_count: 1, site_cabin_capacity: 1,
    });

    expect(response.slots[0]).toMatchObject({ status: 'UNAVAILABLE', reasons: ['AVAILABILITY_NOT_CONFIRMED'] });
  });
});
