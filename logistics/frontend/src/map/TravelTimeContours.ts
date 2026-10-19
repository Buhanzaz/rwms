import type { Feature, MultiPolygon, Polygon } from 'geojson';
import type maplibregl from 'maplibre-gl';
import type {
  TravelTimeContourCollection,
  TravelTimeContourMinutes,
  TravelTimeContourProperties,
} from '../api/client';
import type { UUID, Warehouse } from '../domain/types';

/** Map source shared by the configurable hourly contour layers. */
export const TRAVEL_TIME_CONTOUR_SOURCE_ID = 'rwms-travel-time-contours' as const;

/** Presentation of one travel-time contour in the map and legend. */
export interface TravelTimeContourStyle {
  minutes: TravelTimeContourMinutes;
  label: string;
  color: string;
  opacity: number;
}

export const SUPPORTED_TRAVEL_TIME_CONTOUR_MINUTES = Array.from({ length: 12 }, (_, index) => (index + 1) * 60);

/** Build stable inner-to-outer visual styles for the configured hourly tiers. */
export function travelTimeContourStyles(minutes: readonly number[]): TravelTimeContourStyle[] {
  return [...new Set(minutes)].sort((left, right) => left - right).map((value, index, values) => {
    const ratio = values.length <= 1 ? 0 : index / (values.length - 1);
    const hue = Math.round(160 + ratio * 55);
    const hours = value / 60;
    return {
      minutes: value,
      label: `${hours} ${hours === 1 ? 'час' : hours < 5 ? 'часа' : 'часов'}`,
      color: `hsl(${hue} 62% ${Math.round(58 - ratio * 18)}%)`,
      opacity: Math.max(0.07, 0.22 - ratio * 0.13),
    };
  });
}

const ALL_TRAVEL_TIME_CONTOUR_STYLES = travelTimeContourStyles(SUPPORTED_TRAVEL_TIME_CONTOUR_MINUTES);

/** Warehouse layer IDs in outer-to-inner render order, behind operational overlays. */
export const WAREHOUSE_TRAVEL_TIME_CONTOUR_LAYER_IDS = [...ALL_TRAVEL_TIME_CONTOUR_STYLES]
  .reverse()
  .map((style) => `${TRAVEL_TIME_CONTOUR_SOURCE_ID}-depot-${style.minutes}`);

/** Selected-task layer IDs in outer-to-inner render order, behind operational overlays. */
export const TASK_TRAVEL_TIME_CONTOUR_LAYER_IDS = [...ALL_TRAVEL_TIME_CONTOUR_STYLES]
  .reverse()
  .map((style) => `${TRAVEL_TIME_CONTOUR_SOURCE_ID}-task-${style.minutes}`);

/** Supported sources from which the visual travel-time estimate can begin. */
export type TravelTimeContourOriginKind = 'DEPOT' | 'TASK';

/** One deduplicated point for which the map requests visual travel-time contours. */
export interface TravelTimeContourOrigin {
  kind: TravelTimeContourOriginKind;
  id: UUID;
  name: string;
  latitude: number;
  longitude: number;
}

/** Client-only origin identity added without changing the provider geometry. */
export interface TravelTimeContourFeatureProperties extends TravelTimeContourProperties {
  origin_kind: TravelTimeContourOriginKind;
  origin_id: UUID;
  origin_name: string;
}

/** Explicit non-blocking lifecycle shown next to the layer switches. */
export type TravelTimeContourLayerState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'loaded'; warehouseCount: number; taskCount: number }
  | { status: 'unavailable'; error: string };

function coordinateKey(origin: TravelTimeContourOrigin): string {
  const latitude = Object.is(origin.latitude, -0) ? 0 : origin.latitude;
  const longitude = Object.is(origin.longitude, -0) ? 0 : origin.longitude;
  return `${latitude}:${longitude}`;
}

function originOrder(left: TravelTimeContourOrigin, right: TravelTimeContourOrigin): number {
  const leftKind = left.kind === 'DEPOT' ? 0 : 1;
  const rightKind = right.kind === 'DEPOT' ? 0 : 1;
  return leftKind - rightKind
    || left.id.localeCompare(right.id);
}

/** Derive only the explicitly enabled warehouse and selected-task contour origins. */
export function deriveTravelTimeContourOrigins(
  warehouses: readonly Warehouse[],
  task: TravelTimeContourOrigin | null,
  warehouseIsochronesVisible: boolean,
  taskIsochronesVisible: boolean,
): TravelTimeContourOrigin[] {
  const candidates: TravelTimeContourOrigin[] = [
    ...(warehouseIsochronesVisible ? warehouses.map((warehouse) => ({
      kind: 'DEPOT' as const,
      id: warehouse.id,
      name: warehouse.name,
      latitude: warehouse.latitude,
      longitude: warehouse.longitude,
    })) : []),
    ...(taskIsochronesVisible && task ? [task] : []),
  ].sort(originOrder);

  const coordinates = new Set<string>();
  return candidates.filter((origin) => {
    const key = coordinateKey(origin);
    if (coordinates.has(key)) return false;
    coordinates.add(key);
    return true;
  });
}

/** Build separately switchable warehouse/task fills from outer to inner. */
export function travelTimeContourLayerSpecifications(): maplibregl.FillLayerSpecification[] {
  return [...ALL_TRAVEL_TIME_CONTOUR_STYLES].reverse().flatMap((style) => ([
    {
      id: `${TRAVEL_TIME_CONTOUR_SOURCE_ID}-depot-${style.minutes}`,
      type: 'fill' as const,
      source: TRAVEL_TIME_CONTOUR_SOURCE_ID,
      filter: ['all', ['==', ['get', 'contour_minutes'], style.minutes], ['==', ['get', 'origin_kind'], 'DEPOT']],
      layout: { visibility: 'none' as const },
      paint: {
        'fill-color': style.color,
        'fill-opacity': style.opacity,
        'fill-outline-color': style.color,
      },
    },
    {
      id: `${TRAVEL_TIME_CONTOUR_SOURCE_ID}-task-${style.minutes}`,
      type: 'fill' as const,
      source: TRAVEL_TIME_CONTOUR_SOURCE_ID,
      filter: ['all', ['==', ['get', 'contour_minutes'], style.minutes], ['==', ['get', 'origin_kind'], 'TASK']],
      layout: { visibility: 'none' as const },
      paint: {
        'fill-color': '#c084fc',
        'fill-opacity': Math.min(0.25, style.opacity + 0.03),
        'fill-outline-color': '#c084fc',
      },
    },
  ]));
}

/** Attach one depot or route-front identity to already validated provider features. */
export function travelTimeContourFeaturesForOrigin(
  collection: TravelTimeContourCollection,
  origin: TravelTimeContourOrigin,
): Array<Feature<Polygon | MultiPolygon, TravelTimeContourFeatureProperties>> {
  return collection.features.map((feature) => ({
    ...feature,
    properties: {
      ...feature.properties,
      origin_kind: origin.kind,
      origin_id: origin.id,
      origin_name: origin.name,
    },
  }));
}
