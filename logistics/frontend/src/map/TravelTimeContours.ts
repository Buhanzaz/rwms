import type { Feature, MultiPolygon, Polygon } from 'geojson';
import type maplibregl from 'maplibre-gl';
import type {
  TravelTimeContourCollection,
  TravelTimeContourMinutes,
  TravelTimeContourProperties,
} from '../api/client';
import type { DriverRoute, RoutePlan, RouteStop, UUID, Warehouse, Zone } from '../domain/types';

/** Map source shared by the fixed one-to-four-hour contour layers. */
export const TRAVEL_TIME_CONTOUR_SOURCE_ID = 'rwms-travel-time-contours' as const;

/** Presentation of one travel-time contour in the map and legend. */
export interface TravelTimeContourStyle {
  minutes: TravelTimeContourMinutes;
  label: string;
  color: string;
  opacity: number;
}

/** Inner-to-outer legend order for the four fixed visual truck estimates. */
export const TRAVEL_TIME_CONTOUR_STYLES: readonly TravelTimeContourStyle[] = [
  { minutes: 60, label: '1 час', color: '#45d6b0', opacity: 0.22 },
  { minutes: 120, label: '2 часа', color: '#38a7c7', opacity: 0.17 },
  { minutes: 180, label: '3 часа', color: '#3b82b8', opacity: 0.13 },
  { minutes: 240, label: '4 часа', color: '#315f96', opacity: 0.1 },
];

/** Layer IDs in outer-to-inner render order, behind operational overlays. */
export const TRAVEL_TIME_CONTOUR_LAYER_IDS = [...TRAVEL_TIME_CONTOUR_STYLES]
  .reverse()
  .map((style) => `${TRAVEL_TIME_CONTOUR_SOURCE_ID}-${style.minutes}`);

/** Supported sources from which the visual travel-time estimate can begin. */
export type TravelTimeContourOriginKind = 'DEPOT' | 'ROUTE_FRONT';

/** One deduplicated point for which the map requests visual travel-time contours. */
export interface TravelTimeContourOrigin {
  kind: TravelTimeContourOriginKind;
  id: UUID;
  name: string;
  latitude: number;
  longitude: number;
  driverShiftId?: UUID;
}

/** Client-only origin identity added without changing the provider geometry. */
export interface TravelTimeContourFeatureProperties extends TravelTimeContourProperties {
  origin_kind: TravelTimeContourOriginKind;
  origin_id: UUID;
  origin_name: string;
  driver_shift_id?: UUID;
}

/** Explicit non-blocking lifecycle shown by the map status line. */
export type TravelTimeContourLayerState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'loaded'; depotCount: number; routeFrontCount: number }
  | { status: 'unavailable'; error: string };

function deliveryStopChronology(
  left: { cycleStart: string; cycleSequence: number; stop: RouteStop },
  right: { cycleStart: string; cycleSequence: number; stop: RouteStop },
): number {
  return left.cycleStart.localeCompare(right.cycleStart)
    || left.cycleSequence - right.cycleSequence
    || left.stop.planned_departure.localeCompare(right.stop.planned_departure)
    || left.stop.planned_arrival.localeCompare(right.stop.planned_arrival)
    || left.stop.sequence - right.stop.sequence
    || left.stop.id.localeCompare(right.stop.id);
}

function routeFrontForDriver(route: DriverRoute): TravelTimeContourOrigin | null {
  const latestDelivery = route.cycles
    .flatMap((cycle) => cycle.stops
      .filter((stop) => stop.stop_type === 'DELIVERY')
      .map((stop) => ({ cycleStart: cycle.planned_start, cycleSequence: cycle.sequence, stop })))
    .sort(deliveryStopChronology)
    .at(-1)?.stop;
  if (!latestDelivery) return null;
  return {
    kind: 'ROUTE_FRONT',
    id: latestDelivery.id,
    name: `${route.driver_name} · последняя доставка`,
    latitude: latestDelivery.latitude,
    longitude: latestDelivery.longitude,
    driverShiftId: route.driver_shift_id,
  };
}

