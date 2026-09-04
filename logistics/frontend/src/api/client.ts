import type { Feature, FeatureCollection, Geometry, MultiPolygon, Polygon } from 'geojson';
import type {
  Driver,
  DriverShift,
  LogisticsRequest,
  PlanningSettings,
  IsochroneTariff,
  PolicyZoneInput,
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
  WarehousePolicyZone,
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
import { restorePanelUser } from '../auth/panel-oidc';
import type { components } from './schema';

const configuredApiPrefix = import.meta.env.VITE_API_BASE_URL?.trim();
const API_PREFIX = configuredApiPrefix?.replace(/\/+$/, '') || '/api';

/** Resolves the current renewable USER access token for one simulator request. */
export type SimulatorAccessTokenProvider = () => Promise<string | null>;

const defaultAccessTokenProvider: SimulatorAccessTokenProvider = async () => {
  const user = await restorePanelUser();
  return user?.access_token ?? null;
};

let accessTokenProvider: SimulatorAccessTokenProvider = defaultAccessTokenProvider;

/** Installs a scoped token source; tests use this seam without weakening production OIDC. */
export function setSimulatorAccessTokenProvider(provider: SimulatorAccessTokenProvider | null): void {
  accessTokenProvider = provider ?? defaultAccessTokenProvider;
}

/** Resolve a standalone-simulator endpoint against its configured deployment prefix. */
export function simulatorApiUrl(path: string): string {
  if (!path.startsWith('/')) throw new Error('Simulator API path must start with /');
  return `${API_PREFIX}${path}`;
}

export interface ProblemDetails {
  type?: unknown;
  title?: unknown;
  status?: unknown;
  detail?: unknown;
  instance?: unknown;
  code?: unknown;
  errors?: unknown;
  failures?: unknown;
  request_id?: unknown;
  hold_id?: unknown;
  quarantine_count?: unknown;
}

const PUBLIC_ERROR_FORBIDDEN_TEXT = /\b(?:HTTP(?:\/\d(?:\.\d)?)?|backend|exception|traceback|stack\s*trace|sql(?:alchemy)?|pydantic|validation\s+error|rms\s+logistics\s+service|valhalla|nginx|uvicorn|fastapi)\b/iu;
const INTERNAL_ERROR_CODE = /\b(?!RWMS\b)[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/u;
const RUSSIAN_ACTION = /(?:войдите|выберите|измените|обновите|проверьте|повторите|перенесите|добавьте|укажите|исправьте|дождитесь|обратитесь|согласуйте|освободите|свяжитесь|перезагрузите|включите|выключите|заполните|назначьте|создайте|разделите|уменьшите|увеличьте|подтвердите|снимите)/iu;
const PROBLEM_CODE_MESSAGES: Readonly<Record<string, string>> = {
  DELIVERY_FORBIDDEN_ZONE: 'Адрес находится в зоне, где обслуживание запрещено. Измените адрес или границу исключения.',
  DELIVERY_OUTSIDE_ISOCHRONE: 'Адрес находится дальше предельной изохроны склада. Выберите другой склад или адрес.',
  POLICY_ZONE_VALUES_INVALID: 'Для особой цены укажите стоимость доставки и вывоза, а для ограничений удалите цены.',
};

function safeRussianProblemText(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.replace(/\s+/gu, ' ').trim();
  if (
    !normalized
    || normalized.length > 500
    || !/[\u0400-\u04ff]/u.test(normalized)
    || PUBLIC_ERROR_FORBIDDEN_TEXT.test(normalized)
    || INTERNAL_ERROR_CODE.test(normalized)
    || /(?:https?:\/\/|[{}[\]]|<\/?[a-z][^>]*>)/iu.test(normalized)
  ) return null;
  return normalized;
}

function statusAction(status: number): string {
  if (status === 0) return 'Проверьте соединение и повторите действие.';
  if (status === 401) return 'Войдите в RWMS и повторите действие.';
  if (status === 403) return 'Обратитесь к администратору за необходимыми правами.';
  if (status === 404) return 'Обновите страницу и выберите доступный объект.';
  if (status === 409) return 'Обновите данные и повторите действие.';
  if (status === 429) return 'Подождите немного и повторите действие.';
  if (status === 400 || status === 422) return 'Проверьте введённые данные и повторите действие.';
  return 'Повторите действие. Если ошибка сохранится, свяжитесь с администратором.';
}

function statusMessage(status: number): string {
  if (status === 0) return `Сервис логистики недоступен. ${statusAction(status)}`;
  if (status === 401) return `Сессия RWMS недоступна. ${statusAction(status)}`;
  if (status === 403) return `Недостаточно прав для этого действия. ${statusAction(status)}`;
  if (status === 404) return `Запрошенные данные не найдены. ${statusAction(status)}`;
  if (status === 409) return `Данные уже изменились. ${statusAction(status)}`;
  if (status === 429) return `Сервис получил слишком много запросов. ${statusAction(status)}`;
  if (status === 400 || status === 422) return `Запрос содержит недопустимые данные. ${statusAction(status)}`;
  return `Не удалось выполнить действие. ${statusAction(status)}`;
}

function actionableProblemText(value: string, status: number): string {
  const punctuation = /[.!?]$/u.test(value) ? value : `${value}.`;
  return RUSSIAN_ACTION.test(value) ? punctuation : `${punctuation} ${statusAction(status)}`;
}

function problemMessage(status: number, problem: ProblemDetails | null, fallback: string): string {
  // FastAPI validation arrays and all raw diagnostics stay in `problem`; they are never presentation text.
  const code = typeof problem?.code === 'string' ? problem.code : null;
  if (code && PROBLEM_CODE_MESSAGES[code]) return PROBLEM_CODE_MESSAGES[code];
  const candidate = safeRussianProblemText(problem?.detail)
    ?? safeRussianProblemText(problem?.title)
    ?? safeRussianProblemText(fallback);
  return candidate ? actionableProblemText(candidate, status) : statusMessage(status);
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string | null;
  readonly problem: ProblemDetails | null;

  constructor(status: number, problem: ProblemDetails | null, fallback: string) {
    const code = typeof problem?.code === 'string' ? problem.code : null;
    const localizedProblem = code && PROBLEM_CODE_MESSAGES[code]
      ? { ...(problem ?? {}), title: PROBLEM_CODE_MESSAGES[code] }
      : problem;
    super(problemMessage(status, localizedProblem, fallback));
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.problem = localizedProblem;
  }
}

function authenticationRequired(detail = 'Войдите в RWMS, чтобы продолжить работу с логистикой.'): ApiError {
  return new ApiError(401, {
    type: 'urn:rwms:problem:authentication-required',
    title: 'Требуется вход в RWMS',
    status: 401,
    detail,
    code: 'AUTHENTICATION_REQUIRED',
  }, detail);
}

/** Returns a fresh or silently renewed panel USER token and never fabricates a session. */
export async function requireSimulatorAccessToken(): Promise<string> {
  let token: string | null;
  try {
    token = await accessTokenProvider();
  } catch {
    throw authenticationRequired('Не удалось проверить сессию RWMS. Войдите снова и повторите действие.');
  }
  if (!token?.trim()) throw authenticationRequired();
  return token;
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

async function request<T>(path: string, init: RequestInit = {}, authenticated = true): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  headers.set('Accept', 'application/json, application/problem+json');
  try {
    if (authenticated) headers.set('Authorization', `Bearer ${await requireSimulatorAccessToken()}`);
    const response = await fetch(simulatorApiUrl(path), { ...init, headers });
    return await parseResponse<T>(response);
  } catch (error: unknown) {
    if (error instanceof ApiError) throw error;
    if (init.signal?.aborted) throw error;
    throw new ApiError(0, null, 'Сервис логистики недоступен. Проверьте соединение.');
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

/** Persisted acceptance and dynamic-operation summary for one warehouse day. */
export type PlanningDayStatus = components['schemas']['PlanningDayStatusRead'];

/** Optimizer directions permitted by the versioned policy for one warehouse day. */
export type PlanningDayMode = components['schemas']['PlanningDayMode'];
/** Complete server-owned operational projection for the selected warehouse day. */
export type PlanningDayOperations = components['schemas']['PlanningDayOperationsRead'];
/** Version-fenced day-mode command. */
export type PlanningDayModeUpdate = components['schemas']['PlanningDayModeUpdate'];
/** Result of changing a day mode without inventing client-side conflicts. */
export type PlanningDayModeResult = components['schemas']['PlanningDayModeResult'];
/** Supported operational fact accepted by the impact-analysis endpoint. */
export type LogisticsEventInput = components['schemas']['LogisticsEventCreate'];
/** Server-derived notices, actions and proposals created for one fact. */
export type LogisticsEventResult = components['schemas']['LogisticsEventResult'];
/** Immutable dispatcher choice that becomes a server-side planning constraint. */
export type LogisticsDecisionInput = components['schemas']['LogisticsHumanDecisionCreate'];
/** Recorded dispatcher choice returned by the operations boundary. */
export type LogisticsDecision = components['schemas']['LogisticsHumanDecisionRead'];
/** One pending or historical dispatcher action. */
export type LogisticsAction = components['schemas']['LogisticsHumanActionRead'];
/** One structured system comment. */
export type LogisticsNotice = components['schemas']['LogisticsNoticeRead'];
/** One recovery candidate with explicit agreement and application state. */
export type RecoveryProposal = components['schemas']['RecoveryProposalRead'];

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

/** Bounded request slice for one header-selected planning date. */
export interface WarehouseWorkspacePageInput {
  planningDate: string;
  requestLimit?: number;
  requestCursor?: UUID | null;
}

export const WORKSPACE_REQUEST_PAGE_LIMIT = 250;

/** Request body for generating a random workload in one warehouse. */
export interface WorkloadGenerationInput {
  start_date: string;
  days: number;
  deliveries_per_day: number;
  pickups_per_day: number;
  alternative_dates_count: number;
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
  capacity_projection_status: 'NOT_REQUESTED' | 'PUBLISHED' | 'FAILED';
  capacity_projection_warning?: string | null;
}

/** Result of deleting generator-owned workload for one planning day. */
export interface GeneratedWorkloadDeletionResult {
  warehouse_id: UUID;
  date: string;
  deleted_requests: number;
  deleted_plans: number;
  capacity_projection_status: 'NOT_REQUESTED' | 'PUBLISHED' | 'FAILED';
  capacity_projection_warning?: string | null;
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
  split_allowed: boolean;
  notes: string;
  date_options: RequestDateOption[];
}

export interface RequestScheduleInput {
  date: string | null;
  add_if_missing?: boolean;
}

export interface RequestDateOptionInput {
  date: string;
  priority: number;
  window_start: string | null;
  window_end: string | null;
  is_hard: boolean;
  travel_zone_hours?: number | null;
}

/** Assignment mode for a contractor route on the planning date selected in the header. */
export type ContractorDispatchMode = 'AUTO' | 'MANUAL';

/** Server-owned contractor dispatch result; no internal vehicle or cycle is created. */
export interface ContractorDispatchResult {
  contractor_worker_id: UUID;
  contractor_name: string;
  planning_date: string;
  mode: ContractorDispatchMode;
  assigned_request_ids: UUID[];
  assigned_count: number;
  contractor_handoff_command_id: UUID | null;
  external_task_ids: UUID[];
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

/** One fresh owner-calculated delivery slot offered for an existing RWMS order. */
export type RequestRescheduleSlotRead = components['schemas']['RequestRescheduleSlotRead'];

/** Version fences and all currently feasible slots for the operator-selected date. */
export type RequestRescheduleOptionsRead = components['schemas']['RequestRescheduleOptionsRead'];

export type RequestRescheduleOptionsInput = components['schemas']['RequestRescheduleOptionsQuery'];

export type RequestRescheduleInput = components['schemas']['RequestRescheduleApply'];

export type RequestRescheduleRetryInput = components['schemas']['RequestRescheduleRetry'];

/** Owner-confirmed delivery commitment and converged local request projection. */
export type RequestRescheduleResultRead = components['schemas']['RequestRescheduleResultRead'];

export interface ManualChangeInput {
  expected_version: number;
  change_type: 'MOVE_TASK' | 'REORDER_TASK' | 'REMOVE_TASK' | 'LOCK_CYCLE' | 'LOCK_TASK' | 'SPLIT_CYCLE' | 'MERGE_CYCLES';
  task_id?: UUID;
  source_cycle_id?: UUID;
  target_cycle_id?: UUID;
  target_sequence?: number;
  locked?: boolean;
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
  health: () => request<{ status: string }>('/health', {}, false),

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
  updateWarehouse: async (id: UUID, input: WarehouseUpdateInput, expectedVersion: number) => normalizeWarehouse(await request<RawWarehouse>(`/warehouses/${id}`, {
    method: 'PATCH',
    body: jsonBody({ ...input, expected_version: expectedVersion }),
  })),
  getWarehouseWorkspace: (id: UUID, input: WarehouseWorkspacePageInput) => {
    const query = new URLSearchParams({
      planning_date: input.planningDate,
      request_limit: String(input.requestLimit ?? WORKSPACE_REQUEST_PAGE_LIMIT),
    });
    if (input.requestCursor) query.set('request_cursor', input.requestCursor);
    return request<WarehouseWorkspace>(`/warehouses/${id}/workspace?${query.toString()}`);
  },
  listPolicyZones: (warehouseId: UUID) =>
    request<WarehousePolicyZone[]>(`/warehouses/${warehouseId}/policy-zones`),
  createPolicyZone: (warehouseId: UUID, input: PolicyZoneInput, idempotencyKey: UUID) =>
    request<WarehousePolicyZone>(`/warehouses/${warehouseId}/policy-zones`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: jsonBody(input),
    }),
  updatePolicyZone: (warehouseId: UUID, zoneId: UUID, input: PolicyZoneInput, expectedVersion: number) =>
    request<WarehousePolicyZone>(`/warehouses/${warehouseId}/policy-zones/${zoneId}`, {
      method: 'PATCH',
      body: jsonBody({ ...input, expected_version: expectedVersion }),
    }),
  deletePolicyZone: (warehouseId: UUID, zoneId: UUID, expectedVersion: number) =>
    request<void>(`/warehouses/${warehouseId}/policy-zones/${zoneId}?expected_version=${encodeURIComponent(String(expectedVersion))}`, {
      method: 'DELETE',
    }),
  generateWorkload: (warehouseId: UUID, input: WorkloadGenerationInput) =>
    request<WorkloadGenerationResult>(`/warehouses/${warehouseId}/generate-workload`, { method: 'POST', body: jsonBody(input) }),
  deleteGeneratedWorkload: (warehouseId: UUID, date: string) =>
    request<GeneratedWorkloadDeletionResult>(`/warehouses/${warehouseId}/generated-workload?date=${encodeURIComponent(date)}`, { method: 'DELETE' }),
  getPlanningDayStatus: (warehouseId: UUID, date: string) =>
    request<PlanningDayStatus>(`/warehouses/${warehouseId}/planning-days/${encodeURIComponent(date)}`),
  closePlanningDay: (warehouseId: UUID, date: string) =>
    request<PlanningDayStatus>(`/warehouses/${warehouseId}/planning-days/${encodeURIComponent(date)}/close`, { method: 'POST' }),
  getPlanningDayOperations: (warehouseId: UUID, date: string) =>
    request<PlanningDayOperations>(`/warehouses/${warehouseId}/planning-days/${encodeURIComponent(date)}/operations`),
  updatePlanningDayMode: (
    warehouseId: UUID,
    date: string,
    input: PlanningDayModeUpdate,
    idempotencyKey: UUID,
  ) => request<PlanningDayModeResult>(`/warehouses/${warehouseId}/planning-days/${encodeURIComponent(date)}/mode`, {
    method: 'PUT',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: jsonBody(input),
  }),
  createLogisticsEvent: (
    warehouseId: UUID,
    date: string,
    input: LogisticsEventInput,
    idempotencyKey: UUID,
  ) => request<LogisticsEventResult>(`/warehouses/${warehouseId}/planning-days/${encodeURIComponent(date)}/events`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: jsonBody(input),
  }),
  decideLogisticsAction: (
    actionId: UUID,
    input: LogisticsDecisionInput,
    idempotencyKey: UUID,
  ) => request<LogisticsDecision>(`/logistics-actions/${actionId}/decisions`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: jsonBody(input),
  }),
  applyRecoveryProposal: (proposalId: UUID, expectedVersion: number, idempotencyKey: UUID) =>
    request<RecoveryProposal>(`/recovery-proposals/${proposalId}/apply`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: jsonBody({ expected_version: expectedVersion }),
    }),

  listAvailableDrivers: (warehouseId: UUID) => request<AvailableDriver[]>(`/warehouses/${warehouseId}/available-drivers`),
  createDriver: (warehouseId: UUID, input: DriverInput, idempotencyKey: UUID) =>
    request<Driver>(`/warehouses/${warehouseId}/drivers`, { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: jsonBody(input) }),
  updateDriver: (id: UUID, input: Partial<DriverInput>, expectedVersion: number) =>
    request<Driver>(`/drivers/${id}`, { method: 'PATCH', body: jsonBody({ ...input, expected_version: expectedVersion }) }),
  deleteDriver: (id: UUID, expectedVersion: number) => request<void>(`/drivers/${id}?expected_version=${encodeURIComponent(String(expectedVersion))}`, { method: 'DELETE' }),

  updateVehicle: (id: UUID, input: Partial<VehicleInput>, expectedVersion: number) =>
    request<Vehicle>(`/vehicles/${id}`, { method: 'PATCH', body: jsonBody({ ...input, expected_version: expectedVersion }) }),
  createVehicleConfiguration: (warehouseId: UUID, input: VehicleConfigurationInput, idempotencyKey: UUID) =>
    request<Vehicle>(`/warehouses/${warehouseId}/vehicle-configurations`, { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: jsonBody(input) }),
  updateVehicleConfiguration: (id: UUID, input: VehicleConfigurationInput, expectedVersion: number) =>
    request<Vehicle>(`/vehicles/${id}/configuration`, { method: 'PUT', body: jsonBody({ ...input, vehicle: { ...input.vehicle, expected_version: expectedVersion } }) }),
  deleteVehicle: (id: UUID, expectedVersion: number) => request<void>(`/vehicles/${id}?expected_version=${encodeURIComponent(String(expectedVersion))}`, { method: 'DELETE' }),

  createTrailer: (warehouseId: UUID, input: TrailerInput, idempotencyKey: UUID) =>
    request<Trailer>(`/warehouses/${warehouseId}/trailers`, { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: jsonBody(input) }),
  updateTrailer: (id: UUID, input: Partial<TrailerInput>, expectedVersion: number) =>
    request<Trailer>(`/trailers/${id}`, { method: 'PATCH', body: jsonBody({ ...input, expected_version: expectedVersion }) }),
  deleteTrailer: (id: UUID, expectedVersion: number) => request<void>(`/trailers/${id}?expected_version=${encodeURIComponent(String(expectedVersion))}`, { method: 'DELETE' }),

  createShift: (warehouseId: UUID, input: ShiftInput, idempotencyKey: UUID) =>
    request<DriverShift>(`/warehouses/${warehouseId}/shifts`, { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: jsonBody(input) }),
  updateShift: (id: UUID, input: Partial<ShiftInput>, expectedVersion: number) =>
    request<DriverShift>(`/shifts/${id}`, { method: 'PATCH', body: jsonBody({ ...input, expected_version: expectedVersion }) }),
  deleteShift: (id: UUID, expectedVersion: number) => request<void>(`/shifts/${id}?expected_version=${encodeURIComponent(String(expectedVersion))}`, { method: 'DELETE' }),

  createRequest: (warehouseId: UUID, input: LogisticsRequestInput, idempotencyKey: UUID) =>
    request<LogisticsRequest>(`/warehouses/${warehouseId}/requests`, { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: jsonBody(input) }),
  updateRequest: (id: UUID, input: Partial<LogisticsRequestInput>, expectedVersion: number) =>
    request<LogisticsRequest>(`/requests/${id}`, { method: 'PATCH', body: jsonBody({ ...input, expected_version: expectedVersion }) }),
  createRequestDateOption: (requestId: UUID, input: RequestDateOptionInput, idempotencyKey: UUID) =>
    request<RequestDateOption>(`/requests/${requestId}/date-options`, { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: jsonBody(input) }),
  updateRequestDateOption: (optionId: UUID, input: Partial<RequestDateOptionInput>, expectedVersion: number) =>
    request<RequestDateOption>(`/request-date-options/${optionId}`, { method: 'PATCH', body: jsonBody({ ...input, expected_version: expectedVersion }) }),
  deleteRequestDateOption: (optionId: UUID, expectedVersion: number) =>
    request<void>(`/request-date-options/${optionId}?expected_version=${encodeURIComponent(String(expectedVersion))}`, { method: 'DELETE' }),
  scheduleRequest: (id: UUID, input: RequestScheduleInput, expectedVersion: number) =>
    request<LogisticsRequest>(`/requests/${id}/schedule`, { method: 'POST', body: jsonBody({ ...input, expected_version: expectedVersion }) }),
  deleteRequest: (id: UUID, expectedVersion: number) => request<void>(`/requests/${id}?expected_version=${encodeURIComponent(String(expectedVersion))}`, { method: 'DELETE' }),
  splitRequest: (id: UUID, partQuantities: number[] | undefined, expectedVersion: number) => request<LogisticsRequest>(`/requests/${id}/split`, {
    method: 'POST',
    body: jsonBody({ expected_version: expectedVersion, ...(partQuantities ? { part_quantities: partQuantities } : {}) }),
  }),
  saveRequestPlanningDetails: (id: UUID, input: RequestPlanningDetailsInput, expectedVersion: number) =>
    request<LogisticsRequest>(`/requests/${id}/planning-details`, { method: 'POST', body: jsonBody({ ...input, expected_version: expectedVersion }) }),
  getRequestRescheduleOptions: (id: UUID, input: RequestRescheduleOptionsInput, signal?: AbortSignal) =>
    request<RequestRescheduleOptionsRead>(`/requests/${id}/reschedule-options`, {
      method: 'POST',
      body: jsonBody(input),
      ...(signal ? { signal } : {}),
    }),
  rescheduleRequest: (id: UUID, input: RequestRescheduleInput, idempotencyKey: UUID) =>
    request<RequestRescheduleResultRead>(`/requests/${id}/reschedule`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: jsonBody(input),
    }),
  retryRequestReschedule: (id: UUID, input: RequestRescheduleRetryInput, idempotencyKey: UUID) =>
    request<RequestRescheduleResultRead>(`/requests/${id}/reschedule-retry`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: jsonBody(input),
    }),
  assignRequestToContractor: (id: UUID, contractorWorkerId: UUID) =>
    request<LogisticsRequest>(`/requests/${id}/contractor-assignment`, {
      method: 'POST',
      body: jsonBody({ contractor_worker_id: contractorWorkerId }),
    }),
  dispatchContractor: (
    warehouseId: UUID,
    contractorWorkerId: UUID,
    planningDate: string,
    mode: ContractorDispatchMode,
    requestIds: UUID[] = [],
  ) => request<ContractorDispatchResult>(`/warehouses/${warehouseId}/contractor-dispatches`, {
    method: 'POST',
    body: jsonBody({
      contractor_worker_id: contractorWorkerId,
      planning_date: planningDate,
      mode,
      request_ids: requestIds,
    }),
  }),

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
    const { expected_version, change_type, reason, ...payload } = input;
    return normalizeValidationWithDiagnostics(
      await request<unknown>(`/plans/${planId}/manual-change`, {
        method: 'POST',
        body: jsonBody({ expected_version, change_type, payload, reason }),
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

export async function getWarehouseWorkspace(
  warehouseId: UUID,
  input: WarehouseWorkspacePageInput,
): Promise<WarehouseWorkspace> {
  return normalizeWorkspace(await api.getWarehouseWorkspace(warehouseId, input));
}

/** One decoded server-sent optimizer event. */
export interface OptimizationStreamEvent {
  id: string | null;
  event: string;
  data: string;
}

/** Callbacks for one authenticated optimizer event-stream connection. */
interface OptimizationStreamHandlers {
  onEvent: (event: OptimizationStreamEvent) => void;
  onError?: (error: unknown) => void;
}

function dispatchOptimizationFrame(
  frame: string,
  onEvent: (event: OptimizationStreamEvent) => void,
): void {
  let id: string | null = null;
  let event = 'message';
  const data: string[] = [];
  for (const line of frame.split('\n')) {
    if (!line || line.startsWith(':')) continue;
    const separator = line.indexOf(':');
    const field = separator < 0 ? line : line.slice(0, separator);
    const value = separator < 0 ? '' : line.slice(separator + 1).replace(/^ /, '');
    if (field === 'id') id = value;
    else if (field === 'event') event = value || 'message';
    else if (field === 'data') data.push(value);
  }
  if (data.length) onEvent({ id, event, data: data.join('\n') });
}

function dispatchOptimizationFrames(
  buffer: string,
  onEvent: (event: OptimizationStreamEvent) => void,
): string {
  const normalized = buffer.replaceAll('\r\n', '\n').replaceAll('\r', '\n');
  const frames = normalized.split('\n\n');
  const remainder = frames.pop() ?? '';
  frames.forEach((frame) => dispatchOptimizationFrame(frame, onEvent));
  return remainder;
}

async function consumeOptimizationStream(
  body: ReadableStream<Uint8Array>,
  signal: AbortSignal,
  onEvent: (event: OptimizationStreamEvent) => void,
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  try {
    while (!signal.aborted) {
      const chunk = await reader.read();
      if (chunk.done) break;
      buffer += decoder.decode(chunk.value, { stream: true });
      buffer = dispatchOptimizationFrames(buffer, onEvent);
    }
    buffer += decoder.decode();
    if (buffer.trim()) dispatchOptimizationFrame(buffer.replaceAll('\r\n', '\n').replaceAll('\r', '\n'), onEvent);
  } finally {
    reader.releaseLock();
  }
}

function waitForStreamReconnect(signal: AbortSignal, delayMs: number): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) {
      resolve();
      return;
    }
    const timer = window.setTimeout(done, delayMs);
    signal.addEventListener('abort', done, { once: true });
    function done() {
      window.clearTimeout(timer);
      signal.removeEventListener('abort', done);
      resolve();
    }
  });
}

