"""Pydantic request and response contracts for simulator REST endpoints."""

from __future__ import annotations

from datetime import date, time
from datetime import date as DateValue
from typing import Annotated, Any, Literal
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    field_validator,
    model_validator,
)
from shapely.geometry import MultiPolygon, Polygon, shape
from shapely.geometry.base import BaseGeometry

from app.models.domain import (
    OptimizationStatus,
    PlanStatus,
    RelationType,
    RequestStatus,
    RequestType,
    StopType,
    TaskStatus,
    VehicleLoadProfileType,
    ZoneClassificationStatus,
)

type NonBlank = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]
type Position = tuple[float, float]
type LinearRing = list[Position]
type PolygonCoordinates = list[LinearRing]
type MultiPolygonCoordinates = list[PolygonCoordinates]


class ApiModel(BaseModel):
    """Common API schema configuration with ORM attribute support."""

    model_config = ConfigDict(from_attributes=True, extra="forbid")


class ScenarioSettings(ApiModel):
    """Tunable deterministic planner and mock-routing settings."""

    model_config = ConfigDict(extra="allow")

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
    city_speed_kmh: float = Field(default=35, gt=0)
    region_speed_kmh: float = Field(default=65, gt=0)
    road_factor: float = Field(default=1.25, ge=1)
    morning_traffic_multiplier: float = Field(default=1.25, ge=1)
    evening_traffic_multiplier: float = Field(default=1.2, ge=1)
    default_service_minutes: int = Field(default=30, ge=0)
    default_buffer_minutes: int = Field(default=15, ge=0)
    max_optimization_seconds: float = Field(default=5, gt=0)
    max_local_search_iterations: int = Field(default=100, ge=0)
    empty_travel_weight: float = Field(default=1.5, ge=0)
    detour_weight: float = Field(default=1.2, ge=0)
    cross_group_penalty: float = Field(default=15, ge=0)
    driver_preference_bonus: float = Field(default=10, ge=0)
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


class ScenarioCreate(ApiModel):
    """Input for creating an empty scenario."""

    name: NonBlank
    description: str = ""
    timezone: str = "Europe/Moscow"
    default_planning_date: date | None = None
    seed: int = 1
    settings: ScenarioSettings = Field(default_factory=ScenarioSettings)

    @field_validator("timezone")
    @classmethod
    def validate_timezone(cls, value: str) -> str:
        """Require an IANA timezone so local planning dates are unambiguous."""

        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as exc:
            raise ValueError("timezone must be a valid IANA name") from exc
        return value


class ScenarioUpdate(ApiModel):
    """Partial scenario metadata and settings update."""

    name: NonBlank | None = None
    description: str | None = None
    timezone: str | None = None
    default_planning_date: date | None = None
    seed: int | None = None
    settings: ScenarioSettings | None = None

    @field_validator("timezone")
    @classmethod
    def validate_timezone(cls, value: str | None) -> str | None:
        """Validate optional IANA timezone updates."""

        if value is not None:
            try:
                ZoneInfo(value)
            except ZoneInfoNotFoundError as exc:
                raise ValueError("timezone must be a valid IANA name") from exc
        return value


class ScenarioRead(ApiModel):
    """Scenario metadata returned to clients."""

    id: UUID
    name: str
    description: str
    timezone: str
    default_planning_date: date | None
    seed: int
    settings: dict[str, Any]
    created_at: AwareDatetime
    updated_at: AwareDatetime


class WorkloadGeneratorInput(ApiModel):
    """Bounded deterministic request workload generated for one scenario horizon."""

    start_date: date
    days: int = Field(default=1, ge=1, le=31)
    deliveries_per_day: int = Field(ge=0, le=10)
    pickups_per_day: int = Field(ge=0, le=10)
    alternative_dates_count: int = Field(default=0, ge=0, le=3)
    cargo_length_mm: int = Field(default=6_000, gt=0)
    cargo_width_mm: int = Field(default=2_400, gt=0)
    cargo_height_mm: int = Field(default=2_400, gt=0)
    cargo_weight_kg: int = Field(default=2_500, gt=0)
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

    scenario_id: UUID
    seed: int
    start_date: date
    end_date: date
    created_requests: int
    created_deliveries: int
    created_pickups: int
    replaced_requests: int = 0
    daily_counts: list[WorkloadGenerationDailyCount]


class WorkloadDeletionResult(ApiModel):
    """Summary of an idempotent generated-workload deletion for one date."""

    scenario_id: UUID
    date: date
    deleted_requests: int


