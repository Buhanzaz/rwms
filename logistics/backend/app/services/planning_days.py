"""One-way planning-day closure and final-plan orchestration."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from uuid import UUID

from sqlalchemy import and_, delete, or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError, not_found
from app.models import (
    LogisticsRequest,
    PlanningDayClosure,
    PlanningTask,
    RequestDateOption,
    RouteCycle,
    RoutePlan,
    RouteStop,
    Warehouse,
)
from app.models.domain import PlanStatus, RequestStatus
from app.schemas.domain import PlanningDayStatusRead
from app.services.auto_planning import generate_missing_draft_plans
from app.services.plans import PlannerFacade


@dataclass(frozen=True, slots=True)
class PlanningDayCloseResult:
    """Closure status plus whether this command changed durable acceptance state."""

    status: PlanningDayStatusRead
    changed: bool


async def _resolve_warehouse(session: AsyncSession, warehouse_id: UUID) -> Warehouse:
    """Resolve the explicitly selected warehouse workspace."""

    warehouse = await session.scalar(select(Warehouse).where(Warehouse.id == warehouse_id))
    if warehouse is None:
        raise ApiError(
            422,
            "NO_WAREHOUSE",
            "Planning-day status requires an existing warehouse",
        )
    return warehouse


async def _latest_plan_id(
    session: AsyncSession,
    warehouse_id: UUID,
    planning_date: date,
) -> UUID | None:
    """Return the newest active plan identity for one exact depot date."""

    plan_id: UUID | None = await session.scalar(
        select(RoutePlan.id)
        .where(
            RoutePlan.warehouse_id == warehouse_id,
            RoutePlan.date == planning_date,
            RoutePlan.status != PlanStatus.ARCHIVED,
        )
        .order_by(RoutePlan.updated_at.desc(), RoutePlan.id.desc())
        .limit(1)
    )
    return plan_id


def _status(
    warehouse_id: UUID,
    planning_date: date,
    closure: PlanningDayClosure | None,
    plan_id: UUID | None,
) -> PlanningDayStatusRead:
    """Map durable closure state into the public date-status projection."""

    return PlanningDayStatusRead(
        warehouse_id=warehouse_id,
        date=planning_date,
        accepting_requests=closure is None,
        closed_at=closure.created_at if closure is not None else None,
        closed_by=closure.closed_by if closure is not None else None,
        plan_id=plan_id,
    )


async def get_planning_day_status(
    session: AsyncSession,
    warehouse_id: UUID,
    planning_date: date,
) -> PlanningDayStatusRead:
    """Read acceptance state without mutating or regenerating the current plan."""

    warehouse = await _resolve_warehouse(session, warehouse_id)
    closure = await session.scalar(
        select(PlanningDayClosure).where(
            PlanningDayClosure.warehouse_id == warehouse.id,
            PlanningDayClosure.date == planning_date,
        )
    )
    plan_id = await _latest_plan_id(session, warehouse.id, planning_date)
    return _status(warehouse.id, planning_date, closure, plan_id)


async def close_planning_day(
    session: AsyncSession,
    planner: PlannerFacade,
    warehouse_id: UUID,
    planning_date: date,
    *,
    closed_by: str = "local-admin",
) -> PlanningDayCloseResult:
    """Stop new demand and atomically replace the preliminary plan with a final one."""

    warehouse = await session.scalar(
        select(Warehouse).where(Warehouse.id == warehouse_id).with_for_update()
    )
    if warehouse is None:
        raise not_found("warehouse", warehouse_id)
    closure = await session.scalar(
        select(PlanningDayClosure).where(
            PlanningDayClosure.warehouse_id == warehouse.id,
            PlanningDayClosure.date == planning_date,
        )
    )
    changed = closure is None
    if closure is None:
        closure = PlanningDayClosure(
            warehouse_id=warehouse.id,
            date=planning_date,
            closed_by=closed_by,
        )
        session.add(closure)
        await session.flush()
        exact_date = (
            RoutePlan.warehouse_id == warehouse.id,
            RoutePlan.date == planning_date,
        )
        await session.execute(
            delete(RoutePlan).where(
                *exact_date,
                RoutePlan.status == PlanStatus.GENERATED,
                RoutePlan.manually_changed.is_(False),
            )
        )
        await session.execute(
            update(RoutePlan)
            .where(*exact_date, RoutePlan.status != PlanStatus.ARCHIVED)
            .values(status=PlanStatus.ARCHIVED)
        )
        await session.flush()
    await generate_missing_draft_plans(
        session,
        planner,
        warehouse.id,
        (planning_date,),
    )
    await session.refresh(closure)
    plan_id = await _latest_plan_id(session, warehouse.id, planning_date)
    mandatory_task_ids = set(
        await session.scalars(
            select(PlanningTask.id)
            .join(LogisticsRequest, LogisticsRequest.id == PlanningTask.request_id)
            .where(
                LogisticsRequest.warehouse_id == warehouse.id,
                LogisticsRequest.status == RequestStatus.READY,
                PlanningTask.mandatory.is_(True),
                or_(
                    LogisticsRequest.scheduled_date == planning_date,
                    and_(
                        LogisticsRequest.scheduled_date.is_(None),
                        LogisticsRequest.date_options.any(
                            RequestDateOption.date == planning_date
                        ),
                    ),
                ),
            )
        )
    )
    assigned_task_ids: set[UUID] = set()
    if plan_id is not None:
        assigned_task_ids = {
            task_id
            for task_id in await session.scalars(
                select(RouteStop.task_id)
                .join(RouteCycle, RouteCycle.id == RouteStop.route_cycle_id)
                .where(
                    RouteCycle.route_plan_id == plan_id,
                    RouteStop.task_id.is_not(None),
                )
            )
            if task_id is not None
        }
    missing_mandatory = sorted(mandatory_task_ids - assigned_task_ids, key=str)
    if missing_mandatory:
        raise ApiError(
            409,
            "MANDATORY_TASKS_UNASSIGNED",
            "Every mandatory delivery or pickup must be assigned before closing the day",
            extra={"task_ids": [str(task_id) for task_id in missing_mandatory]},
        )
    return PlanningDayCloseResult(
        status=_status(warehouse.id, planning_date, closure, plan_id),
        changed=changed,
    )
