"""Operator endpoints for explicit RWMS import and assignment application."""

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query

from app.api.dependencies import SessionDep, SettingsDep
from app.integrations.rwms import RwmsPlanningClient, get_rwms_planning_client
from app.integrations.rwms_sync import (
    apply_plan_to_rwms,
    get_plan_rwms_status,
    sync_scenario_requests,
)
from app.schemas.domain import (
    RwmsApplyResult,
    RwmsPlanApplyRequest,
    RwmsPlanStatusResult,
    RwmsSyncRequest,
    RwmsSyncResult,
)

router = APIRouter(tags=["rwms-integration"])


def rwms_client(settings: SettingsDep) -> RwmsPlanningClient:
    """Resolve the configured adapter while retaining its in-memory token cache."""

    return get_rwms_planning_client(settings)


RwmsClientDep = Annotated[RwmsPlanningClient, Depends(rwms_client)]


@router.post("/scenarios/{scenario_id}/rwms/sync", response_model=RwmsSyncResult)
async def sync_rwms_requests(
    scenario_id: UUID,
    payload: RwmsSyncRequest,
    session: SessionDep,
    client: RwmsClientDep,
) -> RwmsSyncResult:
    """Import a bounded RWMS delivery feed without geocoding address-only orders."""

    return await sync_scenario_requests(session, scenario_id, payload, client)


@router.post("/plans/{plan_id}/rwms/apply", response_model=RwmsApplyResult)
async def apply_rwms_plan(
    plan_id: UUID,
    payload: RwmsPlanApplyRequest,
    session: SessionDep,
    client: RwmsClientDep,
) -> RwmsApplyResult:
    """Submit the exact plan version and return all upstream rejections unchanged."""

    return await apply_plan_to_rwms(session, plan_id, payload, client)


@router.get("/plans/{plan_id}/rwms/status", response_model=RwmsPlanStatusResult)
async def rwms_plan_status(
    plan_id: UUID,
    expected_version: Annotated[int, Query(ge=1)],
    session: SessionDep,
    client: RwmsClientDep,
) -> RwmsPlanStatusResult:
    """Return current RWMS publication and claim states for one exact plan version."""

    return await get_plan_rwms_status(session, plan_id, expected_version, client)
