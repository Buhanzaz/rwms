"""Production adapter between persisted scenarios and the deterministic planner core."""

from __future__ import annotations

import asyncio
from collections import OrderedDict
from collections.abc import Iterable, Mapping
from dataclasses import asdict, dataclass, fields, replace
from datetime import date, datetime, timedelta
from itertools import pairwise
from typing import TYPE_CHECKING, Any
from uuid import UUID
from zoneinfo import ZoneInfo

from geoalchemy2.shape import from_shape
from shapely.geometry import LineString, shape
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.db import utc_now
from app.errors import ApiError, not_found
from app.geo import geometry_to_geojson
from app.models import (
    DriverShift as DbDriverShift,
)
from app.models import (
    LogisticsRequest as DbLogisticsRequest,
)
from app.models import (
    OptimizationRun,
    OptimizationTraceEvent,
    RouteExplanation,
    RoutePlan,
    RouteSegment,
    Scenario,
)
from app.models import (
    PlanningTask as DbPlanningTask,
)
from app.models import (
    RouteCycle as DbRouteCycle,
)
from app.models import (
    RouteStop as DbRouteStop,
)
from app.models import (
    UnassignedTask as DbUnassignedTask,
)
from app.models import Vehicle as DbVehicle
from app.models import (
    Warehouse as DbWarehouse,
)
from app.models.domain import OptimizationStatus, PlanStatus
from app.planner import (
    DriverShift,
    HeuristicPlanner,
    LogisticsRequest,
    PlanningInput,
    PlanningResult,
    PlanningSettings,
    PlanningTask,
    RelationType,
    RequestDateOption,
    RequestStatus,
    RouteCycle,
    RouteStop,
    StopType,
    TaskType,
    Vehicle,
    Warehouse,
    ZoneRelation,
    ZoneSnapshot,
    calculate_driver_workload_cost,
    calculate_plan_metrics,
    calculate_resource_activation_cost,
    validate_route_plan,
)
from app.planner.engine import CandidateRouteRejected, NullProgressPublisher
from app.planner.heuristic import _build_relation_index
from app.planner.models import PlannedLeg, ValidationIssue, ValidationWarningCode
from app.routing import (
    GeoPoint,
    MockRoutingProvider,
    OsrmRoutingProvider,
    RouteGeometry,
    RoutingProfileIncompleteError,
    RoutingProvider,
    RoutingProviderUnavailableError,
    RoutingSettings,
    TravelMatrix,
    ValhallaRoutingProvider,
)
from app.routing.truck_profile import (
    CargoDimensions,
    OperationalAxleLoadProfile,
    TrailerSpec,
    TruckConfigurationType,
    VehicleRoutingSpec,
)
from app.schemas.domain import CyclePatch, GeneratePlanRequest, ManualChangeRequest
from app.services import plans as plan_service
from app.services.truck_cycle_router import ExactTruckCycleRouter
from app.simulation import DelayOverride, DriverUnavailableOverride, propagate_delays

if TYPE_CHECKING:
    from app.routing.truck_profile import EffectiveTruckProfile


@dataclass(frozen=True, slots=True)
class _RuntimeSnapshot:
    """Fully loaded immutable planning boundary plus persistence ID mappings."""

    scenario: Scenario
    warehouse_entity: DbWarehouse
    input_data: PlanningInput
    settings: PlanningSettings
    routing_settings: RoutingSettings
    task_uuid_by_core_id: Mapping[str, UUID]
    core_task_by_uuid: Mapping[UUID, PlanningTask]
    request_task_uuids: Mapping[str, tuple[UUID, ...]]
    scenario_fingerprint: str


def request_is_available_on_date(
    scheduled_date: date | None,
    option_dates: Iterable[date],
    planning_date: date,
) -> bool:
    """Resolve date eligibility from an explicit assignment or accepted alternatives."""

    if scheduled_date is not None:
        return scheduled_date == planning_date
    return planning_date in option_dates


class _CachedRoutingProvider:
    """Bounded process-local cache partitioned by the effective truck profile."""

    def __init__(
        self,
        delegate: RoutingProvider,
        cache: OrderedDict[tuple[object, ...], TravelMatrix],
        route_cache: OrderedDict[tuple[object, ...], RouteGeometry],
        scenario_fingerprint: str,
        max_entries: int,
        provider_cache_key: tuple[object, ...],
    ) -> None:
        self._delegate = delegate
        self._cache = cache
        self._route_cache = route_cache
        self._scenario_fingerprint = scenario_fingerprint
        self._max_entries = max_entries
        self._provider_cache_key = provider_cache_key

    async def get_matrix(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
        *,
        profile: EffectiveTruckProfile | None = None,
    ) -> TravelMatrix:
        """Return a cached matrix for equal snapshot, points, settings, and time bucket."""

        departure_bucket = (
            departure_at.replace(minute=0, second=0, microsecond=0).isoformat()
            if departure_at is not None
            else None
        )
        point_key = tuple((point.lon, point.lat, point.is_city) for point in points)
        key: tuple[object, ...] = (
            self._scenario_fingerprint,
            point_key,
            self._provider_cache_key,
            profile.cache_key_data() if profile is not None else None,
            departure_bucket,
        )
        cached = self._cache.get(key)
        if cached is not None:
            self._cache.move_to_end(key)
            return cached
        matrix = await self._delegate.get_matrix(
            points,
            departure_at,
            profile=profile,
        )
        self._cache[key] = matrix
        self._cache.move_to_end(key)
        while len(self._cache) > self._max_entries:
            self._cache.popitem(last=False)
        return matrix

    async def get_route(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
        *,
        profile: EffectiveTruckProfile | None = None,
    ) -> RouteGeometry:
        """Cache exact geometry without sharing it across load configurations."""

        departure_bucket = departure_at.isoformat() if departure_at is not None else None
        point_key = tuple((point.lon, point.lat, point.is_city) for point in points)
        key: tuple[object, ...] = (
            self._scenario_fingerprint,
            point_key,
            self._provider_cache_key,
            profile.cache_key_data() if profile is not None else None,
            departure_bucket,
        )
        cached = self._route_cache.get(key)
        if cached is not None:
            self._route_cache.move_to_end(key)
            return cached
        route = await self._delegate.get_route(
            points,
            departure_at,
            profile=profile,
        )
        self._route_cache[key] = route
        self._route_cache.move_to_end(key)
        while len(self._route_cache) > self._max_entries * 16:
            self._route_cache.popitem(last=False)
        return route

    async def aclose(self) -> None:
        """Close pooled connections owned by the wrapped runtime provider."""

        close = getattr(self._delegate, "aclose", None)
        if close is not None:
            await close()


