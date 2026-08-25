"""Framework-neutral planning inputs, outputs, and invariant vocabulary."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from enum import StrEnum
from math import isfinite
from typing import TYPE_CHECKING

from app.routing import GeoJsonLineString, GeoPoint
from app.routing.models import require_aware

if TYPE_CHECKING:
    from app.routing.truck_profile import (
        CargoDimensions,
        OperationalAxleLoadProfile,
        TrailerSpec,
        VehicleRoutingSpec,
    )


class TaskType(StrEnum):
    """Direction in which a cabin changes vehicle load at a customer stop."""

    DELIVERY = "DELIVERY"
    PICKUP = "PICKUP"


class RequestStatus(StrEnum):
    """Planner-relevant lifecycle state of a logistics request."""

    DRAFT = "DRAFT"
    READY = "READY"
    PLANNED = "PLANNED"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"
    CANCELLED = "CANCELLED"
    UNASSIGNED = "UNASSIGNED"


class RelationType(StrEnum):
    """Operational meaning of a directed transition between two zones."""

    ADJACENT = "ADJACENT"
    PREFERRED = "PREFERRED"
    ALLOWED = "ALLOWED"
    DISCOURAGED = "DISCOURAGED"
    BLOCKED = "BLOCKED"


class StopType(StrEnum):
    """Observable operation performed at a route stop."""

    DEPOT_LOAD = "DEPOT_LOAD"
    DELIVERY = "DELIVERY"
    PICKUP = "PICKUP"
    DEPOT_UNLOAD = "DEPOT_UNLOAD"
    DEPOT_RETURN = "DEPOT_RETURN"


class UnassignedReasonCode(StrEnum):
    """Stable machine-readable reason why a task is absent from a plan."""

    NO_ACTIVE_DRIVER = "NO_ACTIVE_DRIVER"
    NO_ACTIVE_VEHICLE = "NO_ACTIVE_VEHICLE"
    NO_SHIFT_CAPACITY = "NO_SHIFT_CAPACITY"
    TIME_WINDOW_CONFLICT = "TIME_WINDOW_CONFLICT"
    SHIFT_LIMIT_EXCEEDED = "SHIFT_LIMIT_EXCEEDED"
    ZONE_RELATION_BLOCKED = "ZONE_RELATION_BLOCKED"
    DETOUR_TOO_LARGE = "DETOUR_TOO_LARGE"
    OUTSIDE_ZONES = "OUTSIDE_ZONES"
    REQUEST_NOT_READY = "REQUEST_NOT_READY"
    NO_ALLOWED_DATE = "NO_ALLOWED_DATE"
    DUPLICATE_ASSIGNMENT_CONFLICT = "DUPLICATE_ASSIGNMENT_CONFLICT"
    NO_FEASIBLE_DELIVERY_PAIR = "NO_FEASIBLE_DELIVERY_PAIR"
    NO_FEASIBLE_PICKUP_PAIR = "NO_FEASIBLE_PICKUP_PAIR"
    CARGO_TOO_HEAVY = "CARGO_TOO_HEAVY"
    CARGO_TOO_LONG = "CARGO_TOO_LONG"
    CARGO_TOO_WIDE = "CARGO_TOO_WIDE"
    CARGO_TOO_HIGH = "CARGO_TOO_HIGH"
    TRAILER_REQUIRED = "TRAILER_REQUIRED"
    NO_COMPATIBLE_TRAILER = "NO_COMPATIBLE_TRAILER"
    AXLE_LOAD_EXCEEDED = "AXLE_LOAD_EXCEEDED"
    NO_SAFE_ROUTE = "NO_SAFE_ROUTE"
    ROUTING_PROVIDER_UNAVAILABLE = "ROUTING_PROVIDER_UNAVAILABLE"
    ROUTING_PROFILE_INCOMPLETE = "ROUTING_PROFILE_INCOMPLETE"
    UNKNOWN = "UNKNOWN"


class ValidationErrorCode(StrEnum):
    """Hard route-plan invariant violation that prevents confirmation."""

    CAPACITY_EXCEEDED = "CAPACITY_EXCEEDED"
    NEGATIVE_LOAD = "NEGATIVE_LOAD"
    PICKUP_BEFORE_DELIVERY = "PICKUP_BEFORE_DELIVERY"
    TIME_WINDOW_VIOLATION = "TIME_WINDOW_VIOLATION"
    DRIVER_OVERLAP = "DRIVER_OVERLAP"
    VEHICLE_OVERLAP = "VEHICLE_OVERLAP"
    SHIFT_EXCEEDED = "SHIFT_EXCEEDED"
    TASK_ALREADY_ASSIGNED = "TASK_ALREADY_ASSIGNED"
    ROUTE_NOT_RETURNED_TO_DEPOT = "ROUTE_NOT_RETURNED_TO_DEPOT"
    INVALID_TASK_QUANTITY = "INVALID_TASK_QUANTITY"
    LOAD_DISCONTINUITY = "LOAD_DISCONTINUITY"
    INVALID_TIME = "INVALID_TIME"


class ValidationWarningCode(StrEnum):
    """Non-fatal route quality or schedule risk surfaced to an operator."""

    HIGH_DETOUR = "HIGH_DETOUR"
    CROSS_ROUTE_GROUP = "CROSS_ROUTE_GROUP"
    LOW_TIME_BUFFER = "LOW_TIME_BUFFER"
    SOFT_WINDOW_RISK = "SOFT_WINDOW_RISK"
    OVERTIME_WARNING = "OVERTIME_WARNING"
    INEFFICIENT_EMPTY_RUN = "INEFFICIENT_EMPTY_RUN"


class TracePhase(StrEnum):
    """Stable phases emitted while the deterministic heuristic runs."""

    VALIDATING_INPUT = "VALIDATING_INPUT"
    CLASSIFYING_ZONES = "CLASSIFYING_ZONES"
    BUILDING_TRAVEL_MATRIX = "BUILDING_TRAVEL_MATRIX"
    GROUPING_DELIVERIES = "GROUPING_DELIVERIES"
    GENERATING_DELIVERY_PAIRS = "GENERATING_DELIVERY_PAIRS"
    MATCHING_PICKUPS = "MATCHING_PICKUPS"
    BUILDING_CYCLES = "BUILDING_CYCLES"
    ASSIGNING_DRIVERS = "ASSIGNING_DRIVERS"
    LOCAL_SEARCH = "LOCAL_SEARCH"
    FINALIZING = "FINALIZING"
    COMPLETED = "COMPLETED"


class TraceEventType(StrEnum):
    """Stable event names consumable by SSE and visual search playback."""

    PHASE_STARTED = "phase_started"
    PHASE_PROGRESS = "phase_progress"
    CANDIDATE_EDGE_CONSIDERED = "candidate_edge_considered"
    CANDIDATE_EDGE_REJECTED = "candidate_edge_rejected"
    CANDIDATE_CYCLE_CREATED = "candidate_cycle_created"
    CANDIDATE_CYCLE_REJECTED = "candidate_cycle_rejected"
    CYCLE_ASSIGNED = "cycle_assigned"
    ASSIGNMENT_CHANGED = "assignment_changed"
    BEST_SCORE_UPDATED = "best_score_updated"
    PHASE_COMPLETED = "phase_completed"


@dataclass(frozen=True, slots=True)
class RequestDateOption:
    """One allowed service date with an optional hard or soft time window."""

    date: date
    priority: int = 0
    window_start: datetime | None = None
    window_end: datetime | None = None
    is_hard: bool = False

    def __post_init__(self) -> None:
        require_aware(self.window_start, "window_start")
        require_aware(self.window_end, "window_end")
        if (self.window_start is None) != (self.window_end is None):
            raise ValueError("window_start and window_end must both be provided")
        if self.window_start is not None and self.window_end is not None:
            if self.window_start.date() != self.date or self.window_end.date() != self.date:
                raise ValueError("time-window datetimes must belong to option date")
            if self.window_start >= self.window_end:
                raise ValueError("window_start must be before window_end")

    @property
    def width(self) -> timedelta:
        """Return window width, treating a missing window as a full day."""

        if self.window_start is None or self.window_end is None:
            return timedelta(days=1)
        return self.window_end - self.window_start


@dataclass(frozen=True, slots=True)
class LogisticsRequest:
    """Unsplittable operator intent that is converted into transport tasks."""

    id: str
    request_type: TaskType
    name: str
    address_label: str
    point: GeoPoint
    quantity: int
    service_minutes: int
    priority: int
    status: RequestStatus
    zone_id: str | None
    zone_version: int | None
    date_options: tuple[RequestDateOption, ...]
    created_at: datetime
    split_allowed: bool = True
    notes: str = ""
    source_key: str | None = None
    cargo_dimensions: CargoDimensions | None = None

    def __post_init__(self) -> None:
        require_aware(self.created_at, "created_at")
        if self.quantity < 1:
            raise ValueError("request quantity must be positive")
        if self.service_minutes < 0:
            raise ValueError("service_minutes cannot be negative")
        if self.zone_version is not None and self.zone_version < 1:
            raise ValueError("zone_version must be positive")


@dataclass(frozen=True, slots=True)
class PlanningTask:
    """A capacity-bounded transport part scheduled exactly zero or one times."""

    id: str
    request_id: str
    part_number: int
    quantity: int
    task_type: TaskType
    name: str
    address_label: str
    point: GeoPoint
    zone_id: str | None
    zone_version: int | None
    service_minutes: int
    priority: int
    status: RequestStatus
    created_at: datetime
    selected_option: RequestDateOption
    remaining_date_count: int
    is_last_available_date: bool
    cargo_dimensions: CargoDimensions | None = None

    def __post_init__(self) -> None:
        require_aware(self.created_at, "created_at")
        if not 1 <= self.quantity <= 2:
            raise ValueError("planning task quantity must be in [1, 2]")
        if self.part_number < 1:
            raise ValueError("part_number must be positive")
        if self.remaining_date_count < 1:
            raise ValueError("remaining_date_count must be positive")

    @property
    def is_hard(self) -> bool:
        """Whether the selected date/window is mandatory."""

        return self.selected_option.is_hard


@dataclass(frozen=True, slots=True)
class ZoneSnapshot:
    """Planner-facing immutable zone identity and route-group metadata."""

    id: str
    code: str
    route_group: str
    version: int = 1
    priority: int = 0


@dataclass(frozen=True, slots=True)
class ZoneRelation:
    """Directed compatibility and detour policy between two zones."""

    from_zone_id: str
    to_zone_id: str
    relation_type: RelationType = RelationType.ALLOWED
    delivery_pair_allowed: bool = True
    pickup_allowed: bool = True
    max_detour_minutes: float | None = None
    max_detour_ratio: float | None = None
    penalty: float = 0.0
    is_bidirectional: bool = False

    def __post_init__(self) -> None:
        if self.max_detour_minutes is not None and self.max_detour_minutes < 0:
            raise ValueError("max_detour_minutes cannot be negative")
        if self.max_detour_ratio is not None and self.max_detour_ratio < 0:
            raise ValueError("max_detour_ratio cannot be negative")


@dataclass(frozen=True, slots=True)
class Warehouse:
    """Depot used as the mandatory start and finish of every route cycle."""

    id: str
    name: str
    point: GeoPoint
    loading_minutes: int = 30
    unloading_minutes: int = 20
    turnaround_minutes: int = 15

    def __post_init__(self) -> None:
        if min(self.loading_minutes, self.unloading_minutes, self.turnaround_minutes) < 0:
            raise ValueError("warehouse operation durations cannot be negative")


@dataclass(frozen=True, slots=True)
class Vehicle:
    """Active vehicle capacity snapshot used for a single planning run."""

    id: str
    name: str
    capacity: int = 2
    active: bool = True
    routing_spec: VehicleRoutingSpec | None = None
    default_trailer: TrailerSpec | None = None
    axle_load_profiles: tuple[OperationalAxleLoadProfile, ...] = ()

    def __post_init__(self) -> None:
        if self.capacity < 1:
            raise ValueError("vehicle capacity must be positive")


@dataclass(frozen=True, slots=True)
class DriverShift:
    """Exclusive driver/vehicle availability interval on the planning date."""

    id: str
    driver_id: str
    driver_name: str
    vehicle_id: str
    start_at: datetime
    end_at: datetime
    preferred_route_group: str | None = None
    break_minutes: int = 0
    active: bool = True

    def __post_init__(self) -> None:
        require_aware(self.start_at, "start_at")
        require_aware(self.end_at, "end_at")
        if self.start_at >= self.end_at:
            raise ValueError("shift start must be before shift end")
        if self.break_minutes < 0:
            raise ValueError("break_minutes cannot be negative")


@dataclass(frozen=True, slots=True)
class PlannedLeg:
    """Timed route geometry joining two adjacent planned stops."""

    from_stop_sequence: int
    to_stop_sequence: int
    departure_at: datetime
    arrival_at: datetime
    distance_meters: int
    travel_seconds: int
    geometry: GeoJsonLineString
    routing_profile_snapshot: Mapping[str, object] | None = None
    routing_provider: str | None = None
    osm_data_version: str | None = None
    routed_at: datetime | None = None

    def __post_init__(self) -> None:
        require_aware(self.departure_at, "departure_at")
        require_aware(self.arrival_at, "arrival_at")
        require_aware(self.routed_at, "routed_at")
        if self.departure_at > self.arrival_at:
            raise ValueError("leg departure cannot be after arrival")


@dataclass(frozen=True, slots=True)
class RouteStop:
    """Timed load transition at a depot or request location."""

    sequence: int
    stop_type: StopType
    point: GeoPoint
    planned_arrival: datetime
    planned_departure: datetime
    service_seconds: int
    quantity_delta: int
    load_before: int
    load_after: int
    task_id: str | None = None
    request_id: str | None = None
    address_label: str = ""
    zone_id: str | None = None
    route_group: str | None = None
    window_start: datetime | None = None
    window_end: datetime | None = None
    window_is_hard: bool = False

    def __post_init__(self) -> None:
        require_aware(self.planned_arrival, "planned_arrival")
        require_aware(self.planned_departure, "planned_departure")
        require_aware(self.window_start, "window_start")
        require_aware(self.window_end, "window_end")
        if self.planned_arrival > self.planned_departure:
            raise ValueError("stop arrival cannot be after departure")
        if self.service_seconds < 0:
            raise ValueError("service_seconds cannot be negative")
        if self.load_after != self.load_before + self.quantity_delta:
            raise ValueError("load_after must equal load_before + quantity_delta")


@dataclass(frozen=True, slots=True)
class RouteCycle:
    """One immutable depot-to-depot trip assigned to a driver shift."""

    id: str
    driver_shift_id: str
    driver_id: str
    vehicle_id: str
    sequence: int
    planned_start: datetime
    planned_finish: datetime
    stops: tuple[RouteStop, ...]
    legs: tuple[PlannedLeg, ...]
    total_distance_meters: int
    total_travel_seconds: int
    total_service_seconds: int
    waiting_seconds: int
    empty_distance_meters: int
    detour_seconds: int
    score: float
    detour_ratio: float = 0.0
    explanation: tuple[str, ...] = ()
    warnings: tuple[ValidationWarningCode, ...] = ()
    locked: bool = False
    manually_changed: bool = False

    def __post_init__(self) -> None:
        require_aware(self.planned_start, "planned_start")
        require_aware(self.planned_finish, "planned_finish")
        if self.planned_start > self.planned_finish:
            raise ValueError("cycle start cannot be after finish")
        if self.sequence < 1:
            raise ValueError("cycle sequence must be positive")
        if self.detour_ratio < 0:
            raise ValueError("detour_ratio cannot be negative")

    @property
    def task_ids(self) -> tuple[str, ...]:
        """Return assigned task IDs in stop order."""

        return tuple(stop.task_id for stop in self.stops if stop.task_id is not None)


@dataclass(frozen=True, slots=True)
class TraceEvent:
    """Bounded deterministic planner event suitable for storage and SSE."""

    sequence: int
    phase: TracePhase
    event_type: TraceEventType
    payload: Mapping[str, object] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class UnassignedTask:
    """A task plus actionable reasons and the closest known alternative."""

    task: PlanningTask | LogisticsRequest
    reason_codes: tuple[UnassignedReasonCode, ...]
    explanation_ru: tuple[str, ...]
    nearest_possible_at: datetime | None = None
    recommendation_ru: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        require_aware(self.nearest_possible_at, "nearest_possible_at")
        if not self.reason_codes:
            raise ValueError("an unassigned task must contain at least one reason")


@dataclass(frozen=True, slots=True)
class PlanMetrics:
    """Aggregate plan KPIs calculated from the final immutable cycles."""

    total_tasks: int
    assigned_tasks: int
    unassigned_tasks: int
    assignment_percent: float
    cycle_count: int
    active_shift_count: int
    total_distance_meters: int
    empty_distance_meters: int
    empty_distance_percent: float
    total_travel_seconds: int
    total_service_seconds: int
    total_waiting_seconds: int
    total_detour_seconds: int
    paired_delivery_count: int
    paired_pickup_count: int
    average_vehicle_load: float
    shift_utilization_percent: float
    overtime_seconds: int
    minimum_buffer_seconds: int
    score: float


@dataclass(frozen=True, slots=True)
class PlanningSettings:
    """Versionable heuristic limits and cost weights for one planning run."""

    vehicle_capacity: int = 2
    max_delivery_stops: int = 2
    max_pickup_stops: int = 2
    deliveries_before_pickups: bool = True
    max_detour_minutes: float = 35.0
    max_detour_ratio: float = 1.5
    max_candidate_neighbors: int = 8
    default_load_minutes: int = 30
    default_unload_minutes: int = 20
    default_pickup_minutes: int = 30
    default_depot_turnaround_minutes: int = 15
    default_route_buffer_minutes: int = 15
    max_optimization_seconds: float = 5.0
    max_local_search_iterations: int = 50
    empty_travel_weight: float = 1.5
    detour_weight: float = 1.0
    cross_group_penalty: float = 20.0
    driver_preference_bonus: float = 15.0
    paired_delivery_bonus: float = 20.0
    paired_pickup_bonus: float = 15.0
    unassigned_hard_task_penalty: float = 1_000_000.0
    unassigned_task_penalty: float = 10_000.0
    last_available_date_penalty: float = 100_000.0
    hard_window_violation_penalty: float = 1_000_000.0
    soft_window_violation_penalty: float = 500.0
    overtime_penalty_per_minute: float = 25.0
    waiting_weight: float = 0.25
    additional_resource_activation_penalty: float = 180.0
    preferred_shift_utilization_percent: float = 80.0
    driver_workload_weight: float = 3.0
    allow_soft_overtime: bool = False
    soft_overtime_limit_minutes: int = 0
    low_buffer_warning_minutes: int = 20
    max_trace_events: int = 2_000
    trace_sample_rate: int = 1
    seed: int = 0

    def __post_init__(self) -> None:
        if self.vehicle_capacity != 2:
            raise ValueError("the MVP invariant requires vehicle_capacity=2")
        if not 1 <= self.max_delivery_stops <= 2:
            raise ValueError("max_delivery_stops must be in [1, 2]")
        if not 1 <= self.max_pickup_stops <= 2:
            raise ValueError("max_pickup_stops must be in [1, 2]")
        if not self.deliveries_before_pickups:
            raise ValueError("the MVP requires deliveries_before_pickups=true")
        non_negative = (
            self.max_detour_minutes,
            self.max_detour_ratio,
            self.default_load_minutes,
            self.default_unload_minutes,
            self.default_pickup_minutes,
            self.default_depot_turnaround_minutes,
            self.default_route_buffer_minutes,
            self.max_optimization_seconds,
            self.max_local_search_iterations,
            self.soft_overtime_limit_minutes,
        )
        if any(value < 0 for value in non_negative):
            raise ValueError("planner durations and limits cannot be negative")
        finite_limits = (
            self.max_detour_minutes,
            self.max_detour_ratio,
            self.max_optimization_seconds,
            self.preferred_shift_utilization_percent,
        )
        if any(not isfinite(value) for value in finite_limits):
            raise ValueError("planner limits must be finite")
        if self.max_candidate_neighbors < 1:
            raise ValueError("max_candidate_neighbors must be positive")
        if not 0 < self.preferred_shift_utilization_percent <= 100:
            raise ValueError("preferred_shift_utilization_percent must be in (0, 100]")
        if self.max_trace_events < 0 or self.trace_sample_rate < 1:
            raise ValueError("trace bounds must be non-negative")
        weights = (
            self.empty_travel_weight,
            self.detour_weight,
            self.cross_group_penalty,
            self.driver_preference_bonus,
            self.paired_delivery_bonus,
            self.paired_pickup_bonus,
            self.unassigned_hard_task_penalty,
            self.unassigned_task_penalty,
            self.last_available_date_penalty,
            self.waiting_weight,
            self.additional_resource_activation_penalty,
            self.driver_workload_weight,
        )
        if any(not isfinite(value) or value < 0 for value in weights):
            raise ValueError("planner weights must be finite and non-negative")


@dataclass(frozen=True, slots=True)
class PlanningInput:
    """Complete immutable snapshot needed to generate or reoptimize a plan."""

    scenario_id: str
    planning_date: date
    warehouse: Warehouse
    requests: tuple[LogisticsRequest, ...]
    zones: tuple[ZoneSnapshot, ...]
    zone_relations: tuple[ZoneRelation, ...]
    shifts: tuple[DriverShift, ...]
    vehicles: tuple[Vehicle, ...]
    locked_cycles: tuple[RouteCycle, ...] = ()


@dataclass(frozen=True, slots=True)
class PlanningResult:
    """Deterministic planner result with routes, diagnostics, trace, and KPIs."""

    cycles: tuple[RouteCycle, ...]
    unassigned: tuple[UnassignedTask, ...]
    tasks: tuple[PlanningTask, ...]
    score: float
    metrics: PlanMetrics
    trace_events: tuple[TraceEvent, ...]
    seed: int
    timed_out: bool = False
    local_search_iterations: int = 0


@dataclass(frozen=True, slots=True)
class ValidationIssue:
    """One validation error or warning tied to a route, stop, or task."""

    code: ValidationErrorCode | ValidationWarningCode
    message_ru: str
    cycle_id: str | None = None
    task_id: str | None = None


@dataclass(frozen=True, slots=True)
class ValidationResult:
    """Pure validation outcome returned after generation or manual edits."""

    errors: tuple[ValidationIssue, ...]
    warnings: tuple[ValidationIssue, ...]
    metrics: PlanMetrics | None = None

    @property
    def valid(self) -> bool:
        """Return whether confirmation-blocking errors are absent."""

        return not self.errors
