import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../src/api/client';

afterEach(() => vi.unstubAllGlobals());

describe('geocoding transport', () => {
  it('encodes suggestions, selected URI resolution and reverse lookup', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(JSON.stringify([{ id: 'one', title: 'Невский, 1', uri: 'ymapsbm1://geo?1' }]), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ address: 'Санкт-Петербург, Невский проспект, 1', latitude: 59.935, longitude: 30.325 }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ address: 'Санкт-Петербург, Невский проспект, 1', latitude: 59.935, longitude: 30.325 }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    await api.suggestAddresses('Невский 1', { latitude: 59.9, longitude: 30.3 });
    await api.resolveAddressSuggestion('ymapsbm1://geo?1');
    await api.reverseGeocode(59.935, 30.325);

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/geocoding/suggestions?text=%D0%9D%D0%B5%D0%B2%D1%81%D0%BA%D0%B8%D0%B9+1&latitude=59.9&longitude=30.3');
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/geocoding/resolve?uri=ymapsbm1%3A%2F%2Fgeo%3F1');
    expect(fetchMock.mock.calls[2]?.[0]).toBe('/api/geocoding/reverse?latitude=59.935&longitude=30.325');
  });
});
