"""Production adapter between warehouse workspaces and the deterministic planner core."""

# ruff: noqa: RUF001 -- Russian operator-facing explanations are intentional.

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
from app.integrations.rwms import RwmsPlanningClient
from app.models import (
    DriverShift as DbDriverShift,
)
from app.models import (
    LogisticsRequest as DbLogisticsRequest,
)
from app.models import (
    OptimizationRun,
    OptimizationTraceEvent,
    PlanningDayClosure,
    RouteExplanation,
    RoutePlan,
    RouteSegment,
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
    RequestDateOption,
    RequestStatus,
    RouteCycle,
    RouteStop,
    StopType,
    TaskType,
    Vehicle,
    calculate_driver_workload_cost,
    calculate_plan_metrics,
    calculate_resource_activation_cost,
    validate_route_plan,
)
from app.planner import (
    Warehouse as PlannerWarehouse,
)
from app.planner.engine import CandidateRouteRejected, NullProgressPublisher
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
from app.services.support_resource_candidates import (
    RoutedSupportResource,
    load_support_resource_facts,
    route_support_resource_facts,
)
from app.services.truck_cycle_router import ExactTruckCycleRouter
from app.simulation import DelayOverride, DriverUnavailableOverride, propagate_delays
from app.slot_planning.configuration import (
    effective_vehicle_cabin_capacity,
    vehicle_equipment_snapshot,
    vehicle_has_available_trailer,
    warehouse_slot_configuration,
)
from app.slot_planning.models import PlanningReason
from app.slot_planning.routing_adapter import CachedTruckTravelTimeProvider

if TYPE_CHECKING:
    from app.routing.truck_profile import EffectiveTruckProfile


