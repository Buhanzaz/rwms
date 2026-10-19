"""REST endpoints for warehouse workspaces, resources, and requests."""

import logging
from datetime import date, datetime, time, timedelta
from typing import Annotated
from uuid import UUID
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Header, Query, Response, status
from sqlalchemy import select

from app.api.authorization import (
    filter_authorized_warehouses,
    require_external_warehouse_access,
    require_local_warehouse_access,
    require_local_warehouse_set_access,
    require_owned_entity_access,
    require_request_date_option_access,
)
from app.api.dependencies import (
    CapacityRwmsClientDep,
    CurrentUserDep,
    PlannerDep,
    RoadSnapperDep,
    SessionDep,
    SettingsDep,
)
from app.api.geocoding import GeocodingClientDep
from app.api.serializers import request_read
from app.errors import ApiError
from app.integrations.rwms_sync import warehouse_geocoding_query
from app.models import (
    Driver,
    DriverShift,
    LogisticsRequest,
    RequestDateOption,
    Trailer,
    Vehicle,
    Warehouse,
)
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
from app.security import WarehouseAccessLevel
from app.services import catalog as service
from app.services.auto_planning import generate_missing_draft_plans
from app.services.capacity_mutations import publish_capacity_after_mutation
from app.services.capacity_projection import publish_warehouse_capacity
from app.services.catalog_command_idempotency import execute_idempotent_create
from app.services.contractor_assignment import (
    assign_request_to_contractor,
    dispatch_requests_to_contractor,
)
from app.services.planning_group import resolve_planning_warehouse_group
from app.services.vehicle_availability import (
    VehicleAssignmentDataError,
    VehicleAvailabilityPolicy,
    covering_window,
    load_vehicle_availability,
    local_shift_interval,
    recurring_shift_intervals,
)
from app.services.workload_generator import (
    GENERATOR_SOURCE_SYSTEM,
    delete_generated_workload,
    generate_warehouse_workload,
)

router = APIRouter(tags=["catalog"])
logger = logging.getLogger(__name__)
IdempotencyKey = Annotated[
    str,
    Header(
        alias="Idempotency-Key",
        min_length=1,
        max_length=200,
        pattern=r"^[\x21-\x7e]+$",
    ),
]


async def _load_vehicle_policy(
    client: CapacityRwmsClientDep,
    warehouse_ids: tuple[UUID, ...],
    intervals: tuple[tuple[datetime, datetime], ...],
) -> VehicleAvailabilityPolicy:
    """Load a bounded operational snapshot and normalize merge conflicts as upstream errors."""

    window_start, window_end = covering_window(intervals)
    try:
        return await load_vehicle_availability(
            client,
            warehouse_ids,
            window_start=window_start,
            window_end=window_end,
        )
    except VehicleAssignmentDataError as exc:
        raise ApiError(
            502,
            "RWMS_VEHICLE_ASSIGNMENT_RESPONSE_INVALID",
            "RWMS logistics-service vehicle assignment response is invalid",
        ) from exc


async def _load_shift_vehicle_policy(
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    warehouse_id: UUID,
    *,
    date_from: date,
    date_to: date,
    start_time: time,
    end_time: time,
) -> VehicleAvailabilityPolicy | None:
    """Read assignment facts covering every occurrence of one shift command."""

    if not settings.rwms_sync_enabled:
        return None
    warehouse = await service.require_warehouse(session, warehouse_id)
    intervals = recurring_shift_intervals(
        date_from,
        date_to,
        start_time,
        end_time,
        ZoneInfo(warehouse.timezone),
    )
    return await _load_vehicle_policy(
        client,
        (warehouse.external_warehouse_id,),
        intervals,
    )


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
    principal: CurrentUserDep,
) -> list[Warehouse]:
    """List persisted routing-ready workspaces without reconciling external state."""

    warehouses = list(
        await session.scalars(
            select(Warehouse)
            .where(Warehouse.routing_ready.is_(True))
            .order_by(Warehouse.name, Warehouse.id)
        )
    )
    return filter_authorized_warehouses(principal, warehouses)


