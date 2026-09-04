"""Warehouse-ownership authorization helpers for FastAPI route boundaries."""

from __future__ import annotations

from collections.abc import Iterable
from datetime import date
from uuid import UUID

from sqlalchemy import select, union
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError, not_found
from app.integrations.rwms import RwmsPlanningClient
from app.models import (
    LogisticsEvent,
    LogisticsHumanAction,
    LogisticsNotice,
    RecoveryProposal,
)
from app.models.domain import (
    Driver,
    DriverShift,
    LogisticsRequest,
    OptimizationRun,
    PlanningTask,
    RequestDateOption,
    RouteCycle,
    RoutePlan,
    RouteStop,
    SlotDayPlan,
    SlotHold,
    Trailer,
    UnassignedTask,
    Vehicle,
    Warehouse,
)
from app.security import CurrentUserPrincipal, WarehouseAccessLevel
from app.services.planning_group import resolve_planning_warehouse_group


def require_external_warehouse_access(
    principal: CurrentUserPrincipal,
    warehouse_id: UUID,
    level: WarehouseAccessLevel,
) -> None:
    """Require the OAuth scope and signed grant for one canonical warehouse ID."""

    principal.require_scope("rwms.write" if level >= WarehouseAccessLevel.EDIT else "rwms.read")
    principal.require_warehouse(warehouse_id, level)


async def require_local_warehouse_access(
    session: AsyncSession,
    principal: CurrentUserPrincipal,
    warehouse_id: UUID,
    level: WarehouseAccessLevel,
) -> Warehouse:
    """Resolve one planner-local warehouse and authorize its canonical RWMS identity."""

    warehouse = await session.get(Warehouse, warehouse_id)
    if warehouse is None:
        raise not_found("warehouse", warehouse_id)
    require_external_warehouse_access(principal, warehouse.external_warehouse_id, level)
    return warehouse


async def require_owned_entity_access[
    WarehouseOwnedType: (Driver, DriverShift, LogisticsRequest, Trailer, Vehicle)
](
    session: AsyncSession,
    principal: CurrentUserPrincipal,
    entity_type: type[WarehouseOwnedType],
    entity_id: UUID,
    resource: str,
    level: WarehouseAccessLevel,
) -> WarehouseOwnedType:
    """Authorize a warehouse-owned catalog or request entity by its local identifier."""

    entity = await session.get(entity_type, entity_id)
    if entity is None:
        raise not_found(resource, entity_id)
    await require_local_warehouse_access(session, principal, entity.warehouse_id, level)
    return entity


async def require_request_date_option_access(
    session: AsyncSession,
    principal: CurrentUserPrincipal,
    option_id: UUID,
    level: WarehouseAccessLevel,
) -> RequestDateOption:
    """Authorize a request-date option through its source request warehouse."""

    option = await session.get(RequestDateOption, option_id)
    if option is None:
        raise not_found("request_date_option", option_id)
    await require_owned_entity_access(
        session,
        principal,
        LogisticsRequest,
        option.request_id,
        "request",
        level,
    )
    return option


async def require_plan_access(
    session: AsyncSession,
    principal: CurrentUserPrincipal,
    plan_id: UUID,
    level: WarehouseAccessLevel,
) -> RoutePlan:
    """Authorize a plan root and every regional request warehouse represented in it."""

    plan = await session.get(RoutePlan, plan_id)
    if plan is None:
        raise not_found("route_plan", plan_id)
    warehouse_ids = {plan.warehouse_id}
    assigned = (
        select(LogisticsRequest.warehouse_id)
        .join(PlanningTask, PlanningTask.request_id == LogisticsRequest.id)
        .join(RouteStop, RouteStop.task_id == PlanningTask.id)
        .join(RouteCycle, RouteCycle.id == RouteStop.route_cycle_id)
        .where(RouteCycle.route_plan_id == plan_id)
    )
    unassigned = (
        select(LogisticsRequest.warehouse_id)
        .join(PlanningTask, PlanningTask.request_id == LogisticsRequest.id)
        .join(UnassignedTask, UnassignedTask.task_id == PlanningTask.id)
        .where(UnassignedTask.route_plan_id == plan_id)
    )
    warehouse_ids.update(await session.scalars(union(assigned, unassigned)))
    await require_local_warehouse_set_access(session, principal, warehouse_ids, level)
    return plan