@dataclass(frozen=True, slots=True)
class _RuntimeSnapshot:
    """Fully loaded immutable planning boundary plus persistence ID mappings."""

    warehouse: DbWarehouse
    input_data: PlanningInput
    settings: PlanningSettings
    routing_settings: RoutingSettings
    task_uuid_by_core_id: Mapping[str, UUID]
    core_task_by_uuid: Mapping[UUID, PlanningTask]
    request_task_uuids: Mapping[str, tuple[UUID, ...]]
    warehouse_fingerprint: str
    support_resources: Mapping[str, RoutedSupportResource]
    support_reason_codes: tuple[str, ...]
    support_source_revision: str | None


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
        warehouse_fingerprint: str,
        max_entries: int,
        provider_cache_key: tuple[object, ...],
    ) -> None:
        self._delegate = delegate
        self._cache = cache
        self._route_cache = route_cache
        self._warehouse_fingerprint = warehouse_fingerprint
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
            self._warehouse_fingerprint,
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
            self._warehouse_fingerprint,
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
    """Execute planning and validated edits against the warehouse-owned database."""

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
        rwms_client: RwmsPlanningClient | None = None,
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
        self._rwms_client = rwms_client
        self._matrix_cache: OrderedDict[tuple[object, ...], TravelMatrix] = OrderedDict()
        self._route_cache: OrderedDict[tuple[object, ...], RouteGeometry] = OrderedDict()

    async def generate_plan(
        self,
        session: AsyncSession,
        warehouse_id: UUID,
        command: GeneratePlanRequest,
    ) -> OptimizationRun:
        """Generate and persist one independent best-known plan and optimizer run."""

        snapshot = await self._load_snapshot(
            session,
            warehouse_id,
            command.date,
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
            plan.warehouse_id,
            plan.date,
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

    async def refresh_plan_after_request_changes(
        self,
        session: AsyncSession,
        plan_id: UUID,
        expected_version: int,
    ) -> RoutePlan:
        """Refresh a fenced draft in place while preserving task and cycle identities."""

        plan = await plan_service.assert_plan_version(session, plan_id, expected_version)
        marker = plan.metrics.get(plan_service.PENDING_REQUEST_REFRESH_METRIC)
        if not isinstance(marker, dict):
            return plan
        if plan.status == PlanStatus.CONFIRMED:
            raise ApiError(
                409,
                "PLAN_ALREADY_CONFIRMED",
                "A confirmed plan cannot be refreshed",
            )
        snapshot = await self._load_snapshot(
            session,
            plan.warehouse_id,
            plan.date,
            None,
            None,
        )
        refreshed_by_id: dict[UUID, RouteCycle] = {}
        refreshed_by_shift: dict[UUID, list[RouteCycle]] = {}
        cursor_by_shift: dict[UUID, datetime] = {}
        turnaround = timedelta(
            minutes=(
                snapshot.input_data.warehouse.turnaround_minutes
                + snapshot.settings.default_route_buffer_minutes
            )
        )
        for cycle in sorted(
            plan.cycles,
            key=lambda item: (str(item.driver_shift_id), item.sequence, str(item.id)),
        ):
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
            start_at = cursor_by_shift.get(cycle.driver_shift_id, shift.start_at)
            core_cycle = await self._reschedule_cycle(
                snapshot,
                cycle,
                self._cycle_tasks(cycle, snapshot),
                start_at=start_at,
                existing_shift_cycles=tuple(
                    refreshed_by_shift.get(cycle.driver_shift_id, ())
                ),
                mark_manually_changed=False,
            )
            refreshed_by_id[cycle.id] = core_cycle
            refreshed_by_shift.setdefault(cycle.driver_shift_id, []).append(core_cycle)
            cursor_by_shift[cycle.driver_shift_id] = core_cycle.planned_finish + turnaround

        candidate_cycles = tuple(refreshed_by_id[cycle.id] for cycle in plan.cycles)
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
            unassigned_tasks=len(plan.unassigned_tasks),
            score=score,
        )
        if validation.errors:
            raise ApiError(
                422,
                "PLAN_REFRESH_INVALID",
                "The preserved operator order is no longer feasible",
                extra={
                    "errors": [self._issue_payload(issue) for issue in validation.errors]
                },
            )

        for cycle in plan.cycles:
            await self._replace_cycle(
                session,
                cycle,
                refreshed_by_id[cycle.id],
                snapshot,
            )
        plan.score = score
        self._store_validation(plan, (), validation.warnings, validation.metrics)
        refreshed_metrics = dict(plan.metrics)
        refreshed_metrics.pop(plan_service.PENDING_REQUEST_REFRESH_METRIC, None)
        plan.metrics = refreshed_metrics
        plan.status = PlanStatus.DRAFT
        plan.version += 1
        await session.flush()
        self._expire_plan_graph(session, plan)
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
            source.warehouse_id,
            source.date,
            self._payload_mapping(command.payload.get("settings")),
            self._optional_int(command.payload.get("seed")),
        )
        locked = tuple(self._core_cycle(cycle, snapshot) for cycle in source.cycles if cycle.locked)
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

    async def reset_manual_changes(
        self,
        session: AsyncSession,
        plan_id: UUID,
        expected_version: int,
    ) -> RoutePlan:
        """Archive a mutable edited plan and return a freshly generated replacement."""

        source = await plan_service.get_plan(session, plan_id, for_update=True)
        reset_marker = source.metrics.get("manual_reset")
        if isinstance(reset_marker, dict) and reset_marker.get("expected_version") == (
            expected_version
        ):
            replacement_id = self._optional_uuid(reset_marker.get("replacement_plan_id"))
            if replacement_id is not None:
                return await plan_service.get_plan(session, replacement_id)
        if source.version != expected_version:
            raise ApiError(
                409,
                "PLAN_VERSION_CONFLICT",
                "The route plan changed after it was loaded",
                extra={
                    "expected_version": expected_version,
                    "actual_version": source.version,
                },
            )
        if source.status == PlanStatus.CONFIRMED:
            raise ApiError(
                409,
                "PLAN_ALREADY_CONFIRMED",
                "A confirmed plan cannot be reset",
            )
        if not source.manually_changed:
            raise ApiError(
                409,
                "PLAN_HAS_NO_MANUAL_CHANGES",
                "Only a manually changed plan can be reset",
            )

        snapshot = await self._load_snapshot(
            session,
            source.warehouse_id,
            source.date,
            None,
            None,
        )
        run = await self._execute_generation(session, snapshot, locked_cycles=())
        if run.plan_id is None:
            raise ApiError(
                422,
                "PLAN_RESET_FAILED",
                run.error_message or "The automatic replacement could not be generated",
            )
        source.status = PlanStatus.ARCHIVED
        source.version += 1
        source.metrics = {
            **source.metrics,
            "manual_reset": {
                "expected_version": expected_version,
                "replacement_plan_id": str(run.plan_id),
            },
        }
        await session.flush()
        return await plan_service.get_plan(session, run.plan_id)

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
            plan.warehouse_id,
            plan.date,
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
        warehouse_id: UUID,
        planning_date: date,
        command_settings: Mapping[str, Any] | None,
        command_seed: int | None,
    ) -> _RuntimeSnapshot:
        """Load one complete warehouse graph and translate it into planner value objects."""

        statement = (
            select(DbWarehouse)
            .where(DbWarehouse.id == warehouse_id)
            .options(
                selectinload(DbWarehouse.vehicles).selectinload(DbVehicle.default_trailer),
                selectinload(DbWarehouse.vehicles).selectinload(DbVehicle.load_profiles),
                selectinload(DbWarehouse.shifts).selectinload(DbDriverShift.driver),
                selectinload(DbWarehouse.shifts).selectinload(DbDriverShift.vehicle),
                selectinload(DbWarehouse.requests).selectinload(DbLogisticsRequest.date_options),
                selectinload(DbWarehouse.requests).selectinload(DbLogisticsRequest.tasks),
            )
        )
        workspace = await session.scalar(statement)
        if workspace is None:
            raise not_found("warehouse", warehouse_id)
        closure = await session.scalar(
            select(PlanningDayClosure.id).where(
                PlanningDayClosure.warehouse_id == warehouse_id,
                PlanningDayClosure.date == planning_date,
            )
        )
        accepting_requests = closure is None
        zone_info = ZoneInfo(workspace.timezone)
        settings = self._planning_settings(
            workspace.settings,
            command_settings,
            command_seed if command_seed is not None else workspace.seed,
        )
        routing_settings = self._routing_settings(
            workspace.settings,
            command_settings,
            settings.seed,
        )
        request_entities = sorted(
            workspace.requests,
            key=lambda item: (item.created_at, item.id),
        )
        eligible_request_entities = [
            request
            for request in request_entities
            if request_is_available_on_date(
                request.scheduled_date,
                (option.date for option in request.date_options),
                planning_date,
            )
        ]
        self._assert_planning_details_complete(
            eligible_request_entities,
            planning_date,
        )
        requests = tuple(
            self._core_request(request, zone_info)
            for request in eligible_request_entities
        )
        vehicles = tuple(
            self._core_vehicle(vehicle)
            for vehicle in sorted(workspace.vehicles, key=lambda item: str(item.id))
        )
        shifts = tuple(
            DriverShift(
                id=str(shift.id),
                driver_id=str(shift.driver_id),
                driver_name=shift.driver.name,
                vehicle_id=str(shift.vehicle_id),
                start_at=datetime.combine(planning_date, shift.start_time, tzinfo=zone_info),
                end_at=datetime.combine(planning_date, shift.end_time, tzinfo=zone_info),
                break_minutes=shift.break_minutes,
                active=(shift.active and shift.driver.active and shift.vehicle.active),
            )
            for shift in sorted(
                workspace.shifts,
                key=lambda item: (item.date_from, item.start_time, item.id),
            )
            if shift.date_from <= planning_date <= shift.date_to
        )
        depot = PlannerWarehouse(
            id=str(workspace.id),
            name=workspace.name,
            point=GeoPoint(
                lon=workspace.longitude,
                lat=workspace.latitude,
                is_city=True,
            ),
            loading_minutes=workspace.loading_minutes,
            unloading_minutes=workspace.unloading_minutes,
            turnaround_minutes=workspace.turnaround_minutes,
        )
        task_uuid_by_core_id: dict[str, UUID] = {}
        core_task_by_uuid: dict[UUID, PlanningTask] = {}
        request_task_uuids: dict[str, tuple[UUID, ...]] = {}
        core_request_by_id = {request.id: request for request in requests}
        for request_entity in workspace.requests:
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
                workspace.id,
                sorted(
                    (request.id, request.latitude, request.longitude)
                    for request in workspace.requests
                ),
                asdict(routing_settings),
                accepting_requests,
            )
        )
        snapshot = _RuntimeSnapshot(
            warehouse=workspace,
            input_data=PlanningInput(
                warehouse_id=str(workspace.id),
                planning_date=planning_date,
                warehouse=depot,
                requests=requests,
                shifts=shifts,
                vehicles=vehicles,
                accepting_requests=accepting_requests,
            ),
            settings=settings,
            routing_settings=routing_settings,
            task_uuid_by_core_id=task_uuid_by_core_id,
            core_task_by_uuid=core_task_by_uuid,
            request_task_uuids=request_task_uuids,
            warehouse_fingerprint=fingerprint,
            support_resources={},
            support_reason_codes=(),
            support_source_revision=None,
        )
        return await self._with_support_resources(session, snapshot)

    async def _with_support_resources(
        self,
        session: AsyncSession,
        snapshot: _RuntimeSnapshot,
    ) -> _RuntimeSnapshot:
        """Add routed one-day support shifts without changing workforce ownership."""

        if not snapshot.warehouse.representative or self._rwms_client is None:
            return snapshot
        configuration = warehouse_slot_configuration(snapshot.warehouse)
        zone = ZoneInfo(snapshot.warehouse.timezone)
        planning_instants = tuple(
            datetime.combine(snapshot.input_data.planning_date, value, tzinfo=zone)
            for value in dict.fromkeys(
                (
                    configuration.driver_day_start,
                    *(start for start, _ in configuration.customer_slots),
                )
            )
        )
        facts = await load_support_resource_facts(
            session,
            self._rwms_client,
            snapshot.warehouse,
            snapshot.input_data.planning_date,
            planning_instants,
            vehicle_equipment_snapshot,
        )
        if not facts.candidates:
            return replace(
                snapshot,
                support_reason_codes=(
                    (
                        "CONTRACTOR_REQUIRED"
                        if facts.contractor_fallback_allowed
                        else "NO_SUPPORT_RESOURCE"
                    ),
                ),
                support_source_revision=facts.source_revision,
                warehouse_fingerprint=repr(
                    (snapshot.warehouse_fingerprint, facts.source_revision)
                ),
            )
        provider = self._provider(snapshot)
        support_router = CachedTruckTravelTimeProvider(
            provider,
            facts.equipment,
            routing_version=(
                "cross-warehouse-day-plan",
                self._routing_provider,
                self._osm_data_version,
                facts.source_revision,
            ),
        )
        try:
            resolution = await route_support_resource_facts(
                facts,
                configuration,
                support_router,
                support_router,
            )
        finally:
            await provider.aclose()

        support_resources = {
            str(candidate.fact.shift.id): candidate
            for candidate in resolution.candidates
        }
        vehicles_by_id = {
            vehicle.id: vehicle for vehicle in snapshot.input_data.vehicles
        }
        shifts_by_id = {
            shift.id: shift for shift in snapshot.input_data.shifts
        }
        for candidate in resolution.candidates:
            fact = candidate.fact
            vehicle = self._core_vehicle(fact.shift.vehicle)
            vehicles_by_id.setdefault(vehicle.id, vehicle)
            # The synthetic interval begins immediately before the shared warehouse-load
            # operation. Inbound travel, unloading and turnaround are already consumed by
            # the routed resolver; the original support shift remains the persisted FK.
            service_start = candidate.available_at_served - timedelta(
                minutes=configuration.load_one_minutes
            ) + timedelta(
                microseconds=min(max(fact.link.priority - 1, 0), 999_999)
            )
            shifts_by_id.setdefault(
                str(fact.shift.id),
                DriverShift(
                    id=str(fact.shift.id),
                    driver_id=str(fact.shift.driver_id),
                    driver_name=fact.identity.display_name,
                    vehicle_id=str(fact.shift.vehicle_id),
                    start_at=service_start,
                    end_at=candidate.latest_served_finish,
                    break_minutes=fact.shift.break_minutes,
                    active=True,
                    resource_origin_warehouse_id=str(
                        fact.support_warehouse.external_warehouse_id
                    ),
                    support_link_id=str(fact.link.support_link_id),
                    support_priority=fact.link.priority,
                    positioning_travel_minutes=(
                        candidate.inbound_travel_minutes
                        + candidate.return_travel_minutes
                    ),
                    positioning_distance_meters=(
                        candidate.positioning_distance_meters
                    ),
                    return_required=True,
                ),
            )
        reason_codes = tuple(reason.value for reason in resolution.reasons)
        return replace(
            snapshot,
            input_data=replace(
                snapshot.input_data,
                shifts=tuple(
                    sorted(
                        shifts_by_id.values(),
                        key=lambda item: (item.start_at, item.driver_id, item.id),
                    )
                ),
                vehicles=tuple(
                    sorted(vehicles_by_id.values(), key=lambda item: item.id)
                ),
            ),
            support_resources=support_resources,
            support_reason_codes=reason_codes,
            support_source_revision=facts.source_revision,
            warehouse_fingerprint=repr(
                (snapshot.warehouse_fingerprint, facts.source_revision)
            ),
        )

    @staticmethod
    def _core_request(
        request: DbLogisticsRequest,
        zone_info: ZoneInfo,
    ) -> LogisticsRequest:
        """Translate one persisted request without trusting any client zone input."""

        point = GeoPoint(
            lon=request.longitude,
            lat=request.latitude,
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
                travel_zone_hours=option.travel_zone_hours,
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
            trailer_access_allowed=(request.trailer_access_allowed is not False),
            task_quantities=tuple(
                task.quantity for task in sorted(request.tasks, key=lambda item: item.part_number)
            ),
            mandatory=request.mandatory,
        )

    @staticmethod
    def _assert_planning_details_complete(
        requests: Iterable[DbLogisticsRequest],
        planning_date: date,
    ) -> None:
        """Reject planning until every eligible ready request has operator decisions."""

        incomplete: list[dict[str, object]] = []
        for request in requests:
            if request.status != RequestStatus.READY:
                continue
            option = next(
                (item for item in request.date_options if item.date == planning_date),
                None,
            )
            missing_fields: list[str] = []
            if option is None:
                missing_fields.extend(("date_option", "time_window"))
            elif option.window_start is None or option.window_end is None:
                missing_fields.append("time_window")
            if request.trailer_access_allowed is None:
                missing_fields.append("trailer_access_allowed")
            if missing_fields:
                incomplete.append(
                    {
                        "request_id": str(request.id),
                        "name": request.name,
                        "missing_fields": missing_fields,
                    }
                )
        if incomplete:
            raise ApiError(
                422,
                "PLANNING_INPUT_INCOMPLETE",
                "Set a service window and trailer-access decision for every request",
                extra={"requests": incomplete},
            )

    @staticmethod
    def _request_source_key(request: DbLogisticsRequest) -> str | None:
        """Return authoritative source identity or a coordinate key for legacy generator rows."""

        if not request.source_system:
            return None
        if (
            request.source_system != "WAREHOUSE_WORKLOAD_GENERATOR"
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
                (scheduled_date.isoformat(),) if isinstance(scheduled_date, date) else ()
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
        if not matching:
            raise ApiError(
                422,
                "PLANNING_INPUT_INCOMPLETE",
                "A persisted planning task has no accepted option for the plan date",
                extra={
                    "requests": [
                        {
                            "request_id": request.id,
                            "name": request.name,
                            "missing_fields": ["date_option", "time_window"],
                        }
                    ]
                },
            )
        selected = min(
            matching,
            key=lambda option: (
                not option.is_hard,
                -option.priority,
                option.width,
                option.window_start or request.created_at,
            ),
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
            trailer_access_allowed=request.trailer_access_allowed,
            mandatory=task.mandatory,
        )

    @staticmethod
    def _core_vehicle(vehicle: DbVehicle) -> Vehicle:
        """Translate one active-or-inactive catalog vehicle without changing ownership."""

        return Vehicle(
            id=str(vehicle.id),
            name=vehicle.name,
            capacity=vehicle.capacity,
            active=vehicle.active,
            routing_spec=RuntimePlannerFacade._vehicle_routing_spec(vehicle),
            default_trailer=RuntimePlannerFacade._trailer_spec(vehicle.default_trailer),
            axle_load_profiles=tuple(
                OperationalAxleLoadProfile(
                    configuration_type=TruckConfigurationType(profile.configuration_type),
                    max_actual_axle_load_kg=profile.max_actual_axle_load_kg,
                )
                for profile in vehicle.load_profiles
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
        warehouse_settings: Mapping[str, Any],
        command_settings: Mapping[str, Any] | None,
        seed: int,
    ) -> PlanningSettings:
        """Select only planner-owned fields from warehouse and command snapshots."""

        allowed = {field.name for field in fields(PlanningSettings)}
        values = {
            key: value
            for key, value in {**warehouse_settings, **(command_settings or {})}.items()
            if key in allowed
        }
        values["seed"] = seed
        try:
            return PlanningSettings(**values)
        except (TypeError, ValueError) as exc:
            raise ApiError(422, "PLANNING_SETTINGS_INVALID", str(exc)) from exc

    @staticmethod
    def _routing_settings(
        warehouse_settings: Mapping[str, Any],
        command_settings: Mapping[str, Any] | None,
        seed: int,
    ) -> RoutingSettings:
        """Build the offline routing snapshot independently from planner weights."""

        source = {**warehouse_settings, **(command_settings or {})}
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
            warehouse_id=snapshot.warehouse.id,
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
                OptimizationStatus.TIMED_OUT if result.timed_out else OptimizationStatus.COMPLETED
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
            snapshot.warehouse_fingerprint,
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
            snapshot.warehouse_fingerprint,
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
                (first.sequence, second.sequence) for first, second in pairwise(ordered_stops)
            )
            if len(cycle.legs) != len(expected_pairs):
                raise RuntimeError("planner cycle leg count does not match its ordered stop count")
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

        enriched_cycles = await asyncio.gather(*(enrich_cycle(cycle) for cycle in result.cycles))
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
            warehouse_id=snapshot.warehouse.id,
            date=snapshot.input_data.planning_date,
            name=f"Автоплан · seed {result.seed}",
            version=1,
            status=PlanStatus.GENERATED,
            score=result.score,
            metrics={
                **asdict(metrics),
                "accepting_requests": snapshot.input_data.accepting_requests,
                "support_candidate_count": len(snapshot.support_resources),
                "support_reason_codes": list(snapshot.support_reason_codes),
                "support_source_revision": snapshot.support_source_revision,
                "support_positioning_distance_meters": sum(
                    resource.positioning_distance_meters
                    for shift_id, resource in snapshot.support_resources.items()
                    if any(
                        cycle.driver_shift_id == shift_id for cycle in result.cycles
                    )
                ),
                "support_positioning_travel_minutes": sum(
                    resource.inbound_travel_minutes + resource.return_travel_minutes
                    for shift_id, resource in snapshot.support_resources.items()
                    if any(
                        cycle.driver_shift_id == shift_id for cycle in result.cycles
                    )
                ),
            },
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
                metrics=self._cycle_metrics(core_cycle, snapshot),
            )
            session.add(cycle)
            await session.flush()
            await self._populate_cycle(session, cycle, core_cycle, snapshot)
        for task_uuid, item in sorted(unassigned_by_task.items(), key=lambda pair: str(pair[0])):
            support_reasons = [
                code
                for code in snapshot.support_reason_codes
                if code not in {reason.value for reason in item.reason_codes}
            ]
            contractor_required = "CONTRACTOR_REQUIRED" in support_reasons
            session.add(
                DbUnassignedTask(
                    route_plan_id=plan.id,
                    task_id=task_uuid,
                    reason_codes=[
                        *(reason.value for reason in item.reason_codes),
                        *support_reasons,
                    ],
                    descriptions_ru=[
                        *item.explanation_ru,
                        *(
                            ["Не найден подходящий штатный ресурс."]
                            if contractor_required
                            else []
                        ),
                    ],
                    nearest_option=(
                        {"possible_at": item.nearest_possible_at.isoformat()}
                        if item.nearest_possible_at is not None
                        else None
                    ),
                    recommendation_ru=(
                        "Добавить наёмного водителя на этот день и пересчитать план."
                        if contractor_required
                        else (
                            " ".join(item.recommendation_ru)
                            if item.recommendation_ru
                            else None
                        )
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
        *,
        locked_task_ids: frozenset[UUID] = frozenset(),
    ) -> None:
        """Persist stops, route geometry, explanations, and retained task locks."""

        stop_ids: dict[int, UUID] = {}
        warning_payloads = [
            {"code": warning.value, "message_ru": self._warning_message(warning.value)}
            for warning in core_cycle.warnings
        ]
        for core_stop in core_cycle.stops:
            task_uuid = (
                snapshot.task_uuid_by_core_id.get(core_stop.task_id)
                if core_stop.task_id is not None
                else None
            )
            stop = DbRouteStop(
                route_cycle_id=cycle.id,
                sequence=core_stop.sequence,
                task_id=task_uuid,
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
                locked=task_uuid in locked_task_ids,
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
        support = snapshot.support_resources.get(core_cycle.driver_shift_id)
        if support is not None:
            session.add(
                RouteExplanation(
                    route_cycle_id=cycle.id,
                    explanation_type="CROSS_WAREHOUSE_SERVICE",
                    summary_ru=(
                        f"Ресурс привлечён со склада "
                        f"«{support.fact.support_warehouse.name}» на один рейс; "
                        "базовый и оперативный склад водителя не меняется."
                    ),
                    facts=[
                        *({"code": reason.value} for reason in support.reason_codes),
                        {
                            "supportWarehouseLinkId": str(
                                support.fact.link.support_link_id
                            ),
                            "supportWarehouseId": str(
                                support.fact.support_warehouse.external_warehouse_id
                            ),
                            "servedWarehouseId": str(
                                snapshot.warehouse.external_warehouse_id
                            ),
                            "driverWorkerId": str(support.fact.identity.worker_id),
                            "vehicleId": str(support.fact.shift.vehicle_id),
                            "availableAtServed": support.available_at_served.isoformat(),
                            "latestServedFinish": support.latest_served_finish.isoformat(),
                            "inboundTravelMinutes": support.inbound_travel_minutes,
                            "returnTravelMinutes": support.return_travel_minutes,
                            "inboundDistanceMeters": support.inbound_distance_meters,
                            "returnDistanceMeters": support.return_distance_meters,
                            "positioningDistanceMeters": (
                                support.positioning_distance_meters
                            ),
                            "returnsToSupportWarehouse": True,
                            "changesOperationalWarehouse": False,
                        },
                    ],
                )
            )
        await session.flush()

    @staticmethod
    def _cycle_metrics(
        cycle: RouteCycle,
        snapshot: _RuntimeSnapshot,
    ) -> dict[str, Any]:
        """Store planner-only cycle facts not represented by dedicated columns."""

        metrics: dict[str, Any] = {
            "waiting_seconds": cycle.waiting_seconds,
            "detour_ratio": cycle.detour_ratio,
            "driver_id": cycle.driver_id,
            "vehicle_id": cycle.vehicle_id,
        }
        support = snapshot.support_resources.get(cycle.driver_shift_id)
        if support is None:
            metrics["execution_mode"] = "LOCAL"
            return metrics
        vehicle = support.fact.shift.vehicle
        trailer_available = vehicle_has_available_trailer(vehicle)
        raw_capacity = min(2, vehicle.capacity)
        transfer_capacity = effective_vehicle_cabin_capacity(vehicle)
        capacity_reasons: list[PlanningReason] = []
        if transfer_capacity == 1:
            capacity_reasons.append(
                PlanningReason.TRAILER_REQUIRED
                if raw_capacity > 1 and not trailer_available
                else PlanningReason.VEHICLE_CAPACITY_ONE_CABIN
            )
        metrics.update(
            {
                "execution_mode": "CROSS_WAREHOUSE_SERVICE",
                "service_warehouse_id": str(snapshot.warehouse.external_warehouse_id),
                "resource_origin_warehouse_id": str(
                    support.fact.support_warehouse.external_warehouse_id
                ),
                "support_warehouse_link_id": str(support.fact.link.support_link_id),
                "driver_worker_id": str(support.fact.identity.worker_id),
                "driver_name": support.fact.identity.display_name,
                "vehicle_name": support.fact.shift.vehicle.name,
                "vehicle_registration_number": (
                    support.fact.shift.vehicle.registration_number
                ),
                "available_at_served": support.available_at_served.isoformat(),
                "latest_served_finish": support.latest_served_finish.isoformat(),
                "inbound_travel_minutes": support.inbound_travel_minutes,
                "return_travel_minutes": support.return_travel_minutes,
                "inbound_distance_meters": support.inbound_distance_meters,
                "return_distance_meters": support.return_distance_meters,
                "positioning_distance_meters": support.positioning_distance_meters,
                "positioning_outbound_geometry": support.inbound_geometry,
                "positioning_return_geometry": support.return_geometry,
                "available_transfer_cabin_capacity": transfer_capacity,
                "trailer_available": trailer_available,
                "outbound_positioning_empty": True,
                "empty_positioning_reason_required": True,
                "returns_to_origin": True,
                "changes_operational_warehouse": False,
                "reason_codes": [
                    *(reason.value for reason in support.reason_codes),
                    *(reason.value for reason in capacity_reasons),
                ],
            }
        )
        return metrics

    def _core_cycle(
        self,
        cycle: DbRouteCycle,
        snapshot: _RuntimeSnapshot,
    ) -> RouteCycle:
        """Reconstruct the pure domain cycle used by validation and simulation."""

        shift = next(
            (item for item in snapshot.input_data.shifts if item.id == str(cycle.driver_shift_id)),
            None,
        )
        if shift is None:
            raise ApiError(
                422,
                "PLAN_SHIFT_MISSING",
                f"Plan cycle {cycle.id} references a missing driver shift",
            )
        stop_by_id = {stop.id: stop for stop in cycle.stops}
        stops: list[RouteStop] = []
        warning_codes: set[ValidationWarningCode] = set()
        for db_stop in sorted(cycle.stops, key=lambda item: item.sequence):
            core_task = (
                snapshot.core_task_by_uuid.get(db_stop.task_id)
                if db_stop.task_id is not None
                else None
            )
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
                    window_start=(
                        core_task.selected_option.window_start if core_task is not None else None
                    ),
                    window_end=(
                        core_task.selected_option.window_end if core_task is not None else None
                    ),
                    window_is_hard=(
                        core_task.selected_option.is_hard if core_task is not None else False
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
            plan.warehouse_id,
            plan.date,
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
            stop.task_id == task_id and stop.locked for cycle in plan.cycles for stop in cycle.stops
        ):
            raise ApiError(409, "TASK_LOCKED", "The selected task is locked")

        manually_changed_ids = {target_cycle.id}
        if source_cycle is not None:
            manually_changed_ids.add(source_cycle.id)
        affected_shift_ids = {target_cycle.driver_shift_id}
        if source_cycle is not None:
            affected_shift_ids.add(source_cycle.driver_shift_id)
        affected_ids = {
            cycle.id
            for cycle in plan.cycles
            if cycle.driver_shift_id in affected_shift_ids
        }
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
        target_tasks = self._tasks_in_route_phase_order(target_tasks)
        source_tasks = (
            self._tasks_in_route_phase_order(
                [
                    task
                    for task in self._cycle_tasks(source_cycle, snapshot)
                    if task.id != core_task.id
                ]
            )
            if source_cycle is not None and source_cycle is not target_cycle
            else None
        )

        task_overrides: dict[UUID, list[PlanningTask] | None] = {
            target_cycle.id: target_tasks,
        }
        if source_cycle is not None and source_cycle is not target_cycle:
            task_overrides[source_cycle.id] = source_tasks
        replacements = await self._reschedule_affected_shifts(
            snapshot,
            plan.cycles,
            affected_shift_ids=frozenset(affected_shift_ids),
            task_overrides=task_overrides,
            manually_changed_ids=frozenset(manually_changed_ids),
        )
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

        for cycle in tuple(plan.cycles):
            if cycle.id not in replacements:
                continue
            replacement = replacements[cycle.id]
            if replacement is None:
                await session.delete(cycle)
            else:
                await self._replace_cycle(session, cycle, replacement, snapshot)
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

    async def _reschedule_affected_shifts(
        self,
        snapshot: _RuntimeSnapshot,
        cycles: Iterable[DbRouteCycle],
        *,
        affected_shift_ids: frozenset[UUID],
        task_overrides: Mapping[UUID, list[PlanningTask] | None],
        manually_changed_ids: frozenset[UUID],
    ) -> dict[UUID, RouteCycle | None]:
        """Rebuild every affected shift sequentially without changing cycle identity.

        Existing idle gaps remain valid lower bounds, while a longer preceding
        cycle pushes every following cycle forward by the configured depot
        turnaround. Empty source cycles are removed from the sequence.
        """

        shift_by_id = {
            UUID(shift.id): shift
            for shift in snapshot.input_data.shifts
            if UUID(shift.id) in affected_shift_ids
        }
        rebuilt_by_shift: dict[UUID, list[RouteCycle]] = {}
        cursor_by_shift: dict[UUID, datetime] = {}
        replacements: dict[UUID, RouteCycle | None] = {}
        turnaround = timedelta(
            minutes=(
                snapshot.input_data.warehouse.turnaround_minutes
                + snapshot.settings.default_route_buffer_minutes
            )
        )
        for cycle in sorted(
            (item for item in cycles if item.driver_shift_id in affected_shift_ids),
            key=lambda item: (str(item.driver_shift_id), item.sequence, str(item.id)),
        ):
            shift = shift_by_id.get(cycle.driver_shift_id)
            if shift is None:
                raise ApiError(
                    422,
                    "PLAN_SHIFT_MISSING",
                    f"Plan cycle {cycle.id} references a missing driver shift",
                )
            tasks = (
                task_overrides[cycle.id]
                if cycle.id in task_overrides
                else self._cycle_tasks(cycle, snapshot)
            )
            if not tasks:
                replacements[cycle.id] = None
                continue
            earliest_start = cursor_by_shift.get(cycle.driver_shift_id, shift.start_at)
            core_cycle = await self._reschedule_cycle(
                snapshot,
                cycle,
                tasks,
                start_at=max(cycle.planned_start, earliest_start),
                existing_shift_cycles=tuple(
                    rebuilt_by_shift.get(cycle.driver_shift_id, ())
                ),
                mark_manually_changed=cycle.id in manually_changed_ids,
            )
            replacements[cycle.id] = core_cycle
            rebuilt_by_shift.setdefault(cycle.driver_shift_id, []).append(core_cycle)
            cursor_by_shift[cycle.driver_shift_id] = core_cycle.planned_finish + turnaround
        return replacements

    @staticmethod
    def _tasks_in_route_phase_order(tasks: list[PlanningTask]) -> list[PlanningTask]:
        """Clamp an operator insertion to delivery then pickup route phases.

        The stable partition preserves the requested relative order among
        deliveries and among pickups while preventing a sequence coordinate
        outside the selected task's phase from producing an invalid route.
        """

        return [
            *(task for task in tasks if task.task_type is TaskType.DELIVERY),
            *(task for task in tasks if task.task_type is TaskType.PICKUP),
        ]

    async def _reschedule_cycle(
        self,
        snapshot: _RuntimeSnapshot,
        cycle: DbRouteCycle,
        tasks: list[PlanningTask],
        *,
        start_at: datetime | None = None,
        existing_shift_cycles: tuple[RouteCycle, ...] | None = None,
        mark_manually_changed: bool = True,
    ) -> RouteCycle:
        """Rebuild one cycle in persisted task order through the production scheduler.

        The generated plan uses one matrix snapshot at the earliest active shift start.
        Reusing that departure instant here keeps a no-op manual edit deterministic;
        the cycle's actual start still drives time-window and shift validation.
        """

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
            default=cycle.planned_start.astimezone(ZoneInfo(snapshot.warehouse.timezone)),
        )
        matrix = await provider.get_matrix(points, departure_at)
        matrix_index = {task.id: index + 1 for index, task in enumerate(all_tasks)}
        shift = next(
            (item for item in snapshot.input_data.shifts if item.id == str(cycle.driver_shift_id)),
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
            start_at=start_at or cycle.planned_start,
            sequence=cycle.sequence,
            deliveries=deliveries,
            pickups=pickups,
            warehouse=snapshot.input_data.warehouse,
            vehicle=vehicle,
            matrix=matrix,
            matrix_index=matrix_index,
            settings=snapshot.settings,
            cycle_count=max(0, cycle.sequence - 1),
            existing_shift_cycles=(
                existing_shift_cycles
                if existing_shift_cycles is not None
                else tuple(
                    self._core_cycle(item, snapshot)
                    for item in cycle.route_plan.cycles
                    if item.id != cycle.id and item.driver_shift_id == cycle.driver_shift_id
                )
            ),
            ordered_tasks=tuple(tasks),
        )
        if candidate is None:
            raise ApiError(
                422,
                "MANUAL_CHANGE_INVALID",
                "The edited cycle violates capacity, detour, window, or shift limits",
                extra={
                    "errors": [
                        {
                            "code": "NO_FEASIBLE_CYCLE",
                            "message_ru": (
                                "Изменённый рейс не помещается во вместимость, окно, "
                                "лимит крюка или смену."
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
            manually_changed=(
                True if mark_manually_changed else cycle.manually_changed
            ),
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
        """Replace one cycle schedule while retaining its UUID and task locks."""

        locked_task_ids = frozenset(
            stop.task_id for stop in cycle.stops if stop.task_id is not None and stop.locked
        )

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
        cycle.manually_changed = core_cycle.manually_changed
        cycle.metrics = self._cycle_metrics(core_cycle, snapshot)
        await self._populate_cycle(
            session,
            cycle,
            core_cycle,
            snapshot,
            locked_task_ids=locked_task_ids,
        )
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
            stop for cycle in plan.cycles for stop in cycle.stops if stop.task_id == task_id
        ]
        if not matching_stops:
            raise not_found("assigned_planning_task", task_id)
        previous: dict[str, object] = {"locked": all(stop.locked for stop in matching_stops)}
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
        """Persist an explicit unavailable-driver warehouse as an audited plan warning."""

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
            calculated = asdict(metrics)
            metadata = {
                key: value for key, value in plan.metrics.items() if key not in calculated
            }
            plan.metrics = {**calculated, **metadata}

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
