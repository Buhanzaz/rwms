import type { Feature, FeatureCollection, Geometry, MultiPolygon, Polygon } from 'geojson';
import type {
  Driver,
  DriverShift,
  LogisticsRequest,
  PlanningSettings,
  IsochroneTariff,
  RequestDateOption,
  RoutePlan,
  RoutingCargoPlacementSnapshot,
  RoutingProfileSnapshot,
  AvailableWarehouse,
  Trailer,
  UUID,
  Vehicle,
  VehicleLoadConfigurationType,
  WarehouseWorkspace,
} from '../domain/types';
import {
  normalizeOptimizationRun,
  normalizeRoutePlan,
  normalizeWarehouse,
  normalizeValidationResult,
  normalizeWorkspace,
  type RawOptimizationRun,
  type RawRoutePlan,
  type RawWarehouse,
} from './mappers';
import {
  normalizeSlotAvailabilityResponse,
  type SlotAvailabilityInput,
} from '../features/slot-availability/types';

const configuredApiPrefix = import.meta.env.VITE_API_BASE_URL?.trim();
const API_PREFIX = configuredApiPrefix?.replace(/\/+$/, '') || '/api';

export interface ProblemDetails {
  type?: unknown;
  title?: unknown;
  status?: unknown;
  detail?: unknown;
  instance?: unknown;
  code?: unknown;
  errors?: Record<string, string[]>;
  failures?: unknown;
}

function problemMessage(problem: ProblemDetails | null, fallback: string): string {
  if (typeof problem?.detail === 'string') return problem.detail;
  if (Array.isArray(problem?.detail)) {
    const messages: string[] = [];
    for (const item of problem.detail as unknown[]) {
      if (!item || typeof item !== 'object') continue;
      const message = (item as Record<string, unknown>).msg;
      if (typeof message === 'string') messages.push(message);
    }
    if (messages.length) return messages.join('; ');
  }
  return typeof problem?.title === 'string' ? problem.title : fallback;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string | null;
  readonly problem: ProblemDetails | null;

  constructor(status: number, problem: ProblemDetails | null, fallback: string) {
    super(problemMessage(problem, fallback));
    this.name = 'ApiError';
    this.status = status;
    this.code = typeof problem?.code === 'string' ? problem.code : null;
    this.problem = problem;
  }
}

async function parseResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let problem: ProblemDetails | null = null;
    try {
      const value: unknown = await response.json();
      problem = value && typeof value === 'object' ? value : null;
    } catch {
      problem = null;
    }
    throw new ApiError(response.status, problem, `HTTP ${response.status}`);
  }
  if (response.status === 204) return undefined as T;
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes('application/json')) return (await response.text()) as T;
  return (await response.json()) as T;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  headers.set('Accept', 'application/json, application/problem+json');
  try {
    const response = await fetch(`${API_PREFIX}${path}`, { ...init, headers });
    return await parseResponse<T>(response);
  } catch (error: unknown) {
    if (error instanceof ApiError) throw error;
    if (init.signal?.aborted) throw error;
    throw new ApiError(0, null, 'Backend недоступен. Проверьте контейнер и соединение.');
  }
}

export type TruckRestrictionCategory =
  | 'HGV_ACCESS'
  | 'MAX_HEIGHT'
  | 'MAX_WIDTH'
  | 'MAX_LENGTH'
  | 'MAX_WEIGHT'
  | 'MAX_AXLE_LOAD'
  | 'CONDITIONAL'
  | 'TRAILER_ACCESS';

export type TruckRestrictionSupportStatus = 'SUPPORTED' | 'PARTIAL' | 'UNSUPPORTED';

export interface TruckRestrictionProperties {
  osm_type: 'node' | 'way';
  osm_id: number;
  category: TruckRestrictionCategory;
  primary_tag: string;
  value: string;
  tags: Record<string, string>;
  support_status: TruckRestrictionSupportStatus;
}

export type TruckRestrictionFeature = Feature<Geometry, TruckRestrictionProperties>;

