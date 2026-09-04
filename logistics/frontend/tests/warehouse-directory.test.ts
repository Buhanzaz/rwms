import { afterEach, describe, expect, it, vi } from 'vitest';
import { loadWarehouseKinds } from '../src/api/warehouse-directory';
import { setSimulatorAccessTokenProvider } from '../src/api/client';

const id = '11111111-1111-4111-8111-111111111111';
const record = { id, representative: false, production: true, mainWarehouse: true };

afterEach(() => { vi.unstubAllGlobals(); setSimulatorAccessTokenProvider(() => Promise.resolve('test-token')); });

describe('canonical warehouse kind directory', () => {
  it('uses the public same-origin warehouse endpoint and a renewed bearer token', async () => {
    setSimulatorAccessTokenProvider(() => Promise.resolve('fresh-token'));
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify([record])));
    vi.stubGlobal('fetch', fetch);
    const controller = new AbortController();
    expect((await loadWarehouseKinds(controller.signal)).get(id)).toEqual(record);
    expect(fetch).toHaveBeenCalledWith('/api/warehouse/v1/warehouses', expect.objectContaining({ signal: controller.signal, headers: { Accept: 'application/json', Authorization: 'Bearer fresh-token' } }));
  });

  it('does not request the directory without an authenticated session', async () => {
    setSimulatorAccessTokenProvider(() => Promise.resolve(null));
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    await expect(loadWarehouseKinds()).rejects.toMatchObject({ status: 401 });
    expect(fetch).not.toHaveBeenCalled();
  });

  it.each([
    [{ id, representative: false }],
    [record, record],
    [{ ...record, production: 'false' }],
    [{ ...record, representative: true }],
    [{ ...record, production: false, mainWarehouse: false }],
  ])('rejects missing, duplicate or malformed type data: %j', async (...rows) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(rows))));
    await expect(loadWarehouseKinds()).rejects.toThrow('неподтверждённые типы');
  });

  it('surfaces directory failure without inventing a warehouse type', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })));
    await expect(loadWarehouseKinds()).rejects.toMatchObject({ status: 503 });
  });
});
