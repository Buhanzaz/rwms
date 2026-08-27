"""Saved-plan persistence, optimistic concurrency, and planner-facing facade."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol
from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.db import utc_now
from app.errors import ApiError, not_found
from app.geo import geometry_to_geojson
from app.models import (
    Driver,
    DriverShift,
    LogisticsRequest,
    ManualChangeAudit,
    OptimizationRun,
    OptimizationTraceEvent,
    PlanningTask,
    PlanNotificationLog,
    RouteCycle,
    RouteExplanation,
    RoutePlan,
    RouteSegment,
    RouteStop,
    UnassignedTask,
    Vehicle,
)
from app.models.domain import OptimizationStatus, PlanStatus
from app.repositories import get_required
from app.schemas.domain import (
    CyclePatch,
    GeneratePlanRequest,
    ManualChangeRequest,
    PlanNotificationLogRead,
    RouteCycleRead,
    RoutePlanRead,
    RouteSegmentRead,
    RouteStopRead,
    UnassignedTaskRead,
)


class PlannerFacade(Protocol):
    """Integration boundary implemented by the independently testable planner lane."""

    async def generate_plan(
        self,
        session: AsyncSession,
        scenario_id: UUID,
        command: GeneratePlanRequest,
    ) -> OptimizationRun:
        """Start plan generation and persist an independently addressable run."""

    async def validate_plan(
        self, session: AsyncSession, plan_id: UUID, expected_version: int
    ) -> RoutePlan:
        """Fully validate a saved plan without accepting stale input."""

    async def reoptimize_plan(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeRequest,
    ) -> OptimizationRun:
        """Start optimization of unlocked remaining work."""

    async def apply_cycle_change(
        self,
        session: AsyncSession,
        plan_id: UUID,
        cycle_id: UUID,
        command: CyclePatch,
    ) -> RoutePlan:
        """Apply and fully validate an explicit route-cycle edit."""

    async def apply_manual_change(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeRequest,
    ) -> RoutePlan:
        """Apply and fully validate a drag-and-drop or structural plan edit."""

    async def apply_simulation_delay(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeRequest,
    ) -> RoutePlan:
        """Persist or derive a non-destructive delay override result."""

    async def replan_simulation(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeRequest,
    ) -> OptimizationRun:
        """Replan only unfinished, unlocked work from fixed simulation state."""


@dataclass(slots=True)
class _NotificationGroup:
    """Mutable confirmation-time aggregation for one assigned source request."""

    request: LogisticsRequest
    visits: list[tuple[RouteStop, Driver, Vehicle, int]] = field(default_factory=list)
    task_ids: set[UUID] = field(default_factory=set)
    assigned_quantity: int = 0


class UnavailablePlannerFacade:
    """Honest default that rejects planner commands until the engine is wired."""

    @staticmethod
    def _unavailable() -> ApiError:
        """Return the stable integration-not-ready error."""

        return ApiError(
            503,
            "PLANNER_NOT_CONFIGURED",
            "The planner engine has not been connected to the persistence facade",
        )

    async def generate_plan(
        self,
        session: AsyncSession,
        scenario_id: UUID,
        command: GeneratePlanRequest,
    ) -> OptimizationRun:
        """Reject generation instead of returning fabricated plan data."""

        raise self._unavailable()

    async def validate_plan(
        self, session: AsyncSession, plan_id: UUID, expected_version: int
    ) -> RoutePlan:
        """Reject validation until the real validator is attached."""

        raise self._unavailable()

    async def reoptimize_plan(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeRequest,
    ) -> OptimizationRun:
        """Reject reoptimization until the real planner is attached."""

        raise self._unavailable()

    async def apply_cycle_change(
        self,
        session: AsyncSession,
        plan_id: UUID,
        cycle_id: UUID,
        command: CyclePatch,
    ) -> RoutePlan:
        """Reject semantic route edits until full validation is attached."""

        raise self._unavailable()

    async def apply_manual_change(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeRequest,
    ) -> RoutePlan:
        """Reject manual mutation until full validation is attached."""

        raise self._unavailable()

    async def apply_simulation_delay(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeRequest,
    ) -> RoutePlan:
        """Reject delay commands until the simulator is attached."""

        raise self._unavailable()

    async def replan_simulation(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeRequest,
    ) -> OptimizationRun:
        """Reject remaining-day replanning until the simulator is attached."""

        raise self._unavailable()


async def get_plan(session: AsyncSession, plan_id: UUID, *, for_update: bool = False) -> RoutePlan:
    """Load a complete saved plan graph, optionally locking its version row."""

    cycles = selectinload(RoutePlan.cycles)
    stops = cycles.selectinload(RouteCycle.stops)
    statement = (
        select(RoutePlan)
        .where(RoutePlan.id == plan_id)
        .options(
            stops.selectinload(RouteStop.task)
            .selectinload(PlanningTask.request)
            .selectinload(LogisticsRequest.date_options),
            cycles.selectinload(RouteCycle.segments),
            cycles.selectinload(RouteCycle.explanations),
            cycles.selectinload(RouteCycle.driver_shift).selectinload(DriverShift.driver),
            cycles.selectinload(RouteCycle.driver_shift).selectinload(DriverShift.vehicle),
            selectinload(RoutePlan.scenario),
            selectinload(RoutePlan.unassigned_tasks),
            selectinload(RoutePlan.notification_logs),
        )
    )
    if for_update:
        statement = statement.with_for_update()
    plan = await session.scalar(statement)
    if plan is None:
        raise not_found("plan", plan_id)
    return plan


def _segment_read(segment: RouteSegment) -> RouteSegmentRead:
    """Serialize geometry and the immutable truck-routing audit snapshot."""

    return RouteSegmentRead(
        id=segment.id,
        sequence=segment.sequence,
        from_stop_id=segment.from_stop_id,
        to_stop_id=segment.to_stop_id,
        departure_at=segment.departure_at,
        arrival_at=segment.arrival_at,
        distance_meters=segment.distance_meters,
        travel_seconds=segment.travel_seconds,
        geometry=geometry_to_geojson(segment.geometry),
        routing_profile_snapshot=(
            dict(segment.routing_profile_snapshot)
            if segment.routing_profile_snapshot is not None
            else None
        ),
        routing_provider=segment.routing_provider,
        osm_data_version=segment.osm_data_version,
        routed_at=segment.routed_at,
    )


def plan_read(plan: RoutePlan) -> RoutePlanRead:
    """Build the complete transport model without triggering async lazy loads."""

    return RoutePlanRead(
        id=plan.id,
        scenario_id=plan.scenario_id,
        warehouse_id=plan.warehouse_id,
        date=plan.date,
        name=plan.name,
        version=plan.version,
        status=plan.status,
        score=plan.score,
        metrics=plan.metrics,
        validation_errors=plan.validation_errors,
        validation_warnings=plan.validation_warnings,
        manually_changed=plan.manually_changed,
        created_at=plan.created_at,
        updated_at=plan.updated_at,
        cycles=[
            RouteCycleRead(
                id=cycle.id,
                driver_shift_id=cycle.driver_shift_id,
                sequence=cycle.sequence,
                planned_start=cycle.planned_start,
                planned_finish=cycle.planned_finish,
                total_distance_meters=cycle.total_distance_meters,
                total_travel_seconds=cycle.total_travel_seconds,
                total_service_seconds=cycle.total_service_seconds,
                empty_distance_meters=cycle.empty_distance_meters,
                detour_seconds=cycle.detour_seconds,
                score=cycle.score,
                locked=cycle.locked,
                manually_changed=cycle.manually_changed,
                metrics=cycle.metrics,
                stops=[RouteStopRead.model_validate(stop) for stop in cycle.stops],
                segments=[_segment_read(segment) for segment in cycle.segments],
                explanations=[
                    {
                        "explanation_type": explanation.explanation_type,
                        "summary_ru": explanation.summary_ru,
                        "facts": explanation.facts,
                    }
                    for explanation in cycle.explanations
                ],
            )
            for cycle in plan.cycles
        ],
        unassigned_tasks=[
            UnassignedTaskRead.model_validate(item) for item in plan.unassigned_tasks
        ],
        notification_logs=[
            PlanNotificationLogRead.model_validate(item) for item in plan.notification_logs
        ],
    )


async def assert_plan_version(
    session: AsyncSession, plan_id: UUID, expected_version: int
) -> RoutePlan:
    """Lock the plan row and reject stale mutable commands with a stable 409."""

    plan = await get_plan(session, plan_id, for_update=True)
    if plan.version != expected_version:
        raise ApiError(
            409,
            "PLAN_VERSION_CONFLICT",
            "The route plan changed after it was loaded",
            extra={"expected_version": expected_version, "actual_version": plan.version},
        )
    return plan


async def record_manual_change(
    session: AsyncSession,
    plan: RoutePlan,
    command: ManualChangeRequest,
    *,
    previous_value: dict[str, object] | None,
    new_value: dict[str, object] | None,
) -> ManualChangeAudit:
    """Advance a locked plan version and append its immutable manual audit row."""

    version_before = plan.version
    plan.version += 1
    plan.manually_changed = True
    audit = ManualChangeAudit(
        route_plan_id=plan.id,
        changed_by=command.changed_by,
        changed_at=utc_now(),
        change_type=command.change_type,
        previous_value=previous_value,
        new_value=new_value,
        reason=command.reason,
        plan_version_before=version_before,
        plan_version_after=plan.version,
    )
    session.add(audit)
    await session.flush()
    return audit


async def confirm_plan(
    session: AsyncSession,
    plan_id: UUID,
    expected_version: int,
    *,
    accept_warnings: bool,
) -> RoutePlan:
    """Confirm an error-free plan after optimistic concurrency and warning consent."""

    plan = await assert_plan_version(session, plan_id, expected_version)
    if plan.validation_errors:
        raise ApiError(
            409,
            "PLAN_HAS_VALIDATION_ERRORS",
            "A plan with validation errors cannot be confirmed",
            extra={"errors": plan.validation_errors},
        )
    if plan.validation_warnings and not accept_warnings:
        raise ApiError(
            409,
            "PLAN_WARNINGS_REQUIRE_CONFIRMATION",
            "Explicitly accept warnings before confirming this plan",
            extra={"warnings": plan.validation_warnings},
        )
    if plan.status == PlanStatus.CONFIRMED:
        return plan
    await _create_simulated_notification_logs(session, plan)
    plan.status = PlanStatus.CONFIRMED
    plan.version += 1
    await session.flush()
    return await get_plan(session, plan.id)


def _vehicle_label(vehicle: Vehicle) -> str:
    """Render manufacturer and model with the operational name as a fallback."""

    manufacturer = str(getattr(vehicle, "manufacturer", "") or "").strip()
    model = str(getattr(vehicle, "model", "") or "").strip()
    physical_label = " ".join(part for part in (manufacturer, model) if part)
    return physical_label or str(getattr(vehicle, "name", "Автомобиль"))


def _notification_message(
    plan: RoutePlan,
    request: LogisticsRequest,
    visits: list[tuple[RouteStop, Driver, Vehicle, int]],
    assigned_quantity: int,
) -> str:
    """Build one Russian contact message aggregating every split-request visit."""

    operation = "Доставка" if request.type == "DELIVERY" else "Вывоз"
    planned_word = "запланирована" if request.type == "DELIVERY" else "запланирован"
    greeting = f"{request.contact_name}, " if request.contact_name.strip() else ""
    scenario_zone = ZoneInfo(plan.scenario.timezone)
    lines = [
        f"{greeting}{operation} {assigned_quantity} БК {planned_word} "
        f"на {plan.date.strftime('%d.%m.%Y')}.",
    ]
    option = next((item for item in request.date_options if item.date == plan.date), None)
    if option is not None and option.window_start is not None and option.window_end is not None:
        lines.append(
            f"Согласованное окно: {option.window_start.strftime('%H:%M')}-"
            f"{option.window_end.strftime('%H:%M')}."
        )
    seen_assignments: set[tuple[UUID, UUID, int, str]] = set()
    for stop, driver, vehicle, cycle_sequence in visits:
        assignment_key = (driver.id, vehicle.id, cycle_sequence, stop.planned_arrival.isoformat())
        if assignment_key in seen_assignments:
            continue
        seen_assignments.add(assignment_key)
        local_arrival = stop.planned_arrival.astimezone(scenario_zone)
        lines.append(
            f"Рейс {cycle_sequence}, прибытие ориентировочно "
            f"{local_arrival.strftime('%H:%M')}: водитель {driver.name}; "
            f"автомобиль {_vehicle_label(vehicle)}; госномер {vehicle.registration_number}."
        )
    if request.include_driver_passport_in_notification:
        seen_drivers: set[UUID] = set()
        for _, driver, _, _ in visits:
            if driver.id in seen_drivers:
                continue
            seen_drivers.add(driver.id)
            lines.append(f"Паспортные данные водителя {driver.name}: {driver.passport_details}.")
    return "\n".join(lines)


async def _create_simulated_notification_logs(
    session: AsyncSession,
    plan: RoutePlan,
) -> None:
    """Append one idempotent simulated delivery record per assigned source request."""

    grouped: dict[UUID, _NotificationGroup] = {}
    for cycle in sorted(plan.cycles, key=lambda item: (item.planned_start, item.sequence)):
        shift = cycle.driver_shift
        for stop in sorted(cycle.stops, key=lambda item: item.sequence):
            if stop.task is None:
                continue
            request = stop.task.request
            group = grouped.setdefault(
                request.id,
                _NotificationGroup(request=request),
            )
            group.visits.append((stop, shift.driver, shift.vehicle, cycle.sequence))
            if stop.task.id not in group.task_ids:
                group.task_ids.add(stop.task.id)
                group.assigned_quantity += stop.task.quantity

    missing_passports: list[dict[str, object]] = []
    for group in grouped.values():
        request = group.request
        if not request.include_driver_passport_in_notification:
            continue
        missing_names = sorted(
            {driver.name for _, driver, _, _ in group.visits if not driver.passport_details.strip()}
        )
        if missing_names:
            missing_passports.append(
                {
                    "request_id": str(request.id),
                    "request_name": request.name,
                    "drivers": missing_names,
                }
            )
    if missing_passports:
        raise ApiError(
            422,
            "DRIVER_PASSPORT_REQUIRED",
            "Fill in passport details for every driver included in a client notification",
            extra={"requests": missing_passports},
        )

    existing_request_ids = {item.request_id for item in plan.notification_logs}
    for request_id, group in sorted(grouped.items(), key=lambda item: str(item[0])):
        if request_id in existing_request_ids:
            continue
        request = group.request
        session.add(
            PlanNotificationLog(
                plan_id=plan.id,
                request_id=request_id,
                recipient_name=request.contact_name,
                recipient_contact=request.contact_phone,
                message=_notification_message(
                    plan,
                    request,
                    group.visits,
                    group.assigned_quantity,
                ),
                includes_driver_passport=(request.include_driver_passport_in_notification),
                status="SIMULATED_DELIVERED",
            )
        )
    await session.flush()
    session.expire(plan, ["notification_logs"])


async def clone_plan(session: AsyncSession, plan_id: UUID, *, name: str | None = None) -> RoutePlan:
    """Clone a loaded plan graph while retaining source tasks and shift assignments."""

    source = await get_plan(session, plan_id)
    clone = RoutePlan(
        scenario_id=source.scenario_id,
        warehouse_id=source.warehouse_id,
        date=source.date,
        name=name or f"{source.name} — копия",
        version=1,
        status=PlanStatus.DRAFT,
        score=source.score,
        metrics=dict(source.metrics),
        validation_errors=list(source.validation_errors),
        validation_warnings=list(source.validation_warnings),
        manually_changed=False,
    )
    session.add(clone)
    await session.flush()
    for source_cycle in source.cycles:
        cycle = RouteCycle(
            route_plan_id=clone.id,
            driver_shift_id=source_cycle.driver_shift_id,
            sequence=source_cycle.sequence,
            planned_start=source_cycle.planned_start,
            planned_finish=source_cycle.planned_finish,
            total_distance_meters=source_cycle.total_distance_meters,
            total_travel_seconds=source_cycle.total_travel_seconds,
            total_service_seconds=source_cycle.total_service_seconds,
            empty_distance_meters=source_cycle.empty_distance_meters,
            detour_seconds=source_cycle.detour_seconds,
            score=source_cycle.score,
            locked=source_cycle.locked,
            manually_changed=False,
            metrics=dict(source_cycle.metrics),
        )
        session.add(cycle)
        await session.flush()
        stop_ids: dict[UUID, UUID] = {}
        for source_stop in source_cycle.stops:
            stop = RouteStop(
                route_cycle_id=cycle.id,
                sequence=source_stop.sequence,
                task_id=source_stop.task_id,
                stop_type=source_stop.stop_type,
                planned_arrival=source_stop.planned_arrival,
                planned_departure=source_stop.planned_departure,
                service_seconds=source_stop.service_seconds,
                quantity_delta=source_stop.quantity_delta,
                load_before=source_stop.load_before,
                load_after=source_stop.load_after,
                latitude=source_stop.latitude,
                longitude=source_stop.longitude,
                warnings=list(source_stop.warnings),
                locked=source_stop.locked,
            )
            session.add(stop)
            await session.flush()
            stop_ids[source_stop.id] = stop.id
        for source_segment in source_cycle.segments:
            session.add(
                RouteSegment(
                    route_cycle_id=cycle.id,
                    sequence=source_segment.sequence,
                    from_stop_id=stop_ids[source_segment.from_stop_id],
                    to_stop_id=stop_ids[source_segment.to_stop_id],
                    departure_at=source_segment.departure_at,
                    arrival_at=source_segment.arrival_at,
                    distance_meters=source_segment.distance_meters,
                    travel_seconds=source_segment.travel_seconds,
                    geometry=source_segment.geometry,
                    routing_profile_snapshot=(
                        dict(source_segment.routing_profile_snapshot)
                        if source_segment.routing_profile_snapshot is not None
                        else None
                    ),
                    routing_provider=source_segment.routing_provider,
                    osm_data_version=source_segment.osm_data_version,
                    routed_at=source_segment.routed_at,
                )
            )
        for source_explanation in source_cycle.explanations:
            session.add(
                RouteExplanation(
                    route_cycle_id=cycle.id,
                    explanation_type=source_explanation.explanation_type,
                    summary_ru=source_explanation.summary_ru,
                    facts=list(source_explanation.facts),
                )
            )
    for item in source.unassigned_tasks:
        session.add(
            UnassignedTask(
                route_plan_id=clone.id,
                task_id=item.task_id,
                reason_codes=list(item.reason_codes),
                descriptions_ru=list(item.descriptions_ru),
                nearest_option=dict(item.nearest_option) if item.nearest_option else None,
                recommendation_ru=item.recommendation_ru,
            )
        )
    await session.flush()
    return await get_plan(session, clone.id)


async def get_optimization_run(session: AsyncSession, run_id: UUID) -> OptimizationRun:
    """Load one optimizer execution by UUID."""

    return await get_required(session, OptimizationRun, run_id, "optimization_run")


async def list_trace_events(
    session: AsyncSession, run_id: UUID, *, after_sequence: int = 0, limit: int = 500
) -> list[OptimizationTraceEvent]:
    """Read a bounded ordered page of real optimizer trace events."""

    await get_optimization_run(session, run_id)
    return list(
        await session.scalars(
            select(OptimizationTraceEvent)
            .where(
                OptimizationTraceEvent.optimization_run_id == run_id,
                OptimizationTraceEvent.sequence > after_sequence,
            )
            .order_by(OptimizationTraceEvent.sequence)
            .limit(limit)
        )
    )


async def request_run_cancel(session: AsyncSession, run_id: UUID) -> OptimizationRun:
    """Set a cooperative cancellation flag without fabricating terminal completion."""

    run = await get_optimization_run(session, run_id)
    terminal = {
        OptimizationStatus.COMPLETED,
        OptimizationStatus.CANCELLED,
        OptimizationStatus.FAILED,
        OptimizationStatus.TIMED_OUT,
    }
    if run.status not in terminal:
        run.cancel_requested = True
        await session.flush()
    return run
