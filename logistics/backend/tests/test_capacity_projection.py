"""Focused PostGIS tests for deterministic warehouse-capacity publication."""

from datetime import UTC, date, datetime, time
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.integrations.rwms import RwmsPlanningClient
from app.models import PlanningDayClosure, Trailer, WarehouseIsochroneTariff
from app.schemas.domain import RwmsCapacitySnapshotCommand, RwmsCapacitySnapshotResult
from app.services.capacity_projection import build_capacity_projection, publish_warehouse_capacity
from app.services.workload_generator import GENERATOR_SOURCE_SYSTEM
from tests.factories import (
    make_driver,
    make_request,
    make_shift,
    make_vehicle,
    make_warehouse,
)

pytestmark = pytest.mark.integration


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("capacity", "can_use_trailer", "trailer_active", "expected_capacity"),
    (
        (2, False, None, 1),
        (2, True, None, 1),
        (2, True, True, 2),
        (2, True, False, 1),
        (1, True, True, 1),
    ),
)
async def test_projection_uses_only_available_assigned_trailer_capacity(
    db_session: AsyncSession,
    capacity: int,
    can_use_trailer: bool,
    trailer_active: bool | None,
    expected_capacity: int,
) -> None:
    """A cold projection cannot offer a second platform absent from actual equipment."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    warehouse.capacity_generation = 1
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    vehicle.capacity = capacity
    vehicle.can_use_trailer = can_use_trailer
    if trailer_active is not None:
        vehicle.default_trailer = Trailer(
            warehouse_id=warehouse.id,
            name="Capacity test trailer",
            registration_number=f"TRAILER-{uuid4()}",
            active=trailer_active,
        )
    await make_shift(
        db_session, warehouse, driver, vehicle,
        date_from=planning_date, date_to=planning_date,
    )
    await db_session.flush()
    warehouse_id = warehouse.id
    db_session.expire_all()

    projection = await build_capacity_projection(db_session, warehouse_id)

    assert len(projection.command.shifts) == 1
    assert projection.command.shifts[0].cabin_capacity == expected_capacity


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("allow_soft_overtime", "limit_minutes", "shift_end", "expected_end"),
    (
        (False, 90, time(20), time(20)),
        (True, 0, time(20), time(20)),
        (True, 90, time(20), time(21, 30)),
        (True, 120, time(23, 30), time.max),
    ),
)
async def test_projection_extends_shift_capacity_only_for_enabled_soft_overtime(
    db_session: AsyncSession,
    allow_soft_overtime: bool,
    limit_minutes: int,
    shift_end: time,
    expected_end: time,
) -> None:
    """Published availability matches the planner's bounded overtime setting."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    warehouse.capacity_generation = 1
    warehouse.settings = {
        **warehouse.settings,
        "allow_soft_overtime": allow_soft_overtime,
        "soft_overtime_limit_minutes": limit_minutes,
    }
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    await make_shift(
        db_session,
        warehouse,
        driver,
        vehicle,
        date_from=planning_date,
        date_to=planning_date,
        end_time=shift_end,
    )
    await db_session.flush()

    projection = await build_capacity_projection(db_session, warehouse.id)

    assert len(projection.command.shifts) == 1
    assert projection.command.shifts[0].shift_end == expected_end


@pytest.mark.asyncio
async def test_projection_expands_monthly_shifts_and_carries_mandatory_jobs(
    db_session: AsyncSession,
) -> None:
    """Capacity contains generated jobs, open daily shifts, and ordered tariffs."""

    start = date(2026, 8, 29)
    warehouse = await make_warehouse(db_session, default_planning_date=start)
    warehouse.capacity_generation = 7
    warehouse.isochrone_tariffs = [
        WarehouseIsochroneTariff(travel_minutes=60, price_rubles=11_000),
        WarehouseIsochroneTariff(travel_minutes=120, price_rubles=16_000),
        WarehouseIsochroneTariff(travel_minutes=180, price_rubles=21_000),
        WarehouseIsochroneTariff(travel_minutes=240, price_rubles=26_000),
        WarehouseIsochroneTariff(travel_minutes=300, price_rubles=31_000),
    ]
    request = await make_request(
        db_session,
        warehouse,
        planning_date=start,
        mandatory=True,
    )
    request.source_system = GENERATOR_SOURCE_SYSTEM
    request.external_id = uuid4()
    request.scheduled_date = start
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    shift = await make_shift(
        db_session,
        warehouse,
        driver,
        vehicle,
        date_from=start,
        date_to=date(2026, 8, 31),
    )
    db_session.add(
        PlanningDayClosure(
            warehouse_id=warehouse.id,
            date=date(2026, 8, 30),
            closed_by="test",
        )
    )
    await db_session.flush()

    projection = await build_capacity_projection(db_session, warehouse.id)

    assert projection.command.source_generation == 7
    assert len(projection.command.jobs) == 1
    assert projection.command.jobs[0].mandatory is True
    assert projection.command.jobs[0].source_job_id == request.external_id
    assert [item.delivery_date for item in projection.command.shifts] == [
        date(2026, 8, 29),
        date(2026, 8, 31),
    ]
    assert all(item.source_shift_id != shift.id for item in projection.command.shifts)
    assert [
        (tariff.travel_minutes, tariff.price_rubles)
        for tariff in projection.command.isochrone_tariffs
    ] == [
        (60, 11_000),
        (120, 16_000),
        (180, 21_000),
        (240, 26_000),
        (300, 31_000),
    ]
    payload = projection.command.model_dump(mode="json", by_alias=True)
    assert "warehouseId" not in payload
    assert payload["isochroneTariffs"][-1] == {
        "travelMinutes": 300,
        "priceRubles": 31_000,
    }
    assert payload["priceZones"] == []
    assert payload["restrictionZones"] == []


