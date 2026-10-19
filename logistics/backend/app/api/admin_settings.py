"""Isolated admin commands reuse the existing planner configuration and policy owners."""

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Query, Response, status

from app.api.admin_catalog import (
    IdempotencyKey,
    _require_canonical_warehouse,
    _require_local_warehouse,
    _warehouse_directory,
)
from app.api.dependencies import CapacityRwmsClientDep, CurrentAdminDep, SessionDep, SettingsDep
from app.models import Warehouse
from app.models.policy_zone import WarehousePolicyZone
from app.schemas.admin_settings import (
    AdminPlanningSettingsRead,
    AdminPlanningSettingsUpdate,
    AdminPolicyZoneRead,
)
from app.schemas.domain import PlanningSettings, WarehouseUpdate
from app.schemas.policy_zones import PolicyZoneCreate, PolicyZoneUpdate
from app.services import catalog, policy_zones
from app.services.capacity_mutations import publish_capacity_after_mutation
from app.services.catalog_command_idempotency import execute_idempotent_create

router = APIRouter(tags=["admin planning settings"])


async def _warehouse(
    session: SessionDep, client: CapacityRwmsClientDep, canonical_id: UUID
) -> Warehouse:
    """Resolve an active canonical object; representative workspaces keep their own settings."""

    directory = await _warehouse_directory(client)
    _require_canonical_warehouse(directory, canonical_id, require_eligible=False)
    return await _require_local_warehouse(session, canonical_id)


def _settings_read(warehouse: Warehouse) -> AdminPlanningSettingsRead:
    """Return effective owner defaults and tariffs from the saved configuration."""

    return AdminPlanningSettingsRead.model_validate(
        {
            "warehouse_id": warehouse.external_warehouse_id,
            "version": warehouse.version,
            "name": warehouse.name,
            "timezone": warehouse.timezone,
            "latitude": warehouse.latitude,
            "longitude": warehouse.longitude,
            "settings": PlanningSettings.model_validate(warehouse.settings),
            "isochrone_tariffs": warehouse.isochrone_tariffs,
            "capacity_publish_status": warehouse.capacity_publish_status,
        }
    )


def _policy_read(zone: WarehousePolicyZone, canonical_id: UUID) -> AdminPolicyZoneRead:
    payload = policy_zones.policy_zone_read(zone).model_dump()
    payload["warehouse_id"] = canonical_id
    return AdminPolicyZoneRead.model_validate(payload)


@router.get(
    "/warehouses/{warehouse_id}/planning-settings",
    response_model=AdminPlanningSettingsRead,
    operation_id="plannerAdminGetPlanningSettings",
)
async def get_planning_settings(
    warehouse_id: UUID, session: SessionDep, client: CapacityRwmsClientDep
) -> AdminPlanningSettingsRead:
    return _settings_read(await _warehouse(session, client, warehouse_id))


@router.put(
    "/warehouses/{warehouse_id}/planning-settings",
    response_model=AdminPlanningSettingsRead,
    operation_id="plannerAdminReplacePlanningSettings",
)
async def replace_planning_settings(
    warehouse_id: UUID,
    payload: AdminPlanningSettingsUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> AdminPlanningSettingsRead:
    """Version-fence the existing owner configuration and publish capacity after its commit."""

    warehouse = await _warehouse(session, client, warehouse_id)
    updated = await catalog.update_warehouse(
        session,
        warehouse.id,
        WarehouseUpdate(**payload.model_dump()),
    )
    publication = await publish_capacity_after_mutation(session, warehouse.id, settings, client)
    return _settings_read(updated).model_copy(
        update={"capacity_publish_status": publication.status}
    )


@router.get(
    "/warehouses/{warehouse_id}/policy-zones",
    response_model=list[AdminPolicyZoneRead],
    operation_id="plannerAdminListPolicyZones",
)
async def list_admin_policy_zones(
    warehouse_id: UUID, session: SessionDep, client: CapacityRwmsClientDep
) -> list[AdminPolicyZoneRead]:
    warehouse = await _warehouse(session, client, warehouse_id)
    return [
        _policy_read(zone, warehouse_id)
        for zone in await policy_zones.list_policy_zones(session, warehouse.id)
    ]


@router.post(
    "/warehouses/{warehouse_id}/policy-zones",
    response_model=AdminPolicyZoneRead,
    status_code=status.HTTP_201_CREATED,
    operation_id="plannerAdminCreatePolicyZone",
)
async def create_admin_policy_zone(
    warehouse_id: UUID,
    payload: PolicyZoneCreate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentAdminDep,
    idempotency_key: IdempotencyKey,
) -> AdminPolicyZoneRead:
    """Retain create receipts, exact polygon validation and capacity/plan invalidation."""

    warehouse = await _warehouse(session, client, warehouse_id)

    async def create() -> WarehousePolicyZone:
        return await policy_zones.create_policy_zone(session, warehouse.id, payload)

    async def load(resource_id: UUID) -> WarehousePolicyZone | None:
        zone = await session.get(WarehousePolicyZone, resource_id)
        return zone if zone is not None and zone.warehouse_id == warehouse.id else None

    outcome = await execute_idempotent_create(
        session,
        operation="admin_create_warehouse_policy_zone",
        actor_id=principal.subject_id,
        idempotency_key=idempotency_key,
        payload={"warehouse_id": str(warehouse_id), "payload": payload.model_dump(mode="json")},
        resource_type="warehouse_policy_zone",
        create=create,
        load=load,
    )
    if not outcome.replayed:
        await policy_zones.archive_policy_stale_plan_heads(session, warehouse.id)
        await publish_capacity_after_mutation(session, warehouse.id, settings, client)
    return _policy_read(outcome.resource, warehouse_id)


@router.patch(
    "/warehouses/{warehouse_id}/policy-zones/{zone_id}",
    response_model=AdminPolicyZoneRead,
    operation_id="plannerAdminUpdatePolicyZone",
)
async def update_admin_policy_zone(
    warehouse_id: UUID,
    zone_id: UUID,
    payload: PolicyZoneUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> AdminPolicyZoneRead:
    warehouse = await _warehouse(session, client, warehouse_id)
    zone = await policy_zones.update_policy_zone(session, warehouse.id, zone_id, payload)
    await policy_zones.archive_policy_stale_plan_heads(session, warehouse.id)
    await publish_capacity_after_mutation(session, warehouse.id, settings, client)
    return _policy_read(zone, warehouse_id)


@router.delete(
    "/warehouses/{warehouse_id}/policy-zones/{zone_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    operation_id="plannerAdminDeletePolicyZone",
)
async def delete_admin_policy_zone(
    warehouse_id: UUID,
    zone_id: UUID,
    expected_version: Annotated[int, Query(ge=1)],
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Response:
    warehouse = await _warehouse(session, client, warehouse_id)
    await policy_zones.delete_policy_zone(session, warehouse.id, zone_id, expected_version)
    await policy_zones.archive_policy_stale_plan_heads(session, warehouse.id)
    await publish_capacity_after_mutation(session, warehouse.id, settings, client)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
