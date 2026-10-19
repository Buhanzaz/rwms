"""CRUD workflows for warehouse-owned logistics inputs and planning resources."""

from __future__ import annotations

from collections.abc import Awaitable, Callable, Sequence
from datetime import date, datetime, time, timedelta
from hashlib import sha256
from typing import cast
from uuid import UUID
from zoneinfo import ZoneInfo

from pydantic import BaseModel
from sqlalchemy import and_, delete, func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.db import Base
from app.errors import ApiError, not_found
from app.models import (
    Driver,
    DriverShift,
    LogisticsRequest,
    PlanningTask,
    RequestDateOption,
    RouteCycle,
    RoutePlan,
    RouteStop,
    Trailer,
    UnassignedTask,
    Vehicle,
    VehicleLoadProfile,
    Warehouse,
    WarehouseIsochroneTariff,
)
from app.models.domain import (
    CatalogVersionMixin,
    PlanStatus,
    RequestStatus,
    RequestType,
    TaskStatus,
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
)
from app.schemas.geocoding import ResolvedAddress
from app.services import plans as plan_service
from app.services.request_reschedule_fence import reject_active_request_reschedules
from app.services.vehicle_availability import (
    VehicleAvailabilityPolicy,
    recurring_shift_intervals,
)

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


def _routing_coordinates_ready(latitude: float | None, longitude: float | None) -> bool:
    """Return whether a complete route origin is not the conventional 0,0 placeholder."""

    return latitude is not None and longitude is not None and not (latitude == 0 and longitude == 0)


def _shift_duration_seconds(start: time, end: time) -> int:
    """Return the interval, treating only an earlier end as the next local day."""

    duration = (
        end.hour * 3600
        + end.minute * 60
        + end.second
        - start.hour * 3600
        - start.minute * 60
        - start.second
    )
    return duration + 24 * 3600 if duration < 0 else duration


def _shift_interval(day: date, start: time, end: time) -> tuple[datetime, datetime]:
    """Materialize one repeated local shift interval without assigning a timezone offset."""

    starts_at = datetime.combine(day, start)
    ends_at = datetime.combine(day, end)
    if ends_at < starts_at:
        ends_at += timedelta(days=1)
    return starts_at, ends_at


def _shift_ranges_overlap(
    *,
    left_from: date,
    left_to: date,
    left_start: time,
    left_end: time,
    right_from: date,
    right_to: date,
    right_start: time,
    right_end: time,
) -> bool:
    """Return whether two bounded recurring ranges share any positive local-time interval."""

    left_day = left_from
    while left_day <= left_to:
        left_interval = _shift_interval(left_day, left_start, left_end)
        right_day = max(right_from, left_day - timedelta(days=1))
        right_last = min(right_to, left_day + timedelta(days=1))
        while right_day <= right_last:
            right_interval = _shift_interval(right_day, right_start, right_end)
            if max(left_interval[0], right_interval[0]) < min(left_interval[1], right_interval[1]):
                return True
            right_day += timedelta(days=1)
        left_day += timedelta(days=1)
    return False


def _shift_lock_key(resource: str, resource_id: UUID) -> int:
    """Derive a stable signed PostgreSQL advisory key for one shift resource."""

    digest = sha256(f"rwms-logistics:shift:{resource}:{resource_id}".encode()).digest()
    return int.from_bytes(digest[:8], byteorder="big", signed=True)


def _trailer_assignment_lock_key(trailer_id: UUID) -> int:
    """Derive the transaction fence shared by trailer assignment and relocation."""

    digest = sha256(f"rwms-logistics:trailer-assignment:{trailer_id}".encode()).digest()
    return int.from_bytes(digest[:8], byteorder="big", signed=True)


async def _lock_trailer_assignment(session: AsyncSession, trailer_id: UUID) -> None:
    """Serialize a trailer relocation with vehicles selecting it as their default."""

    await session.execute(
        select(func.pg_advisory_xact_lock(_trailer_assignment_lock_key(trailer_id)))
    )


def _driver_pool_lock_key(warehouse_id: UUID) -> int:
    """Derive the transaction fence for one warehouse-wide driver audience."""

    digest = sha256(f"rwms-logistics:driver-pool:{warehouse_id}".encode()).digest()
    return int.from_bytes(digest[:8], byteorder="big", signed=True)


async def _ensure_driver_pool_available(
    session: AsyncSession,
    warehouse_id: UUID,
    *,
    exclude_id: UUID | None = None,
) -> None:
    """Serialize and reject a new warehouse-wide pool when one is already persisted."""

    await session.execute(select(func.pg_advisory_xact_lock(_driver_pool_lock_key(warehouse_id))))
    statement = select(Driver.id).where(
        Driver.warehouse_id == warehouse_id,
        Driver.rwms_assignment_mode == "WAREHOUSE_DRIVERS",
    )
    if exclude_id is not None:
        statement = statement.where(Driver.id != exclude_id)
    if await session.scalar(statement.limit(1)) is not None:
        raise ApiError(
            409,
            "WAREHOUSE_DRIVER_POOL_EXISTS",
            "The warehouse already has a warehouse-wide driver pool",
        )


async def _lock_shift_resources(
    session: AsyncSession,
    *,
    driver_ids: set[UUID],
    vehicle_ids: set[UUID],
) -> None:
    """Serialize overlap checks for every affected driver and vehicle without deadlocks."""

    keys = {
        *(_shift_lock_key("driver", resource_id) for resource_id in driver_ids),
        *(_shift_lock_key("vehicle", resource_id) for resource_id in vehicle_ids),
    }
    for key in sorted(keys):
        await session.execute(select(func.pg_advisory_xact_lock(key)))


async def _get_versioned_catalog_entity[MutableModel: Base](
    session: AsyncSession,
    model: type[MutableModel],
    entity_id: UUID,
    resource_name: str,
    expected_version: int,
) -> MutableModel:
    """Lock one mutable catalog row and reject a stale optimistic command."""

    entity = await session.get(model, entity_id, with_for_update=True)
    if entity is None:
        raise not_found(resource_name, entity_id)
    _assert_catalog_version(
        cast(CatalogVersionMixin, entity),
        resource_name,
        expected_version,
    )
    return entity


def _assert_catalog_version(
    entity: CatalogVersionMixin,
    resource_name: str,
    expected_version: int,
) -> None:
    """Reject a stale command after its mutable catalog row has been locked."""

    versioned = entity
    if versioned.version != expected_version:
        raise ApiError(
            409,
            "CATALOG_VERSION_CONFLICT",
            "The catalog resource changed after it was loaded",
            extra={
                "resource": resource_name,
                "expected_version": expected_version,
                "actual_version": versioned.version,
            },
        )


def _advance_catalog_version(entity: CatalogVersionMixin) -> None:
    """Advance an already locked mutable catalog aggregate exactly once per command."""

    entity.version += 1


DEFAULT_ISOCHRONE_TARIFFS = (
    (60, 10_000),
    (120, 15_000),
    (180, 20_000),
    (240, 25_000),
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
    """Archive stale mutable heads without deleting confirmed plans or revision history."""

    await plan_service.archive_mutable_plans_for_dates(session, warehouse_id, dates)


async def _invalidate_mutable_route_plans_for_warehouse(
    session: AsyncSession,
    warehouse_id: UUID,
) -> None:
    """Archive recomputable route heads for one changed warehouse projection."""

    dates = set(
        await session.scalars(
            select(RoutePlan.date).where(
                RoutePlan.warehouse_id == warehouse_id,
                RoutePlan.status.in_(plan_service.MUTABLE_PLAN_STATUSES),
            )
        )
    )
    await plan_service.archive_mutable_plans_for_dates(session, warehouse_id, dates)


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


def _tariff_entities(
    values: Sequence[tuple[int, int]],
) -> list[WarehouseIsochroneTariff]:
    """Build persistence rows from an already validated ordered tariff sequence."""

    return [
        WarehouseIsochroneTariff(travel_minutes=minutes, price_rubles=price)
        for minutes, price in values
    ]


async def _insert_canonical_warehouse(
    session: AsyncSession,
    identity: RwmsWarehouseIdentity,
    resolved: ResolvedAddress | None = None,
) -> Warehouse:
    """Insert one routable canonical identity, tolerating a concurrent first discovery."""

    if identity.routing_ready and _routing_coordinates_ready(identity.latitude, identity.longitude):
        assert identity.latitude is not None
        assert identity.longitude is not None
        latitude = identity.latitude
        longitude = identity.longitude
    elif resolved is not None and _routing_coordinates_ready(resolved.latitude, resolved.longitude):
        assert resolved is not None
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
        address=identity.address,
        timezone=identity.timezone,
        latitude=latitude,
        longitude=longitude,
        representative=identity.representative,
        routing_ready=True,
        settings=PlanningSettings().model_dump(mode="json"),
        isochrone_tariffs=_tariff_entities(DEFAULT_ISOCHRONE_TARIFFS),
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
    resolve_address: Callable[[RwmsWarehouseIdentity], Awaitable[ResolvedAddress]] | None = None,
) -> list[Warehouse]:
    """Reconcile RWMS identities and isolate address-resolution failures per warehouse."""

    if len({identity.warehouse_id for identity in identities}) != len(identities):
        raise ApiError(
            502,
            "RWMS_WAREHOUSE_DIRECTORY_RESPONSE_INVALID",
            "RWMS warehouse directory contains duplicate identities",
        )
    materialized: list[Warehouse] = []
    for identity in identities:
        owner_coordinates_available = identity.routing_ready and _routing_coordinates_ready(
            identity.latitude, identity.longitude
        )
        current = await session.scalar(
            select(Warehouse).where(Warehouse.external_warehouse_id == identity.warehouse_id)
        )
        current_address_point_still_valid = (
            not owner_coordinates_available
            and current is not None
            and current.routing_ready
            and _routing_coordinates_ready(current.latitude, current.longitude)
            and identity.address is not None
            and current.address == identity.address
            and current.city == identity.city
        )
        resolved = None
        if (
            not owner_coordinates_available
            and not current_address_point_still_valid
            and identity.address is not None
            and resolve_address is not None
        ):
            try:
                resolved = await resolve_address(identity)
                if not _routing_coordinates_ready(resolved.latitude, resolved.longitude):
                    resolved = None
            except ApiError:
                resolved = None

        entity = await session.scalar(
            select(Warehouse)
            .where(Warehouse.external_warehouse_id == identity.warehouse_id)
            .with_for_update()
        )
        inserted = entity is None
        if entity is None:
            if not identity.routing_ready:
                if resolved is None:
                    continue
            entity = await _insert_canonical_warehouse(session, identity, resolved)

        address_derived_point_still_valid = (
            not owner_coordinates_available
            and entity.routing_ready
            and _routing_coordinates_ready(entity.latitude, entity.longitude)
            and identity.address is not None
            and entity.address == identity.address
            and entity.city == identity.city
        )
        effective_routing_ready = (
            owner_coordinates_available or address_derived_point_still_valid or resolved is not None
        )
        route_facts_changed = (
            entity.external_warehouse_version != identity.warehouse_version
            or entity.routing_ready != effective_routing_ready
            or (
                owner_coordinates_available
                and (entity.latitude != identity.latitude or entity.longitude != identity.longitude)
            )
            or (
                resolved is not None
                and (entity.latitude != resolved.latitude or entity.longitude != resolved.longitude)
            )
        )
        catalog_facts_changed = not inserted and (
            route_facts_changed
            or entity.name != identity.name
            or entity.city != identity.city
            or entity.address != identity.address
            or entity.timezone != identity.timezone
            or entity.representative != identity.representative
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
        elif resolved is not None:
            entity.latitude = resolved.latitude
            entity.longitude = resolved.longitude
        if catalog_facts_changed:
            _advance_catalog_version(entity)
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
    if identity.routing_ready and _routing_coordinates_ready(identity.latitude, identity.longitude):
        assert identity.latitude is not None
        assert identity.longitude is not None
        address = identity.address
        latitude = identity.latitude
        longitude = identity.longitude
    elif resolved is not None and _routing_coordinates_ready(resolved.latitude, resolved.longitude):
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
        **payload.model_dump(exclude={"external_warehouse_id", "isochrone_tariffs"}),
        isochrone_tariffs=_tariff_entities(
            [(item.travel_minutes, item.price_rubles) for item in payload.isochrone_tariffs]
        ),
    )
    session.add(entity)
    await session.flush()
    return entity


