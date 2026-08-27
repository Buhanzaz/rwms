import type { Feature, LineString } from 'geojson';
import { DEFAULT_PLANNING_SETTINGS, EMPTY_METRICS } from '../domain/defaults';
import type {
  DriverRoute,
  LogisticsRequest,
  OptimizationRun,
  PlanMetrics,
  PlanningSettings,
  PlanningTask,
  RouteCycle,
  RouteLeg,
  RoutePlan,
  RouteStop,
  Scenario,
  ScenarioWorkspace,
  UnassignedTask,
  UUID,
  ValidationMessage,
  ValidationResult,
} from '../domain/types';
import { dateInTimeZone } from '../utils/format';
import type { components } from './schema';

export type RawScenario = components['schemas']['ScenarioRead'];

export function normalizePlanningSettings(source: Record<string, unknown>): PlanningSettings {
  const normalized: Record<string, unknown> = { ...DEFAULT_PLANNING_SETTINGS };
  for (const [key, fallback] of Object.entries(DEFAULT_PLANNING_SETTINGS)) {
    const candidate = source[key];
    if (typeof candidate === typeof fallback) normalized[key] = candidate;
  }
  return normalized as unknown as PlanningSettings;
}

export function normalizeScenario(raw: RawScenario | Scenario, now = new Date()): Scenario {
  const timeZone = raw.timezone || 'Europe/Moscow';
  return {
    ...raw,
    timezone: timeZone,
    default_planning_date: raw.default_planning_date ?? dateInTimeZone(now, timeZone),
    settings: normalizePlanningSettings(raw.settings as unknown as Record<string, unknown>),
  };
}

interface RawRouteStop {
  id: UUID;
  sequence: number;
  task_id: UUID | null;
  stop_type: RouteStop['stop_type'];
  planned_arrival: string;
  planned_departure: string;
  service_seconds: number;
  quantity_delta: number;
  load_before: number;
  load_after: number;
  latitude: number;
  longitude: number;
  warnings: Array<Record<string, unknown>>;
  locked: boolean;
}

interface RawRouteSegment {
  id: UUID;
  sequence: number;
  from_stop_id: UUID;
  to_stop_id: UUID;
  departure_at: string;
  arrival_at: string;
  distance_meters: number;
  travel_seconds: number;
  geometry: Record<string, unknown>;
}

interface RawRouteCycle {
  id: UUID;
  driver_shift_id: UUID;
  sequence: number;
  planned_start: string;
  planned_finish: string;
  total_distance_meters: number;
  total_travel_seconds: number;
  total_service_seconds: number;
  empty_distance_meters: number;
  detour_seconds: number;
  score: number;
  locked: boolean;
  manually_changed: boolean;
  metrics: Record<string, unknown>;
  stops: RawRouteStop[];
  segments: RawRouteSegment[];
  explanations: Array<Record<string, unknown>>;
}

export type RawRoutePlan = components['schemas']['RoutePlanRead'];

