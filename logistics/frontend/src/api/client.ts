import type { Feature, FeatureCollection, Geometry } from 'geojson';
import type {
  Driver,
  DriverShift,
  LogisticsRequest,
  OptimizationRun,
  PlanningSettings,
  RequestDateOption,
  RoutePlan,
  RoutingCargoPlacementSnapshot,
  RoutingProfileSnapshot,
  ScenarioWorkspace,
  Trailer,
  UUID,
  Vehicle,
  VehicleLoadConfigurationType,
  VehicleLoadProfile,
  Warehouse,
  Zone,
  ZoneRelation,
} from '../domain/types';
import {
  normalizeOptimizationRun,
  normalizeRoutePlan,
  normalizeScenario,
  normalizeValidationResult,
  normalizeWorkspace,
  type RawOptimizationRun,
  type RawRoutePlan,
  type RawScenario,
} from './mappers';

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

function jsonBody(value: unknown): string {
  return JSON.stringify(value);
}

export interface ScenarioCreateInput {
  name: string;
  description: string;
  timezone: string;
  default_planning_date: string;
  settings?: Partial<PlanningSettings>;
}

export interface WarehouseInput {
  external_warehouse_id?: UUID | null;
  name: string;
  latitude: number;
  longitude: number;
  loading_minutes: number;
  unloading_minutes: number;
  turnaround_minutes: number;
  working_day_start: string;
  working_day_end: string;
}

export interface ZoneInput {
  name: string;
  code: string;
  route_group: string;
  delivery_price: number;
  pickup_price: number;
  geometry: Zone['geometry'];
  priority: number;
  locked: boolean;
}

/** Request body for generating reproducible workload in one scenario. */
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
  scenario_id: UUID;
  seed: number;
  start_date: string;
  end_date: string;
  created_requests: number;
  created_deliveries: number;
  created_pickups: number;
  replaced_requests: number;
  daily_counts: WorkloadGenerationDailyCount[];
}

/** Result of deleting generator-owned workload for one planning day. */
export interface GeneratedWorkloadDeletionResult {
  scenario_id: UUID;
  date: string;
  deleted_requests: number;
}

export interface ZoneCutoutResult {
  source_zone: Zone;
  inner_zone: Zone;
}

export interface DriverInput {
  external_worker_id?: UUID | null;
  name: string;
  preferred_route_group: string;
  active: boolean;
  notes: string;
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
  date: string;
  start_at: string;
  end_at: string;
  break_minutes: number;
  preferred_route_group: string;
  active: boolean;
}

export interface LogisticsRequestInput {
  type: LogisticsRequest['type'];
  name: string;
  address_label: string;
  latitude: number;
  longitude: number;
  quantity: number;
  cargo_length_mm: number | null;
  cargo_width_mm: number | null;
  cargo_height_mm: number | null;
  cargo_weight_kg: number | null;
  service_minutes: number;
  priority: number;
  status: LogisticsRequest['status'];
  split_allowed: boolean;
  notes: string;
  date_options: RequestDateOption[];
}