export interface TruckRestrictionMetadata {
  osm_data_version: string;
  count: number;
  truncated: boolean;
  generated_at: string | null;
}

export interface TruckRestrictionCollection extends FeatureCollection<Geometry, TruckRestrictionProperties> {
  metadata: TruckRestrictionMetadata;
}

export interface TruckRestrictionBounds {
  west: number;
  south: number;
  east: number;
  north: number;
}

/** One of the fixed Valhalla truck travel-time contours exposed by the backend. */
export type TravelTimeContourMinutes = number;

/** Stable properties attached to one validated travel-time area. */
export interface TravelTimeContourProperties {
  contour_minutes: TravelTimeContourMinutes;
}

/** One Polygon/MultiPolygon truck isochrone returned by the private Valhalla adapter. */
export type TravelTimeContourFeature = Feature<Polygon | MultiPolygon, TravelTimeContourProperties>;

/** Provider provenance and requested origin echoed by the read-only endpoint. */
export interface TravelTimeContourMetadata {
  source: 'valhalla';
  costing: 'truck';
  origin: { latitude: number; longitude: number };
  contours_minutes: TravelTimeContourMinutes[];
  osm_data_version: string;
}

/** Validated GeoJSON response containing all one-to-four-hour visual estimates. */
export interface TravelTimeContourCollection extends FeatureCollection<Polygon | MultiPolygon, TravelTimeContourProperties> {
  metadata: TravelTimeContourMetadata;
}

/** One provider suggestion kept unresolved until the operator selects it. */
export interface AddressSuggestion {
  id: string;
  title: string;
  subtitle?: string;
  address?: string;
  uri: string;
}

/** Address and WGS84 point resolved by the server-side geocoder. */
export interface GeocodedAddress {
  address: string;
  latitude: number;
  longitude: number;
}

function jsonBody(value: unknown): string {
  return JSON.stringify(value);
}

/** Persisted acceptance state for one warehouse and planning date. */
export interface PlanningDayStatus {
  warehouse_id: UUID;
  date: string;
  accepting_requests: boolean;
  closed_at: string | null;
  closed_by: string | null;
  plan_id: UUID | null;
}

export interface WarehouseUpdateInput {
  loading_minutes?: number;
  unloading_minutes?: number;
  turnaround_minutes?: number;
  working_day_start?: string;
  working_day_end?: string;
  default_planning_date?: string;
  seed?: number;
  settings?: Partial<PlanningSettings>;
  isochrone_tariffs?: IsochroneTariff[];
}

/** Request body for generating reproducible workload in one warehouse. */
export interface WorkloadGenerationInput {
  start_date: string;
  days: number;
  deliveries_per_day: number;
  pickups_per_day: number;
  alternative_dates_count: number;
  cargo_length_mm: number;
  cargo_width_mm: number;
  cargo_height_mm: number;
  cargo_weight_kg: number;
  seed: number;
}

/** Daily breakdown returned by the workload-generation endpoint. */
export interface WorkloadGenerationDailyCount {
  date: string;
  deliveries: number;
  pickups: number;
}

/** Result of a workload-generation command. */
export interface WorkloadGenerationResult {
  warehouse_id: UUID;
  seed: number;
  start_date: string;
  end_date: string;
  created_requests: number;
  created_deliveries: number;
  created_pickups: number;
  replaced_requests: number;
  deleted_plans: number;
  daily_counts: WorkloadGenerationDailyCount[];
  auto_plan_run_ids?: UUID[];
  auto_plan_ids?: UUID[];
}

/** Result of deleting generator-owned workload for one planning day. */
export interface GeneratedWorkloadDeletionResult {
  warehouse_id: UUID;
  date: string;
  deleted_requests: number;
  deleted_plans: number;
}

export interface DriverInput {
  external_worker_id?: UUID | null;
  rwms_assignment_mode: Driver['rwms_assignment_mode'];
  passport_details?: string;
  active: boolean;
  notes: string;
}

