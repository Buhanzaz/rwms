"""Pydantic request and response contracts for warehouse-planning REST endpoints."""

from __future__ import annotations

from datetime import date, time
from datetime import date as DateValue
from decimal import Decimal
from typing import Annotated, Any, Literal
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    field_serializer,
    field_validator,
    model_validator,
)

from app.models.domain import (
    OptimizationStatus,
    PlanStatus,
    RequestStatus,
    RequestType,
    StopType,
    TaskStatus,
    VehicleLoadProfileType,
)

type NonBlank = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]


def _default_isochrone_tariffs() -> list[IsochroneTariff]:
    """Return an independent copy of the default four hourly tariff tiers."""

    return [
        IsochroneTariff(travel_minutes=60, price_rubles=10_000),
        IsochroneTariff(travel_minutes=120, price_rubles=15_000),
        IsochroneTariff(travel_minutes=180, price_rubles=20_000),
        IsochroneTariff(travel_minutes=240, price_rubles=25_000),
    ]


class ApiModel(BaseModel):
    """Common API schema configuration with ORM attribute support."""

    model_config = ConfigDict(from_attributes=True, extra="forbid")


class PlanningSettings(ApiModel):
    """Tunable deterministic planner and mock-routing settings."""

    model_config = ConfigDict(extra="forbid")

    vehicle_capacity: Literal[2] = 2
    max_delivery_stops: int = Field(default=2, ge=1, le=2)
    max_pickup_stops: int = Field(default=2, ge=1, le=2)
    deliveries_before_pickups: Literal[True] = True
    max_detour_minutes: int = Field(default=35, ge=0)
    max_detour_ratio: float = Field(default=1.5, ge=0)
    max_candidate_neighbors: int = Field(default=8, ge=1)
    default_load_minutes: int = Field(default=30, ge=0)
    default_unload_minutes: int = Field(default=30, ge=0)
    default_pickup_minutes: int = Field(default=30, ge=0)
    default_depot_turnaround_minutes: int = Field(default=15, ge=0)
    default_route_buffer_minutes: int = Field(default=15, ge=0)
    max_customer_wait_minutes: int = Field(default=120, ge=0)
    city_speed_kmh: float = Field(default=35, gt=0)
    region_speed_kmh: float = Field(default=65, gt=0)
    road_factor: float = Field(default=1.25, ge=1)
    morning_traffic_multiplier: float = Field(default=1.25, ge=1)
    evening_traffic_multiplier: float = Field(default=1.2, ge=1)
    default_service_minutes: int = Field(default=30, ge=0)
    default_buffer_minutes: int = Field(default=15, ge=0)
    default_cargo_length_mm: int = Field(default=6_000, gt=0, le=30_000)
    default_cargo_width_mm: int = Field(default=2_400, gt=0, le=10_000)
    default_cargo_height_mm: int = Field(default=2_400, gt=0, le=10_000)
    default_cargo_weight_kg: int = Field(default=1_200, gt=0, le=100_000)
    max_optimization_seconds: float = Field(default=5, gt=0)
    max_local_search_iterations: int = Field(default=100, ge=0)
    empty_travel_weight: float = Field(default=1.5, ge=0)
    detour_weight: float = Field(default=1.2, ge=0)
    additional_resource_activation_penalty: float = Field(default=180, ge=0)
    preferred_shift_utilization_percent: float = Field(default=80, gt=0, le=100)
    driver_workload_weight: float = Field(default=3, ge=0)
    paired_delivery_bonus: float = Field(default=20, ge=0)
    paired_pickup_bonus: float = Field(default=15, ge=0)
    unassigned_hard_task_penalty: float = Field(default=100_000, ge=0)
    last_available_date_penalty: float = Field(default=10_000, ge=0)
    allow_soft_overtime: bool = False
    soft_overtime_limit_minutes: int = Field(default=0, ge=0)
    max_trace_events: int = Field(default=2_000, ge=0)
    trace_sample_rate: int = Field(default=1, ge=1)


class WorkloadGeneratorInput(ApiModel):
    """Bounded deterministic request workload generated for one warehouse horizon."""

    start_date: date
    days: int = Field(default=1, ge=1, le=31)
    deliveries_per_day: int = Field(ge=0, le=10)
    pickups_per_day: int = Field(ge=0, le=10)
    alternative_dates_count: int = Field(default=0, ge=0, le=3)
    cargo_length_mm: int = Field(default=6_000, gt=0)
    cargo_width_mm: int = Field(default=2_400, gt=0)
    cargo_height_mm: int = Field(default=2_400, gt=0)
    cargo_weight_kg: int = Field(default=1_200, gt=0)
    seed: int

    @model_validator(mode="after")
    def validate_date_choices_fit_horizon(self) -> WorkloadGeneratorInput:
        """Keep the primary and all alternative dates inside the horizon."""

        if self.alternative_dates_count > self.days - 1:
            raise ValueError("alternative_dates_count must be smaller than days")
        return self


class WorkloadGenerationDailyCount(ApiModel):
    """Created source-request counts attributed to one primary date."""

    date: date
    deliveries: int
    pickups: int


class WorkloadGenerationResult(ApiModel):
    """Auditable summary of one workload generation command."""

    warehouse_id: UUID
    seed: int
    start_date: date
    end_date: date
    created_requests: int
    created_deliveries: int
    created_pickups: int
    replaced_requests: int = 0
    deleted_plans: int = 0
    daily_counts: list[WorkloadGenerationDailyCount]
    auto_plan_run_ids: list[UUID] = Field(default_factory=list)
    auto_plan_ids: list[UUID] = Field(default_factory=list)


class WorkloadDeletionResult(ApiModel):
    """Summary of idempotently deleting dated generated workload and its plans."""

    warehouse_id: UUID
    date: date
    deleted_requests: int
    deleted_plans: int = 0


class IsochroneTariff(ApiModel):
    """One contiguous hourly road-travel price tier."""

    travel_minutes: int = Field(ge=60, le=720, multiple_of=60)
    price_rubles: int = Field(ge=0)


def _validate_isochrone_tariffs(value: list[IsochroneTariff]) -> list[IsochroneTariff]:
    """Require one-to-twelve contiguous hourly tiers starting at sixty minutes."""

    expected = list(range(60, 60 * (len(value) + 1), 60))
    actual = [tariff.travel_minutes for tariff in value]
    if actual != expected:
        raise ValueError("isochrone tariffs must be contiguous hourly tiers starting at 60")
    return value