class CloneRequest(ApiModel):
    """Optional name override for a cloned scenario or plan."""

    name: NonBlank | None = None


class WarehouseCreate(ApiModel):
    """Input for creating a depot in a scenario."""

    name: NonBlank
    external_warehouse_id: UUID | None = None
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    loading_minutes: int = Field(default=30, ge=0)
    unloading_minutes: int = Field(default=30, ge=0)
    turnaround_minutes: int = Field(default=15, ge=0)
    working_day_start: time = time(8)
    working_day_end: time = time(20)

    @model_validator(mode="after")
    def validate_working_day(self) -> WarehouseCreate:
        """Reject an empty or backwards local working interval."""

        if self.working_day_end <= self.working_day_start:
            raise ValueError("working_day_end must be after working_day_start")
        return self


class WarehouseUpdate(ApiModel):
    """Partial update for depot location and service timings."""

    name: NonBlank | None = None
    external_warehouse_id: UUID | None = None
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)
    loading_minutes: int | None = Field(default=None, ge=0)
    unloading_minutes: int | None = Field(default=None, ge=0)
    turnaround_minutes: int | None = Field(default=None, ge=0)
    working_day_start: time | None = None
    working_day_end: time | None = None


class WarehouseRead(WarehouseCreate):
    """Persisted warehouse representation."""

    id: UUID
    scenario_id: UUID


class GeoJsonGeometry(ApiModel):
    """GeoJSON Polygon or MultiPolygon accepted by the zone editor."""

    type: Literal["Polygon", "MultiPolygon"]
    coordinates: PolygonCoordinates | MultiPolygonCoordinates

    def to_shapely(self) -> Polygon | MultiPolygon:
        """Build and validate a non-empty polygonal Shapely geometry."""

        geometry = shape(self.model_dump())
        if not isinstance(geometry, (Polygon, MultiPolygon)):
            raise ValueError("zone geometry must be Polygon or MultiPolygon")
        if geometry.is_empty:
            raise ValueError("zone geometry must not be empty")
        if not geometry.is_valid:
            raise ValueError("zone geometry is invalid or self-intersecting")
        return geometry

    @model_validator(mode="after")
    def validate_geometry(self) -> GeoJsonGeometry:
        """Fail request validation before invalid geometry reaches PostGIS."""

        self.to_shapely()
        return self

    @classmethod
    def from_shapely(cls, geometry: BaseGeometry) -> GeoJsonGeometry:
        """Convert a persisted Polygon or MultiPolygon into JSON-safe GeoJSON."""

        from shapely.geometry import mapping

        payload = mapping(geometry)
        return cls.model_validate(payload)


class ZoneCreate(ApiModel):
    """Input for a new version-one operational zone."""

    name: NonBlank
    code: NonBlank
    route_group: NonBlank
    geometry: GeoJsonGeometry
    priority: int = 0
    delivery_price: int = Field(default=0, ge=0)
    pickup_price: int = Field(default=0, ge=0)
    locked: bool = False


class ZoneUpdate(ApiModel):
    """Partial zone update; geometry changes increment version server-side."""

    name: NonBlank | None = None
    code: NonBlank | None = None
    route_group: NonBlank | None = None
    geometry: GeoJsonGeometry | None = None
    priority: int | None = None
    delivery_price: int | None = Field(default=None, ge=0)
    pickup_price: int | None = Field(default=None, ge=0)


class ZoneRead(ApiModel):
    """Versioned zone geometry and stale-request count."""

    id: UUID
    scenario_id: UUID
    name: str
    code: str
    route_group: str
    geometry: GeoJsonGeometry
    version: int
    priority: int
    delivery_price: int
    pickup_price: int
    locked: bool
    stale_request_count: int = 0
    created_at: AwareDatetime
    updated_at: AwareDatetime


class ZoneLockRequest(ApiModel):
    """Explicit desired editing-lock state."""

    locked: bool = True


class ZoneCutoutInnerZone(ApiModel):
    """Required metadata for the operational zone occupying a new cutout."""

    name: NonBlank
    code: NonBlank
    route_group: NonBlank
    priority: int
    delivery_price: int = Field(default=0, ge=0)
    pickup_price: int = Field(default=0, ge=0)
    locked: bool


class ZoneCutoutRequest(ApiModel):
    """Strictly internal geometry plus metadata for its new operational zone."""

    geometry: GeoJsonGeometry
    inner_zone: ZoneCutoutInnerZone


