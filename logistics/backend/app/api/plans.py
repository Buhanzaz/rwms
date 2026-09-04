"""Plan, optimizer-run, trace streaming, and manual command endpoints."""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from datetime import date
from time import monotonic
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Header, Query, Request
from fastapi.responses import StreamingResponse

from app.api.authorization import (
    require_local_warehouse_access,
    require_optimization_run_access,
    require_plan_access,
)
from app.api.dependencies import (
    CapacityRwmsClientDep,
    CurrentUserDep,
    PlannerDep,
    SessionDep,
    SettingsDep,
)
from app.config import Settings
from app.db import async_session_factory
from app.errors import ApiError
from app.models.domain import OptimizationStatus
from app.schemas.domain import (
    ConfirmPlanRequest,
    CyclePatch,
    DriverUnavailableRequest,
    ExpectedVersionRequest,
    ManualChangeCommand,
    ManualChangeRequest,
    OptimizationRunRead,
    PlanningDayStatusRead,
    RoutePlanRead,
    SimulationDelayRequest,
    TraceEventRead,
)
from app.security import WarehouseAccessLevel
from app.services import plans as service
from app.services.auto_planning import generate_missing_draft_plans
from app.services.capacity_mutations import publish_capacity_after_mutation
from app.services.capacity_projection import publish_warehouse_capacity
from app.services.planning_days import close_planning_day, get_planning_day_status

router = APIRouter(tags=["plans"])


@router.get(
    "/warehouses/{warehouse_id}/planning-days/{planning_date}",
    response_model=PlanningDayStatusRead,
)
async def planning_day_status(
    warehouse_id: UUID,
    planning_date: date,
    session: SessionDep,
    principal: CurrentUserDep,
) -> PlanningDayStatusRead:
    """Read whether a depot date still accepts new delivery demand."""

    await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.VIEW
    )
    return await get_planning_day_status(
        session,
        warehouse_id,
        planning_date,
    )


@router.post(
    "/warehouses/{warehouse_id}/planning-days/{planning_date}/close",
    response_model=PlanningDayStatusRead,
)
async def close_day_acceptance(
    warehouse_id: UUID,
    planning_date: date,
    session: SessionDep,
    planner: PlannerDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> PlanningDayStatusRead:
    """Finalize one date without publishing an unconfirmed route plan to RWMS."""

    await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.EDIT
    )
    result = await close_planning_day(
        session,
        planner,
        warehouse_id,
        planning_date,
        closed_by=principal.audit_actor,
    )
    if result.changed:
        await publish_capacity_after_mutation(session, warehouse_id, settings, client)
    elif settings.rwms_capacity_publish_enabled:
        # A repeated close retries the same committed capacity generation after
        # a lost or failed remote response without advancing business state.
        await publish_warehouse_capacity(session, warehouse_id, client)
    return result.status


@router.post(
    "/warehouses/{warehouse_id}/plans/ensure",
    response_model=RoutePlanRead | None,
)
async def ensure_automatic_plan(
    warehouse_id: UUID,
    planning_date: Annotated[date, Query(alias="date")],
    session: SessionDep,
    planner: PlannerDep,
    principal: CurrentUserDep,
) -> RoutePlanRead | None:
    """Refresh a marked plan in place or create a missing pre-plan for a complete day."""

    await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.EDIT
    )
    await generate_missing_draft_plans(
        session,
        planner,
        warehouse_id,
        (planning_date,),
    )
    plan = await service.get_latest_plan_for_date(session, warehouse_id, planning_date)
    return service.plan_read(plan) if plan is not None else None


@router.get("/plans/{plan_id}", response_model=RoutePlanRead)
async def get_plan(
    plan_id: UUID, session: SessionDep, principal: CurrentUserDep
) -> RoutePlanRead:
    """Read one complete saved plan."""

    await require_plan_access(session, principal, plan_id, WarehouseAccessLevel.VIEW)
    return service.plan_read(await service.get_plan(session, plan_id))


@router.post("/plans/{plan_id}/validate", response_model=RoutePlanRead)
async def validate_plan(
    plan_id: UUID,
    payload: ExpectedVersionRequest,
    session: SessionDep,
    planner: PlannerDep,
    principal: CurrentUserDep,
) -> RoutePlanRead:
    """Run full planner validation against an optimistic version token."""

    await require_plan_access(session, principal, plan_id, WarehouseAccessLevel.EDIT)
    plan = await planner.validate_plan(session, plan_id, payload.expected_version)
    return service.plan_read(await service.get_plan(session, plan.id))


