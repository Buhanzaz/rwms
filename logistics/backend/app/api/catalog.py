"""REST endpoints for warehouse workspaces, resources, and requests."""

import logging
from datetime import date, datetime, timedelta
from typing import Annotated
from uuid import UUID
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Query, Response, status
from sqlalchemy import select

from app.api.dependencies import (
    CapacityRwmsClientDep,
    PlannerDep,
    RoadSnapperDep,
    SessionDep,
    SettingsDep,
)
from app.api.geocoding import GeocodingClientDep
from app.api.serializers import request_read
from app.errors import ApiError
from app.integrations.rwms_sync import (
    refresh_warehouse_directory,
    sync_warehouse_requests,
)
from app.models import (
    Driver,
    DriverShift,
    LogisticsRequest,
    RequestDateOption,
    Trailer,
    Vehicle,
    Warehouse,
)
from app.repositories import get_required
from app.schemas.domain import (
    AvailableDriverRead,
    AvailableWarehouseRead,
    ContractorAssignmentCreate,
    ContractorDispatchCreate,
    ContractorDispatchRead,
    DriverCreate,
    DriverRead,
    DriverUpdate,
    LogisticsRequestCreate,
    LogisticsRequestRead,
    LogisticsRequestUpdate,
    RequestDateOptionInput,
    RequestDateOptionRead,
    RequestDateOptionUpdate,
    RequestPlanningDetailsInput,
    RequestScheduleInput,
    RequestTaskSplitInput,
    RwmsSyncRequest,
    RwmsSyncResult,
    RwmsWarehouseIdentity,
    ShiftCreate,
    ShiftRead,
    ShiftUpdate,
    TrailerCreate,
    TrailerRead,
    TrailerUpdate,
    VehicleConfigurationCreate,
    VehicleConfigurationUpdate,
    VehicleRead,
    VehicleUpdate,
    WarehouseCreate,
    WarehouseRead,
    WarehouseUpdate,
    WarehouseWorkspaceRead,
    WorkloadDeletionResult,
    WorkloadGenerationResult,
    WorkloadGeneratorInput,
)
from app.services import catalog as service
from app.services.auto_planning import (
    generate_missing_draft_plans,
    invalidate_mutable_group_root_plans,
)
from app.services.capacity_mutations import publish_capacity_after_mutation
from app.services.capacity_projection import publish_warehouse_capacity
from app.services.contractor_assignment import (
    assign_request_to_contractor,
    dispatch_requests_to_contractor,
)
from app.services.planning_group import resolve_planning_warehouse_group
from app.services.workload_generator import (
    GENERATOR_SOURCE_SYSTEM,
    delete_generated_workload,
    generate_warehouse_workload,
)

router = APIRouter(tags=["catalog"])
logger = logging.getLogger(__name__)


async def _publish_anonymous_test_capacity(
    session: SessionDep,
    warehouse_id: UUID,
    client: CapacityRwmsClientDep,
) -> tuple[str, str | None]:
    """Publish generated anonymous capacity without rolling back the committed simulator state."""

    try:
        await publish_warehouse_capacity(session, warehouse_id, client)
    except Exception as exc:
        logger.exception(
            "Anonymous test-capacity projection failed for warehouse %s",
            warehouse_id,
        )
        code = exc.code if isinstance(exc, ApiError) else type(exc).__name__
        return (
            "FAILED",
            (
                "Тестовая нагрузка сохранена локально, но анонимная проекция "
                f"мощности не опубликована ({code})."
            ),
        )
    return "PUBLISHED", None


def _warehouse_geocoding_query(identity: RwmsWarehouseIdentity) -> str:
    """Qualify a canonical warehouse address with its city for forward geocoding."""

    address = (identity.address or "").strip()
    if not address:
        raise ApiError(
            422,
            "WAREHOUSE_COORDINATES_REQUIRED",
            "Не заданы координаты для использования склада в логистике",  # noqa: RUF001
        )
    city = identity.city.strip()
    if city and city.casefold() not in address.casefold():
        return f"{city}, {address}"
    return address


