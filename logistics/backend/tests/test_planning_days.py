"""Integration coverage for one-way warehouse planning-date finalization."""

from datetime import date
from unittest.mock import AsyncMock

import pytest
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.models import PlanningDayClosure
from app.services.planning_days import close_planning_day, get_planning_day_status
from tests.factories import make_request, make_warehouse

pytestmark = pytest.mark.integration


@pytest.mark.asyncio
async def test_close_day_is_one_way_and_idempotent(db_session: AsyncSession) -> None:
    """The first close persists one closure while retries keep the same final state."""

    planning_date = date(2026, 8, 29)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    planner = AsyncMock()
    planner.generate_plan.return_value = None
    open_status = await get_planning_day_status(db_session, warehouse.id, planning_date)
    assert open_status.accepting_requests is True

    first = await close_planning_day(db_session, planner, warehouse.id, planning_date)
    repeated = await close_planning_day(db_session, planner, warehouse.id, planning_date)

    assert first.changed is True
    assert first.status.accepting_requests is False
    assert first.status.closed_at is not None
    assert repeated.changed is False
    assert repeated.status.closed_at == first.status.closed_at
    closure_count = await db_session.scalar(
        select(func.count(PlanningDayClosure.id)).where(
            PlanningDayClosure.warehouse_id == warehouse.id,
            PlanningDayClosure.date == planning_date,
        )
    )
    assert closure_count == 1


@pytest.mark.asyncio
async def test_mandatory_unassigned_work_prevents_day_closure(
    db_session: AsyncSession,
) -> None:
    """A date cannot close while any mandatory task lacks a route stop assignment."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    request = await make_request(
        db_session,
        warehouse,
        planning_date=planning_date,
        mandatory=True,
    )
    planner = AsyncMock()
    planner.generate_plan.return_value = None
    savepoint = await db_session.begin_nested()
    with pytest.raises(ApiError) as rejected:
        await close_planning_day(db_session, planner, warehouse.id, planning_date)
    assert rejected.value.status_code == 409
    assert rejected.value.code == "MANDATORY_TASKS_UNASSIGNED"
    assert rejected.value.extra == {"task_ids": [str(request.tasks[0].id)]}
    await savepoint.rollback()

    status = await get_planning_day_status(db_session, warehouse.id, planning_date)
    assert status.accepting_requests is True