@router.post("/plans/{plan_id}/confirm", response_model=RoutePlanRead)
async def confirm_plan(
    plan_id: UUID,
    payload: ConfirmPlanRequest,
    session: SessionDep,
    principal: CurrentUserDep,
) -> RoutePlanRead:
    """Confirm an error-free plan with explicit warning acknowledgement."""

    await require_plan_access(session, principal, plan_id, WarehouseAccessLevel.EDIT)
    plan = await service.confirm_plan(
        session,
        plan_id,
        payload.expected_version,
        accept_warnings=payload.accept_warnings,
        empty_positioning_reason=payload.empty_positioning_reason,
        confirmed_by=principal.audit_actor,
    )
    return service.plan_read(plan)


@router.post("/plans/{plan_id}/reoptimize", response_model=OptimizationRunRead, status_code=202)
async def reoptimize_plan(
    plan_id: UUID,
    payload: ManualChangeRequest,
    session: SessionDep,
    planner: PlannerDep,
    principal: CurrentUserDep,
) -> object:
    """Reoptimize only unlocked plan content through the planner facade."""

    await require_plan_access(session, principal, plan_id, WarehouseAccessLevel.EDIT)
    command = ManualChangeCommand(
        **payload.model_dump(mode="python"),
        changed_by=principal.audit_actor,
    )
    return await planner.reoptimize_plan(session, plan_id, command)


@router.patch("/plans/{plan_id}/cycles/{cycle_id}", response_model=RoutePlanRead)
async def patch_cycle(
    plan_id: UUID,
    cycle_id: UUID,
    payload: CyclePatch,
    session: SessionDep,
    planner: PlannerDep,
    principal: CurrentUserDep,
) -> RoutePlanRead:
    """Apply a fully validated route-cycle mutation."""

    await require_plan_access(session, principal, plan_id, WarehouseAccessLevel.EDIT)
    plan = await planner.apply_cycle_change(
        session, plan_id, cycle_id, payload, principal.audit_actor
    )
    return service.plan_read(await service.get_plan(session, plan.id))


@router.post("/plans/{plan_id}/manual-change", response_model=RoutePlanRead)
async def manual_change(
    plan_id: UUID,
    payload: ManualChangeRequest,
    session: SessionDep,
    planner: PlannerDep,
    principal: CurrentUserDep,
) -> RoutePlanRead:
    """Apply an audited, planner-validated drag-and-drop or structural edit."""

    await require_plan_access(session, principal, plan_id, WarehouseAccessLevel.EDIT)
    command = ManualChangeCommand(
        **payload.model_dump(mode="python"),
        changed_by=principal.audit_actor,
    )
    plan = await planner.apply_manual_change(session, plan_id, command)
    return service.plan_read(await service.get_plan(session, plan.id))


@router.post("/plans/{plan_id}/manual-changes/reset", response_model=RoutePlanRead)
async def reset_manual_changes(
    plan_id: UUID,
    payload: ExpectedVersionRequest,
    session: SessionDep,
    planner: PlannerDep,
    principal: CurrentUserDep,
) -> RoutePlanRead:
    """Discard pre-confirmation manual edits and return a rebuilt automatic plan."""

    await require_plan_access(session, principal, plan_id, WarehouseAccessLevel.EDIT)
    plan = await planner.reset_manual_changes(
        session,
        plan_id,
        payload.expected_version,
    )
    return service.plan_read(await service.get_plan(session, plan.id))


@router.post("/plans/{plan_id}/simulation/delay", response_model=RoutePlanRead)
async def simulation_delay(
    plan_id: UUID,
    payload: SimulationDelayRequest,
    session: SessionDep,
    planner: PlannerDep,
    principal: CurrentUserDep,
) -> RoutePlanRead:
    """Apply a non-destructive delay override and return recalculated timing."""

    await require_plan_access(session, principal, plan_id, WarehouseAccessLevel.EDIT)
    command = ManualChangeCommand(
        expected_version=payload.expected_version,
        change_type="SIMULATION_DELAY",
        payload=payload.model_dump(
            exclude={"expected_version", "reason"},
            mode="json",
        ),
        reason=payload.reason,
        changed_by=principal.audit_actor,
    )
    return await planner.apply_simulation_delay(session, plan_id, command)


@router.post(
    "/plans/{plan_id}/simulation/driver-unavailable",
    response_model=RoutePlanRead,
)
async def simulation_driver_unavailable(
    plan_id: UUID,
    payload: DriverUnavailableRequest,
    session: SessionDep,
    planner: PlannerDep,
    principal: CurrentUserDep,
) -> RoutePlanRead:
    """Validate a driver-unavailability override against the remaining schedule."""

    await require_plan_access(session, principal, plan_id, WarehouseAccessLevel.EDIT)
    command = ManualChangeCommand(
        expected_version=payload.expected_version,
        change_type="DRIVER_UNAVAILABLE",
        payload=payload.model_dump(
            exclude={"expected_version", "reason"},
            mode="json",
        ),
        reason=payload.reason,
        changed_by=principal.audit_actor,
    )
    return await planner.apply_simulation_delay(session, plan_id, command)


