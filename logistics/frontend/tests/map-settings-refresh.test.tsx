import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getMapSettings, type MapDisplaySettings } from '../src/api/map-settings';
import { ConfiguredMapCanvas } from '../src/map/ConfiguredMapCanvas';
import type { MapCanvasProps } from '../src/map/MapCanvas';

vi.mock('../src/api/map-settings', () => ({ getMapSettings: vi.fn() }));
vi.mock('../src/map/MapCanvas', () => ({
  MapCanvas: ({ mapSettings }: { mapSettings: MapDisplaySettings }) => (
    <div data-testid="configured-map">
      {mapSettings.provider}:{mapSettings.version}
    </div>
  ),
}));
const standard: MapDisplaySettings = { version: 1, provider: 'STANDARD', yandex_api_key: null };
let receive: ((event: MessageEvent<unknown>) => void) | null;
const close = vi.fn();
beforeEach(() => {
  receive = null;
  close.mockReset();
  vi.mocked(getMapSettings).mockReset().mockResolvedValue(standard);
  vi.stubGlobal(
    'BroadcastChannel',
    class {
      set onmessage(listener: (event: MessageEvent<unknown>) => void) {
        receive = listener;
      }
      close = close;
    },
  );
});
function mount() {
  const client = new QueryClient();
  client.setQueryData(['unrelated-routes'], { version: 1 });
  const props = {} as Omit<MapCanvasProps, 'mapSettings'>;
  const view = render(
    <QueryClientProvider client={client}>
      <ConfiguredMapCanvas {...props} />
    </QueryClientProvider>,
  );
  return { client, ...view };
}
function broadcast(version: number) {
  act(() => receive?.(new MessageEvent('message', { data: { type: 'changed', version } })));
}

describe('map setting updates in an open logistics tab', () => {
  it('switches provider, replaces the key and switches back after admin invalidation', async () => {
    const view = mount();
    expect(await screen.findByTestId('configured-map')).toHaveTextContent('STANDARD:1');
    for (const settings of [
      { version: 2, provider: 'YANDEX' as const, yandex_api_key: 'first-browser-key' },
      { version: 3, provider: 'YANDEX' as const, yandex_api_key: 'replacement-browser-key' },
      { ...standard, version: 4 },
    ]) {
      vi.mocked(getMapSettings).mockResolvedValue(settings);
      broadcast(settings.version);
      await waitFor(() =>
        expect(screen.getByTestId('configured-map')).toHaveTextContent(
          `${settings.provider}:${settings.version}`,
        ),
      );
    }
    expect(view.client.getQueryState(['unrelated-routes'])?.isInvalidated).toBe(false);
    view.unmount();
    expect(close).toHaveBeenCalledOnce();
  });

  it('polls other browsers without requiring BroadcastChannel', async () => {
    vi.stubGlobal('BroadcastChannel', undefined);
    const view = mount();
    await screen.findByTestId('configured-map');
    vi.mocked(getMapSettings).mockResolvedValue({
      version: 2,
      provider: 'YANDEX',
      yandex_api_key: 'browser-key',
    });
    await waitFor(() => expect(screen.getByTestId('configured-map')).toHaveTextContent('YANDEX:2'), {
      timeout: 6500,
    });
    view.unmount();
  }, 8000);

  it('shows a bounded read failure and retries explicitly without a made-up default', async () => {
    vi.mocked(getMapSettings).mockRejectedValue(new Error('Настройки недоступны'));
    const view = mount();
    expect(screen.queryByTestId('configured-map')).not.toBeInTheDocument();
    expect(await screen.findByRole('alert', {}, { timeout: 4500 })).toHaveTextContent('Настройки недоступны');
    expect(getMapSettings).toHaveBeenCalledTimes(3);
    vi.mocked(getMapSettings).mockResolvedValue(standard);
    fireEvent.click(screen.getByRole('button', { name: 'Повторить загрузку настроек карты' }));
    expect(await screen.findByTestId('configured-map')).toHaveTextContent('STANDARD:1');
    view.unmount();
  }, 6500);
});
