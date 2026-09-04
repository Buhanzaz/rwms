import { describe, expect, it, vi } from 'vitest';
import { ApiError, api } from '../src/api/client';
import type { PolicyZoneInput, WarehousePolicyZone } from '../src/domain/types';
import { userFacingErrorDetail } from '../src/utils/user-facing-error';

const geometry = {
  type: 'MultiPolygon' as const,
  coordinates: [[[[30.3, 59.9], [30.4, 59.9], [30.4, 60], [30.3, 59.9]]]],
};
const input: PolicyZoneInput = {
  name: 'Особая цена',
  kind: 'SPECIAL_PRICE',
  color: '#3B82F6',
  geometry,
  delivery_price_rubles: 20_000,
  pickup_price_rubles: 12_000,
};
const zone: WarehousePolicyZone = {
  id: '22222222-2222-4222-8222-222222222222',
  warehouse_id: 'warehouse-1',
  ...input,
  color: '#3B82F6',
  version: 4,
  created_at: '2026-08-31T10:00:00Z',
  updated_at: '2026-08-31T10:00:00Z',
};

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function body(init: RequestInit | undefined): unknown {
  return typeof init?.body === 'string' ? JSON.parse(init.body) as unknown : undefined;
}

describe('policy-zone transport', () => {
  it('keeps CRUD warehouse-scoped, idempotent, and optimistically fenced', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse([zone]))
      .mockResolvedValueOnce(jsonResponse(zone, 201))
      .mockResolvedValueOnce(jsonResponse({ ...zone, version: 5 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await api.listPolicyZones('warehouse-1');
    await api.createPolicyZone('warehouse-1', input, 'create-policy-intent');
    await api.updatePolicyZone('warehouse-1', zone.id, input, 4);
    await api.deletePolicyZone('warehouse-1', zone.id, 5);

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/warehouses/warehouse-1/policy-zones',
      '/api/warehouses/warehouse-1/policy-zones',
      `/api/warehouses/warehouse-1/policy-zones/${zone.id}`,
      `/api/warehouses/warehouse-1/policy-zones/${zone.id}?expected_version=5`,
    ]);
    const createInit = fetchMock.mock.calls[1]?.[1] as RequestInit;
    const updateInit = fetchMock.mock.calls[2]?.[1] as RequestInit;
    expect(new Headers(createInit.headers).get('Idempotency-Key')).toBe('create-policy-intent');
    expect(body(createInit)).toEqual(input);
    expect(body(updateInit)).toEqual({ ...input, expected_version: 4 });
    expect((fetchMock.mock.calls[3]?.[1] as RequestInit).method).toBe('DELETE');
  });

  it('maps policy slot diagnostics to stable Russian operator messages', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({
        code: 'DELIVERY_FORBIDDEN_ZONE',
        detail: 'Delivery is prohibited for this address',
      }, 422))
      .mockResolvedValueOnce(jsonResponse({
        code: 'POLICY_ZONE_VALUES_INVALID',
        detail: 'A SPECIAL_PRICE policy requires a delivery price',
      }, 422));
    vi.stubGlobal('fetch', fetchMock);
    const slotInput = {
      warehouse_id: 'warehouse-1',
      date: '2026-08-31',
      address: 'Тестовый адрес',
      latitude: 59.94,
      longitude: 30.33,
      cabin_count: 1,
      site_cabin_capacity: 1 as const,
    };

    const forbidden = await api.calculateSlotAvailability(slotInput)
      .catch((error: unknown) => error);
    const invalidPrice = await api.calculateSlotAvailability(slotInput)
      .catch((error: unknown) => error);

    expect(forbidden).toBeInstanceOf(ApiError);
    expect((forbidden as Error).message).toBe(
      'Адрес находится в зоне, где обслуживание запрещено. Измените адрес или границу исключения.',
    );
    expect(userFacingErrorDetail(forbidden, 'Не удалось рассчитать слоты')).toBe(
      'Адрес находится в зоне, где обслуживание запрещено. Измените адрес или границу исключения.',
    );
    expect(invalidPrice).toBeInstanceOf(ApiError);
    expect((invalidPrice as Error).message).toBe(
      'Для особой цены укажите стоимость доставки и вывоза, а для ограничений удалите цены.',
    );
    expect(userFacingErrorDetail(invalidPrice, 'Не удалось рассчитать слоты')).toBe(
      'Для особой цены укажите стоимость доставки и вывоза, а для ограничений удалите цены.',
    );
  });
});
