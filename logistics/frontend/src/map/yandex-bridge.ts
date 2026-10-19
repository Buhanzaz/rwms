import type { Map as MapLibreMap } from 'maplibre-gl';

export const YANDEX_BRIDGE = 'rwms-yandex-map-v3';
export const YANDEX_CREDITS_HEIGHT = 48;

/** Camera expressed in Yandex's 256-pixel world and radians, in Web Mercator. */
export interface YandexCamera {
  center: [number, number];
  zoom: number;
  azimuth: number;
}

export function yandexCamera(map: MapLibreMap): YandexCamera {
  const container = map.getContainer();
  // getCenter() points to the padded viewport; the underlay fills the entire container.
  const center = map.unproject([container.clientWidth / 2, container.clientHeight / 2]);
  return {
    center: [center.lng, center.lat],
    zoom: map.getZoom() + 1,
    azimuth: (-map.getBearing() * Math.PI) / 180,
  };
}

export function isYandexCamera(value: unknown): value is YandexCamera {
  const camera = value as Partial<YandexCamera> | null;
  return (
    !!camera &&
    Array.isArray(camera.center) &&
    camera.center.length === 2 &&
    camera.center.every(Number.isFinite) &&
    Number.isFinite(camera.zoom) &&
    Number.isFinite(camera.azimuth)
  );
}
