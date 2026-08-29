import type { Feature, FeatureCollection, Geometry } from 'geojson';
import type { UUID } from '../../domain/types';

/** Request used by the dispatcher UI to ask the server for feasible customer slots. */
export interface SlotAvailabilityInput {
  warehouse_id: UUID;
  date: string;
  address: string;
  latitude: number;
  longitude: number;
  cabin_count: number;
  site_cabin_capacity: 1 | 2;
  service_duration_minutes?: number;
}

/** Server-owned status of one fixed customer delivery window. */
export type SlotAvailabilityStatus = 'AVAILABLE' | 'UNAVAILABLE';

/** GeoJSON shapes that may be rendered as read-only planning evidence. */
export type SlotPlanningGeoJson = Geometry | Feature | FeatureCollection;

/** One simulated stop returned for explaining the best insertion candidate. */
export interface SlotTimelineStop {
  stop_id?: string;
  type: string;
  label: string;
  address?: string;
  arrival_at?: string;
  service_start?: string;
  service_end?: string;
  departure_at?: string;
  waiting_minutes?: number;
  service_minutes?: number;
  load_before?: number;
  load_after?: number;
  status?: string;
  infeasibility_reason?: string;
}

/** Best server-simulated route insertion for an available customer slot. */
export interface SlotAvailabilityCandidate {
  driver_id?: UUID;
  driver_shift_id?: UUID;
  trip_id?: UUID;
  insert_after_stop_id?: UUID;
  insert_before_stop_id?: UUID;
  estimated_arrival?: string;
  estimated_service_start?: string;
  estimated_finish?: string;
  warehouse_return_time?: string;
  minimum_slack_minutes?: number;
  incremental_travel_minutes?: number;
  incremental_distance?: number;
  waiting_minutes?: number;
  pickup_count?: number;
  affected_stops: string[];
  timeline: SlotTimelineStop[];
  route_before_geojson?: SlotPlanningGeoJson;
  route_after_geojson?: SlotPlanningGeoJson;
  pickup_candidates_geojson?: SlotPlanningGeoJson;
}

/** Feasibility result for one of the three fixed customer delivery windows. */
export interface SlotAvailabilityOption {
  start: string;
  end: string;
  status: SlotAvailabilityStatus;
  candidate_count: number;
  best_candidate?: SlotAvailabilityCandidate;
  reasons: string[];
  explanation: string[];
}

/** Server response for one address/date/day-plan version. */
export interface SlotAvailabilityResponse {
  date: string;
  plan_version: number;
  delivery_price_rubles?: number;
  price_isochrone_minutes?: 60 | 120 | 180 | 240;
  price_zone_id?: UUID;
  price_zone_name?: string;
  trailer_access_allowed: boolean;
  slots: SlotAvailabilityOption[];
}

/** Visibility switches for server-provided planning explanation layers. */
export interface SlotPlanningLayers {
  routeBefore: boolean;
  routeAfter: boolean;
  pickupCandidates: boolean;
}

/** Read-only map presentation derived from the selected server result. */
export interface SlotPlanningMapPresentation {
  active: boolean;
  warehouseId: UUID;
  point: { latitude: number; longitude: number } | null;
  selectedSlot: SlotAvailabilityOption | null;
  layers: SlotPlanningLayers;
}

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function optionalNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function geoJson(value: unknown): SlotPlanningGeoJson | undefined {
  const candidate = record(value);
  const type = candidate?.type;
  if (typeof type !== 'string') return undefined;
  if (!['Feature', 'FeatureCollection', 'Point', 'MultiPoint', 'LineString', 'MultiLineString', 'Polygon', 'MultiPolygon', 'GeometryCollection'].includes(type)) return undefined;
  return candidate as unknown as SlotPlanningGeoJson;
}

