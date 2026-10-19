"""Isolated administration API for warehouse-owned vehicles and trailers."""

from __future__ import annotations

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Header, Query, Response, status
from sqlalchemy import select

from app.api.dependencies import (
    CapacityRwmsClientDep,
    CurrentAdminDep,
    SessionDep,
    SettingsDep,
)
from app.errors import ApiError, not_found
from app.models import Trailer, Vehicle, Warehouse
from app.schemas.domain import (
    AdminCatalogRelocationRequest,
    AdminTrailerRead,
    AdminVehicleRead,
    RwmsWarehouseIdentity,
    TrailerCreate,
    TrailerRead,
    TrailerUpdate,
    VehicleConfigurationCreate,
    VehicleConfigurationUpdate,
    VehicleRead,
    VehicleUpdate,
)
from app.services import catalog as service
from app.services.capacity_mutations import publish_capacity_after_mutation
from app.services.catalog_command_idempotency import execute_idempotent_create

router = APIRouter(tags=["admin catalog"])
IdempotencyKey = Annotated[
    str,
    Header(
        alias="Idempotency-Key",
        min_length=1,
        max_length=200,
        pattern=r"^[\x21-\x7e]+$",
    ),
]


async def _warehouse_directory(
    client: CapacityRwmsClientDep,
) -> dict[UUID, RwmsWarehouseIdentity]:
    """Index the authoritative active warehouse directory for one admin request."""

    return {identity.warehouse_id: identity for identity in await client.list_warehouses()}


def _require_canonical_warehouse(
    directory: dict[UUID, RwmsWarehouseIdentity],
    warehouse_id: UUID,
    *,
    require_eligible: bool,
) -> RwmsWarehouseIdentity:
    """Require one active canonical object and its production/main classification."""

    identity = directory.get(warehouse_id)
    if identity is None:
        raise ApiError(
            404,
            "CANONICAL_WAREHOUSE_NOT_FOUND",
            "The warehouse is not present in the active RWMS directory",
            extra={"warehouse_id": str(warehouse_id)},
        )
    if require_eligible and identity.representative:
        raise ApiError(
            422,
            "WAREHOUSE_NOT_ELIGIBLE_FOR_CATALOG",
            "Vehicles and trailers can belong only to a production or main warehouse",
            extra={"warehouse_id": str(warehouse_id)},
        )
    return identity


async def _require_local_warehouse(
    session: SessionDep,
    external_warehouse_id: UUID,
    *,
    relocation_target: bool = False,
) -> Warehouse:
    """Resolve a canonical UUID to the planner-local persistence root."""

    warehouse = await session.scalar(
        select(Warehouse).where(Warehouse.external_warehouse_id == external_warehouse_id)
    )
    if warehouse is not None:
        return warehouse
    if relocation_target:
        raise ApiError(
            409,
            "WAREHOUSE_NOT_CONFIGURED_FOR_LOGISTICS",
            "Configure the target warehouse in logistics before relocating catalog resources",
            extra={"warehouse_id": str(external_warehouse_id)},
        )
    raise ApiError(
        404,
        "WAREHOUSE_NOT_CONFIGURED_FOR_LOGISTICS",
        "The warehouse is not configured in logistics",
        extra={"warehouse_id": str(external_warehouse_id)},
    )


async def _require_catalog_warehouse(
    session: SessionDep,
    client: CapacityRwmsClientDep,
    external_warehouse_id: UUID,
) -> Warehouse:
    """Require one active eligible canonical warehouse with a local catalog root."""

    directory = await _warehouse_directory(client)
    _require_canonical_warehouse(directory, external_warehouse_id, require_eligible=True)
    return await _require_local_warehouse(session, external_warehouse_id)


def _vehicle_read(entity: Vehicle, canonical_warehouse_id: UUID) -> AdminVehicleRead:
    """Replace the private persistence owner with the canonical warehouse UUID."""

    payload = VehicleRead.model_validate(entity).model_dump()
    payload["warehouse_id"] = canonical_warehouse_id
    return AdminVehicleRead.model_validate(payload)


def _trailer_read(entity: Trailer, canonical_warehouse_id: UUID) -> AdminTrailerRead:
    """Replace the private persistence owner with the canonical warehouse UUID."""

    payload = TrailerRead.model_validate(entity).model_dump()
    payload["warehouse_id"] = canonical_warehouse_id
    return AdminTrailerRead.model_validate(payload)


async def _publish_catalog_capacity(
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    *warehouse_ids: UUID,
) -> None:
    """Advance and publish capacity for every distinct side changed by a command."""

    for warehouse_id in dict.fromkeys(warehouse_ids):
        await publish_capacity_after_mutation(session, warehouse_id, settings, client)


