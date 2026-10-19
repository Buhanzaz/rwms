import { useEffect, useRef, useState } from 'react';
import type { Map as MapLibreMap } from 'maplibre-gl';
import { Button } from '../components/ui';
import { YANDEX_BRIDGE, yandexCamera } from './yandex-bridge';

/** Keeps all operational interaction in MapLibre while the v3 SDK draws the base. */
export function YandexBaseMap({ map, apiKey }: { map: MapLibreMap; apiKey: string }) {
  const [attempt, setAttempt] = useState(0);
  return <YandexFrame key={attempt} map={map} apiKey={apiKey} onRetry={() => setAttempt(attempt + 1)} />;
}

function YandexFrame({ map, apiKey, onRetry }: { map: MapLibreMap; apiKey: string; onRetry: () => void }) {
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const [status, setStatus] = useState<'loading' | 'loaded' | 'error'>('loading');

  useEffect(() => {
    const frame = iframeRef.current;
    if (!frame) return;
    const origin = window.location.origin;
    let ready = false;
    let animationFrame = 0;
    const timeout = window.setTimeout(() => setStatus('error'), 20_000);
    const sendCamera = () => {
      animationFrame = 0;
      if (ready)
        frame.contentWindow?.postMessage(
          { channel: YANDEX_BRIDGE, type: 'camera', camera: yandexCamera(map) },
          origin,
        );
    };
    const scheduleCamera = () => {
      if (!animationFrame) animationFrame = window.requestAnimationFrame(sendCamera);
    };
    const receive = (event: MessageEvent<unknown>) => {
      if (event.source !== frame.contentWindow || event.origin !== origin) return;
      const message = event.data as { channel?: unknown; type?: unknown } | null;
      if (message?.channel !== YANDEX_BRIDGE) return;
      if (message.type === 'ready') {
        ready = true;
        frame.contentWindow?.postMessage(
          { channel: YANDEX_BRIDGE, type: 'init', apiKey, camera: yandexCamera(map) },
          origin,
        );
      } else if (message.type === 'loaded' || message.type === 'error') {
        window.clearTimeout(timeout);
        setStatus(message.type);
      }
    };
    window.addEventListener('message', receive);
    map.on('move', scheduleCamera);
    map.on('resize', scheduleCamera);
    return () => {
      window.clearTimeout(timeout);
      window.cancelAnimationFrame(animationFrame);
      window.removeEventListener('message', receive);
      map.off('move', scheduleCamera);
      map.off('resize', scheduleCamera);
    };
  }, [apiKey, map]);

  return (
    <>
      {status !== 'error' ? (
        <iframe
          ref={iframeRef}
          className="yandex-map-frame"
          title="Яндекс Карты"
          src={`${import.meta.env.BASE_URL}yandex-map.html`}
          referrerPolicy="strict-origin"
          onError={() => setStatus('error')}
        />
      ) : null}
      {status === 'loading' ? (
        <div className="map-overlay map-settings-error" role="status">
          Подключаем Яндекс Карты…
        </div>
      ) : null}
      {status === 'error' ? (
        <div className="map-overlay map-settings-error" role="alert">
          <span>
            Яндекс Карты недоступны. Проверьте ключ JavaScript API 3.0 и разрешённый домен в админке.
          </span>
          <Button onClick={onRetry}>Повторить загрузку Яндекс Карт</Button>
        </div>
      ) : null}
    </>
  );
}
