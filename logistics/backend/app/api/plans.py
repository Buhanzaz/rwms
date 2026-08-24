"""Plan, optimizer-run, trace streaming, and manual command endpoints."""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from time import monotonic
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Header, Query, Request
from fastapi.responses import StreamingResponse

from app.api.dependencies import PlannerDep, SessionDep, SettingsDep
from app.config import Settings
from app.db import async_session_factory
from app.errors import ApiError
from app.models.domain import OptimizationStatus
from app.schemas.domain import (
    CloneRequest,
    ConfirmPlanRequest,
    CyclePatch,
    DriverUnavailableRequest,
    ExpectedVersionRequest,
    GeneratePlanRequest,
    ManualChangeRequest,
    OptimizationRunRead,
    RoutePlanRead,
    SimulationDelayRequest,
    TraceEventRead,
)
from app.services import plans as service

router = APIRouter(tags=["plans"])


@router.post(
    "/scenarios/{scenario_id}/plans/generate",
    response_model=OptimizationRunRead,
    status_code=202,
)
async def generate_plan(
    scenario_id: UUID,
    payload: GeneratePlanRequest,
    session: SessionDep,
    planner: PlannerDep,
) -> object:
    """Start a real planner run through the configured integration facade."""

    return await planner.generate_plan(session, scenario_id, payload)


@router.get("/plans/{plan_id}", response_model=RoutePlanRead)
async def get_plan(plan_id: UUID, session: SessionDep) -> RoutePlanRead:
    """Read one complete saved plan."""

    return service.plan_read(await service.get_plan(session, plan_id))


@router.post("/plans/{plan_id}/validate", response_model=RoutePlanRead)
async def validate_plan(
    plan_id: UUID,
    payload: ExpectedVersionRequest,
    session: SessionDep,
    planner: PlannerDep,
) -> RoutePlanRead:
    """Run full planner validation against an optimistic version token."""

    plan = await planner.validate_plan(session, plan_id, payload.expected_version)
    return service.plan_read(await service.get_plan(session, plan.id))


@router.post("/plans/{plan_id}/clone", response_model=RoutePlanRead, status_code=201)
async def clone_plan(plan_id: UUID, payload: CloneRequest, session: SessionDep) -> RoutePlanRead:
    """Clone a saved plan before experimentation or manual editing."""

    return service.plan_read(await service.clone_plan(session, plan_id, name=payload.name))


@router.post("/plans/{plan_id}/confirm", response_model=RoutePlanRead)
async def confirm_plan(
    plan_id: UUID, payload: ConfirmPlanRequest, session: SessionDep
) -> RoutePlanRead:
    """Confirm an error-free plan with explicit warning acknowledgement."""

    plan = await service.confirm_plan(
        session,
        plan_id,
        payload.expected_version,
        accept_warnings=payload.accept_warnings,
    )
    return service.plan_read(plan)


@router.post("/plans/{plan_id}/reoptimize", response_model=OptimizationRunRead, status_code=202)
async def reoptimize_plan(
    plan_id: UUID,
    payload: ManualChangeRequest,
    session: SessionDep,
    planner: PlannerDep,
) -> object:
    """Reoptimize only unlocked plan content through the planner facade."""

    return await planner.reoptimize_plan(session, plan_id, payload)


@router.patch("/plans/{plan_id}/cycles/{cycle_id}", response_model=RoutePlanRead)
async def patch_cycle(
    plan_id: UUID,
    cycle_id: UUID,
    payload: CyclePatch,
    session: SessionDep,
    planner: PlannerDep,
) -> RoutePlanRead:
    """Apply a fully validated route-cycle mutation."""

    plan = await planner.apply_cycle_change(session, plan_id, cycle_id, payload)
    return service.plan_read(await service.get_plan(session, plan.id))


@router.post("/plans/{plan_id}/manual-change", response_model=RoutePlanRead)
async def manual_change(
    plan_id: UUID,
    payload: ManualChangeRequest,
    session: SessionDep,
    planner: PlannerDep,
) -> RoutePlanRead:
    """Apply an audited, planner-validated drag-and-drop or structural edit."""

    plan = await planner.apply_manual_change(session, plan_id, payload)
    return service.plan_read(await service.get_plan(session, plan.id))


@router.post("/plans/{plan_id}/simulation/delay", response_model=RoutePlanRead)
async def simulation_delay(
    plan_id: UUID,
    payload: SimulationDelayRequest,
    session: SessionDep,
    planner: PlannerDep,
) -> RoutePlanRead:
    """Apply a non-destructive delay override and return recalculated timing."""

    command = ManualChangeRequest(
        expected_version=payload.expected_version,
        change_type="SIMULATION_DELAY",
        payload=payload.model_dump(
            exclude={"expected_version", "reason"},
            mode="json",
        ),
        reason=payload.reason,
        changed_by="local-admin",
    )
    plan = await planner.apply_simulation_delay(session, plan_id, command)
    return service.plan_read(await service.get_plan(session, plan.id))


@router.post(
    "/plans/{plan_id}/simulation/driver-unavailable",
    response_model=RoutePlanRead,
)
async def simulation_driver_unavailable(
    plan_id: UUID,
    payload: DriverUnavailableRequest,
    session: SessionDep,
    planner: PlannerDep,
) -> RoutePlanRead:
    """Validate a driver-unavailability override against the remaining schedule."""

    command = ManualChangeRequest(
        expected_version=payload.expected_version,
        change_type="DRIVER_UNAVAILABLE",
        payload=payload.model_dump(
            exclude={"expected_version", "reason"},
            mode="json",
        ),
        reason=payload.reason,
        changed_by="local-admin",
    )
    plan = await planner.apply_simulation_delay(session, plan_id, command)
    return service.plan_read(await service.get_plan(session, plan.id))


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
) -> object:
    """Start remaining-day replanning with completed and locked work fixed."""

    return await planner.replan_simulation(session, plan_id, payload)


@router.get("/optimization-runs/{run_id}", response_model=OptimizationRunRead)
async def get_optimization_run(run_id: UUID, session: SessionDep) -> object:
    """Read optimizer status independently from any eventual route plan."""

    return await service.get_optimization_run(session, run_id)


@router.get("/optimization-runs/{run_id}/events", response_model=list[TraceEventRead])
async def list_optimization_events(
    run_id: UUID,
    session: SessionDep,
    after_sequence: Annotated[int, Query(ge=0)] = 0,
    limit: Annotated[int, Query(ge=1, le=2_000)] = 500,
) -> object:
    """Read a bounded page of persisted real optimizer events."""

    return await service.list_trace_events(
        session, run_id, after_sequence=after_sequence, limit=limit
    )


@router.post("/optimization-runs/{run_id}/cancel", response_model=OptimizationRunRead)
async def cancel_optimization_run(run_id: UUID, session: SessionDep) -> object:
    """Request cooperative optimizer cancellation."""

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
        await service.get_optimization_run(session, run_id)
    return StreamingResponse(
        _event_stream(request, run_id, start_sequence, settings),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )
