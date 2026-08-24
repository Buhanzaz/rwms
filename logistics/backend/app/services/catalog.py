"""CRUD workflows for scenario-owned logistics inputs and zone classification."""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from uuid import UUID

from geoalchemy2.shape import from_shape, to_shape
from pydantic import BaseModel
from sqlalchemy import delete, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.db import Base
from app.errors import ApiError, not_found
from app.geo import classify_point, geometry_from_geojson, subtract_polygonal_cutout
from app.models import (
    Driver,
    DriverShift,
    LogisticsRequest,
    PlanningTask,
    RequestDateOption,
    RouteStop,
    Scenario,
    Vehicle,
    Warehouse,
    Zone,
    ZoneRelation,
)
from app.models.domain import RequestStatus, RequestType, TaskStatus, ZoneClassificationStatus
from app.repositories import get_required, list_for_scenario
from app.schemas.domain import (
    DriverCreate,
    DriverUpdate,
    LogisticsRequestCreate,
    LogisticsRequestUpdate,
    RequestDateOptionInput,
    RequestDateOptionUpdate,
    RequestScheduleInput,
    RwmsPlanningRequest,
    ShiftCreate,
    ShiftUpdate,
    VehicleCreate,
    VehicleUpdate,
    WarehouseCreate,
    WarehouseUpdate,
    ZoneCreate,
    ZoneCutoutRequest,
    ZoneRelationCreate,
    ZoneRelationUpdate,
    ZoneUpdate,
)

RWMS_SOURCE_SYSTEM = "RWMS"


def split_quantities(quantity: int, capacity: int = 2) -> list[int]:
    """Split a positive quantity into stable, capacity-bounded parts."""

    if quantity <= 0:
        raise ValueError("quantity must be positive")
    if not 1 <= capacity <= 2:
        raise ValueError("capacity must be between 1 and 2")
    parts: list[int] = []
    remaining = quantity
    while remaining:
        part = min(capacity, remaining)
        parts.append(part)
        remaining -= part
    return parts


def apply_update(entity: object, payload: BaseModel, *, exclude: set[str] | None = None) -> None:
    """Apply explicitly supplied Pydantic fields to an ORM entity."""

    for field, value in payload.model_dump(exclude_unset=True, exclude=exclude or set()).items():
        setattr(entity, field, value)


async def require_scenario(session: AsyncSession, scenario_id: UUID) -> Scenario:
    """Resolve the owning scenario for nested resources."""

    return await get_required(session, Scenario, scenario_id, "scenario")


async def create_warehouse(
    session: AsyncSession, scenario_id: UUID, payload: WarehouseCreate
) -> Warehouse:
    """Create a warehouse after confirming scenario ownership."""

    await require_scenario(session, scenario_id)
    entity = Warehouse(scenario_id=scenario_id, **payload.model_dump())
    session.add(entity)
    await session.flush()
    return entity


async def update_warehouse(
    session: AsyncSession, warehouse_id: UUID, payload: WarehouseUpdate
) -> Warehouse:
    """Update warehouse fields and validate the effective local hours."""

    entity = await get_required(session, Warehouse, warehouse_id, "warehouse")
    values = payload.model_dump(exclude_unset=True)
    start = values.get("working_day_start", entity.working_day_start)
    end = values.get("working_day_end", entity.working_day_end)
    if end <= start:
        raise ApiError(422, "INVALID_WORKING_DAY", "working_day_end must be after start")
    apply_update(entity, payload)
    await session.flush()
    return entity


async def create_zone(session: AsyncSession, scenario_id: UUID, payload: ZoneCreate) -> Zone:
    """Persist a validated zone as normalized MultiPolygon version one."""

    await require_scenario(session, scenario_id)
    values = payload.model_dump(exclude={"geometry"})
    entity = Zone(
        scenario_id=scenario_id,
        geometry=geometry_from_geojson(payload.geometry),
        version=1,
        **values,
    )
    session.add(entity)
    await session.flush()
    return entity