class WarehouseCreate(ApiModel):
    """Input that binds one RWMS warehouse and its ordered road-travel tariffs."""

    external_warehouse_id: UUID
    loading_minutes: int = Field(default=30, ge=0)
    unloading_minutes: int = Field(default=30, ge=0)
    turnaround_minutes: int = Field(default=15, ge=0)
    working_day_start: time = time(8)
    working_day_end: time = time(20)
    isochrone_tariffs: list[IsochroneTariff] = Field(
        default_factory=_default_isochrone_tariffs,
        min_length=1,
        max_length=12,
    )

    _validate_tariffs = field_validator("isochrone_tariffs")(_validate_isochrone_tariffs)

    @model_validator(mode="after")
    def validate_working_day(self) -> WarehouseCreate:
        """Reject an empty or backwards local working interval."""

        if self.working_day_end <= self.working_day_start:
            raise ValueError("working_day_end must be after working_day_start")
        return self


class WarehouseUpdate(ApiModel):
    """Partial update for warehouse planning configuration and depot timings."""

    default_planning_date: date | None = None
    seed: int | None = None
    settings: PlanningSettings | None = None
    loading_minutes: int | None = Field(default=None, ge=0)
    unloading_minutes: int | None = Field(default=None, ge=0)
    turnaround_minutes: int | None = Field(default=None, ge=0)
    working_day_start: time | None = None
    working_day_end: time | None = None
    isochrone_tariffs: list[IsochroneTariff] | None = Field(
        default=None,
        min_length=1,
        max_length=12,
    )

    _validate_tariffs = field_validator("isochrone_tariffs")(_validate_isochrone_tariffs)


class WarehouseRead(ApiModel):
    """Canonical RWMS identity plus warehouse-local planning configuration."""

    id: UUID
    external_warehouse_id: UUID
    external_warehouse_version: int
    name: str
    city: str | None
    address: str | None
    timezone: str
    latitude: float
    longitude: float
    representative: bool
    routing_ready: bool
    loading_minutes: int
    unloading_minutes: int
    turnaround_minutes: int
    working_day_start: time
    working_day_end: time
    isochrone_tariffs: list[IsochroneTariff]
    default_planning_date: date | None
    seed: int
    settings: dict[str, Any]
    capacity_generation: int
    created_at: AwareDatetime
    updated_at: AwareDatetime


class AvailableWarehouseRead(ApiModel):
    """RWMS warehouse candidate with an optional existing local binding."""

    warehouse_id: UUID
    warehouse_version: int
    name: str
    city: str
    address: str | None
    latitude: float | None
    longitude: float | None
    timezone: str
    representative: bool
    routing_ready: bool
    routing_unavailable_reason: str | None = None
    local_warehouse_id: UUID | None = None




class DriverCreate(ApiModel):
    """Input for a route-assignable driver entity."""

    rwms_assignment_mode: Literal["ASSIGNED_DRIVER", "WAREHOUSE_DRIVERS"]
    external_worker_id: UUID | None = None
    active: bool = True
    passport_details: str = ""
    notes: str = ""

    @model_validator(mode="after")
    def validate_rwms_assignment(self) -> DriverCreate:
        """Require an exact worker only for the assigned-driver audience mode."""

        if self.rwms_assignment_mode == "ASSIGNED_DRIVER":
            if self.external_worker_id is None:
                raise ValueError("ASSIGNED_DRIVER requires external_worker_id")
        elif self.external_worker_id is not None:
            raise ValueError("WAREHOUSE_DRIVERS requires external_worker_id to be null")
        return self


class DriverUpdate(ApiModel):
    """Partial driver update."""

    rwms_assignment_mode: Literal["ASSIGNED_DRIVER", "WAREHOUSE_DRIVERS"] | None = None
    external_worker_id: UUID | None = None
    active: bool | None = None
    passport_details: str | None = None
    notes: str | None = None


class DriverRead(DriverCreate):
    """Persisted driver representation."""

    id: UUID
    warehouse_id: UUID
    name: str


class AvailableDriverRead(ApiModel):
    """Canonical RWMS worker eligible for one warehouse planning audience."""

    worker_id: UUID
    display_name: str


class VehicleCreate(ApiModel):
    """Input for a vehicle, routing speeds, and optional physical truck data."""

    name: NonBlank
    registration_number: NonBlank
    capacity: int = Field(default=2, ge=1, le=2)
    active: bool = True
    average_speed_city: float = Field(default=35, gt=0)
    average_speed_region: float = Field(default=65, gt=0)
    vehicle_type: str | None = None
    manufacturer: str | None = None
    model: str | None = None
    is_hgv: bool | None = None
    tare_weight_kg: int | None = Field(default=None, gt=0)
    max_gross_weight_kg: int | None = Field(default=None, gt=0)
    length_mm: int | None = Field(default=None, gt=0)
    width_mm: int | None = Field(default=None, gt=0)
    height_mm: int | None = Field(default=None, gt=0)
    axle_count: int | None = Field(default=None, gt=0)
    max_axle_load_kg: int | None = Field(default=None, gt=0)
    payload_capacity_kg: int | None = Field(default=None, gt=0)
    platform_length_mm: int | None = Field(default=None, gt=0)
    platform_width_mm: int | None = Field(default=None, gt=0)
    platform_height_from_ground_mm: int | None = Field(default=None, gt=0)
    max_platform_payload_kg: int | None = Field(default=None, gt=0)
    max_cargo_length_mm: int | None = Field(default=None, gt=0)
    max_cargo_width_mm: int | None = Field(default=None, gt=0)
    max_cargo_height_mm: int | None = Field(default=None, gt=0)
    max_cargo_weight_kg: int | None = Field(default=None, gt=0)
    can_use_trailer: bool | None = None
    default_trailer_id: UUID | None = None
    combined_length_with_trailer_mm: int | None = Field(default=None, gt=0)
    coupling_length_mm: int | None = Field(default=None, gt=0)
    height_safety_margin_mm: int = Field(default=0, ge=0)
    width_safety_margin_mm: int = Field(default=0, ge=0)
    weight_safety_margin_kg: int = Field(default=0, ge=0)
    notes: str = ""

    @model_validator(mode="after")
    def validate_trailer_configuration(self) -> VehicleCreate:
        """Reject contradictory trailer settings while allowing incomplete profiles."""

        if self.default_trailer_id is not None and self.can_use_trailer is False:
            raise ValueError("default_trailer_id requires can_use_trailer")
        return self


