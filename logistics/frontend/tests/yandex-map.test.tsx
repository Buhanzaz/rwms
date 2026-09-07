import { act, fireEvent, render, screen } from '@testing-library/react';
import type { Map as MapLibreMap } from 'maplibre-gl';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { YandexBaseMap } from '../src/map/YandexBaseMap';
import { YANDEX_BRIDGE, yandexCamera } from '../src/map/yandex-bridge';
import { loadYandexReactify } from '../src/map/yandex-sdk';

function mapFixture() {
  return {
    getContainer: () => ({ clientWidth: 1000, clientHeight: 800 }),
    unproject: vi.fn().mockReturnValue({ lng: 37.62, lat: 55.75 }),
    getZoom: () => 11.4,
    getBearing: () => 45,
    on: vi.fn(),
    off: vi.fn(),
  };
}
function receive(
  frame: HTMLIFrameElement,
  type: string,
  origin = window.location.origin,
  source: MessageEventSource | null = frame.contentWindow,
) {
  void act(() =>
    window.dispatchEvent(
      new MessageEvent('message', { source, origin, data: { channel: YANDEX_BRIDGE, type } }),
    ),
  );
}
afterEach(() => vi.useRealTimers());

describe('Yandex v3 base map bridge', () => {
  it('aligns the full viewport, fractional zoom and bearing with Web Mercator', () => {
    const map = mapFixture();
    const camera = yandexCamera(map as unknown as MapLibreMap);
    expect(map.unproject).toHaveBeenCalledWith([500, 400]);
    expect(camera.center).toEqual([37.62, 55.75]);
    expect(camera.zoom).toBeCloseTo(12.4);
    expect(camera.azimuth).toBeCloseTo(-Math.PI / 4);
  });

  it('sends the key only to its own frame and removes listeners on unmount', () => {
    const map = mapFixture();
    const view = render(<YandexBaseMap map={map as unknown as MapLibreMap} apiKey="browser-key" />);
    const frame = screen.getByTitle<HTMLIFrameElement>('Яндекс Карты');
    expect(frame.src).not.toContain('browser-key');
    expect(frame.src).toContain('yandex-map.html');
    const send = vi.spyOn(frame.contentWindow!, 'postMessage');
    receive(frame, 'ready', 'https://foreign.example');
    receive(frame, 'ready', window.location.origin, window);
    expect(send).not.toHaveBeenCalled();
    receive(frame, 'ready');
    expect(send).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'init',
        apiKey: 'browser-key',
        camera: yandexCamera(map as unknown as MapLibreMap),
      }),
      window.location.origin,
    );
    receive(frame, 'loaded');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    view.unmount();
    expect(map.off).toHaveBeenCalledWith('move', expect.any(Function));
    expect(map.off).toHaveBeenCalledWith('resize', expect.any(Function));
  });

  it('shows SDK errors, retries with a fresh frame and bounds loading time', () => {
    vi.useFakeTimers();
    const map = mapFixture() as unknown as MapLibreMap;
    render(<YandexBaseMap map={map} apiKey="browser-key" />);
    const original = screen.getByTitle<HTMLIFrameElement>('Яндекс Карты');
    receive(original, 'error');
    expect(screen.getByRole('alert')).toHaveTextContent('Яндекс Карты недоступны');
    expect(screen.queryByTitle('Яндекс Карты')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Повторить загрузку Яндекс Карт' }));
    expect(screen.getByTitle('Яндекс Карты')).not.toBe(original);
    void act(() => vi.advanceTimersByTime(20_000));
    expect(screen.getByRole('alert')).toHaveTextContent('Яндекс Карты недоступны');
  });

  it('loads v3 with Reactify and a spherical Mercator projection', async () => {
    const bindTo = vi
      .fn()
      .mockReturnValue({ module: () => ({ YMap: () => null, YMapDefaultSchemeLayer: () => null }) });
    const sdkImport = vi.fn((name: string) =>
      Promise.resolve(name.includes('reactify') ? { reactify: { bindTo } } : { SphericalMercator: class {} }),
    );
    vi.stubGlobal('ymaps3', { ready: Promise.resolve(), import: sdkImport });
    const result = loadYandexReactify('test-browser-key');
    const script = document.head.querySelector<HTMLScriptElement>(
      'script[src^="https://api-maps.yandex.ru/v3/"]',
    )!;
    expect(new URL(script.src).searchParams.get('apikey')).toBe('test-browser-key');
    expect(new URL(script.src).searchParams.get('lang')).toBe('ru_RU');
    script.dispatchEvent(new Event('load'));
    await result;
    expect(sdkImport).toHaveBeenCalledWith('@yandex/ymaps3-reactify');
    expect(sdkImport).toHaveBeenCalledWith('@yandex/ymaps3-spherical-mercator-projection@0.0.1');
    expect(bindTo).toHaveBeenCalledOnce();
    script.remove();
  });

  it('does not put raw SDK errors or credentials in a failure message', async () => {
    const result = loadYandexReactify('private-test-key');
    const assertion = expect(result).rejects.toThrow('Яндекс Карты недоступны.');
    const script = document.head.querySelector<HTMLScriptElement>(
      'script[src^="https://api-maps.yandex.ru/v3/"]',
    )!;
    script.dispatchEvent(new Event('error'));
    await assertion;
    script.remove();
  });
});
