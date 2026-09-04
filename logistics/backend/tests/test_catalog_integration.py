"""PostGIS integration tests for warehouse catalogs and request invariants."""

from datetime import UTC, date, datetime, time, timedelta
from uuid import uuid4

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.models import Driver, RoutePlan, UnassignedTask
from app.schemas.domain import (
    LogisticsRequestUpdate,
    RequestPlanningDetailsInput,
    RequestScheduleInput,
    RwmsVehicleOperationalAssignment,
    RwmsWarehouseIdentity,
    ShiftCreate,
    ShiftUpdate,
    WarehouseCreate,
)
from app.schemas.geocoding import ResolvedAddress
from app.services import catalog
from app.services.vehicle_availability import VehicleAvailabilityPolicy
from tests.factories import (
    make_driver,
    make_request,
    make_shift,
    make_vehicle,
    make_warehouse,
)

pytestmark = pytest.mark.integration


@pytest.mark.asyncio
async def test_warehouse_binding_uses_default_ordered_isochrone_tariffs(
    db_session: AsyncSession,
) -> None:
    """A canonical depot is deliverable by isochrones without a polygonal coverage zone."""

    external_id = uuid4()
    identity = RwmsWarehouseIdentity(
        warehouseId=external_id,
        warehouseVersion=0,
        name="Canonical depot",
        city="Санкт-Петербург",
        address="Canonical address",
        latitude=None,
        longitude=None,
        timeZone="Europe/Moscow",
        representative=False,
        routingReady=False,
    )
    payload = WarehouseCreate(external_warehouse_id=external_id)

    created = await catalog.create_warehouse(
        db_session,
        payload,
        identity,
        ResolvedAddress(address="Resolved canonical address", latitude=55.75, longitude=37.62),
    )
    assert created.name == identity.name
    assert created.address == "Canonical address"
    assert created.latitude == 55.75
    assert [
        (item.travel_minutes, item.price_rubles) for item in created.isochrone_tariffs
    ] == [(60, 10_000), (120, 15_000), (180, 20_000), (240, 25_000)]


@pytest.mark.asyncio
async def test_monthly_shift_rejects_overlapping_driver_or_vehicle_range(
    db_session: AsyncSession,
) -> None:
    """A resource has only one active daily assignment for any date in the period."""

    warehouse = await make_warehouse(db_session)
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    shift = await make_shift(
        db_session,
        warehouse,
        driver,
        vehicle,
        date_from=date(2026, 8, 1),
        date_to=date(2026, 8, 15),
    )
    assert (shift.date_from, shift.date_to, shift.start_time, shift.end_time) == (
        date(2026, 8, 1),
        date(2026, 8, 15),
        time(8),
        time(20),
    )
    with pytest.raises(ApiError) as overlap:
        await catalog.create_shift(
            db_session,
            warehouse.id,
            ShiftCreate(
                driver_id=driver.id,
                vehicle_id=vehicle.id,
                date_from=date(2026, 8, 15),
                date_to=date(2026, 8, 20),
                start_time=time(9),
                end_time=time(18),
            ),
        )
    assert overlap.value.code == "DRIVER_SHIFT_OVERLAP"