class VehicleUpdate(ApiModel):
    """Partial vehicle update."""

    name: NonBlank | None = None
    registration_number: NonBlank | None = None
    capacity: int | None = Field(default=None, ge=1, le=2)
    active: bool | None = None
    average_speed_city: float | None = Field(default=None, gt=0)
    average_speed_region: float | None = Field(default=None, gt=0)
    vehicle_type: str | None = None
    manufacturer: str | None = None
    model: str | None = None
    is_hgv: bool | None = None
    tare_weight_kg: int | None = Field(default=None, gt=0)
    max_gross_weight_kg: int | None = Field(default=None, gt=0)
    length_mm: int | None = Field(default=None, gt=0)
    width_mm: int | None = Field(default=None, gt=0)
    height_mm: int | None = Field(default=None, gt=0)
    axle_count: int | None = Field(default=None, gt=0)
    max_axle_load_kg: int | None = Field(default=None, gt=0)
    payload_capacity_kg: int | None = Field(default=None, gt=0)
    platform_length_mm: int | None = Field(default=None, gt=0)
    platform_width_mm: int | None = Field(default=None, gt=0)
    platform_height_from_ground_mm: int | None = Field(default=None, gt=0)
    max_platform_payload_kg: int | None = Field(default=None, gt=0)
    max_cargo_length_mm: int | None = Field(default=None, gt=0)
    max_cargo_width_mm: int | None = Field(default=None, gt=0)
    max_cargo_height_mm: int | None = Field(default=None, gt=0)
    max_cargo_weight_kg: int | None = Field(default=None, gt=0)
    can_use_trailer: bool | None = None
    default_trailer_id: UUID | None = None
    combined_length_with_trailer_mm: int | None = Field(default=None, gt=0)
    coupling_length_mm: int | None = Field(default=None, gt=0)
    height_safety_margin_mm: int | None = Field(default=None, ge=0)
    width_safety_margin_mm: int | None = Field(default=None, ge=0)
    weight_safety_margin_kg: int | None = Field(default=None, ge=0)
    notes: str | None = None


class VehicleLoadProfileCreate(ApiModel):
    """One measured operational peak axle-load value embedded in a vehicle."""

    configuration_type: VehicleLoadProfileType
    max_actual_axle_load_kg: int = Field(gt=0)


class VehicleRead(VehicleCreate):
    """Persisted vehicle with its complete operational axle-load profile set."""

    id: UUID
    warehouse_id: UUID
    load_profiles: list[VehicleLoadProfileCreate]


class TrailerCreate(ApiModel):
    """Input for a warehouse-owned trailer and its optional physical limits."""

    name: NonBlank
    registration_number: NonBlank
    active: bool = True
    tare_weight_kg: int | None = Field(default=None, gt=0)
    max_gross_weight_kg: int | None = Field(default=None, gt=0)
    length_mm: int | None = Field(default=None, gt=0)
    width_mm: int | None = Field(default=None, gt=0)
    height_mm: int | None = Field(default=None, gt=0)
    platform_length_mm: int | None = Field(default=None, gt=0)
    platform_width_mm: int | None = Field(default=None, gt=0)
    platform_height_from_ground_mm: int | None = Field(default=None, gt=0)
    max_platform_payload_kg: int | None = Field(default=None, gt=0)
    payload_capacity_kg: int | None = Field(default=None, gt=0)
    axle_count: int | None = Field(default=None, gt=0)
    max_axle_load_kg: int | None = Field(default=None, gt=0)
    max_cargo_length_mm: int | None = Field(default=None, gt=0)
    max_cargo_width_mm: int | None = Field(default=None, gt=0)
    max_cargo_height_mm: int | None = Field(default=None, gt=0)
    max_cargo_weight_kg: int | None = Field(default=None, gt=0)
    notes: str = ""


class TrailerUpdate(ApiModel):
    """Partial update of trailer identity, availability, and physical limits."""

    name: NonBlank | None = None
    registration_number: NonBlank | None = None
    active: bool | None = None
    tare_weight_kg: int | None = Field(default=None, gt=0)
    max_gross_weight_kg: int | None = Field(default=None, gt=0)
    length_mm: int | None = Field(default=None, gt=0)
    width_mm: int | None = Field(default=None, gt=0)
    height_mm: int | None = Field(default=None, gt=0)
    platform_length_mm: int | None = Field(default=None, gt=0)
    platform_width_mm: int | None = Field(default=None, gt=0)
    platform_height_from_ground_mm: int | None = Field(default=None, gt=0)
    max_platform_payload_kg: int | None = Field(default=None, gt=0)
    payload_capacity_kg: int | None = Field(default=None, gt=0)
    axle_count: int | None = Field(default=None, gt=0)
    max_axle_load_kg: int | None = Field(default=None, gt=0)
    max_cargo_length_mm: int | None = Field(default=None, gt=0)
    max_cargo_width_mm: int | None = Field(default=None, gt=0)
    max_cargo_height_mm: int | None = Field(default=None, gt=0)
    max_cargo_weight_kg: int | None = Field(default=None, gt=0)
    notes: str | None = None


class TrailerRead(TrailerCreate):
    """Persisted trailer representation."""

    id: UUID
    warehouse_id: UUID


class VehicleConfigurationCreate(ApiModel):
    """Atomic command for a new vehicle and all operational axle-load profiles."""

    vehicle: VehicleCreate
    load_profiles: list[VehicleLoadProfileCreate] = Field(default_factory=list)

    @field_validator("load_profiles")
    @classmethod
    def validate_unique_configuration_types(
        cls, value: list[VehicleLoadProfileCreate]
    ) -> list[VehicleLoadProfileCreate]:
        """Reject duplicate operational states before entering a transaction."""

        types = [profile.configuration_type for profile in value]
        if len(types) != len(set(types)):
            raise ValueError("load_profiles must contain unique configuration types")
        return value


class VehicleConfigurationUpdate(ApiModel):
    """Atomic replacement of vehicle fields and its complete axle-profile set."""

    vehicle: VehicleUpdate
    load_profiles: list[VehicleLoadProfileCreate]

    @field_validator("load_profiles")
    @classmethod
    def validate_unique_configuration_types(
        cls, value: list[VehicleLoadProfileCreate]
    ) -> list[VehicleLoadProfileCreate]:
        """Reject duplicate operational states before deleting the old set."""

        types = [profile.configuration_type for profile in value]
        if len(types) != len(set(types)):
            raise ValueError("load_profiles must contain unique configuration types")
        return value


class ShiftCreate(ApiModel):
    """Input for one repeated daily shift over an inclusive monthly date range."""

    driver_id: UUID
    vehicle_id: UUID
    date_from: date
    date_to: date
    start_time: time
    end_time: time
    break_minutes: int = Field(default=0, ge=0)
    active: bool = True

    @model_validator(mode="after")
    def validate_interval(self) -> ShiftCreate:
        """Require a bounded ascending range within one calendar month."""

        if self.date_to < self.date_from:
            raise ValueError("date_to must be on or after date_from")
        if (self.date_to - self.date_from).days > 30:
            raise ValueError("shift date range cannot exceed 31 inclusive days")
        if (self.date_from.year, self.date_from.month) != (
            self.date_to.year,
            self.date_to.month,
        ):
            raise ValueError("shift date range must stay within one calendar month")
        if self.end_time <= self.start_time:
            raise ValueError("end_time must be after start_time")
        return self


class ShiftUpdate(ApiModel):
    """Partial driver shift update."""

    driver_id: UUID | None = None
    vehicle_id: UUID | None = None
    date_from: DateValue | None = None
    date_to: DateValue | None = None
    start_time: time | None = None
    end_time: time | None = None
    break_minutes: int | None = Field(default=None, ge=0)
    active: bool | None = None