export interface AvailableDriver {
  worker_id: UUID;
  display_name: string;
}

export interface VehicleInput {
  name: string;
  registration_number: string;
  capacity: number;
  active: boolean;
  average_speed_city: number;
  average_speed_region: number;
  notes: string;
  vehicle_type?: string | null;
  manufacturer?: string | null;
  model?: string | null;
  is_hgv?: boolean | null;
  tare_weight_kg?: number | null;
  max_gross_weight_kg?: number | null;
  length_mm?: number | null;
  width_mm?: number | null;
  height_mm?: number | null;
  axle_count?: number | null;
  max_axle_load_kg?: number | null;
  payload_capacity_kg?: number | null;
  platform_length_mm?: number | null;
  platform_width_mm?: number | null;
  platform_height_from_ground_mm?: number | null;
  max_platform_payload_kg?: number | null;
  max_cargo_length_mm?: number | null;
  max_cargo_width_mm?: number | null;
  max_cargo_height_mm?: number | null;
  max_cargo_weight_kg?: number | null;
  can_use_trailer?: boolean | null;
  default_trailer_id?: UUID | null;
  combined_length_with_trailer_mm?: number | null;
  coupling_length_mm?: number | null;
  height_safety_margin_mm?: number;
  width_safety_margin_mm?: number;
  weight_safety_margin_kg?: number;
}

export interface TrailerInput {
  name: string;
  registration_number: string;
  active: boolean;
  tare_weight_kg: number | null;
  max_gross_weight_kg: number | null;
  length_mm: number | null;
  width_mm: number | null;
  height_mm: number | null;
  platform_length_mm: number | null;
  platform_width_mm: number | null;
  platform_height_from_ground_mm: number | null;
  max_platform_payload_kg: number | null;
  payload_capacity_kg: number | null;
  axle_count: number | null;
  max_axle_load_kg: number | null;
  max_cargo_length_mm: number | null;
  max_cargo_width_mm: number | null;
  max_cargo_height_mm: number | null;
  max_cargo_weight_kg: number | null;
  notes: string;
}

export interface VehicleLoadProfileInput {
  configuration_type: VehicleLoadConfigurationType;
  max_actual_axle_load_kg: number;
}

export interface VehicleConfigurationInput {
  vehicle: VehicleInput;
  load_profiles: VehicleLoadProfileInput[];
}

export interface ShiftInput {
  driver_id: UUID;
  vehicle_id: UUID;
  date_from: string;
  date_to: string;
  start_time: string;
  end_time: string;
  break_minutes: number;
  active: boolean;
}

export interface LogisticsRequestInput {
  type: LogisticsRequest['type'];
  name: string;
  address_label: string;
  latitude: number;
  longitude: number;
  quantity: number;
  trailer_access_allowed?: boolean | null;
  include_driver_passport_in_notification?: boolean;
  contact_name?: string;
  contact_phone?: string;
  cargo_length_mm: number | null;
  cargo_width_mm: number | null;
  cargo_height_mm: number | null;
  cargo_weight_kg: number | null;
  service_minutes: number;
  priority: number;
  mandatory: boolean;
  status: LogisticsRequest['status'];
  split_allowed: boolean;
  notes: string;
  date_options: RequestDateOption[];
}

export interface RequestScheduleInput {
  date: string | null;
  add_if_missing?: boolean;
}

/** Atomic dispatcher preparation required before a request can enter planning. */
export interface RequestPlanningDetailsInput {
  date: string;
  window_start: string | null;
  window_end: string | null;
  is_hard: boolean;
  mandatory: boolean;
  trailer_access_allowed: boolean;
  include_driver_passport_in_notification: boolean;
  contact_name: string;
  contact_phone: string;
}

export interface ManualChangeInput {
  expected_version: number;
  change_type: 'MOVE_TASK' | 'REORDER_TASK' | 'REMOVE_TASK' | 'LOCK_CYCLE' | 'LOCK_TASK' | 'SPLIT_CYCLE' | 'MERGE_CYCLES';
  task_id?: UUID;
  source_cycle_id?: UUID;
  target_cycle_id?: UUID;
  target_sequence?: number;
  locked?: boolean;
  changed_by: 'local-admin';
  reason: string;
}

const loadConfigurationTypes: readonly VehicleLoadConfigurationType[] = [
  'EMPTY_TRUCK',
  'CARGO_ON_TRUCK',
  'EMPTY_COMBINATION',
  'CARGO_ON_TRUCK_WITH_TRAILER',
  'CARGO_ON_TRAILER_WITH_TRAILER',
  'TWO_CARGO_SPLIT',
];

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function finiteNumber(record: Record<string, unknown>, key: string): number | null {
  const value = record[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function parseCargoPlacement(value: unknown): RoutingCargoPlacementSnapshot | null {
  const record = asRecord(value);
  if (!record) return null;
  const cargoId = record.cargoId;
  const position = record.position;
  const lengthMm = finiteNumber(record, 'lengthMm');
  const widthMm = finiteNumber(record, 'widthMm');
  const heightMm = finiteNumber(record, 'heightMm');
  const weightKg = finiteNumber(record, 'weightKg');
  if (
    typeof cargoId !== 'string'
    || (position !== 'TRUCK_PLATFORM' && position !== 'TRAILER_PLATFORM')
    || lengthMm === null
    || widthMm === null
    || heightMm === null
    || weightKg === null
  ) return null;
  return { cargoId, position, lengthMm, widthMm, heightMm, weightKg };
}

function parseRoutingProfileSnapshot(value: unknown): RoutingProfileSnapshot | null {
  const record = asRecord(value);
  if (!record) return null;
  const vehicleId = record.vehicleId;
  const trailerId = record.trailerId;
  const configurationType = record.configurationType;
  const placementsRaw = record.cargoPlacements;
  const effectiveHeightMeters = finiteNumber(record, 'effectiveHeightMeters');
  const effectiveWidthMeters = finiteNumber(record, 'effectiveWidthMeters');
  const effectiveLengthMeters = finiteNumber(record, 'effectiveLengthMeters');
  const actualWeightTons = finiteNumber(record, 'actualWeightTons');
  const maxAxleLoadTons = finiteNumber(record, 'maxAxleLoadTons');
  const axleCount = finiteNumber(record, 'axleCount');
  const cargoCount = finiteNumber(record, 'cargoCount');
  if (
    typeof vehicleId !== 'string'
    || (trailerId !== null && typeof trailerId !== 'string')
    || typeof record.trailerAttached !== 'boolean'
    || typeof record.isHgv !== 'boolean'
    || typeof configurationType !== 'string'
    || !loadConfigurationTypes.includes(configurationType as VehicleLoadConfigurationType)
    || !Array.isArray(placementsRaw)
    || effectiveHeightMeters === null
    || effectiveWidthMeters === null
    || effectiveLengthMeters === null
    || actualWeightTons === null
    || maxAxleLoadTons === null
    || axleCount === null
    || cargoCount === null
  ) return null;
  const cargoPlacements = placementsRaw.map(parseCargoPlacement);
  if (cargoPlacements.some((placement) => placement === null)) return null;
  const routingProvider = typeof record.routingProvider === 'string' ? record.routingProvider : null;
  const osmDataVersion = typeof record.osmDataVersion === 'string' ? record.osmDataVersion : null;
  const calculatedAt = typeof record.calculatedAt === 'string' ? record.calculatedAt : null;
  return {
    vehicleId,
    trailerId,
    trailerAttached: record.trailerAttached,
    isHgv: record.isHgv,
    cargoCount,
    cargoPlacements: cargoPlacements as RoutingCargoPlacementSnapshot[],
    configurationType: configurationType as VehicleLoadConfigurationType,
    effectiveHeightMeters,
    effectiveWidthMeters,
    effectiveLengthMeters,
    actualWeightTons,
    maxAxleLoadTons,
    axleCount,
    routingProvider,
    osmDataVersion,
    calculatedAt,
  };
}

interface SegmentDiagnostics {
  routing_profile_snapshot: RoutingProfileSnapshot | null;
  routing_provider: string | null;
  osm_data_version: string | null;
  routed_at: string | null;
}

function segmentDiagnostics(rawPlan: unknown): Map<UUID, SegmentDiagnostics> {
  const result = new Map<UUID, SegmentDiagnostics>();
  const cycles = asRecord(rawPlan)?.cycles;
  if (!Array.isArray(cycles)) return result;
  for (const cycle of cycles) {
    const segments = asRecord(cycle)?.segments;
    if (!Array.isArray(segments)) continue;
    for (const segment of segments) {
      const record = asRecord(segment);
      if (!record || typeof record.id !== 'string') continue;
      const snapshot = parseRoutingProfileSnapshot(record.routing_profile_snapshot);
      result.set(record.id, {
        routing_profile_snapshot: snapshot,
        routing_provider: typeof record.routing_provider === 'string'
          ? record.routing_provider
          : snapshot?.routingProvider ?? null,
        osm_data_version: typeof record.osm_data_version === 'string'
          ? record.osm_data_version
          : snapshot?.osmDataVersion ?? null,
        routed_at: typeof record.routed_at === 'string'
          ? record.routed_at
          : snapshot?.calculatedAt ?? null,
      });
    }
  }
  return result;
}

function normalizeRoutePlanWithDiagnostics(raw: RawRoutePlan, workspace: WarehouseWorkspace): RoutePlan {
  const normalized = normalizeRoutePlan(raw, workspace);
  const diagnostics = segmentDiagnostics(raw);
  return {
    ...normalized,
    driver_routes: normalized.driver_routes.map((route) => ({
      ...route,
      cycles: route.cycles.map((cycle) => ({
        ...cycle,
        legs: cycle.legs.map((leg) => {
          const values = leg.id ? diagnostics.get(leg.id) : undefined;
          return values ? { ...leg, ...values } : leg;
        }),
      })),
    })),
  };
}

function normalizeValidationWithDiagnostics(
  value: unknown,
  workspace: WarehouseWorkspace,
  currentPlan: RoutePlan,
) {
  const normalized = normalizeValidationResult(value, workspace, currentPlan);
  const rawSchedule = asRecord(value)?.updated_schedule;
  if (!normalized.updated_schedule || !rawSchedule) return normalized;
  const updatedSchedule = normalizeRoutePlanWithDiagnostics(rawSchedule as unknown as RawRoutePlan, workspace);
  return { ...normalized, updated_schedule: updatedSchedule, updated_metrics: updatedSchedule.metrics };
}

export const api = {
  health: () => request<{ status: string }>('/health'),

  calculateSlotAvailability: async (input: SlotAvailabilityInput, signal?: AbortSignal) =>
    normalizeSlotAvailabilityResponse(await request<unknown>('/planning/slot-availability', {
      method: 'POST',
      body: jsonBody(input),
      ...(signal ? { signal } : {}),
    })),

  suggestAddresses: (
    text: string,
    point?: { latitude: number; longitude: number } | null,
    signal?: AbortSignal,
  ) => {
    const query = new URLSearchParams({ text });
    if (point) {
      query.set('latitude', String(point.latitude));
      query.set('longitude', String(point.longitude));
    }
    return request<AddressSuggestion[]>(`/geocoding/suggestions?${query.toString()}`, signal ? { signal } : {});
  },

  resolveAddressSuggestion: (uri: string, signal?: AbortSignal) => {
    const query = new URLSearchParams({ uri });
    return request<GeocodedAddress>(`/geocoding/resolve?${query.toString()}`, signal ? { signal } : {});
  },

  reverseGeocode: (latitude: number, longitude: number, signal?: AbortSignal) => {
    const query = new URLSearchParams({ latitude: String(latitude), longitude: String(longitude) });
    return request<GeocodedAddress>(`/geocoding/reverse?${query.toString()}`, signal ? { signal } : {});
  },

  getTruckRestrictions: (
    bounds: TruckRestrictionBounds,
    signal?: AbortSignal,
    limit = 2000,
  ) => {
    const query = new URLSearchParams({
      west: String(bounds.west),
      south: String(bounds.south),
      east: String(bounds.east),
      north: String(bounds.north),
      limit: String(limit),
    });
    const init: RequestInit = signal ? { signal } : {};
    return request<TruckRestrictionCollection>(`/routing/truck-restrictions?${query.toString()}`, init);
  },

  getTravelTimeContours: (
    latitude: number,
    longitude: number,
    signal?: AbortSignal,
  ) => {
    const query = new URLSearchParams({
      latitude: String(latitude),
      longitude: String(longitude),
    });
    const init: RequestInit = signal ? { signal } : {};
    return request<TravelTimeContourCollection>(`/routing/travel-time-contours?${query.toString()}`, init);
  },

  listWarehouses: async () => (await request<RawWarehouse[]>('/warehouses')).map((warehouse) => normalizeWarehouse(warehouse)),
  listAvailableWarehouses: () => request<AvailableWarehouse[]>('/warehouses/available'),
  updateWarehouse: async (id: UUID, input: WarehouseUpdateInput) => normalizeWarehouse(await request<RawWarehouse>(`/warehouses/${id}`, {
    method: 'PATCH',
    body: jsonBody(input),
  })),
  getWarehouseWorkspace: (id: UUID, refreshRwms = true) => {
    const query = refreshRwms ? '' : '?refresh_rwms=false';
    return request<WarehouseWorkspace>(`/warehouses/${id}/workspace${query}`);
  },
  generateWorkload: (warehouseId: UUID, input: WorkloadGenerationInput) =>
    request<WorkloadGenerationResult>(`/warehouses/${warehouseId}/generate-workload`, { method: 'POST', body: jsonBody(input) }),
  deleteGeneratedWorkload: (warehouseId: UUID, date: string) =>
    request<GeneratedWorkloadDeletionResult>(`/warehouses/${warehouseId}/generated-workload?date=${encodeURIComponent(date)}`, { method: 'DELETE' }),
  getPlanningDayStatus: (warehouseId: UUID, date: string) =>
    request<PlanningDayStatus>(`/warehouses/${warehouseId}/planning-days/${encodeURIComponent(date)}`),
  closePlanningDay: (warehouseId: UUID, date: string) =>
    request<PlanningDayStatus>(`/warehouses/${warehouseId}/planning-days/${encodeURIComponent(date)}/close`, { method: 'POST' }),

  listAvailableDrivers: (warehouseId: UUID) => request<AvailableDriver[]>(`/warehouses/${warehouseId}/available-drivers`),
  createDriver: (warehouseId: UUID, input: DriverInput) =>
    request<Driver>(`/warehouses/${warehouseId}/drivers`, { method: 'POST', body: jsonBody(input) }),
  updateDriver: (id: UUID, input: Partial<DriverInput>) =>
    request<Driver>(`/drivers/${id}`, { method: 'PATCH', body: jsonBody(input) }),
  deleteDriver: (id: UUID) => request<void>(`/drivers/${id}`, { method: 'DELETE' }),

  updateVehicle: (id: UUID, input: Partial<VehicleInput>) =>
    request<Vehicle>(`/vehicles/${id}`, { method: 'PATCH', body: jsonBody(input) }),
  createVehicleConfiguration: (warehouseId: UUID, input: VehicleConfigurationInput) =>
    request<Vehicle>(`/warehouses/${warehouseId}/vehicle-configurations`, { method: 'POST', body: jsonBody(input) }),
  updateVehicleConfiguration: (id: UUID, input: VehicleConfigurationInput) =>
    request<Vehicle>(`/vehicles/${id}/configuration`, { method: 'PUT', body: jsonBody(input) }),
  deleteVehicle: (id: UUID) => request<void>(`/vehicles/${id}`, { method: 'DELETE' }),

  createTrailer: (warehouseId: UUID, input: TrailerInput) =>
    request<Trailer>(`/warehouses/${warehouseId}/trailers`, { method: 'POST', body: jsonBody(input) }),
  updateTrailer: (id: UUID, input: Partial<TrailerInput>) =>
    request<Trailer>(`/trailers/${id}`, { method: 'PATCH', body: jsonBody(input) }),
  deleteTrailer: (id: UUID) => request<void>(`/trailers/${id}`, { method: 'DELETE' }),

  createShift: (warehouseId: UUID, input: ShiftInput) =>
    request<DriverShift>(`/warehouses/${warehouseId}/shifts`, { method: 'POST', body: jsonBody(input) }),
  updateShift: (id: UUID, input: Partial<ShiftInput>) =>
    request<DriverShift>(`/shifts/${id}`, { method: 'PATCH', body: jsonBody(input) }),
  deleteShift: (id: UUID) => request<void>(`/shifts/${id}`, { method: 'DELETE' }),

  createRequest: (warehouseId: UUID, input: LogisticsRequestInput) =>
    request<LogisticsRequest>(`/warehouses/${warehouseId}/requests`, { method: 'POST', body: jsonBody(input) }),
  updateRequest: (id: UUID, input: Partial<LogisticsRequestInput>) =>
    request<LogisticsRequest>(`/requests/${id}`, { method: 'PATCH', body: jsonBody(input) }),
  scheduleRequest: (id: UUID, input: RequestScheduleInput) =>
    request<LogisticsRequest>(`/requests/${id}/schedule`, { method: 'POST', body: jsonBody(input) }),
  deleteRequest: (id: UUID) => request<void>(`/requests/${id}`, { method: 'DELETE' }),
  splitRequest: (id: UUID, partQuantities?: number[]) => request<LogisticsRequest>(`/requests/${id}/split`, {
    method: 'POST',
    ...(partQuantities ? { body: jsonBody({ part_quantities: partQuantities }) } : {}),
  }),
  saveRequestPlanningDetails: (id: UUID, input: RequestPlanningDetailsInput) =>
    request<LogisticsRequest>(`/requests/${id}/planning-details`, { method: 'POST', body: jsonBody(input) }),

  getOptimizationRun: async (id: UUID, fallbackSettings: PlanningSettings) =>
    normalizeOptimizationRun(await request<RawOptimizationRun>(`/optimization-runs/${id}`), fallbackSettings),
  cancelOptimizationRun: async (id: UUID, fallbackSettings: PlanningSettings) =>
    normalizeOptimizationRun(
      await request<RawOptimizationRun>(`/optimization-runs/${id}/cancel`, { method: 'POST' }),
      fallbackSettings,
    ),
  getPlan: async (id: UUID, workspace: WarehouseWorkspace) =>
    normalizeRoutePlanWithDiagnostics(await request<RawRoutePlan>(`/plans/${id}`), workspace),
  ensureAutomaticPlan: async (
    warehouseId: UUID,
    date: string,
    workspace: WarehouseWorkspace,
    signal?: AbortSignal,
  ) => {
    const raw = await request<RawRoutePlan | null>(
      `/warehouses/${warehouseId}/plans/ensure?date=${encodeURIComponent(date)}`,
      { method: 'POST', ...(signal ? { signal } : {}) },
    );
    return raw ? normalizeRoutePlanWithDiagnostics(raw, workspace) : null;
  },
  validatePlan: async (id: UUID, expectedVersion: number, workspace: WarehouseWorkspace, currentPlan: RoutePlan) =>
    normalizeValidationWithDiagnostics(await request<unknown>(`/plans/${id}/validate`, {
      method: 'POST',
      body: jsonBody({ expected_version: expectedVersion }),
    }), workspace, currentPlan),
  confirmPlan: async (
    id: UUID,
    expectedVersion: number,
    acceptWarnings: boolean,
    workspace: WarehouseWorkspace,
    emptyPositioningReason?: string,
  ) =>
    normalizeRoutePlanWithDiagnostics(await request<RawRoutePlan>(`/plans/${id}/confirm`, {
      method: 'POST',
      body: jsonBody({
        expected_version: expectedVersion,
        accept_warnings: acceptWarnings,
        ...(emptyPositioningReason ? { empty_positioning_reason: emptyPositioningReason } : {}),
      }),
    }), workspace),
  resetManualChanges: async (id: UUID, expectedVersion: number, workspace: WarehouseWorkspace) =>
    normalizeRoutePlanWithDiagnostics(await request<RawRoutePlan>(`/plans/${id}/manual-changes/reset`, {
      method: 'POST',
      body: jsonBody({ expected_version: expectedVersion }),
    }), workspace),
  manualChange: async (planId: UUID, input: ManualChangeInput, workspace: WarehouseWorkspace, currentPlan: RoutePlan) => {
    const { expected_version, change_type, changed_by, reason, ...payload } = input;
    return normalizeValidationWithDiagnostics(
      await request<unknown>(`/plans/${planId}/manual-change`, {
        method: 'POST',
        body: jsonBody({ expected_version, change_type, payload, reason, changed_by }),
      }),
      workspace,
      currentPlan,
    );
  },
  patchCycle: async (
    planId: UUID,
    cycleId: UUID,
    input: { expected_version: number; sequence?: number; driver_shift_id?: UUID; locked?: boolean; reason: string },
    workspace: WarehouseWorkspace,
  ) => normalizeRoutePlanWithDiagnostics(
    await request<RawRoutePlan>(`/plans/${planId}/cycles/${cycleId}`, { method: 'PATCH', body: jsonBody(input) }),
    workspace,
  ),
  applyDelay: (planId: UUID, input: {
    expected_version: number;
    driver_shift_id: UUID;
    effective_at: string;
    delay_minutes: number;
    reason: string;
    persist: boolean;
  }, workspace: WarehouseWorkspace, currentPlan: RoutePlan) => request<unknown>(`/plans/${planId}/simulation/delay`, { method: 'POST', body: jsonBody(input) })
    .then((value) => normalizeValidationWithDiagnostics(value, workspace, currentPlan)),
  markDriverUnavailable: (planId: UUID, input: {
    expected_version: number;
    driver_shift_id: UUID;
    effective_at: string;
    reason: string;
    persist: boolean;
  }, workspace: WarehouseWorkspace, currentPlan: RoutePlan) => request<unknown>(`/plans/${planId}/simulation/driver-unavailable`, {
    method: 'POST',
    body: jsonBody(input),
  }).then((value) => normalizeValidationWithDiagnostics(value, workspace, currentPlan)),
};

export async function getWarehouseWorkspace(warehouseId: UUID): Promise<WarehouseWorkspace> {
  try {
    return normalizeWorkspace(await api.getWarehouseWorkspace(warehouseId));
  } catch (error: unknown) {
    if (!(error instanceof ApiError) || error.code !== 'RWMS_WORKSPACE_SYNC_INCOMPLETE') throw error;
    const failureCount = Array.isArray(error.problem?.failures) ? error.problem.failures.length : 0;
    const rwmsRefreshWarning = failureCount > 0
      ? `RWMS не обновил доставки и вывозы: ${failureCount}. Показаны последние сохранённые данные; автоматическая синхронизация повторится.`
      : 'RWMS обновил рабочую область не полностью. Показаны последние сохранённые данные; автоматическая синхронизация повторится.';
    const workspace = normalizeWorkspace(await api.getWarehouseWorkspace(warehouseId, false));
    return { ...workspace, rwms_refresh_warning: rwmsRefreshWarning };
  }
}

export function optimizationStreamUrl(runId: UUID): string {
  return `${API_PREFIX}/optimization-runs/${runId}/stream`;
}
