"""PostGIS integration tests for random warehouse workload generation."""

from datetime import date
from uuid import UUID

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.models import RoutePlan, UnassignedTask
from app.models.domain import PlanStatus, RequestType
from app.routing import MockRoutingProvider
from app.schemas.domain import WorkloadGenerationResult, WorkloadGeneratorInput
from app.services import catalog
from app.services.workload_generator import (
    GENERATOR_SOURCE_SYSTEM,
    delete_generated_workload,
    generate_warehouse_workload,
)
from tests.factories import make_request, make_warehouse

pytestmark = pytest.mark.integration


async def _generate(
    session: AsyncSession,
    warehouse_id: UUID,
    *,
    start_date: date = date(2026, 8, 29),
    days: int = 3,
) -> WorkloadGenerationResult:
    """Generate random load with an identity road snapper."""

    return await generate_warehouse_workload(
        session,
        warehouse_id,
        WorkloadGeneratorInput(
            start_date=start_date,
            days=days,
            deliveries_per_day=4,
            pickups_per_day=2,
            alternative_dates_count=1 if days > 1 else 0,
        ),
        MockRoutingProvider(),
    )


@pytest.mark.asyncio
async def test_configurable_generator_creates_three_day_random_load(
    db_session: AsyncSession,
) -> None:
    """A configurable three-day run produces six jobs per date and advances generation once."""

    warehouse = await make_warehouse(db_session)

    result = await _generate(db_session, warehouse.id)
    requests = await catalog.list_requests(db_session, warehouse.id)

    assert result.warehouse_id == warehouse.id
    assert result.created_requests == 18
    assert result.created_deliveries == 12
    assert result.created_pickups == 6
    assert result.capacity_projection_status == "NOT_REQUESTED"
    assert result.capacity_projection_warning is None
    assert [item.deliveries + item.pickups for item in result.daily_counts] == [6, 6, 6]
    assert len(requests) == 18
    assert all(request.source_system == GENERATOR_SOURCE_SYSTEM for request in requests)
    generated_client_types = {"INDIVIDUAL", "SOLE_PROPRIETOR", "LEGAL_ENTITY"}
    generated_requests = [
        request
        for request in requests
        if request.source_system == GENERATOR_SOURCE_SYSTEM
    ]
    assert all(request.client_type in generated_client_types for request in generated_requests)
    assert {request.client_type for request in generated_requests} == generated_client_types
    assert all(
        request.client_type in generated_client_types
        for request in generated_requests
        if request.type == RequestType.DELIVERY
    )
    assert all(
        request.client_type in generated_client_types
        for request in generated_requests
        if request.type == RequestType.PICKUP
    )
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
async def test_rerun_replaces_generated_rows_but_preserves_business_facts(
    db_session: AsyncSession,
) -> None:
    """A rerun replaces only generator-owned rows in its horizon."""

    warehouse = await make_warehouse(db_session)
    manual = await make_request(db_session, warehouse, planning_date=date(2026, 8, 29))
    manual.client_type = "LEGAL_ENTITY"
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
    assert {item[0] for item in second}.isdisjoint({item[0] for item in first})
    assert any(request.id == manual.id for request in current)
    preserved_manual = next(request for request in current if request.id == manual.id)
    assert preserved_manual.client_type == "LEGAL_ENTITY"
    assert warehouse.capacity_generation > first_generation


@pytest.mark.asyncio
async def test_regeneration_rejects_business_plan_and_preserves_other_dates(
    db_session: AsyncSession,
) -> None:
    """Test generation refuses to mutate an unproven business plan in its horizon."""

    warehouse = await make_warehouse(db_session)
    target = date(2026, 8, 29)
    other = date(2026, 8, 30)
    await _generate(db_session, warehouse.id, start_date=target, days=1)
    target_plan = RoutePlan(warehouse_id=warehouse.id, date=target, name="Target")
    other_plan = RoutePlan(warehouse_id=warehouse.id, date=other, name="Other")
    db_session.add_all([target_plan, other_plan])
    await db_session.flush()

    with pytest.raises(ApiError) as rejected:
        await _generate(db_session, warehouse.id, start_date=target, days=1)

    assert rejected.value.code == "WORKLOAD_GENERATOR_PLAN_CONFLICT"
    assert await db_session.get(RoutePlan, target_plan.id) is not None
    assert await db_session.get(RoutePlan, other_plan.id) is not None


@pytest.mark.asyncio
async def test_regeneration_replaces_only_generator_owned_plan(
    db_session: AsyncSession,
) -> None:
    """A plan containing only generated requests is disposable test state, not business data."""

    warehouse = await make_warehouse(db_session)
    target = date(2026, 8, 29)
    await _generate(db_session, warehouse.id, start_date=target, days=1)
    generated_request = next(
        request
        for request in await catalog.list_requests(db_session, warehouse.id)
        if request.source_system == GENERATOR_SOURCE_SYSTEM
    )
    plan = RoutePlan(warehouse_id=warehouse.id, date=target, name="Generated-only")
    db_session.add(plan)
    await db_session.flush()
    db_session.add(
        UnassignedTask(
            route_plan_id=plan.id,
            task_id=generated_request.tasks[0].id,
            reason_codes=["NO_RESOURCE"],
            descriptions_ru=["No resource."],
        )
    )
    await db_session.flush()

    result = await _generate(db_session, warehouse.id, start_date=target, days=1)

    assert result.deleted_plans == 1
    assert await db_session.get(RoutePlan, plan.id) is None