class ShiftRead(ShiftCreate):
    """Persisted driver shift representation."""

    id: UUID
    warehouse_id: UUID


class RequestDateOptionInput(ApiModel):
    """Acceptable request date with a hard or soft local time window."""

    date: date
    priority: int = 0
    window_start: time | None = None
    window_end: time | None = None
    is_hard: bool = False
    travel_zone_hours: int | None = Field(default=None, ge=1)

    @model_validator(mode="after")
    def validate_window(self) -> RequestDateOptionInput:
        """Require both window bounds together and in ascending order."""

        if (self.window_start is None) != (self.window_end is None):
            raise ValueError("window_start and window_end must both be set or both omitted")
        if (
            self.window_start is not None
            and self.window_end is not None
            and self.window_end <= self.window_start
        ):
            raise ValueError("window_end must be after window_start")
        if self.travel_zone_hours is not None and (self.window_start is None or not self.is_hard):
            raise ValueError("travel_zone_hours requires a complete hard time window")
        return self


class RequestDateOptionRead(RequestDateOptionInput):
    """Persisted request date option."""

    id: UUID
    request_id: UUID


class RequestDateOptionUpdate(ApiModel):
    """Partial update of an acceptable date and local service window."""

    date: DateValue | None = None
    priority: int | None = None
    window_start: time | None = None
    window_end: time | None = None
    is_hard: bool | None = None
    travel_zone_hours: int | None = Field(default=None, ge=1)


class RequestScheduleInput(ApiModel):
    """Explicitly assign a request to one accepted date or clear that choice."""

    date: DateValue | None
    add_if_missing: bool = False


class RequestPlanningDetailsInput(ApiModel):
    """Dispatcher-owned date, window, obligation, access, and notification details."""

    date: date
    window_start: time | None = None
    window_end: time | None = None
    is_hard: bool = False
    mandatory: bool
    trailer_access_allowed: bool
    include_driver_passport_in_notification: bool = False
    contact_name: str = Field(max_length=200)
    contact_phone: str = Field(max_length=64)

    @model_validator(mode="after")
    def validate_window(self) -> RequestPlanningDetailsInput:
        """Accept a complete positive interval or a flexible full-day option."""

        if (self.window_start is None) != (self.window_end is None):
            raise ValueError("window_start and window_end must both be set or both omitted")
        if self.is_hard and self.window_start is None:
            raise ValueError("a hard planning window requires both bounds")
        if (
            self.window_start is not None
            and self.window_end is not None
            and self.window_end <= self.window_start
        ):
            raise ValueError("window_end must be after window_start")
        return self


class RequestTaskSplitInput(ApiModel):
    """Explicit operator-selected transport-part quantities for one request."""

    part_quantities: list[int] = Field(min_length=1)

    @field_validator("part_quantities")
    @classmethod
    def validate_part_capacities(cls, value: list[int]) -> list[int]:
        """Keep every explicit subtask within the supported one-or-two-unit capacity."""

        if any(quantity < 1 or quantity > 2 for quantity in value):
            raise ValueError("each part quantity must be between 1 and 2")
        return value


class LogisticsRequestCreate(ApiModel):
    """Source request input intentionally excluding any client-supplied zone."""

    type: RequestType
    name: NonBlank
    address_label: str = ""
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    quantity: int = Field(gt=0)
    cargo_length_mm: int | None = Field(default=None, gt=0)
    cargo_width_mm: int | None = Field(default=None, gt=0)
    cargo_height_mm: int | None = Field(default=None, gt=0)
    cargo_weight_kg: int | None = Field(default=None, gt=0)
    service_minutes: int = Field(default=30, ge=0)
    priority: int = 0
    status: RequestStatus = RequestStatus.READY
    split_allowed: bool = True
    mandatory: bool = False
    trailer_access_allowed: bool | None = None
    include_driver_passport_in_notification: bool = False
    contact_name: str = Field(default="", max_length=200)
    contact_phone: str = Field(default="", max_length=64)
    notes: str = ""
    date_options: list[RequestDateOptionInput] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_cargo_dimensions(self) -> LogisticsRequestCreate:
        """Require a complete positive physical cargo tuple or no tuple at all."""

        values = (
            self.cargo_length_mm,
            self.cargo_width_mm,
            self.cargo_height_mm,
            self.cargo_weight_kg,
        )
        if any(value is not None for value in values) and any(value is None for value in values):
            raise ValueError("cargo dimensions and weight must be supplied together")
        return self

    @field_validator("date_options")
    @classmethod
    def validate_unique_dates(
        cls, value: list[RequestDateOptionInput]
    ) -> list[RequestDateOptionInput]:
        """Prevent ambiguous duplicate options for the same planning date."""

        dates = [option.date for option in value]
        if len(set(dates)) != len(dates):
            raise ValueError("date_options dates must be unique")
        return value


class LogisticsRequestUpdate(ApiModel):
    """Partial request update; coordinate changes trigger server reclassification."""

    type: RequestType | None = None
    name: NonBlank | None = None
    address_label: str | None = None
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)
    quantity: int | None = Field(default=None, gt=0)
    cargo_length_mm: int | None = Field(default=None, gt=0)
    cargo_width_mm: int | None = Field(default=None, gt=0)
    cargo_height_mm: int | None = Field(default=None, gt=0)
    cargo_weight_kg: int | None = Field(default=None, gt=0)
    service_minutes: int | None = Field(default=None, ge=0)
    priority: int | None = None
    status: RequestStatus | None = None
    split_allowed: bool | None = None
    mandatory: bool | None = None
    trailer_access_allowed: bool | None = None
    include_driver_passport_in_notification: bool | None = None
    contact_name: str | None = Field(default=None, max_length=200)
    contact_phone: str | None = Field(default=None, max_length=64)
    notes: str | None = None
    date_options: list[RequestDateOptionInput] | None = None


class PlanningTaskRead(ApiModel):
    """Persisted vehicle-sized planning part."""

    id: UUID
    request_id: UUID
    part_number: int
    quantity: int
    cargo_length_mm: int | None = None
    cargo_width_mm: int | None = None
    cargo_height_mm: int | None = None
    cargo_weight_kg: int | None = None
    type: RequestType
    latitude: float
    longitude: float
    service_minutes: int
    priority: int
    mandatory: bool
    status: TaskStatus
    locked: bool


