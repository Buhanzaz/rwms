import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createContractorRouteShare } from '../src/features/contractors/contractor-client';

const warehouseId = '11111111-1111-4111-8111-111111111111';
const contractorWorkerId = '22222222-2222-4222-8222-222222222222';
const handoffCommandId = '33333333-3333-4333-8333-333333333333';
const externalTaskIds = [
  '55555555-5555-4555-8555-555555555555',
  '44444444-4444-4444-8444-444444444444',
];

describe('contractor route-share client', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('preserves canonical task order and the handoff idempotency identity on stable replay', async () => {
    const response = {
      id: '66666666-6666-4666-8666-666666666666',
      version: 0,
      warehouseId,
      contractorWorkerId,
      expiresAt: '2026-09-12T00:00:00.000Z',
      revokedAt: null,
      createdAt: '2026-09-01T09:00:00.000Z',
      publicPath: '/contractor-routes/signed-token',
      externalTaskIds,
    };
    const fetchMock = vi.fn<typeof fetch>(() => Promise.resolve(new Response(JSON.stringify(response), {
      status: 201,
      headers: { 'Content-Type': 'application/json' },
    })));
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      contractorWorkerId,
      expiresAt: '2026-09-12T00:00:00.000Z',
      externalTaskIds,
    };

    await expect(createContractorRouteShare('user-token', warehouseId, input, handoffCommandId)).resolves.toEqual(response);
    await expect(createContractorRouteShare('user-token', warehouseId, input, handoffCommandId)).resolves.toEqual(response);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    for (const [url, init] of fetchMock.mock.calls) {
      expect(url).toBe(`/api/logistics/v1/warehouses/${warehouseId}/contractor-route-shares`);
      expect(init?.method).toBe('POST');
      const headers = new Headers(init?.headers);
      expect(headers.get('Authorization')).toBe('Bearer user-token');
      expect(headers.get('Idempotency-Key')).toBe(handoffCommandId);
      expect(headers.get('Content-Type')).toBe('application/json');
      if (typeof init?.body !== 'string') throw new Error('Expected a JSON route-share request');
      expect(JSON.parse(init.body)).toEqual(input);
    }
    expect(fetchMock.mock.calls[1]?.[1]?.body).toBe(fetchMock.mock.calls[0]?.[1]?.body);
  });
});
