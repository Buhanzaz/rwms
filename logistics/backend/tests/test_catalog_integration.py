"""PostGIS integration tests for warehouse catalogs and request invariants."""

from datetime import date, time
from uuid import uuid4

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.models import RoutePlan, UnassignedTask
from app.schemas.domain import (
    LogisticsRequestUpdate,
    RequestPlanningDetailsInput,
    RwmsWarehouseIdentity,
    ShiftCreate,
    WarehouseCreate,
)
from app.schemas.geocoding import ResolvedAddress
from app.services import catalog
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
        LogisticsRequestUpdate(mandatory=True),
    )
    assert updated.mandatory is True
    assert [task.id for task in updated.tasks] == task_ids
    assert all(task.mandatory for task in updated.tasks)


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