async def require_dynamic_day_access(
    session: AsyncSession,
    principal: CurrentUserPrincipal,
    rwms_client: RwmsPlanningClient | None,
    warehouse_id: UUID,
    planning_date: date,
    level: WarehouseAccessLevel,
) -> Warehouse:
    """Authorize a root-group day and every warehouse represented in its history."""

    selected = await session.get(Warehouse, warehouse_id)
    if selected is None:
        raise not_found("warehouse", warehouse_id)
    group = await resolve_planning_warehouse_group(
        session,
        rwms_client,
        selected,
        planning_date=planning_date,
    )
    if group.root.id != warehouse_id:
        raise ApiError(
            422,
            "PLANNING_ROOT_REQUIRED",
            "Операции группового дня выполняются через основной склад.",
            extra={"planning_root_warehouse_id": str(group.root.id)},
        )
    warehouse_ids = {member.id for member in group.members}
    plan_ids = tuple(
        await session.scalars(
            select(RoutePlan.id).where(
                RoutePlan.warehouse_id == warehouse_id,
                RoutePlan.date == planning_date,
            )
        )
    )
    for plan_id in plan_ids:
        await require_plan_access(session, principal, plan_id, level)

    referenced_request_ids = set(
        await session.scalars(
            select(LogisticsEvent.request_id).where(
                LogisticsEvent.warehouse_id == warehouse_id,
                LogisticsEvent.day == planning_date,
                LogisticsEvent.request_id.is_not(None),
            )
        )
    )
    referenced_request_ids.update(
        await session.scalars(
            select(LogisticsNotice.request_id).where(
                LogisticsNotice.warehouse_id == warehouse_id,
                LogisticsNotice.day == planning_date,
                LogisticsNotice.request_id.is_not(None),
            )
        )
    )
    referenced_request_ids.update(
        await session.scalars(
            select(LogisticsHumanAction.request_id).where(
                LogisticsHumanAction.warehouse_id == warehouse_id,
                LogisticsHumanAction.day == planning_date,
                LogisticsHumanAction.request_id.is_not(None),
            )
        )
    )
    proposals = tuple(
        await session.scalars(
            select(RecoveryProposal).where(
                RecoveryProposal.warehouse_id == warehouse_id,
                RecoveryProposal.day == planning_date,
            )
        )
    )
    for proposal in proposals:
        for raw_request_id in proposal.affected_request_ids:
            try:
                referenced_request_ids.add(UUID(raw_request_id))
            except (TypeError, ValueError):
                raise ApiError(
                    409,
                    "DYNAMIC_OPERATION_REFERENCE_INVALID",
                    "История дня содержит повреждённую ссылку на задание.",
                ) from None
    referenced_request_ids.discard(None)
    if referenced_request_ids:
        warehouse_ids.update(
            await session.scalars(
                select(LogisticsRequest.warehouse_id).where(
                    LogisticsRequest.id.in_(referenced_request_ids)
                )
            )
        )
    await require_local_warehouse_set_access(session, principal, warehouse_ids, level)
    return selected


async def require_optimization_run_access(
    session: AsyncSession,
    principal: CurrentUserPrincipal,
    run_id: UUID,
    level: WarehouseAccessLevel,
) -> OptimizationRun:
    """Authorize an optimizer run through its saved plan or planning-root warehouse."""

    run = await session.get(OptimizationRun, run_id)
    if run is None:
        raise not_found("optimization_run", run_id)
    if run.plan_id is not None:
        await require_plan_access(session, principal, run.plan_id, level)
    else:
        await require_local_warehouse_access(session, principal, run.warehouse_id, level)
    return run


async def require_slot_hold_access(
    session: AsyncSession,
    principal: CurrentUserPrincipal,
    hold_id: UUID,
    level: WarehouseAccessLevel,
) -> SlotHold:
    """Authorize a slot hold through its immutable day-plan warehouse."""

    hold = await session.get(SlotHold, hold_id)
    if hold is None:
        raise not_found("slot_hold", hold_id)
    day_plan = await session.get(SlotDayPlan, hold.day_plan_id)
    if day_plan is None:
        raise not_found("slot_day_plan", hold.day_plan_id)
    await require_local_warehouse_access(session, principal, day_plan.warehouse_id, level)
    return hold


async def require_local_warehouse_set_access(
    session: AsyncSession,
    principal: CurrentUserPrincipal,
    warehouse_ids: Iterable[UUID],
    level: WarehouseAccessLevel,
) -> None:
    """Authorize every unique planner-local warehouse in one grouped operation."""

    unique_ids = set(warehouse_ids)
    warehouses = list(
        await session.scalars(select(Warehouse).where(Warehouse.id.in_(unique_ids)))
    )
    if len(warehouses) != len(unique_ids):
        missing = unique_ids.difference(item.id for item in warehouses)
        raise not_found("warehouse", next(iter(missing)))
    for warehouse in warehouses:
        require_external_warehouse_access(principal, warehouse.external_warehouse_id, level)


def filter_authorized_warehouses(
    principal: CurrentUserPrincipal,
    warehouses: Iterable[Warehouse],
    level: WarehouseAccessLevel = WarehouseAccessLevel.VIEW,
) -> list[Warehouse]:
    """Return only local warehouses covered by the caller's signed grants."""

    return [
        warehouse
        for warehouse in warehouses
        if principal.can_access(warehouse.external_warehouse_id, level)
    ]
