import type {
  PlannerWarehouseSettings,
  PlanningSettings,
} from "./planning-types"

export const DEFAULT_PLANNING_SETTINGS: PlanningSettings = {
  vehicle_capacity: 2,
  max_delivery_stops: 2,
  max_pickup_stops: 2,
  deliveries_before_pickups: true,
  max_detour_minutes: 35,
  max_detour_ratio: 1.5,
  max_candidate_neighbors: 8,
  default_load_minutes: 30,
  default_unload_minutes: 20,
  default_pickup_minutes: 30,
  default_depot_turnaround_minutes: 20,
  default_route_buffer_minutes: 15,
  max_customer_wait_minutes: 120,
  city_speed_kmh: 35,
  region_speed_kmh: 65,
  road_factor: 1.25,
  morning_traffic_multiplier: 1.25,
  evening_traffic_multiplier: 1.2,
  default_service_minutes: 30,
  default_buffer_minutes: 15,
  default_cargo_length_mm: 6_000,
  default_cargo_width_mm: 2_400,
  default_cargo_height_mm: 2_400,
  default_cargo_weight_kg: 1_200,
  max_optimization_seconds: 5,
  max_local_search_iterations: 250,
  empty_travel_weight: 1.5,
  detour_weight: 1.4,
  additional_resource_activation_penalty: 180,
  preferred_shift_utilization_percent: 80,
  driver_workload_weight: 3,
  paired_delivery_bonus: 20,
  paired_pickup_bonus: 16,
  unassigned_hard_task_penalty: 10000,
  last_available_date_penalty: 1000,
  allow_soft_overtime: false,
  soft_overtime_limit_minutes: 0,
  max_trace_events: 2000,
  trace_sample_rate: 1,
}

export const SPB = "00000000-0000-0000-0000-000000000001"
export const MSK = "00000000-0000-0000-0000-000000000002"
export function planningFixture(
  overrides: Partial<PlannerWarehouseSettings> = {}
): PlannerWarehouseSettings {
  return {
    warehouse_id: SPB,
    version: 7,
    name: "SPB",
    timezone: "Europe/Moscow",
    latitude: 59.9,
    longitude: 30.3,
    settings: { ...DEFAULT_PLANNING_SETTINGS },
    isochrone_tariffs: [1, 2, 3, 4].map((hour) => ({
      travel_minutes: hour * 60,
      price_rubles: 5000 + hour * 5000,
    })),
    capacity_publish_status: "PUBLISHED",
    ...overrides,
  }
}
