"""Saved-plan persistence, optimistic concurrency, and planner-facing facade."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime
from typing import Protocol
from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy import select, update
from sqlalchemy.exc import DBAPIError
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
    RoutePlan,
    RouteSegment,
    RouteStop,
    Trailer,
    UnassignedTask,
    Vehicle,
    Warehouse,
)
from app.models.domain import OptimizationStatus, PlanStatus, RequestStatus, TaskStatus
from app.repositories import get_required
from app.schemas.domain import (
    CyclePatch,
    GeneratePlanRequest,
    ManualChangeCommand,
    PlanNotificationLogRead,
    RouteCycleRead,
    RoutePlanRead,
    RouteSegmentRead,
    RouteStopRead,
    UnassignedTaskRead,
)
from app.services.dynamic_manual_history import append_manual_change_history
from app.services.request_reschedule_fence import (
    fence_plan_request_reschedules as fence_plan_request_reschedules,
)
from app.services.request_reschedule_fence import reject_active_request_reschedules

PENDING_REQUEST_REFRESH_METRIC = "pending_request_metadata_refresh"
MUTABLE_PLAN_STATUSES = (
    PlanStatus.DRAFT,
    PlanStatus.GENERATED,
    PlanStatus.VALIDATED,
)


class PlannerFacade(Protocol):
    """Integration boundary implemented by the independently testable planner lane."""

    async def generate_plan(
        self,
        session: AsyncSession,
        warehouse_id: UUID,
        command: GeneratePlanRequest,
    ) -> OptimizationRun:
        """Start plan generation and persist an independently addressable run."""

    async def validate_plan(
        self, session: AsyncSession, plan_id: UUID, expected_version: int
    ) -> RoutePlan:
        """Fully validate a saved plan without accepting stale input."""

    async def validate_confirmation(
        self, session: AsyncSession, plan: RoutePlan, *, accept_warnings: bool,
    ) -> None:
        """Check current resources and route evidence inside the confirmation fence."""

    async def refresh_plan_after_request_changes(
        self,
        session: AsyncSession,
        plan_id: UUID,
        expected_version: int,
    ) -> RoutePlan:
        """Recalculate a marked draft without changing its task or cycle order."""

    async def reoptimize_plan(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeCommand,
    ) -> OptimizationRun:
        """Start optimization of unlocked remaining work."""

    async def apply_cycle_change(
        self,
        session: AsyncSession,
        plan_id: UUID,
        cycle_id: UUID,
        command: CyclePatch,
        changed_by: str,
    ) -> RoutePlan:
        """Apply and fully validate an explicit route-cycle edit."""

    async def apply_manual_change(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeCommand,
    ) -> RoutePlan:
        """Apply and fully validate a drag-and-drop or structural plan edit."""

    async def reset_manual_changes(
        self,
        session: AsyncSession,
        plan_id: UUID,
        expected_version: int,
    ) -> RoutePlan:
        """Archive a mutable manual plan and return its automatic replacement."""

    async def apply_simulation_delay(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeCommand,
    ) -> RoutePlanRead:
        """Persist or derive a non-destructive delay override result."""

    async def replan_simulation(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeCommand,
    ) -> OptimizationRun:
        """Replan only unfinished, unlocked work from fixed simulation state."""

    async def reoptimize_recovery(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeCommand,
    ) -> OptimizationRun:
        """Create a recovery revision while preserving the confirmed source as history."""

    async def preview_feasible_request_dates(
        self,
        session: AsyncSession,
        request_id: UUID,
        candidate_dates: tuple[date, ...],
    ) -> tuple[date, ...]:
        """Check candidate dates through the production routing and optimizer without writes."""

    async def preview_delay_task_etas(
        self,
        session: AsyncSession,
        plan_id: UUID,
        vehicle_id: UUID,
        effective_at: datetime,
        delay_minutes: int,
    ) -> dict[UUID, datetime]:
        """Propagate one delay through future stops without mutating the source plan."""


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
        warehouse_id: UUID,
        command: GeneratePlanRequest,
    ) -> OptimizationRun:
        """Reject generation instead of returning fabricated plan data."""

        raise self._unavailable()

    async def validate_plan(
        self, session: AsyncSession, plan_id: UUID, expected_version: int
    ) -> RoutePlan:
        """Reject validation until the real validator is attached."""

        raise self._unavailable()

    async def validate_confirmation(
        self, session: AsyncSession, plan: RoutePlan, *, accept_warnings: bool,
    ) -> None:
        """Never confirm a draft without a configured current-facts validator."""

        raise self._unavailable()

    async def refresh_plan_after_request_changes(
        self,
        session: AsyncSession,
        plan_id: UUID,
        expected_version: int,
    ) -> RoutePlan:
        """Reject metadata refresh until the real planner is attached."""

        raise self._unavailable()

    async def reoptimize_plan(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeCommand,
    ) -> OptimizationRun:
        """Reject reoptimization until the real planner is attached."""

        raise self._unavailable()

    async def apply_cycle_change(
        self,
        session: AsyncSession,
        plan_id: UUID,
        cycle_id: UUID,
        command: CyclePatch,
        changed_by: str,
    ) -> RoutePlan:
        """Reject semantic route edits until full validation is attached."""

        raise self._unavailable()

    async def apply_manual_change(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeCommand,
    ) -> RoutePlan:
        """Reject manual mutation until full validation is attached."""

        raise self._unavailable()

    async def reset_manual_changes(
        self,
        session: AsyncSession,
        plan_id: UUID,
        expected_version: int,
    ) -> RoutePlan:
        """Reject manual reset until the real planner is attached."""

        raise self._unavailable()

    async def apply_simulation_delay(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeCommand,
    ) -> RoutePlanRead:
        """Reject delay commands until the planning engine is attached."""

        raise self._unavailable()

    async def replan_simulation(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeCommand,
    ) -> OptimizationRun:
        """Reject remaining-day replanning until the planning engine is attached."""

        raise self._unavailable()

    async def reoptimize_recovery(
        self,
        session: AsyncSession,
        plan_id: UUID,
        command: ManualChangeCommand,
    ) -> OptimizationRun:
        """Reject operational recovery until the real planner is attached."""

        raise self._unavailable()

    async def preview_feasible_request_dates(
        self,
        session: AsyncSession,
        request_id: UUID,
        candidate_dates: tuple[date, ...],
    ) -> tuple[date, ...]:
        """Reject recovery-date previews until the real planner is attached."""

        raise self._unavailable()

    async def preview_delay_task_etas(
        self,
        session: AsyncSession,
        plan_id: UUID,
        vehicle_id: UUID,
        effective_at: datetime,
        delay_minutes: int,
    ) -> dict[UUID, datetime]:
        """Reject delay propagation until the real planner is attached."""

        raise self._unavailable()


async def get_plan(
    session: AsyncSession,
    plan_id: UUID,
    *,
    for_update: bool = False,
    populate_existing: bool = False,
    nowait: bool = False,
) -> RoutePlan:
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
            selectinload(RoutePlan.warehouse),
            selectinload(RoutePlan.unassigned_tasks)
            .selectinload(UnassignedTask.task)
            .selectinload(PlanningTask.request),
            selectinload(RoutePlan.notification_logs),
        )
    )
    if populate_existing:
        statement = statement.execution_options(populate_existing=True)
    if for_update:
        statement = statement.with_for_update(nowait=nowait)
    plan = await session.scalar(statement)
    if plan is None:
        raise not_found("plan", plan_id)
    return plan


async def get_latest_plan_for_date(
    session: AsyncSession,
    warehouse_id: UUID,
    planning_date: date,
    *,
    for_update: bool = False,
) -> RoutePlan | None:
    """Load the newest non-archived plan for one warehouse day and its full graph."""

    plan_id = await session.scalar(
        select(RoutePlan.id)
        .where(
            RoutePlan.warehouse_id == warehouse_id,
            RoutePlan.date == planning_date,
            RoutePlan.status != PlanStatus.ARCHIVED,
        )
        .order_by(RoutePlan.updated_at.desc(), RoutePlan.id.desc())
        .limit(1)
    )
    return await get_plan(session, plan_id, for_update=for_update) if plan_id is not None else None


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
        warehouse_id=plan.warehouse_id,
        supersedes_plan_id=plan.supersedes_plan_id,
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
    session: AsyncSession, plan_id: UUID, expected_version: int, *, nowait: bool = False,
) -> RoutePlan:
    """Lock the plan row and reject stale mutable commands with a stable 409."""

    plan = await get_plan(session, plan_id, for_update=True, nowait=nowait)
    if plan.version != expected_version:
        raise ApiError(
            409,
            "PLAN_VERSION_CONFLICT",
            "The route plan changed after it was loaded",
            extra={"expected_version": expected_version, "actual_version": plan.version},
        )
    await fence_plan_request_reschedules(session, plan.id)
    return plan


async def archive_mutable_plans_for_dates(
    session: AsyncSession,
    warehouse_id: UUID,
    dates: set[date],
    *,
    exclude_request_reschedule_hold_id: UUID | None = None,
) -> tuple[UUID, ...]:
    """Archive recomputable plan heads while preserving confirmed and historical revisions."""

    if not dates:
        return ()
    plan_ids = tuple(
        await session.scalars(
            select(RoutePlan.id)
            .where(
                RoutePlan.warehouse_id == warehouse_id,
                RoutePlan.date.in_(dates),
                RoutePlan.status.in_(MUTABLE_PLAN_STATUSES),
            )
            .order_by(RoutePlan.id)
            .with_for_update()
        )
    )
    for plan_id in plan_ids:
        await fence_plan_request_reschedules(
            session,
            plan_id,
            exclude_hold_id=exclude_request_reschedule_hold_id,
        )
    if not plan_ids:
        return ()
    result = await session.execute(
        update(RoutePlan)
        .where(
            RoutePlan.id.in_(plan_ids),
        )
        .values(status=PlanStatus.ARCHIVED, version=RoutePlan.version + 1)
        .returning(RoutePlan.id)
    )
    archived_ids = tuple(result.scalars())
    await session.flush()
    return archived_ids


async def archive_plan_head_for_replacement(
    session: AsyncSession,
    warehouse_id: UUID,
    planning_date: date,
    supersedes_plan_id: UUID | None,
    *,
    allow_confirmed: bool = False,
) -> RoutePlan | None:
    """Serialize an atomic active-head swap and archive only the expected mutable predecessor."""

    warehouse = await session.scalar(
        select(Warehouse).where(Warehouse.id == warehouse_id).with_for_update()
    )
    if warehouse is None:
        raise not_found("warehouse", warehouse_id)
    active = await session.scalar(
        select(RoutePlan)
        .where(
            RoutePlan.warehouse_id == warehouse_id,
            RoutePlan.date == planning_date,
            RoutePlan.status != PlanStatus.ARCHIVED,
        )
        .with_for_update()
    )
    if supersedes_plan_id is None:
        if active is not None:
            raise ApiError(
                409,
                "ACTIVE_PLAN_EXISTS",
                "Archive or refresh the active plan before generating another revision",
                extra={"plan_id": str(active.id)},
            )
        return None
    if active is None or active.id != supersedes_plan_id:
        raise ApiError(
            409,
            "PLAN_HEAD_CHANGED",
            "The active route-plan revision changed before the replacement was saved",
            extra={
                "expected_plan_id": str(supersedes_plan_id),
                "actual_plan_id": str(active.id) if active is not None else None,
            },
        )
    if active.status == PlanStatus.CONFIRMED and not allow_confirmed:
        raise ApiError(
            409,
            "PLAN_ALREADY_CONFIRMED",
            "A confirmed plan cannot be replaced",
        )
    await fence_plan_request_reschedules(session, active.id)
    active.status = PlanStatus.ARCHIVED
    active.version += 1
    await session.flush()
    return active


async def assert_plan_head_for_recovery_stage(
    session: AsyncSession,
    warehouse_id: UUID,
    planning_date: date,
    source_plan_id: UUID,
    expected_source_version: int,
) -> RoutePlan:
    """Lock and fence the still-active source before persisting a hidden recovery revision."""

    warehouse = await session.scalar(
        select(Warehouse).where(Warehouse.id == warehouse_id).with_for_update()
    )
    if warehouse is None:
        raise not_found("warehouse", warehouse_id)
    active = await session.scalar(
        select(RoutePlan)
        .where(
            RoutePlan.warehouse_id == warehouse_id,
            RoutePlan.date == planning_date,
            RoutePlan.status != PlanStatus.ARCHIVED,
        )
        .with_for_update()
    )
    if active is None or active.id != source_plan_id:
        raise ApiError(
            409,
            "PLAN_HEAD_CHANGED",
            "The active route-plan revision changed before recovery was staged",
            extra={
                "expected_plan_id": str(source_plan_id),
                "actual_plan_id": str(active.id) if active is not None else None,
            },
        )
    if active.version != expected_source_version:
        raise ApiError(
            409,
            "PLAN_VERSION_CONFLICT",
            "The source plan changed while recovery was being calculated",
            extra={
                "expected_version": expected_source_version,
                "actual_version": active.version,
            },
        )
    await fence_plan_request_reschedules(session, active.id)
    return active


async def activate_staged_recovery_plan(
    session: AsyncSession,
    *,
    source_plan_id: UUID,
    expected_source_version: int,
    result_plan_id: UUID,
    expected_result_version: int,
) -> RoutePlan:
    """Atomically expose one staged recovery after its owner-side effects have converged.

    A replay after the swap returns the already-active result. The method never restores an old
    source or displaces an unrelated active plan head.
    """

    identities = (
        await session.execute(
            select(
                RoutePlan.id,
                RoutePlan.warehouse_id,
                RoutePlan.date,
            ).where(RoutePlan.id.in_((source_plan_id, result_plan_id)))
        )
    ).all()
    identity_by_id = {row.id: row for row in identities}
    source_identity = identity_by_id.get(source_plan_id)
    result_identity = identity_by_id.get(result_plan_id)
    if source_identity is None:
        raise not_found("route_plan", source_plan_id)
    if result_identity is None:
        raise not_found("route_plan", result_plan_id)
    if (
        source_identity.warehouse_id != result_identity.warehouse_id
        or source_identity.date != result_identity.date
    ):
        raise ApiError(
            409,
            "RECOVERY_PLAN_SCOPE_CHANGED",
            "The staged recovery no longer belongs to the source planning day",
        )

    warehouse = await session.scalar(
        select(Warehouse).where(Warehouse.id == source_identity.warehouse_id).with_for_update()
    )
    if warehouse is None:
        raise not_found("warehouse", source_identity.warehouse_id)
    plans = tuple(
        await session.scalars(
            select(RoutePlan)
            .where(RoutePlan.id.in_((source_plan_id, result_plan_id)))
            .order_by(RoutePlan.id)
            .with_for_update()
        )
    )
    plan_by_id = {plan.id: plan for plan in plans}
    source = plan_by_id.get(source_plan_id)
    result = plan_by_id.get(result_plan_id)
    if source is None:
        raise not_found("route_plan", source_plan_id)
    if result is None:
        raise not_found("route_plan", result_plan_id)
    if result.supersedes_plan_id != source.id:
        raise ApiError(
            409,
            "RECOVERY_PLAN_LINEAGE_CHANGED",
            "The staged recovery no longer supersedes the expected source",
        )

    active = await session.scalar(
        select(RoutePlan)
        .where(
            RoutePlan.warehouse_id == source.warehouse_id,
            RoutePlan.date == source.date,
            RoutePlan.status != PlanStatus.ARCHIVED,
        )
        .with_for_update()
    )
    if active is not None and active.id == result.id:
        if source.status != PlanStatus.ARCHIVED:
            raise ApiError(
                409,
                "RECOVERY_PLAN_ACTIVATION_INCOMPLETE",
                "The recovery head is active while its source is not archived",
            )
        return result
    if active is None or active.id != source.id:
        raise ApiError(
            409,
            "PLAN_HEAD_CHANGED",
            "Another route-plan revision became active before recovery activation",
            extra={
                "expected_plan_id": str(source.id),
                "actual_plan_id": str(active.id) if active is not None else None,
            },
        )
    if source.version != expected_source_version:
        raise ApiError(
            409,
            "PLAN_VERSION_CONFLICT",
            "The source plan changed before recovery activation",
            extra={
                "expected_version": expected_source_version,
                "actual_version": source.version,
            },
        )
    if result.status != PlanStatus.ARCHIVED or result.version != expected_result_version:
        raise ApiError(
            409,
            "RECOVERY_PLAN_STAGE_CHANGED",
            "The prepared recovery revision changed before activation",
        )
    await fence_plan_request_reschedules(session, source.id)
    await fence_plan_request_reschedules(session, result.id)
    source.status = PlanStatus.ARCHIVED
    source.version += 1
    await session.flush((source,))
    result.status = PlanStatus.GENERATED
    result.version += 1
    await session.flush((result,))
    return result


async def _plan_assigned_task_ids(
    session: AsyncSession,
    plan_id: UUID,
) -> tuple[UUID, ...]:
    """Return stable task identities physically assigned to cycles in one plan."""

    return tuple(
        await session.scalars(
            select(RouteStop.task_id)
            .join(RouteCycle, RouteCycle.id == RouteStop.route_cycle_id)
            .where(
                RouteCycle.route_plan_id == plan_id,
                RouteStop.task_id.is_not(None),
            )
            .order_by(RouteStop.task_id)
        )
    )


async def _plan_referenced_request_ids(
    session: AsyncSession,
    plan_id: UUID,
) -> tuple[UUID, ...]:
    """Snapshot every request referenced by assigned or unassigned plan membership."""

    assigned = (
        select(PlanningTask.request_id)
        .join(RouteStop, RouteStop.task_id == PlanningTask.id)
        .join(RouteCycle, RouteCycle.id == RouteStop.route_cycle_id)
        .where(RouteCycle.route_plan_id == plan_id)
    )
    unassigned = (
        select(PlanningTask.request_id)
        .join(UnassignedTask, UnassignedTask.task_id == PlanningTask.id)
        .where(UnassignedTask.route_plan_id == plan_id)
    )
    return tuple(
        sorted(
            set(await session.scalars(assigned.union(unassigned))),
            key=str,
        )
    )


async def lock_plan_for_request_mutation(
    session: AsyncSession,
    plan_id: UUID,
) -> RoutePlan:
    """Lock referenced requests before their plan and reject an active owner-slot hold."""

    request_ids = await _plan_referenced_request_ids(session, plan_id)
    if request_ids:
        locked_request_ids = tuple(
            await session.scalars(
                select(LogisticsRequest.id)
                .where(LogisticsRequest.id.in_(request_ids))
                .order_by(LogisticsRequest.id)
                .with_for_update()
            )
        )
        if locked_request_ids != request_ids:
            raise ApiError(
                409,
                "PLAN_REQUEST_MISSING",
                "A request referenced by the plan no longer exists",
            )
    plan = await get_plan(
        session,
        plan_id,
        for_update=True,
        populate_existing=True,
    )
    if await _plan_referenced_request_ids(session, plan_id) != request_ids:
        raise ApiError(
            409,
            "PLAN_MEMBERSHIP_CHANGED",
            "The plan membership changed before its requests could be locked",
        )
    await fence_plan_request_reschedules(session, plan.id)
    return plan


async def _reserve_plan_requests(
    session: AsyncSession,
    plan: RoutePlan,
) -> None:
    """Atomically claim assigned flexible requests and invalidate competing draft revisions."""

    assigned_task_ids = await _plan_assigned_task_ids(session, plan.id)
    unassigned_task_ids = tuple(
        await session.scalars(
            select(UnassignedTask.task_id)
            .where(UnassignedTask.route_plan_id == plan.id)
            .order_by(UnassignedTask.task_id)
        )
    )
    referenced_task_ids = tuple(dict.fromkeys((*assigned_task_ids, *unassigned_task_ids)))
    if not referenced_task_ids:
        return
    referenced_request_ids = tuple(
        await session.scalars(
            select(PlanningTask.request_id)
            .where(PlanningTask.id.in_(referenced_task_ids))
            .distinct()
            .order_by(PlanningTask.request_id)
        )
    )
    requests = tuple(
        await session.scalars(
            select(LogisticsRequest)
            .where(LogisticsRequest.id.in_(referenced_request_ids))
            .order_by(LogisticsRequest.id)
            .with_for_update()
            .options(selectinload(LogisticsRequest.tasks))
        )
    )
    if len(requests) != len(referenced_request_ids):
        raise ApiError(
            409,
            "PLAN_REQUEST_MISSING",
            "A request assigned to the plan no longer exists",
        )
    await reject_active_request_reschedules(
        session,
        tuple(request.id for request in requests),
    )
    if not assigned_task_ids:
        return
    assigned_request_ids = tuple(
        await session.scalars(
            select(PlanningTask.request_id)
            .where(PlanningTask.id.in_(assigned_task_ids))
            .distinct()
            .order_by(PlanningTask.request_id)
        )
    )
    requests = tuple(request for request in requests if request.id in assigned_request_ids)

    assigned_plan_refs = (
        select(RoutePlan.id, RoutePlan.status)
        .join(RouteCycle, RouteCycle.route_plan_id == RoutePlan.id)
        .join(RouteStop, RouteStop.route_cycle_id == RouteCycle.id)
        .join(PlanningTask, PlanningTask.id == RouteStop.task_id)
        .where(
            PlanningTask.request_id.in_(assigned_request_ids),
            RoutePlan.id != plan.id,
            RoutePlan.status != PlanStatus.ARCHIVED,
        )
    )
    unassigned_plan_refs = (
        select(RoutePlan.id, RoutePlan.status)
        .join(UnassignedTask, UnassignedTask.route_plan_id == RoutePlan.id)
        .join(PlanningTask, PlanningTask.id == UnassignedTask.task_id)
        .where(
            PlanningTask.request_id.in_(assigned_request_ids),
            RoutePlan.id != plan.id,
            RoutePlan.status != PlanStatus.ARCHIVED,
        )
    )
    competing_rows = tuple(
        (await session.execute(assigned_plan_refs.union(unassigned_plan_refs))).all()
    )
    confirmed_conflicts = sorted(
        {row.id for row in competing_rows if row.status == PlanStatus.CONFIRMED},
        key=str,
    )
    if confirmed_conflicts:
        raise ApiError(
            409,
            "REQUEST_ALREADY_ALLOCATED",
            "A request in this plan is already allocated by another confirmed plan",
            extra={"plan_ids": [str(plan_id) for plan_id in confirmed_conflicts]},
        )
    mutable_competing_ids = {
        row.id for row in competing_rows if row.status in MUTABLE_PLAN_STATUSES
    }
    if mutable_competing_ids:
        locked_competing_ids = tuple(
            await session.scalars(
                select(RoutePlan.id)
                .where(RoutePlan.id.in_(mutable_competing_ids))
                .order_by(RoutePlan.id)
                .with_for_update()
            )
        )
        for competing_plan_id in locked_competing_ids:
            await fence_plan_request_reschedules(session, competing_plan_id)
        await session.execute(
            update(RoutePlan)
            .where(RoutePlan.id.in_(mutable_competing_ids))
            .values(status=PlanStatus.ARCHIVED, version=RoutePlan.version + 1)
        )

    assigned_set = set(assigned_task_ids)
    unassigned_set = {item.task_id for item in plan.unassigned_tasks}
    for request in requests:
        if request.status not in (RequestStatus.READY, RequestStatus.UNASSIGNED):
            raise ApiError(
                409,
                "REQUEST_NOT_AVAILABLE",
                "A request assigned to the plan is no longer available",
                extra={"request_id": str(request.id), "status": request.status},
            )
        request.scheduled_date = plan.date
        request.status = RequestStatus.PLANNED
        for task in request.tasks:
            if task.id in assigned_set:
                task.status = TaskStatus.PLANNED
            elif task.id in unassigned_set:
                task.status = TaskStatus.UNASSIGNED
    await session.flush()


async def mark_plans_for_request_refresh(
    session: AsyncSession,
    warehouse_id: UUID,
    dates: set[date],
    request_id: UUID,
) -> tuple[RoutePlan, ...]:
    """Fence active plans and record a server-owned request-metadata refresh marker."""

    if not dates:
        return ()
    plans = tuple(
        await session.scalars(
            select(RoutePlan)
            .where(
                RoutePlan.warehouse_id == warehouse_id,
                RoutePlan.date.in_(dates),
                RoutePlan.status != PlanStatus.ARCHIVED,
            )
            .order_by(RoutePlan.id)
            .with_for_update()
        )
    )
    if any(plan.status == PlanStatus.CONFIRMED for plan in plans):
        raise ApiError(
            409,
            "PLAN_ALREADY_CONFIRMED",
            "A confirmed plan cannot be changed by request planning metadata",
        )
    request_id_value = str(request_id)
    for plan in plans:
        await fence_plan_request_reschedules(session, plan.id)
        marker = plan.metrics.get(PENDING_REQUEST_REFRESH_METRIC)
        marked_request_ids: set[str] = set()
        if isinstance(marker, dict):
            marked_request_ids.update(
                value for value in marker.get("request_ids", []) if isinstance(value, str)
            )
        marked_request_ids.add(request_id_value)
        plan.version += 1
        plan.status = PlanStatus.DRAFT
        plan.metrics = {
            **plan.metrics,
            PENDING_REQUEST_REFRESH_METRIC: {
                "request_ids": sorted(marked_request_ids),
                "marked_at_version": plan.version,
            },
        }
    await session.flush()
    return plans


async def record_manual_change(
    session: AsyncSession,
    plan: RoutePlan,
    command: ManualChangeCommand,
    *,
    previous_value: dict[str, object] | None,
    new_value: dict[str, object] | None,
) -> ManualChangeAudit:
    """Advance a locked plan version and append its immutable manual audit row."""

    if plan.status == PlanStatus.CONFIRMED:
        raise ApiError(
            409,
            "PLAN_ALREADY_CONFIRMED",
            "A confirmed plan cannot be changed manually",
        )

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
    await append_manual_change_history(session, plan, audit, command)
    await session.flush()
    return audit


async def confirm_plan(
    session: AsyncSession,
    plan_id: UUID,
    expected_version: int,
    *,
    planner: PlannerFacade,
    accept_warnings: bool,
    empty_positioning_reason: str | None,
    confirmed_by: str,
) -> RoutePlan:
    """Confirm under current-facts locks, surfacing contention without hidden retries."""

    try:
        return await _confirm_plan_locked(
            session, plan_id, expected_version, planner=planner,
            accept_warnings=accept_warnings,
            empty_positioning_reason=empty_positioning_reason, confirmed_by=confirmed_by,
        )
    except DBAPIError as exc:
        if getattr(exc.orig, "sqlstate", None) != "55P03":
            raise
        # PostgreSQL has aborted this transaction. Let the request dependency roll
        # it back; never query or retry against that transaction here.
        raise ApiError(
            409, "PLAN_RESOURCES_BUSY",
            "Resources are being updated; reload the plan and retry confirmation",
        ) from exc


async def _confirm_plan_locked(
    session: AsyncSession,
    plan_id: UUID,
    expected_version: int,
    *,
    planner: PlannerFacade,
    accept_warnings: bool,
    empty_positioning_reason: str | None,
    confirmed_by: str,
) -> RoutePlan:
    """Confirm a valid plan and audit an explicitly accepted empty support leg."""

    plan = await lock_plan_execution_resources(session, plan_id, expected_version)
    return await _confirm_reserved_plan(
        session, plan, planner=planner, accept_warnings=accept_warnings,
        empty_positioning_reason=empty_positioning_reason, confirmed_by=confirmed_by,
    )


async def lock_plan_execution_resources(
    session: AsyncSession, plan_id: UUID, expected_version: int,
) -> RoutePlan:
    """Fence current local facts for confirmation or publication until transaction end.

    Every lock is NOWAIT because catalog and incident commands use different
    aggregate orders. Shift identities are locked before reading their current
    driver/vehicle references. Recheck the fresh plan and membership last so a
    concurrent edit cannot replace resources outside this fence.
    """

    try:
        return await _lock_plan_execution_resources(session, plan_id, expected_version)
    except DBAPIError as exc:
        if getattr(exc.orig, "sqlstate", None) != "55P03":
            raise
        raise ApiError(
            409, "PLAN_RESOURCES_BUSY",
            "Resources are being updated; reload the plan and retry",
        ) from exc


async def _lock_plan_execution_resources(
    session: AsyncSession, plan_id: UUID, expected_version: int,
) -> RoutePlan:
    """Acquire one local execution fence without retries or external mutations."""

    plan_identity = (
        await session.execute(
            select(RoutePlan.warehouse_id, RoutePlan.date).where(RoutePlan.id == plan_id)
        )
    ).one_or_none()
    if plan_identity is None:
        raise not_found("route_plan", plan_id)
    referenced_request_ids = await _plan_referenced_request_ids(session, plan_id)
    warehouse_ids = {plan_identity.warehouse_id}
    if referenced_request_ids:
        requests = tuple(
            await session.scalars(
                select(LogisticsRequest)
                .where(LogisticsRequest.id.in_(referenced_request_ids))
                .order_by(LogisticsRequest.id)
                .with_for_update(nowait=True)
                .execution_options(populate_existing=True)
            )
        )
        if tuple(request.id for request in requests) != referenced_request_ids:
            raise ApiError(
                409,
                "PLAN_REQUEST_MISSING",
                "A request referenced by the plan no longer exists",
            )
        warehouse_ids.update(request.warehouse_id for request in requests)
    shift_ids = tuple(sorted(set(await session.scalars(
        select(RouteCycle.driver_shift_id).where(RouteCycle.route_plan_id == plan_id)
    )), key=str))
    shifts = tuple(await session.scalars(
        select(DriverShift).where(DriverShift.id.in_(shift_ids))
        .order_by(DriverShift.id).with_for_update(nowait=True)
        .execution_options(populate_existing=True)
    ))
    if tuple(shift.id for shift in shifts) != shift_ids:
        raise ApiError(409, "PLAN_REFRESH_REQUIRED", "A planned driver shift no longer exists")
    warehouse_ids.update(shift.warehouse_id for shift in shifts)
    await session.scalars(
        select(Driver).where(Driver.id.in_({shift.driver_id for shift in shifts}))
        .order_by(Driver.id).with_for_update(nowait=True)
        .execution_options(populate_existing=True)
    )
    vehicles = tuple(await session.scalars(
        select(Vehicle).where(Vehicle.id.in_({shift.vehicle_id for shift in shifts}))
        .order_by(Vehicle.id).with_for_update(nowait=True)
        .execution_options(populate_existing=True)
    ))
    warehouse_ids.update(vehicle.warehouse_id for vehicle in vehicles)
    trailer_ids = [vehicle.default_trailer_id for vehicle in vehicles
                   if vehicle.default_trailer_id is not None]
    if trailer_ids:
        await session.scalars(
            select(Trailer).where(Trailer.id.in_(trailer_ids))
            .order_by(Trailer.id).with_for_update(nowait=True)
            .execution_options(populate_existing=True)
        )
    locked_warehouses = tuple(await session.scalars(
        select(Warehouse.id).where(Warehouse.id.in_(warehouse_ids))
        .order_by(Warehouse.id).with_for_update(nowait=True)
    ))
    if set(locked_warehouses) != warehouse_ids:
        raise not_found("warehouse", plan_identity.warehouse_id)
    plan = await get_plan(
        session, plan_id, for_update=True, populate_existing=True, nowait=True,
    )
    if plan.version != expected_version:
        raise ApiError(
            409, "PLAN_VERSION_CONFLICT", "The route plan changed after it was loaded",
            extra={"expected_version": expected_version, "actual_version": plan.version},
        )
    if (
        plan.warehouse_id != plan_identity.warehouse_id
        or plan.date != plan_identity.date
        or tuple(sorted({cycle.driver_shift_id for cycle in plan.cycles}, key=str)) != shift_ids
        or await _plan_referenced_request_ids(session, plan_id) != referenced_request_ids
    ):
        raise ApiError(
            409,
            "PLAN_MEMBERSHIP_CHANGED",
            "The plan membership changed before its requests could be reserved",
        )
    await fence_plan_request_reschedules(session, plan.id)
    return plan


async def _confirm_reserved_plan(
    session: AsyncSession, plan: RoutePlan, *, planner: PlannerFacade,
    accept_warnings: bool, empty_positioning_reason: str | None, confirmed_by: str,
) -> RoutePlan:
    """Reserve a validated draft while the caller holds its complete resource fence."""

    if PENDING_REQUEST_REFRESH_METRIC in plan.metrics:
        raise ApiError(
            409,
            "PLAN_REFRESH_REQUIRED",
            "Refresh routes after changing request planning metadata",
        )
    mandatory_unassigned = [item.task_id for item in plan.unassigned_tasks if item.task.mandatory]
    if mandatory_unassigned:
        raise ApiError(
            409,
            "MANDATORY_TASKS_UNASSIGNED",
            "Every mandatory delivery or pickup must be assigned before plan confirmation",
            extra={"task_ids": [str(task_id) for task_id in mandatory_unassigned]},
        )
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
    if plan.status not in MUTABLE_PLAN_STATUSES:
        raise ApiError(409, "PLAN_NOT_CONFIRMABLE", "Only a current draft can be confirmed")
    positioning_distance = plan.metrics.get("support_positioning_distance_meters", 0)
    empty_positioning = isinstance(positioning_distance, (int, float)) and positioning_distance > 0
    normalized_reason = (empty_positioning_reason or "").strip()
    if empty_positioning and not normalized_reason:
        raise ApiError(
            409,
            "EMPTY_POSITIONING_REASON_REQUIRED",
            "Confirming an empty cross-warehouse positioning leg requires a reason",
        )
    await planner.validate_confirmation(session, plan, accept_warnings=accept_warnings)
    # Refreshing catalog projections can expire inverse task/request relations.
    # Reload the complete command graph before reservation and notice creation.
    plan = await get_plan(session, plan.id)
    version_before = plan.version
    await _reserve_plan_requests(session, plan)
    await _create_simulated_notification_logs(session, plan)
    plan.status = PlanStatus.CONFIRMED
    plan.version += 1
    if empty_positioning:
        plan.metrics = {
            **plan.metrics,
            "empty_positioning_approved": True,
            "empty_positioning_approved_by": confirmed_by,
            "empty_positioning_reason": normalized_reason,
        }
        session.add(
            ManualChangeAudit(
                route_plan_id=plan.id,
                changed_by=confirmed_by,
                changed_at=utc_now(),
                change_type="EMPTY_POSITIONING_APPROVED",
                previous_value={"approved": False},
                new_value={
                    "approved": True,
                    "positioningDistanceMeters": positioning_distance,
                    "positioningTravelMinutes": plan.metrics.get(
                        "support_positioning_travel_minutes",
                        0,
                    ),
                },
                reason=normalized_reason,
                plan_version_before=version_before,
                plan_version_after=plan.version,
            )
        )
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
    warehouse_zone = ZoneInfo(plan.warehouse.timezone)
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
        local_arrival = stop.planned_arrival.astimezone(warehouse_zone)
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
