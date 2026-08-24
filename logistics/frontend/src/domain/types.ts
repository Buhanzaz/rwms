import type { Feature, LineString, MultiPolygon, Point, Polygon } from 'geojson';

export type UUID = string;
export type IsoDate = string;
export type IsoDateTime = string;
export type RequestType = 'DELIVERY' | 'PICKUP';
export type RequestStatus =
  | 'DRAFT'
  | 'READY'
  | 'PLANNED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'UNASSIGNED';
export type PlanStatus = 'DRAFT' | 'GENERATED' | 'VALIDATED' | 'CONFIRMED' | 'ARCHIVED';
export type StopType = 'DEPOT_LOAD' | 'DELIVERY' | 'PICKUP' | 'DEPOT_UNLOAD' | 'DEPOT_RETURN';
export type SimulationStatus =
  | 'WAITING_SHIFT'
  | 'LOADING'
  | 'DRIVING'
  | 'DELIVERING'
  | 'PICKING_UP'
  | 'RETURNING'
  | 'UNLOADING'
  | 'BREAK'
  | 'FINISHED'
  | 'DELAYED';

export interface PlanningSettings {
  vehicle_capacity: number;
  max_delivery_stops: number;
  max_pickup_stops: number;
  deliveries_before_pickups: boolean;
  max_detour_minutes: number;
  max_detour_ratio: number;
  max_candidate_neighbors: number;
  default_load_minutes: number;
  default_unload_minutes: number;
  default_pickup_minutes: number;
  default_depot_turnaround_minutes: number;
  default_route_buffer_minutes: number;
  city_speed_kmh: number;
  region_speed_kmh: number;
  road_factor: number;
  morning_traffic_multiplier: number;
  evening_traffic_multiplier: number;
  default_service_minutes: number;
  default_buffer_minutes: number;
  max_optimization_seconds: number;
  max_local_search_iterations: number;
  empty_travel_weight: number;
  detour_weight: number;
  cross_group_penalty: number;
  driver_preference_bonus: number;
  paired_delivery_bonus: number;
  paired_pickup_bonus: number;
  unassigned_hard_task_penalty: number;
  last_available_date_penalty: number;
  allow_soft_overtime: boolean;
  soft_overtime_limit_minutes: number;
  max_trace_events: number;
  trace_sample_rate: number;
  trace_enabled?: boolean;
}

export interface Scenario {
  id: UUID;
  name: string;
  description: string;
  timezone: string;
  default_planning_date: IsoDate;
  created_at: IsoDateTime;
  updated_at: IsoDateTime;
  settings: PlanningSettings;
  seed?: number | null;
}