function normalizeTimeline(value: unknown): SlotTimelineStop[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item): SlotTimelineStop[] => {
    const raw = record(item);
    if (!raw) return [];
    const type = optionalString(raw.type) ?? optionalString(raw.stop_type);
    if (!type) return [];
    const stopId = optionalString(raw.stop_id) ?? optionalString(raw.id);
    const address = optionalString(raw.address);
    const arrivalAt = optionalString(raw.arrival_at) ?? optionalString(raw.arrival);
    const serviceStart = optionalString(raw.service_start);
    const serviceEnd = optionalString(raw.service_end);
    const departureAt = optionalString(raw.departure_at) ?? optionalString(raw.departure);
    const waitingMinutes = optionalNumber(raw.waiting_minutes);
    const serviceMinutes = optionalNumber(raw.service_minutes);
    const loadBefore = optionalNumber(raw.load_before);
    const loadAfter = optionalNumber(raw.load_after);
    const status = optionalString(raw.status);
    const infeasibilityReason = optionalString(raw.infeasibility_reason);
    return [{
      type,
      label: optionalString(raw.label) ?? optionalString(raw.address) ?? type,
      ...(stopId ? { stop_id: stopId } : {}),
      ...(address ? { address } : {}),
      ...(arrivalAt ? { arrival_at: arrivalAt } : {}),
      ...(serviceStart ? { service_start: serviceStart } : {}),
      ...(serviceEnd ? { service_end: serviceEnd } : {}),
      ...(departureAt ? { departure_at: departureAt } : {}),
      ...(waitingMinutes !== undefined ? { waiting_minutes: waitingMinutes } : {}),
      ...(serviceMinutes !== undefined ? { service_minutes: serviceMinutes } : {}),
      ...(loadBefore !== undefined ? { load_before: loadBefore } : {}),
      ...(loadAfter !== undefined ? { load_after: loadAfter } : {}),
      ...(status ? { status } : {}),
      ...(infeasibilityReason ? { infeasibility_reason: infeasibilityReason } : {}),
    }];
  });
}

function normalizeCandidate(value: unknown): SlotAvailabilityCandidate | undefined {
  const raw = record(value);
  if (!raw) return undefined;
  const strings = {
    driver_id: optionalString(raw.driver_id),
    driver_shift_id: optionalString(raw.driver_shift_id) ?? optionalString(raw.shift_id),
    trip_id: optionalString(raw.trip_id),
    insert_after_stop_id: optionalString(raw.insert_after_stop_id),
    insert_before_stop_id: optionalString(raw.insert_before_stop_id),
    estimated_arrival: optionalString(raw.estimated_arrival),
    estimated_service_start: optionalString(raw.estimated_service_start),
    estimated_finish: optionalString(raw.estimated_finish),
    warehouse_return_time: optionalString(raw.warehouse_return_time),
  };
  const numbers = {
    minimum_slack_minutes: optionalNumber(raw.minimum_slack_minutes),
    incremental_travel_minutes: optionalNumber(raw.incremental_travel_minutes),
    incremental_distance: optionalNumber(raw.incremental_distance) ?? optionalNumber(raw.incremental_distance_meters),
    waiting_minutes: optionalNumber(raw.waiting_minutes),
    pickup_count: optionalNumber(raw.pickup_count),
  };
  const geometries = {
    route_before_geojson: geoJson(raw.route_before_geojson) ?? geoJson(raw.route_before),
    route_after_geojson: geoJson(raw.route_after_geojson) ?? geoJson(raw.route_after),
    pickup_candidates_geojson: geoJson(raw.pickup_candidates_geojson),
  };
  return {
    ...(strings.driver_id ? { driver_id: strings.driver_id } : {}),
    ...(strings.driver_shift_id ? { driver_shift_id: strings.driver_shift_id } : {}),
    ...(strings.trip_id ? { trip_id: strings.trip_id } : {}),
    ...(strings.insert_after_stop_id ? { insert_after_stop_id: strings.insert_after_stop_id } : {}),
    ...(strings.insert_before_stop_id ? { insert_before_stop_id: strings.insert_before_stop_id } : {}),
    ...(strings.estimated_arrival ? { estimated_arrival: strings.estimated_arrival } : {}),
    ...(strings.estimated_service_start ? { estimated_service_start: strings.estimated_service_start } : {}),
    ...(strings.estimated_finish ? { estimated_finish: strings.estimated_finish } : {}),
    ...(strings.warehouse_return_time ? { warehouse_return_time: strings.warehouse_return_time } : {}),
    ...(numbers.minimum_slack_minutes !== undefined ? { minimum_slack_minutes: numbers.minimum_slack_minutes } : {}),
    ...(numbers.incremental_travel_minutes !== undefined ? { incremental_travel_minutes: numbers.incremental_travel_minutes } : {}),
    ...(numbers.incremental_distance !== undefined ? { incremental_distance: numbers.incremental_distance } : {}),
    ...(numbers.waiting_minutes !== undefined ? { waiting_minutes: numbers.waiting_minutes } : {}),
    ...(numbers.pickup_count !== undefined ? { pickup_count: numbers.pickup_count } : {}),
    affected_stops: stringArray(raw.affected_stops),
    timeline: normalizeTimeline(raw.timeline),
    ...(geometries.route_before_geojson ? { route_before_geojson: geometries.route_before_geojson } : {}),
    ...(geometries.route_after_geojson ? { route_after_geojson: geometries.route_after_geojson } : {}),
    ...(geometries.pickup_candidates_geojson ? { pickup_candidates_geojson: geometries.pickup_candidates_geojson } : {}),
  };
}