function numberValue(source: Record<string, unknown>, key: string, fallback = 0): number {
  const value = source[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

export function normalizeMetrics(source: Record<string, unknown>): PlanMetrics {
  return {
    ...EMPTY_METRICS,
    request_count: numberValue(source, 'total_tasks', numberValue(source, 'request_count')),
    assigned_count: numberValue(source, 'assigned_tasks', numberValue(source, 'assigned_count')),
    unassigned_count: numberValue(source, 'unassigned_tasks', numberValue(source, 'unassigned_count')),
    assignment_percent: numberValue(source, 'assignment_percent'),
    cycle_count: numberValue(source, 'cycle_count'),
    total_distance_meters: numberValue(source, 'total_distance_meters'),
    empty_distance_meters: numberValue(source, 'empty_distance_meters'),
    empty_distance_percent: numberValue(source, 'empty_distance_percent'),
    travel_seconds: numberValue(source, 'total_travel_seconds', numberValue(source, 'travel_seconds')),
    service_seconds: numberValue(source, 'total_service_seconds', numberValue(source, 'service_seconds')),
    waiting_seconds: numberValue(source, 'total_waiting_seconds', numberValue(source, 'waiting_seconds')),
    detour_seconds: numberValue(source, 'total_detour_seconds', numberValue(source, 'detour_seconds')),
    paired_deliveries: numberValue(source, 'paired_delivery_count', numberValue(source, 'paired_deliveries')),
    paired_pickups: numberValue(source, 'paired_pickup_count', numberValue(source, 'paired_pickups')),
    average_load: numberValue(source, 'average_vehicle_load', numberValue(source, 'average_load')),
    shift_utilization_percent: numberValue(source, 'shift_utilization_percent'),
    overtime_seconds: numberValue(source, 'overtime_seconds'),
    minimum_buffer_seconds: numberValue(source, 'minimum_buffer_seconds'),
    score: numberValue(source, 'score'),
  };
}

function lineGeometry(raw: Record<string, unknown>, segmentId: UUID): Feature<LineString> {
  const rawType = raw.type;
  const rawCoordinates = raw.coordinates;
  if (rawType !== 'LineString' || !Array.isArray(rawCoordinates) || rawCoordinates.length < 2) {
    throw new Error(`Route segment ${segmentId} has invalid GeoJSON geometry`);
  }
  const coordinates = rawCoordinates.map((point) => {
    if (
      !Array.isArray(point)
      || typeof point[0] !== 'number'
      || typeof point[1] !== 'number'
      || !Number.isFinite(point[0])
      || !Number.isFinite(point[1])
      || point[0] < -180
      || point[0] > 180
      || point[1] < -90
      || point[1] > 90
    ) {
      throw new Error(`Route segment ${segmentId} has invalid WGS84 coordinates`);
    }
    return [point[0], point[1]] as [number, number];
  });
  return { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates } };
}

function textFromRecord(record: Record<string, unknown>): string {
  for (const key of ['summary_ru', 'message_ru', 'description_ru', 'message', 'reason', 'text']) {
    const value = record[key];
    if (typeof value === 'string') return value;
  }
  return JSON.stringify(record);
}

function validationMessage(record: Record<string, unknown>): ValidationMessage {
  const code = typeof record.code === 'string' ? record.code : 'UNKNOWN';
  const cycleId = typeof record.cycle_id === 'string' ? record.cycle_id : undefined;
  const taskId = typeof record.task_id === 'string' ? record.task_id : undefined;
  return {
    code,
    message: textFromRecord(record),
    ...(cycleId ? { cycle_id: cycleId } : {}),
    ...(taskId ? { task_id: taskId } : {}),
  };
}

function explanationLines(records: Array<Record<string, unknown>>): string[] {
  return records.map(textFromRecord).filter(Boolean);
}

function lookupTask(workspace: ScenarioWorkspace, taskId: UUID): { task: PlanningTask; request: LogisticsRequest } | null {
  for (const request of workspace.requests) {
    const task = request.tasks?.find((candidate) => candidate.id === taskId);
    if (task) return { task, request };
  }
  return null;
}

function normalizeCycle(raw: RawRouteCycle, workspace: ScenarioWorkspace, planId: UUID): RouteCycle {
  const stops: RouteStop[] = raw.stops.map((stop) => {
    const task = stop.task_id ? lookupTask(workspace, stop.task_id) : null;
    const zone = task?.task.zone_id ? workspace.zones.find((candidate) => candidate.id === task.task.zone_id) : undefined;
    return {
      ...stop,
      route_cycle_id: raw.id,
      label: task?.request?.name ?? (stop.stop_type.startsWith('DEPOT') ? workspace.warehouses[0]?.name ?? 'Склад' : stop.stop_type),
      zone_code: zone?.code ?? null,
      warnings: undefined,
    };
  });
  const legs: RouteLeg[] = raw.segments
    .sort((a, b) => a.sequence - b.sequence)
    .map((segment) => ({
      id: segment.id,
      from_stop_id: segment.from_stop_id,
      to_stop_id: segment.to_stop_id,
      departure_at: segment.departure_at,
      arrival_at: segment.arrival_at,
      distance_meters: segment.distance_meters,
      travel_seconds: segment.travel_seconds,
      geometry: lineGeometry(segment.geometry, segment.id),
    }));
  return {
    id: raw.id,
    route_plan_id: planId,
    driver_shift_id: raw.driver_shift_id,
    sequence: raw.sequence,
    planned_start: raw.planned_start,
    planned_finish: raw.planned_finish,
    total_distance_meters: raw.total_distance_meters,
    total_travel_seconds: raw.total_travel_seconds,
    total_service_seconds: raw.total_service_seconds,
    empty_distance_meters: raw.empty_distance_meters,
    detour_seconds: raw.detour_seconds,
    score: raw.score,
    locked: raw.locked,
    manually_changed: raw.manually_changed,
    stops,
    legs,
    explanation: explanationLines(raw.explanations),
    warnings: raw.stops.flatMap((stop) => stop.warnings.map(validationMessage)),
  };
}

export function normalizeRoutePlan(raw: RawRoutePlan, workspace: ScenarioWorkspace): RoutePlan {
  const cycles = raw.cycles.map((cycle) => normalizeCycle(cycle, workspace, raw.id));
  const grouped = new Map<UUID, RouteCycle[]>();
  for (const cycle of cycles) grouped.set(cycle.driver_shift_id, [...(grouped.get(cycle.driver_shift_id) ?? []), cycle]);
  const driverRoutes: DriverRoute[] = [...grouped.entries()].map(([shiftId, routeCycles]) => {
    const shift = workspace.shifts.find((candidate) => candidate.id === shiftId);
    const driver = workspace.drivers.find((candidate) => candidate.id === shift?.driver_id);
    const vehicle = workspace.vehicles.find((candidate) => candidate.id === shift?.vehicle_id);
    const orderedCycles = [...routeCycles].sort((a, b) => a.planned_start.localeCompare(b.planned_start));
    const firstCycle = orderedCycles[0];
    const lastCycle = orderedCycles.at(-1);
    const dutySeconds = firstCycle && lastCycle
      ? Math.max(0, (Date.parse(lastCycle.planned_finish) - Date.parse(firstCycle.planned_start)) / 1000)
      : 0;
    const usableShiftSeconds = shift
      ? Math.max(1, (Date.parse(shift.end_at) - Date.parse(shift.start_at)) / 1000 - shift.break_minutes * 60)
      : 0;
    const cycleMetrics = normalizeMetrics({
      total_tasks: routeCycles.flatMap((cycle) => cycle.stops).filter((stop) => stop.task_id).length,
      assigned_tasks: routeCycles.flatMap((cycle) => cycle.stops).filter((stop) => stop.task_id).length,
      cycle_count: routeCycles.length,
      total_distance_meters: routeCycles.reduce((sum, cycle) => sum + cycle.total_distance_meters, 0),
      empty_distance_meters: routeCycles.reduce((sum, cycle) => sum + cycle.empty_distance_meters, 0),
      total_travel_seconds: routeCycles.reduce((sum, cycle) => sum + cycle.total_travel_seconds, 0),
      total_service_seconds: routeCycles.reduce((sum, cycle) => sum + cycle.total_service_seconds, 0),
      total_detour_seconds: routeCycles.reduce((sum, cycle) => sum + cycle.detour_seconds, 0),
      shift_utilization_percent: usableShiftSeconds ? dutySeconds / usableShiftSeconds * 100 : 0,
      score: routeCycles.reduce((sum, cycle) => sum + cycle.score, 0),
    });
    return {
      driver_shift_id: shiftId,
      shift_start_at: shift?.start_at ?? firstCycle?.planned_start ?? raw.created_at,
      shift_end_at: shift?.end_at ?? lastCycle?.planned_finish ?? raw.updated_at,
      driver_id: driver?.id ?? shift?.driver_id ?? shiftId,
      driver_name: driver?.name ?? 'Неизвестный водитель',
      vehicle_id: vehicle?.id ?? shift?.vehicle_id ?? shiftId,
      vehicle_name: vehicle?.name ?? 'Неизвестная машина',
      registration_number: vehicle?.registration_number ?? '—',
      preferred_route_group: shift?.preferred_route_group || driver?.preferred_route_group || '—',
      cycles: routeCycles.sort((a, b) => a.sequence - b.sequence),
      metrics: cycleMetrics,
    };
  });
  const unassigned: UnassignedTask[] = raw.unassigned_tasks.map((item) => {
    const found = lookupTask(workspace, item.task_id);
    if (!found) {
      throw new Error(`Route plan ${raw.id} references missing task ${item.task_id}`);
    }
    const nearest = item.nearest_option ? textFromRecord(item.nearest_option) : null;
    return {
      task: found.task,
      request: found.request,
      reason_codes: item.reason_codes as UnassignedTask['reason_codes'],
      reasons: item.descriptions_ru,
      closest_option: nearest,
      recommendations: item.recommendation_ru ? [item.recommendation_ru] : [],
    };
  });
  const rawRecord = raw as unknown as Record<string, unknown>;
  const notificationLogs = Array.isArray(rawRecord.notification_logs)
    ? rawRecord.notification_logs.flatMap((value) => {
      if (!value || typeof value !== 'object') return [];
      const item = value as Record<string, unknown>;
      if (
        typeof item.id !== 'string'
        || typeof item.plan_id !== 'string'
        || typeof item.request_id !== 'string'
        || typeof item.message !== 'string'
        || typeof item.created_at !== 'string'
      ) return [];
      return [{
        id: item.id,
        plan_id: item.plan_id,
        request_id: item.request_id,
        recipient_name: typeof item.recipient_name === 'string' ? item.recipient_name : '',
        recipient_contact: typeof item.recipient_contact === 'string' ? item.recipient_contact : '',
        message: item.message,
        includes_driver_passport: item.includes_driver_passport === true,
        status: 'SIMULATED_DELIVERED' as const,
        created_at: item.created_at,
      }];
    })
    : [];
  return {
    id: raw.id,
    scenario_id: raw.scenario_id,
    warehouse_id: raw.warehouse_id,
    date: raw.date,
    version: raw.version,
    status: raw.status,
    score: raw.score,
    created_at: raw.created_at,
    updated_at: raw.updated_at,
    driver_routes: driverRoutes,
    unassigned,
    metrics: normalizeMetrics(raw.metrics),
    notification_logs: notificationLogs,
  };
}

export type RawOptimizationRun = components['schemas']['OptimizationRunRead'];

export function normalizeOptimizationRun(raw: RawOptimizationRun, fallbackSettings: PlanningSettings): OptimizationRun {
  return { ...raw, settings_snapshot: { ...fallbackSettings, ...raw.settings_snapshot } };
}

export function normalizeValidationResult(
  value: unknown,
  workspace: ScenarioWorkspace,
  currentPlan: RoutePlan,
): ValidationResult {
  if (typeof value !== 'object' || value === null) {
    return { valid: false, version: currentPlan.version, errors: [{ code: 'INVALID_RESPONSE', message: 'Backend вернул некорректный результат проверки' }], warnings: [] };
  }
  const record = value as Record<string, unknown>;
  if (Array.isArray(record.cycles) && typeof record.id === 'string') {
    const plan = normalizeRoutePlan(record as unknown as RawRoutePlan, workspace);
    return {
      valid: !Array.isArray(record.validation_errors) || record.validation_errors.length === 0,
      version: plan.version,
      errors: Array.isArray(record.validation_errors) ? (record.validation_errors as Array<Record<string, unknown>>).map(validationMessage) : [],
      warnings: Array.isArray(record.validation_warnings) ? (record.validation_warnings as Array<Record<string, unknown>>).map(validationMessage) : [],
      updated_schedule: plan,
      updated_metrics: plan.metrics,
    };
  }
  const errors = Array.isArray(record.errors) ? (record.errors as Array<Record<string, unknown>>).map(validationMessage) : [];
  const warnings = Array.isArray(record.warnings) ? (record.warnings as Array<Record<string, unknown>>).map(validationMessage) : [];
  const schedule = record.updated_schedule && typeof record.updated_schedule === 'object'
    ? normalizeRoutePlan(record.updated_schedule as RawRoutePlan, workspace)
    : undefined;
  return {
    valid: typeof record.valid === 'boolean' ? record.valid : errors.length === 0,
    version: typeof record.version === 'number' ? record.version : schedule?.version ?? currentPlan.version,
    errors,
    warnings,
    ...(schedule ? { updated_schedule: schedule, updated_metrics: schedule.metrics } : {}),
  };
}

export function normalizeWorkspace(workspace: ScenarioWorkspace): ScenarioWorkspace {
  return {
    ...workspace,
    scenario: normalizeScenario(workspace.scenario),
    drivers: workspace.drivers.map((driver) => ({
      ...driver,
      preferred_route_group: driver.preferred_route_group ?? '',
      passport_details: driver.passport_details ?? '',
    })),
    shifts: workspace.shifts.map((shift) => ({ ...shift, preferred_route_group: shift.preferred_route_group ?? '' })),
    requests: workspace.requests.map((request) => ({
      ...request,
      scheduled_date: request.scheduled_date ?? null,
      trailer_access_allowed: request.trailer_access_allowed ?? null,
      include_driver_passport_in_notification: request.include_driver_passport_in_notification ?? false,
      contact_name: request.contact_name ?? '',
      contact_phone: request.contact_phone ?? '',
      date_options: request.date_options ?? [],
      tasks: request.tasks ?? [],
      zone_status: request.zone_status ??
        ((request as LogisticsRequest & { zone_classification_status?: string }).zone_classification_status === 'OUTSIDE_ZONES'
          ? 'OUTSIDE_ZONES'
          : (request as LogisticsRequest & { zone_is_stale?: boolean }).zone_is_stale
            ? 'STALE'
            : 'CURRENT'),
    })),
  };
}