class ZoneCutoutRead(ApiModel):
    """Both atomic outcomes of cutting a source zone and creating its inner zone."""

    source_zone: ZoneRead
    inner_zone: ZoneRead


class ZoneRelationCreate(ApiModel):
    """Input for a directed zone transition policy."""

    from_zone_id: UUID
    to_zone_id: UUID
    relation_type: RelationType = RelationType.ADJACENT
    delivery_pair_allowed: bool = True
    pickup_allowed: bool = True
    max_detour_minutes: int = Field(default=35, ge=0)
    max_detour_ratio: float = Field(default=1.5, ge=0)
    penalty: float = Field(default=0, ge=0)
    is_bidirectional: bool = False

    @model_validator(mode="after")
    def validate_distinct_zones(self) -> ZoneRelationCreate:
        """Reject meaningless self-relations before persistence."""

        if self.from_zone_id == self.to_zone_id:
            raise ValueError("from_zone_id and to_zone_id must differ")
        return self


class ZoneRelationUpdate(ApiModel):
    """Partial update of relation behavior without changing its endpoints."""

    relation_type: RelationType | None = None
    delivery_pair_allowed: bool | None = None
    pickup_allowed: bool | None = None
    max_detour_minutes: int | None = Field(default=None, ge=0)
    max_detour_ratio: float | None = Field(default=None, ge=0)
    penalty: float | None = Field(default=None, ge=0)
    is_bidirectional: bool | None = None


class ZoneRelationRead(ZoneRelationCreate):
    """Persisted directed zone relation."""

    id: UUID
    scenario_id: UUID


class DriverCreate(ApiModel):
    """Input for a route-assignable driver entity."""

    name: NonBlank
    external_worker_id: UUID | None = None
    preferred_route_group: str | None = None
    active: bool = True
    notes: str = ""


class DriverUpdate(ApiModel):
    """Partial driver update."""

    name: NonBlank | None = None
    external_worker_id: UUID | None = None
    preferred_route_group: str | None = None
    active: bool | None = None
    notes: str | None = None


class DriverRead(DriverCreate):
    """Persisted driver representation."""

    id: UUID
    scenario_id: UUID


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


class VehicleRead(VehicleCreate):
    """Persisted vehicle representation."""

    id: UUID
    scenario_id: UUID


class TrailerCreate(ApiModel):
    """Input for a scenario-owned trailer and its optional physical limits."""

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
    scenario_id: UUID


class VehicleLoadProfileCreate(ApiModel):
    """Input for one measured operational peak axle-load value."""

    configuration_type: VehicleLoadProfileType
    max_actual_axle_load_kg: int = Field(gt=0)


class VehicleLoadProfileUpdate(ApiModel):
    """Partial update of one vehicle operational axle-load profile."""

    configuration_type: VehicleLoadProfileType | None = None
    max_actual_axle_load_kg: int | None = Field(default=None, gt=0)


class VehicleLoadProfileRead(VehicleLoadProfileCreate):
    """Persisted vehicle operational axle-load profile."""

    id: UUID
    vehicle_id: UUID


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
    """Input for an aware driver and vehicle availability interval."""

    driver_id: UUID
    vehicle_id: UUID
    date: date
    start_at: AwareDatetime
    end_at: AwareDatetime
    break_minutes: int = Field(default=0, ge=0)
    preferred_route_group: str | None = None
    active: bool = True

    @model_validator(mode="after")
    def validate_interval(self) -> ShiftCreate:
        """Ensure a positive interval matching the declared local date."""

        if self.end_at <= self.start_at:
            raise ValueError("end_at must be after start_at")
        return self


class ShiftUpdate(ApiModel):
    """Partial driver shift update."""

    driver_id: UUID | None = None
    vehicle_id: UUID | None = None
    date: DateValue | None = None
    start_at: AwareDatetime | None = None
    end_at: AwareDatetime | None = None
    break_minutes: int | None = Field(default=None, ge=0)
    preferred_route_group: str | None = None
    active: bool | None = None


class ShiftRead(ShiftCreate):
    """Persisted driver shift representation."""

    id: UUID
    scenario_id: UUID


class RequestDateOptionInput(ApiModel):
    """Acceptable request date with a hard or soft local time window."""

    date: date
    priority: int = 0
    window_start: time | None = None
    window_end: time | None = None
    is_hard: bool = False

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


class RequestScheduleInput(ApiModel):
    """Explicitly assign a request to one accepted date or clear that choice."""

    date: DateValue | None
    add_if_missing: bool = False


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
    zone_id: UUID | None
    zone_version: int | None
    service_minutes: int
    priority: int
    status: TaskStatus
    locked: bool