function coordinateKey(origin: TravelTimeContourOrigin): string {
  const latitude = Object.is(origin.latitude, -0) ? 0 : origin.latitude;
  const longitude = Object.is(origin.longitude, -0) ? 0 : origin.longitude;
  return `${latitude}:${longitude}`;
}

function originOrder(left: TravelTimeContourOrigin, right: TravelTimeContourOrigin): number {
  const leftKind = left.kind === 'DEPOT' ? 0 : 1;
  const rightKind = right.kind === 'DEPOT' ? 0 : 1;
  return leftKind - rightKind
    || left.id.localeCompare(right.id)
    || (left.driverShiftId ?? '').localeCompare(right.driverShiftId ?? '');
}

/** Return every depot and zone vertex that must be visible when a scenario is first opened. */
export function scenarioViewportCoordinates(
  warehouses: readonly Warehouse[],
  zones: readonly Zone[],
): Array<[number, number]> {
  const coordinates: Array<[number, number]> = warehouses.map((warehouse) => [
    warehouse.longitude,
    warehouse.latitude,
  ]);
  zones.forEach((zone) => {
    const polygons = zone.geometry.type === 'Polygon'
      ? [zone.geometry.coordinates]
      : zone.geometry.coordinates;
    polygons.forEach((polygon) => polygon.forEach((ring) => ring.forEach((position) => {
      const longitude = position[0];
      const latitude = position[1];
      if (typeof longitude === 'number' && typeof latitude === 'number'
        && Number.isFinite(longitude) && Number.isFinite(latitude)) {
        coordinates.push([longitude, latitude]);
      }
    })));
  });
  return coordinates;
}

/** Derive stable depot and forward-delivery route-front origins for one visible plan. */
export function deriveTravelTimeContourOrigins(
  warehouses: readonly Warehouse[],
  plan: RoutePlan | null,
): TravelTimeContourOrigin[] {
  const candidates: TravelTimeContourOrigin[] = [
    ...warehouses.map((warehouse) => ({
      kind: 'DEPOT' as const,
      id: warehouse.id,
      name: warehouse.name,
      latitude: warehouse.latitude,
      longitude: warehouse.longitude,
    })),
    ...(plan?.driver_routes
      .map(routeFrontForDriver)
      .filter((origin): origin is TravelTimeContourOrigin => origin !== null) ?? []),
  ].sort(originOrder);

  const coordinates = new Set<string>();
  return candidates.filter((origin) => {
    const key = coordinateKey(origin);
    if (coordinates.has(key)) return false;
    coordinates.add(key);
    return true;
  });
}

/** Build four filtered fill layers from outer to inner so nested areas remain visible. */
export function travelTimeContourLayerSpecifications(): maplibregl.FillLayerSpecification[] {
  return [...TRAVEL_TIME_CONTOUR_STYLES].reverse().map((style) => ({
    id: `${TRAVEL_TIME_CONTOUR_SOURCE_ID}-${style.minutes}`,
    type: 'fill',
    source: TRAVEL_TIME_CONTOUR_SOURCE_ID,
    filter: ['==', ['get', 'contour_minutes'], style.minutes],
    layout: { visibility: 'none' },
    paint: {
      'fill-color': style.color,
      'fill-opacity': style.opacity,
      'fill-outline-color': style.color,
    },
  }));
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
      ...(origin.driverShiftId ? { driver_shift_id: origin.driverShiftId } : {}),
    },
  }));
}

/** Describe contour availability without presenting visual estimates as planner authority. */
export function travelTimeContourStatusText(
  state: TravelTimeContourLayerState,
  visible: boolean,
): string | null {
  if (!visible || state.status === 'idle') return null;
  if (state.status === 'loading') return 'Изохроны складов и точек маршрута загружаются';
  if (state.status === 'unavailable') return `Изохроны складов и точек маршрута недоступны: ${state.error}`;
  return `Изохроны 1–4 ч · складов: ${state.depotCount} · точек маршрута: ${state.routeFrontCount}`;
}