@router.get("/warehouses/available", response_model=list[AvailableWarehouseRead])
async def list_available_warehouses(
    session: SessionDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> list[AvailableWarehouseRead]:
    """Join authoritative candidates to persisted bindings without mutating either source."""

    identities = await client.list_warehouses()
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
        if principal.can_access(identity.warehouse_id, WarehouseAccessLevel.VIEW)
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
    principal: CurrentUserDep,
    idempotency_key: IdempotencyKey,
) -> Warehouse:
    """Bind one RWMS identity, resolving its canonical address when coordinates are absent."""

    require_external_warehouse_access(
        principal,
        payload.external_warehouse_id,
        WarehouseAccessLevel.MANAGE,
    )
    async def create() -> Warehouse:
        """Resolve the authoritative identity only for the first command execution."""

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
            resolved = await geocoder.forward(warehouse_geocoding_query(identity))
        return await service.create_warehouse(session, payload, identity, resolved)

    outcome = await execute_idempotent_create(
        session,
        operation="create_warehouse",
        actor_id=principal.subject_id,
        idempotency_key=idempotency_key,
        payload={"payload": payload.model_dump(mode="json")},
        resource_type="warehouse",
        create=create,
        load=lambda resource_id: session.get(Warehouse, resource_id),
    )
    if not outcome.replayed:
        await _publish_resource_capacity(session, outcome.resource.id, settings, client)
    await session.refresh(outcome.resource)
    return outcome.resource


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
    principal: CurrentUserDep,
) -> WorkloadGenerationResult:
    """Replace random test load for one warehouse and rebuild missing draft plans."""

    await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.EDIT
    )
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
    principal: CurrentUserDep,
) -> WorkloadDeletionResult:
    """Delete generated workload and plans for one exact warehouse date."""

    await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.EDIT
    )
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
async def get_warehouse(
    warehouse_id: UUID, session: SessionDep, principal: CurrentUserDep
) -> Warehouse:
    """Read a depot by UUID."""

    return await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.VIEW
    )