class LogisticsRequestRead(ApiModel):
    """Request with backend classification, date options, and split parts."""

    id: UUID
    scenario_id: UUID
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
    zone_id: UUID | None
    zone_version: int | None
    zone_classification_status: ZoneClassificationStatus
    zone_is_stale: bool = False
    split_allowed: bool
    notes: str
    created_at: AwareDatetime
    updated_at: AwareDatetime
    date_options: list[RequestDateOptionRead] = Field(default_factory=list)
    tasks: list[PlanningTaskRead] = Field(default_factory=list)


class ReclassificationResult(ApiModel):
    """Counted outcome of an explicit scenario-wide zone reclassification."""

    updated: int
    outside_zones: int
    unchanged: int


class RwmsApiModel(BaseModel):
    """Strict camel-case RWMS service contract model."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class RwmsPlanningDateOption(RwmsApiModel):
    """One date advertised by RWMS for an incoming delivery order."""

    date: date
    priority: int
    is_hard: bool = Field(alias="isHard")


class RwmsPlanningRequest(RwmsApiModel):
    """Versioned RWMS rental order exposed to the standalone planner."""

    order_id: UUID = Field(alias="orderId")
    order_version: int = Field(alias="orderVersion", ge=0)
    order_number: NonBlank = Field(alias="orderNumber")
    client_name: NonBlank = Field(alias="clientName")
    address: NonBlank
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)
    quantity: int = Field(gt=0)
    unit_ids: list[UUID] = Field(alias="unitIds")
    date_options: list[RwmsPlanningDateOption] = Field(alias="dateOptions")
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


class RwmsSyncRequest(ApiModel):
    """Date-bounded synchronization command for one RWMS warehouse."""

    warehouse_id: UUID
    date_from: date
    date_to: date

    @model_validator(mode="after")
    def validate_date_range(self) -> RwmsSyncRequest:
        """Reject backwards synchronization ranges."""

        if self.date_to < self.date_from:
            raise ValueError("date_to must be on or after date_from")
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
    """RWMS publication or claim state mapped back to one exact simulator task."""

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


class UnassignedTaskRead(ApiModel):
    """Task with actionable assignment failure detail."""

    id: UUID
    task_id: UUID
    reason_codes: list[str]
    descriptions_ru: list[str]
    nearest_option: dict[str, Any] | None
    recommendation_ru: str | None


class RoutePlanRead(ApiModel):
    """Complete saved plan projection for editor and simulation clients."""

    id: UUID
    scenario_id: UUID
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


class ExpectedVersionRequest(ApiModel):
    """Optimistic concurrency token for a mutable plan command."""

    expected_version: int = Field(ge=1)


class ConfirmPlanRequest(ExpectedVersionRequest):
    """Plan confirmation request with explicit warning acknowledgement."""

    accept_warnings: bool = False


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
    scenario_id: UUID
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
    warehouse_id: UUID | None = None
    seed: int | None = None
    settings: dict[str, Any] | None = None
    show_trace: bool = False


class HealthRead(ApiModel):
    """Runtime and database readiness response."""

    status: Literal["ok"]
    database: Literal["ready"]
    postgis: str
    routing_provider: str


class ExportWarehouse(ApiModel):
    """Warehouse record inside a reproducible scenario document."""

    id: UUID
    data: WarehouseCreate


class ExportZone(ApiModel):
    """Version-preserving zone record inside a scenario document."""

    id: UUID
    data: ZoneCreate
    version: int = Field(ge=1)


class ExportRelation(ApiModel):
    """Zone relation record inside a scenario document."""

    id: UUID
    data: ZoneRelationCreate


class ExportDriver(ApiModel):
    """Driver record inside a scenario document."""

    id: UUID
    data: DriverCreate


class ExportVehicle(ApiModel):
    """Vehicle record inside a scenario document."""

    id: UUID
    data: VehicleCreate


class ExportTrailer(ApiModel):
    """Trailer record inside a reproducible scenario document."""

    id: UUID
    data: TrailerCreate


class ExportVehicleLoadProfile(ApiModel):
    """Vehicle operational axle-load profile inside a scenario document."""

    id: UUID
    vehicle_id: UUID
    data: VehicleLoadProfileCreate


class ExportShift(ApiModel):
    """Driver shift record inside a scenario document."""

    id: UUID
    data: ShiftCreate


class ExportRequest(ApiModel):
    """Source request record with optional upstream lineage for reproducible imports."""

    id: UUID
    data: LogisticsRequestCreate
    source_system: str | None = Field(default=None, max_length=64)
    external_id: UUID | None = None
    external_version: int | None = Field(default=None, ge=0)
    external_payload: dict[str, Any] | None = None
    scheduled_date: date | None = None
    zone_id: UUID | None
    zone_version: int | None
    zone_classification_status: ZoneClassificationStatus

    @model_validator(mode="after")
    def validate_scheduled_date(self) -> ExportRequest:
        """Keep an imported assignment within the request's accepted date set."""

        if self.scheduled_date is not None and self.scheduled_date not in {
            option.date for option in self.data.date_options
        }:
            raise ValueError("scheduled_date must be present in data.date_options")
        return self


