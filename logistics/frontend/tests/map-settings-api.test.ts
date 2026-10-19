import { describe, expect, it, vi } from 'vitest';
import { getMapSettings } from '../src/api/map-settings';
import { setSimulatorAccessTokenProvider } from '../src/api/client';

describe('global logistics map configuration', () => {
  it('uses the operator API with a fresh Bearer token and no cached credentials', async () => {
    const settings = { version: 2, provider: 'YANDEX', yandex_api_key: 'browser-test-key' };
    const fetch = vi.fn().mockResolvedValue(Response.json(settings));
    vi.stubGlobal('fetch', fetch);
    expect(await getMapSettings()).toEqual(settings);
    expect(fetch).toHaveBeenCalledWith(
      '/api/map-settings',
      expect.objectContaining({
        cache: 'no-store',
        headers: { Authorization: 'Bearer test-user-access-token' },
      }),
    );
    setSimulatorAccessTokenProvider(() => Promise.resolve(null));
    await expect(getMapSettings()).rejects.toMatchObject({ status: 401 });
    expect(fetch).toHaveBeenCalledOnce();
  });

  it.each([
    {},
    { version: 0, provider: 'STANDARD', yandex_api_key: null },
    { version: 1, provider: 'UNKNOWN', yandex_api_key: null },
    { version: 1, provider: 'YANDEX', yandex_api_key: null },
    { version: 1, provider: 'YANDEX', yandex_api_key: ' ' },
    { version: 1, provider: 'STANDARD', yandex_api_key: 'unexpected-key' },
  ])('rejects incomplete configuration instead of inventing a map', async (value) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(value)));
    await expect(getMapSettings()).rejects.toThrow('некорректные настройки');
  });

  it('surfaces the owner service failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(Response.json({ code: 'MAP_SETTINGS_UNAVAILABLE' }, { status: 503 })),
    );
    await expect(getMapSettings()).rejects.toMatchObject({ status: 503 });
  });
});
