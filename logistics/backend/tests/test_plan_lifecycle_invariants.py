"""Integration regressions for immutable route-plan revisions and request allocation."""

from __future__ import annotations

from datetime import date, time

import pytest
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.models import RequestDateOption, RoutePlan, UnassignedTask
from app.models.domain import PlanStatus, RequestStatus, TaskStatus
from app.schemas.domain import GeneratePlanRequest, LogisticsRequestUpdate
from app.services import catalog, plans
from app.services.planner_runtime import RuntimePlannerFacade
from tests.factories import (
    make_driver,
    make_request,
    make_shift,
    make_vehicle,
    make_warehouse,
)

pytestmark = pytest.mark.integration


@pytest.mark.asyncio
async def test_database_rejects_two_active_plan_heads_for_one_date(
    db_session: AsyncSession,
) -> None:
    """The partial unique index is the final concurrency fence for active revisions."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    first = RoutePlan(warehouse_id=warehouse.id, date=planning_date, name="First")
    db_session.add(first)
    await db_session.flush()

    savepoint = await db_session.begin_nested()
    db_session.add(RoutePlan(warehouse_id=warehouse.id, date=planning_date, name="Second"))
    with pytest.raises(IntegrityError):
        await db_session.flush()
    await savepoint.rollback()


@pytest.mark.asyncio
async def test_confirming_flexible_request_archives_competing_date_plan(
    db_session: AsyncSession,
) -> None:
    """One confirmed date atomically claims a flexible request and invalidates other drafts."""

    first_date = date(2026, 8, 30)
    second_date = date(2026, 8, 31)
    warehouse = await make_warehouse(db_session, default_planning_date=first_date)
    request = await make_request(
        db_session,
        warehouse,
        planning_date=first_date,
        quantity=1,
    )
    request.date_options.append(
        RequestDateOption(
            date=second_date,
            priority=0,
            window_start=time(10),
            window_end=time(14),
            is_hard=True,
        )
    )
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    await make_shift(
        db_session,
        warehouse,
        driver,
        vehicle,
        date_from=first_date,
        date_to=second_date,
    )
    await db_session.flush()

    planner = RuntimePlannerFacade()
    first_run = await planner.generate_plan(
        db_session,
        warehouse.id,
        GeneratePlanRequest(date=first_date, seed=warehouse.seed),
    )
    second_run = await planner.generate_plan(
        db_session,
        warehouse.id,
        GeneratePlanRequest(date=second_date, seed=warehouse.seed),
    )
    assert first_run.plan_id is not None
    assert second_run.plan_id is not None
    first_plan = await plans.get_plan(db_session, first_run.plan_id)
    second_plan = await plans.get_plan(db_session, second_run.plan_id)

    confirmed = await plans.confirm_plan(
        db_session,
        first_plan.id,
        first_plan.version,
        accept_warnings=True,
        empty_positioning_reason=None,
        confirmed_by="lifecycle-test",
    )
    await db_session.refresh(second_plan)

    assert confirmed.status == PlanStatus.CONFIRMED
    assert second_plan.status == PlanStatus.ARCHIVED
    assert request.scheduled_date == first_date
    assert request.status == RequestStatus.PLANNED
    assert {task.status for task in request.tasks} == {TaskStatus.PLANNED}


@pytest.mark.asyncio
async def test_delete_referenced_request_keeps_plan_unchanged(
    db_session: AsyncSession,
) -> None:
    """Deletion checks references before any stale-plan transition can mutate the plan."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    request = await make_request(
        db_session,
        warehouse,
        planning_date=planning_date,
        quantity=1,
    )
    plan = RoutePlan(
        warehouse_id=warehouse.id,
        date=planning_date,
        name="Referenced plan",
        status=PlanStatus.DRAFT,
    )
    db_session.add(plan)
    await db_session.flush()
    db_session.add(
        UnassignedTask(
            route_plan_id=plan.id,
            task_id=request.tasks[0].id,
            reason_codes=["NO_RESOURCE"],
            descriptions_ru=["No resource."],
        )
    )
    await db_session.flush()

    with pytest.raises(ApiError) as rejected:
        await catalog.delete_request(db_session, request.id, request.version)

    assert rejected.value.code == "REQUEST_ALREADY_PLANNED"
    assert plan.status == PlanStatus.DRAFT
    assert await db_session.get(type(request), request.id) is not None


@pytest.mark.asyncio
async def test_confirmed_request_facts_are_immutable(
    db_session: AsyncSession,
) -> None:
    """A direct request update cannot rewrite facts retained by a confirmed plan."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    request = await make_request(
        db_session,
        warehouse,
        planning_date=planning_date,
        quantity=1,
    )
    plan = RoutePlan(
        warehouse_id=warehouse.id,
        date=planning_date,
        name="Confirmed plan",
        status=PlanStatus.CONFIRMED,
    )
    db_session.add(plan)
    await db_session.flush()
    db_session.add(
        UnassignedTask(
            route_plan_id=plan.id,
            task_id=request.tasks[0].id,
            reason_codes=["NO_RESOURCE"],
            descriptions_ru=["No resource."],
        )
    )
    await db_session.flush()

    with pytest.raises(ApiError) as rejected:
        await catalog.update_request(
            db_session,
            request.id,
            LogisticsRequestUpdate(expected_version=request.version, name="Rewritten"),
        )

    assert rejected.value.code == "REQUEST_IN_CONFIRMED_PLAN"
    assert request.name == "Test delivery"
