import { describe, expect, it, vi } from 'vitest';
import { createTransferDraft } from '../src/features/transfers/transfer-client';

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
});