@router.post(
    "/plans/{plan_id}/simulation/replan",
    response_model=OptimizationRunRead,
    status_code=202,
)
async def simulation_replan(
    plan_id: UUID,
    payload: ManualChangeRequest,
    session: SessionDep,
    planner: PlannerDep,
    principal: CurrentUserDep,
) -> object:
    """Start remaining-day replanning with completed and locked work fixed."""

    await require_plan_access(session, principal, plan_id, WarehouseAccessLevel.EDIT)
    command = ManualChangeCommand(
        **payload.model_dump(mode="python"),
        changed_by=principal.audit_actor,
    )
    return await planner.replan_simulation(session, plan_id, command)


@router.get("/optimization-runs/{run_id}", response_model=OptimizationRunRead)
async def get_optimization_run(
    run_id: UUID, session: SessionDep, principal: CurrentUserDep
) -> object:
    """Read optimizer status independently from any eventual route plan."""

    await require_optimization_run_access(
        session, principal, run_id, WarehouseAccessLevel.VIEW
    )
    return await service.get_optimization_run(session, run_id)


@router.get("/optimization-runs/{run_id}/events", response_model=list[TraceEventRead])
async def list_optimization_events(
    run_id: UUID,
    session: SessionDep,
    principal: CurrentUserDep,
    after_sequence: Annotated[int, Query(ge=0)] = 0,
    limit: Annotated[int, Query(ge=1, le=2_000)] = 500,
) -> object:
    """Read a bounded page of persisted real optimizer events."""

    await require_optimization_run_access(
        session, principal, run_id, WarehouseAccessLevel.VIEW
    )
    return await service.list_trace_events(
        session, run_id, after_sequence=after_sequence, limit=limit
    )


@router.post("/optimization-runs/{run_id}/cancel", response_model=OptimizationRunRead)
async def cancel_optimization_run(
    run_id: UUID, session: SessionDep, principal: CurrentUserDep
) -> object:
    """Request cooperative optimizer cancellation."""

    await require_optimization_run_access(
        session, principal, run_id, WarehouseAccessLevel.EDIT
    )
    return await service.request_run_cancel(session, run_id)


def _sse(event_id: int | None, event: str, data: object) -> str:
    """Encode one Server-Sent Event frame without external SSE dependencies."""

    lines: list[str] = []
    if event_id is not None:
        lines.append(f"id: {event_id}")
    lines.append(f"event: {event}")
    lines.append(
        "data: " + json.dumps(data, ensure_ascii=False, separators=(",", ":"), default=str)
    )
    return "\n".join(lines) + "\n\n"


async def _event_stream(
    request: Request,
    run_id: UUID,
    start_sequence: int,
    settings: Settings,
) -> AsyncIterator[str]:
    """Poll committed trace rows with resumable IDs and bounded heartbeats."""

    last_sequence = start_sequence
    last_heartbeat = monotonic()
    terminal_statuses = {
        OptimizationStatus.COMPLETED,
        OptimizationStatus.CANCELLED,
        OptimizationStatus.FAILED,
        OptimizationStatus.TIMED_OUT,
    }
    while not await request.is_disconnected():
        async with async_session_factory() as session:
            run = await service.get_optimization_run(session, run_id)
            events = await service.list_trace_events(
                session, run_id, after_sequence=last_sequence, limit=500
            )
            status_value = run.status
        for event in events:
            last_sequence = event.sequence
            yield _sse(
                event.sequence,
                event.event_type,
                TraceEventRead.model_validate(event).model_dump(mode="json"),
            )
            last_heartbeat = monotonic()
        if status_value in terminal_statuses and not events:
            yield _sse(None, "run_terminal", {"status": status_value})
            return
        if monotonic() - last_heartbeat >= settings.sse_heartbeat_seconds:
            yield ": heartbeat\n\n"
            last_heartbeat = monotonic()
        await asyncio.sleep(settings.sse_poll_interval_seconds)


@router.get("/optimization-runs/{run_id}/stream")
async def stream_optimization_events(
    run_id: UUID,
    request: Request,
    settings: SettingsDep,
    principal: CurrentUserDep,
    last_event_id: Annotated[str | None, Header(alias="Last-Event-ID")] = None,
) -> StreamingResponse:
    """Stream persisted optimizer events and support standard Last-Event-ID resume."""

    try:
        start_sequence = int(last_event_id or 0)
    except ValueError as exc:
        raise ApiError(400, "INVALID_LAST_EVENT_ID", "Last-Event-ID must be an integer") from exc
    if start_sequence < 0:
        raise ApiError(400, "INVALID_LAST_EVENT_ID", "Last-Event-ID must be non-negative")
    async with async_session_factory() as session:
        await require_optimization_run_access(
            session, principal, run_id, WarehouseAccessLevel.VIEW
        )
    return StreamingResponse(
        _event_stream(request, run_id, start_sequence, settings),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )
