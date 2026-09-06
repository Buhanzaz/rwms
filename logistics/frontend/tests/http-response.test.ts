import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, setSimulatorAccessTokenProvider } from '../src/api/client';
import { readApiResponse } from '../src/api/http-response';
import { loadWarehouseKinds } from '../src/api/warehouse-directory';
import { listContractorDrivers } from '../src/features/contractors/contractor-client';
import { loadTransferDrivers } from '../src/features/transfers/transfer-client';

beforeEach(() => { setSimulatorAccessTokenProvider(() => Promise.resolve('token')); });
afterEach(() => { vi.unstubAllGlobals(); setSimulatorAccessTokenProvider(null); });

const adapters = [
  ['planner', () => api.listWarehouses()],
  ['transfer', () => loadTransferDrivers('warehouse')],
  ['contractor', () => listContractorDrivers('token', 'warehouse')],
  ['warehouse directory', () => loadWarehouseKinds()],
] as const;

describe.each(adapters)('%s HTTP boundary', (_name, request) => {
  it.each([401, 409])('preserves HTTP %s and the domain problem code', async (status) => {
    const problem = { status, code: 'VERSION_CONFLICT', detail: 'Данные изменились', instance: '/command' };
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(problem), {
      status, headers: { 'Content-Type': 'application/problem+json' },
    }));
    vi.stubGlobal('fetch', fetch);
    const error = await request().catch((failure: unknown) => failure);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status, code: problem.code, problem });
    expect(fetch).toHaveBeenCalledOnce();
  });

  it('keeps HTTP failure status when the body is malformed', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<broken>', { status: 409 })));
    await expect(request()).rejects.toMatchObject({ status: 409, code: null, problem: null });
  });

  it('rejects malformed success JSON as a failed response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{broken', {
      headers: { 'Content-Type': 'application/json' },
    })));
    await expect(request()).rejects.toMatchObject({ status: 200, code: null, problem: null });
  });

  it('surfaces a network failure without retrying', async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    vi.stubGlobal('fetch', fetch);
    await expect(request()).rejects.toMatchObject({ status: 0 });
    expect(fetch).toHaveBeenCalledOnce();
  });

  it('preserves transport cancellation', async () => {
    const abort = new DOMException('Cancelled', 'AbortError');
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(abort));
    await expect(request()).rejects.toBe(abort);
  });
});

it('accepts an empty 204 response without parsing JSON', async () => {
  await expect(readApiResponse<void>(new Response(null, { status: 204 }), 'Не удалось удалить'))
    .resolves.toBeUndefined();
});

it('preserves cancellation during both success and problem body decoding', async () => {
  const abort = new DOMException('Cancelled', 'AbortError');
  for (const status of [200, 409]) {
    const response = new Response('{}', { status });
    vi.spyOn(response, 'json').mockRejectedValue(abort);
    await expect(readApiResponse(response, 'Не удалось загрузить')).rejects.toBe(abort);
  }
});

it('passes the directory abort signal to fetch', async () => {
  const controller = new AbortController();
  const abort = new Error('caller abort reason');
  controller.abort(abort);
  const fetch = vi.fn().mockRejectedValue(abort);
  vi.stubGlobal('fetch', fetch);
  await expect(loadWarehouseKinds(controller.signal)).rejects.toBe(abort);
  expect(fetch).toHaveBeenCalledWith('/api/warehouse/v1/warehouses', expect.objectContaining({ signal: controller.signal }));
});