async def update_zone(session: AsyncSession, zone_id: UUID, payload: ZoneUpdate) -> Zone:
    """Update an unlocked zone and increment version only for geometry changes."""

    entity = await session.scalar(
        select(Zone).where(Zone.id == zone_id).with_for_update()
    )
    if entity is None:
        raise not_found("zone", zone_id)
    if entity.locked:
        raise ApiError(409, "ZONE_LOCKED", "Unlock the zone before editing it")
    values = payload.model_dump(exclude_unset=True, exclude={"geometry"})
    for field, value in values.items():
        setattr(entity, field, value)
    if payload.geometry is not None:
        entity.geometry = geometry_from_geojson(payload.geometry)
        entity.version += 1
    await session.flush()
    await session.refresh(entity)
    return entity


async def cut_zone(
    session: AsyncSession,
    zone_id: UUID,
    payload: ZoneCutoutRequest,
) -> tuple[Zone, Zone]:
    """Atomically cut a source zone and create the operational zone occupying the hole."""

    entity = await session.scalar(
        select(Zone).where(Zone.id == zone_id).with_for_update()
    )
    if entity is None:
        raise not_found("zone", zone_id)
    if entity.locked:
        raise ApiError(
            409,
            "ZONE_LOCKED",
            "Сначала разблокируйте зону, чтобы создать в ней вырез.",
        )
    conflicting_zone_id = await session.scalar(
        select(Zone.id).where(
            Zone.scenario_id == entity.scenario_id,
            Zone.code == payload.inner_zone.code,
        )
    )
    if conflicting_zone_id is not None:
        raise ApiError(
            409,
            "ZONE_CODE_CONFLICT",
            "Указанный код уже используется другой зоной этого сценария.",
        )
    try:
        geometry = subtract_polygonal_cutout(
            to_shape(entity.geometry),
            payload.geometry.to_shapely(),
        )
    except ValueError as exc:
        raise ApiError(
            422,
            "ZONE_CUTOUT_OUTSIDE",
            (
                "Вырез должен целиком находиться внутри выбранной зоны и не касаться "
                "её внешней границы или существующих вырезов."
            ),
        ) from exc
    entity.geometry = from_shape(geometry, srid=4326, extended=True)
    entity.version += 1
    inner_zone = Zone(
        scenario_id=entity.scenario_id,
        geometry=geometry_from_geojson(payload.geometry),
        version=1,
        **payload.inner_zone.model_dump(),
    )
    session.add(inner_zone)
    await session.flush()
    await session.refresh(entity)
    await session.refresh(inner_zone)
    return entity, inner_zone


async def set_zone_lock(session: AsyncSession, zone_id: UUID, locked: bool) -> Zone:
    """Set a zone's explicit editor lock without changing its geometry version."""

    entity = await get_required(session, Zone, zone_id, "zone")
    entity.locked = locked
    await session.flush()
    await session.refresh(entity)
    return entity


async def delete_zone(session: AsyncSession, zone_id: UUID) -> None:
    """Delete a zone and mark formerly classified requests outside pending reclassification."""

    entity = await get_required(session, Zone, zone_id, "zone")
    requests = list(
        await session.scalars(select(LogisticsRequest).where(LogisticsRequest.zone_id == zone_id))
    )
    tasks = list(await session.scalars(select(PlanningTask).where(PlanningTask.zone_id == zone_id)))
    for request in requests:
        request.zone_id = None
        request.zone_classification_status = ZoneClassificationStatus.OUTSIDE_ZONES
    for task in tasks:
        task.zone_id = None
    await session.flush()
    await session.delete(entity)
    await session.flush()


async def count_stale_requests(session: AsyncSession, zone: Zone) -> int:
    """Count requests retaining an older version of a still-selected zone."""

    return int(
        await session.scalar(
            select(func.count(LogisticsRequest.id)).where(
                LogisticsRequest.zone_id == zone.id,
                LogisticsRequest.zone_version.is_not(None),
                LogisticsRequest.zone_version != zone.version,
            )
        )
        or 0
    )


async def _validate_relation_zones(
    session: AsyncSession, scenario_id: UUID, from_zone_id: UUID, to_zone_id: UUID
) -> None:
    """Ensure both relation endpoints exist and belong to the requested scenario."""

    count = int(
        await session.scalar(
            select(func.count(Zone.id)).where(
                Zone.scenario_id == scenario_id, Zone.id.in_([from_zone_id, to_zone_id])
            )
        )
        or 0
    )
    if count != 2:
        raise ApiError(
            422,
            "ZONE_RELATION_SCENARIO_MISMATCH",
            "Both relation zones must belong to the scenario",
        )


async def create_zone_relation(
    session: AsyncSession, scenario_id: UUID, payload: ZoneRelationCreate
) -> ZoneRelation:
    """Create one directed relation; bidirectionality remains explicit metadata."""

    await require_scenario(session, scenario_id)
    await _validate_relation_zones(session, scenario_id, payload.from_zone_id, payload.to_zone_id)
    entity = ZoneRelation(scenario_id=scenario_id, **payload.model_dump())
    session.add(entity)
    await session.flush()
    return entity


async def update_zone_relation(
    session: AsyncSession, relation_id: UUID, payload: ZoneRelationUpdate
) -> ZoneRelation:
    """Update transition behavior while retaining immutable endpoints."""

    entity = await get_required(session, ZoneRelation, relation_id, "zone_relation")
    apply_update(entity, payload)
    await session.flush()
    return entity


async def create_driver(session: AsyncSession, scenario_id: UUID, payload: DriverCreate) -> Driver:
    """Create a driver within one scenario."""

    await require_scenario(session, scenario_id)
    entity = Driver(scenario_id=scenario_id, **payload.model_dump())
    session.add(entity)
    await session.flush()
    return entity


async def update_driver(session: AsyncSession, driver_id: UUID, payload: DriverUpdate) -> Driver:
    """Update a driver without altering existing plan history."""

    entity = await get_required(session, Driver, driver_id, "driver")
    apply_update(entity, payload)
    await session.flush()
    return entity


async def create_vehicle(
    session: AsyncSession, scenario_id: UUID, payload: VehicleCreate
) -> Vehicle:
    """Create a vehicle within one scenario."""

    await require_scenario(session, scenario_id)
    entity = Vehicle(scenario_id=scenario_id, **payload.model_dump())
    session.add(entity)
    await session.flush()
    return entity


async def update_vehicle(
    session: AsyncSession, vehicle_id: UUID, payload: VehicleUpdate
) -> Vehicle:
    """Update vehicle availability, capacity, speeds, or labels."""

    entity = await get_required(session, Vehicle, vehicle_id, "vehicle")
    apply_update(entity, payload)
    await session.flush()
    return entity


async def _require_same_scenario_shift_resources(
    session: AsyncSession, scenario_id: UUID, driver_id: UUID, vehicle_id: UUID
) -> None:
    """Ensure a shift cannot bind resources from a different scenario."""

    driver = await get_required(session, Driver, driver_id, "driver")
    vehicle = await get_required(session, Vehicle, vehicle_id, "vehicle")
    if driver.scenario_id != scenario_id or vehicle.scenario_id != scenario_id:
        raise ApiError(
            422,
            "SHIFT_SCENARIO_MISMATCH",
            "Driver and vehicle must belong to the shift scenario",
        )


async def _ensure_shift_available(
    session: AsyncSession,
    *,
    driver_id: UUID,
    vehicle_id: UUID,
    start_at: datetime,
    end_at: datetime,
    exclude_id: UUID | None = None,
) -> None:
    """Reject overlapping active intervals for either driver or vehicle."""

    statement = select(DriverShift).where(
        DriverShift.active.is_(True),
        DriverShift.start_at < end_at,
        DriverShift.end_at > start_at,
        or_(DriverShift.driver_id == driver_id, DriverShift.vehicle_id == vehicle_id),
    )
    if exclude_id is not None:
        statement = statement.where(DriverShift.id != exclude_id)
    conflict = await session.scalar(statement.limit(1))
    if conflict is not None:
        code = (
            "DRIVER_SHIFT_OVERLAP" if conflict.driver_id == driver_id else "VEHICLE_SHIFT_OVERLAP"
        )
        raise ApiError(409, code, "The active shift overlaps an existing resource assignment")


async def create_shift(
    session: AsyncSession, scenario_id: UUID, payload: ShiftCreate
) -> DriverShift:
    """Create a non-overlapping aware shift for scenario-owned resources."""

    await require_scenario(session, scenario_id)
    await _require_same_scenario_shift_resources(
        session, scenario_id, payload.driver_id, payload.vehicle_id
    )
    if payload.active:
        await _ensure_shift_available(
            session,
            driver_id=payload.driver_id,
            vehicle_id=payload.vehicle_id,
            start_at=payload.start_at,
            end_at=payload.end_at,
        )
    entity = DriverShift(scenario_id=scenario_id, **payload.model_dump())
    session.add(entity)
    await session.flush()
    return entity


async def update_shift(session: AsyncSession, shift_id: UUID, payload: ShiftUpdate) -> DriverShift:
    """Update a shift after recomputing ownership, interval, and overlap checks."""

    entity = await get_required(session, DriverShift, shift_id, "shift")
    values = payload.model_dump(exclude_unset=True)
    driver_id = values.get("driver_id", entity.driver_id)
    vehicle_id = values.get("vehicle_id", entity.vehicle_id)
    start_at = values.get("start_at", entity.start_at)
    end_at = values.get("end_at", entity.end_at)
    active = values.get("active", entity.active)
    if end_at <= start_at:
        raise ApiError(422, "INVALID_SHIFT_INTERVAL", "end_at must be after start_at")
    await _require_same_scenario_shift_resources(session, entity.scenario_id, driver_id, vehicle_id)
    if active:
        await _ensure_shift_available(
            session,
            driver_id=driver_id,
            vehicle_id=vehicle_id,
            start_at=start_at,
            end_at=end_at,
            exclude_id=entity.id,
        )
    apply_update(entity, payload)
    await session.flush()
    return entity


async def delete_catalog_entity[MutableModel: Base](
    session: AsyncSession, model: type[MutableModel], entity_id: UUID, resource_name: str
) -> None:
    """Delete an explicitly addressed catalog entity."""

    entity = await get_required(session, model, entity_id, resource_name)
    await session.delete(entity)
    await session.flush()


async def _set_request_classification(session: AsyncSession, request: LogisticsRequest) -> None:
    """Overwrite classification exclusively from stored coordinates and PostGIS."""

    match = await classify_point(session, request.scenario_id, request.latitude, request.longitude)
    if match is None:
        request.zone_id = None
        request.zone_version = None
        request.zone_classification_status = ZoneClassificationStatus.OUTSIDE_ZONES
    else:
        request.zone_id = match.zone_id
        request.zone_version = match.zone_version
        request.zone_classification_status = ZoneClassificationStatus.CLASSIFIED


def _build_task(request: LogisticsRequest, part_number: int, quantity: int) -> PlanningTask:
    """Copy the classification snapshot and planning fields into one request part."""

    return PlanningTask(
        request=request,
        part_number=part_number,
        quantity=quantity,
        type=request.type,
        latitude=request.latitude,
        longitude=request.longitude,
        zone_id=request.zone_id,
        zone_version=request.zone_version,
        service_minutes=request.service_minutes,
        priority=request.priority,
        status=TaskStatus.READY,
    )


async def _replace_request_tasks(session: AsyncSession, request: LogisticsRequest) -> None:
    """Regenerate deterministic vehicle-sized tasks unless a saved route references them."""

    existing_ids = list(
        await session.scalars(select(PlanningTask.id).where(PlanningTask.request_id == request.id))
    )
    if existing_ids:
        assigned = int(
            await session.scalar(
                select(func.count(RouteStop.id)).where(RouteStop.task_id.in_(existing_ids))
            )
            or 0
        )
        if assigned:
            raise ApiError(
                409,
                "REQUEST_TASKS_ALREADY_PLANNED",
                "Clone or archive the plan before changing fields that regenerate tasks",
            )
        await session.execute(delete(PlanningTask).where(PlanningTask.id.in_(existing_ids)))
    for part_number, quantity in enumerate(split_quantities(request.quantity), start=1):
        session.add(_build_task(request, part_number, quantity))
    await session.flush()


def _date_option_entity(
    request: LogisticsRequest, option: RequestDateOptionInput
) -> RequestDateOption:
    """Create a persistence row from one validated date option."""

    return RequestDateOption(request=request, **option.model_dump())


async def create_request(
    session: AsyncSession, scenario_id: UUID, payload: LogisticsRequestCreate
) -> LogisticsRequest:
    """Create, classify, date, and split a source request atomically."""

    await require_scenario(session, scenario_id)
    values = payload.model_dump(exclude={"date_options"})
    entity = LogisticsRequest(scenario_id=scenario_id, **values)
    session.add(entity)
    await session.flush()
    await _set_request_classification(session, entity)
    for option in payload.date_options:
        session.add(_date_option_entity(entity, option))
    await _replace_request_tasks(session, entity)
    await session.flush()
    return await get_request(session, entity.id)


async def upsert_rwms_request(
    session: AsyncSession,
    scenario_id: UUID,
    source: RwmsPlanningRequest,
) -> str:
    """Idempotently import one versioned RWMS order while preserving local-only edits."""

    if source.latitude is None or source.longitude is None:
        raise ApiError(
            422,
            "RWMS_COORDINATES_REQUIRED",
            "RWMS order coordinates are required for simulator synchronization",
        )
    scenario = await require_scenario(session, scenario_id)
    source_payload = source.model_dump(mode="json", by_alias=True)
    entity = await session.scalar(
        select(LogisticsRequest).where(
            LogisticsRequest.scenario_id == scenario_id,
            LogisticsRequest.source_system == RWMS_SOURCE_SYSTEM,
            LogisticsRequest.external_id == source.order_id,
        )
    )
    date_options = [
        RequestDateOptionInput(
            date=option.date,
            priority=option.priority,
            is_hard=option.is_hard,
        )
        for option in source.date_options
    ]
    if entity is None:
        service_minutes = int(scenario.settings.get("default_service_minutes", 30))
        created = await create_request(
            session,
            scenario_id,
            LogisticsRequestCreate(
                type=RequestType.DELIVERY,
                name=f"Заказ {source.order_number}",
                address_label=source.address,
                latitude=source.latitude,
                longitude=source.longitude,
                quantity=source.quantity,
                service_minutes=service_minutes,
                status=RequestStatus.READY,
                date_options=date_options,
            ),
        )
        created.source_system = RWMS_SOURCE_SYSTEM
        created.external_id = source.order_id
        created.external_version = source.order_version
        created.external_payload = source_payload
        await session.flush()
        return "imported"

    if entity.external_version is not None and source.order_version < entity.external_version:
        return "skipped"
    if entity.external_version == source.order_version:
        if entity.external_payload == source_payload:
            return "skipped"
        raise ApiError(
            409,
            "RWMS_SOURCE_VERSION_CONFLICT",
            "RWMS returned different order data with an unchanged version",
        )

    update_values: dict[str, object] = {
        "name": f"Заказ {source.order_number}",
        "address_label": source.address,
        "date_options": date_options,
    }
    if entity.latitude != source.latitude or entity.longitude != source.longitude:
        update_values["latitude"] = source.latitude
        update_values["longitude"] = source.longitude
    if entity.quantity != source.quantity:
        update_values["quantity"] = source.quantity
    updated = await update_request(
        session,
        entity.id,
        LogisticsRequestUpdate.model_validate(update_values),
    )
    updated.source_system = RWMS_SOURCE_SYSTEM
    updated.external_id = source.order_id
    updated.external_version = source.order_version
    updated.external_payload = source_payload
    await session.flush()
    return "updated"


async def get_request(
    session: AsyncSession,
    request_id: UUID,
    *,
    for_update: bool = False,
) -> LogisticsRequest:
    """Load a request graph, optionally locking its command-serialization row."""

    statement = (
        select(LogisticsRequest)
        .where(LogisticsRequest.id == request_id)
        .options(
            selectinload(LogisticsRequest.date_options),
            selectinload(LogisticsRequest.tasks),
        )
    )
    if for_update:
        statement = statement.with_for_update()
    entity = await session.scalar(statement)
    if entity is None:
        raise not_found("request", request_id)
    return entity


async def list_requests(session: AsyncSession, scenario_id: UUID) -> list[LogisticsRequest]:
    """List scenario requests with date options and task parts eagerly loaded."""

    statement = (
        select(LogisticsRequest)
        .where(LogisticsRequest.scenario_id == scenario_id)
        .options(
            selectinload(LogisticsRequest.date_options),
            selectinload(LogisticsRequest.tasks),
        )
        .order_by(LogisticsRequest.created_at, LogisticsRequest.id)
    )
    return list((await session.scalars(statement)).unique().all())


async def update_request(
    session: AsyncSession, request_id: UUID, payload: LogisticsRequestUpdate
) -> LogisticsRequest:
    """Update a request, reclassifying moves and regenerating affected tasks."""

    entity = await get_request(session, request_id, for_update=True)
    changed = payload.model_dump(exclude_unset=True, exclude={"date_options"})
    task_fields = {"type", "latitude", "longitude", "quantity", "service_minutes", "priority"}
    regenerate_tasks = bool(task_fields.intersection(changed))
    moved = "latitude" in changed or "longitude" in changed
    for field, value in changed.items():
        setattr(entity, field, value)
    if moved:
        await _set_request_classification(session, entity)
    if payload.date_options is not None:
        accepted_dates = {option.date for option in payload.date_options}
        if entity.scheduled_date is not None and entity.scheduled_date not in accepted_dates:
            entity.scheduled_date = None
        entity.date_options.clear()
        await session.flush()
        for option in payload.date_options:
            entity.date_options.append(RequestDateOption(**option.model_dump()))
    if regenerate_tasks:
        await _replace_request_tasks(session, entity)
    await session.flush()
    return await get_request(session, entity.id)


async def schedule_request(
    session: AsyncSession,
    request_id: UUID,
    payload: RequestScheduleInput,
) -> LogisticsRequest:
    """Assign one accepted date, optionally recording an explicitly agreed new date."""

    entity = await get_request(session, request_id, for_update=True)
    if payload.date is None:
        entity.scheduled_date = None
        await session.flush()
        return await get_request(session, entity.id)

    option = next(
        (item for item in entity.date_options if item.date == payload.date),
        None,
    )
    if option is None:
        if not payload.add_if_missing:
            raise ApiError(
                422,
                "REQUEST_DATE_NOT_ALLOWED",
                "Выбранная дата отсутствует среди дат, согласованных клиентом.",
            )
        entity.date_options.append(
            RequestDateOption(
                date=payload.date,
                priority=1000,
                window_start=None,
                window_end=None,
                is_hard=False,
            )
        )
    entity.scheduled_date = payload.date
    await session.flush()
    return await get_request(session, entity.id)


async def split_request(session: AsyncSession, request_id: UUID) -> LogisticsRequest:
    """Idempotently regenerate request parts according to the capacity-two invariant."""

    entity = await get_request(session, request_id)
    await _replace_request_tasks(session, entity)
    return await get_request(session, entity.id)