class LogisticsRequestRead(ApiModel):
    """Warehouse request with date options and vehicle-sized split parts."""

    id: UUID
    warehouse_id: UUID
    source_system: str | None
    external_id: UUID | None
    type: RequestType
    name: str
    address_label: str
    latitude: float
    longitude: float
    quantity: int
    cargo_length_mm: int | None = None
    cargo_width_mm: int | None = None
    cargo_height_mm: int | None = None
    cargo_weight_kg: int | None = None
    service_minutes: int
    priority: int
    status: RequestStatus
    scheduled_date: date | None
    split_allowed: bool
    mandatory: bool
    trailer_access_allowed: bool | None
    include_driver_passport_in_notification: bool
    contact_name: str
    contact_phone: str
    notes: str
    created_at: AwareDatetime
    updated_at: AwareDatetime
    date_options: list[RequestDateOptionRead] = Field(default_factory=list)
    tasks: list[PlanningTaskRead] = Field(default_factory=list)


class WarehouseWorkspaceRead(ApiModel):
    """Selected warehouse resources plus connected warehouse markers in one read."""

    warehouse: WarehouseRead
    warehouses: list[WarehouseRead]
    drivers: list[DriverRead]
    vehicles: list[VehicleRead]
    trailers: list[TrailerRead]
    shifts: list[ShiftRead]
    requests: list[LogisticsRequestRead]


class RwmsApiModel(BaseModel):
    """Strict camel-case RWMS service contract model."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class RwmsPlanningDateOption(RwmsApiModel):
    """One date advertised by RWMS for an incoming delivery order."""

    date: date
    priority: int
    is_hard: bool = Field(alias="isHard")
    window_start: time | None = Field(default=None, alias="windowStart")
    window_end: time | None = Field(default=None, alias="windowEnd")
    travel_zone_hours: int | None = Field(default=None, alias="travelZoneHours", ge=1)

    @model_validator(mode="after")
    def validate_customer_window(self) -> RwmsPlanningDateOption:
        """Require complete ordered hard windows for CustomerApp planning options."""

        if (self.window_start is None) != (self.window_end is None):
            raise ValueError("windowStart and windowEnd must be provided together")
        if self.window_start is not None and self.window_end is not None:
            if self.window_start >= self.window_end:
                raise ValueError("windowStart must precede windowEnd")
            if not self.is_hard:
                raise ValueError("fixed delivery windows must be hard")
        if self.travel_zone_hours is not None and (self.window_start is None or not self.is_hard):
            raise ValueError("travelZoneHours requires a complete hard window")
        return self


class RwmsPlanningRequest(RwmsApiModel):
    """Revisioned RWMS planning snapshot with a separate rental-order command fence."""

    order_id: UUID = Field(alias="orderId")
    order_version: int = Field(alias="orderVersion", ge=0)
    source_revision: str = Field(alias="sourceRevision", pattern=r"^[0-9a-f]{64}$")
    order_number: NonBlank = Field(alias="orderNumber")
    client_name: NonBlank = Field(alias="clientName")
    address: NonBlank
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)
    quantity: int = Field(gt=0)
    unit_ids: list[UUID] = Field(alias="unitIds")
    date_options: list[RwmsPlanningDateOption] = Field(alias="dateOptions")
    trailer_access_allowed: bool | None = Field(alias="trailerAccessAllowed")
    created_at: AwareDatetime = Field(alias="createdAt")

    @model_validator(mode="after")
    def validate_coordinates_and_units(self) -> RwmsPlanningRequest:
        """Reject half-coordinate pairs and ambiguous cabin identity lists."""

        if (self.latitude is None) != (self.longitude is None):
            raise ValueError("latitude and longitude must both be set or both omitted")
        if len(self.unit_ids) != self.quantity:
            raise ValueError("unitIds count must equal quantity")
        if len(set(self.unit_ids)) != len(self.unit_ids):
            raise ValueError("unitIds must be unique")
        dates = [option.date for option in self.date_options]
        if len(set(dates)) != len(dates):
            raise ValueError("dateOptions dates must be unique")
        return self


class RwmsPlanningFeed(RwmsApiModel):
    """Warehouse-scoped delivery feed returned by RWMS logistics-service."""

    warehouse_id: UUID = Field(alias="warehouseId")
    time_zone: str = Field(alias="timeZone")
    generated_at: AwareDatetime = Field(alias="generatedAt")
    requests: list[RwmsPlanningRequest]

    @field_validator("time_zone")
    @classmethod
    def validate_time_zone(cls, value: str) -> str:
        """Require an IANA warehouse timezone from the authoritative service."""

        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as exc:
            raise ValueError("timeZone must be a valid IANA name") from exc
        return value


class RwmsWarehouseIdentity(RwmsApiModel):
    """Canonical warehouse identity exposed by the RWMS planning directory."""

    warehouse_id: UUID = Field(alias="warehouseId")
    warehouse_version: int = Field(alias="warehouseVersion", ge=0)
    name: NonBlank
    city: NonBlank
    address: NonBlank | None = None
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)
    timezone: str = Field(alias="timeZone")
    representative: bool
    routing_ready: bool = Field(alias="routingReady")

    @field_validator("timezone")
    @classmethod
    def validate_timezone(cls, value: str) -> str:
        """Require an IANA timezone from the owning warehouse service."""

        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as exc:
            raise ValueError("timeZone must be a valid IANA name") from exc
        return value

    @model_validator(mode="after")
    def validate_routing_coordinates(self) -> RwmsWarehouseIdentity:
        """Require a complete coordinate pair whenever the owner marks routing ready."""

        if (self.latitude is None) != (self.longitude is None):
            raise ValueError("latitude and longitude must both be set or both omitted")
        if self.routing_ready and self.latitude is None:
            raise ValueError("routingReady requires latitude and longitude")
        return self


type RwmsWeekday = Literal[
    "MONDAY",
    "TUESDAY",
    "WEDNESDAY",
    "THURSDAY",
    "FRIDAY",
    "SATURDAY",
    "SUNDAY",
]


class RwmsWarehouseSupportLink(RwmsApiModel):
    """Calendar-eligible directed warehouse support edge retained without local persistence."""

    support_link_id: UUID = Field(alias="supportLinkId")
    support_link_version: int = Field(alias="supportLinkVersion", ge=0)
    support_warehouse: RwmsWarehouseIdentity = Field(alias="supportWarehouse")
    served_warehouse: RwmsWarehouseIdentity = Field(alias="servedWarehouse")
    priority: int = Field(ge=1)
    allow_drivers: bool = Field(alias="allowDrivers")
    allow_vehicles: bool = Field(alias="allowVehicles")
    allow_inventory: bool = Field(alias="allowInventory")
    allow_direct_fulfillment: bool = Field(alias="allowDirectFulfillment")
    allow_interwarehouse_transfer: bool = Field(alias="allowInterwarehouseTransfer")
    allow_contractor_fallback: bool = Field(alias="allowContractorFallback")
    allowed_weekdays: list[RwmsWeekday] = Field(alias="allowedWeekdays")
    allowed_dates: list[date] = Field(alias="allowedDates")
    excluded_dates: list[date] = Field(alias="excludedDates")
    service_start: time | None = Field(alias="serviceStart")
    service_end: time | None = Field(alias="serviceEnd")

    @model_validator(mode="after")
    def validate_link(self) -> RwmsWarehouseSupportLink:
        """Reject ambiguous topology, duplicate calendar values, and incomplete intervals."""

        if self.support_warehouse.warehouse_id == self.served_warehouse.warehouse_id:
            raise ValueError("supportWarehouse and servedWarehouse must differ")
        if len(set(self.allowed_weekdays)) != len(self.allowed_weekdays):
            raise ValueError("allowedWeekdays must be unique")
        if len(set(self.allowed_dates)) != len(self.allowed_dates):
            raise ValueError("allowedDates must be unique")
        if len(set(self.excluded_dates)) != len(self.excluded_dates):
            raise ValueError("excludedDates must be unique")
        if (self.service_start is None) != (self.service_end is None):
            raise ValueError("serviceStart and serviceEnd must be supplied together")
        if (
            self.service_start is not None
            and self.service_end is not None
            and self.service_start >= self.service_end
        ):
            raise ValueError("serviceStart must precede serviceEnd")
        return self


class RwmsDriverIdentity(RwmsApiModel):
    """Canonical active worker identity eligible for one RWMS warehouse."""

    worker_id: UUID = Field(alias="workerId")
    display_name: NonBlank = Field(alias="displayName")
    employment_type: Literal["STAFF", "CONTRACTOR"] = Field(alias="employmentType")
    phone: str | None
    operational_warehouse_id: UUID = Field(alias="operationalWarehouseId")
    available_from: AwareDatetime | None = Field(alias="availableFrom")
    available_until: AwareDatetime | None = Field(alias="availableUntil")
    availability_kind: Literal["HOME", "ACTIVE_ASSIGNMENT", "INCOMING"] = Field(
        alias="availabilityKind"
    )

    @model_validator(mode="after")
    def validate_availability_interval(self) -> RwmsDriverIdentity:
        """Reject a bounded operational interval whose end does not follow its start."""

        if (
            self.available_from is not None
            and self.available_until is not None
            and self.available_until <= self.available_from
        ):
            raise ValueError("availableUntil must follow availableFrom")
        return self


class RwmsSyncRequest(ApiModel):
    """Date-bounded synchronization command for one RWMS warehouse."""

    warehouse_id: UUID
    date_from: date
    date_to: date

    @model_validator(mode="after")
    def validate_date_range(self) -> RwmsSyncRequest:
        """Require the logistics-owner's bounded 31-day inclusive range."""

        if self.date_to < self.date_from:
            raise ValueError("date_to must be on or after date_from")
        if (self.date_to - self.date_from).days > 30:
            raise ValueError("RWMS synchronization range cannot exceed 31 inclusive days")
        return self