async def update_warehouse(
    session: AsyncSession, warehouse_id: UUID, payload: WarehouseUpdate
) -> Warehouse:
    """Update warehouse fields and validate the effective local hours."""

    entity = await _get_versioned_catalog_entity(
        session,
        Warehouse,
        warehouse_id,
        "warehouse",
        payload.expected_version,
    )
    values = payload.model_dump(exclude_unset=True, exclude={"expected_version"})
    start = values.get("working_day_start", entity.working_day_start)
    end = values.get("working_day_end", entity.working_day_end)
    if end <= start:
        raise ApiError(422, "INVALID_WORKING_DAY", "working_day_end must be after start")
    values = payload.model_dump(
        exclude_unset=True,
        exclude={"expected_version", "isochrone_tariffs"},
    )
    for field, value in values.items():
        setattr(entity, field, value)
    if payload.isochrone_tariffs is not None:
        entity.isochrone_tariffs = _tariff_entities(
            [(item.travel_minutes, item.price_rubles) for item in payload.isochrone_tariffs]
        )
    _advance_catalog_version(entity)
    await session.flush()
    return entity


def _require_staff_driver_identity(
    identity: RwmsDriverIdentity | None,
    worker_id: UUID | None,
) -> RwmsDriverIdentity:
    """Validate an exact RWMS worker as a staff route-planning identity."""

    if worker_id is None or identity is None or identity.worker_id != worker_id:
        raise ApiError(422, "RWMS_DRIVER_NOT_FOUND", "RWMS worker is not eligible")
    if identity.employment_type != "STAFF":
        raise ApiError(
            422,
            "RWMS_DRIVER_NOT_STAFF",
            "Для маршрута можно выбрать только штатного водителя RWMS.",
        )
    return identity


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
        name = _require_staff_driver_identity(
            identity,
            payload.external_worker_id,
        ).display_name
    else:
        await _ensure_driver_pool_available(session, warehouse_id)
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

    entity = await _get_versioned_catalog_entity(
        session,
        Driver,
        driver_id,
        "driver",
        payload.expected_version,
    )
    values = payload.model_dump(exclude_unset=True, exclude={"expected_version"})
    mode = values.get("rwms_assignment_mode", entity.rwms_assignment_mode)
    worker_id = values.get("external_worker_id", entity.external_worker_id)
    if mode == "ASSIGNED_DRIVER":
        entity.name = _require_staff_driver_identity(identity, worker_id).display_name
    else:
        if worker_id is not None:
            raise ApiError(
                422,
                "RWMS_DRIVER_ASSIGNMENT_INVALID",
                "WAREHOUSE_DRIVERS requires external_worker_id to be null",
            )
        if entity.rwms_assignment_mode != "WAREHOUSE_DRIVERS":
            await _ensure_driver_pool_available(
                session,
                entity.warehouse_id,
                exclude_id=entity.id,
            )
        entity.name = "Водители склада"
    for field, value in values.items():
        setattr(entity, field, value)
    _advance_catalog_version(entity)
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


async def get_vehicle(session: AsyncSession, vehicle_id: UUID) -> Vehicle:
    """Reload one vehicle with its response-owned axle-load profile aggregate."""

    entity = await session.scalar(
        select(Vehicle).where(Vehicle.id == vehicle_id).options(selectinload(Vehicle.load_profiles))
    )
    if entity is None:
        raise not_found("vehicle", vehicle_id)
    return entity


async def update_vehicle(
    session: AsyncSession, vehicle_id: UUID, payload: VehicleUpdate
) -> Vehicle:
    """Update vehicle availability, capacity, speeds, or labels."""

    entity = await _get_versioned_catalog_entity(
        session,
        Vehicle,
        vehicle_id,
        "vehicle",
        payload.expected_version,
    )
    values = payload.model_dump(exclude_unset=True, exclude={"expected_version"})
    await _validate_vehicle_trailer_assignment(
        session,
        warehouse_id=entity.warehouse_id,
        can_use_trailer=values.get("can_use_trailer", entity.can_use_trailer),
        trailer_id=values.get("default_trailer_id", entity.default_trailer_id),
    )
    apply_update(entity, payload, exclude={"expected_version"})
    _advance_catalog_version(entity)
    await session.flush()
    return await get_vehicle(session, entity.id)


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
    return await get_vehicle(session, entity.id)


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
    _assert_catalog_version(entity, "vehicle", payload.vehicle.expected_version)
    values = payload.vehicle.model_dump(
        exclude_unset=True,
        exclude={"expected_version"},
    )
    await _validate_vehicle_trailer_assignment(
        session,
        warehouse_id=entity.warehouse_id,
        can_use_trailer=values.get("can_use_trailer", entity.can_use_trailer),
        trailer_id=values.get("default_trailer_id", entity.default_trailer_id),
    )
    apply_update(entity, payload.vehicle, exclude={"expected_version"})
    entity.load_profiles.clear()
    await session.flush()
    entity.load_profiles.extend(
        VehicleLoadProfile(**profile.model_dump(mode="json")) for profile in payload.load_profiles
    )
    _advance_catalog_version(entity)
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
    await _lock_trailer_assignment(session, trailer_id)
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

    entity = await _get_versioned_catalog_entity(
        session,
        Trailer,
        trailer_id,
        "trailer",
        payload.expected_version,
    )
    apply_update(entity, payload, exclude={"expected_version"})
    _advance_catalog_version(entity)
    await session.flush()
    return entity