@router.get("/warehouses/{warehouse_id}/workspace", response_model=WarehouseWorkspaceRead)
async def get_warehouse_workspace(
    warehouse_id: UUID,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
    planning_date: Annotated[date | None, Query()] = None,
    request_limit: Annotated[int, Query(ge=1, le=1000)] = 250,
    request_cursor: Annotated[UUID | None, Query()] = None,
) -> WarehouseWorkspaceRead:
    """Read one bounded persisted planning-date projection without synchronizing demand."""

    warehouse = await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.VIEW
    )
    effective_planning_date = planning_date or datetime.now(
        ZoneInfo(warehouse.timezone)
    ).date()
    planning_group = await resolve_planning_warehouse_group(
        session,
        client if settings.rwms_sync_enabled else None,
        warehouse,
        planning_date=effective_planning_date,
    )
    member_ids = tuple(member.id for member in planning_group.members)
    await require_local_warehouse_set_access(
        session,
        principal,
        member_ids,
        WarehouseAccessLevel.VIEW,
    )
    member_by_id = {member.id: member for member in planning_group.members}
    member_by_external_id = {
        member.external_warehouse_id: member for member in planning_group.members
    }
    vehicle_policy: VehicleAvailabilityPolicy | None = None
    projection_at = datetime.combine(
        effective_planning_date,
        warehouse.working_day_start,
        tzinfo=ZoneInfo(warehouse.timezone),
    )
    if settings.rwms_sync_enabled:
        day_intervals = tuple(
            (
                datetime.combine(
                    effective_planning_date,
                    time.min,
                    tzinfo=ZoneInfo(member.timezone),
                ),
                datetime.combine(
                    effective_planning_date + timedelta(days=1),
                    time.min,
                    tzinfo=ZoneInfo(member.timezone),
                ),
            )
            for member in planning_group.members
        )
        vehicle_policy = await _load_vehicle_policy(
            client,
            tuple(member.external_warehouse_id for member in planning_group.members),
            day_intervals,
        )
    warehouses = list(
        await session.scalars(
            select(Warehouse)
            .where(Warehouse.routing_ready.is_(True))
            .order_by(Warehouse.name)
        )
    )
    warehouses = filter_authorized_warehouses(principal, warehouses)
    requests, request_total, request_next_cursor = await service.list_requests_page(
        session,
        member_ids,
        effective_planning_date,
        limit=request_limit,
        cursor=request_cursor,
    )
    vehicle_entities = await service.list_vehicles_for_warehouses(
        session,
        member_ids,
        additional_vehicle_ids=(
            tuple(vehicle_policy.vehicle_ids) if vehicle_policy is not None else ()
        ),
    )
    home_warehouse_ids = tuple(
        dict.fromkeys(item.warehouse_id for item in vehicle_entities)
    )
    home_external_by_local_id = {
        item.id: item.external_warehouse_id
        for item in await session.scalars(
            select(Warehouse).where(Warehouse.id.in_(home_warehouse_ids))
        )
    }
    workspace_vehicles: list[VehicleRead] = []
    for vehicle in vehicle_entities:
        vehicle_read = VehicleRead.model_validate(vehicle)
        if vehicle_policy is not None:
            home_external_id = home_external_by_local_id.get(vehicle.warehouse_id)
            if home_external_id is None:
                continue
            placement = vehicle_policy.placement_at(
                vehicle.id,
                home_external_id,
                projection_at,
            )
            if placement.warehouse_id is None:
                continue
            effective_warehouse = member_by_external_id.get(placement.warehouse_id)
            if effective_warehouse is None:
                continue
            vehicle_read = vehicle_read.model_copy(
                update={"warehouse_id": effective_warehouse.id}
            )
        workspace_vehicles.append(vehicle_read)

    shift_entities = await service.list_catalog_for_warehouses(
        session,
        DriverShift,
        member_ids,
    )
    vehicle_by_id = {item.id: item for item in vehicle_entities}
    workspace_shifts: list[ShiftRead] = []
    for shift in shift_entities:
        if (
            vehicle_policy is not None
            and shift.active
            and shift.date_from <= effective_planning_date <= shift.date_to
        ):
            target = member_by_id.get(shift.warehouse_id)
            shift_vehicle = vehicle_by_id.get(shift.vehicle_id)
            home_external_id = (
                home_external_by_local_id.get(shift_vehicle.warehouse_id)
                if shift_vehicle is not None
                else None
            )
            if target is None or shift_vehicle is None or home_external_id is None:
                continue
            shift_start, shift_end = local_shift_interval(
                effective_planning_date,
                shift.start_time,
                shift.end_time,
                ZoneInfo(target.timezone),
            )
            if not vehicle_policy.available_for_interval(
                shift.vehicle_id,
                home_external_id,
                target.external_warehouse_id,
                shift_start,
                shift_end,
            ):
                continue
        workspace_shifts.append(ShiftRead.model_validate(shift))
    return WarehouseWorkspaceRead(
        warehouse=WarehouseRead.model_validate(warehouse),
        planning_date=effective_planning_date,
        planning_root_warehouse_id=planning_group.root.id,
        planning_group_warehouse_ids=list(member_ids),
        warehouses=[WarehouseRead.model_validate(item) for item in warehouses],
        drivers=[
            DriverRead.model_validate(item)
            for item in await service.list_catalog_for_warehouses(
                session,
                Driver,
                member_ids,
            )
        ],
        vehicles=workspace_vehicles,
        trailers=[
            TrailerRead.model_validate(item)
            for item in await service.list_catalog_for_warehouses(
                session,
                Trailer,
                member_ids,
            )
        ],
        shifts=workspace_shifts,
        requests=[await request_read(session, item) for item in requests],
        request_total=request_total,
        request_next_cursor=request_next_cursor,
    )