class RwmsSyncFailure(ApiModel):
    """One source order that could not be synchronized safely."""

    order_id: UUID | None = None
    code: str
    message: str


class RwmsSyncResult(ApiModel):
    """Counted synchronization result with explicit per-order failures."""

    imported: int
    updated: int
    skipped: int
    failures: list[RwmsSyncFailure] = Field(default_factory=list)
    auto_plan_run_ids: list[UUID] = Field(default_factory=list)
    auto_plan_ids: list[UUID] = Field(default_factory=list)


class RwmsWarehouseSyncResult(RwmsSyncResult):
    """One warehouse outcome inside a server-owned planning refresh."""

    warehouse_id: UUID


class RwmsWarehouseRefreshResult(ApiModel):
    """Server-owned current-horizon refresh for one warehouse workspace."""

    date_from: date
    date_to: date
    warehouses: list[RwmsWarehouseSyncResult] = Field(default_factory=list)


class RwmsPlanningCapacityJob(RwmsApiModel):
    """One anonymous generated delivery or pickup consuming RWMS route capacity."""

    source_job_id: UUID = Field(alias="sourceJobId")
    delivery_date: date = Field(alias="deliveryDate")
    latitude: Decimal = Field(
        ge=Decimal("-90"),
        le=Decimal("90"),
        max_digits=8,
        decimal_places=6,
    )
    longitude: Decimal = Field(
        ge=Decimal("-180"),
        le=Decimal("180"),
        max_digits=9,
        decimal_places=6,
    )
    cabin_count: int = Field(alias="cabinCount", ge=1)
    window_start: time = Field(alias="windowStart")
    window_end: time = Field(alias="windowEnd")
    service_minutes: int = Field(alias="serviceMinutes", ge=1)
    task_type: Literal["DELIVERY", "PICKUP"] = Field(alias="taskType")
    trailer_access_allowed: bool = Field(alias="trailerAccessAllowed")
    priority: int = Field(ge=0)
    mandatory: bool

    @model_validator(mode="after")
    def validate_window(self) -> RwmsPlanningCapacityJob:
        """Require a positive fixed capacity interval."""

        if self.window_end <= self.window_start:
            raise ValueError("windowStart must precede windowEnd")
        return self

    @field_serializer("latitude", "longitude", when_used="json")
    def serialize_coordinate(self, value: Decimal) -> float:
        """Emit JSON numbers while retaining decimal validation and revision precision."""

        return float(value)


class RwmsPlanningCapacityShift(RwmsApiModel):
    """One anonymous active driver/vehicle interval available to customer slot planning."""

    source_shift_id: UUID = Field(alias="sourceShiftId")
    delivery_date: date = Field(alias="deliveryDate")
    shift_start: time = Field(alias="shiftStart")
    shift_end: time = Field(alias="shiftEnd")
    break_minutes: int = Field(alias="breakMinutes", ge=0, le=720)
    cabin_capacity: int = Field(alias="cabinCapacity", ge=1, le=2)

    @model_validator(mode="after")
    def validate_interval(self) -> RwmsPlanningCapacityShift:
        """Require a positive warehouse-local same-day shift interval."""

        if self.shift_end <= self.shift_start:
            raise ValueError("shiftStart must precede shiftEnd")
        return self


class RwmsIsochroneTariff(RwmsApiModel):
    """One ordered hourly tariff published to the RWMS capacity owner."""

    travel_minutes: int = Field(alias="travelMinutes", ge=60, le=720, multiple_of=60)
    price_rubles: int = Field(alias="priceRubles", ge=0)


