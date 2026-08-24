import type {
  Driver,
  DriverShift,
  LogisticsRequest,
  OptimizationRun,
  PlanningSettings,
  RequestDateOption,
  RoutePlan,
  ScenarioWorkspace,
  UUID,
  Vehicle,
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
    throw new ApiError(0, null, 'Backend недоступен. Проверьте контейнер и соединение.');
  }
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
  geometry: Zone['geometry'];
  priority: number;
  locked: boolean;
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
  service_minutes: number;
  priority: number;
  status: LogisticsRequest['status'];
  split_allowed: boolean;
  notes: string;
  date_options: RequestDateOption[];
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

export const api = {
  health: () => request<{ status: string }>('/health'),

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
  deleteVehicle: (id: UUID) => request<void>(`/vehicles/${id}`, { method: 'DELETE' }),

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
    normalizeRoutePlan(await request<RawRoutePlan>(`/plans/${id}`), workspace),
  validatePlan: async (id: UUID, expectedVersion: number, workspace: ScenarioWorkspace, currentPlan: RoutePlan) =>
    normalizeValidationResult(await request<unknown>(`/plans/${id}/validate`, {
      method: 'POST',
      body: jsonBody({ expected_version: expectedVersion }),
    }), workspace, currentPlan),
  clonePlan: async (id: UUID, name: string | undefined, workspace: ScenarioWorkspace) =>
    normalizeRoutePlan(
      await request<RawRoutePlan>(`/plans/${id}/clone`, { method: 'POST', body: jsonBody(name ? { name } : {}) }),
      workspace,
    ),
  confirmPlan: async (id: UUID, expectedVersion: number, acceptWarnings: boolean, workspace: ScenarioWorkspace) =>
    normalizeRoutePlan(await request<RawRoutePlan>(`/plans/${id}/confirm`, {
      method: 'POST',
      body: jsonBody({ expected_version: expectedVersion, accept_warnings: acceptWarnings }),
    }), workspace),
  manualChange: async (planId: UUID, input: ManualChangeInput, workspace: ScenarioWorkspace, currentPlan: RoutePlan) => {
    const { expected_version, change_type, changed_by, reason, ...payload } = input;
    return normalizeValidationResult(
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
  ) => normalizeRoutePlan(
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
    .then((value) => normalizeValidationResult(value, workspace, currentPlan)),
  markDriverUnavailable: (planId: UUID, input: {
    expected_version: number;
    driver_shift_id: UUID;
    effective_at: string;
    reason: string;
    persist: boolean;
  }, workspace: ScenarioWorkspace, currentPlan: RoutePlan) => request<unknown>(`/plans/${planId}/simulation/driver-unavailable`, {
    method: 'POST',
    body: jsonBody(input),
  }).then((value) => normalizeValidationResult(value, workspace, currentPlan)),
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
