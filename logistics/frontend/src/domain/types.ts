import type { Feature, LineString, Point } from 'geojson';

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
  deliveries_before_pickups: true;
  max_detour_minutes: number;
  max_detour_ratio: number;
  max_candidate_neighbors: number;
  default_load_minutes: number;
  default_unload_minutes: number;
  default_pickup_minutes: number;
  default_depot_turnaround_minutes: number;
  default_route_buffer_minutes: number;
  max_customer_wait_minutes: number;
  city_speed_kmh: number;
  region_speed_kmh: number;
  road_factor: number;
  morning_traffic_multiplier: number;
  evening_traffic_multiplier: number;
  default_service_minutes: number;
  default_buffer_minutes: number;
  default_cargo_length_mm: number;
  default_cargo_width_mm: number;
  default_cargo_height_mm: number;
  default_cargo_weight_kg: number;
  max_optimization_seconds: number;
  max_local_search_iterations: number;
  empty_travel_weight: number;
  detour_weight: number;
  additional_resource_activation_penalty: number;
  preferred_shift_utilization_percent: number;
  driver_workload_weight: number;
  paired_delivery_bonus: number;
  paired_pickup_bonus: number;
  unassigned_hard_task_penalty: number;
  last_available_date_penalty: number;
  allow_soft_overtime: boolean;
  soft_overtime_limit_minutes: number;
  max_trace_events: number;
  trace_sample_rate: number;
}

/** One inclusive hourly travel-time boundary and its delivery price. */
export interface IsochroneTariff {
  travel_minutes: number;
  price_rubles: number;
}

/** One RWMS-bound warehouse that owns its operational planning workspace. */
export interface Warehouse {
  id: UUID;
  external_warehouse_id: UUID;
  external_warehouse_version: number;
  name: string;
  city?: string | null;
  address: string | null;
  timezone: string;
  latitude: number;
  longitude: number;
  representative: boolean;
  routing_ready: boolean;
  default_planning_date: IsoDate | null;
  seed: number;
  settings: PlanningSettings;
  capacity_generation: number;
  loading_minutes: number;
  unloading_minutes: number;
  turnaround_minutes: number;
  working_day_start: string;
  working_day_end: string;
  isochrone_tariffs: IsochroneTariff[];
  created_at: IsoDateTime;
  updated_at: IsoDateTime;
}

/** Canonical RWMS warehouse identity offered for a new local binding. */
export interface AvailableWarehouse {
  warehouse_id: UUID;
  warehouse_version: number;
  name: string;
  city: string;
  address: string | null;
  latitude: number | null;
  longitude: number | null;
  timezone: string;
  representative: boolean;
  routing_ready: boolean;
  routing_unavailable_reason?: string | null;
  local_warehouse_id?: UUID | null;
}

export interface Driver {
  id: UUID;
  warehouse_id: UUID;
  external_worker_id?: UUID | null;
  name: string;
  rwms_assignment_mode: 'ASSIGNED_DRIVER' | 'WAREHOUSE_DRIVERS';
  passport_details?: string;
  active: boolean;
  notes: string;
}

export interface Vehicle {
  id: UUID;
  warehouse_id: UUID;
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
  load_profiles?: VehicleLoadProfile[];
}