@pytest.mark.asyncio
async def test_moved_vehicle_can_bind_destination_shift_but_cannot_be_double_assigned(
    db_session: AsyncSession,
) -> None:
    """Operational placement replaces the home guard while the physical shift lock remains."""

    planning_date = date(2026, 9, 1)
    source = await make_warehouse(db_session, name="Source")
    destination = await make_warehouse(db_session, name="Destination")
    vehicle = await make_vehicle(db_session, source)
    first_driver = Driver(
        warehouse_id=destination.id,
        external_worker_id=uuid4(),
        rwms_assignment_mode="ASSIGNED_DRIVER",
        name="Destination driver one",
    )
    second_driver = Driver(
        warehouse_id=destination.id,
        external_worker_id=uuid4(),
        rwms_assignment_mode="ASSIGNED_DRIVER",
        name="Destination driver two",
    )
    source_driver = Driver(
        warehouse_id=source.id,
        external_worker_id=uuid4(),
        rwms_assignment_mode="ASSIGNED_DRIVER",
        name="Source driver",
    )
    db_session.add_all((first_driver, second_driver, source_driver))
    await db_session.flush()
    travel_start = datetime(2026, 9, 1, 2, tzinfo=UTC)
    effective_from = datetime(2026, 9, 1, 4, tzinfo=UTC)
    policy = VehicleAvailabilityPolicy(
        (
            RwmsVehicleOperationalAssignment(
                assignment_id=uuid4(),
                version=1,
                transfer_id=uuid4(),
                vehicle_id=vehicle.id,
                source_warehouse_id=source.external_warehouse_id,
                destination_warehouse_id=destination.external_warehouse_id,
                mode="TEMPORARY",
                status="ACTIVE",
                travel_starts_at=travel_start,
                effective_from=effective_from,
                effective_until=effective_from + timedelta(days=2),
                created_at=travel_start - timedelta(days=1),
                updated_at=effective_from,
            ),
        )
    )
    payload = ShiftCreate(
        driver_id=first_driver.id,
        vehicle_id=vehicle.id,
        date_from=planning_date,
        date_to=planning_date,
        start_time=time(8),
        end_time=time(12),
    )

    created = await catalog.create_shift(
        db_session,
        destination.id,
        payload,
        vehicle_availability=policy,
    )

    assert created.warehouse_id == destination.id
    assert vehicle.warehouse_id == source.id
    updated = await catalog.update_shift(
        db_session,
        created.id,
        ShiftUpdate(expected_version=created.version, start_time=time(9)),
        vehicle_availability=policy,
    )
    assert updated.start_time == time(9)
    with pytest.raises(ApiError) as duplicate:
        await catalog.create_shift(
            db_session,
            destination.id,
            payload.model_copy(update={"driver_id": second_driver.id}),
            vehicle_availability=policy,
        )
    assert duplicate.value.code == "VEHICLE_SHIFT_OVERLAP"

    with pytest.raises(ApiError) as stale_source:
        await catalog.create_shift(
            db_session,
            source.id,
            payload.model_copy(
                update={
                    "driver_id": source_driver.id,
                    "date_from": planning_date + timedelta(days=1),
                    "date_to": planning_date + timedelta(days=1),
                }
            ),
            vehicle_availability=policy,
        )
    assert stale_source.value.code == "SHIFT_VEHICLE_OPERATIONAL_WAREHOUSE_MISMATCH"


@pytest.mark.asyncio
async def test_cross_month_overnight_shift_has_positive_bounded_duration(
    db_session: AsyncSession,
) -> None:
    """A recurring 22:00-06:00 shift may cross a month while equal bounds stay invalid."""

    warehouse = await make_warehouse(db_session)
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    shift = await catalog.create_shift(
        db_session,
        warehouse.id,
        ShiftCreate(
            driver_id=driver.id,
            vehicle_id=vehicle.id,
            date_from=date(2026, 8, 31),
            date_to=date(2026, 9, 2),
            start_time=time(22),
            end_time=time(6),
            break_minutes=60,
        ),
    )

    assert shift.date_from == date(2026, 8, 31)
    assert shift.date_to == date(2026, 9, 2)
    assert catalog._shift_duration_seconds(shift.start_time, shift.end_time) == 8 * 3600
    with pytest.raises(ValueError, match="non-zero shift"):
        ShiftCreate(
            driver_id=driver.id,
            vehicle_id=vehicle.id,
            date_from=date(2026, 8, 31),
            date_to=date(2026, 8, 31),
            start_time=time(8),
            end_time=time(8),
        )
    with pytest.raises(ValueError, match="shorter than the shift duration"):
        ShiftCreate(
            driver_id=driver.id,
            vehicle_id=vehicle.id,
            date_from=date(2026, 8, 31),
            date_to=date(2026, 8, 31),
            start_time=time(22),
            end_time=time(6),
            break_minutes=8 * 60,
        )