/** Validate and normalize the deliberately small slot-availability transport shape. */
export function normalizeSlotAvailabilityResponse(value: unknown): SlotAvailabilityResponse {
  const raw = record(value);
  if (!raw || typeof raw.date !== 'string' || typeof raw.plan_version !== 'number' || !Array.isArray(raw.slots)) {
    throw new Error('Backend вернул некорректный расчёт слотов');
  }
  const slots = raw.slots.flatMap((value): SlotAvailabilityOption[] => {
    const slot = record(value);
    if (!slot || typeof slot.start !== 'string' || typeof slot.end !== 'string') return [];
    const status: SlotAvailabilityStatus = slot.status === 'AVAILABLE' ? 'AVAILABLE' : 'UNAVAILABLE';
    const candidate = normalizeCandidate(slot.best_candidate);
    const reasons = stringArray(slot.reasons);
    return [{
      start: slot.start,
      end: slot.end,
      status,
      candidate_count: Math.max(0, Math.trunc(optionalNumber(slot.candidate_count) ?? 0)),
      ...(candidate ? { best_candidate: candidate } : {}),
      reasons: status === 'UNAVAILABLE' && reasons.length === 0 ? ['AVAILABILITY_NOT_CONFIRMED'] : reasons,
      explanation: stringArray(slot.explanation),
    }];
  });
  const deliveryPriceRubles = optionalNumber(raw.delivery_price_rubles);
  const rawIsochroneMinutes = optionalNumber(raw.price_isochrone_minutes);
  const priceIsochroneMinutes = [60, 120, 180, 240].includes(rawIsochroneMinutes ?? -1)
    ? rawIsochroneMinutes as 60 | 120 | 180 | 240
    : undefined;
  const priceZoneId = optionalString(raw.price_zone_id);
  const priceZoneName = optionalString(raw.price_zone_name);
  return {
    date: raw.date,
    plan_version: raw.plan_version,
    ...(deliveryPriceRubles !== undefined ? { delivery_price_rubles: deliveryPriceRubles } : {}),
    ...(priceIsochroneMinutes ? { price_isochrone_minutes: priceIsochroneMinutes } : {}),
    ...(priceZoneId ? { price_zone_id: priceZoneId } : {}),
    ...(priceZoneName ? { price_zone_name: priceZoneName } : {}),
    trailer_access_allowed: raw.trailer_access_allowed !== false,
    slots,
  };
}