export interface Warehouse {
  id: UUID;
  scenario_id: UUID;
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

export interface Zone {
  id: UUID;
  scenario_id: UUID;
  name: string;
  code: string;
  route_group: string;
  geometry: Polygon | MultiPolygon;
  version: number;
  priority: number;
  locked: boolean;
  created_at: IsoDateTime;
  updated_at: IsoDateTime;
  stale_request_count?: number;
}

export type ZoneRelationType = 'ADJACENT' | 'PREFERRED' | 'ALLOWED' | 'DISCOURAGED' | 'BLOCKED';

export interface ZoneRelation {
  id: UUID;
  from_zone_id: UUID;
  to_zone_id: UUID;
  relation_type: ZoneRelationType;
  delivery_pair_allowed: boolean;
  pickup_allowed: boolean;
  max_detour_minutes: number;
  max_detour_ratio: number;
  penalty: number;
  is_bidirectional: boolean;
}

export interface Driver {
  id: UUID;
  scenario_id: UUID;
  external_worker_id?: UUID | null;
  name: string;
  preferred_route_group: string;
  active: boolean;
  notes: string;
}

export interface Vehicle {
  id: UUID;
  scenario_id: UUID;
  name: string;
  registration_number: string;
  capacity: number;
  active: boolean;
  average_speed_city: number;
  average_speed_region: number;
  notes: string;
}

export interface DriverShift {
  id: UUID;
  scenario_id?: UUID;
  driver_id: UUID;
  vehicle_id: UUID;
  date: IsoDate;
  start_at: IsoDateTime;
  end_at: IsoDateTime;
  break_minutes: number;
  preferred_route_group: string;
  active: boolean;
}

export interface RequestDateOption {
  id?: UUID;
  request_id?: UUID;
  date: IsoDate;
  priority: number;
  window_start: string | null;
  window_end: string | null;
  is_hard: boolean;
}

export interface LogisticsRequest {
  id: UUID;
  scenario_id: UUID;
  type: RequestType;
  name: string;
  address_label: string;
  latitude: number;
  longitude: number;
  quantity: number;
  service_minutes: number;
  priority: number;
  status: RequestStatus;
  zone_id: UUID | null;
  zone_version: number | null;
  split_allowed: boolean;
  notes: string;
  created_at: IsoDateTime;
  updated_at: IsoDateTime;
  date_options: RequestDateOption[];
  tasks?: PlanningTask[];
  zone_status?: 'CURRENT' | 'STALE' | 'OUTSIDE_ZONES';
}

export interface PlanningTask {
  id: UUID;
  request_id: UUID;
  part_number: number;
  quantity: number;
  type: RequestType;
  latitude: number;
  longitude: number;
  zone_id: UUID | null;
  zone_version: number | null;
  service_minutes: number;
  priority: number;
  status: string;
  locked?: boolean;
}

export interface RouteLeg {
  id?: UUID;
  from_stop_id?: UUID;
  to_stop_id?: UUID;
  departure_at: IsoDateTime;
  arrival_at: IsoDateTime;
  distance_meters: number;
  travel_seconds: number;
  geometry: Feature<LineString>;
}

export interface RouteStop {
  id: UUID;
  route_cycle_id: UUID;
  sequence: number;
  task_id: UUID | null;
  stop_type: StopType;
  planned_arrival: IsoDateTime;
  planned_departure: IsoDateTime;
  service_seconds: number;
  quantity_delta: number;
  load_before: number;
  load_after: number;
  latitude: number;
  longitude: number;
  label?: string;
  zone_code?: string | null;
  completed?: boolean;
  locked?: boolean;
}

export interface RouteCycle {
  id: UUID;
  route_plan_id: UUID;
  driver_shift_id: UUID;
  sequence: number;
  planned_start: IsoDateTime;
  planned_finish: IsoDateTime;
  total_distance_meters: number;
  total_travel_seconds: number;
  total_service_seconds: number;
  empty_distance_meters: number;
  detour_seconds: number;
  score: number;
  locked: boolean;
  manually_changed?: boolean;
  stops: RouteStop[];
  legs: RouteLeg[];
  explanation: string[];
  warnings: ValidationMessage[];
}

export interface DriverRoute {
  driver_shift_id: UUID;
  driver_id: UUID;
  driver_name: string;
  vehicle_id: UUID;
  vehicle_name: string;
  registration_number: string;
  preferred_route_group: string;
  cycles: RouteCycle[];
  metrics: PlanMetrics;
}

export interface PlanMetrics {
  request_count: number;
  assigned_count: number;
  unassigned_count: number;
  assignment_percent: number;
  cycle_count: number;
  total_distance_meters: number;
  empty_distance_meters: number;
  empty_distance_percent: number;
  travel_seconds: number;
  service_seconds: number;
  waiting_seconds: number;
  detour_seconds: number;
  paired_deliveries: number;
  paired_pickups: number;
  average_load: number;
  shift_utilization_percent: number;
  overtime_seconds: number;
  minimum_buffer_seconds: number;
  score: number;
}

export type UnassignedReasonCode =
  | 'NO_ACTIVE_DRIVER'
  | 'NO_ACTIVE_VEHICLE'
  | 'NO_SHIFT_CAPACITY'
  | 'TIME_WINDOW_CONFLICT'
  | 'SHIFT_LIMIT_EXCEEDED'
  | 'ZONE_RELATION_BLOCKED'
  | 'DETOUR_TOO_LARGE'
  | 'OUTSIDE_ZONES'
  | 'REQUEST_NOT_READY'
  | 'NO_ALLOWED_DATE'
  | 'DUPLICATE_ASSIGNMENT_CONFLICT'
  | 'NO_FEASIBLE_DELIVERY_PAIR'
  | 'NO_FEASIBLE_PICKUP_PAIR'
  | 'UNKNOWN';

export interface UnassignedTask {
  task: PlanningTask;
  request?: LogisticsRequest;
  reason_codes: UnassignedReasonCode[];
  reasons: string[];
  closest_option?: string | null;
  recommendations: string[];
}

export interface RoutePlan {
  id: UUID;
  scenario_id: UUID;
  warehouse_id: UUID;
  date: IsoDate;
  version: number;
  status: PlanStatus;
  score: number;
  created_at: IsoDateTime;
  updated_at: IsoDateTime;
  driver_routes: DriverRoute[];
  unassigned: UnassignedTask[];
  metrics: PlanMetrics;
}

export interface ValidationMessage {
  code: string;
  message: string;
  cycle_id?: UUID;
  task_id?: UUID;
}

export interface ValidationResult {
  valid: boolean;
  version: number;
  errors: ValidationMessage[];
  warnings: ValidationMessage[];
  updated_schedule?: RoutePlan;
  updated_metrics?: PlanMetrics;
}

export interface OptimizationTraceEvent {
  id: UUID;
  optimization_run_id: UUID;
  sequence: number;
  event_type: string;
  payload: Record<string, unknown>;
  created_at: IsoDateTime;
}

export interface OptimizationRun {
  id: UUID;
  scenario_id: UUID;
  plan_id: UUID | null;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT';
  phase?: string;
  progress?: number;
  started_at: IsoDateTime | null;
  finished_at: IsoDateTime | null;
  seed: number;
  settings_snapshot: PlanningSettings;
  initial_score: number | null;
  final_score: number | null;
  error_message: string | null;
  stopped_by_limit: boolean;
  cancel_requested: boolean;
}

export interface ScenarioWorkspace {
  scenario: Scenario;
  warehouses: Warehouse[];
  zones: Zone[];
  zone_relations: ZoneRelation[];
  drivers: Driver[];
  vehicles: Vehicle[];
  shifts: DriverShift[];
  requests: LogisticsRequest[];
  plans?: RoutePlan[];
}

export type MapSelection =
  | { kind: 'warehouse'; id: UUID }
  | { kind: 'zone'; id: UUID }
  | { kind: 'request'; id: UUID }
  | { kind: 'driver'; id: UUID }
  | { kind: 'vehicle'; id: UUID }
  | { kind: 'shift'; id: UUID }
  | { kind: 'cycle'; id: UUID }
  | { kind: 'unassigned'; id: UUID }
  | null;

export interface MapClickDraft {
  longitude: number;
  latitude: number;
  kind: 'warehouse' | 'request';
}

export interface SimulationOverride {
  id: UUID;
  kind: 'DELAY' | 'DRIVER_UNAVAILABLE';
  driver_shift_id: UUID;
  effective_at: IsoDateTime;
  delay_minutes: number;
  reason: string;
}

export interface SimulationVehicleState {
  driver_shift_id: UUID;
  driver_name: string;
  vehicle_name: string;
  registration_number: string;
  position: Feature<Point>;
  status: SimulationStatus;
  load: number;
  next_stop_label: string | null;
  eta: IsoDateTime | null;
  active_cycle_id: UUID | null;
  active_leg_index: number | null;
  delayed_by_minutes: number;
}

export interface SimulationEvent {
  id: string;
  timestamp: IsoDateTime;
  driver_shift_id: UUID;
  label: string;
  status: SimulationStatus;
}

export interface SimulationDerivedState {
  timestamp: IsoDateTime;
  vehicles: SimulationVehicleState[];
  events: SimulationEvent[];
  completed_stop_ids: UUID[];
  active_stop_ids: UUID[];
  affected_task_ids: UUID[];
  warnings: ValidationMessage[];
}

export type GeoPointFeature = Feature<Point>;
