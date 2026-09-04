"""Shared local mutation fence for owner-backed request rescheduling."""

from collections.abc import Sequence
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.models import (
    PlanningTask,
    RequestRescheduleHold,
    RouteCycle,
    RouteStop,
    UnassignedTask,
)
from app.models.domain import RequestRescheduleHoldState

ACTIVE_REQUEST_RESCHEDULE_STATES = (
    RequestRescheduleHoldState.CLAIMED,
    RequestRescheduleHoldState.OWNER_CALLING,
    RequestRescheduleHoldState.QUARANTINED,
)


async def reject_active_request_reschedules(
    session: AsyncSession,
    request_ids: Sequence[UUID],
    *,
    exclude_hold_id: UUID | None = None,
) -> None:
    """Reject a competing mutation after its request rows have been locked."""

    unique_request_ids = tuple(sorted(set(request_ids), key=str))
    if not unique_request_ids:
        return
    statement = select(
        RequestRescheduleHold.id,
        RequestRescheduleHold.request_id,
        RequestRescheduleHold.state,
        RequestRescheduleHold.quarantine_count,
    ).where(
        RequestRescheduleHold.request_id.in_(unique_request_ids),
        RequestRescheduleHold.state.in_(ACTIVE_REQUEST_RESCHEDULE_STATES),
    )
    if exclude_hold_id is not None:
        statement = statement.where(RequestRescheduleHold.id != exclude_hold_id)
    active = (
        await session.execute(statement.order_by(RequestRescheduleHold.request_id).limit(1))
    ).one_or_none()
    if active is not None:
        code = (
            "REQUEST_RESCHEDULE_QUARANTINED"
            if active.state == RequestRescheduleHoldState.QUARANTINED
            else "REQUEST_RESCHEDULE_IN_PROGRESS"
        )
        detail = (
            "Перенос требует безопасного повторного запуска оператором."
            if active.state == RequestRescheduleHoldState.QUARANTINED
            else "Для заявки уже выполняется подтверждение нового клиентского слота."
        )
        raise ApiError(
            409,
            code,
            detail,
            extra={
                "request_id": str(active.request_id),
                "hold_id": str(active.id),
                "quarantine_count": active.quarantine_count,
            },
            headers={"Retry-After": "3"},
        )


async def fence_plan_request_reschedules(
    session: AsyncSession,
    plan_id: UUID,
    *,
    exclude_hold_id: UUID | None = None,
) -> None:
    """Reject active holds for a plan whose row the caller already locked."""

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
    request_ids = tuple(
        sorted(
            set(await session.scalars(assigned.union(unassigned))),
            key=str,
        )
    )
    if not request_ids:
        return
    await reject_active_request_reschedules(
        session,
        request_ids,
        exclude_hold_id=exclude_hold_id,
    )
