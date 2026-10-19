"""Server-owned automatic draft-plan generation after planning inputs change."""

from __future__ import annotations

from collections.abc import Iterable
from datetime import date
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models import (
    Driver,
    DriverShift,
    LogisticsRequest,
    OptimizationRun,
    PlanningDayClosure,
    RoutePlan,
    Vehicle,
    Warehouse,
)
from app.models.domain import CustomerDeliveryPurpose, PlanStatus, RequestStatus
from app.schemas.domain import GeneratePlanRequest
from app.services.planner_runtime import (
    planning_window_is_complete,
    request_is_available_on_date,
)
from app.services.plans import (
    PENDING_REQUEST_REFRESH_METRIC,
    PlannerFacade,
    archive_mutable_plans_for_dates,
)


async def invalidate_mutable_group_root_plans(
    session: AsyncSession,
    warehouse_id: UUID,
    planning_dates: Iterable[date],
) -> None:
    """Archive recomputable root heads before refreshing group-owned demand."""

    dates = tuple(sorted(set(planning_dates)))
    if not dates:
        return
    await archive_mutable_plans_for_dates(session, warehouse_id, set(dates))


async def generate_missing_draft_plans(
    session: AsyncSession,
    planner: PlannerFacade,
    warehouse_id: UUID,
    planning_dates: Iterable[date],
    *,
    request_warehouse_ids: Iterable[UUID] | None = None,
    resource_warehouse_ids: Iterable[UUID] | None = None,
) -> tuple[OptimizationRun, ...]:
    """Refresh marked plans in place, then generate one missing plan per ready date.

    Request metadata changes fence an existing draft and leave a refresh marker so its stable
    task/cycle ordering can be recalculated in place. Other input mutations may remove plans they
    make stale; this coordinator creates a new automatic draft exactly where no non-archived plan
    remains. A date with incomplete dispatcher facts stays unplanned until its inputs are ready.
    """

    dates = tuple(sorted(set(planning_dates)))
    if not dates:
        return ()
    warehouse = await session.scalar(
        select(Warehouse)
        .where(Warehouse.id == warehouse_id)
        .with_for_update()
        .options(
            selectinload(Warehouse.requests).selectinload(LogisticsRequest.date_options),
        )
    )
    if warehouse is None:
        return ()
    demand_warehouse_ids = tuple(
        dict.fromkeys((warehouse_id, *(request_warehouse_ids or ())))
    )
    permitted_resource_warehouse_ids = tuple(
        dict.fromkeys((warehouse_id, *(resource_warehouse_ids or ())))
    )
    demand_requests = (
        warehouse.requests
        if demand_warehouse_ids == (warehouse_id,)
        else list(
            (
                await session.scalars(
                    select(LogisticsRequest)
                    .where(LogisticsRequest.warehouse_id.in_(demand_warehouse_ids))
                    .options(selectinload(LogisticsRequest.date_options))
                    .order_by(LogisticsRequest.created_at, LogisticsRequest.id)
                )
            )
            .unique()
            .all()
        )
    )

    closed_dates = set(
        await session.scalars(
            select(PlanningDayClosure.date).where(
                PlanningDayClosure.warehouse_id == warehouse_id,
                PlanningDayClosure.date.in_(dates),
            )
        )
    )
    active_plans = list(
        await session.scalars(
            select(RoutePlan).where(
                RoutePlan.warehouse_id == warehouse_id,
                RoutePlan.date.in_(dates),
                RoutePlan.status != PlanStatus.ARCHIVED,
            )
        )
    )
    stale_automatic_ids = [
        plan.id
        for plan in active_plans
        if plan.status == PlanStatus.GENERATED
        and not plan.manually_changed
        and plan.metrics.get("accepting_requests") != (plan.date not in closed_dates)
    ]
    if stale_automatic_ids:
        await archive_mutable_plans_for_dates(
            session,
            warehouse_id,
            {
                plan.date
                for plan in active_plans
                if plan.id in stale_automatic_ids
            },
        )
    for plan in sorted(active_plans, key=lambda item: (item.date, str(item.id))):
        if (
            plan.id not in stale_automatic_ids
            and PENDING_REQUEST_REFRESH_METRIC in plan.metrics
        ):
            await planner.refresh_plan_after_request_changes(
                session,
                plan.id,
                plan.version,
            )
    active_plan_dates = {
        plan.date for plan in active_plans if plan.id not in stale_automatic_ids
    }
    runs: list[OptimizationRun] = []
    for planning_date in dates:
        if planning_date in active_plan_dates:
            continue
        ready = [
            request
            for request in demand_requests
            if request.status == RequestStatus.READY
            and request_is_available_on_date(
                request.scheduled_date,
                (option.date for option in request.date_options),
                planning_date,
            )
        ]
        if (
            not ready
            or not _planning_facts_complete(ready, planning_date)
            or not await _planning_resources_complete(
                session,
                permitted_resource_warehouse_ids,
                planning_date,
            )
        ):
            continue
        run = await planner.generate_plan(
            session,
            warehouse_id,
            GeneratePlanRequest(
                date=planning_date,
                seed=warehouse.seed,
                settings=None,
                show_trace=False,
            ),
        )
        runs.append(run)
    return tuple(runs)


async def _planning_resources_complete(
    session: AsyncSession,
    warehouse_ids: Iterable[UUID],
    planning_date: date,
) -> bool:
    """Require one usable shift from the exact-date admitted planning-group members."""

    permitted_warehouse_ids = tuple(dict.fromkeys(warehouse_ids))
    shift_id = await session.scalar(
        select(DriverShift.id)
        .join(Driver, Driver.id == DriverShift.driver_id)
        .join(Vehicle, Vehicle.id == DriverShift.vehicle_id)
        .where(
            DriverShift.warehouse_id.in_(permitted_warehouse_ids),
            DriverShift.date_from <= planning_date,
            DriverShift.date_to >= planning_date,
            DriverShift.active.is_(True),
            Driver.active.is_(True),
            Vehicle.active.is_(True),
        )
        .limit(1)
    )
    return shift_id is not None


def _planning_facts_complete(
    requests: list[LogisticsRequest],
    planning_date: date,
) -> bool:
    """Return whether every ready request has the mandatory facts for automatic planning.

    A soft date-only option deliberately represents the whole warehouse day and therefore needs
    no synthetic time window. Partial windows and hard options without bounds remain incomplete.
    """

    for request in requests:
        option = next(
            (
                item
                for item in request.date_options
                if item.date == planning_date
                and planning_window_is_complete(
                    item.window_start,
                    item.window_end,
                    is_hard=item.is_hard,
                )
            ),
            None,
        )
        cargo = (
            request.cargo_length_mm,
            request.cargo_width_mm,
            request.cargo_height_mm,
            request.cargo_weight_kg,
        )
        if (
            option is None
            or request.trailer_access_allowed is None
            or any(value is None for value in cargo)
            or request.customer_delivery_purpose
            == CustomerDeliveryPurpose.CUSTOMER_RELOCATION
        ):
            return False
    return True