/** Opens an authenticated optimizer event stream without exposing the bearer in the URL. */
export function startOptimizationEventStream(
  runId: UUID,
  handlers: OptimizationStreamHandlers,
): () => void {
  const controller = new AbortController();
  void (async () => {
    let lastEventId: string | null = null;
    let retryDelayMs = 1_000;
    while (!controller.signal.aborted) {
      try {
        const token = await requireSimulatorAccessToken();
        if (controller.signal.aborted) return;
        const headers = new Headers({
          Accept: 'text/event-stream',
          Authorization: `Bearer ${token}`,
          'Cache-Control': 'no-cache',
        });
        if (lastEventId) headers.set('Last-Event-ID', lastEventId);
        const response = await fetch(simulatorApiUrl(`/optimization-runs/${runId}/stream`), {
          method: 'GET',
          headers,
          cache: 'no-store',
          signal: controller.signal,
        });
        if (!response.ok) await parseResponse<never>(response);
        if (!response.body) throw new Error('Поток событий оптимизации недоступен');
        await consumeOptimizationStream(response.body, controller.signal, (event) => {
          if (event.id) lastEventId = event.id;
          handlers.onEvent(event);
          if (event.event === 'run_terminal') controller.abort();
        });
        retryDelayMs = 1_000;
      } catch (error: unknown) {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status < 500) {
          handlers.onError?.(error);
          return;
        }
      }
      if (!controller.signal.aborted) {
        await waitForStreamReconnect(controller.signal, retryDelayMs);
        retryDelayMs = Math.min(retryDelayMs * 2, 8_000);
      }
    }
  })();
  return () => controller.abort();
}
