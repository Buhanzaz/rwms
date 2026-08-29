"""PostGIS integration tests for deterministic warehouse workload generation."""

from datetime import date
from uuid import UUID

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.models import RoutePlan
from app.routing import MockRoutingProvider
from app.schemas.domain import WorkloadGenerationResult, WorkloadGeneratorInput
from app.services import catalog
from app.services.workload_generator import (
    GENERATOR_SOURCE_SYSTEM,
    delete_generated_workload,
    generate_warehouse_workload,
)
from tests.factories import make_request, make_warehouse, make_zone

pytestmark = pytest.mark.integration


async def _generate(
    session: AsyncSession,
    warehouse_id: UUID,
    *,
    start_date: date = date(2026, 8, 29),
    days: int = 3,
) -> WorkloadGenerationResult:
    """Generate deterministic load with an identity road snapper."""

    return await generate_warehouse_workload(
        session,
        warehouse_id,
        WorkloadGeneratorInput(
            start_date=start_date,
            days=days,
            deliveries_per_day=4,
            pickups_per_day=2,
            alternative_dates_count=1 if days > 1 else 0,
            seed=20260829,
        ),
        MockRoutingProvider(),
    )


@pytest.mark.asyncio
async def test_configurable_generator_creates_three_day_deterministic_load(
    db_session: AsyncSession,
) -> None:
    """A configurable three-day run produces six jobs per date and advances generation once."""

    warehouse = await make_warehouse(db_session)
    await make_zone(db_session, warehouse)

    result = await _generate(db_session, warehouse.id)
    requests = await catalog.list_requests(db_session, warehouse.id)

    assert result.warehouse_id == warehouse.id
    assert result.created_requests == 18
    assert result.created_deliveries == 12
    assert result.created_pickups == 6
    assert [item.deliveries + item.pickups for item in result.daily_counts] == [6, 6, 6]
    assert len(requests) == 18
    assert all(request.source_system == GENERATOR_SOURCE_SYSTEM for request in requests)
    assert all(request.external_id is not None for request in requests)
    assert all(request.scheduled_date is not None for request in requests)
    assert all(request.name.startswith("№") for request in requests)
    assert all(
        "Доставка" not in request.name and "Вывоз" not in request.name
        for request in requests
    )
    assert all("2026-" not in request.name for request in requests)
    assert warehouse.capacity_generation > 0


@pytest.mark.asyncio
async def test_same_seed_replaces_generated_rows_but_preserves_business_facts(
    db_session: AsyncSession,
) -> None:
    """A rerun is deterministic and replaces only generator-owned rows in its horizon."""

    warehouse = await make_warehouse(db_session)
    await make_zone(db_session, warehouse)
    manual = await make_request(db_session, warehouse, planning_date=date(2026, 8, 29))
    await _generate(db_session, warehouse.id, days=1)
    first = sorted(
        [
        (
            request.external_id,
            request.type,
            request.latitude,
            request.longitude,
            request.quantity,
        )
        for request in await catalog.list_requests(db_session, warehouse.id)
        if request.source_system == GENERATOR_SOURCE_SYSTEM
        ],
        key=lambda item: str(item[0]),
    )
    first_generation = warehouse.capacity_generation

    repeated = await _generate(db_session, warehouse.id, days=1)
    current = await catalog.list_requests(db_session, warehouse.id)
    second = sorted(
        [
        (
            request.external_id,
            request.type,
            request.latitude,
            request.longitude,
            request.quantity,
        )
        for request in current
        if request.source_system == GENERATOR_SOURCE_SYSTEM
        ],
        key=lambda item: str(item[0]),
    )

    assert repeated.replaced_requests == 6
    assert second == first
    assert any(request.id == manual.id for request in current)
    assert warehouse.capacity_generation > first_generation


@pytest.mark.asyncio
async def test_regeneration_deletes_only_affected_date_plans(db_session: AsyncSession) -> None:
    """Replacing one generated date invalidates that plan and leaves another date intact."""

    warehouse = await make_warehouse(db_session)
    await make_zone(db_session, warehouse)
    target = date(2026, 8, 29)
    other = date(2026, 8, 30)
    await _generate(db_session, warehouse.id, start_date=target, days=1)
    target_plan = RoutePlan(warehouse_id=warehouse.id, date=target, name="Target")
    other_plan = RoutePlan(warehouse_id=warehouse.id, date=other, name="Other")
    db_session.add_all([target_plan, other_plan])
    await db_session.flush()

    result = await _generate(db_session, warehouse.id, start_date=target, days=1)

    assert result.deleted_plans == 1
    assert await db_session.get(RoutePlan, target_plan.id) is None
    assert await db_session.get(RoutePlan, other_plan.id) is not None


@pytest.mark.asyncio
async def test_generation_requires_an_owned_zone(db_session: AsyncSession) -> None:
    """A warehouse cannot use another workspace's otherwise usable polygon."""

    warehouse = await make_warehouse(db_session)
    other_warehouse = await make_warehouse(db_session, name="Other warehouse")
    await make_zone(db_session, other_warehouse)
    with pytest.raises(ApiError) as rejected:
        await _generate(db_session, warehouse.id, days=1)
    assert rejected.value.code == "NO_ZONES"


@pytest.mark.asyncio
async def test_delete_generated_workload_is_exact_date_and_idempotent(
    db_session: AsyncSession,
) -> None:
    """Deleting one date removes only generated demand and a repeated command is empty."""

    warehouse = await make_warehouse(db_session)
    await make_zone(db_session, warehouse)
    target = date(2026, 8, 29)
    await _generate(db_session, warehouse.id, start_date=target, days=1)

    deleted = await delete_generated_workload(db_session, warehouse.id, target)
    repeated = await delete_generated_workload(db_session, warehouse.id, target)

    assert deleted.deleted_requests == 6
    assert repeated.deleted_requests == 0
