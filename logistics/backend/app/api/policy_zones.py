"""Authenticated warehouse-scoped CRUD for exceptional policy polygons."""

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Header, Query, Response, status

from app.api.authorization import require_local_warehouse_access
from app.api.dependencies import (
    CapacityRwmsClientDep,
    CurrentUserDep,
    SessionDep,
    SettingsDep,
)
from app.models.policy_zone import WarehousePolicyZone
from app.schemas.policy_zones import PolicyZoneCreate, PolicyZoneRead, PolicyZoneUpdate
from app.security import WarehouseAccessLevel
from app.services.capacity_mutations import publish_capacity_after_mutation
from app.services.catalog_command_idempotency import execute_idempotent_create
from app.services.policy_zones import (
    archive_policy_stale_plan_heads,
    create_policy_zone,
    delete_policy_zone,
    list_policy_zones,
    policy_zone_read,
    require_policy_zone,
    update_policy_zone,
)

router = APIRouter(tags=["policy-zones"])
IdempotencyKey = Annotated[
    str,
    Header(
        alias="Idempotency-Key",
        min_length=1,
        max_length=200,
        pattern=r"^[\x21-\x7e]+$",
    ),
]


@router.get(
    "/warehouses/{warehouse_id}/policy-zones",
    response_model=list[PolicyZoneRead],
)
async def list_warehouse_policy_zones(
    warehouse_id: UUID,
    session: SessionDep,
    principal: CurrentUserDep,
) -> list[PolicyZoneRead]:
    """List exact exceptional policies visible for one authorized warehouse."""

    await require_local_warehouse_access(
        session,
        principal,
        warehouse_id,
        WarehouseAccessLevel.VIEW,
    )
    return [
        policy_zone_read(zone)
        for zone in await list_policy_zones(session, warehouse_id)
    ]


@router.post(
    "/warehouses/{warehouse_id}/policy-zones",
    response_model=PolicyZoneRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_warehouse_policy_zone(
    warehouse_id: UUID,
    payload: PolicyZoneCreate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
    idempotency_key: IdempotencyKey,
) -> PolicyZoneRead:
    """Create exactly once, then advance and publish warehouse capacity facts."""

    await require_local_warehouse_access(
        session,
        principal,
        warehouse_id,
        WarehouseAccessLevel.EDIT,
    )

    async def create() -> WarehousePolicyZone:
        """Persist the policy only for the first durable command execution."""

        return await create_policy_zone(session, warehouse_id, payload)

    async def load(resource_id: UUID) -> WarehousePolicyZone | None:
        """Replay only the exact resource still owned by the request warehouse."""

        zone = await session.get(WarehousePolicyZone, resource_id)
        return zone if zone is not None and zone.warehouse_id == warehouse_id else None

    outcome = await execute_idempotent_create(
        session,
        operation="create_warehouse_policy_zone",
        actor_id=principal.subject_id,
        idempotency_key=idempotency_key,
        payload={
            "warehouse_id": str(warehouse_id),
            "payload": payload.model_dump(mode="json"),
        },
        resource_type="warehouse_policy_zone",
        create=create,
        load=load,
    )
    if not outcome.replayed:
        await archive_policy_stale_plan_heads(session, warehouse_id)
        await publish_capacity_after_mutation(
            session,
            warehouse_id,
            settings,
            client,
        )
    return policy_zone_read(outcome.resource)


@router.get(
    "/warehouses/{warehouse_id}/policy-zones/{zone_id}",
    response_model=PolicyZoneRead,
)
async def get_warehouse_policy_zone(
    warehouse_id: UUID,
    zone_id: UUID,
    session: SessionDep,
    principal: CurrentUserDep,
) -> PolicyZoneRead:
    """Read one exact owner policy after warehouse authorization."""

    await require_local_warehouse_access(
        session,
        principal,
        warehouse_id,
        WarehouseAccessLevel.VIEW,
    )
    return policy_zone_read(
        await require_policy_zone(session, warehouse_id, zone_id)
    )


@router.patch(
    "/warehouses/{warehouse_id}/policy-zones/{zone_id}",
    response_model=PolicyZoneRead,
)
async def patch_warehouse_policy_zone(
    warehouse_id: UUID,
    zone_id: UUID,
    payload: PolicyZoneUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> PolicyZoneRead:
    """Apply a fenced update and publish the new policy revision."""

    await require_local_warehouse_access(
        session,
        principal,
        warehouse_id,
        WarehouseAccessLevel.EDIT,
    )
    zone = await update_policy_zone(
        session,
        warehouse_id,
        zone_id,
        payload,
    )
    await archive_policy_stale_plan_heads(session, warehouse_id)
    await publish_capacity_after_mutation(
        session,
        warehouse_id,
        settings,
        client,
    )
    return policy_zone_read(zone)


@router.delete(
    "/warehouses/{warehouse_id}/policy-zones/{zone_id}",
    status_code=status.HTTP_204_NO_CONTENT,
)
async def remove_warehouse_policy_zone(
    warehouse_id: UUID,
    zone_id: UUID,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
    expected_version: Annotated[int, Query(ge=1)],
) -> Response:
    """Delete one fenced owner policy and publish its absence."""

    await require_local_warehouse_access(
        session,
        principal,
        warehouse_id,
        WarehouseAccessLevel.EDIT,
    )
    await delete_policy_zone(
        session,
        warehouse_id,
        zone_id,
        expected_version,
    )
    await archive_policy_stale_plan_heads(session, warehouse_id)
    await publish_capacity_after_mutation(
        session,
        warehouse_id,
        settings,
        client,
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)