class RwmsCapacitySnapshotCommand(RwmsApiModel):
    """Complete replacement of one warehouse's active planning-capacity projection."""

    source_generation: int = Field(alias="sourceGeneration", ge=1)
    source_revision: str = Field(alias="sourceRevision", pattern=r"^[0-9a-f]{64}$")
    jobs: list[RwmsPlanningCapacityJob] = Field(max_length=1_000)
    shifts: list[RwmsPlanningCapacityShift] = Field(max_length=2_000)
    isochrone_tariffs: list[RwmsIsochroneTariff] = Field(
        alias="isochroneTariffs",
        min_length=1,
        max_length=12,
    )

    @field_validator("jobs")
    @classmethod
    def validate_unique_source_jobs(
        cls, value: list[RwmsPlanningCapacityJob]
    ) -> list[RwmsPlanningCapacityJob]:
        """Reject an ambiguous snapshot containing the same source job twice."""

        identifiers = [job.source_job_id for job in value]
        if len(set(identifiers)) != len(identifiers):
            raise ValueError("sourceJobId values must be unique")
        return value

    @field_validator("shifts")
    @classmethod
    def validate_unique_source_shifts(
        cls, value: list[RwmsPlanningCapacityShift]
    ) -> list[RwmsPlanningCapacityShift]:
        """Reject duplicate planning-shift identities in one replacement snapshot."""

        identifiers = [shift.source_shift_id for shift in value]
        if len(set(identifiers)) != len(identifiers):
            raise ValueError("sourceShiftId values must be unique")
        return value

    @field_validator("isochrone_tariffs")
    @classmethod
    def validate_tariffs(
        cls, value: list[RwmsIsochroneTariff]
    ) -> list[RwmsIsochroneTariff]:
        """Require the same contiguous hourly tier sequence as the public warehouse API."""

        expected = list(range(60, 60 * (len(value) + 1), 60))
        if [tariff.travel_minutes for tariff in value] != expected:
            raise ValueError("isochroneTariffs must be contiguous hourly tiers starting at 60")
        return value


class RwmsCapacitySnapshotResult(RwmsApiModel):
    """Versioned capacity revision accepted or idempotently replayed by RWMS."""

    warehouse_id: UUID = Field(alias="warehouseId")
    source_generation: int = Field(alias="sourceGeneration", ge=1)
    version: int = Field(ge=0)
    source_revision: str = Field(alias="sourceRevision", pattern=r"^[0-9a-f]{64}$")
    job_count: int = Field(alias="jobCount", ge=0)
    shift_count: int = Field(alias="shiftCount", ge=0)
    isochrone_tariff_count: int = Field(alias="isochroneTariffCount", ge=1, le=12)
    replayed: bool
    updated_at: AwareDatetime = Field(alias="updatedAt")


class RwmsPlanningAssignment(RwmsApiModel):
    """One vehicle-sized delivery assignment submitted back to RWMS."""

    order_id: UUID = Field(alias="orderId")
    expected_order_version: int = Field(alias="expectedOrderVersion", ge=0)
    scheduled_date: date = Field(alias="scheduledDate")
    driver_audience_mode: Literal["ASSIGNED_DRIVER", "WAREHOUSE_DRIVERS"] = Field(
        default="ASSIGNED_DRIVER", alias="driverAudienceMode"
    )
    driver_worker_id: UUID | None = Field(default=None, alias="driverWorkerId")
    driver_name: NonBlank = Field(alias="driverName")
    unit_ids: list[UUID] = Field(alias="unitIds", min_length=1, max_length=2)

    @model_validator(mode="after")
    def validate_driver_audience(self) -> RwmsPlanningAssignment:
        """Keep a concrete driver and a shared warehouse pool mutually exclusive."""

        assigned = self.driver_audience_mode == "ASSIGNED_DRIVER"
        if assigned != (self.driver_worker_id is not None):
            raise ValueError(
                "ASSIGNED_DRIVER requires driverWorkerId and WAREHOUSE_DRIVERS forbids it"
            )
        return self


class RwmsAssignmentsCommand(RwmsApiModel):
    """Idempotent plan-version assignment command sent to RWMS."""

    warehouse_id: UUID = Field(alias="warehouseId")
    plan_id: UUID = Field(alias="planId")
    plan_version: int = Field(alias="planVersion", ge=1)
    assignments: list[RwmsPlanningAssignment]


class RwmsAppliedAssignment(RwmsApiModel):
    """Successfully applied or replayed RWMS assignment outcome."""

    order_id: UUID = Field(alias="orderId")
    document_id: UUID = Field(alias="documentId")
    replayed: bool


class RwmsRejectedAssignment(RwmsApiModel):
    """RWMS assignment rejection retained for operator action."""

    order_id: UUID = Field(alias="orderId")
    code: str
    message: str


class RwmsApplyResult(RwmsApiModel):
    """Complete upstream application result, including every rejection."""

    applied: list[RwmsAppliedAssignment] = Field(default_factory=list)
    rejected: list[RwmsRejectedAssignment] = Field(default_factory=list)


class RwmsPlanningAssignmentStatus(RwmsApiModel):
    """Current RWMS driver-task ownership for one exact shipment unit slice."""

    order_id: UUID = Field(alias="orderId")
    document_id: UUID = Field(alias="documentId")
    scheduled_date: date = Field(alias="scheduledDate")
    unit_ids: list[UUID] = Field(alias="unitIds", min_length=1, max_length=2)
    driver_audience_mode: Literal["ASSIGNED_DRIVER", "WAREHOUSE_DRIVERS"] = Field(
        alias="driverAudienceMode"
    )
    driver_worker_id: UUID | None = Field(alias="driverWorkerId")
    driver_name: NonBlank | None = Field(alias="driverName")
    task_state: NonBlank = Field(alias="taskState")

    @model_validator(mode="after")
    def validate_identity_and_audience(self) -> RwmsPlanningAssignmentStatus:
        """Reject ambiguous unit slices and impossible driver audience combinations."""

        if len(set(self.unit_ids)) != len(self.unit_ids):
            raise ValueError("unitIds must be unique")
        assigned = self.driver_audience_mode == "ASSIGNED_DRIVER"
        if assigned != (self.driver_worker_id is not None):
            raise ValueError(
                "ASSIGNED_DRIVER requires driverWorkerId and WAREHOUSE_DRIVERS forbids it"
            )
        if assigned and self.driver_name is None:
            raise ValueError("ASSIGNED_DRIVER requires driverName")
        return self


class RwmsPlanningAssignmentStatusFeed(RwmsApiModel):
    """Strict warehouse/date-bounded assignment snapshot returned by RWMS."""

    warehouse_id: UUID = Field(alias="warehouseId")
    date: date
    assignments: list[RwmsPlanningAssignmentStatus] = Field(max_length=500)

    @model_validator(mode="after")
    def validate_assignment_dates(self) -> RwmsPlanningAssignmentStatusFeed:
        """Keep every assignment inside the date declared by its containing snapshot."""

        if any(assignment.scheduled_date != self.date for assignment in self.assignments):
            raise ValueError("every scheduledDate must equal the feed date")
        return self