async def relocate_vehicle(
    session: AsyncSession,
    vehicle_id: UUID,
    target_warehouse_id: UUID,
    expected_version: int,
) -> Vehicle:
    """Permanently move an unassigned vehicle between configured warehouse objects."""

    await _lock_shift_resources(session, driver_ids=set(), vehicle_ids={vehicle_id})
    entity = await _get_versioned_catalog_entity(
        session,
        Vehicle,
        vehicle_id,
        "vehicle",
        expected_version,
    )
    await require_warehouse(session, target_warehouse_id)
    if entity.warehouse_id == target_warehouse_id:
        raise ApiError(
            409,
            "CATALOG_RELOCATION_TARGET_UNCHANGED",
            "The vehicle already belongs to the target warehouse",
        )
    if entity.default_trailer_id is not None:
        raise ApiError(
            409,
            "VEHICLE_HAS_DEFAULT_TRAILER",
            "Detach the vehicle's default trailer before relocation",
        )
    active_shift_id = await session.scalar(
        select(DriverShift.id)
        .where(
            DriverShift.vehicle_id == vehicle_id,
            DriverShift.active.is_(True),
        )
        .order_by(DriverShift.id)
        .limit(1)
    )
    if active_shift_id is not None:
        raise ApiError(
            409,
            "VEHICLE_HAS_ACTIVE_SHIFTS",
            "Deactivate every active vehicle shift before relocation",
            extra={"shift_id": str(active_shift_id)},
        )
    duplicate_id = await session.scalar(
        select(Vehicle.id)
        .where(
            Vehicle.warehouse_id == target_warehouse_id,
            Vehicle.registration_number == entity.registration_number,
            Vehicle.id != entity.id,
        )
        .limit(1)
    )
    if duplicate_id is not None:
        raise ApiError(
            409,
            "VEHICLE_REGISTRATION_CONFLICT",
            "The target warehouse already has a vehicle with this registration number",
        )
    entity.warehouse_id = target_warehouse_id
    _advance_catalog_version(entity)
    await session.flush()
    return await get_vehicle(session, entity.id)


async def relocate_trailer(
    session: AsyncSession,
    trailer_id: UUID,
    target_warehouse_id: UUID,
    expected_version: int,
) -> Trailer:
    """Permanently move an unattached trailer between configured warehouse objects."""

    await _lock_trailer_assignment(session, trailer_id)
    entity = await _get_versioned_catalog_entity(
        session,
        Trailer,
        trailer_id,
        "trailer",
        expected_version,
    )
    await require_warehouse(session, target_warehouse_id)
    if entity.warehouse_id == target_warehouse_id:
        raise ApiError(
            409,
            "CATALOG_RELOCATION_TARGET_UNCHANGED",
            "The trailer already belongs to the target warehouse",
        )
    referencing_vehicle_id = await session.scalar(
        select(Vehicle.id)
        .where(Vehicle.default_trailer_id == trailer_id)
        .order_by(Vehicle.id)
        .limit(1)
    )
    if referencing_vehicle_id is not None:
        raise ApiError(
            409,
            "TRAILER_IS_DEFAULT_FOR_VEHICLE",
            "Detach the trailer from every vehicle before relocation",
            extra={"vehicle_id": str(referencing_vehicle_id)},
        )
    duplicate_id = await session.scalar(
        select(Trailer.id)
        .where(
            Trailer.warehouse_id == target_warehouse_id,
            Trailer.registration_number == entity.registration_number,
            Trailer.id != entity.id,
        )
        .limit(1)
    )
    if duplicate_id is not None:
        raise ApiError(
            409,
            "TRAILER_REGISTRATION_CONFLICT",
            "The target warehouse already has a trailer with this registration number",
        )
    entity.warehouse_id = target_warehouse_id
    _advance_catalog_version(entity)
    await session.flush()
    return entity


async def delete_vehicle(
    session: AsyncSession,
    vehicle_id: UUID,
    expected_version: int,
) -> None:
    """Delete only a vehicle that has never been retained by a driver shift."""

    await _lock_shift_resources(session, driver_ids=set(), vehicle_ids={vehicle_id})
    entity = await _get_versioned_catalog_entity(
        session,
        Vehicle,
        vehicle_id,
        "vehicle",
        expected_version,
    )
    shift = await session.scalar(
        select(DriverShift)
        .where(DriverShift.vehicle_id == vehicle_id)
        .order_by(DriverShift.active.desc(), DriverShift.id)
        .limit(1)
    )
    if shift is not None:
        if shift.active:
            raise ApiError(
                409,
                "VEHICLE_HAS_ACTIVE_SHIFTS",
                "Deactivate every active vehicle shift before deleting the vehicle",
                extra={"shift_id": str(shift.id)},
            )
        raise ApiError(
            409,
            "VEHICLE_HAS_LINKED_SHIFTS",
            "Delete is forbidden while the vehicle has retained driver-shift history",
            extra={"shift_id": str(shift.id)},
        )
    await session.delete(entity)
    await session.flush()


async def delete_trailer(
    session: AsyncSession,
    trailer_id: UUID,
    expected_version: int,
) -> None:
    """Delete only a trailer that is not selected as any vehicle's default."""

    await _lock_trailer_assignment(session, trailer_id)
    entity = await _get_versioned_catalog_entity(
        session,
        Trailer,
        trailer_id,
        "trailer",
        expected_version,
    )
    referencing_vehicle_id = await session.scalar(
        select(Vehicle.id)
        .where(Vehicle.default_trailer_id == trailer_id)
        .order_by(Vehicle.id)
        .limit(1)
    )
    if referencing_vehicle_id is not None:
        raise ApiError(
            409,
            "TRAILER_IS_DEFAULT_FOR_VEHICLE",
            "Detach the trailer from every vehicle before deleting it",
            extra={"vehicle_id": str(referencing_vehicle_id)},
        )
    await session.delete(entity)
    await session.flush()


async def _require_same_warehouse_shift_resources(
    session: AsyncSession,
    warehouse_id: UUID,
    driver_id: UUID,
    vehicle_id: UUID,
    *,
    date_from: date,
    date_to: date,
    start_time: time,
    end_time: time,
    vehicle_availability: VehicleAvailabilityPolicy | None,
) -> None:
    """Require a local driver and a vehicle based at the shift warehouse throughout."""

    driver = await get_required(session, Driver, driver_id, "driver")
    vehicle = await get_required(session, Vehicle, vehicle_id, "vehicle")
    if driver.warehouse_id != warehouse_id:
        raise ApiError(
            422,
            "SHIFT_WAREHOUSE_MISMATCH",
            "Driver must belong to the shift warehouse",
        )
    if vehicle_availability is None:
        if vehicle.warehouse_id != warehouse_id:
            raise ApiError(
                422,
                "SHIFT_WAREHOUSE_MISMATCH",
                "Vehicle must belong to the shift warehouse",
            )
        return

    target_warehouse = await require_warehouse(session, warehouse_id)
    home_warehouse = await require_warehouse(session, vehicle.warehouse_id)
    intervals = recurring_shift_intervals(
        date_from,
        date_to,
        start_time,
        end_time,
        ZoneInfo(target_warehouse.timezone),
    )
    if any(
        not vehicle_availability.available_for_interval(
            vehicle.id,
            home_warehouse.external_warehouse_id,
            target_warehouse.external_warehouse_id,
            interval_start,
            interval_end,
        )
        for interval_start, interval_end in intervals
    ):
        raise ApiError(
            409,
            "SHIFT_VEHICLE_OPERATIONAL_WAREHOUSE_MISMATCH",
            "Vehicle is reserved or is not operationally based at the shift warehouse",
        )


async def _ensure_shift_available(
    session: AsyncSession,
    *,
    driver_id: UUID,
    vehicle_id: UUID,
    date_from: date,
    date_to: date,
    start_time: time,
    end_time: time,
    exclude_id: UUID | None = None,
) -> None:
    """Reject any positive recurring interval overlap for either locked resource."""

    statement = select(DriverShift).where(
        DriverShift.active.is_(True),
        DriverShift.date_from <= date_to + timedelta(days=1),
        DriverShift.date_to >= date_from - timedelta(days=1),
        or_(DriverShift.driver_id == driver_id, DriverShift.vehicle_id == vehicle_id),
    )
    if exclude_id is not None:
        statement = statement.where(DriverShift.id != exclude_id)
    candidates = list(await session.scalars(statement.order_by(DriverShift.id)))
    conflict = next(
        (
            candidate
            for candidate in candidates
            if _shift_ranges_overlap(
                left_from=date_from,
                left_to=date_to,
                left_start=start_time,
                left_end=end_time,
                right_from=candidate.date_from,
                right_to=candidate.date_to,
                right_start=candidate.start_time,
                right_end=candidate.end_time,
            )
        ),
        None,
    )
    if conflict is not None:
        code = (
            "DRIVER_SHIFT_OVERLAP" if conflict.driver_id == driver_id else "VEHICLE_SHIFT_OVERLAP"
        )
        raise ApiError(409, code, "The active shift overlaps an existing resource assignment")


async def create_shift(
    session: AsyncSession,
    warehouse_id: UUID,
    payload: ShiftCreate,
    *,
    vehicle_availability: VehicleAvailabilityPolicy | None = None,
) -> DriverShift:
    """Create a non-overlapping aware shift for warehouse-owned resources."""

    await require_warehouse(session, warehouse_id)
    if payload.active:
        await _lock_shift_resources(
            session,
            driver_ids={payload.driver_id},
            vehicle_ids={payload.vehicle_id},
        )
    await _require_same_warehouse_shift_resources(
        session,
        warehouse_id,
        payload.driver_id,
        payload.vehicle_id,
        date_from=payload.date_from,
        date_to=payload.date_to,
        start_time=payload.start_time,
        end_time=payload.end_time,
        vehicle_availability=vehicle_availability,
    )
    if payload.active:
        await _ensure_shift_available(
            session,
            driver_id=payload.driver_id,
            vehicle_id=payload.vehicle_id,
            date_from=payload.date_from,
            date_to=payload.date_to,
            start_time=payload.start_time,
            end_time=payload.end_time,
        )
    entity = DriverShift(warehouse_id=warehouse_id, **payload.model_dump())
    session.add(entity)
    await session.flush()
    return entity


async def update_shift(
    session: AsyncSession,
    shift_id: UUID,
    payload: ShiftUpdate,
    *,
    vehicle_availability: VehicleAvailabilityPolicy | None = None,
) -> DriverShift:
    """Update a shift after recomputing ownership, interval, and overlap checks."""

    entity = await _get_versioned_catalog_entity(
        session,
        DriverShift,
        shift_id,
        "shift",
        payload.expected_version,
    )
    values = payload.model_dump(exclude_unset=True, exclude={"expected_version"})
    driver_id = values.get("driver_id", entity.driver_id)
    vehicle_id = values.get("vehicle_id", entity.vehicle_id)
    date_from = values.get("date_from", entity.date_from)
    date_to = values.get("date_to", entity.date_to)
    start_time = values.get("start_time", entity.start_time)
    end_time = values.get("end_time", entity.end_time)
    break_minutes = values.get("break_minutes", entity.break_minutes)
    active = values.get("active", entity.active)
    if date_to < date_from or (date_to - date_from).days > 30:
        raise ApiError(422, "INVALID_SHIFT_DATE_RANGE", "Shift date range must be 1-31 days")
    duration_seconds = _shift_duration_seconds(start_time, end_time)
    if duration_seconds == 0:
        raise ApiError(
            422,
            "INVALID_SHIFT_DURATION",
            "Shift start and end must define a non-zero duration",
        )
    if break_minutes * 60 >= duration_seconds:
        raise ApiError(
            422,
            "INVALID_SHIFT_BREAK",
            "Shift break must be shorter than the shift duration",
        )
    if active:
        await _lock_shift_resources(
            session,
            driver_ids={entity.driver_id, driver_id},
            vehicle_ids={entity.vehicle_id, vehicle_id},
        )
    await _require_same_warehouse_shift_resources(
        session,
        entity.warehouse_id,
        driver_id,
        vehicle_id,
        date_from=date_from,
        date_to=date_to,
        start_time=start_time,
        end_time=end_time,
        vehicle_availability=vehicle_availability,
    )
    if active:
        await _ensure_shift_available(
            session,
            driver_id=driver_id,
            vehicle_id=vehicle_id,
            date_from=date_from,
            date_to=date_to,
            start_time=start_time,
            end_time=end_time,
            exclude_id=entity.id,
        )
    apply_update(entity, payload, exclude={"expected_version"})
    _advance_catalog_version(entity)
    await session.flush()
    return entity


async def delete_catalog_entity[MutableModel: Base](
    session: AsyncSession,
    model: type[MutableModel],
    entity_id: UUID,
    resource_name: str,
    expected_version: int,
) -> None:
    """Delete an explicitly addressed catalog entity under its optimistic fence."""

    entity = await session.get(model, entity_id, with_for_update=True)
    if entity is None:
        raise not_found(resource_name, entity_id)
    _assert_catalog_version(
        cast(CatalogVersionMixin, entity),
        resource_name,
        expected_version,
    )
    await session.delete(entity)
    await session.flush()


def _build_task(request: LogisticsRequest, part_number: int, quantity: int) -> PlanningTask:
    """Copy physical and planning fields into one vehicle-sized request part."""

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


async def _confirmed_plan_reference_id(
    session: AsyncSession,
    request: LogisticsRequest,
) -> UUID | None:
    """Return a confirmed plan that owns immutable planning facts for this request."""

    task_ids = [task.id for task in request.tasks]
    if not task_ids:
        return None
    assigned = (
        select(RoutePlan.id)
        .join(RouteCycle, RouteCycle.route_plan_id == RoutePlan.id)
        .join(RouteStop, RouteStop.route_cycle_id == RouteCycle.id)
        .where(
            RouteStop.task_id.in_(task_ids),
            RoutePlan.status == PlanStatus.CONFIRMED,
        )
    )
    unassigned = (
        select(RoutePlan.id)
        .join(UnassignedTask, UnassignedTask.route_plan_id == RoutePlan.id)
        .where(
            UnassignedTask.task_id.in_(task_ids),
            RoutePlan.status == PlanStatus.CONFIRMED,
        )
    )
    return cast(UUID | None, await session.scalar(assigned.union(unassigned).limit(1)))


async def _reject_confirmed_request_mutation(
    session: AsyncSession,
    request: LogisticsRequest,
) -> None:
    """Keep request and task facts immutable once a confirmed plan references them."""

    await reject_active_request_reschedules(session, (request.id,))
    plan_id = await _confirmed_plan_reference_id(session, request)
    if plan_id is not None:
        raise ApiError(
            409,
            "REQUEST_IN_CONFIRMED_PLAN",
            "A request in a confirmed plan cannot be changed or deleted",
            extra={"plan_id": str(plan_id)},
        )


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
    """Create, date, and split a source request atomically."""

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
    for option in payload.date_options:
        session.add(_date_option_entity(entity, option))
    await _replace_request_tasks(session, entity)
    await session.flush()
    return await get_request(session, entity.id)


async def upsert_rwms_request(
    session: AsyncSession,
    warehouse_id: UUID,
    source: RwmsPlanningRequest,
    resolved: ResolvedAddress | None = None,
) -> str:
    """Import one revisioned snapshot using source or address-derived operational coordinates.

    A planning revision may advance without an order-version change because the feed also contains
    customer-slot and asset-reservation facts owned by separate aggregates. Pre-revision stored
    snapshots are upgraded once from the authenticated source and thereafter obey the same strict
    revision replay check. The stored source payload remains byte-for-field authoritative and never
    receives the derived coordinates.
    """

    if source.latitude is not None and source.longitude is not None:
        latitude = source.latitude
        longitude = source.longitude
    elif resolved is not None:
        latitude = resolved.latitude
        longitude = resolved.longitude
    else:
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
        .options(
            selectinload(LogisticsRequest.date_options),
            selectinload(LogisticsRequest.tasks),
        )
        .with_for_update()
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
                latitude=latitude,
                longitude=longitude,
                quantity=source.quantity,
                **default_cargo,
                service_minutes=service_minutes,
                status=RequestStatus.READY,
                contact_name=source.contact_name or source.client_name,
                contact_phone=source.contact_phone or "",
                client_type=source.client_type,
                trailer_access_allowed=source.trailer_access_allowed,
                date_options=date_options,
            ),
        )
        created.source_system = RWMS_SOURCE_SYSTEM
        created.external_id = source.order_id
        created.external_version = source.order_version
        created.external_payload = source_payload
        created.customer_delivery_purpose = source.customer_delivery_purpose
        created.delivery_price_rubles = source.delivery_price_rubles
        created.price_isochrone_minutes = source.price_isochrone_minutes
        await session.flush()
        return "imported"

    if entity.external_version is not None and source.order_version < entity.external_version:
        return "skipped"
    restore_cancelled = entity.status == RequestStatus.CANCELLED
    if restore_cancelled and await _confirmed_plan_reference_id(session, entity) is not None:
        return "skipped"
    cargo_is_missing = all(getattr(entity, field) is None for field in CARGO_PHYSICAL_FIELDS)
    if entity.external_version == source.order_version:
        if entity.external_payload == source_payload:
            if not cargo_is_missing:
                if not restore_cancelled:
                    return "skipped"
                await _invalidate_route_plans_for_dates(
                    session,
                    warehouse_id,
                    _request_effective_dates(entity),
                )
                entity.status = RequestStatus.READY
                for task in entity.tasks:
                    task.status = TaskStatus.READY
                _advance_catalog_version(entity)
                await session.flush()
                return "updated"
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
        "contact_name": source.contact_name or source.client_name,
        "contact_phone": source.contact_phone or "",
        "client_type": source.client_type,
        "trailer_access_allowed": source.trailer_access_allowed,
        "date_options": date_options,
    }
    if cargo_is_missing:
        update_values.update(default_cargo)
    if entity.latitude != latitude or entity.longitude != longitude:
        update_values["latitude"] = latitude
        update_values["longitude"] = longitude
    if entity.quantity != source.quantity:
        update_values["quantity"] = source.quantity
    updated = await update_request(
        session,
        entity.id,
        LogisticsRequestUpdate.model_validate(
            {"expected_version": entity.version, **update_values}
        ),
        from_authoritative_source=True,
    )
    updated.source_system = RWMS_SOURCE_SYSTEM
    updated.external_id = source.order_id
    updated.external_version = source.order_version
    updated.external_payload = source_payload
    updated.customer_delivery_purpose = source.customer_delivery_purpose
    updated.delivery_price_rubles = source.delivery_price_rubles
    updated.price_isochrone_minutes = source.price_isochrone_minutes
    if restore_cancelled:
        updated.status = RequestStatus.READY
        for task in updated.tasks:
            task.status = TaskStatus.READY
    await session.flush()
    return "updated"


