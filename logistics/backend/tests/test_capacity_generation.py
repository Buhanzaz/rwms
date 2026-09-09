"""Regressions for capacity mutations after a sequence falls behind retained state."""

from datetime import UTC, date, datetime
from unittest.mock import AsyncMock
from uuid import UUID

import pytest
from sqlalchemy import Sequence, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.rwms import reconcile_rwms_capacity
from app.integrations.rwms import RwmsPlanningClient
from app.models import Warehouse
from app.models.domain import CapacityPublicationStatus
from app.routing import MockRoutingProvider
from app.schemas.domain import (
    RwmsCapacitySnapshotCommand,
    RwmsCapacitySnapshotResult,
    WorkloadGeneratorInput,
)
from app.services.capacity_generation import advance_warehouse_capacity_generation
from app.services.workload_generator import generate_warehouse_workload
from tests.auth import admin_principal
from tests.factories import make_driver, make_shift, make_vehicle, make_warehouse

pytestmark = pytest.mark.integration


async def _retained_generation(session: AsyncSession) -> int:
    """Create a retained-state floor without resetting the shared test sequence."""

    next_value = await session.scalar(
        select(Sequence("warehouse_capacity_generation_seq").next_value())
    )
    assert next_value is not None
    return next_value + 1_115


@pytest.mark.asyncio
@pytest.mark.parametrize("published", [False, True])
async def test_workload_generation_advances_past_retained_state(
    db_session: AsyncSession,
    published: bool,
) -> None:
    """A lagging sequence neither rejects published work nor regresses pending work."""

    warehouse = await make_warehouse(db_session)
    retained = await _retained_generation(db_session)
    warehouse.capacity_generation = retained
    warehouse.capacity_published_generation = retained if published else 0
    await db_session.flush()

    result = await generate_warehouse_workload(
        db_session,
        warehouse.id,
        WorkloadGeneratorInput(
            start_date=date(2026, 9, 10),
            days=1,
            deliveries_per_day=1,
            pickups_per_day=1,
            alternative_dates_count=0,
        ),
        MockRoutingProvider(),
    )

    assert result.created_deliveries == 1
    assert result.created_pickups == 1
    assert warehouse.capacity_generation > retained
    assert warehouse.capacity_published_generation == (retained if published else 0)


@pytest.mark.asyncio
async def test_capacity_reconciliation_republishes_customer_resources_with_new_generation(
    db_session: AsyncSession,
) -> None:
    """The recovery endpoint can republish shifts and tariffs after sequence drift."""

    warehouse = await make_warehouse(db_session)
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    await make_shift(
        db_session,
        warehouse,
        driver,
        vehicle,
        date_from=date(2026, 9, 10),
        date_to=date(2026, 9, 10),
    )
    retained = await _retained_generation(db_session)
    warehouse.capacity_generation = retained
    warehouse.capacity_published_generation = retained
    warehouse.capacity_publish_status = CapacityPublicationStatus.PUBLISHED
    await db_session.flush()
    client = AsyncMock(spec=RwmsPlanningClient)
    client.list_support_network.return_value = []

    async def acknowledge(
        warehouse_id: UUID,
        command: RwmsCapacitySnapshotCommand,
        *,
        idempotency_key: UUID,
    ) -> RwmsCapacitySnapshotResult:
        assert warehouse_id == warehouse.external_warehouse_id
        assert command.source_generation > retained
        assert len(command.shifts) == 1
        assert len(command.isochrone_tariffs) == 4
        return RwmsCapacitySnapshotResult(
            warehouse_id=warehouse_id,
            source_generation=command.source_generation,
            version=1,
            source_revision=command.source_revision,
            job_count=len(command.jobs),
            shift_count=len(command.shifts),
            isochrone_tariff_count=len(command.isochrone_tariffs),
            price_zone_count=len(command.price_zones),
            restriction_zone_count=len(command.restriction_zones),
            replayed=False,
            updated_at=datetime.now(UTC),
        )

    client.replace_capacity_snapshot.side_effect = acknowledge

    result = await reconcile_rwms_capacity(warehouse.id, db_session, client, admin_principal())

    client.replace_capacity_snapshot.assert_awaited_once()
    assert result.source_generation == warehouse.capacity_generation
    assert warehouse.capacity_published_generation == warehouse.capacity_generation
    assert warehouse.capacity_publish_status == CapacityPublicationStatus.PUBLISHED


@pytest.mark.asyncio
async def test_generation_uses_locked_database_state_instead_of_cached_warehouse(
    db_session: AsyncSession,
) -> None:
    """A warehouse cached before another update cannot move the publication fence back."""

    warehouse = await make_warehouse(db_session)
    retained = await _retained_generation(db_session)
    await db_session.execute(
        update(Warehouse)
        .where(Warehouse.id == warehouse.id)
        .values(capacity_generation=retained, capacity_published_generation=retained)
        .execution_options(synchronize_session=False)
    )
    assert warehouse.capacity_generation == 0

    generation = await advance_warehouse_capacity_generation(db_session, warehouse.id)

    assert generation > retained
    assert warehouse.capacity_generation == generation
    assert warehouse.capacity_published_generation == retained