class ExportTaskReference(ApiModel):
    """Stable request-part reference independent of database UUID remapping."""

    request_id: UUID
    part_number: int = Field(ge=1)


class ExportRouteStop(ApiModel):
    """Route stop record using a stable task reference when applicable."""

    sequence: int = Field(ge=0)
    task: ExportTaskReference | None
    stop_type: StopType
    planned_arrival: AwareDatetime
    planned_departure: AwareDatetime
    service_seconds: int = Field(ge=0)
    quantity_delta: int
    load_before: int = Field(ge=0)
    load_after: int = Field(ge=0)
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    warnings: list[dict[str, Any]] = Field(default_factory=list)
    locked: bool = False


class ExportRouteSegment(ApiModel):
    """Route segment record addressing endpoint stops by sequence."""

    sequence: int = Field(ge=1)
    from_stop_sequence: int = Field(ge=0)
    to_stop_sequence: int = Field(ge=0)
    departure_at: AwareDatetime
    arrival_at: AwareDatetime
    distance_meters: float = Field(ge=0)
    travel_seconds: int = Field(ge=0)
    geometry: dict[str, Any]
    routing_profile_snapshot: dict[str, Any] | None = None
    routing_provider: str | None = None
    osm_data_version: str | None = None
    routed_at: AwareDatetime | None = None


class ExportRouteCycle(ApiModel):
    """Complete depot-to-depot cycle inside an exported plan."""

    driver_shift_id: UUID
    sequence: int = Field(ge=1)
    planned_start: AwareDatetime
    planned_finish: AwareDatetime
    total_distance_meters: float = Field(ge=0)
    total_travel_seconds: int = Field(ge=0)
    total_service_seconds: int = Field(ge=0)
    empty_distance_meters: float = Field(ge=0)
    detour_seconds: int = Field(ge=0)
    score: float
    locked: bool
    manually_changed: bool
    metrics: dict[str, Any]
    stops: list[ExportRouteStop]
    segments: list[ExportRouteSegment]
    explanations: list[dict[str, Any]]


class ExportUnassignedTask(ApiModel):
    """Unassigned task result inside an exported plan."""

    task: ExportTaskReference
    reason_codes: list[str]
    descriptions_ru: list[str]
    nearest_option: dict[str, Any] | None
    recommendation_ru: str | None


class ExportRoutePlan(ApiModel):
    """Validated saved plan representation in a scenario export."""

    id: UUID
    warehouse_id: UUID
    date: date
    name: str
    version: int = Field(ge=1)
    status: PlanStatus
    score: float
    metrics: dict[str, Any]
    validation_errors: list[dict[str, Any]]
    validation_warnings: list[dict[str, Any]]
    manually_changed: bool
    cycles: list[ExportRouteCycle]
    unassigned_tasks: list[ExportUnassignedTask]


class ScenarioExportDocument(ApiModel):
    """Versioned, fully validated interchange document for reproducible scenarios."""

    schema_version: Literal[1] = 1
    exported_at: AwareDatetime
    scenario: ScenarioCreate
    warehouses: list[ExportWarehouse]
    zones: list[ExportZone]
    zone_relations: list[ExportRelation]
    drivers: list[ExportDriver]
    vehicles: list[ExportVehicle]
    trailers: list[ExportTrailer] = Field(default_factory=list)
    vehicle_load_profiles: list[ExportVehicleLoadProfile] = Field(default_factory=list)
    shifts: list[ExportShift]
    requests: list[ExportRequest]
    plans: list[ExportRoutePlan] = Field(default_factory=list)


class ScenarioImportRequest(ApiModel):
    """Atomic import request with an optional conflict-free name override."""

    document: ScenarioExportDocument
    name: NonBlank | None = None
