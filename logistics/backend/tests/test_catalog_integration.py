"""PostGIS integration tests for warehouse catalogs and request invariants."""

from datetime import date, time
from uuid import uuid4

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.models import RoutePlan, UnassignedTask
from app.schemas.domain import (
    LogisticsRequestCreate,
    LogisticsRequestUpdate,
    RequestDateOptionInput,
    RequestPlanningDetailsInput,
    RwmsWarehouseIdentity,
    ShiftCreate,
    WarehouseCreate,
    ZoneCreate,
    ZoneCutoutRequest,
    ZoneUpdate,
)
from app.schemas.geocoding import ResolvedAddress
from app.services import catalog
from tests.factories import (
    make_driver,
    make_request,
    make_shift,
    make_vehicle,
    make_warehouse,
    make_zone,
)

pytestmark = pytest.mark.integration


@pytest.mark.asyncio
async def test_warehouse_zone_classification_uses_smallest_covering_area(
    db_session: AsyncSession,
) -> None:
    """Request classification stays in its warehouse and selects the most specific polygon."""

    warehouse = await make_warehouse(db_session)
    await make_zone(
        db_session,
        warehouse,
        name="Broad",
        west=29,
        south=59,
        east=32,
        north=61,
    )
    specific = await make_zone(
        db_session,
        warehouse,
        name="Specific",
        color="#A855F7",
        west=30.2,
        south=59.8,
        east=30.5,
        north=60.0,
    )
    request = await catalog.create_request(
        db_session,
        warehouse.id,
        LogisticsRequestCreate(
            type="DELIVERY",
            name="Five cabins",
            latitude=59.9,
            longitude=30.3,
            quantity=5,
            mandatory=True,
            trailer_access_allowed=True,
            date_options=[RequestDateOptionInput(date=date(2026, 8, 25))],
        ),
    )
    assert request.zone_id == specific.id
    assert request.zone_version == 1
    assert request.mandatory is True
    assert [task.quantity for task in request.tasks] == [2, 2, 1]
    assert all(task.mandatory for task in request.tasks)

    await catalog.update_zone(
        db_session,
        warehouse.id,
        specific.id,
        ZoneUpdate(
            geometry={
                "type": "Polygon",
                "coordinates": [
                    [[30.2, 59.8], [30.6, 59.8], [30.6, 60.1], [30.2, 60.1], [30.2, 59.8]]
                ],
            }
        ),
    )
    assert await catalog.count_stale_requests(db_session, specific) == 1


@pytest.mark.asyncio
async def test_classification_ignores_a_more_specific_foreign_warehouse_zone(
    db_session: AsyncSession,
) -> None:
    """An overlapping zone from another warehouse can never classify this request."""

    warehouse = await make_warehouse(db_session, name="Owner")
    other = await make_warehouse(db_session, name="Other")
    owner_zone = await make_zone(db_session, warehouse, name="Owner broad")
    foreign_zone = await make_zone(
        db_session,
        other,
        name="Foreign specific",
        west=30.2,
        south=59.8,
        east=30.5,
        north=60.0,
    )
    request = await make_request(db_session, warehouse)

    assert request.zone_id == owner_zone.id
    assert request.zone_id != foreign_zone.id


@pytest.mark.asyncio
async def test_exceptional_zone_can_move_or_be_deleted_without_removing_delivery_coverage(
    db_session: AsyncSession,
) -> None:
    """Isochrones cover delivery; exceptional polygons need not contain the depot."""

    warehouse = await make_warehouse(db_session)
    zone = await make_zone(db_session, warehouse)
    warehouse_id = warehouse.id
    zone_id = zone.id
    outside = {
        "type": "Polygon",
        "coordinates": [[[36, 55], [38, 55], [38, 57], [36, 57], [36, 55]]],
    }

    moved = await catalog.update_zone(
        db_session,
        warehouse_id,
        zone_id,
        ZoneUpdate(geometry=outside),
    )
    assert moved.version == 2

    await catalog.delete_zone(db_session, warehouse_id, zone_id)
    with pytest.raises(ApiError) as missing:
        await catalog.require_zone(db_session, warehouse_id, zone_id)
    assert missing.value.status_code == 404