async def create_date_option(
    session: AsyncSession, request_id: UUID, payload: RequestDateOptionInput
) -> RequestDateOption:
    """Append one unique acceptable date to an existing request."""

    request = await get_request(session, request_id)
    duplicate = await session.scalar(
        select(RequestDateOption.id).where(
            RequestDateOption.request_id == request_id,
            RequestDateOption.date == payload.date,
        )
    )
    if duplicate is not None:
        raise ApiError(409, "REQUEST_DATE_DUPLICATE", "This request date already exists")
    entity = _date_option_entity(request, payload)
    session.add(entity)
    await session.flush()
    return entity


async def update_date_option(
    session: AsyncSession, option_id: UUID, payload: RequestDateOptionUpdate
) -> RequestDateOption:
    """Patch a date option while validating its effective time window."""

    entity = await get_required(session, RequestDateOption, option_id, "request_date_option")
    request = await get_required(session, LogisticsRequest, entity.request_id, "request")
    previous_date = entity.date
    values = payload.model_dump(exclude_unset=True)
    start = values.get("window_start", entity.window_start)
    end = values.get("window_end", entity.window_end)
    if (start is None) != (end is None):
        raise ApiError(
            422,
            "INVALID_TIME_WINDOW",
            "window_start and window_end must both be set or both omitted",
        )
    if start is not None and end is not None and end <= start:
        raise ApiError(422, "INVALID_TIME_WINDOW", "window_end must be after window_start")
    apply_update(entity, payload)
    if request.scheduled_date == previous_date:
        request.scheduled_date = entity.date
    await session.flush()
    return entity


async def delete_date_option(session: AsyncSession, option_id: UUID) -> None:
    """Delete one acceptable date and clear an assignment that referenced it."""

    entity = await get_required(session, RequestDateOption, option_id, "request_date_option")
    request = await get_required(session, LogisticsRequest, entity.request_id, "request")
    if request.scheduled_date == entity.date:
        request.scheduled_date = None
    await session.delete(entity)
    await session.flush()


async def delete_request(session: AsyncSession, request_id: UUID) -> None:
    """Delete an unplanned source request and all owned date/task rows."""

    entity = await get_request(session, request_id)
    task_ids = [task.id for task in entity.tasks]
    assigned = 0
    if task_ids:
        assigned = int(
            await session.scalar(
                select(func.count(RouteStop.id)).where(RouteStop.task_id.in_(task_ids))
            )
            or 0
        )
    if assigned:
        raise ApiError(
            409,
            "REQUEST_ALREADY_PLANNED",
            "The request is referenced by a saved plan and cannot be deleted",
        )
    await session.delete(entity)
    await session.flush()


async def reclassify_requests(session: AsyncSession, scenario_id: UUID) -> tuple[int, int, int]:
    """Explicitly refresh every request and task classification snapshot."""

    await require_scenario(session, scenario_id)
    requests = await list_requests(session, scenario_id)
    updated = 0
    outside = 0
    unchanged = 0
    for request in requests:
        previous = (request.zone_id, request.zone_version, request.zone_classification_status)
        await _set_request_classification(session, request)
        current = (request.zone_id, request.zone_version, request.zone_classification_status)
        if current == previous:
            unchanged += 1
        else:
            updated += 1
        if request.zone_classification_status == ZoneClassificationStatus.OUTSIDE_ZONES:
            outside += 1
        for task in request.tasks:
            task.zone_id = request.zone_id
            task.zone_version = request.zone_version
    await session.flush()
    return updated, outside, unchanged


async def list_catalog[MutableModel: Base](
    session: AsyncSession, model: type[MutableModel], scenario_id: UUID
) -> Sequence[MutableModel]:
    """Expose the generic scenario-list query to thin API routes."""

    await require_scenario(session, scenario_id)
    return await list_for_scenario(session, model, scenario_id)
