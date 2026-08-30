import { describe, expect, it, vi } from 'vitest';
import {
  createTransferDraft,
  estimateTransferArrival,
  loadTransferCargoCatalog,
  loadTransferRouteVehicles,
} from '../src/features/transfers/transfer-client';

describe('canonical transfer client', () => {
  it('posts the required CreateTransferRequest to the root same-origin logistics endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 'transfer-1', version: 0, state: 'DRAFT' }), {
      status: 201,
      headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    await createTransferDraft({
      accessToken: 'panel-token',
      idempotencyKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      warehouseId: '11111111-1111-4111-8111-111111111111',
      destinationWarehouseId: '22222222-2222-4222-8222-222222222222',
      scheduledDate: '2026-08-30',
      plan: {
        plannedDepartureAt: null,
        plannedArrivalAt: null,
        logisticsComment: null,
        tripDriverId: null,
        tripVehicleId: null,
        driverReposition: null,
        vehicleReposition: null,
        cabinGroups: [],
        looseFurniture: [],
      },
    });

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/logistics/v1/transfers');
    expect(new Headers(init.headers)).toMatchObject(expect.any(Headers));
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer panel-token');
    expect(new Headers(init.headers).get('Idempotency-Key')).toBe('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    expect(JSON.parse(typeof init.body === 'string' ? init.body : '')).toEqual({
      warehouseId: '11111111-1111-4111-8111-111111111111',
      destinationWarehouseId: '22222222-2222-4222-8222-222222222222',
      scheduledDate: '2026-08-30',
      lines: [],
      furnitureReplacements: [],
      plan: {
        plannedDepartureAt: null,
        plannedArrivalAt: null,
        logisticsComment: null,
        tripDriverId: null,
        tripVehicleId: null,
        driverReposition: null,
        vehicleReposition: null,
        cabinGroups: [],
        looseFurniture: [],
      },
    });
  });

  it('loads cabin catalogs and source furniture balances from canonical asset APIs', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        rentalTypes: [{ id: 'type-1', name: 'BK2' }],
        dimensions: [{ id: 'dimension-1', name: '6 × 2,4 м' }],
        finishings: [{ id: 'finishing-1', name: 'ЛДСП' }],
        characteristics: [{ id: 'characteristic-1', name: 'Усиленная' }],
        typeDimensions: [{ typeId: 'type-1', dimensionId: 'dimension-1', sortOrder: 0 }],
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify([
        {
          equipment: { id: 'bed-1', name: 'Кровать', category: 'FURNITURE', active: true },
          totals: { warehouseId: 'warehouse-1', availableStock: 18, reservedQuantity: 2 },
        },
        {
          equipment: { id: 'cable-1', name: 'Кабель', category: 'ELECTRICAL', active: true },
          totals: { warehouseId: 'warehouse-1', availableStock: 8, reservedQuantity: 0 },
        },
      ]), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    const catalog = await loadTransferCargoCatalog('panel-token', 'warehouse-1');

    expect(catalog.rentalTypes).toEqual([{ id: 'type-1', name: 'BK2' }]);
    expect(catalog.furniture).toEqual([{ id: 'bed-1', name: 'Кровать', availableStock: 18, reservedQuantity: 2 }]);
    expect(fetchMock.mock.calls.map((call) => String(call[0]))).toEqual([
      '/api/asset/v1/rental-items/creation-options?warehouseId=warehouse-1',
      '/api/asset/v1/equipment?warehouseId=warehouse-1',
    ]);
    expect(new Headers((fetchMock.mock.calls[0]?.[1] as RequestInit).headers).get('Authorization')).toBe('Bearer panel-token');
  });

  it('loads active local vehicles and asks backend for an exact transfer arrival', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        vehicles: [
          { id: 'vehicle-1', warehouse_id: 'local-spb', name: 'SPB-04', registration_number: 'А123АА 178', capacity: 2, active: true },
          { id: 'vehicle-2', warehouse_id: 'local-spb', name: 'Резерв', registration_number: 'В456ВВ 178', capacity: 1, active: false },
        ],
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        departure_at: '2026-08-30T05:30:00Z',
        estimated_arrival_at: '2026-08-30T09:20:00Z',
        travel_seconds: 13_800,
        distance_meters: 194_600,
        vehicle_id: 'vehicle-1',
        cabin_count: 2,
        trailer_attached: true,
        routing_provider: 'valhalla',
        osm_data_version: '2026-08-29',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    expect(await loadTransferRouteVehicles('local-spb')).toEqual([{
      id: 'vehicle-1',
      name: 'SPB-04',
      registrationNumber: 'А123АА 178',
      capacity: 2,
    }]);
    const arrival = await estimateTransferArrival({
      sourceWarehouseId: 'spb',
      destinationWarehouseId: 'novgorod',
      plannedDepartureAt: '2026-08-30T05:30:00Z',
      vehicleId: 'vehicle-1',
      cabinCount: 2,
    });

    expect(arrival.estimated_arrival_at).toBe('2026-08-30T09:20:00Z');
    const [estimateUrl, estimateInit] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(estimateUrl).toBe('/api/routing/transfer-arrival-estimate');
    expect(JSON.parse(estimateInit.body as string)).toEqual({
      source_warehouse_id: 'spb',
      destination_warehouse_id: 'novgorod',
      planned_departure_at: '2026-08-30T05:30:00Z',
      vehicle_id: 'vehicle-1',
      cabin_count: 2,
    });
  });
});