@pytest.mark.asyncio
async def test_cutout_inherits_owner_and_cross_owner_id_is_not_found(
    db_session: AsyncSession,
) -> None:
    """Both cutout results retain one owner and nested IDs do not disclose foreign zones."""

    warehouse = await make_warehouse(db_session, name="Owner")
    other = await make_warehouse(db_session, name="Other")
    source = await make_zone(db_session, warehouse)
    foreign = await make_zone(db_session, other, name="Foreign")

    with pytest.raises(ApiError) as hidden:
        await catalog.update_zone(
            db_session,
            warehouse.id,
            foreign.id,
            ZoneUpdate(name="Must not change"),
        )
    assert hidden.value.status_code == 404

    source_after, inner = await catalog.cut_zone(
        db_session,
        warehouse.id,
        source.id,
        ZoneCutoutRequest(
            geometry={
                "type": "Polygon",
                "coordinates": [
                    [[29.1, 59.1], [29.2, 59.1], [29.2, 59.2], [29.1, 59.2], [29.1, 59.1]]
                ],
            },
            inner_zone={
                "name": "Inner",
                "color": "#EC4899",
                "delivery_price": 3_000,
                "pickup_price": 2_000,
                "locked": False,
            },
        ),
    )
    assert source_after.warehouse_id == warehouse.id
    assert inner.warehouse_id == warehouse.id


@pytest.mark.asyncio
async def test_warehouse_binding_needs_no_zone_and_uses_default_isochrone_prices(
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
    assert created.isochrone_price_60_minutes == 10_000
    assert created.isochrone_price_120_minutes == 15_000
    assert created.isochrone_price_180_minutes == 20_000
    assert created.isochrone_price_240_minutes == 25_000


@pytest.mark.asyncio
async def test_zone_policies_reject_forbidden_delivery_and_force_solo_transport(
    db_session: AsyncSession,
) -> None:
    """Forbidden polygons block orders and no-trailer polygons split two cabins."""

    warehouse = await make_warehouse(db_session)
    forbidden = ZoneCreate(
        name="Запрет",
        kind="FORBIDDEN",
        geometry={
            "type": "Polygon",
            "coordinates": [
                [[30.2, 59.8], [30.4, 59.8], [30.4, 60.0], [30.2, 60.0], [30.2, 59.8]]
            ],
        },
    )
    forbidden_zone = await catalog.create_zone(db_session, warehouse.id, forbidden)
    with pytest.raises(ApiError) as rejected:
        await make_request(db_session, warehouse)
    assert rejected.value.code == "DELIVERY_FORBIDDEN_ZONE"

    await catalog.delete_zone(db_session, warehouse.id, forbidden_zone.id)
    await catalog.create_zone(
        db_session,
        warehouse.id,
        ZoneCreate(
            name="Без прицепа",
            kind="NO_TRAILER",
            geometry=forbidden.geometry,
        ),
    )
    request = await make_request(db_session, warehouse, quantity=2)
    assert request.trailer_access_allowed is False
    assert [task.quantity for task in request.tasks] == [1, 1]


@pytest.mark.asyncio
async def test_restriction_zone_cannot_carry_a_price(
    db_session: AsyncSession,
) -> None:
    """Only SPECIAL_PRICE may override the warehouse isochrone tariff."""

    warehouse = await make_warehouse(db_session)
    with pytest.raises(ApiError) as rejected:
        await catalog.create_zone(
            db_session,
            warehouse.id,
            ZoneCreate(
                name="Некорректная цена",
                kind="NO_TRAILER",
                delivery_price=1,
                geometry={
                    "type": "Polygon",
                    "coordinates": [
                        [[30.2, 59.8], [30.4, 59.8], [30.4, 60.0], [30.2, 60.0], [30.2, 59.8]]
                    ],
                },
            ),
        )
    assert rejected.value.code == "ZONE_PRICE_NOT_ALLOWED"


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
    await make_zone(db_session, warehouse)
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
    await make_zone(db_session, warehouse)
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
    await make_zone(db_session, warehouse)
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
    await make_zone(db_session, warehouse)
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
    await make_zone(db_session, warehouse)
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
    await make_zone(db_session, warehouse)
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