@pytest.mark.asyncio
async def test_partial_shift_update_rejects_a_break_that_consumes_capacity(
    db_session: AsyncSession,
) -> None:
    """A partial patch validates the persisted times together with the new break."""

    warehouse = await make_warehouse(db_session)
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    shift = await make_shift(
        db_session,
        warehouse,
        driver,
        vehicle,
        date_from=date(2026, 8, 31),
        date_to=date(2026, 8, 31),
        start_time=time(8),
        end_time=time(8, 30),
    )

    with pytest.raises(ApiError) as rejected:
        await catalog.update_shift(
            db_session,
            shift.id,
            ShiftUpdate(expected_version=shift.version, break_minutes=30),
        )

    assert rejected.value.code == "INVALID_SHIFT_BREAK"


@pytest.mark.asyncio
async def test_task_regeneration_rejects_an_unassigned_plan_reference(
    db_session: AsyncSession,
) -> None:
    """A topology change cannot replace stable task IDs retained by an existing plan."""

    warehouse = await make_warehouse(db_session)
    request = await make_request(db_session, warehouse, planning_date=date(2026, 8, 27))
    plan = RoutePlan(
        warehouse_id=warehouse.id,
        date=date(2026, 8, 27),
        name="Plan with unassigned task",
    )
    db_session.add(plan)
    await db_session.flush()
    db_session.add(
        UnassignedTask(
            route_plan_id=plan.id,
            task_id=request.tasks[0].id,
            reason_codes=["NO_SHIFT_CAPACITY"],
            descriptions_ru=["Нет доступной смены"],
        )
    )
    await db_session.flush()

    with pytest.raises(ApiError) as rejected:
        await catalog.set_request_planning_details(
            db_session,
            request.id,
            RequestPlanningDetailsInput(
                expected_version=request.version,
                date=date(2026, 8, 27),
                window_start=time(9),
                window_end=time(12),
                is_hard=True,
                mandatory=False,
                trailer_access_allowed=False,
                contact_name="Контакт",
                contact_phone="+7 900 000-00-00",
            ),
        )

    assert rejected.value.code == "REQUEST_TASKS_ALREADY_PLANNED"
    assert [task.quantity for task in request.tasks] == [2]
    assert await db_session.get(RoutePlan, plan.id) is plan


@pytest.mark.asyncio
async def test_request_mandatory_update_is_copied_to_existing_tasks(
    db_session: AsyncSession,
) -> None:
    """Changing the source obligation updates every still-authoritative task row."""

    warehouse = await make_warehouse(db_session)
    request = await make_request(db_session, warehouse, mandatory=False)
    task_ids = [task.id for task in request.tasks]

    updated = await catalog.update_request(
        db_session,
        request.id,
        LogisticsRequestUpdate(expected_version=request.version, mandatory=True),
    )
    assert updated.mandatory is True
    assert [task.id for task in updated.tasks] == task_ids
    assert all(task.mandatory for task in updated.tasks)


@pytest.mark.asyncio
async def test_stale_request_semantic_command_is_rejected(
    db_session: AsyncSession,
) -> None:
    """A schedule command loaded before a newer request revision cannot apply afterward."""

    warehouse = await make_warehouse(db_session)
    request = await make_request(db_session, warehouse)
    scheduled = await catalog.schedule_request(
        db_session,
        request.id,
        RequestScheduleInput(
            expected_version=request.version,
            date=date(2026, 8, 30),
        ),
    )
    assert scheduled.version == 2

    with pytest.raises(ApiError) as stale:
        await catalog.schedule_request(
            db_session,
            request.id,
            RequestScheduleInput(expected_version=1, date=None),
        )

    assert stale.value.code == "CATALOG_VERSION_CONFLICT"
    assert scheduled.scheduled_date == date(2026, 8, 30)