@pytest.mark.asyncio
async def test_delete_generated_workload_is_exact_date_and_idempotent(
    db_session: AsyncSession,
) -> None:
    """Deleting one date removes only generated demand and a repeated command is empty."""

    warehouse = await make_warehouse(db_session)
    target = date(2026, 8, 29)
    await _generate(db_session, warehouse.id, start_date=target, days=1)

    deleted = await delete_generated_workload(db_session, warehouse.id, target)
    repeated = await delete_generated_workload(db_session, warehouse.id, target)

    assert deleted.deleted_requests == 6
    assert repeated.deleted_requests == 0


@pytest.mark.asyncio
async def test_explicit_delete_without_generated_work_preserves_real_draft(
    db_session: AsyncSession,
) -> None:
    """An empty cleanup command cannot delete an unrelated real plan revision."""

    warehouse = await make_warehouse(db_session)
    target = date(2026, 8, 29)
    manual = await make_request(db_session, warehouse, planning_date=target)
    plan = RoutePlan(warehouse_id=warehouse.id, date=target, name="Real draft")
    db_session.add(plan)
    await db_session.flush()
    db_session.add(
        UnassignedTask(
            route_plan_id=plan.id,
            task_id=manual.tasks[0].id,
            reason_codes=["NO_RESOURCE"],
            descriptions_ru=["No resource."],
        )
    )
    await db_session.flush()

    result = await delete_generated_workload(db_session, warehouse.id, target)

    assert result.deleted_requests == 0
    assert result.deleted_plans == 0
    assert await db_session.get(RoutePlan, plan.id) is not None


@pytest.mark.asyncio
async def test_explicit_delete_removes_mixed_unconfirmed_plans_but_preserves_real_requests(
    db_session: AsyncSession,
) -> None:
    """Explicit cleanup invalidates mixed drafts without deleting authoritative demand."""

    warehouse = await make_warehouse(db_session)
    target = date(2026, 8, 29)
    manual = await make_request(db_session, warehouse, planning_date=target)
    await _generate(db_session, warehouse.id, start_date=target, days=1)
    generated = next(
        request
        for request in await catalog.list_requests(db_session, warehouse.id)
        if request.source_system == GENERATOR_SOURCE_SYSTEM
    )
    plans = (
        RoutePlan(
            warehouse_id=warehouse.id,
            date=target,
            name="Archived mixed draft",
            status=PlanStatus.ARCHIVED,
        ),
        RoutePlan(
            warehouse_id=warehouse.id,
            date=target,
            name="Active mixed draft",
            status=PlanStatus.GENERATED,
        ),
    )
    db_session.add_all(plans)
    await db_session.flush()
    for plan in plans:
        db_session.add_all(
            (
                UnassignedTask(
                    route_plan_id=plan.id,
                    task_id=generated.tasks[0].id,
                    reason_codes=["NO_RESOURCE"],
                    descriptions_ru=["No resource."],
                ),
                UnassignedTask(
                    route_plan_id=plan.id,
                    task_id=manual.tasks[0].id,
                    reason_codes=["NO_RESOURCE"],
                    descriptions_ru=["No resource."],
                ),
            )
        )
    await db_session.flush()

    result = await delete_generated_workload(db_session, warehouse.id, target)
    remaining = await catalog.list_requests(db_session, warehouse.id)

    assert result.deleted_requests == 6
    assert result.deleted_plans == 2
    for plan in plans:
        assert await db_session.get(RoutePlan, plan.id) is None
    assert any(request.id == manual.id for request in remaining)
    assert all(request.source_system != GENERATOR_SOURCE_SYSTEM for request in remaining)


@pytest.mark.asyncio
async def test_explicit_delete_rejects_a_confirmed_plan(
    db_session: AsyncSession,
) -> None:
    """Generated workload cannot erase a confirmed customer commitment."""

    warehouse = await make_warehouse(db_session)
    target = date(2026, 8, 29)
    await _generate(db_session, warehouse.id, start_date=target, days=1)
    generated = next(
        request
        for request in await catalog.list_requests(db_session, warehouse.id)
        if request.source_system == GENERATOR_SOURCE_SYSTEM
    )
    plan = RoutePlan(
        warehouse_id=warehouse.id,
        date=target,
        name="Confirmed",
        status=PlanStatus.CONFIRMED,
    )
    db_session.add(plan)
    await db_session.flush()
    db_session.add(
        UnassignedTask(
            route_plan_id=plan.id,
            task_id=generated.tasks[0].id,
            reason_codes=["NO_RESOURCE"],
            descriptions_ru=["No resource."],
        )
    )
    await db_session.flush()

    with pytest.raises(ApiError) as rejected:
        await delete_generated_workload(db_session, warehouse.id, target)

    assert rejected.value.code == "WORKLOAD_GENERATOR_PLAN_CONFLICT"
    assert await db_session.get(RoutePlan, plan.id) is not None
    assert any(
        request.id == generated.id
        for request in await catalog.list_requests(db_session, warehouse.id)
    )