@router.patch("/warehouses/{warehouse_id}", response_model=WarehouseRead)
async def update_warehouse(
    warehouse_id: UUID,
    payload: WarehouseUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> Warehouse:
    """Update a depot."""

    await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.MANAGE
    )
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
    principal: CurrentUserDep,
) -> list[AvailableDriverRead]:
    """List canonical RWMS workers eligible for exact-driver assignment."""

    warehouse = await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.VIEW
    )
    return [
        AvailableDriverRead(worker_id=item.worker_id, display_name=item.display_name)
        for item in await client.list_drivers(warehouse.external_warehouse_id)
        if item.employment_type == "STAFF"
    ]


@router.post("/warehouses/{warehouse_id}/drivers", response_model=DriverRead, status_code=201)
async def create_driver(
    warehouse_id: UUID,
    payload: DriverCreate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
    idempotency_key: IdempotencyKey,
) -> Driver:
    """Create a warehouse driver."""

    warehouse = await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.EDIT
    )
    async def create() -> Driver:
        """Resolve an exact RWMS worker only when this command owns the receipt."""

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
        return await service.create_driver(session, warehouse_id, payload, identity)

    outcome = await execute_idempotent_create(
        session,
        operation="create_driver",
        actor_id=principal.subject_id,
        idempotency_key=idempotency_key,
        payload={
            "warehouse_id": str(warehouse_id),
            "payload": payload.model_dump(mode="json"),
        },
        resource_type="driver",
        create=create,
        load=lambda resource_id: session.get(Driver, resource_id),
    )
    if not outcome.replayed:
        await _publish_resource_capacity(session, warehouse_id, settings, client)
    return outcome.resource


@router.patch("/drivers/{driver_id}", response_model=DriverRead)
async def update_driver(
    driver_id: UUID,
    payload: DriverUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> Driver:
    """Update a driver."""

    driver = await require_owned_entity_access(
        session, principal, Driver, driver_id, "driver", WarehouseAccessLevel.EDIT
    )
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
    expected_version: Annotated[int, Query(ge=1)],
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> Response:
    """Delete a driver not retained by plan history."""

    entity = await require_owned_entity_access(
        session, principal, Driver, driver_id, "driver", WarehouseAccessLevel.EDIT
    )
    warehouse_id = entity.warehouse_id
    await service.delete_catalog_entity(
        session,
        Driver,
        driver_id,
        "driver",
        expected_version,
    )
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
    principal: CurrentUserDep,
    idempotency_key: IdempotencyKey,
) -> Vehicle:
    """Atomically create a vehicle and its operational axle-load profiles."""

    await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.EDIT
    )

    outcome = await execute_idempotent_create(
        session,
        operation="create_vehicle_configuration",
        actor_id=principal.subject_id,
        idempotency_key=idempotency_key,
        payload={
            "warehouse_id": str(warehouse_id),
            "payload": payload.model_dump(mode="json"),
        },
        resource_type="vehicle",
        create=lambda: service.create_vehicle_configuration(session, warehouse_id, payload),
        load=lambda resource_id: service.get_vehicle(session, resource_id),
    )
    if not outcome.replayed:
        await _publish_resource_capacity(session, warehouse_id, settings, client)
    return outcome.resource


@router.patch("/vehicles/{vehicle_id}", response_model=VehicleRead)
async def update_vehicle(
    vehicle_id: UUID,
    payload: VehicleUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> Vehicle:
    """Update a vehicle."""

    await require_owned_entity_access(
        session, principal, Vehicle, vehicle_id, "vehicle", WarehouseAccessLevel.EDIT
    )
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
    principal: CurrentUserDep,
) -> Vehicle:
    """Atomically replace vehicle fields and its complete axle-profile set."""

    await require_owned_entity_access(
        session, principal, Vehicle, vehicle_id, "vehicle", WarehouseAccessLevel.EDIT
    )
    entity = await service.update_vehicle_configuration(session, vehicle_id, payload)
    await _publish_resource_capacity(session, entity.warehouse_id, settings, client)
    return entity