@pytest.mark.asyncio
async def test_projection_revision_uses_canonical_delivery_and_pickup_obligation(
    db_session: AsyncSession,
) -> None:
    """Delivery is always mandatory while pickup retains its explicit local obligation."""

    warehouse = await make_warehouse(db_session)
    warehouse.capacity_generation = 3
    request = await make_request(db_session, warehouse, mandatory=False)
    request.source_system = GENERATOR_SOURCE_SYSTEM
    request.external_id = uuid4()
    request.scheduled_date = date(2026, 8, 30)
    await db_session.flush()

    first = await build_capacity_projection(db_session, warehouse.id)
    replay = await build_capacity_projection(db_session, warehouse.id)
    assert replay.command.source_revision == first.command.source_revision
    assert replay.idempotency_key == first.idempotency_key
    assert first.command.jobs[0].mandatory is True

    request.mandatory = True
    await db_session.flush()
    same_delivery = await build_capacity_projection(db_session, warehouse.id)
    assert same_delivery.command.source_revision == first.command.source_revision
    assert same_delivery.idempotency_key == first.idempotency_key

    request.type = "PICKUP"
    request.mandatory = False
    await db_session.flush()
    pickup = await build_capacity_projection(db_session, warehouse.id)
    assert pickup.command.jobs[0].mandatory is False

    request.mandatory = True
    await db_session.flush()
    changed = await build_capacity_projection(db_session, warehouse.id)
    assert changed.command.source_revision != pickup.command.source_revision
    assert changed.idempotency_key != pickup.idempotency_key

    warehouse.isochrone_tariffs[0].price_rubles += 1
    await db_session.flush()
    changed_tariff = await build_capacity_projection(db_session, warehouse.id)
    assert changed_tariff.command.source_revision != changed.command.source_revision
    assert changed_tariff.idempotency_key != changed.idempotency_key

def test_capacity_command_requires_contiguous_isochrone_tariffs() -> None:
    """The capacity boundary rejects omitted or gapped tariff tiers."""

    with pytest.raises(ValueError, match="isochroneTariffs"):
        RwmsCapacitySnapshotCommand.model_validate(
            {
                "sourceGeneration": 1,
                "sourceRevision": "a" * 64,
                "jobs": [],
                "shifts": [],
                "isochroneTariffs": [
                    {"travelMinutes": 60, "priceRubles": 1},
                    {"travelMinutes": 180, "priceRubles": 2},
                ],
            }
        )


@pytest.mark.asyncio
async def test_publication_uses_external_warehouse_as_path_authority(
    db_session: AsyncSession,
) -> None:
    """The remote PUT path receives the canonical RWMS ID, never the local root ID."""

    warehouse = await make_warehouse(db_session)
    warehouse.capacity_generation = 1
    await db_session.flush()
    projection = await build_capacity_projection(db_session, warehouse.id)
    result = RwmsCapacitySnapshotResult(
        warehouseId=warehouse.external_warehouse_id,
        sourceGeneration=1,
        version=1,
        sourceRevision=projection.command.source_revision,
        jobCount=0,
        shiftCount=0,
        isochroneTariffCount=4,
        priceZoneCount=0,
        restrictionZoneCount=0,
        replayed=False,
        updatedAt=datetime(2026, 8, 30, tzinfo=UTC),
    )
    client = AsyncMock(spec=RwmsPlanningClient)
    client.replace_capacity_snapshot.return_value = result

    published = await publish_warehouse_capacity(db_session, warehouse.id, client)

    assert published == result
    assert warehouse.capacity_published_generation == 1
    assert warehouse.capacity_publish_status == "PUBLISHED"
    client.replace_capacity_snapshot.assert_awaited_once()
    assert client.replace_capacity_snapshot.await_args.args[0] == warehouse.external_warehouse_id
    submitted = client.replace_capacity_snapshot.await_args.args[1]
    assert "warehouseId" not in submitted.model_dump(mode="json", by_alias=True)


@pytest.mark.asyncio
async def test_projection_never_publishes_unrouted_support_warehouse_capacity(
    db_session: AsyncSession,
) -> None:
    """Anonymous capacity excludes another depot's shift until exact support routing succeeds."""

    planning_date = date(2026, 9, 14)
    served = await make_warehouse(
        db_session,
        name="Representative",
        default_planning_date=planning_date,
    )
    served.representative = True
    served.capacity_generation = 1
    support = await make_warehouse(
        db_session,
        name="Support",
        default_planning_date=planning_date,
    )
    driver = await make_driver(db_session, support)
    vehicle = await make_vehicle(db_session, support)
    await make_shift(
        db_session,
        support,
        driver,
        vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    await db_session.flush()

    projection = await build_capacity_projection(db_session, served.id)

    assert projection.command.shifts == []