export interface Trailer {
  id: UUID;
  warehouse_id: UUID;
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

export type VehicleLoadConfigurationType =
  | 'EMPTY_TRUCK'
  | 'CARGO_ON_TRUCK'
  | 'EMPTY_COMBINATION'
  | 'CARGO_ON_TRUCK_WITH_TRAILER'
  | 'CARGO_ON_TRAILER_WITH_TRAILER'
  | 'TWO_CARGO_SPLIT';

export interface VehicleLoadProfile {
  configuration_type: VehicleLoadConfigurationType;
  max_actual_axle_load_kg: number;
}

/** Repeating daily driver assignment for an inclusive period inside one month. */
export interface DriverShift {
  id: UUID;
  warehouse_id: UUID;
  driver_id: UUID;
  vehicle_id: UUID;
  date_from: IsoDate;
  date_to: IsoDate;
  start_time: string;
  end_time: string;
  break_minutes: number;
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
  warehouse_id: UUID;
  source_system?: string | null;
  external_id?: UUID | null;
  type: RequestType;
  name: string;
  address_label: string;
  latitude: number;
  longitude: number;
  quantity: number;
  trailer_access_allowed?: boolean | null;
  include_driver_passport_in_notification?: boolean;
  contact_name?: string;
  contact_phone?: string;
  service_minutes: number;
  priority: number;
  mandatory: boolean;
  status: RequestStatus;
  split_allowed: boolean;
  notes: string;
  created_at: IsoDateTime;
  updated_at: IsoDateTime;
  scheduled_date: IsoDate | null;
  cargo_length_mm?: number | null;
  cargo_width_mm?: number | null;
  cargo_height_mm?: number | null;
  cargo_weight_kg?: number | null;
  date_options: RequestDateOption[];
  tasks?: PlanningTask[];
}

export interface PlanningTask {
  id: UUID;
  request_id: UUID;
  part_number: number;
  quantity: number;
  trailer_access_allowed?: boolean;
  type: RequestType;
  latitude: number;
  longitude: number;
  service_minutes: number;
  priority: number;
  mandatory: boolean;
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
  routing_profile_snapshot?: RoutingProfileSnapshot | null;
  routing_provider?: string | null;
  osm_data_version?: string | null;
  routed_at?: IsoDateTime | null;
}

export type CargoPlacementPosition = 'TRUCK_PLATFORM' | 'TRAILER_PLATFORM';

export interface RoutingCargoPlacementSnapshot {
  cargoId: string;
  position: CargoPlacementPosition;
  lengthMm: number;
  widthMm: number;
  heightMm: number;
  weightKg: number;
}

export interface RoutingProfileSnapshot {
  vehicleId: UUID;
  trailerId: UUID | null;
  trailerAttached: boolean;
  isHgv: boolean;
  cargoCount: number;
  cargoPlacements: RoutingCargoPlacementSnapshot[];
  configurationType: VehicleLoadConfigurationType;
  effectiveHeightMeters: number;
  effectiveWidthMeters: number;
  effectiveLengthMeters: number;
  actualWeightTons: number;
  maxAxleLoadTons: number;
  axleCount: number;
  routingProvider?: string | null;
  osmDataVersion?: string | null;
  calculatedAt?: IsoDateTime | null;
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
  cross_warehouse_service?: CrossWarehouseServiceContext;
}

/** Immutable planning facts for a one-off visit from a supporting warehouse. */
export interface CrossWarehouseServiceContext {
  execution_mode: 'CROSS_WAREHOUSE_SERVICE';
  service_warehouse_id: UUID;
  resource_origin_warehouse_id: UUID;
  resource_origin_warehouse_name: string;
  support_warehouse_link_id: UUID;
  driver_id: UUID;
  driver_worker_id: UUID;
  driver_name: string;
  vehicle_id: UUID;
  vehicle_name: string;
  vehicle_registration_number: string;
  available_at_served: IsoDateTime;
  latest_served_finish: IsoDateTime;
  inbound_travel_minutes: number;
  return_travel_minutes: number;
  positioning_distance_meters: number;
  inbound_distance_meters: number;
  return_distance_meters: number;
  positioning_outbound_geometry?: Feature<LineString>;
  positioning_return_geometry?: Feature<LineString>;
  available_transfer_cabin_capacity: number;
  trailer_available: boolean;
  outbound_positioning_empty: boolean;
  empty_positioning_reason_required: boolean;
  returns_to_origin: boolean;
  changes_operational_warehouse: boolean;
  reason_codes: string[];
}

export interface DriverRoute {
  driver_shift_id: UUID;
  shift_start_at: IsoDateTime;
  shift_end_at: IsoDateTime;
  driver_id: UUID;
  driver_name: string;
  vehicle_id: UUID;
  vehicle_name: string;
  registration_number: string;
  cycles: RouteCycle[];
  metrics: PlanMetrics;
  cross_warehouse_service?: CrossWarehouseServiceContext;
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
  | 'DETOUR_TOO_LARGE'
  | 'REQUEST_NOT_READY'
  | 'NO_ALLOWED_DATE'
  | 'DUPLICATE_ASSIGNMENT_CONFLICT'
  | 'NO_FEASIBLE_DELIVERY_PAIR'
  | 'NO_FEASIBLE_PICKUP_PAIR'
  | 'TRAILER_ACCESS_NOT_ALLOWED'
  | 'CARGO_TOO_HEAVY'
  | 'CARGO_TOO_LONG'
  | 'CARGO_TOO_WIDE'
  | 'CARGO_TOO_HIGH'
  | 'TRAILER_REQUIRED'
  | 'NO_COMPATIBLE_TRAILER'
  | 'AXLE_LOAD_EXCEEDED'
  | 'NO_SAFE_ROUTE'
  | 'ROUTING_PROVIDER_UNAVAILABLE'
  | 'ROUTING_PROFILE_INCOMPLETE'
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
  notification_logs?: PlanNotificationLog[];
  manually_changed: boolean;
}

/** Simulated contact notification written only after final plan confirmation. */
export interface PlanNotificationLog {
  id: UUID;
  plan_id: UUID;
  request_id: UUID;
  recipient_name: string;
  recipient_contact: string;
  message: string;
  includes_driver_passport: boolean;
  status: 'SIMULATED_DELIVERED';
  created_at: IsoDateTime;
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
  warehouse_id: UUID;
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

/** Selected warehouse resources plus all warehouses shown on the common map. */
export interface WarehouseWorkspace {
  warehouse: Warehouse;
  warehouses: Warehouse[];
  drivers: Driver[];
  vehicles: Vehicle[];
  trailers?: Trailer[];
  shifts: DriverShift[];
  requests: LogisticsRequest[];
  plans?: RoutePlan[];
  /** Explicit non-fatal warning when saved demand is shown after an incomplete RWMS refresh. */
  rwms_refresh_warning?: string;
}

export type MapSelection =
  | { kind: 'warehouse'; id: UUID }
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
  kind: 'request';
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