@router.delete("/vehicles/{vehicle_id}", status_code=204)
async def delete_vehicle(
    vehicle_id: UUID,
    expected_version: Annotated[int, Query(ge=1)],
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> Response:
    """Delete a vehicle only when no driver shift retains it."""

    entity = await require_owned_entity_access(
        session, principal, Vehicle, vehicle_id, "vehicle", WarehouseAccessLevel.EDIT
    )
    warehouse_id = entity.warehouse_id
    await service.delete_vehicle(session, vehicle_id, expected_version)
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
    principal: CurrentUserDep,
    idempotency_key: IdempotencyKey,
) -> Trailer:
    """Create a warehouse-owned trailer."""

    await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.EDIT
    )

    outcome = await execute_idempotent_create(
        session,
        operation="create_trailer",
        actor_id=principal.subject_id,
        idempotency_key=idempotency_key,
        payload={
            "warehouse_id": str(warehouse_id),
            "payload": payload.model_dump(mode="json"),
        },
        resource_type="trailer",
        create=lambda: service.create_trailer(session, warehouse_id, payload),
        load=lambda resource_id: session.get(Trailer, resource_id),
    )
    if not outcome.replayed:
        await _publish_resource_capacity(session, warehouse_id, settings, client)
    return outcome.resource


@router.patch("/trailers/{trailer_id}", response_model=TrailerRead)
async def update_trailer(
    trailer_id: UUID,
    payload: TrailerUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> Trailer:
    """Update a trailer's label, availability, or physical limits."""

    await require_owned_entity_access(
        session, principal, Trailer, trailer_id, "trailer", WarehouseAccessLevel.EDIT
    )
    entity = await service.update_trailer(session, trailer_id, payload)
    await _publish_resource_capacity(session, entity.warehouse_id, settings, client)
    return entity


@router.delete("/trailers/{trailer_id}", status_code=204)
async def delete_trailer(
    trailer_id: UUID,
    expected_version: Annotated[int, Query(ge=1)],
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> Response:
    """Delete a trailer only when no vehicle selects it as default."""

    entity = await require_owned_entity_access(
        session, principal, Trailer, trailer_id, "trailer", WarehouseAccessLevel.EDIT
    )
    warehouse_id = entity.warehouse_id
    await service.delete_trailer(session, trailer_id, expected_version)
    await _publish_resource_capacity(session, warehouse_id, settings, client)
    return Response(status_code=204)


@router.post("/warehouses/{warehouse_id}/shifts", response_model=ShiftRead, status_code=201)
async def create_shift(
    warehouse_id: UUID,
    payload: ShiftCreate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
    idempotency_key: IdempotencyKey,
) -> DriverShift:
    """Create a non-overlapping warehouse shift."""

    await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.EDIT
    )

    async def create_resource() -> DriverShift:
        """Resolve current operational placement before persisting the idempotent shift."""

        vehicle_policy = await _load_shift_vehicle_policy(
            session,
            settings,
            client,
            warehouse_id,
            date_from=payload.date_from,
            date_to=payload.date_to,
            start_time=payload.start_time,
            end_time=payload.end_time,
        )
        return await service.create_shift(
            session,
            warehouse_id,
            payload,
            vehicle_availability=vehicle_policy,
        )

    outcome = await execute_idempotent_create(
        session,
        operation="create_shift",
        actor_id=principal.subject_id,
        idempotency_key=idempotency_key,
        payload={
            "warehouse_id": str(warehouse_id),
            "payload": payload.model_dump(mode="json"),
        },
        resource_type="shift",
        create=create_resource,
        load=lambda resource_id: session.get(DriverShift, resource_id),
    )
    if not outcome.replayed:
        await _publish_resource_capacity(session, warehouse_id, settings, client)
    return outcome.resource


@router.patch("/shifts/{shift_id}", response_model=ShiftRead)
async def update_shift(
    shift_id: UUID,
    payload: ShiftUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> DriverShift:
    """Update a shift with repeated overlap validation."""

    current = await require_owned_entity_access(
        session, principal, DriverShift, shift_id, "shift", WarehouseAccessLevel.EDIT
    )
    values = payload.model_dump(exclude_unset=True, exclude={"expected_version"})
    vehicle_policy = await _load_shift_vehicle_policy(
        session,
        settings,
        client,
        current.warehouse_id,
        date_from=values.get("date_from", current.date_from),
        date_to=values.get("date_to", current.date_to),
        start_time=values.get("start_time", current.start_time),
        end_time=values.get("end_time", current.end_time),
    )
    entity = await service.update_shift(
        session,
        shift_id,
        payload,
        vehicle_availability=vehicle_policy,
    )
    await _publish_resource_capacity(session, entity.warehouse_id, settings, client)
    return entity


@router.delete("/shifts/{shift_id}", status_code=204)
async def delete_shift(
    shift_id: UUID,
    expected_version: Annotated[int, Query(ge=1)],
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> Response:
    """Delete a shift not retained by plan history."""

    entity = await require_owned_entity_access(
        session, principal, DriverShift, shift_id, "shift", WarehouseAccessLevel.EDIT
    )
    warehouse_id = entity.warehouse_id
    await service.delete_catalog_entity(
        session,
        DriverShift,
        shift_id,
        "shift",
        expected_version,
    )
    await _publish_resource_capacity(session, warehouse_id, settings, client)
    return Response(status_code=204)


@router.post(
    "/warehouses/{warehouse_id}/requests",
    response_model=LogisticsRequestRead,
    status_code=201,
)
async def create_request(
    warehouse_id: UUID,
    payload: LogisticsRequestCreate,
    session: SessionDep,
    principal: CurrentUserDep,
    idempotency_key: IdempotencyKey,
) -> LogisticsRequestRead:
    """Create, server-classify, and split a delivery or pickup request."""

    await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.EDIT
    )
    outcome = await execute_idempotent_create(
        session,
        operation="create_request",
        actor_id=principal.subject_id,
        idempotency_key=idempotency_key,
        payload={
            "warehouse_id": str(warehouse_id),
            "payload": payload.model_dump(mode="json"),
        },
        resource_type="request",
        create=lambda: service.create_request(session, warehouse_id, payload),
        load=lambda resource_id: service.get_request(session, resource_id),
    )
    return await request_read(session, outcome.resource)


@router.get("/requests/{request_id}", response_model=LogisticsRequestRead)
async def get_request(
    request_id: UUID, session: SessionDep, principal: CurrentUserDep
) -> LogisticsRequestRead:
    """Read one logistics request."""

    entity = await require_owned_entity_access(
        session, principal, LogisticsRequest, request_id, "request", WarehouseAccessLevel.VIEW
    )
    return await request_read(session, entity)


@router.post(
    "/requests/{request_id}/contractor-assignment",
    response_model=LogisticsRequestRead,
)
async def assign_contractor(
    request_id: UUID,
    payload: ContractorAssignmentCreate,
    session: SessionDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> LogisticsRequestRead:
    """Hand one complete delivery to a contractor without an internal route cycle."""

    await require_owned_entity_access(
        session, principal, LogisticsRequest, request_id, "request", WarehouseAccessLevel.EDIT
    )
    entity = await assign_request_to_contractor(
        session,
        request_id,
        payload,
        client,
        assigned_by=principal.audit_actor,
    )
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
    principal: CurrentUserDep,
) -> ContractorDispatchRead:
    """Assign selected-day unplanned requests without contractor route optimization."""

    await require_local_warehouse_access(
        session, principal, warehouse_id, WarehouseAccessLevel.EDIT
    )
    return await dispatch_requests_to_contractor(
        session,
        warehouse_id,
        payload,
        client,
        assigned_by=principal.audit_actor,
    )


@router.patch("/requests/{request_id}", response_model=LogisticsRequestRead)
async def update_request(
    request_id: UUID,
    payload: LogisticsRequestUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> LogisticsRequestRead:
    """Update a request and reclassify coordinate changes on the backend."""

    await require_owned_entity_access(
        session, principal, LogisticsRequest, request_id, "request", WarehouseAccessLevel.EDIT
    )
    entity = await service.update_request(session, request_id, payload)
    await _publish_generated_request_capacity(session, entity, settings, client)
    return await request_read(session, entity)


@router.delete("/requests/{request_id}", status_code=204)
async def delete_request(
    request_id: UUID,
    expected_version: Annotated[int, Query(ge=1)],
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> Response:
    """Delete one request when no saved plan references its tasks."""

    entity = await require_owned_entity_access(
        session, principal, LogisticsRequest, request_id, "request", WarehouseAccessLevel.EDIT
    )
    await service.delete_request(session, request_id, expected_version)
    await _publish_generated_request_capacity(session, entity, settings, client)
    return Response(status_code=204)


@router.post("/requests/{request_id}/planning-details", response_model=LogisticsRequestRead)
async def set_request_planning_details(
    request_id: UUID,
    payload: RequestPlanningDetailsInput,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> LogisticsRequestRead:
    """Store dispatcher-owned obligation, date, window, access, and contact details."""

    await require_owned_entity_access(
        session, principal, LogisticsRequest, request_id, "request", WarehouseAccessLevel.EDIT
    )
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
    payload: RequestTaskSplitInput,
    session: SessionDep,
    principal: CurrentUserDep,
) -> LogisticsRequestRead:
    """Regenerate automatic or explicitly-sized transport parts."""

    await require_owned_entity_access(
        session, principal, LogisticsRequest, request_id, "request", WarehouseAccessLevel.EDIT
    )
    return await request_read(
        session,
        await service.split_request(
            session,
            request_id,
            payload.expected_version,
            payload.part_quantities,
        ),
    )


@router.post("/requests/{request_id}/schedule", response_model=LogisticsRequestRead)
async def schedule_request(
    request_id: UUID,
    payload: RequestScheduleInput,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> LogisticsRequestRead:
    """Assign the request to one accepted or explicitly agreed date."""

    await require_owned_entity_access(
        session, principal, LogisticsRequest, request_id, "request", WarehouseAccessLevel.EDIT
    )
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
    principal: CurrentUserDep,
    idempotency_key: IdempotencyKey,
) -> RequestDateOption:
    """Add one acceptable date option."""

    await require_owned_entity_access(
        session, principal, LogisticsRequest, request_id, "request", WarehouseAccessLevel.EDIT
    )
    outcome = await execute_idempotent_create(
        session,
        operation="create_request_date_option",
        actor_id=principal.subject_id,
        idempotency_key=idempotency_key,
        payload={
            "request_id": str(request_id),
            "payload": payload.model_dump(mode="json"),
        },
        resource_type="request_date_option",
        create=lambda: service.create_date_option(session, request_id, payload),
        load=lambda resource_id: session.get(RequestDateOption, resource_id),
    )
    request = await service.get_request(session, request_id)
    if not outcome.replayed:
        await _publish_generated_request_capacity(session, request, settings, client)
    return outcome.resource


@router.patch("/request-date-options/{option_id}", response_model=RequestDateOptionRead)
async def update_date_option(
    option_id: UUID,
    payload: RequestDateOptionUpdate,
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> RequestDateOption:
    """Update one acceptable request date and window."""

    await require_request_date_option_access(
        session, principal, option_id, WarehouseAccessLevel.EDIT
    )
    entity = await service.update_date_option(session, option_id, payload)
    request = await service.get_request(session, entity.request_id)
    await _publish_generated_request_capacity(session, request, settings, client)
    return entity


@router.delete("/request-date-options/{option_id}", status_code=204)
async def delete_date_option(
    option_id: UUID,
    expected_version: Annotated[int, Query(ge=1)],
    session: SessionDep,
    settings: SettingsDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> Response:
    """Delete one acceptable request date."""

    entity = await require_request_date_option_access(
        session, principal, option_id, WarehouseAccessLevel.EDIT
    )
    request = await service.get_request(session, entity.request_id)
    await service.delete_date_option(session, option_id, expected_version)
    await _publish_generated_request_capacity(session, request, settings, client)
    return Response(status_code=204)