async def _vehicle_owner(
    session: SessionDep,
    client: CapacityRwmsClientDep,
    vehicle_id: UUID,
    *,
    require_eligible: bool,
) -> tuple[Vehicle, Warehouse]:
    """Resolve a vehicle and validate its current canonical owner."""

    entity = await service.get_vehicle(session, vehicle_id)
    warehouse = await service.require_warehouse(session, entity.warehouse_id)
    directory = await _warehouse_directory(client)
    _require_canonical_warehouse(
        directory,
        warehouse.external_warehouse_id,
        require_eligible=require_eligible,
    )
    return entity, warehouse


async def _trailer_owner(
    session: SessionDep,
    client: CapacityRwmsClientDep,
    trailer_id: UUID,
    *,
    require_eligible: bool,
) -> tuple[Trailer, Warehouse]:
    """Resolve a trailer and validate its current canonical owner."""

    entity = await session.get(Trailer, trailer_id)
    if entity is None:
        raise not_found("trailer", trailer_id)
    warehouse = await service.require_warehouse(session, entity.warehouse_id)
    directory = await _warehouse_directory(client)
    _require_canonical_warehouse(
        directory,
        warehouse.external_warehouse_id,
        require_eligible=require_eligible,
    )
    return entity, warehouse


@router.get(
    "/warehouses/{warehouse_id}/vehicles",
    response_model=list[AdminVehicleRead],
)
async def admin_list_vehicles(
    warehouse_id: UUID,
    session: SessionDep,
    client: CapacityRwmsClientDep,
) -> list[AdminVehicleRead]:
    """List vehicles for one canonical production/main warehouse UUID."""

    warehouse = await _require_catalog_warehouse(session, client, warehouse_id)
    return [
        _vehicle_read(entity, warehouse_id)
        for entity in await service.list_vehicles(session, warehouse.id)
    ]


@router.post(
    "/warehouses/{warehouse_id}/vehicle-configurations",
    response_model=AdminVehicleRead,
    status_code=status.HTTP_201_CREATED,
)
async def admin_create_vehicle_configuration(
    warehouse_id: UUID,
    payload: VehicleConfigurationCreate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentAdminDep,
    idempotency_key: IdempotencyKey,
) -> AdminVehicleRead:
    """Create a complete vehicle under a canonical warehouse UUID."""

    warehouse = await _require_catalog_warehouse(session, client, warehouse_id)
    outcome = await execute_idempotent_create(
        session,
        operation="admin_create_vehicle_configuration",
        actor_id=principal.subject_id,
        idempotency_key=idempotency_key,
        payload={
            "warehouse_id": str(warehouse_id),
            "payload": payload.model_dump(mode="json"),
        },
        resource_type="vehicle",
        create=lambda: service.create_vehicle_configuration(session, warehouse.id, payload),
        load=lambda resource_id: service.get_vehicle(session, resource_id),
    )
    if not outcome.replayed:
        await _publish_catalog_capacity(session, settings, client, warehouse.id)
    return _vehicle_read(outcome.resource, warehouse_id)


