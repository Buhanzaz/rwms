"""Operator endpoints for explicit RWMS import and assignment application."""

from datetime import UTC, date, datetime, timedelta
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query

from app.api.authorization import (
    require_external_warehouse_access,
    require_local_warehouse_access,
    require_local_warehouse_set_access,
    require_plan_access,
)
from app.api.dependencies import CurrentUserDep, PlannerDep, SessionDep, SettingsDep
from app.api.geocoding import GeocodingClientDep
from app.errors import ApiError
from app.integrations.rwms import RwmsPlanningClient, get_rwms_planning_client
from app.integrations.rwms_sync import (
    apply_plan_to_rwms,
    get_plan_rwms_status,
    refresh_warehouse_requests,
    sync_warehouse_requests,
    warehouse_geocoding_query,
)
from app.schemas.domain import (
    RwmsApplyResult,
    RwmsCapacitySnapshotResult,
    RwmsPlanApplyRequest,
    RwmsPlanStatusResult,
    RwmsSyncRequest,
    RwmsSyncResult,
    RwmsWarehouseRefreshResult,
)
from app.security import WarehouseAccessLevel
from app.services.capacity_generation import advance_warehouse_capacity_generation
from app.services.capacity_projection import publish_warehouse_capacity
from app.services.capacity_publication_state import mark_capacity_publication_pending
from app.services.planning_group import resolve_planning_warehouse_group

router = APIRouter(tags=["rwms-integration"])


def rwms_client(settings: SettingsDep) -> RwmsPlanningClient:
    """Resolve the configured adapter while retaining its in-memory token cache."""

    return get_rwms_planning_client(settings)


RwmsClientDep = Annotated[RwmsPlanningClient, Depends(rwms_client)]


def utc_today() -> date:
    """Return the current UTC date used for the deterministic 31-day refresh horizon."""

    return datetime.now(UTC).date()


@router.post("/warehouses/{warehouse_id}/rwms/sync", response_model=RwmsSyncResult)
async def sync_rwms_requests(
    warehouse_id: UUID,
    payload: RwmsSyncRequest,
    session: SessionDep,
    client: RwmsClientDep,
    planner: PlannerDep,
    geocoder: GeocodingClientDep,
    principal: CurrentUserDep,
) -> RwmsSyncResult:
    """Import a bounded RWMS delivery feed, resolving address-only orders server-side."""

    warehouse = await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.EDIT
    )
    require_external_warehouse_access(
        principal, payload.warehouse_id, WarehouseAccessLevel.EDIT
    )
    if payload.warehouse_id != warehouse.external_warehouse_id:
        raise ApiError(
            422,
            "RWMS_WAREHOUSE_ID_MISMATCH",
            "Запрос синхронизации относится к другому складу.",
        )
    return await sync_warehouse_requests(
        session,
        warehouse_id,
        payload,
        client,
        planner,
        resolve_address=lambda source: geocoder.forward(source.address),
    )


@router.post(
    "/warehouses/{warehouse_id}/rwms/refresh",
    response_model=RwmsWarehouseRefreshResult,
)
async def refresh_rwms_requests(
    warehouse_id: UUID,
    session: SessionDep,
    client: RwmsClientDep,
    planner: PlannerDep,
    geocoder: GeocodingClientDep,
    principal: CurrentUserDep,
) -> RwmsWarehouseRefreshResult:
    """Refresh the current 31-day horizon for every linked warehouse server-side."""

    warehouse = await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.EDIT
    )
    group = await resolve_planning_warehouse_group(session, client, warehouse)
    await require_local_warehouse_set_access(
        session,
        principal,
        (member.id for member in group.members),
        WarehouseAccessLevel.EDIT,
    )
    date_from = utc_today()
    result = await refresh_warehouse_requests(
        session,
        warehouse_id,
        date_from=date_from,
        date_to=date_from + timedelta(days=30),
        client=client,
        planner=planner,
        resolve_warehouse_address=lambda identity: geocoder.forward(
            warehouse_geocoding_query(identity)
        ),
        resolve_request_address=lambda source: geocoder.forward(source.address),
    )
    failures = [
        {
            "warehouse_id": str(warehouse.warehouse_id),
            **failure.model_dump(mode="json"),
        }
        for warehouse in result.warehouses
        for failure in warehouse.failures
    ]
    if failures:
        # Keep valid sibling orders durable, but never let an automatic workspace refresh
        # silently present an incomplete authoritative feed as fully synchronized.
        await session.commit()
        raise ApiError(
            422,
            "RWMS_WORKSPACE_SYNC_INCOMPLETE",
            "RWMS workspace refresh contains orders that could not be synchronized",
            extra={
                "date_from": result.date_from.isoformat(),
                "date_to": result.date_to.isoformat(),
                "failures": failures,
            },
        )
    return result


@router.post(
    "/warehouses/{warehouse_id}/rwms/capacity",
    response_model=RwmsCapacitySnapshotResult,
)
async def reconcile_rwms_capacity(
    warehouse_id: UUID,
    session: SessionDep,
    client: RwmsClientDep,
    principal: CurrentUserDep,
) -> RwmsCapacitySnapshotResult:
    """Publish the complete current generated-delivery capacity snapshot to RWMS."""

    await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.EDIT
    )
    client.ensure_capacity_publish_enabled()
    generation = await advance_warehouse_capacity_generation(session, warehouse_id)
    await mark_capacity_publication_pending(session, warehouse_id, generation)
    await session.commit()
    return await publish_warehouse_capacity(session, warehouse_id, client)


@router.post("/plans/{plan_id}/rwms/apply", response_model=RwmsApplyResult)
async def apply_rwms_plan(
    plan_id: UUID,
    payload: RwmsPlanApplyRequest,
    session: SessionDep,
    client: RwmsClientDep,
    principal: CurrentUserDep,
    planner: PlannerDep,
) -> RwmsApplyResult:
    """Submit the exact plan version and return all upstream rejections unchanged."""

    await require_plan_access(session, principal, plan_id, WarehouseAccessLevel.EDIT)
    return await apply_plan_to_rwms(session, plan_id, payload, client, planner=planner)


@router.get("/plans/{plan_id}/rwms/status", response_model=RwmsPlanStatusResult)
async def rwms_plan_status(
    plan_id: UUID,
    expected_version: Annotated[int, Query(ge=1)],
    session: SessionDep,
    client: RwmsClientDep,
    principal: CurrentUserDep,
) -> RwmsPlanStatusResult:
    """Return current RWMS publication and claim states for one exact plan version."""

    await require_plan_access(session, principal, plan_id, WarehouseAccessLevel.VIEW)
    return await get_plan_rwms_status(session, plan_id, expected_version, client)
