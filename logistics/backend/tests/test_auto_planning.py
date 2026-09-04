"""Integration coverage for server-owned automatic warehouse pre-planning."""

from datetime import date, time
from unittest.mock import AsyncMock

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.models import OptimizationRun, RoutePlan
from app.schemas.domain import GeneratePlanRequest
from app.services.auto_planning import generate_missing_draft_plans
from app.services.planner_runtime import RuntimePlannerFacade
from app.services.plans import PENDING_REQUEST_REFRESH_METRIC
from tests.factories import (
    make_driver,
    make_request,
    make_shift,
    make_vehicle,
    make_warehouse,
)

pytestmark = pytest.mark.integration


@pytest.mark.asyncio
async def test_complete_warehouse_day_is_automatically_requested_once(
    db_session: AsyncSession,
) -> None:
    """Complete demand/resources trigger one run and an existing plan prevents duplication."""

    planning_date = date(2026, 8, 29)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    await make_request(db_session, warehouse, planning_date=planning_date)
    run = OptimizationRun(
        warehouse_id=warehouse.id,
        seed=warehouse.seed,
        settings_snapshot={},
    )
    planner = AsyncMock()
    planner.generate_plan.return_value = run

    assert await generate_missing_draft_plans(
        db_session,
        planner,
        warehouse.id,
        (planning_date,),
    ) == ()
    planner.generate_plan.assert_not_awaited()

    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    await make_shift(
        db_session,
        warehouse,
        driver,
        vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    generated = await generate_missing_draft_plans(
        db_session,
        planner,
        warehouse.id,
        (planning_date,),
    )
    assert generated == (run,)
    planner.generate_plan.assert_awaited_once()
    command = planner.generate_plan.await_args.args[2]
    assert isinstance(command, GeneratePlanRequest)
    assert command.date == planning_date

    db_session.add(
        RoutePlan(
            warehouse_id=warehouse.id,
            date=planning_date,
            name="Current automatic plan",
        )
    )
    await db_session.flush()
    planner.reset_mock()
    assert await generate_missing_draft_plans(
        db_session,
        planner,
        warehouse.id,
        (planning_date,),
    ) == ()
    planner.generate_plan.assert_not_awaited()


@pytest.mark.asyncio
async def test_soft_full_day_option_is_complete_for_automatic_planning(
    db_session: AsyncSession,
) -> None:
    """A date-only non-hard option represents an accepted flexible warehouse day."""

    planning_date = date(2026, 9, 2)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    request = await make_request(db_session, warehouse, planning_date=planning_date)
    option = request.date_options[0]
    option.window_start = None
    option.window_end = None
    option.is_hard = False
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    await make_shift(
        db_session,
        warehouse,
        driver,
        vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    run = OptimizationRun(
        warehouse_id=warehouse.id,
        seed=warehouse.seed,
        settings_snapshot={},
    )
    planner = AsyncMock()
    planner.generate_plan.return_value = run

    generated = await generate_missing_draft_plans(
        db_session,
        planner,
        warehouse.id,
        (planning_date,),
    )

    assert generated == (run,)
    planner.generate_plan.assert_awaited_once()


@pytest.mark.asyncio
async def test_soft_full_day_option_reaches_authoritative_planner(
    db_session: AsyncSession,
) -> None:
    """The automatic coordinator and runtime snapshot accept the same flexible day."""

    planning_date = date(2026, 9, 2)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    request = await make_request(db_session, warehouse, planning_date=planning_date)
    option = request.date_options[0]
    option.window_start = None
    option.window_end = None
    option.is_hard = False
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    await make_shift(
        db_session,
        warehouse,
        driver,
        vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    await db_session.flush()

    generated = await generate_missing_draft_plans(
        db_session,
        RuntimePlannerFacade(),
        warehouse.id,
        (planning_date,),
    )

    assert len(generated) == 1
    assert generated[0].plan_id is not None


@pytest.mark.parametrize(
    ("window_start", "window_end", "is_hard"),
    (
        (time(10), None, False),
        (None, time(14), False),
        (None, None, True),
    ),
)
@pytest.mark.asyncio
async def test_incomplete_date_option_does_not_trigger_automatic_planning(
    db_session: AsyncSession,
    window_start: time | None,
    window_end: time | None,
    is_hard: bool,
) -> None:
    """Partial bounds and hard date-only options remain blocked until facts are complete."""

    planning_date = date(2026, 9, 3)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    request = await make_request(db_session, warehouse, planning_date=planning_date)
    option = request.date_options[0]
    option.window_start = window_start
    option.window_end = window_end
    option.is_hard = is_hard
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    await make_shift(
        db_session,
        warehouse,
        driver,
        vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    planner = AsyncMock()

    generated = await generate_missing_draft_plans(
        db_session,
        planner,
        warehouse.id,
        (planning_date,),
    )

    assert generated == ()
    planner.generate_plan.assert_not_awaited()


@pytest.mark.parametrize(
    ("window_start", "window_end", "is_hard"),
    (
        (time(10), None, False),
        (None, time(14), False),
        (None, None, True),
    ),
)
@pytest.mark.asyncio
async def test_authoritative_planner_rejects_incomplete_date_option(
    db_session: AsyncSession,
    window_start: time | None,
    window_end: time | None,
    is_hard: bool,
) -> None:
    """Partial bounds and hard date-only options never bypass the runtime fence."""

    planning_date = date(2026, 9, 3)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    request = await make_request(db_session, warehouse, planning_date=planning_date)
    option = request.date_options[0]
    option.window_start = window_start
    option.window_end = window_end
    option.is_hard = is_hard

    with pytest.raises(ApiError) as rejected:
        RuntimePlannerFacade._assert_planning_details_complete(
            (request,),
            planning_date,
        )

    assert rejected.value.code == "PLANNING_INPUT_INCOMPLETE"
    assert rejected.value.extra["requests"][0]["missing_fields"] == ["time_window"]


@pytest.mark.asyncio
async def test_authoritative_planner_still_requires_trailer_access_decision(
    db_session: AsyncSession,
) -> None:
    """A flexible date does not weaken the independent trailer-access readiness fence."""

    planning_date = date(2026, 9, 3)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    request = await make_request(db_session, warehouse, planning_date=planning_date)
    option = request.date_options[0]
    option.window_start = None
    option.window_end = None
    option.is_hard = False
    request.trailer_access_allowed = None

    with pytest.raises(ApiError) as rejected:
        RuntimePlannerFacade._assert_planning_details_complete(
            (request,),
            planning_date,
        )

    assert rejected.value.code == "PLANNING_INPUT_INCOMPLETE"
    assert rejected.value.extra["requests"][0]["missing_fields"] == [
        "trailer_access_allowed"
    ]


@pytest.mark.asyncio
async def test_ensure_refreshes_a_marked_plan_in_place_instead_of_regenerating(
    db_session: AsyncSession,
) -> None:
    """The ensure boundary delegates a fenced metadata refresh for the same plan version."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    plan = RoutePlan(
        warehouse_id=warehouse.id,
        date=planning_date,
        name="Manually ordered draft",
        version=7,
        metrics={
            PENDING_REQUEST_REFRESH_METRIC: {
                "request_ids": [],
                "marked_at_version": 7,
            }
        },
        manually_changed=True,
    )
    db_session.add(plan)
    await db_session.flush()
    planner = AsyncMock()
    planner.refresh_plan_after_request_changes.return_value = plan

    generated = await generate_missing_draft_plans(
        db_session,
        planner,
        warehouse.id,
        (planning_date,),
    )

    assert generated == ()
    planner.refresh_plan_after_request_changes.assert_awaited_once_with(
        db_session,
        plan.id,
        7,
    )
    planner.generate_plan.assert_not_awaited()
