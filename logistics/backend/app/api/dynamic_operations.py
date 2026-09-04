"""Authenticated API for day modes, incidents, comments, decisions, and recovery."""

from __future__ import annotations

from datetime import date
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Header

from app.api.authorization import require_dynamic_day_access
from app.api.dependencies import (
    CapacityRwmsClientDep,
    CurrentUserDep,
    PlannerDep,
    SessionDep,
)
from app.errors import ApiError
from app.models import LogisticsHumanAction, RecoveryProposal
from app.schemas.operations import (
    LogisticsEventCreate,
    LogisticsEventResult,
    LogisticsHumanDecisionCreate,
    LogisticsHumanDecisionRead,
    PlanningDayModeResult,
    PlanningDayModeUpdate,
    PlanningDayOperationsRead,
    RecoveryProposalApply,
    RecoveryProposalRead,
    RetentionDryRunRead,
)
from app.security import WarehouseAccessLevel
from app.services.dynamic_operations import DynamicLogisticsService

router = APIRouter(tags=["dynamic-logistics"])
IdempotencyKey = Annotated[str, Header(alias="Idempotency-Key", min_length=1, max_length=200)]


@router.get(
    "/warehouses/{warehouse_id}/planning-days/{planning_date}/operations",
    response_model=PlanningDayOperationsRead,
)
async def get_day_operations(
    warehouse_id: UUID,
    planning_date: date,
    session: SessionDep,
    planner: PlannerDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> PlanningDayOperationsRead:
    """Read mode, comments, pending actions, decisions, and proposal history."""

    await require_dynamic_day_access(
        session,
        principal,
        client,
        warehouse_id,
        planning_date,
        WarehouseAccessLevel.VIEW,
    )
    return await DynamicLogisticsService(planner, client).day_operations(
        session, warehouse_id, planning_date
    )


@router.put(
    "/warehouses/{warehouse_id}/planning-days/{planning_date}/mode",
    response_model=PlanningDayModeResult,
)
async def put_day_mode(
    warehouse_id: UUID,
    planning_date: date,
    payload: PlanningDayModeUpdate,
    idempotency_key: IdempotencyKey,
    session: SessionDep,
    planner: PlannerDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> PlanningDayModeResult:
    """Change a day mode without silently removing conflicting planned work."""

    await require_dynamic_day_access(
        session,
        principal,
        client,
        warehouse_id,
        planning_date,
        WarehouseAccessLevel.EDIT,
    )
    return await DynamicLogisticsService(planner, client).set_day_mode(
        session,
        warehouse_id,
        planning_date,
        expected_version=payload.expected_version,
        plan_id=payload.plan_id,
        expected_plan_version=payload.expected_plan_version,
        mode=payload.mode,
        actor=principal.audit_actor,
        idempotency_key=idempotency_key,
    )


@router.post(
    "/warehouses/{warehouse_id}/planning-days/{planning_date}/events",
    response_model=LogisticsEventResult,
)
async def post_logistics_event(
    warehouse_id: UUID,
    planning_date: date,
    payload: LogisticsEventCreate,
    idempotency_key: IdempotencyKey,
    session: SessionDep,
    planner: PlannerDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> LogisticsEventResult:
    """Register an incident and return its structured impact analysis."""

    await require_dynamic_day_access(
        session,
        principal,
        client,
        warehouse_id,
        planning_date,
        WarehouseAccessLevel.EDIT,
    )
    return await DynamicLogisticsService(planner, client).register_event(
        session,
        warehouse_id,
        planning_date,
        payload,
        actor=principal.audit_actor,
        idempotency_key=idempotency_key,
    )


@router.post(
    "/logistics-actions/{action_id}/decisions",
    response_model=LogisticsHumanDecisionRead,
)
async def post_human_decision(
    action_id: UUID,
    payload: LogisticsHumanDecisionCreate,
    idempotency_key: IdempotencyKey,
    session: SessionDep,
    planner: PlannerDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> LogisticsHumanDecisionRead:
    """Record customer/operator feedback as an immutable solve constraint."""

    action = await session.get(LogisticsHumanAction, action_id)
    if action is None:
        raise ApiError(404, "LOGISTICS_ACTION_NOT_FOUND", "Действие не найдено.")
    await require_dynamic_day_access(
        session,
        principal,
        client,
        action.warehouse_id,
        action.day,
        WarehouseAccessLevel.EDIT,
    )
    return await DynamicLogisticsService(planner, client).decide_action(
        session,
        action_id,
        payload,
        actor=principal.audit_actor,
        idempotency_key=idempotency_key,
    )


@router.post(
    "/recovery-proposals/{proposal_id}/apply",
    response_model=RecoveryProposalRead,
)
async def apply_recovery_proposal(
    proposal_id: UUID,
    payload: RecoveryProposalApply,
    idempotency_key: IdempotencyKey,
    session: SessionDep,
    planner: PlannerDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> RecoveryProposalRead:
    """Apply only a ready, version-fenced proposal through the existing planner."""

    proposal = await session.get(RecoveryProposal, proposal_id)
    if proposal is None:
        raise ApiError(404, "RECOVERY_PROPOSAL_NOT_FOUND", "Предложение не найдено.")
    await require_dynamic_day_access(
        session,
        principal,
        client,
        proposal.warehouse_id,
        proposal.day,
        WarehouseAccessLevel.EDIT,
    )
    return await DynamicLogisticsService(planner, client).apply_proposal(
        session,
        proposal_id,
        expected_version=payload.expected_version,
        actor=principal.audit_actor,
        actor_subject_id=principal.subject_id,
        idempotency_key=idempotency_key,
    )


@router.get("/retention/dry-run", response_model=RetentionDryRunRead)
async def retention_dry_run(
    session: SessionDep,
    planner: PlannerDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> RetentionDryRunRead:
    """Show safe retention candidates; never delete or archive records."""

    principal.require_scope("rwms.read")
    if principal.global_role not in {"SYSTEM_ADMIN", "WMS_ADMIN"}:
        raise ApiError(403, "ACCESS_DENIED", "Недостаточно прав для отчёта хранения.")
    return await DynamicLogisticsService(planner, client).retention_dry_run(session)