async def retire_absent_rwms_requests(
    session: AsyncSession,
    warehouse_id: UUID,
    *,
    present_order_ids: set[UUID],
    date_from: date,
    date_to: date,
) -> int:
    """Cancel unplanned projections omitted from one complete authoritative feed.

    The warehouse/date RWMS endpoint is a complete still-unplanned snapshot. A missing source row
    therefore retires only a locally mutable request intersecting that inclusive range. Source
    payloads, accepted dates, task identities, and archived plan references remain intact for
    history; confirmed plans fence the request from this transition.
    """

    statement = (
        select(LogisticsRequest)
        .where(
            LogisticsRequest.warehouse_id == warehouse_id,
            LogisticsRequest.source_system == RWMS_SOURCE_SYSTEM,
            LogisticsRequest.status.in_((RequestStatus.READY, RequestStatus.UNASSIGNED)),
        )
        .options(
            selectinload(LogisticsRequest.date_options),
            selectinload(LogisticsRequest.tasks),
        )
        .order_by(LogisticsRequest.id)
        .with_for_update()
    )
    if present_order_ids:
        statement = statement.where(LogisticsRequest.external_id.not_in(present_order_ids))
    candidates = list((await session.scalars(statement)).unique().all())
    retiring_candidates: list[tuple[LogisticsRequest, set[date]]] = []
    for request in candidates:
        accepted_dates = {option.date for option in request.date_options}
        if request.scheduled_date is not None:
            accepted_dates.add(request.scheduled_date)
        if not any(date_from <= candidate <= date_to for candidate in accepted_dates):
            continue
        if await _confirmed_plan_reference_id(session, request) is not None:
            continue
        retiring_candidates.append((request, accepted_dates))
    await reject_active_request_reschedules(
        session,
        tuple(request.id for request, _accepted_dates in retiring_candidates),
    )

    retired = 0
    affected_dates: set[date] = set()
    for request, accepted_dates in retiring_candidates:
        request.status = RequestStatus.CANCELLED
        for task in request.tasks:
            task.status = TaskStatus.CANCELLED
        _advance_catalog_version(request)
        affected_dates.update(accepted_dates)
        retired += 1

    await _invalidate_route_plans_for_dates(session, warehouse_id, affected_dates)
    await session.flush()
    return retired


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


async def list_requests_page(
    session: AsyncSession,
    warehouse_ids: Sequence[UUID],
    planning_date: date,
    *,
    limit: int,
    cursor: UUID | None,
) -> tuple[list[LogisticsRequest], int, UUID | None]:
    """Read one exact planning date in stable UUID order with a bounded keyset page."""

    unique_warehouse_ids = tuple(dict.fromkeys(warehouse_ids))
    if not unique_warehouse_ids:
        return [], 0, None
    relevant = or_(
        LogisticsRequest.scheduled_date == planning_date,
        and_(
            LogisticsRequest.scheduled_date.is_(None),
            LogisticsRequest.date_options.any(RequestDateOption.date == planning_date),
        ),
    )
    base = select(LogisticsRequest).where(
        LogisticsRequest.warehouse_id.in_(unique_warehouse_ids),
        relevant,
    )
    total = int(
        await session.scalar(
            select(func.count(LogisticsRequest.id)).where(
                LogisticsRequest.warehouse_id.in_(unique_warehouse_ids),
                relevant,
            )
        )
        or 0
    )
    if cursor is not None:
        base = base.where(LogisticsRequest.id > cursor)
    entities = list(
        (
            await session.scalars(
                base.options(
                    selectinload(LogisticsRequest.date_options),
                    selectinload(LogisticsRequest.tasks),
                )
                .order_by(LogisticsRequest.id)
                .limit(limit + 1)
            )
        )
        .unique()
        .all()
    )
    has_more = len(entities) > limit
    page = entities[:limit]
    next_cursor = page[-1].id if has_more else None
    return page, total, next_cursor


async def update_request(
    session: AsyncSession,
    request_id: UUID,
    payload: LogisticsRequestUpdate,
    *,
    from_authoritative_source: bool = False,
) -> LogisticsRequest:
    """Update local fields or apply a trusted feed refresh under the request lock."""

    entity = await get_request(session, request_id, for_update=True)
    _assert_catalog_version(entity, "request", payload.expected_version)
    await _reject_confirmed_request_mutation(session, entity)
    previous_dates = _request_effective_dates(entity)
    supplied_values = payload.model_dump(
        exclude_unset=True,
        exclude={"expected_version", "date_options"},
    )
    supplied_fields = set(payload.model_fields_set).difference({"expected_version"})
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
    for field, value in changed.items():
        setattr(entity, field, value)
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
    _advance_catalog_version(entity)
    await session.flush()
    return await get_request(session, entity.id)


async def schedule_request(
    session: AsyncSession,
    request_id: UUID,
    payload: RequestScheduleInput,
) -> LogisticsRequest:
    """Assign one accepted date, optionally recording an explicitly agreed new date."""

    entity = await get_request(session, request_id, for_update=True)
    _assert_catalog_version(entity, "request", payload.expected_version)
    await _reject_confirmed_request_mutation(session, entity)
    previous_dates = _request_effective_dates(entity)
    if payload.date is None:
        entity.scheduled_date = None
        await _invalidate_route_plans_for_dates(
            session,
            entity.warehouse_id,
            previous_dates | _request_effective_dates(entity),
        )
        _advance_catalog_version(entity)
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
    _advance_catalog_version(entity)
    await session.flush()
    return await get_request(session, entity.id)


