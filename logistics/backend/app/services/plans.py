"""Saved-plan persistence, optimistic concurrency, and planner-facing facade."""

from __future__ import annotations

from typing import Protocol
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.db import utc_now
from app.errors import ApiError, not_found
from app.geo import geometry_to_geojson
from app.models import (
    ManualChangeAudit,
    OptimizationRun,
    OptimizationTraceEvent,
    RouteCycle,
    RouteExplanation,
    RoutePlan,
    RouteSegment,
    RouteStop,
    UnassignedTask,
)
from app.models.domain import OptimizationStatus, PlanStatus
from app.repositories import get_required
from app.schemas.domain import (
    CyclePatch,
    GeneratePlanRequest,
    ManualChangeRequest,
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
    statement = (
        select(RoutePlan)
        .where(RoutePlan.id == plan_id)
        .options(
            cycles.selectinload(RouteCycle.stops),
            cycles.selectinload(RouteCycle.segments),
            cycles.selectinload(RouteCycle.explanations),
            selectinload(RoutePlan.unassigned_tasks),
        )
    )
    if for_update:
        statement = statement.with_for_update()
    plan = await session.scalar(statement)
    if plan is None:
        raise not_found("plan", plan_id)
    return plan


def _segment_read(segment: RouteSegment) -> RouteSegmentRead:
    """Serialize a persisted spatial segment as GeoJSON."""

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
    plan.status = PlanStatus.CONFIRMED
    plan.version += 1
    await session.flush()
    return await get_plan(session, plan.id)


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