async def _publish_resource_capacity(
    session: SessionDep,
    warehouse_id: UUID,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> None:
    """Delegate one catalog mutation to the shared capacity transaction boundary."""

    await publish_capacity_after_mutation(session, warehouse_id, settings, client)


async def _publish_generated_request_capacity(
    session: SessionDep,
    request: LogisticsRequest,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> None:
    """Publish only mutations that alter generator-owned anonymous capacity facts."""

    if request.source_system == GENERATOR_SOURCE_SYSTEM:
        await publish_capacity_after_mutation(session, request.warehouse_id, settings, client)


@router.get("/warehouses", response_model=list[WarehouseRead])
async def list_warehouses(
    session: SessionDep,
    client: CapacityRwmsClientDep,
    geocoder: GeocodingClientDep,
) -> list[Warehouse]:
    """Reconcile and list routing-ready canonical RWMS warehouse workspaces."""

    return await refresh_warehouse_directory(
        session,
        client,
        lambda identity: geocoder.forward(_warehouse_geocoding_query(identity)),
    )


@router.get("/warehouses/available", response_model=list[AvailableWarehouseRead])
async def list_available_warehouses(
    session: SessionDep,
    client: CapacityRwmsClientDep,
    geocoder: GeocodingClientDep,
) -> list[AvailableWarehouseRead]:
    """List authoritative RWMS warehouse candidates and their local binding state."""

    identities = await client.list_warehouses()
    await service.reconcile_warehouse_directory(
        session,
        identities,
        lambda identity: geocoder.forward(_warehouse_geocoding_query(identity)),
    )
    local_by_external = {
        warehouse.external_warehouse_id: warehouse
        for warehouse in await session.scalars(select(Warehouse))
    }
    return [
        AvailableWarehouseRead(
            warehouse_id=identity.warehouse_id,
            warehouse_version=identity.warehouse_version,
            name=identity.name,
            city=identity.city,
            address=identity.address,
            latitude=(
                local_by_external[identity.warehouse_id].latitude
                if identity.warehouse_id in local_by_external
                and local_by_external[identity.warehouse_id].routing_ready
                else identity.latitude
            ),
            longitude=(
                local_by_external[identity.warehouse_id].longitude
                if identity.warehouse_id in local_by_external
                and local_by_external[identity.warehouse_id].routing_ready
                else identity.longitude
            ),
            timezone=identity.timezone,
            representative=identity.representative,
            routing_ready=(
                local_by_external[identity.warehouse_id].routing_ready
                if identity.warehouse_id in local_by_external
                else identity.routing_ready
            ),
            routing_unavailable_reason=(
                None
                if (
                    local_by_external[identity.warehouse_id].routing_ready
                    if identity.warehouse_id in local_by_external
                    else identity.routing_ready
                )
                else "Не заданы координаты для использования склада в логистике"  # noqa: RUF001
            ),
            local_warehouse_id=(
                local_by_external[identity.warehouse_id].id
                if identity.warehouse_id in local_by_external
                else None
            ),
        )
        for identity in identities
    ]


@router.post(
    "/warehouses",
    response_model=WarehouseRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_warehouse(
    payload: WarehouseCreate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    geocoder: GeocodingClientDep,
) -> Warehouse:
    """Bind one RWMS identity, resolving its canonical address when coordinates are absent."""

    identity = next(
        (
            candidate
            for candidate in await client.list_warehouses()
            if candidate.warehouse_id == payload.external_warehouse_id
        ),
        None,
    )
    if identity is None:
        raise ApiError(422, "RWMS_WAREHOUSE_NOT_FOUND", "RWMS warehouse is not available")
    resolved = None
    if not identity.routing_ready:
        resolved = await geocoder.forward(_warehouse_geocoding_query(identity))
    entity = await service.create_warehouse(session, payload, identity, resolved)
    await _publish_resource_capacity(session, entity.id, settings, client)
    await session.refresh(entity)
    return entity


@router.post(
    "/warehouses/{warehouse_id}/generate-workload",
    response_model=WorkloadGenerationResult,
    status_code=status.HTTP_201_CREATED,
)
async def generate_workload(
    warehouse_id: UUID,
    payload: WorkloadGeneratorInput,
    session: SessionDep,
    snapper: RoadSnapperDep,
    planner: PlannerDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> WorkloadGenerationResult:
    """Replace deterministic load for one warehouse and rebuild missing draft plans."""

    result = await generate_warehouse_workload(session, warehouse_id, payload, snapper)
    runs = await generate_missing_draft_plans(
        session,
        planner,
        warehouse_id,
        (result.start_date + timedelta(days=offset) for offset in range(payload.days)),
    )
    result = result.model_copy(
        update={
            "auto_plan_run_ids": [run.id for run in runs],
            "auto_plan_ids": [run.plan_id for run in runs if run.plan_id is not None],
        }
    )
    if settings.rwms_capacity_publish_enabled:
        await session.commit()
        projection_status, projection_warning = await _publish_anonymous_test_capacity(
            session,
            warehouse_id,
            client,
        )
        result = result.model_copy(
            update={
                "capacity_projection_status": projection_status,
                "capacity_projection_warning": projection_warning,
            }
        )
    return result


@router.delete(
    "/warehouses/{warehouse_id}/generated-workload",
    response_model=WorkloadDeletionResult,
)
async def delete_workload(
    warehouse_id: UUID,
    target_date: Annotated[date, Query(alias="date")],
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> WorkloadDeletionResult:
    """Delete generated workload and plans for one exact warehouse date."""

    result = await delete_generated_workload(session, warehouse_id, target_date)
    if settings.rwms_capacity_publish_enabled and result.deleted_requests > 0:
        await session.commit()
        projection_status, projection_warning = await _publish_anonymous_test_capacity(
            session,
            warehouse_id,
            client,
        )
        result = result.model_copy(
            update={
                "capacity_projection_status": projection_status,
                "capacity_projection_warning": projection_warning,
            }
        )
    return result


@router.get("/warehouses/{warehouse_id}", response_model=WarehouseRead)
async def get_warehouse(warehouse_id: UUID, session: SessionDep) -> Warehouse:
    """Read a depot by UUID."""

    return await get_required(session, Warehouse, warehouse_id, "warehouse")


@router.get("/warehouses/{warehouse_id}/workspace", response_model=WarehouseWorkspaceRead)
async def get_warehouse_workspace(
    warehouse_id: UUID,
    session: SessionDep,
    planner: PlannerDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    refresh_rwms: Annotated[bool, Query()] = True,
) -> WarehouseWorkspaceRead:
    """Refresh RWMS demand by default, or read persisted state for explicit recovery."""

    warehouse = await service.require_warehouse(session, warehouse_id)
    date_from = datetime.now(ZoneInfo(warehouse.timezone)).date()
    if refresh_rwms and settings.rwms_sync_enabled:
        await refresh_warehouse_directory(session, client)
        warehouse = await service.require_warehouse(session, warehouse_id)
        planning_group = await resolve_planning_warehouse_group(
            session,
            client,
            warehouse,
        )
        planning_dates = tuple(date_from + timedelta(days=offset) for offset in range(31))
        refresh_results: list[tuple[UUID, RwmsSyncResult]] = []
        for member in planning_group.members:
            result = await sync_warehouse_requests(
                session,
                member.id,
                RwmsSyncRequest(
                    warehouse_id=member.external_warehouse_id,
                    date_from=date_from,
                    date_to=date_from + timedelta(days=30),
                ),
                client,
                None,
            )
            refresh_results.append(
                (
                    member.external_warehouse_id,
                    result,
                )
            )
        failures = [
            {
                "warehouse_id": str(external_warehouse_id),
                **failure.model_dump(mode="json"),
            }
            for external_warehouse_id, result in refresh_results
            for failure in result.failures
        ]
        if failures:
            await session.commit()
            raise ApiError(
                422,
                "RWMS_WORKSPACE_SYNC_INCOMPLETE",
                "RWMS workspace refresh contains orders that could not be synchronized",
                extra={
                    "date_from": date_from.isoformat(),
                    "date_to": (date_from + timedelta(days=30)).isoformat(),
                    "failures": failures,
                },
            )
        if len(planning_group.members) > 1 and any(
            result.imported > 0 or result.updated > 0
            for _, result in refresh_results
        ):
            await invalidate_mutable_group_root_plans(
                session,
                planning_group.root.id,
                planning_dates,
            )
        await generate_missing_draft_plans(
            session,
            planner,
            planning_group.root.id,
            planning_dates,
            request_warehouse_ids=(
                member.id for member in planning_group.members
            ),
        )
        warehouse = await service.require_warehouse(session, warehouse_id)
    planning_group = await resolve_planning_warehouse_group(
        session,
        client if settings.rwms_sync_enabled else None,
        warehouse,
    )
    warehouses = list(
        await session.scalars(
            select(Warehouse)
            .where(Warehouse.routing_ready.is_(True))
            .order_by(Warehouse.name)
        )
    )
    request_entities = [
        request
        for member in planning_group.members
        for request in await service.list_requests(session, member.id)
    ]
    requests = sorted(request_entities, key=lambda item: (item.created_at, item.id))
    return WarehouseWorkspaceRead(
        warehouse=WarehouseRead.model_validate(warehouse),
        planning_root_warehouse_id=planning_group.root.id,
        planning_group_warehouse_ids=[member.id for member in planning_group.members],
        warehouses=[WarehouseRead.model_validate(item) for item in warehouses],
        drivers=[
            DriverRead.model_validate(item)
            for item in await service.list_catalog(
                session,
                Driver,
                planning_group.root.id,
            )
        ],
        vehicles=[
            VehicleRead.model_validate(item)
            for item in await service.list_vehicles(session, planning_group.root.id)
        ],
        trailers=[
            TrailerRead.model_validate(item)
            for item in await service.list_catalog(
                session,
                Trailer,
                planning_group.root.id,
            )
        ],
        shifts=[
            ShiftRead.model_validate(item)
            for item in await service.list_catalog(
                session,
                DriverShift,
                planning_group.root.id,
            )
        ],
        requests=[await request_read(session, item) for item in requests],
    )


@router.patch("/warehouses/{warehouse_id}", response_model=WarehouseRead)
async def update_warehouse(
    warehouse_id: UUID,
    payload: WarehouseUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Warehouse:
    """Update a depot."""

    entity = await service.update_warehouse(session, warehouse_id, payload)
    await _publish_resource_capacity(session, entity.id, settings, client)
    return entity


@router.get(
    "/warehouses/{warehouse_id}/available-drivers",
    response_model=list[AvailableDriverRead],
)
async def list_available_drivers(
    warehouse_id: UUID,
    session: SessionDep,
    client: CapacityRwmsClientDep,
) -> list[AvailableDriverRead]:
    """List canonical RWMS workers eligible for exact-driver assignment."""

    warehouse = await service.require_warehouse(session, warehouse_id)
    return [
        AvailableDriverRead(worker_id=item.worker_id, display_name=item.display_name)
        for item in await client.list_drivers(warehouse.external_warehouse_id)
    ]


@router.post("/warehouses/{warehouse_id}/drivers", response_model=DriverRead, status_code=201)
async def create_driver(
    warehouse_id: UUID,
    payload: DriverCreate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Driver:
    """Create a warehouse driver."""

    warehouse = await service.require_warehouse(session, warehouse_id)
    identity = None
    if payload.external_worker_id is not None:
        identity = next(
            (
                item
                for item in await client.list_drivers(warehouse.external_warehouse_id)
                if item.worker_id == payload.external_worker_id
            ),
            None,
        )
    entity = await service.create_driver(session, warehouse_id, payload, identity)
    await _publish_resource_capacity(session, warehouse_id, settings, client)
    return entity


@router.patch("/drivers/{driver_id}", response_model=DriverRead)
async def update_driver(
    driver_id: UUID,
    payload: DriverUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Driver:
    """Update a driver."""

    driver = await get_required(session, Driver, driver_id, "driver")
    mode = payload.rwms_assignment_mode or driver.rwms_assignment_mode
    worker_id = (
        payload.external_worker_id
        if "external_worker_id" in payload.model_fields_set
        else driver.external_worker_id
    )
    identity = None
    if mode == "ASSIGNED_DRIVER" and worker_id is not None:
        warehouse = await service.require_warehouse(session, driver.warehouse_id)
        identity = next(
            (
                item
                for item in await client.list_drivers(warehouse.external_warehouse_id)
                if item.worker_id == worker_id
            ),
            None,
        )
    entity = await service.update_driver(session, driver_id, payload, identity)
    await _publish_resource_capacity(session, entity.warehouse_id, settings, client)
    return entity


@router.delete("/drivers/{driver_id}", status_code=204)
async def delete_driver(
    driver_id: UUID,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Response:
    """Delete a driver not retained by plan history."""

    entity = await get_required(session, Driver, driver_id, "driver")
    warehouse_id = entity.warehouse_id
    await service.delete_catalog_entity(session, Driver, driver_id, "driver")
    await _publish_resource_capacity(session, warehouse_id, settings, client)
    return Response(status_code=204)


@router.post(
    "/warehouses/{warehouse_id}/vehicle-configurations",
    response_model=VehicleRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_vehicle_configuration(
    warehouse_id: UUID,
    payload: VehicleConfigurationCreate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Vehicle:
    """Atomically create a vehicle and its operational axle-load profiles."""

    entity = await service.create_vehicle_configuration(session, warehouse_id, payload)
    await _publish_resource_capacity(session, warehouse_id, settings, client)
    return entity


@router.patch("/vehicles/{vehicle_id}", response_model=VehicleRead)
async def update_vehicle(
    vehicle_id: UUID,
    payload: VehicleUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Vehicle:
    """Update a vehicle."""

    entity = await service.update_vehicle(session, vehicle_id, payload)
    await _publish_resource_capacity(session, entity.warehouse_id, settings, client)
    return entity


@router.put("/vehicles/{vehicle_id}/configuration", response_model=VehicleRead)
async def update_vehicle_configuration(
    vehicle_id: UUID,
    payload: VehicleConfigurationUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Vehicle:
    """Atomically replace vehicle fields and its complete axle-profile set."""

    entity = await service.update_vehicle_configuration(session, vehicle_id, payload)
    await _publish_resource_capacity(session, entity.warehouse_id, settings, client)
    return entity


@router.delete("/vehicles/{vehicle_id}", status_code=204)
async def delete_vehicle(
    vehicle_id: UUID,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Response:
    """Delete a vehicle not retained by plan history."""

    entity = await get_required(session, Vehicle, vehicle_id, "vehicle")
    warehouse_id = entity.warehouse_id
    await service.delete_catalog_entity(session, Vehicle, vehicle_id, "vehicle")
    await _publish_resource_capacity(session, warehouse_id, settings, client)
    return Response(status_code=204)


@router.post(
    "/warehouses/{warehouse_id}/trailers",
    response_model=TrailerRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_trailer(
    warehouse_id: UUID,
    payload: TrailerCreate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Trailer:
    """Create a warehouse-owned trailer."""

    entity = await service.create_trailer(session, warehouse_id, payload)
    await _publish_resource_capacity(session, warehouse_id, settings, client)
    return entity


@router.patch("/trailers/{trailer_id}", response_model=TrailerRead)
async def update_trailer(
    trailer_id: UUID,
    payload: TrailerUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Trailer:
    """Update a trailer's label, availability, or physical limits."""

    entity = await service.update_trailer(session, trailer_id, payload)
    await _publish_resource_capacity(session, entity.warehouse_id, settings, client)
    return entity


@router.delete("/trailers/{trailer_id}", status_code=204)
async def delete_trailer(
    trailer_id: UUID,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Response:
    """Delete a trailer while vehicle defaults are cleared by the database."""

    entity = await get_required(session, Trailer, trailer_id, "trailer")
    warehouse_id = entity.warehouse_id
    await service.delete_catalog_entity(session, Trailer, trailer_id, "trailer")
    await _publish_resource_capacity(session, warehouse_id, settings, client)
    return Response(status_code=204)


@router.post("/warehouses/{warehouse_id}/shifts", response_model=ShiftRead, status_code=201)
async def create_shift(
    warehouse_id: UUID,
    payload: ShiftCreate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> DriverShift:
    """Create a non-overlapping warehouse shift."""

    entity = await service.create_shift(session, warehouse_id, payload)
    await _publish_resource_capacity(session, warehouse_id, settings, client)
    return entity


@router.patch("/shifts/{shift_id}", response_model=ShiftRead)
async def update_shift(
    shift_id: UUID,
    payload: ShiftUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> DriverShift:
    """Update a shift with repeated overlap validation."""

    entity = await service.update_shift(session, shift_id, payload)
    await _publish_resource_capacity(session, entity.warehouse_id, settings, client)
    return entity


@router.delete("/shifts/{shift_id}", status_code=204)
async def delete_shift(
    shift_id: UUID,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Response:
    """Delete a shift not retained by plan history."""

    entity = await get_required(session, DriverShift, shift_id, "shift")
    warehouse_id = entity.warehouse_id
    await service.delete_catalog_entity(session, DriverShift, shift_id, "shift")
    await _publish_resource_capacity(session, warehouse_id, settings, client)
    return Response(status_code=204)


@router.post(
    "/warehouses/{warehouse_id}/requests",
    response_model=LogisticsRequestRead,
    status_code=201,
)
async def create_request(
    warehouse_id: UUID, payload: LogisticsRequestCreate, session: SessionDep
) -> LogisticsRequestRead:
    """Create, server-classify, and split a delivery or pickup request."""

    entity = await service.create_request(session, warehouse_id, payload)
    return await request_read(session, entity)


@router.get("/requests/{request_id}", response_model=LogisticsRequestRead)
async def get_request(request_id: UUID, session: SessionDep) -> LogisticsRequestRead:
    """Read one logistics request."""

    return await request_read(session, await service.get_request(session, request_id))


@router.post(
    "/requests/{request_id}/contractor-assignment",
    response_model=LogisticsRequestRead,
)
async def assign_contractor(
    request_id: UUID,
    payload: ContractorAssignmentCreate,
    session: SessionDep,
    client: CapacityRwmsClientDep,
) -> LogisticsRequestRead:
    """Hand one complete delivery to a contractor without an internal route cycle."""

    entity = await assign_request_to_contractor(session, request_id, payload, client)
    return await request_read(session, entity)


@router.post(
    "/warehouses/{warehouse_id}/contractor-dispatches",
    response_model=ContractorDispatchRead,
)
async def dispatch_contractor_requests(
    warehouse_id: UUID,
    payload: ContractorDispatchCreate,
    session: SessionDep,
    client: CapacityRwmsClientDep,
) -> ContractorDispatchRead:
    """Assign selected-day unplanned requests without contractor route optimization."""

    return await dispatch_requests_to_contractor(session, warehouse_id, payload, client)


@router.patch("/requests/{request_id}", response_model=LogisticsRequestRead)
async def update_request(
    request_id: UUID,
    payload: LogisticsRequestUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> LogisticsRequestRead:
    """Update a request and reclassify coordinate changes on the backend."""

    entity = await service.update_request(session, request_id, payload)
    await _publish_generated_request_capacity(session, entity, settings, client)
    return await request_read(session, entity)


@router.delete("/requests/{request_id}", status_code=204)
async def delete_request(
    request_id: UUID,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Response:
    """Delete one request when no saved plan references its tasks."""

    entity = await service.get_request(session, request_id)
    await service.delete_request(session, request_id)
    await _publish_generated_request_capacity(session, entity, settings, client)
    return Response(status_code=204)


@router.post("/requests/{request_id}/planning-details", response_model=LogisticsRequestRead)
async def set_request_planning_details(
    request_id: UUID,
    payload: RequestPlanningDetailsInput,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> LogisticsRequestRead:
    """Store dispatcher-owned obligation, date, window, access, and contact details."""

    entity = await service.set_request_planning_details(
        session,
        request_id,
        payload,
    )
    await _publish_generated_request_capacity(session, entity, settings, client)
    return await request_read(session, entity)


@router.post("/requests/{request_id}/split", response_model=LogisticsRequestRead)
async def split_request(
    request_id: UUID,
    session: SessionDep,
    payload: RequestTaskSplitInput | None = None,
) -> LogisticsRequestRead:
    """Regenerate automatic or explicitly-sized transport parts."""

    quantities = payload.part_quantities if payload is not None else None
    return await request_read(
        session,
        await service.split_request(session, request_id, quantities),
    )


@router.post("/requests/{request_id}/schedule", response_model=LogisticsRequestRead)
async def schedule_request(
    request_id: UUID,
    payload: RequestScheduleInput,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> LogisticsRequestRead:
    """Assign the request to one accepted or explicitly agreed date."""

    entity = await service.schedule_request(session, request_id, payload)
    await _publish_generated_request_capacity(session, entity, settings, client)
    return await request_read(session, entity)


@router.post(
    "/requests/{request_id}/date-options",
    response_model=RequestDateOptionRead,
    status_code=201,
)
async def create_date_option(
    request_id: UUID,
    payload: RequestDateOptionInput,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> RequestDateOption:
    """Add one acceptable date option."""

    entity = await service.create_date_option(session, request_id, payload)
    request = await service.get_request(session, request_id)
    await _publish_generated_request_capacity(session, request, settings, client)
    return entity


@router.patch("/request-date-options/{option_id}", response_model=RequestDateOptionRead)
async def update_date_option(
    option_id: UUID,
    payload: RequestDateOptionUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> RequestDateOption:
    """Update one acceptable request date and window."""

    entity = await service.update_date_option(session, option_id, payload)
    request = await service.get_request(session, entity.request_id)
    await _publish_generated_request_capacity(session, request, settings, client)
    return entity


@router.delete("/request-date-options/{option_id}", status_code=204)
async def delete_date_option(
    option_id: UUID,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
) -> Response:
    """Delete one acceptable request date."""

    entity = await get_required(session, RequestDateOption, option_id, "request_date_option")
    request = await service.get_request(session, entity.request_id)
    await service.delete_date_option(session, option_id)
    await _publish_generated_request_capacity(session, request, settings, client)
    return Response(status_code=204)
