import { Component, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { isYandexCamera, YANDEX_BRIDGE, type YandexCamera } from './yandex-bridge';
import { loadYandexReactify } from './yandex-sdk';
import './yandex-frame.css';

const origin = window.location.origin;
const send = (type: 'ready' | 'loaded' | 'error') =>
  window.parent.postMessage({ channel: YANDEX_BRIDGE, type }, origin);

/** Report rendering errors without forwarding SDK diagnostics or the browser key. */
class MapErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  componentDidCatch() {
    send('error');
  }
  render() {
    return this.state.failed ? null : this.props.children;
  }
}

let started = false;
let camera: YandexCamera;
let update: (() => void) | undefined;

async function start(apiKey: string) {
  const {
    components: { YMap, YMapDefaultSchemeLayer },
    projection,
  } = await loadYandexReactify(apiKey);
  const root = createRoot(document.getElementById('root')!, {
    onCaughtError: () => send('error'),
    onUncaughtError: () => send('error'),
  });
  update = () =>
    root.render(
      <MapErrorBoundary>
        <YMap
          location={{ center: camera.center, zoom: camera.zoom, duration: 0 }}
          camera={{ azimuth: camera.azimuth, tilt: 0, duration: 0 }}
          projection={projection}
          zoomRange={{ min: 0, max: 25 }}
          zoomRounding="smooth"
          behaviors={[]}
          mode="vector"
          copyrightsPosition="bottom right"
          distributionPosition="bottom left"
        >
          <YMapDefaultSchemeLayer />
        </YMap>
      </MapErrorBoundary>,
    );
  update();
  send('loaded');
}

window.addEventListener('message', (event: MessageEvent<unknown>) => {
  if (event.source !== window.parent || event.origin !== origin) return;
  const message = event.data as {
    channel?: unknown;
    type?: unknown;
    camera?: unknown;
    apiKey?: unknown;
  } | null;
  if (message?.channel !== YANDEX_BRIDGE || !isYandexCamera(message.camera)) return;
  if (
    message.type === 'init' &&
    !started &&
    typeof message.apiKey === 'string' &&
    message.apiKey.trim() &&
    message.apiKey.length <= 256
  ) {
    started = true;
    camera = message.camera;
    void start(message.apiKey).catch(() => send('error'));
  } else if (message.type === 'camera' && started) {
    camera = message.camera;
    update?.();
  }
});
send('ready');