class RwmsPlanApplyRequest(ApiModel):
    """Optimistic plan version plus explicitly selected future shared delivery parts."""

    expected_version: int = Field(ge=1)
    publish_unassigned_task_ids: list[UUID] = Field(default_factory=list, max_length=500)

    @field_validator("publish_unassigned_task_ids")
    @classmethod
    def require_unique_published_tasks(cls, value: list[UUID]) -> list[UUID]:
        """Reject ambiguous repeated publication choices before any RWMS command is built."""

        if len(set(value)) != len(value):
            raise ValueError("publish_unassigned_task_ids must be unique")
        return value


class RwmsPlanTaskStatus(ApiModel):
    """RWMS publication or claim state mapped back to one exact planning task."""

    task_id: UUID
    request_id: UUID
    order_id: UUID
    document_id: UUID
    driver_audience_mode: Literal["ASSIGNED_DRIVER", "WAREHOUSE_DRIVERS"]
    driver_worker_id: UUID | None
    driver_name: NonBlank | None
    task_state: NonBlank


class RwmsPlanStatusResult(ApiModel):
    """Exact plan-version RWMS status snapshot exposed to the operator UI."""

    plan_id: UUID
    plan_version: int = Field(ge=1)
    tasks: list[RwmsPlanTaskStatus] = Field(default_factory=list)


class RouteStopRead(ApiModel):
    """Scheduled route stop returned with load transition data."""

    id: UUID
    sequence: int
    task_id: UUID | None
    stop_type: StopType
    planned_arrival: AwareDatetime
    planned_departure: AwareDatetime
    service_seconds: int
    quantity_delta: int
    load_before: int
    load_after: int
    latitude: float
    longitude: float
    warnings: list[dict[str, Any]]
    locked: bool


class RouteSegmentRead(ApiModel):
    """Timed GeoJSON line used for route rendering and interpolation."""

    id: UUID
    sequence: int
    from_stop_id: UUID
    to_stop_id: UUID
    departure_at: AwareDatetime
    arrival_at: AwareDatetime
    distance_meters: float
    travel_seconds: int
    geometry: dict[str, Any]
    routing_profile_snapshot: dict[str, Any] | None = None
    routing_provider: str | None = None
    osm_data_version: str | None = None
    routed_at: AwareDatetime | None = None


class RouteCycleRead(ApiModel):
    """One route cycle with ordered stops, segments, and explanations."""

    id: UUID
    driver_shift_id: UUID
    sequence: int
    planned_start: AwareDatetime
    planned_finish: AwareDatetime
    total_distance_meters: float
    total_travel_seconds: int
    total_service_seconds: int
    empty_distance_meters: float
    detour_seconds: int
    score: float
    locked: bool
    manually_changed: bool
    metrics: dict[str, Any]
    stops: list[RouteStopRead]
    segments: list[RouteSegmentRead]
    explanations: list[dict[str, Any]]


class NearestOptionRead(ApiModel):
    """Earliest typed scheduling alternative for one unassigned task."""

    possible_at: AwareDatetime


class UnassignedTaskRead(ApiModel):
    """Task with actionable assignment failure detail."""

    id: UUID
    task_id: UUID
    reason_codes: list[str]
    descriptions_ru: list[str]
    nearest_option: NearestOptionRead | None
    recommendation_ru: str | None


class PlanNotificationLogRead(ApiModel):
    """One simulated notification rendered after explicit plan confirmation."""

    id: UUID
    plan_id: UUID
    request_id: UUID
    recipient_name: str
    recipient_contact: str
    message: str
    includes_driver_passport: bool
    status: Literal["SIMULATED_DELIVERED"]
    created_at: AwareDatetime


class RoutePlanRead(ApiModel):
    """Complete saved plan projection for editor and simulation clients."""

    id: UUID
    warehouse_id: UUID
    date: date
    name: str
    version: int
    status: PlanStatus
    score: float
    metrics: dict[str, Any]
    validation_errors: list[dict[str, Any]]
    validation_warnings: list[dict[str, Any]]
    manually_changed: bool
    created_at: AwareDatetime
    updated_at: AwareDatetime
    cycles: list[RouteCycleRead]
    unassigned_tasks: list[UnassignedTaskRead]
    notification_logs: list[PlanNotificationLogRead] = Field(default_factory=list)


class PlanningDayStatusRead(ApiModel):
    """Operator-visible acceptance and finalization state for one depot date."""

    warehouse_id: UUID
    date: date
    accepting_requests: bool
    closed_at: AwareDatetime | None = None
    closed_by: str | None = None
    plan_id: UUID | None = None


class ExpectedVersionRequest(ApiModel):
    """Optimistic concurrency token for a mutable plan command."""

    expected_version: int = Field(ge=1)


class ConfirmPlanRequest(ExpectedVersionRequest):
    """Plan confirmation with warning consent and audited empty-positioning approval."""

    accept_warnings: bool = False
    empty_positioning_reason: NonBlank | None = None
    confirmed_by: NonBlank = "local-admin"


class CyclePatch(ExpectedVersionRequest):
    """Requested route-cycle change delegated to planner validation."""

    sequence: int | None = Field(default=None, ge=1)
    driver_shift_id: UUID | None = None
    locked: bool | None = None
    reason: NonBlank


class ManualChangeRequest(ExpectedVersionRequest):
    """Validated manual route-edit command and audit context."""

    change_type: NonBlank
    payload: dict[str, Any]
    reason: NonBlank
    changed_by: NonBlank = "local-admin"


class SimulationDelayRequest(ExpectedVersionRequest):
    """Temporary or persisted delay applied from one point in a driver shift."""

    driver_shift_id: UUID
    effective_at: AwareDatetime
    delay_minutes: int = Field(gt=0, le=24 * 60)
    reason: NonBlank
    persist: bool = False


class DriverUnavailableRequest(ExpectedVersionRequest):
    """Temporary or persisted driver-unavailability override for the remaining day."""

    driver_shift_id: UUID
    effective_at: AwareDatetime
    reason: NonBlank = "Водитель недоступен"
    persist: bool = False


class OptimizationRunRead(ApiModel):
    """Optimizer run status and score summary."""

    id: UUID
    warehouse_id: UUID
    plan_id: UUID | None
    status: OptimizationStatus
    started_at: AwareDatetime | None
    finished_at: AwareDatetime | None
    seed: int
    settings_snapshot: dict[str, Any]
    initial_score: float | None
    final_score: float | None
    error_message: str | None
    stopped_by_limit: bool
    cancel_requested: bool


class TraceEventRead(ApiModel):
    """One persisted optimizer progress event."""

    id: UUID
    optimization_run_id: UUID
    sequence: int
    event_type: str
    payload: dict[str, Any]
    created_at: AwareDatetime


class GeneratePlanRequest(ApiModel):
    """Asynchronous plan-generation command passed to the configured engine."""

    date: date
    seed: int | None = None
    settings: dict[str, Any] | None = None
    show_trace: bool = False


class HealthRead(ApiModel):
    """Runtime and database readiness response."""

    status: Literal["ok"]
    database: Literal["ready"]
    postgis: str
    routing_provider: str