@pytest.mark.parametrize(
    ("source_system", "request_type"),
    [
        ("RWMS", "DELIVERY"),
        ("RWMS", "PICKUP"),
        ("WAREHOUSE_WORKLOAD_GENERATOR", "DELIVERY"),
        ("WAREHOUSE_WORKLOAD_GENERATOR", "PICKUP"),
    ],
)
@pytest.mark.asyncio
async def test_planning_details_update_operator_mandatory_for_every_request_source(
    db_session: AsyncSession,
    source_system: str,
    request_type: str,
) -> None:
    """Operator-owned mandatory metadata changes without rewriting source-owned facts."""

    warehouse = await make_warehouse(db_session)
    request = await make_request(
        db_session,
        warehouse,
        planning_date=date(2026, 8, 30),
        mandatory=False,
    )
    request.source_system = source_system
    request.type = request_type
    for task in request.tasks:
        task.type = request_type
    source_facts = (
        request.type,
        request.name,
        request.address_label,
        request.latitude,
        request.longitude,
        request.quantity,
    )
    await db_session.flush()

    updated = await catalog.set_request_planning_details(
        db_session,
        request.id,
        RequestPlanningDetailsInput(
            expected_version=request.version,
            date=date(2026, 8, 30),
            window_start=time(10),
            window_end=time(14),
            is_hard=True,
            mandatory=True,
            trailer_access_allowed=True,
            contact_name="Диспетчер",
            contact_phone="+7 900 000-00-00",
        ),
    )

    assert updated.mandatory is True
    assert all(task.mandatory for task in updated.tasks)
    assert (
        updated.type,
        updated.name,
        updated.address_label,
        updated.latitude,
        updated.longitude,
        updated.quantity,
    ) == source_facts


@pytest.mark.asyncio
async def test_rwms_fixed_window_failure_does_not_apply_mandatory_metadata(
    db_session: AsyncSession,
) -> None:
    """RWMS window ownership rejects the whole planning-details command before mutation."""

    warehouse = await make_warehouse(db_session)
    request = await make_request(db_session, warehouse, mandatory=False)
    request.source_system = "RWMS"
    await db_session.flush()

    with pytest.raises(ApiError) as rejected:
        await catalog.set_request_planning_details(
            db_session,
            request.id,
            RequestPlanningDetailsInput(
                expected_version=request.version,
                date=date(2026, 8, 30),
                window_start=time(11),
                window_end=time(15),
                is_hard=True,
                mandatory=True,
                trailer_access_allowed=True,
                contact_name="Диспетчер",
                contact_phone="+7 900 000-00-00",
            ),
        )

    assert rejected.value.code == "RWMS_FIXED_WINDOW_IMMUTABLE"
    assert request.mandatory is False
    assert all(not task.mandatory for task in request.tasks)


@pytest.mark.asyncio
async def test_rwms_flexible_day_accepts_mandatory_without_inventing_a_window(
    db_session: AsyncSession,
) -> None:
    """Dispatcher metadata can change while CustomerApp's full-day choice stays soft."""

    warehouse = await make_warehouse(db_session)
    request = await make_request(db_session, warehouse, mandatory=False)
    request.source_system = "RWMS"
    option = request.date_options[0]
    option.window_start = None
    option.window_end = None
    option.is_hard = False
    await db_session.flush()

    updated = await catalog.set_request_planning_details(
        db_session,
        request.id,
        RequestPlanningDetailsInput(
            expected_version=request.version,
            date=date(2026, 8, 30),
            window_start=None,
            window_end=None,
            is_hard=False,
            mandatory=True,
            trailer_access_allowed=True,
            contact_name="Диспетчер",
            contact_phone="+7 900 000-00-00",
        ),
    )

    assert updated.mandatory is True
    assert all(task.mandatory for task in updated.tasks)
    assert option.window_start is None
    assert option.window_end is None
    assert option.is_hard is False


@pytest.mark.asyncio
async def test_rwms_flexible_day_rejects_dispatcher_time_narrowing(
    db_session: AsyncSession,
) -> None:
    """A logistics edit cannot silently replace the customer's flexible-day choice."""

    warehouse = await make_warehouse(db_session)
    request = await make_request(db_session, warehouse, mandatory=False)
    request.source_system = "RWMS"
    option = request.date_options[0]
    option.window_start = None
    option.window_end = None
    option.is_hard = False
    await db_session.flush()

    with pytest.raises(ApiError) as rejected:
        await catalog.set_request_planning_details(
            db_session,
            request.id,
            RequestPlanningDetailsInput(
                expected_version=request.version,
                date=date(2026, 8, 30),
                window_start=time(11),
                window_end=time(15),
                is_hard=True,
                mandatory=True,
                trailer_access_allowed=True,
                contact_name="Диспетчер",
                contact_phone="+7 900 000-00-00",
            ),
        )

    assert rejected.value.code == "RWMS_FLEXIBLE_DAY_IMMUTABLE"
    assert request.mandatory is False