export interface RequestScheduleInput {
  date: string | null;
  add_if_missing?: boolean;
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

export interface GenerationAccepted {
  run_id: UUID;
  plan_id: UUID | null;
  status: OptimizationRun['status'];
}

export interface RwmsSyncFailure {
  order_id: UUID | null;
  code: string;
  message: string;
}

export interface RwmsSyncResult {
  imported: number;
  updated: number;
  skipped: number;
  failures: RwmsSyncFailure[];
}

export interface RwmsAppliedAssignment {
  order_id: UUID;
  document_id: UUID;
  replayed: boolean;
}

export interface RwmsRejectedAssignment {
  order_id: UUID;
  code: string;
  message: string;
}

export interface RwmsApplyResult {
  applied: RwmsAppliedAssignment[];
  rejected: RwmsRejectedAssignment[];
}

export type RwmsDriverAudienceMode = 'ASSIGNED_DRIVER' | 'WAREHOUSE_DRIVERS';

export interface RwmsPlanTaskStatus {
  task_id: UUID;
  request_id: UUID;
  order_id: UUID;
  document_id: UUID;
  driver_audience_mode: RwmsDriverAudienceMode;
  driver_worker_id: UUID | null;
  driver_name: string | null;
  task_state: string;
}

export interface RwmsPlanStatusResult {
  plan_id: UUID;
  plan_version: number;
  tasks: RwmsPlanTaskStatus[];
}

function stringField(record: Record<string, unknown>, snakeCase: string, camelCase: string): string {
  const value = record[snakeCase] ?? record[camelCase];
  if (typeof value !== 'string') throw new ApiError(502, null, `Backend не вернул поле ${snakeCase}`);
  return value;
}

function normalizeRwmsApplyResult(value: unknown): RwmsApplyResult {
  if (!value || typeof value !== 'object') throw new ApiError(502, null, 'Backend вернул некорректный результат отправки в RWMS');
  const record = value as Record<string, unknown>;
  const applied = Array.isArray(record.applied) ? record.applied : [];
  const rejected = Array.isArray(record.rejected) ? record.rejected : [];
  return {
    applied: applied.map((item) => {
      if (!item || typeof item !== 'object') throw new ApiError(502, null, 'Backend вернул некорректное назначение RWMS');
      const assignment = item as Record<string, unknown>;
      return {
        order_id: stringField(assignment, 'order_id', 'orderId'),
        document_id: stringField(assignment, 'document_id', 'documentId'),
        replayed: assignment.replayed === true,
      };
    }),
    rejected: rejected.map((item) => {
      if (!item || typeof item !== 'object') throw new ApiError(502, null, 'Backend вернул некорректный отказ RWMS');
      const rejection = item as Record<string, unknown>;
      return {
        order_id: stringField(rejection, 'order_id', 'orderId'),
        code: stringField(rejection, 'code', 'code'),
        message: stringField(rejection, 'message', 'message'),
      };
    }),
  };
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

function normalizeRoutePlanWithDiagnostics(raw: RawRoutePlan, workspace: ScenarioWorkspace): RoutePlan {
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
  workspace: ScenarioWorkspace,
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

  listScenarios: async () => (await request<RawScenario[]>('/scenarios')).map((scenario) => normalizeScenario(scenario)),
  getScenario: async (id: UUID) => normalizeScenario(await request<RawScenario>(`/scenarios/${id}`)),
  createScenario: async (input: ScenarioCreateInput) =>
    normalizeScenario(await request<RawScenario>('/scenarios', { method: 'POST', body: jsonBody(input) })),
  updateScenario: async (id: UUID, input: Partial<ScenarioCreateInput>) =>
    normalizeScenario(await request<RawScenario>(`/scenarios/${id}`, { method: 'PATCH', body: jsonBody(input) })),
  deleteScenario: (id: UUID) => request<void>(`/scenarios/${id}`, { method: 'DELETE' }),
  cloneScenario: async (id: UUID, name?: string) =>
    normalizeScenario(await request<RawScenario>(`/scenarios/${id}/clone`, { method: 'POST', body: jsonBody(name ? { name } : {}) })),
  generateDemo: async (id: UUID) =>
    normalizeScenario(await request<RawScenario>(`/scenarios/${id}/generate-demo`, { method: 'POST' })),
  generateMultiDayDemo: async () =>
    normalizeScenario(await request<RawScenario>('/scenarios/generate-multi-day-demo', { method: 'POST' })),
  generateWorkload: (scenarioId: UUID, input: WorkloadGenerationInput) =>
    request<WorkloadGenerationResult>(`/scenarios/${scenarioId}/generate-workload`, { method: 'POST', body: jsonBody(input) }),
  deleteGeneratedWorkload: (scenarioId: UUID, date: string) =>
    request<GeneratedWorkloadDeletionResult>(`/scenarios/${scenarioId}/generated-workload?date=${encodeURIComponent(date)}`, { method: 'DELETE' }),
  exportScenario: (id: UUID, includePlans = true) =>
    request<Record<string, unknown>>(`/scenarios/${id}/export?include_plans=${includePlans ? 'true' : 'false'}`, { method: 'POST' }),
  importScenario: async (payload: unknown) =>
    normalizeScenario(await request<RawScenario>('/scenarios/import', { method: 'POST', body: jsonBody({ document: payload }) })),

  listWarehouses: (scenarioId: UUID) => request<Warehouse[]>(`/scenarios/${scenarioId}/warehouses`),
  createWarehouse: (scenarioId: UUID, input: WarehouseInput) =>
    request<Warehouse>(`/scenarios/${scenarioId}/warehouses`, { method: 'POST', body: jsonBody(input) }),
  updateWarehouse: (id: UUID, input: Partial<WarehouseInput>) =>
    request<Warehouse>(`/warehouses/${id}`, { method: 'PATCH', body: jsonBody(input) }),

  listZones: (scenarioId: UUID) => request<Zone[]>(`/scenarios/${scenarioId}/zones`),
  createZone: (scenarioId: UUID, input: ZoneInput) =>
    request<Zone>(`/scenarios/${scenarioId}/zones`, { method: 'POST', body: jsonBody(input) }),
  updateZone: (id: UUID, input: Partial<Omit<ZoneInput, 'locked'>>) =>
    request<Zone>(`/zones/${id}`, { method: 'PATCH', body: jsonBody(input) }),
  cutZone: (id: UUID, input: ZoneInput) => {
    const { geometry, ...innerZone } = input;
    return request<ZoneCutoutResult>(`/zones/${id}/cutouts`, {
      method: 'POST',
      body: jsonBody({ geometry, inner_zone: innerZone }),
    });
  },
  deleteZone: (id: UUID) => request<void>(`/zones/${id}`, { method: 'DELETE' }),
  setZoneLocked: (id: UUID, locked: boolean) =>
    request<Zone>(`/zones/${id}/lock`, { method: 'POST', body: jsonBody({ locked }) }),
  reclassifyRequests: (scenarioId: UUID) =>
    request<{ updated: number; outside_zones: number; unchanged: number }>(`/scenarios/${scenarioId}/reclassify-requests`, { method: 'POST' }),

  listZoneRelations: (scenarioId: UUID) =>
    request<ZoneRelation[]>(`/scenarios/${scenarioId}/zone-relations`),
  createZoneRelation: (scenarioId: UUID, input: Omit<ZoneRelation, 'id'>) =>
    request<ZoneRelation>(`/scenarios/${scenarioId}/zone-relations`, { method: 'POST', body: jsonBody(input) }),
  updateZoneRelation: (id: UUID, input: Partial<Omit<ZoneRelation, 'id' | 'from_zone_id' | 'to_zone_id'>>) =>
    request<ZoneRelation>(`/zone-relations/${id}`, { method: 'PATCH', body: jsonBody(input) }),
  deleteZoneRelation: (id: UUID) => request<void>(`/zone-relations/${id}`, { method: 'DELETE' }),

  listDrivers: (scenarioId: UUID) => request<Driver[]>(`/scenarios/${scenarioId}/drivers`),
  createDriver: (scenarioId: UUID, input: DriverInput) =>
    request<Driver>(`/scenarios/${scenarioId}/drivers`, { method: 'POST', body: jsonBody(input) }),
  updateDriver: (id: UUID, input: Partial<DriverInput>) =>
    request<Driver>(`/drivers/${id}`, { method: 'PATCH', body: jsonBody(input) }),
  deleteDriver: (id: UUID) => request<void>(`/drivers/${id}`, { method: 'DELETE' }),

  listVehicles: (scenarioId: UUID) => request<Vehicle[]>(`/scenarios/${scenarioId}/vehicles`),
  createVehicle: (scenarioId: UUID, input: VehicleInput) =>
    request<Vehicle>(`/scenarios/${scenarioId}/vehicles`, { method: 'POST', body: jsonBody(input) }),
  updateVehicle: (id: UUID, input: Partial<VehicleInput>) =>
    request<Vehicle>(`/vehicles/${id}`, { method: 'PATCH', body: jsonBody(input) }),
  createVehicleConfiguration: (scenarioId: UUID, input: VehicleConfigurationInput) =>
    request<Vehicle>(`/scenarios/${scenarioId}/vehicle-configurations`, { method: 'POST', body: jsonBody(input) }),
  updateVehicleConfiguration: (id: UUID, input: VehicleConfigurationInput) =>
    request<Vehicle>(`/vehicles/${id}/configuration`, { method: 'PUT', body: jsonBody(input) }),
  deleteVehicle: (id: UUID) => request<void>(`/vehicles/${id}`, { method: 'DELETE' }),

  listTrailers: (scenarioId: UUID) => request<Trailer[]>(`/scenarios/${scenarioId}/trailers`),
  getTrailer: (id: UUID) => request<Trailer>(`/trailers/${id}`),
  createTrailer: (scenarioId: UUID, input: TrailerInput) =>
    request<Trailer>(`/scenarios/${scenarioId}/trailers`, { method: 'POST', body: jsonBody(input) }),
  updateTrailer: (id: UUID, input: Partial<TrailerInput>) =>
    request<Trailer>(`/trailers/${id}`, { method: 'PATCH', body: jsonBody(input) }),
  deleteTrailer: (id: UUID) => request<void>(`/trailers/${id}`, { method: 'DELETE' }),

  listVehicleLoadProfiles: (vehicleId: UUID) => request<VehicleLoadProfile[]>(`/vehicles/${vehicleId}/load-profiles`),
  createVehicleLoadProfile: (vehicleId: UUID, input: VehicleLoadProfileInput) =>
    request<VehicleLoadProfile>(`/vehicles/${vehicleId}/load-profiles`, { method: 'POST', body: jsonBody(input) }),
  updateVehicleLoadProfile: (id: UUID, input: Partial<VehicleLoadProfileInput>) =>
    request<VehicleLoadProfile>(`/vehicle-load-profiles/${id}`, { method: 'PATCH', body: jsonBody(input) }),
  deleteVehicleLoadProfile: (id: UUID) => request<void>(`/vehicle-load-profiles/${id}`, { method: 'DELETE' }),

  listShifts: (scenarioId: UUID) => request<DriverShift[]>(`/scenarios/${scenarioId}/shifts`),
  createShift: (scenarioId: UUID, input: ShiftInput) =>
    request<DriverShift>(`/scenarios/${scenarioId}/shifts`, { method: 'POST', body: jsonBody(input) }),
  updateShift: (id: UUID, input: Partial<ShiftInput>) =>
    request<DriverShift>(`/shifts/${id}`, { method: 'PATCH', body: jsonBody(input) }),
  deleteShift: (id: UUID) => request<void>(`/shifts/${id}`, { method: 'DELETE' }),

  listRequests: (scenarioId: UUID) => request<LogisticsRequest[]>(`/scenarios/${scenarioId}/requests`),
  createRequest: (scenarioId: UUID, input: LogisticsRequestInput) =>
    request<LogisticsRequest>(`/scenarios/${scenarioId}/requests`, { method: 'POST', body: jsonBody(input) }),
  updateRequest: (id: UUID, input: Partial<LogisticsRequestInput>) =>
    request<LogisticsRequest>(`/requests/${id}`, { method: 'PATCH', body: jsonBody(input) }),
  scheduleRequest: (id: UUID, input: RequestScheduleInput) =>
    request<LogisticsRequest>(`/requests/${id}/schedule`, { method: 'POST', body: jsonBody(input) }),
  deleteRequest: (id: UUID) => request<void>(`/requests/${id}`, { method: 'DELETE' }),
  splitRequest: (id: UUID) => request<LogisticsRequest>(`/requests/${id}/split`, { method: 'POST' }),

  syncRwmsRequests: (scenarioId: UUID, input: { warehouse_id: UUID; date_from: string; date_to: string }) =>
    request<RwmsSyncResult>(`/scenarios/${scenarioId}/rwms/sync`, { method: 'POST', body: jsonBody(input) }),
  applyPlanToRwms: async (
    planId: UUID,
    expectedVersion: number,
    publishUnassignedTaskIds: UUID[] = [],
  ) => normalizeRwmsApplyResult(
    await request<unknown>(`/plans/${planId}/rwms/apply`, {
      method: 'POST',
      body: jsonBody({
        expected_version: expectedVersion,
        publish_unassigned_task_ids: publishUnassignedTaskIds,
      }),
    }),
  ),
  getPlanRwmsStatus: (planId: UUID, expectedVersion: number) =>
    request<RwmsPlanStatusResult>(`/plans/${planId}/rwms/status?expected_version=${expectedVersion}`),

  generatePlan: async (scenarioId: UUID, date: string, seed: number, settings: PlanningSettings) => {
    const raw = await request<Record<string, unknown>>(`/scenarios/${scenarioId}/plans/generate`, {
      method: 'POST',
      body: jsonBody({ date, seed, settings, show_trace: settings.trace_enabled ?? false }),
    });
    const runId = typeof raw.run_id === 'string' ? raw.run_id : typeof raw.id === 'string' ? raw.id : null;
    if (!runId) throw new ApiError(502, null, 'Backend не вернул идентификатор запуска оптимизации');
    return {
      run_id: runId,
      plan_id: typeof raw.plan_id === 'string' ? raw.plan_id : null,
      status: typeof raw.status === 'string' ? raw.status as OptimizationRun['status'] : 'PENDING',
    } satisfies GenerationAccepted;
  },
  getOptimizationRun: async (id: UUID, fallbackSettings: PlanningSettings) =>
    normalizeOptimizationRun(await request<RawOptimizationRun>(`/optimization-runs/${id}`), fallbackSettings),
  cancelOptimizationRun: async (id: UUID, fallbackSettings: PlanningSettings) =>
    normalizeOptimizationRun(
      await request<RawOptimizationRun>(`/optimization-runs/${id}/cancel`, { method: 'POST' }),
      fallbackSettings,
    ),
  getPlan: async (id: UUID, workspace: ScenarioWorkspace) =>
    normalizeRoutePlanWithDiagnostics(await request<RawRoutePlan>(`/plans/${id}`), workspace),
  validatePlan: async (id: UUID, expectedVersion: number, workspace: ScenarioWorkspace, currentPlan: RoutePlan) =>
    normalizeValidationWithDiagnostics(await request<unknown>(`/plans/${id}/validate`, {
      method: 'POST',
      body: jsonBody({ expected_version: expectedVersion }),
    }), workspace, currentPlan),
  clonePlan: async (id: UUID, name: string | undefined, workspace: ScenarioWorkspace) =>
    normalizeRoutePlanWithDiagnostics(
      await request<RawRoutePlan>(`/plans/${id}/clone`, { method: 'POST', body: jsonBody(name ? { name } : {}) }),
      workspace,
    ),
  confirmPlan: async (id: UUID, expectedVersion: number, acceptWarnings: boolean, workspace: ScenarioWorkspace) =>
    normalizeRoutePlanWithDiagnostics(await request<RawRoutePlan>(`/plans/${id}/confirm`, {
      method: 'POST',
      body: jsonBody({ expected_version: expectedVersion, accept_warnings: acceptWarnings }),
    }), workspace),
  manualChange: async (planId: UUID, input: ManualChangeInput, workspace: ScenarioWorkspace, currentPlan: RoutePlan) => {
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
    workspace: ScenarioWorkspace,
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
  }, workspace: ScenarioWorkspace, currentPlan: RoutePlan) => request<unknown>(`/plans/${planId}/simulation/delay`, { method: 'POST', body: jsonBody(input) })
    .then((value) => normalizeValidationWithDiagnostics(value, workspace, currentPlan)),
  markDriverUnavailable: (planId: UUID, input: {
    expected_version: number;
    driver_shift_id: UUID;
    effective_at: string;
    reason: string;
    persist: boolean;
  }, workspace: ScenarioWorkspace, currentPlan: RoutePlan) => request<unknown>(`/plans/${planId}/simulation/driver-unavailable`, {
    method: 'POST',
    body: jsonBody(input),
  }).then((value) => normalizeValidationWithDiagnostics(value, workspace, currentPlan)),
};

export async function getScenarioWorkspace(scenarioId: UUID): Promise<ScenarioWorkspace> {
  const [scenario, warehouses, zones, zoneRelations, drivers, vehicles, shifts, requests] = await Promise.all([
    api.getScenario(scenarioId),
    api.listWarehouses(scenarioId),
    api.listZones(scenarioId),
    api.listZoneRelations(scenarioId),
    api.listDrivers(scenarioId),
    api.listVehicles(scenarioId),
    api.listShifts(scenarioId),
    api.listRequests(scenarioId),
  ]);
  return normalizeWorkspace({
    scenario,
    warehouses,
    zones,
    zone_relations: zoneRelations,
    drivers,
    vehicles,
    shifts,
    requests,
  });
}

export function optimizationStreamUrl(runId: UUID): string {
  return `${API_PREFIX}/optimization-runs/${runId}/stream`;
}