@router.patch("/vehicles/{vehicle_id}", response_model=AdminVehicleRead)
async def admin_update_vehicle(
    vehicle_id: UUID,
    payload: VehicleUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> AdminVehicleRead:
    """Update a vehicle owned by an eligible canonical warehouse."""

    _, warehouse = await _vehicle_owner(
        session,
        client,
        vehicle_id,
        require_eligible=True,
    )
    entity = await service.update_vehicle(session, vehicle_id, payload)
    await _publish_catalog_capacity(session, settings, client, warehouse.id)
    return _vehicle_read(entity, warehouse.external_warehouse_id)


@router.put("/vehicles/{vehicle_id}/configuration", response_model=AdminVehicleRead)
async def admin_update_vehicle_configuration(
    vehicle_id: UUID,
    payload: VehicleConfigurationUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> AdminVehicleRead:
    """Atomically replace a vehicle and its axle profiles through the admin client."""

    _, warehouse = await _vehicle_owner(
        session,
        client,
        vehicle_id,
        require_eligible=True,
    )
    entity = await service.update_vehicle_configuration(session, vehicle_id, payload)
    await _publish_catalog_capacity(session, settings, client, warehouse.id)
    return _vehicle_read(entity, warehouse.external_warehouse_id)


@router.delete("/vehicles/{vehicle_id}", status_code=status.HTTP_204_NO_CONTENT)
async def admin_delete_vehicle(
    vehicle_id: UUID,
    expected_version: Annotated[int, Query(ge=1)],
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Response:
    """Delete an eligible vehicle only when no driver shift retains it."""

    _, warehouse = await _vehicle_owner(
        session,
        client,
        vehicle_id,
        require_eligible=True,
    )
    await service.delete_vehicle(session, vehicle_id, expected_version)
    await _publish_catalog_capacity(session, settings, client, warehouse.id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/vehicles/{vehicle_id}/relocate", response_model=AdminVehicleRead)
async def admin_relocate_vehicle(
    vehicle_id: UUID,
    payload: AdminCatalogRelocationRequest,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> AdminVehicleRead:
    """Permanently relocate a vehicle to a canonical production/main warehouse."""

    entity = await service.get_vehicle(session, vehicle_id)
    source = await service.require_warehouse(session, entity.warehouse_id)
    directory = await _warehouse_directory(client)
    _require_canonical_warehouse(
        directory,
        source.external_warehouse_id,
        require_eligible=False,
    )
    _require_canonical_warehouse(
        directory,
        payload.target_warehouse_id,
        require_eligible=True,
    )
    target = await _require_local_warehouse(
        session,
        payload.target_warehouse_id,
        relocation_target=True,
    )
    relocated = await service.relocate_vehicle(
        session,
        vehicle_id,
        target.id,
        payload.expected_version,
    )
    await _publish_catalog_capacity(session, settings, client, source.id, target.id)
    return _vehicle_read(relocated, target.external_warehouse_id)


@router.get(
    "/warehouses/{warehouse_id}/trailers",
    response_model=list[AdminTrailerRead],
)
async def admin_list_trailers(
    warehouse_id: UUID,
    session: SessionDep,
    client: CapacityRwmsClientDep,
) -> list[AdminTrailerRead]:
    """List trailers for one canonical production/main warehouse UUID."""

    warehouse = await _require_catalog_warehouse(session, client, warehouse_id)
    entities = await service.list_catalog(session, Trailer, warehouse.id)
    return [_trailer_read(entity, warehouse_id) for entity in entities]


@router.post(
    "/warehouses/{warehouse_id}/trailers",
    response_model=AdminTrailerRead,
    status_code=status.HTTP_201_CREATED,
)
async def admin_create_trailer(
    warehouse_id: UUID,
    payload: TrailerCreate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentAdminDep,
    idempotency_key: IdempotencyKey,
) -> AdminTrailerRead:
    """Create a trailer under a canonical warehouse UUID."""

    warehouse = await _require_catalog_warehouse(session, client, warehouse_id)
    outcome = await execute_idempotent_create(
        session,
        operation="admin_create_trailer",
        actor_id=principal.subject_id,
        idempotency_key=idempotency_key,
        payload={
            "warehouse_id": str(warehouse_id),
            "payload": payload.model_dump(mode="json"),
        },
        resource_type="trailer",
        create=lambda: service.create_trailer(session, warehouse.id, payload),
        load=lambda resource_id: session.get(Trailer, resource_id),
    )
    if not outcome.replayed:
        await _publish_catalog_capacity(session, settings, client, warehouse.id)
    return _trailer_read(outcome.resource, warehouse_id)


@router.patch("/trailers/{trailer_id}", response_model=AdminTrailerRead)
async def admin_update_trailer(
    trailer_id: UUID,
    payload: TrailerUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> AdminTrailerRead:
    """Update a trailer owned by an eligible canonical warehouse."""

    _, warehouse = await _trailer_owner(
        session,
        client,
        trailer_id,
        require_eligible=True,
    )
    entity = await service.update_trailer(session, trailer_id, payload)
    await _publish_catalog_capacity(session, settings, client, warehouse.id)
    return _trailer_read(entity, warehouse.external_warehouse_id)


@router.delete("/trailers/{trailer_id}", status_code=status.HTTP_204_NO_CONTENT)
async def admin_delete_trailer(
    trailer_id: UUID,
    expected_version: Annotated[int, Query(ge=1)],
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Response:
    """Delete an eligible trailer only when no vehicle selects it as default."""

    _, warehouse = await _trailer_owner(
        session,
        client,
        trailer_id,
        require_eligible=True,
    )
    await service.delete_trailer(session, trailer_id, expected_version)
    await _publish_catalog_capacity(session, settings, client, warehouse.id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/trailers/{trailer_id}/relocate", response_model=AdminTrailerRead)
async def admin_relocate_trailer(
    trailer_id: UUID,
    payload: AdminCatalogRelocationRequest,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> AdminTrailerRead:
    """Permanently relocate a trailer to a canonical production/main warehouse."""

    entity = await session.get(Trailer, trailer_id)
    if entity is None:
        raise not_found("trailer", trailer_id)
    source = await service.require_warehouse(session, entity.warehouse_id)
    directory = await _warehouse_directory(client)
    _require_canonical_warehouse(
        directory,
        source.external_warehouse_id,
        require_eligible=False,
    )
    _require_canonical_warehouse(
        directory,
        payload.target_warehouse_id,
        require_eligible=True,
    )
    target = await _require_local_warehouse(
        session,
        payload.target_warehouse_id,
        relocation_target=True,
    )
    relocated = await service.relocate_trailer(
        session,
        trailer_id,
        target.id,
        payload.expected_version,
    )
    await _publish_catalog_capacity(session, settings, client, source.id, target.id)
    return _trailer_read(relocated, target.external_warehouse_id)