async def set_request_planning_details(
    session: AsyncSession,
    request_id: UUID,
    payload: RequestPlanningDetailsInput,
) -> LogisticsRequest:
    """Atomically store one accepted date's dispatcher planning decisions."""

    entity = await get_request(session, request_id, for_update=True)
    _assert_catalog_version(entity, "request", payload.expected_version)
    await _reject_confirmed_request_mutation(session, entity)
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
        payload.window_start is not None or payload.window_end is not None or payload.is_hard
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
    _advance_catalog_version(entity)
    await session.flush()
    return await get_request(session, entity.id)


async def split_request(
    session: AsyncSession,
    request_id: UUID,
    expected_version: int,
    part_quantities: Sequence[int] | None = None,
) -> LogisticsRequest:
    """Regenerate automatic or explicitly-sized transport subtasks."""

    entity = await get_request(session, request_id, for_update=True)
    _assert_catalog_version(entity, "request", expected_version)
    await _reject_confirmed_request_mutation(session, entity)
    await _invalidate_route_plans_for_dates(
        session,
        entity.warehouse_id,
        _request_effective_dates(entity),
    )
    await _replace_request_tasks(session, entity, part_quantities)
    _advance_catalog_version(entity)
    await session.flush()
    return await get_request(session, entity.id)


async def create_date_option(
    session: AsyncSession, request_id: UUID, payload: RequestDateOptionInput
) -> RequestDateOption:
    """Append one unique acceptable date to an existing request."""

    request = await get_request(session, request_id, for_update=True)
    await _reject_confirmed_request_mutation(session, request)
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
    _advance_catalog_version(request)
    await session.flush()
    return entity


async def update_date_option(
    session: AsyncSession, option_id: UUID, payload: RequestDateOptionUpdate
) -> RequestDateOption:
    """Patch a date option while validating its effective time window."""

    entity = await get_required(session, RequestDateOption, option_id, "request_date_option")
    request = await get_request(session, entity.request_id, for_update=True)
    _assert_catalog_version(request, "request", payload.expected_version)
    await _reject_confirmed_request_mutation(session, request)
    _reject_rwms_source_edit(
        request,
        "RWMS-owned accepted dates can only be changed by synchronizing the source feed",
    )
    previous_dates = _request_effective_dates(request)
    previous_date = entity.date
    values = payload.model_dump(exclude_unset=True, exclude={"expected_version"})
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
    apply_update(entity, payload, exclude={"expected_version"})
    if request.scheduled_date == previous_date:
        request.scheduled_date = entity.date
    await _invalidate_route_plans_for_dates(
        session,
        request.warehouse_id,
        previous_dates | _request_effective_dates(request),
    )
    _advance_catalog_version(request)
    await session.flush()
    return entity


async def delete_date_option(
    session: AsyncSession,
    option_id: UUID,
    expected_version: int,
) -> None:
    """Delete one acceptable date and clear an assignment that referenced it."""

    entity = await get_required(session, RequestDateOption, option_id, "request_date_option")
    request = await get_request(session, entity.request_id, for_update=True)
    _assert_catalog_version(request, "request", expected_version)
    await _reject_confirmed_request_mutation(session, request)
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
    _advance_catalog_version(request)
    await session.flush()


async def delete_request(
    session: AsyncSession,
    request_id: UUID,
    expected_version: int,
) -> None:
    """Delete an unplanned source request and all owned date/task rows."""

    entity = await get_request(session, request_id, for_update=True)
    _assert_catalog_version(entity, "request", expected_version)
    _reject_rwms_source_edit(
        entity,
        "An RWMS-owned request can only be removed or cancelled in the authoritative service",
    )
    await _reject_confirmed_request_mutation(session, entity)
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
    await _invalidate_route_plans_for_dates(
        session,
        entity.warehouse_id,
        _request_effective_dates(entity),
    )
    await session.delete(entity)
    await session.flush()


async def list_catalog[MutableModel: Base](
    session: AsyncSession, model: type[MutableModel], warehouse_id: UUID
) -> Sequence[MutableModel]:
    """Expose the generic warehouse-list query to thin API routes."""

    await require_warehouse(session, warehouse_id)
    return await list_for_warehouse(session, model, warehouse_id)


async def list_catalog_for_warehouses[MutableModel: Base](
    session: AsyncSession,
    model: type[MutableModel],
    warehouse_ids: Sequence[UUID],
) -> Sequence[MutableModel]:
    """List warehouse-owned rows across one already-authorized direct planning group."""

    unique_ids = tuple(dict.fromkeys(warehouse_ids))
    if not unique_ids:
        return ()
    warehouse_column = model.warehouse_id  # type: ignore[attr-defined]
    id_column = model.id  # type: ignore[attr-defined]
    return list(
        (
            await session.scalars(
                select(model)
                .where(warehouse_column.in_(unique_ids))
                .order_by(warehouse_column, id_column)
            )
        )
        .unique()
        .all()
    )


async def list_vehicles_for_warehouses(
    session: AsyncSession,
    warehouse_ids: Sequence[UUID],
    *,
    additional_vehicle_ids: Sequence[UUID] = (),
) -> list[Vehicle]:
    """List home-group and explicitly incoming vehicles with original ownership intact."""

    unique_ids = tuple(dict.fromkeys(warehouse_ids))
    incoming_ids = tuple(dict.fromkeys(additional_vehicle_ids))
    if not unique_ids and not incoming_ids:
        return []
    result = await session.scalars(
        select(Vehicle)
        .where(
            or_(
                Vehicle.warehouse_id.in_(unique_ids),
                Vehicle.id.in_(incoming_ids),
            )
        )
        .options(selectinload(Vehicle.load_profiles))
        .order_by(Vehicle.warehouse_id, Vehicle.id)
    )
    return list(result.unique())