class RuntimePlannerFacade:
    """Execute planning and validated edits against the simulator-owned database."""

    def __init__(
        self,
        routing_provider: str = "mock",
        *,
        osrm_base_url: str = "http://osrm:5000",
        osrm_profile: str = "driving",
        osrm_timeout_seconds: float = 15.0,
        valhalla_url: str = "http://valhalla:8002",
        valhalla_timeout_seconds: float = 30.0,
        osm_data_version: str = "unknown",
        matrix_cache_entries: int = 32,
    ) -> None:
        if matrix_cache_entries < 1:
            raise ValueError("matrix_cache_entries must be positive")
        self._routing_provider = routing_provider
        self._osrm_base_url = osrm_base_url
        self._osrm_profile = osrm_profile
        self._osrm_timeout_seconds = osrm_timeout_seconds
        self._valhalla_url = valhalla_url
        self._valhalla_timeout_seconds = valhalla_timeout_seconds
        self._osm_data_version = osm_data_version
        self._matrix_cache_entries = matrix_cache_entries
        self._matrix_cache: OrderedDict[tuple[object, ...], TravelMatrix] = OrderedDict()
        self._route_cache: OrderedDict[tuple[object, ...], RouteGeometry] = OrderedDict()

    async def generate_plan(
        self,
        session: AsyncSession,
        scenario_id: UUID,
        command: GeneratePlanRequest,
    ) -> OptimizationRun:
        """Generate and persist one independent best-known plan and optimizer run."""

        snapshot = await self._load_snapshot(
            session,
            scenario_id,
            command.date,
            command.warehouse_id,
            command.settings,
            command.seed,
        )
        return await self._execute_generation(session, snapshot, locked_cycles=())

    async def validate_plan(
        self,
        session: AsyncSession,
        plan_id: UUID,
        expected_version: int,
    ) -> RoutePlan:
        """Rebuild the domain projection, validate it, and persist its current verdict."""

        plan = await plan_service.assert_plan_version(session, plan_id, expected_version)
        snapshot = await self._load_snapshot(
            session,
            plan.scenario_id,
            plan.date,
            plan.warehouse_id,
            None,
            None,
        )
        cycles = tuple(self._core_cycle(cycle, snapshot) for cycle in plan.cycles)
        validation = validate_route_plan(
            cycles,
            warehouse=snapshot.input_data.warehouse,
            shifts=snapshot.input_data.shifts,
            vehicles=snapshot.input_data.vehicles,
            settings=snapshot.settings,
            total_tasks=self._plan_task_count(plan, cycles),
            unassigned_tasks=len(plan.unassigned_tasks),
            score=plan.score,
        )
        self._store_validation(plan, validation.errors, validation.warnings, validation.metrics)
        plan.status = PlanStatus.VALIDATED if validation.valid else PlanStatus.DRAFT
        plan.version += 1
        await session.flush()
        return plan

    async def reoptimize_plan(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeRequest,
    ) -> OptimizationRun:
        """Create a new plan while preserving every locked cycle from the source version."""

        source = await plan_service.assert_plan_version(
            session,
            plan_id,
            command.expected_version,
        )
        snapshot = await self._load_snapshot(
            session,
            source.scenario_id,
            source.date,
            source.warehouse_id,
            self._payload_mapping(command.payload.get("settings")),
            self._optional_int(command.payload.get("seed")),
        )
        locked = tuple(
            self._core_cycle(cycle, snapshot) for cycle in source.cycles if cycle.locked
        )
        return await self._execute_generation(session, snapshot, locked_cycles=locked)

    async def replan_simulation(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeRequest,
    ) -> OptimizationRun:
        """Reoptimize the remaining unlocked plan using the same version-fenced boundary."""

        return await self.reoptimize_plan(session, plan_id, command)

    async def apply_cycle_change(
        self,
        session: AsyncSession,
        plan_id: UUID,
        cycle_id: UUID,
        command: CyclePatch,
    ) -> RoutePlan:
        """Apply the supported explicit cycle lock command with an immutable audit row."""

        plan = await plan_service.assert_plan_version(session, plan_id, command.expected_version)
        cycle = next((item for item in plan.cycles if item.id == cycle_id), None)
        if cycle is None:
            raise not_found("route_cycle", cycle_id)
        if command.driver_shift_id is not None or command.sequence is not None:
            raise ApiError(
                422,
                "CYCLE_RESCHEDULE_REQUIRES_MANUAL_CHANGE",
                "Changing a cycle assignment or sequence requires a validated task move",
            )
        if command.locked is None:
            raise ApiError(422, "CYCLE_CHANGE_EMPTY", "No cycle change was provided")
        previous: dict[str, object] = {"locked": cycle.locked}
        cycle.locked = command.locked
        manual = ManualChangeRequest(
            expected_version=command.expected_version,
            change_type="LOCK_CYCLE",
            payload={"cycle_id": str(cycle_id), "locked": command.locked},
            reason=command.reason,
            changed_by="local-admin",
        )
        await plan_service.record_manual_change(
            session,
            plan,
            manual,
            previous_value=previous,
            new_value={"locked": cycle.locked},
        )
        plan.status = PlanStatus.DRAFT
        await session.flush()
        return plan

    async def apply_manual_change(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeRequest,
    ) -> RoutePlan:
        """Apply a supported task move/reorder/lock only after complete domain validation."""

        if command.change_type in {"MOVE_TASK", "REORDER_TASK"}:
            return await self._move_task(session, plan_id, command)
        if command.change_type == "LOCK_TASK":
            return await self._lock_task(session, plan_id, command)
        raise ApiError(
            422,
            "MANUAL_CHANGE_NOT_SUPPORTED",
            f"Manual change {command.change_type} is not supported by this MVP editor",
        )

    async def apply_simulation_delay(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeRequest,
    ) -> RoutePlan:
        """Validate delay/unavailability overrides and persist only an explicit save."""

        plan = await plan_service.assert_plan_version(session, plan_id, command.expected_version)
        snapshot = await self._load_snapshot(
            session,
            plan.scenario_id,
            plan.date,
            plan.warehouse_id,
            None,
            None,
        )
        shift_id = self._payload_uuid(command.payload, "driver_shift_id")
        effective_at = self._payload_datetime(command.payload, "effective_at")
        if not any(cycle.driver_shift_id == shift_id for cycle in plan.cycles):
            raise ApiError(
                422,
                "DRIVER_SHIFT_NOT_IN_PLAN",
                "The selected driver shift has no cycles in this plan",
            )
        persist = bool(command.payload.get("persist", False))
        if command.change_type == "DRIVER_UNAVAILABLE":
            override = DriverUnavailableOverride(
                id=f"unavailable:{plan.id}:{shift_id}:{effective_at.isoformat()}",
                driver_shift_id=str(shift_id),
                effective_at=effective_at,
                reason=command.reason,
            )
            del override
            if persist:
                await self._persist_unavailability(session, plan, command, shift_id, effective_at)
            return plan
        if command.change_type not in {"SIMULATION_DELAY", "DELAY"}:
            raise ApiError(
                422,
                "SIMULATION_OVERRIDE_UNKNOWN",
                f"Unknown simulation override {command.change_type}",
            )
        delay_minutes = self._payload_positive_int(command.payload, "delay_minutes")
        cycles = tuple(self._core_cycle(cycle, snapshot) for cycle in plan.cycles)
        shifted = propagate_delays(
            cycles,
            (
                DelayOverride(
                    id=f"delay:{plan.id}:{shift_id}:{effective_at.isoformat()}",
                    driver_shift_id=str(shift_id),
                    effective_at=effective_at,
                    delay=timedelta(minutes=delay_minutes),
                    reason=command.reason,
                ),
            ),
        )
        validation = validate_route_plan(
            shifted,
            warehouse=snapshot.input_data.warehouse,
            shifts=snapshot.input_data.shifts,
            vehicles=snapshot.input_data.vehicles,
            settings=snapshot.settings,
            total_tasks=self._plan_task_count(plan, shifted),
            unassigned_tasks=len(plan.unassigned_tasks),
            score=plan.score,
        )
        if persist:
            by_id = {UUID(cycle.id): cycle for cycle in shifted}
            for db_cycle in plan.cycles:
                shifted_cycle = by_id.get(db_cycle.id)
                if shifted_cycle is not None:
                    await self._replace_cycle(session, db_cycle, shifted_cycle, snapshot)
            self._store_validation(
                plan,
                validation.errors,
                validation.warnings,
                validation.metrics,
            )
            await plan_service.record_manual_change(
                session,
                plan,
                command,
                previous_value=None,
                new_value={
                    "driver_shift_id": str(shift_id),
                    "effective_at": effective_at.isoformat(),
                    "delay_minutes": delay_minutes,
                },
            )
            plan.status = PlanStatus.DRAFT
            await session.flush()
            self._expire_plan_graph(session, plan)
        return plan

    async def _load_snapshot(
        self,
        session: AsyncSession,
        scenario_id: UUID,
        planning_date: date,
        warehouse_id: UUID | None,
        command_settings: Mapping[str, Any] | None,
        command_seed: int | None,
    ) -> _RuntimeSnapshot:
        """Load one complete scenario graph and translate it into planner value objects."""

        statement = (
            select(Scenario)
            .where(Scenario.id == scenario_id)
            .options(
                selectinload(Scenario.warehouses),
                selectinload(Scenario.zones),
                selectinload(Scenario.zone_relations),
                selectinload(Scenario.vehicles),
                selectinload(Scenario.vehicles).selectinload(DbVehicle.default_trailer),
                selectinload(Scenario.vehicles).selectinload(DbVehicle.load_profiles),
                selectinload(Scenario.shifts).selectinload(DbDriverShift.driver),
                selectinload(Scenario.shifts).selectinload(DbDriverShift.vehicle),
                selectinload(Scenario.requests).selectinload(
                    DbLogisticsRequest.date_options
                ),
                selectinload(Scenario.requests).selectinload(DbLogisticsRequest.tasks),
            )
        )
        scenario = await session.scalar(statement)
        if scenario is None:
            raise not_found("scenario", scenario_id)
        warehouses = sorted(scenario.warehouses, key=lambda item: str(item.id))
        if warehouse_id is None:
            warehouse_entity = warehouses[0] if warehouses else None
        else:
            warehouse_entity = next(
                (item for item in warehouses if item.id == warehouse_id),
                None,
            )
        if warehouse_entity is None:
            raise ApiError(
                422,
                "NO_WAREHOUSE",
                "Planning requires a warehouse in the selected scenario",
            )
        zone_info = ZoneInfo(scenario.timezone)
        settings = self._planning_settings(
            scenario.settings,
            command_settings,
            command_seed if command_seed is not None else scenario.seed,
        )
        routing_settings = self._routing_settings(
            scenario.settings,
            command_settings,
            settings.seed,
        )
        zone_entities = {zone.id: zone for zone in scenario.zones}
        zones = tuple(
            ZoneSnapshot(
                id=str(zone.id),
                code=zone.code,
                route_group=zone.route_group,
                version=zone.version,
                priority=zone.priority,
            )
            for zone in sorted(scenario.zones, key=lambda item: str(item.id))
        )
        request_entities = sorted(
            scenario.requests,
            key=lambda item: (item.created_at, item.id),
        )
        requests = tuple(
            self._core_request(request, zone_entities, zone_info)
            for request in request_entities
            if request_is_available_on_date(
                request.scheduled_date,
                (option.date for option in request.date_options),
                planning_date,
            )
        )
        vehicles = tuple(
            Vehicle(
                id=str(vehicle.id),
                name=vehicle.name,
                capacity=vehicle.capacity,
                active=vehicle.active,
                routing_spec=self._vehicle_routing_spec(vehicle),
                default_trailer=self._trailer_spec(vehicle.default_trailer),
                axle_load_profiles=tuple(
                    OperationalAxleLoadProfile(
                        configuration_type=TruckConfigurationType(
                            profile.configuration_type
                        ),
                        max_actual_axle_load_kg=profile.max_actual_axle_load_kg,
                    )
                    for profile in vehicle.load_profiles
                ),
            )
            for vehicle in sorted(scenario.vehicles, key=lambda item: str(item.id))
        )
        shifts = tuple(
            DriverShift(
                id=str(shift.id),
                driver_id=str(shift.driver_id),
                driver_name=shift.driver.name,
                vehicle_id=str(shift.vehicle_id),
                start_at=shift.start_at.astimezone(zone_info),
                end_at=shift.end_at.astimezone(zone_info),
                preferred_route_group=(
                    shift.preferred_route_group or shift.driver.preferred_route_group
                ),
                break_minutes=shift.break_minutes,
                active=(shift.active and shift.driver.active and shift.vehicle.active),
            )
            for shift in sorted(scenario.shifts, key=lambda item: (item.start_at, item.id))
        )
        relations = tuple(
            ZoneRelation(
                from_zone_id=str(relation.from_zone_id),
                to_zone_id=str(relation.to_zone_id),
                relation_type=RelationType(relation.relation_type),
                delivery_pair_allowed=relation.delivery_pair_allowed,
                pickup_allowed=relation.pickup_allowed,
                max_detour_minutes=float(relation.max_detour_minutes),
                max_detour_ratio=relation.max_detour_ratio,
                penalty=relation.penalty,
                is_bidirectional=relation.is_bidirectional,
            )
            for relation in sorted(
                scenario.zone_relations,
                key=lambda item: (str(item.from_zone_id), str(item.to_zone_id)),
            )
        )
        warehouse = Warehouse(
            id=str(warehouse_entity.id),
            name=warehouse_entity.name,
            point=GeoPoint(
                lon=warehouse_entity.longitude,
                lat=warehouse_entity.latitude,
                is_city=True,
            ),
            loading_minutes=warehouse_entity.loading_minutes,
            unloading_minutes=warehouse_entity.unloading_minutes,
            turnaround_minutes=warehouse_entity.turnaround_minutes,
        )
        task_uuid_by_core_id: dict[str, UUID] = {}
        core_task_by_uuid: dict[UUID, PlanningTask] = {}
        request_task_uuids: dict[str, tuple[UUID, ...]] = {}
        core_request_by_id = {request.id: request for request in requests}
        for request_entity in scenario.requests:
            request_id = str(request_entity.id)
            core_request = core_request_by_id.get(request_id)
            if core_request is None:
                continue
            ids: list[UUID] = []
            for task_entity in sorted(request_entity.tasks, key=lambda item: item.part_number):
                core_id = f"{request_id}:part:{task_entity.part_number}"
                task_uuid_by_core_id[core_id] = task_entity.id
                core_task_by_uuid[task_entity.id] = self._core_task(
                    task_entity,
                    core_request,
                    planning_date,
                )
                ids.append(task_entity.id)
            request_task_uuids[request_id] = tuple(ids)
        fingerprint = repr(
            (
                scenario.id,
                sorted((zone.id, zone.version) for zone in scenario.zones),
                sorted(
                    (request.id, request.latitude, request.longitude)
                    for request in scenario.requests
                ),
                asdict(routing_settings),
            )
        )
        return _RuntimeSnapshot(
            scenario=scenario,
            warehouse_entity=warehouse_entity,
            input_data=PlanningInput(
                scenario_id=str(scenario.id),
                planning_date=planning_date,
                warehouse=warehouse,
                requests=requests,
                zones=zones,
                zone_relations=relations,
                shifts=shifts,
                vehicles=vehicles,
            ),
            settings=settings,
            routing_settings=routing_settings,
            task_uuid_by_core_id=task_uuid_by_core_id,
            core_task_by_uuid=core_task_by_uuid,
            request_task_uuids=request_task_uuids,
            scenario_fingerprint=fingerprint,
        )

    @staticmethod
    def _core_request(
        request: DbLogisticsRequest,
        zones: Mapping[UUID, Any],
        zone_info: ZoneInfo,
    ) -> LogisticsRequest:
        """Translate one persisted request without trusting any client zone input."""

        zone = zones.get(request.zone_id) if request.zone_id is not None else None
        point = GeoPoint(
            lon=request.longitude,
            lat=request.latitude,
            is_city=bool(zone and zone.route_group.upper() == "CITY"),
        )
        options = tuple(
            RequestDateOption(
                date=option.date,
                priority=option.priority,
                window_start=(
                    datetime.combine(option.date, option.window_start, tzinfo=zone_info)
                    if option.window_start is not None
                    else None
                ),
                window_end=(
                    datetime.combine(option.date, option.window_end, tzinfo=zone_info)
                    if option.window_end is not None
                    else None
                ),
                is_hard=option.is_hard,
            )
            for option in request.date_options
        )
        return LogisticsRequest(
            id=str(request.id),
            request_type=TaskType(request.type),
            name=request.name,
            address_label=request.address_label,
            point=point,
            quantity=request.quantity,
            service_minutes=request.service_minutes,
            priority=request.priority,
            status=RequestStatus(request.status),
            zone_id=str(request.zone_id) if request.zone_id is not None else None,
            zone_version=request.zone_version,
            date_options=options,
            created_at=request.created_at.astimezone(zone_info),
            split_allowed=request.split_allowed,
            notes=request.notes,
            source_key=RuntimePlannerFacade._request_source_key(request),
            cargo_dimensions=CargoDimensions(
                length_mm=request.cargo_length_mm,
                width_mm=request.cargo_width_mm,
                height_mm=request.cargo_height_mm,
                weight_kg=request.cargo_weight_kg,
            ),
        )

    @staticmethod
    def _request_source_key(request: DbLogisticsRequest) -> str | None:
        """Return authoritative source identity or a coordinate key for legacy generator rows."""

        if not request.source_system:
            return None
        if (
            request.source_system != "SIMULATOR_GENERATOR"
            or request.external_id is not None
        ):
            return (
                f"{request.source_system}:{request.external_id}"
                if request.external_id is not None
                else None
            )
        options = tuple(request.date_options)
        if options:
            highest_option_priority = max(option.priority for option in options)
            primary_dates = tuple(
                sorted(
                    option.date.isoformat()
                    for option in options
                    if option.priority == highest_option_priority
                )
            )
        else:
            scheduled_date = getattr(request, "scheduled_date", None)
            primary_dates = (
                (scheduled_date.isoformat(),)
                if isinstance(scheduled_date, date)
                else ()
            )
        return repr(
            (
                request.source_system,
                request.type,
                round(request.latitude, 7),
                round(request.longitude, 7),
                primary_dates,
            )
        )

    @staticmethod
    def _core_task(
        task: DbPlanningTask,
        request: LogisticsRequest,
        planning_date: date,
    ) -> PlanningTask:
        """Translate a stored request part for validation and manual editing."""

        matching = [option for option in request.date_options if option.date == planning_date]
        selected = (
            min(
                matching,
                key=lambda option: (
                    not option.is_hard,
                    -option.priority,
                    option.width,
                    option.window_start or request.created_at,
                ),
            )
            if matching
            else RequestDateOption(date=planning_date)
        )
        available_dates = sorted({option.date for option in request.date_options})
        remaining = sum(value >= planning_date for value in available_dates)
        return PlanningTask(
            id=f"{request.id}:part:{task.part_number}",
            request_id=request.id,
            part_number=task.part_number,
            quantity=task.quantity,
            task_type=TaskType(task.type),
            name=request.name,
            address_label=request.address_label,
            point=request.point,
            zone_id=str(task.zone_id) if task.zone_id is not None else None,
            zone_version=task.zone_version,
            service_minutes=task.service_minutes,
            priority=task.priority,
            status=RequestStatus(request.status),
            created_at=request.created_at,
            selected_option=selected,
            remaining_date_count=max(1, remaining),
            is_last_available_date=(
                bool(available_dates) and planning_date == max(available_dates)
            ),
            cargo_dimensions=CargoDimensions(
                length_mm=task.cargo_length_mm,
                width_mm=task.cargo_width_mm,
                height_mm=task.cargo_height_mm,
                weight_kg=task.cargo_weight_kg,
            ),
        )

    @staticmethod
    def _vehicle_routing_spec(vehicle: DbVehicle) -> VehicleRoutingSpec:
        """Translate nullable persisted equipment values without inventing defaults."""

        return VehicleRoutingSpec(
            vehicle_id=vehicle.id,
            is_hgv=vehicle.is_hgv,
            tare_weight_kg=vehicle.tare_weight_kg,
            max_gross_weight_kg=vehicle.max_gross_weight_kg,
            length_mm=vehicle.length_mm,
            width_mm=vehicle.width_mm,
            height_mm=vehicle.height_mm,
            axle_count=vehicle.axle_count,
            max_axle_load_kg=vehicle.max_axle_load_kg,
            payload_capacity_kg=vehicle.payload_capacity_kg,
            platform_length_mm=vehicle.platform_length_mm,
            platform_width_mm=vehicle.platform_width_mm,
            platform_height_from_ground_mm=vehicle.platform_height_from_ground_mm,
            max_platform_payload_kg=vehicle.max_platform_payload_kg,
            max_cargo_length_mm=vehicle.max_cargo_length_mm,
            max_cargo_width_mm=vehicle.max_cargo_width_mm,
            max_cargo_height_mm=vehicle.max_cargo_height_mm,
            max_cargo_weight_kg=vehicle.max_cargo_weight_kg,
            can_use_trailer=vehicle.can_use_trailer,
            combined_length_with_trailer_mm=vehicle.combined_length_with_trailer_mm,
            coupling_length_mm=vehicle.coupling_length_mm,
            height_safety_margin_mm=vehicle.height_safety_margin_mm,
            width_safety_margin_mm=vehicle.width_safety_margin_mm,
            weight_safety_margin_kg=vehicle.weight_safety_margin_kg,
        )

    @staticmethod
    def _trailer_spec(trailer: Any | None) -> TrailerSpec | None:
        """Translate an optional assigned trailer into the pure routing boundary."""

        if trailer is None:
            return None
        return TrailerSpec(
            trailer_id=trailer.id,
            tare_weight_kg=trailer.tare_weight_kg,
            max_gross_weight_kg=trailer.max_gross_weight_kg,
            length_mm=trailer.length_mm,
            width_mm=trailer.width_mm,
            height_mm=trailer.height_mm,
            platform_length_mm=trailer.platform_length_mm,
            platform_width_mm=trailer.platform_width_mm,
            platform_height_from_ground_mm=trailer.platform_height_from_ground_mm,
            max_platform_payload_kg=trailer.max_platform_payload_kg,
            payload_capacity_kg=trailer.payload_capacity_kg,
            axle_count=trailer.axle_count,
            max_axle_load_kg=trailer.max_axle_load_kg,
            max_cargo_length_mm=trailer.max_cargo_length_mm,
            max_cargo_width_mm=trailer.max_cargo_width_mm,
            max_cargo_height_mm=trailer.max_cargo_height_mm,
            max_cargo_weight_kg=trailer.max_cargo_weight_kg,
        )

    @staticmethod
    def _planning_settings(
        scenario_settings: Mapping[str, Any],
        command_settings: Mapping[str, Any] | None,
        seed: int,
    ) -> PlanningSettings:
        """Select only planner-owned fields from scenario and command snapshots."""

        allowed = {field.name for field in fields(PlanningSettings)}
        values = {
            key: value
            for key, value in {**scenario_settings, **(command_settings or {})}.items()
            if key in allowed
        }
        values["seed"] = seed
        try:
            return PlanningSettings(**values)
        except (TypeError, ValueError) as exc:
            raise ApiError(422, "PLANNING_SETTINGS_INVALID", str(exc)) from exc

    @staticmethod
    def _routing_settings(
        scenario_settings: Mapping[str, Any],
        command_settings: Mapping[str, Any] | None,
        seed: int,
    ) -> RoutingSettings:
        """Build the offline routing snapshot independently from planner weights."""

        source = {**scenario_settings, **(command_settings or {})}
        allowed = {field.name for field in fields(RoutingSettings)}
        values = {key: value for key, value in source.items() if key in allowed}
        values["seed"] = seed
        try:
            return RoutingSettings(**values)
        except (TypeError, ValueError) as exc:
            raise ApiError(422, "ROUTING_SETTINGS_INVALID", str(exc)) from exc

    async def _execute_generation(
        self,
        session: AsyncSession,
        snapshot: _RuntimeSnapshot,
        *,
        locked_cycles: tuple[RouteCycle, ...],
    ) -> OptimizationRun:
        """Execute one synchronous bounded run and preserve its independently queryable status."""

        run = OptimizationRun(
            scenario_id=snapshot.scenario.id,
            status=OptimizationStatus.RUNNING,
            started_at=utc_now(),
            seed=snapshot.settings.seed,
            settings_snapshot={
                **asdict(snapshot.settings),
                "routing": asdict(snapshot.routing_settings),
            },
            stopped_by_limit=False,
            cancel_requested=False,
        )
        session.add(run)
        await session.flush()
        provider: _CachedRoutingProvider | None = None
        try:
            provider = self._provider(snapshot)
            candidate_provider = self._candidate_provider(snapshot)
            route_evaluator = (
                ExactTruckCycleRouter(
                    provider,
                    provider_name="valhalla",
                    osm_data_version=self._osm_data_version,
                    now=utc_now,
                )
                if self._routing_provider == "valhalla"
                else None
            )
            if route_evaluator is not None:
                self._assert_truck_verified_locked_cycles(locked_cycles)
            engine = HeuristicPlanner(candidate_provider, route_evaluator)
            input_data = replace(snapshot.input_data, locked_cycles=locked_cycles)
            result = await engine.generate_plan(
                input_data,
                snapshot.settings,
                NullProgressPublisher(),
            )
            if route_evaluator is None:
                result = await self._attach_road_geometries(result, provider)
            plan = await self._persist_result(session, snapshot, result)
            run.plan_id = plan.id
            run.status = (
                OptimizationStatus.TIMED_OUT
                if result.timed_out
                else OptimizationStatus.COMPLETED
            )
            run.final_score = result.score
            run.finished_at = utc_now()
            run.stopped_by_limit = result.timed_out
            for event in result.trace_events:
                session.add(
                    OptimizationTraceEvent(
                        optimization_run_id=run.id,
                        sequence=event.sequence,
                        event_type=event.event_type.value,
                        payload={"phase": event.phase.value, **dict(event.payload)},
                    )
                )
        except ApiError:
            raise
        except (ValueError, RuntimeError) as exc:
            run.status = OptimizationStatus.FAILED
            run.finished_at = utc_now()
            code = getattr(exc, "code", None)
            run.error_message = f"{code}: {exc}" if isinstance(code, str) else str(exc)
        finally:
            if provider is not None:
                await provider.aclose()
        await session.flush()
        return run

    def _provider(self, snapshot: _RuntimeSnapshot) -> _CachedRoutingProvider:
        """Resolve the configured routing adapter without an implicit mock fallback."""

        delegate: RoutingProvider
        provider_cache_key: tuple[object, ...]
        if self._routing_provider == "mock":
            delegate = MockRoutingProvider(snapshot.routing_settings)
            provider_cache_key = (
                "mock",
                *tuple(asdict(snapshot.routing_settings).items()),
            )
        elif self._routing_provider == "osrm":
            osrm_delegate = OsrmRoutingProvider(
                self._osrm_base_url,
                profile=self._osrm_profile,
                timeout_seconds=self._osrm_timeout_seconds,
            )
            delegate = osrm_delegate
            provider_cache_key = ("osrm", *osrm_delegate.cache_key)
        elif self._routing_provider == "valhalla":
            valhalla_delegate = ValhallaRoutingProvider(
                self._valhalla_url,
                timeout_seconds=self._valhalla_timeout_seconds,
                osm_data_version=self._osm_data_version,
            )
            delegate = valhalla_delegate
            provider_cache_key = valhalla_delegate.cache_key
        else:
            raise ApiError(
                503,
                "ROUTING_PROVIDER_NOT_CONFIGURED",
                f"Routing provider {self._routing_provider!r} is not installed",
            )
        return _CachedRoutingProvider(
            delegate,
            self._matrix_cache,
            self._route_cache,
            snapshot.scenario_fingerprint,
            self._matrix_cache_entries,
            provider_cache_key,
        )

    def _candidate_provider(self, snapshot: _RuntimeSnapshot) -> _CachedRoutingProvider:
        """Return a non-driving geometric prefilter for truck-safe candidate search."""

        if self._routing_provider != "valhalla":
            return self._provider(snapshot)
        delegate = MockRoutingProvider(snapshot.routing_settings)
        return _CachedRoutingProvider(
            delegate,
            self._matrix_cache,
            self._route_cache,
            snapshot.scenario_fingerprint,
            self._matrix_cache_entries,
            (
                "truck-candidate-prefilter",
                *tuple(asdict(snapshot.routing_settings).items()),
            ),
        )

    @staticmethod
    def _assert_truck_verified_locked_cycles(
        locked_cycles: tuple[RouteCycle, ...],
    ) -> None:
        """Reject legacy locked cycles that have never passed exact truck routing.

        A locked cycle must stay byte-for-byte stable during reoptimization.  Therefore
        a legacy matrix/car cycle cannot be silently rerouted or accepted: the operator
        must unlock it first so the normal exact Valhalla candidate flow can rebuild it.
        """

        missing: list[str] = []
        required_snapshot_fields = {
            "vehicleId",
            "trailerAttached",
            "cargoPlacements",
            "configurationType",
            "effectiveHeightMeters",
            "effectiveWidthMeters",
            "effectiveLengthMeters",
            "actualWeightTons",
            "maxAxleLoadTons",
            "routingProvider",
            "osmDataVersion",
            "calculatedAt",
        }
        for cycle in locked_cycles:
            if not cycle.legs:
                missing.append(f"locked_cycle[{cycle.id}].legs")
                continue
            for index, leg in enumerate(cycle.legs, start=1):
                prefix = f"locked_cycle[{cycle.id}].leg[{index}]"
                snapshot = leg.routing_profile_snapshot
                if snapshot is None:
                    missing.append(f"{prefix}.routing_profile_snapshot")
                    continue
                for field_name in sorted(required_snapshot_fields - snapshot.keys()):
                    missing.append(f"{prefix}.{field_name}")
                if leg.routing_provider != "valhalla":
                    missing.append(f"{prefix}.routing_provider")
                if snapshot.get("routingProvider") != "valhalla":
                    missing.append(f"{prefix}.routingProfile.routingProvider")
        if missing:
            raise RoutingProfileIncompleteError(missing)

    @staticmethod
    async def _attach_road_geometries(
        result: PlanningResult,
        provider: RoutingProvider,
    ) -> PlanningResult:
        """Replace straight candidate legs with persisted road geometry after route selection.

        Schedule feasibility deliberately remains based on the provider's complete matrix.
        For OSRM both calls use the same graph; this extra pass only enriches the
        saved geometry consumed by map rendering and the pure simulation function.
        """

        semaphore = asyncio.Semaphore(4)

        async def enrich_cycle(cycle: RouteCycle) -> RouteCycle:
            ordered_stops = tuple(sorted(cycle.stops, key=lambda stop: stop.sequence))
            expected_pairs = tuple(
                (first.sequence, second.sequence)
                for first, second in pairwise(ordered_stops)
            )
            if len(cycle.legs) != len(expected_pairs):
                raise RuntimeError(
                    "planner cycle leg count does not match its ordered stop count"
                )
            departure_at = cycle.legs[0].departure_at if cycle.legs else cycle.planned_start
            async with semaphore:
                road_route = await provider.get_route(
                    [stop.point for stop in ordered_stops],
                    departure_at,
                )
            if len(road_route.legs) != len(cycle.legs):
                raise RuntimeError(
                    "routing provider returned a route leg count that does not match "
                    "the planner cycle"
                )

            enriched_legs: list[PlannedLeg] = []
            for index, (leg, road_leg, expected_pair) in enumerate(
                zip(cycle.legs, road_route.legs, expected_pairs, strict=True)
            ):
                if (leg.from_stop_sequence, leg.to_stop_sequence) != expected_pair:
                    raise RuntimeError("planner cycle legs do not follow its ordered stops")
                if (road_leg.from_index, road_leg.to_index) != (index, index + 1):
                    raise RuntimeError("routing provider returned route legs out of order")
                enriched_legs.append(replace(leg, geometry=road_leg.geometry))
            return replace(cycle, legs=tuple(enriched_legs))

        enriched_cycles = await asyncio.gather(
            *(enrich_cycle(cycle) for cycle in result.cycles)
        )
        return replace(result, cycles=tuple(enriched_cycles))

    async def _persist_result(
        self,
        session: AsyncSession,
        snapshot: _RuntimeSnapshot,
        result: PlanningResult,
    ) -> RoutePlan:
        """Persist a planner result without overwriting any existing saved plan."""

        unassigned_by_task: dict[UUID, Any] = {}
        assigned_uuids = {
            snapshot.task_uuid_by_core_id[task_id]
            for cycle in result.cycles
            for task_id in cycle.task_ids
            if task_id in snapshot.task_uuid_by_core_id
        }
        for item in result.unassigned:
            task_uuids: tuple[UUID, ...]
            if isinstance(item.task, PlanningTask):
                task_uuid = snapshot.task_uuid_by_core_id.get(item.task.id)
                task_uuids = (task_uuid,) if task_uuid is not None else ()
            else:
                task_uuids = snapshot.request_task_uuids.get(item.task.id, ())
            for task_uuid in task_uuids:
                if task_uuid not in assigned_uuids:
                    unassigned_by_task[task_uuid] = item
        total_tasks = len(assigned_uuids) + len(unassigned_by_task)
        metrics = calculate_plan_metrics(
            result.cycles,
            total_tasks=total_tasks,
            unassigned_tasks=len(unassigned_by_task),
            score=result.score,
            shifts=snapshot.input_data.shifts,
        )
        validation = validate_route_plan(
            result.cycles,
            warehouse=snapshot.input_data.warehouse,
            shifts=snapshot.input_data.shifts,
            vehicles=snapshot.input_data.vehicles,
            settings=snapshot.settings,
            total_tasks=total_tasks,
            unassigned_tasks=len(unassigned_by_task),
            score=result.score,
        )
        if validation.errors:
            codes = ", ".join(sorted({issue.code.value for issue in validation.errors}))
            raise RuntimeError(f"planner persistence rejected invalid plan: {codes}")
        plan = RoutePlan(
            scenario_id=snapshot.scenario.id,
            warehouse_id=snapshot.warehouse_entity.id,
            date=snapshot.input_data.planning_date,
            name=f"Автоплан · seed {result.seed}",
            version=1,
            status=PlanStatus.GENERATED,
            score=result.score,
            metrics=asdict(metrics),
            validation_errors=[],
            validation_warnings=[self._issue_payload(issue) for issue in validation.warnings],
            manually_changed=False,
        )
        session.add(plan)
        await session.flush()
        for core_cycle in result.cycles:
            cycle = DbRouteCycle(
                route_plan_id=plan.id,
                driver_shift_id=UUID(core_cycle.driver_shift_id),
                sequence=core_cycle.sequence,
                planned_start=core_cycle.planned_start,
                planned_finish=core_cycle.planned_finish,
                total_distance_meters=core_cycle.total_distance_meters,
                total_travel_seconds=core_cycle.total_travel_seconds,
                total_service_seconds=core_cycle.total_service_seconds,
                empty_distance_meters=core_cycle.empty_distance_meters,
                detour_seconds=core_cycle.detour_seconds,
                score=core_cycle.score,
                locked=core_cycle.locked,
                manually_changed=core_cycle.manually_changed,
                metrics=self._cycle_metrics(core_cycle),
            )
            session.add(cycle)
            await session.flush()
            await self._populate_cycle(session, cycle, core_cycle, snapshot)
        for task_uuid, item in sorted(unassigned_by_task.items(), key=lambda pair: str(pair[0])):
            session.add(
                DbUnassignedTask(
                    route_plan_id=plan.id,
                    task_id=task_uuid,
                    reason_codes=[reason.value for reason in item.reason_codes],
                    descriptions_ru=list(item.explanation_ru),
                    nearest_option=(
                        {"possible_at": item.nearest_possible_at.isoformat()}
                        if item.nearest_possible_at is not None
                        else None
                    ),
                    recommendation_ru=(
                        " ".join(item.recommendation_ru)
                        if item.recommendation_ru
                        else None
                    ),
                )
            )
        await session.flush()
        return plan

    async def _populate_cycle(
        self,
        session: AsyncSession,
        cycle: DbRouteCycle,
        core_cycle: RouteCycle,
        snapshot: _RuntimeSnapshot,
    ) -> None:
        """Persist stops, route geometry, and saved explanations for one cycle."""

        stop_ids: dict[int, UUID] = {}
        warning_payloads = [
            {"code": warning.value, "message_ru": self._warning_message(warning.value)}
            for warning in core_cycle.warnings
        ]
        for core_stop in core_cycle.stops:
            stop = DbRouteStop(
                route_cycle_id=cycle.id,
                sequence=core_stop.sequence,
                task_id=(
                    snapshot.task_uuid_by_core_id.get(core_stop.task_id)
                    if core_stop.task_id is not None
                    else None
                ),
                stop_type=core_stop.stop_type.value,
                planned_arrival=core_stop.planned_arrival,
                planned_departure=core_stop.planned_departure,
                service_seconds=core_stop.service_seconds,
                quantity_delta=core_stop.quantity_delta,
                load_before=core_stop.load_before,
                load_after=core_stop.load_after,
                latitude=core_stop.point.lat,
                longitude=core_stop.point.lon,
                warnings=warning_payloads if core_stop.sequence == 0 else [],
                locked=False,
            )
            session.add(stop)
            await session.flush()
            stop_ids[core_stop.sequence] = stop.id
        for index, leg in enumerate(core_cycle.legs, start=1):
            geometry_shape = shape(leg.geometry)
            if not isinstance(geometry_shape, LineString):
                raise RuntimeError("planner returned non-LineString route geometry")
            session.add(
                RouteSegment(
                    route_cycle_id=cycle.id,
                    sequence=index,
                    from_stop_id=stop_ids[leg.from_stop_sequence],
                    to_stop_id=stop_ids[leg.to_stop_sequence],
                    departure_at=leg.departure_at,
                    arrival_at=leg.arrival_at,
                    distance_meters=leg.distance_meters,
                    travel_seconds=leg.travel_seconds,
                    geometry=from_shape(geometry_shape, srid=4326, extended=True),
                    routing_profile_snapshot=(
                        dict(leg.routing_profile_snapshot)
                        if leg.routing_profile_snapshot is not None
                        else None
                    ),
                    routing_provider=leg.routing_provider,
                    osm_data_version=leg.osm_data_version,
                    routed_at=leg.routed_at,
                )
            )
        for line in core_cycle.explanation:
            session.add(
                RouteExplanation(
                    route_cycle_id=cycle.id,
                    explanation_type="AUTO_ASSIGNMENT",
                    summary_ru=line,
                    facts=[],
                )
            )
        await session.flush()

    @staticmethod
    def _cycle_metrics(cycle: RouteCycle) -> dict[str, Any]:
        """Store planner-only cycle facts not represented by dedicated columns."""

        return {
            "waiting_seconds": cycle.waiting_seconds,
            "detour_ratio": cycle.detour_ratio,
            "driver_id": cycle.driver_id,
            "vehicle_id": cycle.vehicle_id,
        }

    def _core_cycle(
        self,
        cycle: DbRouteCycle,
        snapshot: _RuntimeSnapshot,
    ) -> RouteCycle:
        """Reconstruct the pure domain cycle used by validation and simulation."""

        shift = next(
            (
                item
                for item in snapshot.input_data.shifts
                if item.id == str(cycle.driver_shift_id)
            ),
            None,
        )
        if shift is None:
            raise ApiError(
                422,
                "PLAN_SHIFT_MISSING",
                f"Plan cycle {cycle.id} references a missing driver shift",
            )
        stop_by_id = {stop.id: stop for stop in cycle.stops}
        zone_by_id = {zone.id: zone for zone in snapshot.input_data.zones}
        stops: list[RouteStop] = []
        warning_codes: set[ValidationWarningCode] = set()
        for db_stop in sorted(cycle.stops, key=lambda item: item.sequence):
            core_task = (
                snapshot.core_task_by_uuid.get(db_stop.task_id)
                if db_stop.task_id is not None
                else None
            )
            zone = zone_by_id.get(core_task.zone_id or "") if core_task is not None else None
            for warning in db_stop.warnings:
                raw_code = warning.get("code")
                if isinstance(raw_code, str):
                    try:
                        warning_codes.add(ValidationWarningCode(raw_code))
                    except ValueError:
                        pass
            stops.append(
                RouteStop(
                    sequence=db_stop.sequence,
                    stop_type=StopType(db_stop.stop_type),
                    point=GeoPoint(lon=db_stop.longitude, lat=db_stop.latitude),
                    planned_arrival=db_stop.planned_arrival,
                    planned_departure=db_stop.planned_departure,
                    service_seconds=db_stop.service_seconds,
                    quantity_delta=db_stop.quantity_delta,
                    load_before=db_stop.load_before,
                    load_after=db_stop.load_after,
                    task_id=core_task.id if core_task is not None else None,
                    request_id=core_task.request_id if core_task is not None else None,
                    address_label=(
                        core_task.address_label
                        if core_task is not None
                        else snapshot.input_data.warehouse.name
                    ),
                    zone_id=core_task.zone_id if core_task is not None else None,
                    route_group=zone.route_group if zone is not None else None,
                    window_start=(
                        core_task.selected_option.window_start
                        if core_task is not None
                        else None
                    ),
                    window_end=(
                        core_task.selected_option.window_end
                        if core_task is not None
                        else None
                    ),
                    window_is_hard=(
                        core_task.selected_option.is_hard
                        if core_task is not None
                        else False
                    ),
                )
            )
        legs: list[PlannedLeg] = []
        for segment in sorted(cycle.segments, key=lambda item: item.sequence):
            from_stop = stop_by_id.get(segment.from_stop_id)
            to_stop = stop_by_id.get(segment.to_stop_id)
            if from_stop is None or to_stop is None:
                raise ApiError(
                    422,
                    "PLAN_SEGMENT_INVALID",
                    f"Route segment {segment.id} references a missing stop",
                )
            legs.append(
                PlannedLeg(
                    from_stop_sequence=from_stop.sequence,
                    to_stop_sequence=to_stop.sequence,
                    departure_at=segment.departure_at,
                    arrival_at=segment.arrival_at,
                    distance_meters=round(segment.distance_meters),
                    travel_seconds=segment.travel_seconds,
                    geometry=geometry_to_geojson(segment.geometry),
                    routing_profile_snapshot=segment.routing_profile_snapshot,
                    routing_provider=segment.routing_provider,
                    osm_data_version=segment.osm_data_version,
                    routed_at=segment.routed_at,
                )
            )
        return RouteCycle(
            id=str(cycle.id),
            driver_shift_id=str(cycle.driver_shift_id),
            driver_id=shift.driver_id,
            vehicle_id=shift.vehicle_id,
            sequence=cycle.sequence,
            planned_start=cycle.planned_start,
            planned_finish=cycle.planned_finish,
            stops=tuple(stops),
            legs=tuple(legs),
            total_distance_meters=round(cycle.total_distance_meters),
            total_travel_seconds=cycle.total_travel_seconds,
            total_service_seconds=cycle.total_service_seconds,
            waiting_seconds=self._metric_int(cycle.metrics, "waiting_seconds"),
            empty_distance_meters=round(cycle.empty_distance_meters),
            detour_seconds=cycle.detour_seconds,
            score=cycle.score,
            detour_ratio=self._metric_float(cycle.metrics, "detour_ratio"),
            explanation=tuple(item.summary_ru for item in cycle.explanations),
            warnings=tuple(sorted(warning_codes, key=lambda item: item.value)),
            locked=cycle.locked,
            manually_changed=cycle.manually_changed,
        )

    async def _move_task(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeRequest,
    ) -> RoutePlan:
        """Move or reorder one task, rebuild affected cycles, and reject invalid results."""

        plan = await plan_service.assert_plan_version(session, plan_id, command.expected_version)
        snapshot = await self._load_snapshot(
            session,
            plan.scenario_id,
            plan.date,
            plan.warehouse_id,
            None,
            None,
        )
        task_id = self._payload_uuid(command.payload, "task_id")
        target_cycle_id = self._payload_uuid(command.payload, "target_cycle_id")
        target_cycle = next((item for item in plan.cycles if item.id == target_cycle_id), None)
        if target_cycle is None:
            raise not_found("route_cycle", target_cycle_id)
        if target_cycle.locked:
            raise ApiError(409, "CYCLE_LOCKED", "The target route cycle is locked")
        core_task = snapshot.core_task_by_uuid.get(task_id)
        if core_task is None:
            raise not_found("planning_task", task_id)
        source_cycle = next(
            (
                cycle
                for cycle in plan.cycles
                if any(stop.task_id == task_id for stop in cycle.stops)
            ),
            None,
        )
        requested_source = self._optional_uuid(command.payload.get("source_cycle_id"))
        if requested_source is not None and (
            source_cycle is None or source_cycle.id != requested_source
        ):
            raise ApiError(
                409,
                "TASK_ASSIGNMENT_CHANGED",
                "The task is no longer assigned to the route shown by the editor",
            )
        if source_cycle is not None and source_cycle.locked:
            raise ApiError(409, "CYCLE_LOCKED", "The source route cycle is locked")
        if any(
            stop.task_id == task_id and stop.locked
            for cycle in plan.cycles
            for stop in cycle.stops
        ):
            raise ApiError(409, "TASK_LOCKED", "The selected task is locked")

        affected_ids = {target_cycle.id}
        if source_cycle is not None:
            affected_ids.add(source_cycle.id)
        original_orders = {
            str(cycle.id): [str(stop.task_id) for stop in cycle.stops if stop.task_id]
            for cycle in plan.cycles
            if cycle.id in affected_ids
        }
        target_tasks = self._cycle_tasks(target_cycle, snapshot)
        if source_cycle is target_cycle:
            target_tasks = [task for task in target_tasks if task.id != core_task.id]
        elif any(task.id == core_task.id for task in target_tasks):
            raise ApiError(
                409,
                "DUPLICATE_ASSIGNMENT_CONFLICT",
                "The task is already assigned to the target cycle",
            )
        raw_sequence = command.payload.get("target_sequence", len(target_tasks) + 1)
        if not isinstance(raw_sequence, int):
            raise ApiError(422, "TARGET_SEQUENCE_INVALID", "target_sequence must be an integer")
        insertion_index = max(0, min(len(target_tasks), raw_sequence - 1))
        target_tasks.insert(insertion_index, core_task)
        source_tasks = (
            [task for task in self._cycle_tasks(source_cycle, snapshot) if task.id != core_task.id]
            if source_cycle is not None and source_cycle is not target_cycle
            else None
        )

        target_core = await self._reschedule_cycle(snapshot, target_cycle, target_tasks)
        source_core = (
            await self._reschedule_cycle(snapshot, source_cycle, source_tasks)
            if source_cycle is not None and source_tasks
            else None
        )
        replacements: dict[UUID, RouteCycle | None] = {target_cycle.id: target_core}
        if source_cycle is not None and source_cycle is not target_cycle:
            replacements[source_cycle.id] = source_core
        candidate_list: list[RouteCycle] = []
        for cycle in plan.cycles:
            if cycle.id in replacements:
                replacement = replacements[cycle.id]
                if replacement is not None:
                    candidate_list.append(replacement)
            else:
                candidate_list.append(self._core_cycle(cycle, snapshot))
        candidate_cycles = tuple(candidate_list)
        was_unassigned = any(item.task_id == task_id for item in plan.unassigned_tasks)
        remaining_unassigned = len(plan.unassigned_tasks) - int(was_unassigned)
        score = sum(cycle.score for cycle in candidate_cycles)
        score += calculate_resource_activation_cost(candidate_cycles, snapshot.settings)
        score += calculate_driver_workload_cost(
            candidate_cycles,
            snapshot.input_data.shifts,
            snapshot.settings,
        )
        validation = validate_route_plan(
            candidate_cycles,
            warehouse=snapshot.input_data.warehouse,
            shifts=snapshot.input_data.shifts,
            vehicles=snapshot.input_data.vehicles,
            settings=snapshot.settings,
            total_tasks=self._plan_task_count(plan, candidate_cycles),
            unassigned_tasks=max(0, remaining_unassigned),
            score=score,
        )
        if validation.errors:
            raise ApiError(
                422,
                "MANUAL_CHANGE_INVALID",
                "The requested task move violates route invariants",
                extra={"errors": [self._issue_payload(issue) for issue in validation.errors]},
            )

        await self._replace_cycle(session, target_cycle, target_core, snapshot)
        if source_cycle is not None and source_cycle is not target_cycle:
            if source_core is None:
                await session.delete(source_cycle)
            else:
                await self._replace_cycle(session, source_cycle, source_core, snapshot)
        for item in tuple(plan.unassigned_tasks):
            if item.task_id == task_id:
                await session.delete(item)
        plan.score = score
        self._store_validation(plan, (), validation.warnings, validation.metrics)
        plan.status = PlanStatus.DRAFT
        new_orders = {
            str(cycle_id): (
                [
                    str(snapshot.task_uuid_by_core_id[core_task_id])
                    for core_task_id in core_cycle.task_ids
                    if core_task_id in snapshot.task_uuid_by_core_id
                ]
                if core_cycle is not None
                else []
            )
            for cycle_id, core_cycle in replacements.items()
        }
        await plan_service.record_manual_change(
            session,
            plan,
            command,
            previous_value={"cycles": original_orders},
            new_value={"cycles": new_orders},
        )
        await session.flush()
        self._expire_plan_graph(session, plan)
        return plan

    async def _reschedule_cycle(
        self,
        snapshot: _RuntimeSnapshot,
        cycle: DbRouteCycle,
        tasks: list[PlanningTask],
    ) -> RouteCycle:
        """Rebuild one operator-edited cycle through the same production scheduler.

        The generated plan uses one matrix snapshot at the earliest active shift start.
        Reusing that departure instant here keeps a no-op manual edit deterministic;
        the cycle's actual start still drives time-window and shift validation.
        """

        pickup_seen = False
        for task in tasks:
            if task.task_type is TaskType.PICKUP:
                pickup_seen = True
            elif pickup_seen:
                raise ApiError(
                    422,
                    "PICKUP_BEFORE_DELIVERY",
                    "All deliveries must remain before every pickup",
                )
        deliveries = tuple(task for task in tasks if task.task_type is TaskType.DELIVERY)
        pickups = tuple(task for task in tasks if task.task_type is TaskType.PICKUP)
        if not deliveries and not pickups:
            raise ApiError(422, "EMPTY_ROUTE_CYCLE", "A route cycle cannot be empty")
        all_tasks = sorted(snapshot.core_task_by_uuid.values(), key=lambda item: item.id)
        points = [snapshot.input_data.warehouse.point, *(task.point for task in all_tasks)]
        provider = self._candidate_provider(snapshot)
        active_vehicle_ids = {
            vehicle.id for vehicle in snapshot.input_data.vehicles if vehicle.active
        }
        departure_at = min(
            (
                item.start_at
                for item in snapshot.input_data.shifts
                if item.active
                and item.start_at.date() == snapshot.input_data.planning_date
                and item.vehicle_id in active_vehicle_ids
            ),
            default=cycle.planned_start.astimezone(
                ZoneInfo(snapshot.scenario.timezone)
            ),
        )
        matrix = await provider.get_matrix(points, departure_at)
        matrix_index = {task.id: index + 1 for index, task in enumerate(all_tasks)}
        shift = next(
            (
                item
                for item in snapshot.input_data.shifts
                if item.id == str(cycle.driver_shift_id)
            ),
            None,
        )
        vehicle = next(
            (
                item
                for item in snapshot.input_data.vehicles
                if shift is not None and item.id == shift.vehicle_id
            ),
            None,
        )
        if shift is None or vehicle is None:
            raise ApiError(422, "PLAN_RESOURCE_MISSING", "Cycle driver or vehicle is missing")
        engine = HeuristicPlanner(provider)
        candidate = engine._schedule_candidate(
            shift=shift,
            start_at=cycle.planned_start,
            sequence=cycle.sequence,
            deliveries=deliveries,
            pickups=pickups,
            warehouse=snapshot.input_data.warehouse,
            vehicle=vehicle,
            matrix=matrix,
            matrix_index=matrix_index,
            zones={zone.id: zone for zone in snapshot.input_data.zones},
            relations=_build_relation_index(snapshot.input_data.zone_relations),
            settings=snapshot.settings,
            cycle_count=max(0, cycle.sequence - 1),
            existing_shift_cycles=tuple(
                self._core_cycle(item, snapshot)
                for item in cycle.route_plan.cycles
                if item.id != cycle.id
                and item.driver_shift_id == cycle.driver_shift_id
            ),
        )
        if candidate is None:
            raise ApiError(
                422,
                "MANUAL_CHANGE_INVALID",
                "The edited cycle violates capacity, zone, detour, window, or shift limits",
                extra={
                    "errors": [
                        {
                            "code": "NO_FEASIBLE_CYCLE",
                            "message_ru": (
                                "Изменённый рейс не помещается во вместимость, окно, "
                                "связи зон, лимит крюка или смену."
                            ),
                            "cycle_id": str(cycle.id),
                        }
                    ]
                },
            )
        core_cycle = replace(
            candidate.cycle,
            id=str(cycle.id),
            locked=cycle.locked,
            manually_changed=True,
        )
        if self._routing_provider == "valhalla":
            exact_provider = self._provider(snapshot)
            exact_router = ExactTruckCycleRouter(
                exact_provider,
                provider_name="valhalla",
                osm_data_version=self._osm_data_version,
                now=utc_now,
            )
            try:
                core_cycle = await exact_router.route_candidate(
                    core_cycle,
                    tasks=tuple(tasks),
                    vehicle=vehicle,
                    shift=shift,
                    settings=snapshot.settings,
                )
            except CandidateRouteRejected as exc:
                raise ApiError(
                    422,
                    exc.reason_code.value,
                    exc.detail,
                    extra={"missing_fields": list(exc.missing_fields)},
                ) from exc
            except RoutingProfileIncompleteError as exc:
                raise ApiError(
                    422,
                    exc.code,
                    str(exc),
                    extra={"missing_fields": list(exc.missing_fields)},
                ) from exc
            except RoutingProviderUnavailableError as exc:
                raise ApiError(503, exc.code, str(exc)) from exc
            finally:
                await exact_provider.aclose()
        return core_cycle

    @staticmethod
    def _cycle_tasks(
        cycle: DbRouteCycle,
        snapshot: _RuntimeSnapshot,
    ) -> list[PlanningTask]:
        """Return cycle tasks in persisted stop order."""

        result: list[PlanningTask] = []
        for stop in sorted(cycle.stops, key=lambda item: item.sequence):
            if stop.task_id is None:
                continue
            task = snapshot.core_task_by_uuid.get(stop.task_id)
            if task is None:
                raise ApiError(
                    422,
                    "PLAN_TASK_MISSING",
                    f"Route stop {stop.id} references a missing planning task",
                )
            result.append(task)
        return result

    async def _replace_cycle(
        self,
        session: AsyncSession,
        cycle: DbRouteCycle,
        core_cycle: RouteCycle,
        snapshot: _RuntimeSnapshot,
    ) -> None:
        """Replace one cycle schedule in place while preserving its stable database UUID."""

        await session.execute(
            delete(RouteSegment)
            .where(RouteSegment.route_cycle_id == cycle.id)
            .execution_options(synchronize_session=False)
        )
        await session.execute(
            delete(RouteExplanation)
            .where(RouteExplanation.route_cycle_id == cycle.id)
            .execution_options(synchronize_session=False)
        )
        await session.execute(
            delete(DbRouteStop)
            .where(DbRouteStop.route_cycle_id == cycle.id)
            .execution_options(synchronize_session=False)
        )
        await session.flush()
        cycle.driver_shift_id = UUID(core_cycle.driver_shift_id)
        cycle.sequence = core_cycle.sequence
        cycle.planned_start = core_cycle.planned_start
        cycle.planned_finish = core_cycle.planned_finish
        cycle.total_distance_meters = core_cycle.total_distance_meters
        cycle.total_travel_seconds = core_cycle.total_travel_seconds
        cycle.total_service_seconds = core_cycle.total_service_seconds
        cycle.empty_distance_meters = core_cycle.empty_distance_meters
        cycle.detour_seconds = core_cycle.detour_seconds
        cycle.score = core_cycle.score
        cycle.manually_changed = True
        cycle.metrics = self._cycle_metrics(core_cycle)
        await self._populate_cycle(session, cycle, core_cycle, snapshot)
        session.expire(cycle, ["stops", "segments", "explanations"])

    async def _lock_task(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeRequest,
    ) -> RoutePlan:
        """Lock or unlock one task in the selected plan and append an audit record."""

        plan = await plan_service.assert_plan_version(session, plan_id, command.expected_version)
        task_id = self._payload_uuid(command.payload, "task_id")
        locked_value = command.payload.get("locked", True)
        if not isinstance(locked_value, bool):
            raise ApiError(422, "TASK_LOCK_INVALID", "locked must be a boolean")
        matching_stops = [
            stop
            for cycle in plan.cycles
            for stop in cycle.stops
            if stop.task_id == task_id
        ]
        if not matching_stops:
            raise not_found("assigned_planning_task", task_id)
        previous: dict[str, object] = {
            "locked": all(stop.locked for stop in matching_stops)
        }
        for stop in matching_stops:
            stop.locked = locked_value
        task = await session.get(DbPlanningTask, task_id)
        if task is not None:
            task.locked = locked_value
        await plan_service.record_manual_change(
            session,
            plan,
            command,
            previous_value=previous,
            new_value={"locked": locked_value},
        )
        plan.status = PlanStatus.DRAFT
        await session.flush()
        return plan

    async def _persist_unavailability(
        self,
        session: AsyncSession,
        plan: RoutePlan,
        command: ManualChangeRequest,
        shift_id: UUID,
        effective_at: datetime,
    ) -> None:
        """Persist an explicit unavailable-driver scenario as an audited plan warning."""

        affected = sorted(
            {
                str(stop.task_id)
                for cycle in plan.cycles
                if cycle.driver_shift_id == shift_id
                for stop in cycle.stops
                if stop.task_id is not None and stop.planned_arrival >= effective_at
            }
        )
        warning: dict[str, object] = {
            "code": "DRIVER_UNAVAILABLE",
            "message_ru": "Водитель недоступен; оставшиеся задания требуют перепланирования.",
            "driver_shift_id": str(shift_id),
            "effective_at": effective_at.isoformat(),
            "affected_task_ids": affected,
        }
        plan.validation_warnings = [*plan.validation_warnings, warning]
        plan.status = PlanStatus.DRAFT
        await plan_service.record_manual_change(
            session,
            plan,
            command,
            previous_value=None,
            new_value=warning,
        )
        await session.flush()

    @staticmethod
    def _store_validation(
        plan: RoutePlan,
        errors: Any,
        warnings: Any,
        metrics: Any,
    ) -> None:
        """Persist one validation verdict and its recalculated metrics."""

        plan.validation_errors = [RuntimePlannerFacade._issue_payload(issue) for issue in errors]
        plan.validation_warnings = [
            RuntimePlannerFacade._issue_payload(issue) for issue in warnings
        ]
        if metrics is not None:
            plan.metrics = asdict(metrics)

    @staticmethod
    def _issue_payload(issue: ValidationIssue) -> dict[str, Any]:
        """Serialize a domain validation issue for the stable route-plan response."""

        payload: dict[str, Any] = {
            "code": issue.code.value,
            "message_ru": issue.message_ru,
        }
        if issue.cycle_id is not None:
            payload["cycle_id"] = issue.cycle_id
        if issue.task_id is not None:
            payload["task_id"] = issue.task_id
        return payload

    @staticmethod
    def _plan_task_count(plan: RoutePlan, cycles: Any) -> int:
        """Count unique assigned plus currently unassigned transport tasks."""

        assigned = {task_id for cycle in cycles for task_id in cycle.task_ids}
        return len(assigned) + len(plan.unassigned_tasks)

    @staticmethod
    def _expire_plan_graph(session: AsyncSession, plan: RoutePlan) -> None:
        """Force the API readback to observe replaced children in the same session."""

        session.expire(plan, ["cycles", "unassigned_tasks"])

    @staticmethod
    def _metric_int(values: Mapping[str, Any], key: str) -> int:
        """Read a non-negative integer planner metric from JSON storage."""

        value = values.get(key, 0)
        return max(0, round(value)) if isinstance(value, (int, float)) else 0

    @staticmethod
    def _metric_float(values: Mapping[str, Any], key: str) -> float:
        """Read a non-negative float planner metric from JSON storage."""

        value = values.get(key, 0.0)
        return max(0.0, float(value)) if isinstance(value, (int, float)) else 0.0

    @staticmethod
    def _warning_message(code: str) -> str:
        """Return a concise Russian explanation for one persisted cycle warning."""

        return {
            "HIGH_DETOUR": "Крюк за вывозом превышает рекомендуемый лимит.",
            "CROSS_ROUTE_GROUP": "Рейс проходит через несколько маршрутных групп.",
            "LOW_TIME_BUFFER": "До конца смены остаётся небольшой резерв.",
            "SOFT_WINDOW_RISK": "Рейс рискует выйти за мягкое временное окно.",
            "OVERTIME_WARNING": "Рейс использует разрешённую мягкую переработку.",
            "INEFFICIENT_EMPTY_RUN": "Рейс содержит неэффективный пустой пробег.",
        }.get(code, code)

    @staticmethod
    def _payload_mapping(value: object) -> Mapping[str, Any] | None:
        """Validate an optional nested settings mapping."""

        if value is None:
            return None
        if not isinstance(value, dict):
            raise ApiError(422, "PAYLOAD_INVALID", "settings must be an object")
        return value

    @staticmethod
    def _optional_int(value: object) -> int | None:
        """Validate an optional integer command value."""

        if value is None:
            return None
        if not isinstance(value, int):
            raise ApiError(422, "PAYLOAD_INVALID", "seed must be an integer")
        return value

    @staticmethod
    def _optional_uuid(value: object) -> UUID | None:
        """Parse an optional UUID command value."""

        if value is None:
            return None
        try:
            return UUID(str(value))
        except ValueError as exc:
            raise ApiError(422, "PAYLOAD_INVALID", "Expected a UUID value") from exc

    @staticmethod
    def _payload_uuid(payload: Mapping[str, Any], key: str) -> UUID:
        """Parse a required UUID command field."""

        value = payload.get(key)
        if value is None:
            raise ApiError(422, "PAYLOAD_INVALID", f"{key} is required")
        parsed = RuntimePlannerFacade._optional_uuid(value)
        if parsed is None:
            raise ApiError(422, "PAYLOAD_INVALID", f"{key} is required")
        return parsed

    @staticmethod
    def _payload_datetime(payload: Mapping[str, Any], key: str) -> datetime:
        """Parse a required aware ISO timestamp from a simulation command."""

        value = payload.get(key)
        try:
            parsed = value if isinstance(value, datetime) else datetime.fromisoformat(str(value))
        except ValueError as exc:
            raise ApiError(422, "PAYLOAD_INVALID", f"{key} must be an ISO timestamp") from exc
        if parsed.tzinfo is None or parsed.utcoffset() is None:
            raise ApiError(422, "PAYLOAD_INVALID", f"{key} must be timezone-aware")
        return parsed

    @staticmethod
    def _payload_positive_int(payload: Mapping[str, Any], key: str) -> int:
        """Read a required positive integer simulation value."""

        value = payload.get(key)
        if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
            raise ApiError(422, "PAYLOAD_INVALID", f"{key} must be a positive integer")
        return value
