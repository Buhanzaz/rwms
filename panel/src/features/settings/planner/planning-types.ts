import type { MultiPolygon } from "geojson"

export interface PlanningSettings {
  vehicle_capacity: number
  max_delivery_stops: number
  max_pickup_stops: number
  deliveries_before_pickups: true
  max_detour_minutes: number
  max_detour_ratio: number
  max_candidate_neighbors: number
  default_load_minutes: number
  default_unload_minutes: number
  default_pickup_minutes: number
  default_depot_turnaround_minutes: number
  default_route_buffer_minutes: number
  max_customer_wait_minutes: number
  city_speed_kmh: number
  region_speed_kmh: number
  road_factor: number
  morning_traffic_multiplier: number
  evening_traffic_multiplier: number
  default_service_minutes: number
  default_buffer_minutes: number
  default_cargo_length_mm: number
  default_cargo_width_mm: number
  default_cargo_height_mm: number
  default_cargo_weight_kg: number
  max_optimization_seconds: number
  max_local_search_iterations: number
  empty_travel_weight: number
  detour_weight: number
  additional_resource_activation_penalty: number
  preferred_shift_utilization_percent: number
  driver_workload_weight: number
  paired_delivery_bonus: number
  paired_pickup_bonus: number
  unassigned_hard_task_penalty: number
  last_available_date_penalty: number
  allow_soft_overtime: boolean
  soft_overtime_limit_minutes: number
  max_trace_events: number
  trace_sample_rate: number
}

/** One inclusive hourly travel-time boundary and its delivery price. */
export interface IsochroneTariff {
  travel_minutes: number
  price_rubles: number
}

/** Exceptional policy kinds layered over normal isochrone reach and pricing. */
export type PolicyZoneKind = "SPECIAL_PRICE" | "FORBIDDEN" | "NO_TRAILER"

/** Exact server-owned polygon and values for one warehouse exception. */
export interface WarehousePolicyZone {
  id: string
  warehouse_id: string
  name: string
  kind: PolicyZoneKind
  color: string
  geometry: MultiPolygon
  version: number
  delivery_price_rubles: number | null
  pickup_price_rubles: number | null
  created_at: string
  updated_at: string
}

/** Mutable values accepted by the administrative policy-zone boundary. */
export interface PolicyZoneInput {
  name: string
  kind: PolicyZoneKind
  color?: string | null
  geometry: MultiPolygon
  delivery_price_rubles: number | null
  pickup_price_rubles: number | null
}

/** Administrative representation uses only the canonical warehouse identity. */
export interface PlannerWarehouseSettings {
  warehouse_id: string
  version: number
  name: string
  timezone: string
  latitude: number
  longitude: number
  settings: PlanningSettings
  isochrone_tariffs: IsochroneTariff[]
  capacity_publish_status: "NOT_REQUESTED" | "PENDING" | "PUBLISHED" | "FAILED"
}
export type PlanningSettingsInput = Pick<
  PlannerWarehouseSettings,
  "settings" | "isochrone_tariffs"
>

export const numericPlanningKeys = [
  "vehicle_capacity",
  "max_delivery_stops",
  "max_pickup_stops",
  "max_detour_minutes",
  "max_detour_ratio",
  "max_candidate_neighbors",
  "default_load_minutes",
  "default_unload_minutes",
  "default_pickup_minutes",
  "default_depot_turnaround_minutes",
  "default_route_buffer_minutes",
  "max_customer_wait_minutes",
  "city_speed_kmh",
  "region_speed_kmh",
  "road_factor",
  "morning_traffic_multiplier",
  "evening_traffic_multiplier",
  "default_service_minutes",
  "default_buffer_minutes",
  "default_cargo_length_mm",
  "default_cargo_width_mm",
  "default_cargo_height_mm",
  "default_cargo_weight_kg",
  "max_optimization_seconds",
  "max_local_search_iterations",
  "empty_travel_weight",
  "detour_weight",
  "additional_resource_activation_penalty",
  "preferred_shift_utilization_percent",
  "driver_workload_weight",
  "paired_delivery_bonus",
  "paired_pickup_bonus",
  "unassigned_hard_task_penalty",
  "last_available_date_penalty",
  "soft_overtime_limit_minutes",
  "max_trace_events",
  "trace_sample_rate",
] as const satisfies ReadonlyArray<keyof PlanningSettings>
