"""CRUD workflows for warehouse-owned logistics inputs and zone classification."""

from __future__ import annotations

from collections.abc import Sequence
from datetime import date, time
from uuid import UUID

from geoalchemy2.shape import from_shape, to_shape
from pydantic import BaseModel
from sqlalchemy import delete, func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.db import Base
from app.errors import ApiError, not_found
from app.geo import (
    classify_zone_policies,
    geometry_from_geojson,
    subtract_polygonal_cutout,
)
from app.models import (
    Driver,
    DriverShift,
    LogisticsRequest,
    PlanningTask,
    RequestDateOption,
    RoutePlan,
    RouteStop,
    Trailer,
    UnassignedTask,
    Vehicle,
    VehicleLoadProfile,
    Warehouse,
    Zone,
)
from app.models.domain import (
    PlanStatus,
    RequestStatus,
    RequestType,
    TaskStatus,
    ZoneClassificationStatus,
    ZoneKind,
)
from app.repositories import get_required, list_for_warehouse
from app.schemas.domain import (
    DriverCreate,
    DriverUpdate,
    LogisticsRequestCreate,
    LogisticsRequestUpdate,
    PlanningSettings,
    RequestDateOptionInput,
    RequestDateOptionUpdate,
    RequestPlanningDetailsInput,
    RequestScheduleInput,
    RwmsDriverIdentity,
    RwmsPlanningRequest,
    RwmsWarehouseIdentity,
    ShiftCreate,
    ShiftUpdate,
    TrailerCreate,
    TrailerUpdate,
    VehicleConfigurationCreate,
    VehicleConfigurationUpdate,
    VehicleCreate,
    VehicleUpdate,
    WarehouseCreate,
    WarehouseUpdate,
    ZoneCreate,
    ZoneCutoutRequest,
    ZoneUpdate,
)
from app.schemas.geocoding import ResolvedAddress
from app.services import plans as plan_service

RWMS_SOURCE_SYSTEM = "RWMS"
RWMS_SOURCE_FIELDS = frozenset(
    {
        "type",
        "name",
        "address_label",
        "latitude",
        "longitude",
        "quantity",
        "date_options",
        "trailer_access_allowed",
    }
)
CARGO_PHYSICAL_FIELDS = (
    "cargo_length_mm",
    "cargo_width_mm",
    "cargo_height_mm",
    "cargo_weight_kg",
)


def _warehouse_default_cargo(warehouse: Warehouse) -> dict[str, int]:
    """Return the explicit warehouse cargo profile used for RWMS orders without cargo facts."""

    settings = PlanningSettings.model_validate(warehouse.settings)
    return {
        "cargo_length_mm": settings.default_cargo_length_mm,
        "cargo_width_mm": settings.default_cargo_width_mm,
        "cargo_height_mm": settings.default_cargo_height_mm,
        "cargo_weight_kg": settings.default_cargo_weight_kg,
    }


async def _invalidate_route_plans_for_dates(
    session: AsyncSession,
    warehouse_id: UUID,
    dates: set[date],
) -> None:
    """Delete stale plans before authoritative RWMS demand changes their inputs."""

    if not dates:
        return
    await session.execute(
        delete(RoutePlan).where(
            RoutePlan.warehouse_id == warehouse_id,
            RoutePlan.date.in_(dates),
        )
    )
    await session.flush()


async def _invalidate_mutable_route_plans_for_warehouse(
    session: AsyncSession,
    warehouse_id: UUID,
) -> None:
    """Remove only recomputable route artifacts for one changed warehouse projection."""

    await session.execute(
        delete(RoutePlan).where(
            RoutePlan.warehouse_id == warehouse_id,
            RoutePlan.status.in_(
                (PlanStatus.DRAFT, PlanStatus.GENERATED, PlanStatus.VALIDATED)
            ),
        )
    )
    await session.flush()


def _request_effective_dates(request: LogisticsRequest) -> set[date]:
    """Return dates on which the request can participate in an automatic plan."""

    if request.scheduled_date is not None:
        return {request.scheduled_date}
    return {option.date for option in request.date_options}


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


def _reject_rwms_source_edit(request: LogisticsRequest, detail: str) -> None:
    """Protect request facts that can only be restored by the authoritative RWMS feed."""

    if request.source_system == RWMS_SOURCE_SYSTEM:
        raise ApiError(409, "RWMS_REQUEST_SOURCE_IMMUTABLE", detail)


def _request_task_quantities(
    request: LogisticsRequest,
    explicit_quantities: Sequence[int] | None = None,
) -> list[int]:
    """Validate or derive stable task quantities under access and split consent."""

    quantities = (
        list(explicit_quantities)
        if explicit_quantities is not None
        else split_quantities(
            request.quantity,
            capacity=1 if request.trailer_access_allowed is False else 2,
        )
    )
    if not quantities or any(quantity < 1 or quantity > 2 for quantity in quantities):
        raise ApiError(
            422,
            "INVALID_TASK_SPLIT",
            "Each transport subtask must contain one or two cabins",
        )
    if sum(quantities) != request.quantity:
        raise ApiError(
            422,
            "INVALID_TASK_SPLIT_TOTAL",
            "Transport subtask quantities must sum to the source request quantity",
            extra={"request_quantity": request.quantity, "part_quantities": quantities},
        )
    if len(quantities) > 1 and not request.split_allowed:
        raise ApiError(
            422,
            "REQUEST_SPLIT_NOT_ALLOWED",
            "This request does not allow multiple transport subtasks",
        )
    if request.trailer_access_allowed is False and any(quantity > 1 for quantity in quantities):
        raise ApiError(
            422,
            "TRAILER_ACCESS_NOT_ALLOWED",
            "A two-cabin subtask requires customer-approved trailer access",
        )
    return quantities


def apply_update(entity: object, payload: BaseModel, *, exclude: set[str] | None = None) -> None:
    """Apply explicitly supplied Pydantic fields to an ORM entity."""

    for field, value in payload.model_dump(exclude_unset=True, exclude=exclude or set()).items():
        setattr(entity, field, value)


async def require_warehouse(session: AsyncSession, warehouse_id: UUID) -> Warehouse:
    """Resolve the owning warehouse for nested resources."""

    return await get_required(session, Warehouse, warehouse_id, "warehouse")


async def _lock_warehouse(session: AsyncSession, warehouse_id: UUID) -> Warehouse:
    """Serialize geometry mutations through their owning warehouse row."""

    warehouse = await session.scalar(
        select(Warehouse).where(Warehouse.id == warehouse_id).with_for_update()
    )
    if warehouse is None:
        raise not_found("warehouse", warehouse_id)
    return warehouse


async def require_zone(
    session: AsyncSession,
    warehouse_id: UUID,
    zone_id: UUID,
    *,
    for_update: bool = False,
) -> Zone:
    """Resolve a nested zone without revealing a zone owned by another warehouse."""

    statement = select(Zone).where(
        Zone.id == zone_id,
        Zone.warehouse_id == warehouse_id,
    )
    if for_update:
        statement = statement.with_for_update()
    entity = await session.scalar(statement)
    if entity is None:
        raise not_found("zone", zone_id)
    return entity


async def _insert_canonical_warehouse(
    session: AsyncSession,
    identity: RwmsWarehouseIdentity,
) -> Warehouse:
    """Insert one routing-ready canonical identity, tolerating a concurrent first discovery."""

    assert identity.latitude is not None
    assert identity.longitude is not None
    entity = Warehouse(
        external_warehouse_id=identity.warehouse_id,
        external_warehouse_version=identity.warehouse_version,
        name=identity.name,
        city=identity.city,
        address=identity.address,
        timezone=identity.timezone,
        latitude=identity.latitude,
        longitude=identity.longitude,
        representative=identity.representative,
        routing_ready=True,
        settings=PlanningSettings().model_dump(mode="json"),
    )
    try:
        async with session.begin_nested():
            session.add(entity)
            await session.flush()
    except IntegrityError:
        concurrent = await session.scalar(
            select(Warehouse)
            .where(Warehouse.external_warehouse_id == identity.warehouse_id)
            .with_for_update()
        )
        if concurrent is None:
            raise
        return concurrent
    return entity


async def reconcile_warehouse_directory(
    session: AsyncSession,
    identities: Sequence[RwmsWarehouseIdentity],
) -> list[Warehouse]:
    """Reconcile RWMS identities while retaining a valid address-derived local point."""

    if len({identity.warehouse_id for identity in identities}) != len(identities):
        raise ApiError(
            502,
            "RWMS_WAREHOUSE_DIRECTORY_RESPONSE_INVALID",
            "RWMS warehouse directory contains duplicate identities",
        )
    materialized: list[Warehouse] = []
    for identity in identities:
        entity = await session.scalar(
            select(Warehouse)
            .where(Warehouse.external_warehouse_id == identity.warehouse_id)
            .with_for_update()
        )
        if entity is None:
            if not identity.routing_ready:
                continue
            entity = await _insert_canonical_warehouse(session, identity)

        owner_coordinates_available = (
            identity.routing_ready
            and identity.latitude is not None
            and identity.longitude is not None
        )
        address_derived_point_still_valid = (
            not owner_coordinates_available
            and entity.routing_ready
            and identity.address is not None
            and entity.address == identity.address
            and entity.city == identity.city
        )
        effective_routing_ready = (
            owner_coordinates_available or address_derived_point_still_valid
        )
        route_facts_changed = (
            entity.external_warehouse_version != identity.warehouse_version
            or entity.routing_ready != effective_routing_ready
            or (
                owner_coordinates_available
                and (
                    entity.latitude != identity.latitude
                    or entity.longitude != identity.longitude
                )
            )
        )
        entity.external_warehouse_version = identity.warehouse_version
        entity.name = identity.name
        entity.city = identity.city
        entity.address = identity.address
        entity.timezone = identity.timezone
        entity.representative = identity.representative
        entity.routing_ready = effective_routing_ready
        if owner_coordinates_available:
            assert identity.latitude is not None
            assert identity.longitude is not None
            entity.latitude = identity.latitude
            entity.longitude = identity.longitude
        if route_facts_changed:
            await _invalidate_mutable_route_plans_for_warehouse(session, entity.id)
        if effective_routing_ready:
            materialized.append(entity)
    await session.flush()
    for entity in materialized:
        await session.refresh(entity)
    return materialized


async def create_warehouse(
    session: AsyncSession,
    payload: WarehouseCreate,
    identity: RwmsWarehouseIdentity,
    resolved: ResolvedAddress | None = None,
) -> Warehouse:
    """Bind a canonical RWMS identity and an optional server-resolved address fallback."""

    if identity.warehouse_id != payload.external_warehouse_id:
        raise ApiError(422, "RWMS_WAREHOUSE_ID_MISMATCH", "RWMS warehouse identity mismatch")
    duplicate = await session.scalar(
        select(Warehouse.id).where(Warehouse.external_warehouse_id == identity.warehouse_id)
    )
    if duplicate is not None:
        raise ApiError(409, "WAREHOUSE_ALREADY_BOUND", "RWMS warehouse is already configured")
    if identity.routing_ready:
        assert identity.latitude is not None
        assert identity.longitude is not None
        address = identity.address
        latitude = identity.latitude
        longitude = identity.longitude
    elif resolved is not None:
        assert identity.address is not None
        address = identity.address
        latitude = resolved.latitude
        longitude = resolved.longitude
    else:
        raise ApiError(
            422,
            "WAREHOUSE_COORDINATES_REQUIRED",
            "Не заданы координаты для использования склада в логистике",  # noqa: RUF001
        )
    entity = Warehouse(
        external_warehouse_id=identity.warehouse_id,
        external_warehouse_version=identity.warehouse_version,
        name=identity.name,
        city=identity.city,
        address=address,
        timezone=identity.timezone,
        latitude=latitude,
        longitude=longitude,
        representative=identity.representative,
        routing_ready=True,
        settings=PlanningSettings().model_dump(mode="json"),
        **payload.model_dump(exclude={"external_warehouse_id", "initial_zone"}),
    )
    session.add(entity)
    await session.flush()
    if payload.initial_zone is not None:
        _validate_zone_semantics(
            payload.initial_zone.kind,
            payload.initial_zone.delivery_price,
            payload.initial_zone.pickup_price,
        )
        initial_zone = Zone(
            warehouse_id=entity.id,
            geometry=geometry_from_geojson(payload.initial_zone.geometry),
            version=1,
            **payload.initial_zone.model_dump(exclude={"geometry"}),
        )
        session.add(initial_zone)
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


def _validate_zone_semantics(
    kind: ZoneKind | str,
    delivery_price: int,
    pickup_price: int,
) -> None:
    """Keep ordinary isochrone tariffs out of access-restriction polygons."""

    resolved_kind = ZoneKind(kind)
    if resolved_kind is not ZoneKind.SPECIAL_PRICE and (
        delivery_price != 0 or pickup_price != 0
    ):
        raise ApiError(
            422,
            "ZONE_PRICE_NOT_ALLOWED",
            "Only a SPECIAL_PRICE zone may define delivery or pickup prices",
        )


async def create_zone(
    session: AsyncSession,
    warehouse_id: UUID,
    payload: ZoneCreate,
) -> Zone:
    """Persist a validated zone under one locked warehouse root."""

    await _lock_warehouse(session, warehouse_id)
    _validate_zone_semantics(payload.kind, payload.delivery_price, payload.pickup_price)
    values = payload.model_dump(exclude={"geometry"})
    entity = Zone(
        warehouse_id=warehouse_id,
        geometry=geometry_from_geojson(payload.geometry),
        version=1,
        **values,
    )
    session.add(entity)
    await session.flush()
    return entity


async def update_zone(
    session: AsyncSession,
    warehouse_id: UUID,
    zone_id: UUID,
    payload: ZoneUpdate,
) -> Zone:
    """Update an owner exceptional zone under the warehouse command lock."""

    await _lock_warehouse(session, warehouse_id)
    entity = await require_zone(session, warehouse_id, zone_id, for_update=True)
    if entity.locked:
        raise ApiError(409, "ZONE_LOCKED", "Unlock the zone before editing it")
    values = payload.model_dump(exclude_unset=True, exclude={"geometry"})
    _validate_zone_semantics(
        values.get("kind", entity.kind),
        values.get("delivery_price", entity.delivery_price),
        values.get("pickup_price", entity.pickup_price),
    )
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
    warehouse_id: UUID,
    zone_id: UUID,
    payload: ZoneCutoutRequest,
) -> tuple[Zone, Zone]:
    """Cut an owner zone and create an inner zone under the same warehouse."""

    await _lock_warehouse(session, warehouse_id)
    entity = await require_zone(session, warehouse_id, zone_id, for_update=True)
    if entity.locked:
        raise ApiError(
            409,
            "ZONE_LOCKED",
            "Сначала разблокируйте зону, чтобы создать в ней вырез.",
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
    _validate_zone_semantics(
        payload.inner_zone.kind,
        payload.inner_zone.delivery_price,
        payload.inner_zone.pickup_price,
    )
    inner_zone = Zone(
        warehouse_id=warehouse_id,
        geometry=geometry_from_geojson(payload.geometry),
        version=1,
        **payload.inner_zone.model_dump(),
    )
    session.add(inner_zone)
    await session.flush()
    await session.refresh(entity)
    await session.refresh(inner_zone)
    return entity, inner_zone


async def set_zone_lock(
    session: AsyncSession,
    warehouse_id: UUID,
    zone_id: UUID,
    locked: bool,
) -> Zone:
    """Set an owner zone's editor lock without changing capacity facts."""

    entity = await require_zone(session, warehouse_id, zone_id, for_update=True)
    entity.locked = locked
    await session.flush()
    await session.refresh(entity)
    return entity


async def delete_zone(session: AsyncSession, warehouse_id: UUID, zone_id: UUID) -> None:
    """Delete one exceptional zone without affecting isochrone delivery coverage."""

    await _lock_warehouse(session, warehouse_id)
    entity = await require_zone(session, warehouse_id, zone_id, for_update=True)
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


async def create_driver(
    session: AsyncSession,
    warehouse_id: UUID,
    payload: DriverCreate,
    identity: RwmsDriverIdentity | None,
) -> Driver:
    """Create a driver with a validated exact or warehouse-wide RWMS audience."""

    await require_warehouse(session, warehouse_id)
    values = payload.model_dump()
    name = "Водители склада"
    if payload.rwms_assignment_mode == "ASSIGNED_DRIVER":
        if identity is None or identity.worker_id != payload.external_worker_id:
            raise ApiError(422, "RWMS_DRIVER_NOT_FOUND", "RWMS worker is not eligible")
        name = identity.display_name
    entity = Driver(warehouse_id=warehouse_id, name=name, **values)
    session.add(entity)
    await session.flush()
    return entity


async def update_driver(
    session: AsyncSession,
    driver_id: UUID,
    payload: DriverUpdate,
    identity: RwmsDriverIdentity | None,
) -> Driver:
    """Update a driver while preserving explicit RWMS audience invariants."""

    entity = await get_required(session, Driver, driver_id, "driver")
    values = payload.model_dump(exclude_unset=True)
    mode = values.get("rwms_assignment_mode", entity.rwms_assignment_mode)
    worker_id = values.get("external_worker_id", entity.external_worker_id)
    if mode == "ASSIGNED_DRIVER":
        if worker_id is None or identity is None or identity.worker_id != worker_id:
            raise ApiError(422, "RWMS_DRIVER_NOT_FOUND", "RWMS worker is not eligible")
        entity.name = identity.display_name
    else:
        if worker_id is not None:
            raise ApiError(
                422,
                "RWMS_DRIVER_ASSIGNMENT_INVALID",
                "WAREHOUSE_DRIVERS requires external_worker_id to be null",
            )
        entity.name = "Водители склада"
    for field, value in values.items():
        setattr(entity, field, value)
    await session.flush()
    return entity


async def create_vehicle(
    session: AsyncSession, warehouse_id: UUID, payload: VehicleCreate
) -> Vehicle:
    """Create a vehicle within one warehouse."""

    await require_warehouse(session, warehouse_id)
    await _validate_vehicle_trailer_assignment(
        session,
        warehouse_id=warehouse_id,
        can_use_trailer=payload.can_use_trailer,
        trailer_id=payload.default_trailer_id,
    )
    entity = Vehicle(warehouse_id=warehouse_id, **payload.model_dump())
    session.add(entity)
    await session.flush()
    return entity


async def list_vehicles(session: AsyncSession, warehouse_id: UUID) -> list[Vehicle]:
    """List warehouse vehicles with their complete embedded axle-load profiles."""

    await require_warehouse(session, warehouse_id)
    result = await session.scalars(
        select(Vehicle)
        .where(Vehicle.warehouse_id == warehouse_id)
        .options(selectinload(Vehicle.load_profiles))
        .order_by(Vehicle.id)
    )
    return list(result.unique())


async def _load_vehicle_with_profiles(session: AsyncSession, vehicle_id: UUID) -> Vehicle:
    """Reload one vehicle with its response-owned axle-load profile aggregate."""

    entity = await session.scalar(
        select(Vehicle)
        .where(Vehicle.id == vehicle_id)
        .options(selectinload(Vehicle.load_profiles))
    )
    if entity is None:
        raise not_found("vehicle", vehicle_id)
    return entity


async def update_vehicle(
    session: AsyncSession, vehicle_id: UUID, payload: VehicleUpdate
) -> Vehicle:
    """Update vehicle availability, capacity, speeds, or labels."""

    entity = await get_required(session, Vehicle, vehicle_id, "vehicle")
    values = payload.model_dump(exclude_unset=True)
    await _validate_vehicle_trailer_assignment(
        session,
        warehouse_id=entity.warehouse_id,
        can_use_trailer=values.get("can_use_trailer", entity.can_use_trailer),
        trailer_id=values.get("default_trailer_id", entity.default_trailer_id),
    )
    apply_update(entity, payload)
    await session.flush()
    return await _load_vehicle_with_profiles(session, entity.id)


async def create_vehicle_configuration(
    session: AsyncSession,
    warehouse_id: UUID,
    payload: VehicleConfigurationCreate,
) -> Vehicle:
    """Create a vehicle and its complete axle-profile set in one transaction."""

    entity = await create_vehicle(session, warehouse_id, payload.vehicle)
    session.add_all(
        VehicleLoadProfile(vehicle_id=entity.id, **profile.model_dump(mode="json"))
        for profile in payload.load_profiles
    )
    await session.flush()
    return await _load_vehicle_with_profiles(session, entity.id)


async def update_vehicle_configuration(
    session: AsyncSession,
    vehicle_id: UUID,
    payload: VehicleConfigurationUpdate,
) -> Vehicle:
    """Replace vehicle fields and axle profiles atomically under a row lock."""

    entity = await session.scalar(
        select(Vehicle)
        .where(Vehicle.id == vehicle_id)
        .options(selectinload(Vehicle.load_profiles))
        .with_for_update()
    )
    if entity is None:
        raise not_found("vehicle", vehicle_id)
    values = payload.vehicle.model_dump(exclude_unset=True)
    await _validate_vehicle_trailer_assignment(
        session,
        warehouse_id=entity.warehouse_id,
        can_use_trailer=values.get("can_use_trailer", entity.can_use_trailer),
        trailer_id=values.get("default_trailer_id", entity.default_trailer_id),
    )
    apply_update(entity, payload.vehicle)
    entity.load_profiles.clear()
    await session.flush()
    entity.load_profiles.extend(
        VehicleLoadProfile(**profile.model_dump(mode="json")) for profile in payload.load_profiles
    )
    await session.flush()
    return entity


async def _validate_vehicle_trailer_assignment(
    session: AsyncSession,
    *,
    warehouse_id: UUID,
    can_use_trailer: object,
    trailer_id: object,
) -> None:
    """Ensure a default trailer is explicitly supported and warehouse-owned."""

    if trailer_id is None:
        return
    if can_use_trailer is not True:
        raise ApiError(
            422,
            "DEFAULT_TRAILER_REQUIRES_CAPABILITY",
            "A default trailer requires can_use_trailer=true",
        )
    if not isinstance(trailer_id, UUID):
        raise ApiError(422, "INVALID_TRAILER_ID", "default_trailer_id must be a UUID")
    trailer = await get_required(session, Trailer, trailer_id, "trailer")
    if trailer.warehouse_id != warehouse_id:
        raise ApiError(
            422,
            "TRAILER_WAREHOUSE_MISMATCH",
            "The default trailer must belong to the vehicle warehouse",
        )


async def create_trailer(
    session: AsyncSession, warehouse_id: UUID, payload: TrailerCreate
) -> Trailer:
    """Create a trailer owned by one warehouse."""

    await require_warehouse(session, warehouse_id)
    entity = Trailer(warehouse_id=warehouse_id, **payload.model_dump())
    session.add(entity)
    await session.flush()
    return entity


async def update_trailer(
    session: AsyncSession, trailer_id: UUID, payload: TrailerUpdate
) -> Trailer:
    """Update trailer identity, availability, or physical specification."""

    entity = await get_required(session, Trailer, trailer_id, "trailer")
    apply_update(entity, payload)
    await session.flush()
    return entity


async def _require_same_warehouse_shift_resources(
    session: AsyncSession, warehouse_id: UUID, driver_id: UUID, vehicle_id: UUID
) -> None:
    """Ensure a shift cannot bind resources from a different warehouse."""

    driver = await get_required(session, Driver, driver_id, "driver")
    vehicle = await get_required(session, Vehicle, vehicle_id, "vehicle")
    if driver.warehouse_id != warehouse_id or vehicle.warehouse_id != warehouse_id:
        raise ApiError(
            422,
            "SHIFT_WAREHOUSE_MISMATCH",
            "Driver and vehicle must belong to the shift warehouse",
        )


async def _ensure_shift_available(
    session: AsyncSession,
    *,
    driver_id: UUID,
    vehicle_id: UUID,
    date_from: date,
    date_to: date,
    exclude_id: UUID | None = None,
) -> None:
    """Reject overlapping active date ranges for either driver or vehicle."""

    statement = select(DriverShift).where(
        DriverShift.active.is_(True),
        DriverShift.date_from <= date_to,
        DriverShift.date_to >= date_from,
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
    session: AsyncSession, warehouse_id: UUID, payload: ShiftCreate
) -> DriverShift:
    """Create a non-overlapping aware shift for warehouse-owned resources."""

    await require_warehouse(session, warehouse_id)
    await _require_same_warehouse_shift_resources(
        session, warehouse_id, payload.driver_id, payload.vehicle_id
    )
    if payload.active:
        await _ensure_shift_available(
            session,
            driver_id=payload.driver_id,
            vehicle_id=payload.vehicle_id,
            date_from=payload.date_from,
            date_to=payload.date_to,
        )
    entity = DriverShift(warehouse_id=warehouse_id, **payload.model_dump())
    session.add(entity)
    await session.flush()
    return entity


async def update_shift(session: AsyncSession, shift_id: UUID, payload: ShiftUpdate) -> DriverShift:
    """Update a shift after recomputing ownership, interval, and overlap checks."""

    entity = await get_required(session, DriverShift, shift_id, "shift")
    values = payload.model_dump(exclude_unset=True)
    driver_id = values.get("driver_id", entity.driver_id)
    vehicle_id = values.get("vehicle_id", entity.vehicle_id)
    date_from = values.get("date_from", entity.date_from)
    date_to = values.get("date_to", entity.date_to)
    start_time = values.get("start_time", entity.start_time)
    end_time = values.get("end_time", entity.end_time)
    active = values.get("active", entity.active)
    if date_to < date_from or (date_to - date_from).days > 30:
        raise ApiError(422, "INVALID_SHIFT_DATE_RANGE", "Shift date range must be 1-31 days")
    if (date_from.year, date_from.month) != (date_to.year, date_to.month):
        raise ApiError(422, "INVALID_SHIFT_MONTH", "Shift date range must stay in one month")
    if end_time <= start_time:
        raise ApiError(422, "INVALID_SHIFT_INTERVAL", "end_time must be after start_time")
    await _require_same_warehouse_shift_resources(
        session,
        entity.warehouse_id,
        driver_id,
        vehicle_id,
    )
    if active:
        await _ensure_shift_available(
            session,
            driver_id=driver_id,
            vehicle_id=vehicle_id,
            date_from=date_from,
            date_to=date_to,
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
    """Apply special-price identity and hard access policies at stored coordinates."""

    policies = await classify_zone_policies(
        session,
        request.warehouse_id,
        request.latitude,
        request.longitude,
    )
    if policies.forbidden is not None:
        raise ApiError(
            422,
            "DELIVERY_FORBIDDEN_ZONE",
            "The address is inside a warehouse zone where delivery is prohibited",
            extra={"zone_id": str(policies.forbidden.id), "zone_name": policies.forbidden.name},
        )
    match = policies.special_price
    if match is None:
        request.zone_id = None
        request.zone_version = None
        request.zone_classification_status = ZoneClassificationStatus.OUTSIDE_ZONES
    else:
        request.zone_id = match.id
        request.zone_version = match.version
        request.zone_classification_status = ZoneClassificationStatus.CLASSIFIED
    if policies.no_trailer is not None:
        request.trailer_access_allowed = False


def _build_task(request: LogisticsRequest, part_number: int, quantity: int) -> PlanningTask:
    """Copy the classification snapshot and planning fields into one request part."""

    return PlanningTask(
        request=request,
        part_number=part_number,
        quantity=quantity,
        cargo_length_mm=request.cargo_length_mm,
        cargo_width_mm=request.cargo_width_mm,
        cargo_height_mm=request.cargo_height_mm,
        cargo_weight_kg=request.cargo_weight_kg,
        type=request.type,
        latitude=request.latitude,
        longitude=request.longitude,
        zone_id=request.zone_id,
        zone_version=request.zone_version,
        service_minutes=request.service_minutes,
        priority=request.priority,
        mandatory=request.mandatory,
        status=TaskStatus.READY,
    )


async def _request_tasks_have_plan_references(
    session: AsyncSession,
    request: LogisticsRequest,
) -> bool:
    """Return whether any stable task identity is retained by a saved plan."""

    existing_ids = [task.id for task in request.tasks]
    if not existing_ids:
        return False
    assigned_reference = await session.scalar(
        select(RouteStop.id).where(RouteStop.task_id.in_(existing_ids)).limit(1)
    )
    unassigned_reference = await session.scalar(
        select(UnassignedTask.id).where(UnassignedTask.task_id.in_(existing_ids)).limit(1)
    )
    return assigned_reference is not None or unassigned_reference is not None


async def _replace_request_tasks(
    session: AsyncSession,
    request: LogisticsRequest,
    explicit_quantities: Sequence[int] | None = None,
) -> None:
    """Regenerate deterministic vehicle-sized tasks unless a saved route references them."""

    quantities = _request_task_quantities(request, explicit_quantities)
    existing_ids = list(
        await session.scalars(select(PlanningTask.id).where(PlanningTask.request_id == request.id))
    )
    if existing_ids:
        if await _request_tasks_have_plan_references(session, request):
            raise ApiError(
                409,
                "REQUEST_TASKS_ALREADY_PLANNED",
                "Archive the plan before changing fields that regenerate tasks",
            )
        await session.execute(delete(PlanningTask).where(PlanningTask.id.in_(existing_ids)))
    for part_number, quantity in enumerate(quantities, start=1):
        session.add(_build_task(request, part_number, quantity))
    await session.flush()
    session.expire(request, ["tasks"])


def _date_option_entity(
    request: LogisticsRequest, option: RequestDateOptionInput
) -> RequestDateOption:
    """Create a persistence row from one validated date option."""

    return RequestDateOption(request=request, **option.model_dump())


def _date_option_facts(
    option: RequestDateOption | RequestDateOptionInput,
) -> tuple[date, int, time | None, time | None, bool, int | None]:
    """Return the persisted scheduling facts used to avoid no-op collection rewrites."""

    return (
        option.date,
        option.priority,
        option.window_start,
        option.window_end,
        option.is_hard,
        option.travel_zone_hours,
    )


async def create_request(
    session: AsyncSession, warehouse_id: UUID, payload: LogisticsRequestCreate
) -> LogisticsRequest:
    """Create, classify, date, and split a source request atomically."""

    await require_warehouse(session, warehouse_id)
    await _invalidate_route_plans_for_dates(
        session,
        warehouse_id,
        {option.date for option in payload.date_options},
    )
    values = payload.model_dump(exclude={"date_options"})
    entity = LogisticsRequest(warehouse_id=warehouse_id, **values)
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
    warehouse_id: UUID,
    source: RwmsPlanningRequest,
) -> str:
    """Import one revisioned planning snapshot while retaining the order command fence.

    A planning revision may advance without an order-version change because the feed also contains
    customer-slot and asset-reservation facts owned by separate aggregates. Pre-revision stored
    snapshots are upgraded once from the authenticated source and thereafter obey the same strict
    revision replay check.
    """

    if source.latitude is None or source.longitude is None:
        raise ApiError(
            422,
            "RWMS_COORDINATES_REQUIRED",
            "RWMS order coordinates are required for warehouse synchronization",
        )
    warehouse = await require_warehouse(session, warehouse_id)
    default_cargo = _warehouse_default_cargo(warehouse)
    source_payload = source.model_dump(mode="json", by_alias=True)
    entity = await session.scalar(
        select(LogisticsRequest)
        .where(
            LogisticsRequest.warehouse_id == warehouse_id,
            LogisticsRequest.source_system == RWMS_SOURCE_SYSTEM,
            LogisticsRequest.external_id == source.order_id,
        )
        .options(selectinload(LogisticsRequest.date_options))
    )
    date_options = [
        RequestDateOptionInput(
            date=option.date,
            priority=option.priority,
            window_start=option.window_start,
            window_end=option.window_end,
            is_hard=option.is_hard,
            travel_zone_hours=option.travel_zone_hours,
        )
        for option in source.date_options
    ]
    if entity is None:
        await _invalidate_route_plans_for_dates(
            session,
            warehouse_id,
            {option.date for option in source.date_options},
        )
        service_minutes = int(warehouse.settings.get("default_service_minutes", 30))
        created = await create_request(
            session,
            warehouse_id,
            LogisticsRequestCreate(
                type=RequestType.DELIVERY,
                name=f"Заказ {source.order_number}",
                address_label=source.address,
                latitude=source.latitude,
                longitude=source.longitude,
                quantity=source.quantity,
                **default_cargo,
                service_minutes=service_minutes,
                status=RequestStatus.READY,
                contact_name=source.client_name,
                trailer_access_allowed=source.trailer_access_allowed,
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
    cargo_is_missing = all(getattr(entity, field) is None for field in CARGO_PHYSICAL_FIELDS)
    if entity.external_version == source.order_version:
        if entity.external_payload == source_payload:
            if not cargo_is_missing:
                return "skipped"
        else:
            stored_revision = (entity.external_payload or {}).get("sourceRevision")
            if stored_revision == source.source_revision:
                raise ApiError(
                    409,
                    "RWMS_SOURCE_VERSION_CONFLICT",
                    "RWMS returned different planning data with an unchanged source revision",
                )

    await _invalidate_route_plans_for_dates(
        session,
        warehouse_id,
        {
            *(option.date for option in entity.date_options),
            *(option.date for option in source.date_options),
        },
    )

    update_values: dict[str, object] = {
        "name": f"Заказ {source.order_number}",
        "address_label": source.address,
        "contact_name": source.client_name,
        "trailer_access_allowed": source.trailer_access_allowed,
        "date_options": date_options,
    }
    if cargo_is_missing:
        update_values.update(default_cargo)
    if entity.latitude != source.latitude or entity.longitude != source.longitude:
        update_values["latitude"] = source.latitude
        update_values["longitude"] = source.longitude
    if entity.quantity != source.quantity:
        update_values["quantity"] = source.quantity
    updated = await update_request(
        session,
        entity.id,
        LogisticsRequestUpdate.model_validate(update_values),
        from_authoritative_source=True,
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


async def list_requests(session: AsyncSession, warehouse_id: UUID) -> list[LogisticsRequest]:
    """List warehouse requests with date options and task parts eagerly loaded."""

    statement = (
        select(LogisticsRequest)
        .where(LogisticsRequest.warehouse_id == warehouse_id)
        .options(
            selectinload(LogisticsRequest.date_options),
            selectinload(LogisticsRequest.tasks),
        )
        .order_by(LogisticsRequest.created_at, LogisticsRequest.id)
    )
    return list((await session.scalars(statement)).unique().all())


async def update_request(
    session: AsyncSession,
    request_id: UUID,
    payload: LogisticsRequestUpdate,
    *,
    from_authoritative_source: bool = False,
) -> LogisticsRequest:
    """Update local fields or apply a trusted feed refresh under the request lock."""

    entity = await get_request(session, request_id, for_update=True)
    previous_dates = _request_effective_dates(entity)
    supplied_values = payload.model_dump(exclude_unset=True, exclude={"date_options"})
    supplied_fields = set(payload.model_fields_set)
    if (
        entity.source_system == RWMS_SOURCE_SYSTEM
        and not from_authoritative_source
        and RWMS_SOURCE_FIELDS.intersection(supplied_fields)
    ):
        raise ApiError(
            409,
            "RWMS_REQUEST_SOURCE_IMMUTABLE",
            "Refresh RWMS-owned request facts from the authoritative feed instead of editing them",
        )
    changed = {
        field: value for field, value in supplied_values.items() if getattr(entity, field) != value
    }
    effective_cargo = {
        field: changed.get(field, getattr(entity, field)) for field in CARGO_PHYSICAL_FIELDS
    }
    supplied_cargo_values = tuple(effective_cargo.values())
    if any(value is not None for value in supplied_cargo_values) and any(
        value is None for value in supplied_cargo_values
    ):
        raise ApiError(
            422,
            "INCOMPLETE_CARGO_DIMENSIONS",
            "Cargo dimensions and weight must be supplied or cleared together",
        )
    task_fields = {
        "type",
        "latitude",
        "longitude",
        "quantity",
        "service_minutes",
        "priority",
        *CARGO_PHYSICAL_FIELDS,
    }
    regenerate_tasks = bool(task_fields.intersection(changed))
    moved = "latitude" in changed or "longitude" in changed
    for field, value in changed.items():
        setattr(entity, field, value)
    if moved or "trailer_access_allowed" in changed:
        await _set_request_classification(session, entity)
    if "trailer_access_allowed" in changed:
        current_quantities = [
            task.quantity for task in sorted(entity.tasks, key=lambda task: task.part_number)
        ]
        regenerate_tasks = regenerate_tasks or current_quantities != _request_task_quantities(
            entity
        )
    date_options_changed = payload.date_options is not None and sorted(
        (_date_option_facts(option) for option in payload.date_options),
        key=lambda facts: (facts[0], facts[1]),
    ) != sorted(
        (_date_option_facts(option) for option in entity.date_options),
        key=lambda facts: (facts[0], facts[1]),
    )
    if date_options_changed:
        assert payload.date_options is not None
        accepted_dates = {option.date for option in payload.date_options}
        if entity.scheduled_date is not None and entity.scheduled_date not in accepted_dates:
            entity.scheduled_date = None
        entity.date_options.clear()
        await session.flush()
        for option in payload.date_options:
            entity.date_options.append(RequestDateOption(**option.model_dump()))
    mandatory_only = bool(changed) and set(changed) == {"mandatory"} and not date_options_changed
    if mandatory_only:
        await plan_service.mark_plans_for_request_refresh(
            session,
            entity.warehouse_id,
            previous_dates | _request_effective_dates(entity),
            entity.id,
        )
    elif changed or date_options_changed:
        await _invalidate_route_plans_for_dates(
            session,
            entity.warehouse_id,
            previous_dates | _request_effective_dates(entity),
        )
    if "mandatory" in changed:
        for task in entity.tasks:
            task.mandatory = entity.mandatory
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
    previous_dates = _request_effective_dates(entity)
    if payload.date is None:
        entity.scheduled_date = None
        await _invalidate_route_plans_for_dates(
            session,
            entity.warehouse_id,
            previous_dates | _request_effective_dates(entity),
        )
        await session.flush()
        return await get_request(session, entity.id)

    option = next(
        (item for item in entity.date_options if item.date == payload.date),
        None,
    )
    if option is None:
        if entity.source_system == RWMS_SOURCE_SYSTEM:
            raise ApiError(
                422,
                "REQUEST_DATE_NOT_ALLOWED",
                "RWMS-owned requests can only use dates advertised by the authoritative feed",
            )
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
    await _invalidate_route_plans_for_dates(
        session,
        entity.warehouse_id,
        previous_dates | _request_effective_dates(entity),
    )
    await session.flush()
    return await get_request(session, entity.id)


async def set_request_planning_details(
    session: AsyncSession,
    request_id: UUID,
    payload: RequestPlanningDetailsInput,
) -> LogisticsRequest:
    """Atomically store one accepted date's dispatcher planning decisions."""

    entity = await get_request(session, request_id, for_update=True)
    option = next(
        (item for item in entity.date_options if item.date == payload.date),
        None,
    )
    if option is None:
        raise ApiError(
            422,
            "REQUEST_DATE_NOT_ALLOWED",
            "Выбранная дата отсутствует среди дат, согласованных клиентом.",
        )
    if option.travel_zone_hours is not None and not payload.is_hard:
        raise ApiError(
            422,
            "INVALID_TRAVEL_ZONE",
            "A CustomerApp travel zone must retain its hard delivery window",
        )
    source_fixed_window = (
        entity.source_system == RWMS_SOURCE_SYSTEM
        and option.is_hard
        and option.window_start is not None
        and option.window_end is not None
    )
    if source_fixed_window and (
        payload.window_start != option.window_start
        or payload.window_end != option.window_end
        or not payload.is_hard
    ):
        raise ApiError(
            409,
            "RWMS_FIXED_WINDOW_IMMUTABLE",
            "A fixed RWMS customer window can only be changed in the authoritative source",
        )
    source_flexible_day = (
        entity.source_system == RWMS_SOURCE_SYSTEM
        and not option.is_hard
        and option.window_start is None
        and option.window_end is None
    )
    if source_flexible_day and (
        payload.window_start is not None
        or payload.window_end is not None
        or payload.is_hard
    ):
        raise ApiError(
            409,
            "RWMS_FLEXIBLE_DAY_IMMUTABLE",
            "A CustomerApp full-day option must remain flexible in logistics",
        )
    previous_dates = _request_effective_dates(entity)
    planning_dates = previous_dates | {payload.date}
    previous_trailer_access = entity.trailer_access_allowed
    entity.trailer_access_allowed = payload.trailer_access_allowed
    await _set_request_classification(session, entity)
    trailer_access_changed = previous_trailer_access != entity.trailer_access_allowed
    scheduled_date_changed = (
        entity.scheduled_date is not None and entity.scheduled_date != payload.date
    )
    if scheduled_date_changed:
        active_plan_id = await session.scalar(
            select(RoutePlan.id)
            .where(
                RoutePlan.warehouse_id == entity.warehouse_id,
                RoutePlan.date.in_(planning_dates),
                RoutePlan.status != PlanStatus.ARCHIVED,
            )
            .limit(1)
        )
        if active_plan_id is not None:
            raise ApiError(
                409,
                "REQUEST_DATE_ALREADY_PLANNED",
                "Archive the existing plan before moving a request to another date",
            )
    if trailer_access_changed and await _request_tasks_have_plan_references(session, entity):
        raise ApiError(
            409,
            "REQUEST_TASKS_ALREADY_PLANNED",
            "Archive the plan before changing fields that regenerate tasks",
        )
    await plan_service.mark_plans_for_request_refresh(
        session,
        entity.warehouse_id,
        planning_dates | _request_effective_dates(entity),
        entity.id,
    )
    option.window_start = payload.window_start
    option.window_end = payload.window_end
    option.is_hard = payload.is_hard
    entity.scheduled_date = payload.date
    entity.mandatory = payload.mandatory
    entity.include_driver_passport_in_notification = payload.include_driver_passport_in_notification
    entity.contact_name = payload.contact_name
    entity.contact_phone = payload.contact_phone
    for task in entity.tasks:
        task.mandatory = payload.mandatory
    if trailer_access_changed:
        await _replace_request_tasks(session, entity)
    await session.flush()
    return await get_request(session, entity.id)


async def split_request(
    session: AsyncSession,
    request_id: UUID,
    part_quantities: Sequence[int] | None = None,
) -> LogisticsRequest:
    """Regenerate automatic or explicitly-sized transport subtasks."""

    entity = await get_request(session, request_id, for_update=True)
    await _invalidate_route_plans_for_dates(
        session,
        entity.warehouse_id,
        _request_effective_dates(entity),
    )
    await _replace_request_tasks(session, entity, part_quantities)
    return await get_request(session, entity.id)


async def create_date_option(
    session: AsyncSession, request_id: UUID, payload: RequestDateOptionInput
) -> RequestDateOption:
    """Append one unique acceptable date to an existing request."""

    request = await get_request(session, request_id)
    _reject_rwms_source_edit(
        request,
        "RWMS-owned accepted dates can only be changed by synchronizing the source feed",
    )
    duplicate = await session.scalar(
        select(RequestDateOption.id).where(
            RequestDateOption.request_id == request_id,
            RequestDateOption.date == payload.date,
        )
    )
    if duplicate is not None:
        raise ApiError(409, "REQUEST_DATE_DUPLICATE", "This request date already exists")
    previous_dates = _request_effective_dates(request)
    entity = _date_option_entity(request, payload)
    session.add(entity)
    await session.flush()
    await _invalidate_route_plans_for_dates(
        session,
        request.warehouse_id,
        previous_dates | _request_effective_dates(request),
    )
    return entity


async def update_date_option(
    session: AsyncSession, option_id: UUID, payload: RequestDateOptionUpdate
) -> RequestDateOption:
    """Patch a date option while validating its effective time window."""

    entity = await get_required(session, RequestDateOption, option_id, "request_date_option")
    request = await get_required(session, LogisticsRequest, entity.request_id, "request")
    _reject_rwms_source_edit(
        request,
        "RWMS-owned accepted dates can only be changed by synchronizing the source feed",
    )
    previous_dates = _request_effective_dates(request)
    previous_date = entity.date
    values = payload.model_dump(exclude_unset=True)
    start = values.get("window_start", entity.window_start)
    end = values.get("window_end", entity.window_end)
    is_hard = values.get("is_hard", entity.is_hard)
    travel_zone_hours = values.get("travel_zone_hours", entity.travel_zone_hours)
    if (start is None) != (end is None):
        raise ApiError(
            422,
            "INVALID_TIME_WINDOW",
            "window_start and window_end must both be set or both omitted",
        )
    if start is not None and end is not None and end <= start:
        raise ApiError(422, "INVALID_TIME_WINDOW", "window_end must be after window_start")
    if travel_zone_hours is not None and (start is None or not is_hard):
        raise ApiError(
            422,
            "INVALID_TRAVEL_ZONE",
            "travel_zone_hours requires a complete hard time window",
        )
    apply_update(entity, payload)
    if request.scheduled_date == previous_date:
        request.scheduled_date = entity.date
    await _invalidate_route_plans_for_dates(
        session,
        request.warehouse_id,
        previous_dates | _request_effective_dates(request),
    )
    await session.flush()
    return entity


async def delete_date_option(session: AsyncSession, option_id: UUID) -> None:
    """Delete one acceptable date and clear an assignment that referenced it."""

    entity = await get_required(session, RequestDateOption, option_id, "request_date_option")
    request = await get_required(session, LogisticsRequest, entity.request_id, "request")
    _reject_rwms_source_edit(
        request,
        "RWMS-owned accepted dates can only be changed by synchronizing the source feed",
    )
    previous_dates = _request_effective_dates(request)
    if request.scheduled_date == entity.date:
        request.scheduled_date = None
    await session.delete(entity)
    await session.flush()
    await _invalidate_route_plans_for_dates(
        session,
        request.warehouse_id,
        previous_dates | _request_effective_dates(request),
    )


async def delete_request(session: AsyncSession, request_id: UUID) -> None:
    """Delete an unplanned source request and all owned date/task rows."""

    entity = await get_request(session, request_id)
    _reject_rwms_source_edit(
        entity,
        "An RWMS-owned request can only be removed or cancelled in the authoritative service",
    )
    await _invalidate_route_plans_for_dates(
        session,
        entity.warehouse_id,
        _request_effective_dates(entity),
    )
    task_ids = [task.id for task in entity.tasks]
    referenced = False
    if task_ids:
        assigned_reference = await session.scalar(
            select(RouteStop.id).where(RouteStop.task_id.in_(task_ids)).limit(1)
        )
        unassigned_reference = await session.scalar(
            select(UnassignedTask.id).where(UnassignedTask.task_id.in_(task_ids)).limit(1)
        )
        referenced = assigned_reference is not None or unassigned_reference is not None
    if referenced:
        raise ApiError(
            409,
            "REQUEST_ALREADY_PLANNED",
            "The request is referenced by a saved plan and cannot be deleted",
        )
    await session.delete(entity)
    await session.flush()


async def list_catalog[MutableModel: Base](
    session: AsyncSession, model: type[MutableModel], warehouse_id: UUID
) -> Sequence[MutableModel]:
    """Expose the generic warehouse-list query to thin API routes."""

    await require_warehouse(session, warehouse_id)
    return await list_for_warehouse(session, model, warehouse_id)
