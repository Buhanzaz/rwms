"""Warehouse plan confirmation and manual-reset command tests."""

from __future__ import annotations

from datetime import date, datetime, time, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.models import LogisticsRequest, ManualChangeAudit, RoutePlan, UnassignedTask
from app.models.domain import PlanStatus
from app.schemas.domain import (
    GeneratePlanRequest,
    ManualChangeCommand,
    RequestPlanningDetailsInput,
)
from app.services import catalog, plans
from app.services.auto_planning import generate_missing_draft_plans
from app.services.planner_runtime import RuntimePlannerFacade
from tests.factories import (
    make_driver,
    make_request,
    make_shift,
    make_vehicle,
    make_warehouse,
)

pytestmark = pytest.mark.integration
TEST_ACTOR = "test-logistics-user"


async def _unassigned_plan(
    session: AsyncSession,
    *,
    mandatory: bool,
    manually_changed: bool = False,
    status: PlanStatus = PlanStatus.DRAFT,
) -> tuple[RoutePlan, UnassignedTask]:
    """Persist one plan whose only warehouse task is unassigned."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(session, default_planning_date=planning_date)
    request = await make_request(
        session,
        warehouse,
        planning_date=planning_date,
        mandatory=mandatory,
        quantity=1,
    )
    plan = RoutePlan(
        warehouse_id=warehouse.id,
        date=planning_date,
        name="Operator plan",
        version=2,
        status=status,
        score=0,
        metrics={},
        validation_errors=[],
        validation_warnings=[],
        manually_changed=manually_changed,
    )
    session.add(plan)
    await session.flush()
    unassigned = UnassignedTask(
        route_plan_id=plan.id,
        task_id=request.tasks[0].id,
        reason_codes=["SHIFT_TIME_WINDOW"],
        descriptions_ru=["Временное окно не помещается в смену."],
        nearest_option={"possible_at": "2026-08-30T18:19:55+03:00"},
        recommendation_ru="Расширьте окно или смену.",
    )
    session.add(unassigned)
    await session.flush()
    return plan, unassigned


async def _mixed_generated_plan(
    session: AsyncSession,
) -> tuple[RuntimePlannerFacade, RoutePlan, LogisticsRequest]:
    """Persist one draft whose single cycle contains two deliveries and one pickup."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(session, default_planning_date=planning_date)
    first_delivery = await make_request(
        session,
        warehouse,
        planning_date=planning_date,
        quantity=1,
    )
    await make_request(
        session,
        warehouse,
        planning_date=planning_date,
        quantity=1,
    )
    pickup = await make_request(
        session,
        warehouse,
        planning_date=planning_date,
        quantity=1,
    )
    pickup.type = "PICKUP"
    for task in pickup.tasks:
        task.type = "PICKUP"
    driver = await make_driver(session, warehouse)
    vehicle = await make_vehicle(session, warehouse)
    await make_shift(
        session,
        warehouse,
        driver,
        vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    await session.flush()

    planner = RuntimePlannerFacade()
    run = await planner.generate_plan(
        session,
        warehouse.id,
        GeneratePlanRequest(
            date=planning_date,
            seed=warehouse.seed,
            settings=None,
            show_trace=False,
        ),
    )
    assert run.plan_id is not None
    plan = await plans.get_plan(session, run.plan_id)
    mixed_cycles = [
        cycle
        for cycle in plan.cycles
        if [stop.stop_type for stop in cycle.stops if stop.task_id is not None]
        == ["DELIVERY", "DELIVERY", "PICKUP"]
    ]
    assert len(mixed_cycles) == 1
    return planner, plan, first_delivery


async def _two_cycle_generated_plan(
    session: AsyncSession,
) -> tuple[RuntimePlannerFacade, RoutePlan, tuple[LogisticsRequest, ...]]:
    """Persist two sequential delivery cycles on one driver shift."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(session, default_planning_date=planning_date)
    requests = tuple(
        [
            await make_request(
                session,
                warehouse,
                planning_date=planning_date,
                quantity=1,
            )
            for _ in range(3)
        ]
    )
    driver = await make_driver(session, warehouse)
    vehicle = await make_vehicle(session, warehouse)
    await make_shift(
        session,
        warehouse,
        driver,
        vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    await session.flush()

    planner = RuntimePlannerFacade()
    run = await planner.generate_plan(
        session,
        warehouse.id,
        GeneratePlanRequest(
            date=planning_date,
            seed=warehouse.seed,
            settings=None,
            show_trace=False,
        ),
    )
    assert run.plan_id is not None
    plan = await plans.get_plan(session, run.plan_id)
    assert len(plan.cycles) == 2
    assert len({cycle.driver_shift_id for cycle in plan.cycles}) == 1
    return planner, plan, requests


@pytest.mark.asyncio
async def test_mandatory_unassigned_task_prevents_plan_confirmation(
    db_session: AsyncSession,
) -> None:
    """Reject confirmation with the same stable mandatory-work error as day closing."""

    plan, unassigned = await _unassigned_plan(db_session, mandatory=True)

    with pytest.raises(ApiError) as rejected:
        await plans.confirm_plan(
            db_session,
            plan.id,
            plan.version,
            accept_warnings=True,
            empty_positioning_reason=None,
            confirmed_by=TEST_ACTOR,
        )

    assert rejected.value.status_code == 409
    assert rejected.value.code == "MANDATORY_TASKS_UNASSIGNED"
    assert rejected.value.extra == {"task_ids": [str(unassigned.task_id)]}


@pytest.mark.asyncio
async def test_pending_request_refresh_prevents_stale_plan_confirmation(
    db_session: AsyncSession,
) -> None:
    """A current version still cannot be confirmed until its marked routes are refreshed."""

    plan, _ = await _unassigned_plan(db_session, mandatory=False)
    plan.metrics = {
        plans.PENDING_REQUEST_REFRESH_METRIC: {
            "request_ids": [],
            "marked_at_version": plan.version,
        }
    }
    await db_session.flush()

    with pytest.raises(ApiError) as rejected:
        await plans.confirm_plan(
            db_session,
            plan.id,
            plan.version,
            accept_warnings=True,
            empty_positioning_reason=None,
            confirmed_by=TEST_ACTOR,
        )

    assert rejected.value.code == "PLAN_REFRESH_REQUIRED"


@pytest.mark.asyncio
async def test_empty_support_positioning_requires_reason_and_audits_once(
    db_session: AsyncSession,
) -> None:
    """Never silently approve an empty support leg or duplicate its approval audit."""

    plan, _ = await _unassigned_plan(db_session, mandatory=False)
    plan.metrics = {
        "support_positioning_distance_meters": 410_000,
        "support_positioning_travel_minutes": 385,
    }
    await db_session.flush()

    with pytest.raises(ApiError) as rejected:
        await plans.confirm_plan(
            db_session,
            plan.id,
            plan.version,
            accept_warnings=True,
            empty_positioning_reason=None,
            confirmed_by=TEST_ACTOR,
        )
    assert rejected.value.code == "EMPTY_POSITIONING_REASON_REQUIRED"

    confirmed = await plans.confirm_plan(
        db_session,
        plan.id,
        plan.version,
        accept_warnings=True,
        empty_positioning_reason="Нет подходящего попутного груза",
        confirmed_by=TEST_ACTOR,
    )
    repeated = await plans.confirm_plan(
        db_session,
        confirmed.id,
        confirmed.version,
        accept_warnings=True,
        empty_positioning_reason=None,
        confirmed_by=TEST_ACTOR,
    )

    audits = list(
        (
            await db_session.scalars(
                select(ManualChangeAudit).where(
                    ManualChangeAudit.route_plan_id == plan.id,
                    ManualChangeAudit.change_type == "EMPTY_POSITIONING_APPROVED",
                )
            )
        ).all()
    )
    assert repeated.version == confirmed.version
    assert confirmed.metrics["empty_positioning_reason"] == (
        "Нет подходящего попутного груза"
    )
    assert [(audit.changed_by, audit.reason) for audit in audits] == [
        (TEST_ACTOR, "Нет подходящего попутного груза")
    ]


@pytest.mark.asyncio
async def test_manual_phase_order_and_metadata_refresh_preserve_operator_order(
    db_session: AsyncSession,
) -> None:
    """An out-of-phase pickup coordinate is clamped and survives metadata refresh."""

    planner, plan, request = await _mixed_generated_plan(db_session)
    cycle = next(
        cycle
        for cycle in plan.cycles
        if len([stop for stop in cycle.stops if stop.task_id is not None]) == 3
    )
    pickup_stop = next(stop for stop in cycle.stops if stop.stop_type == "PICKUP")
    assert pickup_stop.task_id is not None

    await planner.apply_manual_change(
        db_session,
        plan.id,
        ManualChangeCommand(
            expected_version=plan.version,
            change_type="REORDER_TASK",
            payload={
                "task_id": str(pickup_stop.task_id),
                "source_cycle_id": str(cycle.id),
                "target_cycle_id": str(cycle.id),
                "target_sequence": 2,
            },
            reason="Забрать бытовку по пути",
            changed_by=TEST_ACTOR,
        ),
    )
    manually_ordered = await plans.get_plan(db_session, plan.id)
    ordered_cycle = next(item for item in manually_ordered.cycles if item.id == cycle.id)
    manual_order = [
        stop.task_id for stop in ordered_cycle.stops if stop.task_id is not None
    ]
    assert [
        stop.stop_type for stop in ordered_cycle.stops if stop.task_id is not None
    ] == ["DELIVERY", "DELIVERY", "PICKUP"]
    validated = await planner.validate_plan(
        db_session,
        manually_ordered.id,
        manually_ordered.version,
    )
    assert validated.status == PlanStatus.VALIDATED
    assert validated.validation_errors == []
    manually_ordered_version = validated.version
    cycle_ids = [item.id for item in manually_ordered.cycles]

    original_task_ids = [task.id for task in request.tasks]
    updated = await catalog.set_request_planning_details(
        db_session,
        request.id,
        RequestPlanningDetailsInput(
            expected_version=request.version,
            date=plan.date,
            window_start=time(9, 30),
            window_end=time(15),
            is_hard=True,
            mandatory=True,
            trailer_access_allowed=True,
            contact_name="Диспетчер",
            contact_phone="+7 900 000-00-00",
        ),
    )
    marked = await plans.get_plan(db_session, plan.id)
    assert updated.mandatory is True
    assert [task.id for task in updated.tasks] == original_task_ids
    assert marked.version == manually_ordered_version + 1
    assert plans.PENDING_REQUEST_REFRESH_METRIC in marked.metrics
    with pytest.raises(ApiError) as stale:
        await plans.assert_plan_version(db_session, plan.id, manually_ordered_version)
    assert stale.value.code == "PLAN_VERSION_CONFLICT"

    generated = await generate_missing_draft_plans(
        db_session,
        planner,
        marked.warehouse_id,
        (marked.date,),
    )
    refreshed = await plans.get_plan(db_session, plan.id)
    refreshed_cycle = next(item for item in refreshed.cycles if item.id == cycle.id)

    assert generated == ()
    assert [item.id for item in refreshed.cycles] == cycle_ids
    assert [
        stop.task_id for stop in refreshed_cycle.stops if stop.task_id is not None
    ] == manual_order
    assert refreshed.version == manually_ordered_version + 2
    assert plans.PENDING_REQUEST_REFRESH_METRIC not in refreshed.metrics
    assert refreshed.manually_changed is True
    confirmed = await plans.confirm_plan(
        db_session,
        refreshed.id,
        refreshed.version,
        accept_warnings=True,
        empty_positioning_reason=None,
        confirmed_by=TEST_ACTOR,
    )
    assert confirmed.status == PlanStatus.CONFIRMED


@pytest.mark.asyncio
async def test_manual_move_clamps_delivery_and_pickup_to_their_route_phases(
    db_session: AsyncSession,
) -> None:
    """Extreme target sequences preserve manual order inside canonical D then P phases."""

    planner, plan, _ = await _mixed_generated_plan(db_session)
    cycle = next(
        item
        for item in plan.cycles
        if len([stop for stop in item.stops if stop.task_id is not None]) == 3
    )
    customer_stops = [stop for stop in cycle.stops if stop.task_id is not None]
    delivery_stops = [stop for stop in customer_stops if stop.stop_type == "DELIVERY"]
    pickup_stop = next(stop for stop in customer_stops if stop.stop_type == "PICKUP")
    moved_delivery = delivery_stops[0]
    assert moved_delivery.task_id is not None
    assert pickup_stop.task_id is not None

    delivery_changed = await planner.apply_manual_change(
        db_session,
        plan.id,
        ManualChangeCommand(
            expected_version=plan.version,
            change_type="MOVE_TASK",
            payload={
                "task_id": str(moved_delivery.task_id),
                "source_cycle_id": str(cycle.id),
                "target_cycle_id": str(cycle.id),
                "target_sequence": len(customer_stops) + 1,
            },
            reason="Перемещение доставки в конец на карте",
            changed_by=TEST_ACTOR,
        ),
    )
    after_delivery = await plans.get_plan(db_session, delivery_changed.id)
    delivery_cycle = next(item for item in after_delivery.cycles if item.id == cycle.id)
    assert [
        stop.stop_type for stop in delivery_cycle.stops if stop.task_id is not None
    ] == ["DELIVERY", "DELIVERY", "PICKUP"]
    assert [
        stop.task_id for stop in delivery_cycle.stops if stop.task_id is not None
    ] == [delivery_stops[1].task_id, moved_delivery.task_id, pickup_stop.task_id]

    pickup_changed = await planner.apply_manual_change(
        db_session,
        plan.id,
        ManualChangeCommand(
            expected_version=after_delivery.version,
            change_type="MOVE_TASK",
            payload={
                "task_id": str(pickup_stop.task_id),
                "source_cycle_id": str(cycle.id),
                "target_cycle_id": str(cycle.id),
                "target_sequence": 1,
            },
            reason="Перемещение вывоза в начало на карте",
            changed_by=TEST_ACTOR,
        ),
    )
    after_pickup = await plans.get_plan(db_session, pickup_changed.id)
    pickup_cycle = next(item for item in after_pickup.cycles if item.id == cycle.id)
    assert [
        stop.stop_type for stop in pickup_cycle.stops if stop.task_id is not None
    ] == ["DELIVERY", "DELIVERY", "PICKUP"]
    assert [
        stop.task_id for stop in pickup_cycle.stops if stop.task_id is not None
    ] == [delivery_stops[1].task_id, moved_delivery.task_id, pickup_stop.task_id]

    validated = await planner.validate_plan(
        db_session,
        after_pickup.id,
        after_pickup.version,
    )
    assert validated.status == PlanStatus.VALIDATED
    assert validated.validation_errors == []


@pytest.mark.asyncio
async def test_manual_move_still_rejects_actual_delivery_overcapacity(
    db_session: AsyncSession,
) -> None:
    """Phase clamping does not weaken the physical two-cabin capacity invariant."""

    planner, plan, _ = await _two_cycle_generated_plan(db_session)
    target_cycle = max(
        plan.cycles,
        key=lambda item: len([stop for stop in item.stops if stop.task_id is not None]),
    )
    source_cycle = min(
        plan.cycles,
        key=lambda item: len([stop for stop in item.stops if stop.task_id is not None]),
    )
    moved_stop = next(stop for stop in source_cycle.stops if stop.task_id is not None)
    assert moved_stop.task_id is not None

    with pytest.raises(ApiError) as rejected:
        await planner.apply_manual_change(
            db_session,
            plan.id,
            ManualChangeCommand(
                expected_version=plan.version,
                change_type="MOVE_TASK",
                payload={
                    "task_id": str(moved_stop.task_id),
                    "source_cycle_id": str(source_cycle.id),
                    "target_cycle_id": str(target_cycle.id),
                    "target_sequence": 3,
                },
                reason="Проверка реальной перегрузки",
                changed_by=TEST_ACTOR,
            ),
        )

    assert rejected.value.code == "MANUAL_CHANGE_INVALID"


@pytest.mark.asyncio
async def test_manual_reorder_reschedules_following_locked_cycle_after_duration_change(
    db_session: AsyncSession,
) -> None:
    """A longer edited cycle pushes its shift suffix without losing IDs, order, or locks."""

    planner, plan, requests = await _two_cycle_generated_plan(db_session)
    ordered_cycles = sorted(plan.cycles, key=lambda item: item.sequence)
    edited_cycle, following_cycle = ordered_cycles
    original_cycle_ids = [cycle.id for cycle in ordered_cycles]
    original_orders = {
        cycle.id: [stop.task_id for stop in cycle.stops if stop.task_id is not None]
        for cycle in ordered_cycles
    }
    following_original_start = following_cycle.planned_start
    following_cycle.locked = True
    following_task_stop = next(
        stop for stop in following_cycle.stops if stop.task_id is not None
    )
    following_task_stop.locked = True
    edited_task_stop = next(stop for stop in edited_cycle.stops if stop.task_id is not None)
    assert edited_task_stop.task_id is not None
    edited_request, edited_task = next(
        (request, task)
        for request in requests
        for task in request.tasks
        if task.id == edited_task_stop.task_id
    )
    edited_task.service_minutes += 90
    edited_request.service_minutes += 90
    await db_session.flush()

    changed = await planner.apply_manual_change(
        db_session,
        plan.id,
        ManualChangeCommand(
            expected_version=plan.version,
            change_type="REORDER_TASK",
            payload={
                "task_id": str(edited_task.id),
                "source_cycle_id": str(edited_cycle.id),
                "target_cycle_id": str(edited_cycle.id),
                "target_sequence": 1,
            },
            reason="Уточнена длительность обслуживания",
            changed_by=TEST_ACTOR,
        ),
    )
    refreshed = await plans.get_plan(db_session, changed.id)
    refreshed_cycles = sorted(refreshed.cycles, key=lambda item: item.sequence)
    refreshed_edited, refreshed_following = refreshed_cycles

    assert [cycle.id for cycle in refreshed_cycles] == original_cycle_ids
    assert {
        cycle.id: [stop.task_id for stop in cycle.stops if stop.task_id is not None]
        for cycle in refreshed_cycles
    } == original_orders
    assert refreshed_edited.manually_changed is True
    assert refreshed_following.locked is True
    assert next(
        stop
        for stop in refreshed_following.stops
        if stop.task_id == following_task_stop.task_id
    ).locked is True
    assert refreshed_following.planned_start > following_original_start
    assert refreshed_following.planned_start >= refreshed_edited.planned_finish + timedelta(
        minutes=30
    )

    validated = await planner.validate_plan(
        db_session,
        refreshed.id,
        refreshed.version,
    )
    assert validated.status == PlanStatus.VALIDATED
    assert validated.validation_errors == []


@pytest.mark.asyncio
async def test_non_persistent_delay_returns_shifted_preview_without_mutating_plan(
    db_session: AsyncSession,
) -> None:
    """Return the validated shifted schedule while the saved revision stays byte-for-byte timed."""

    planner, plan, _ = await _two_cycle_generated_plan(db_session)
    source_cycle = min(plan.cycles, key=lambda item: item.sequence)
    plan_id = plan.id
    original_start = source_cycle.planned_start
    original_version = plan.version

    preview = await planner.apply_simulation_delay(
        db_session,
        plan_id,
        ManualChangeCommand(
            expected_version=plan.version,
            change_type="SIMULATION_DELAY",
            payload={
                "driver_shift_id": str(source_cycle.driver_shift_id),
                "effective_at": original_start.isoformat(),
                "delay_minutes": 30,
                "persist": False,
            },
            reason="Проверка опоздания",
            changed_by=TEST_ACTOR,
        ),
    )

    preview_cycle = min(preview.cycles, key=lambda item: item.sequence)
    assert preview_cycle.planned_start == original_start + timedelta(minutes=30)
    assert preview_cycle.stops[0].planned_arrival == preview_cycle.planned_start
    assert preview.version == original_version

    db_session.expire_all()
    persisted = await plans.get_plan(db_session, plan_id)
    persisted_cycle = min(persisted.cycles, key=lambda item: item.sequence)
    assert persisted_cycle.planned_start == original_start
    assert persisted.version == original_version


@pytest.mark.asyncio
async def test_non_persistent_unavailability_returns_warning_without_audit_mutation(
    db_session: AsyncSession,
) -> None:
    """Explain the affected work in preview mode without changing plan warnings or version."""

    planner, plan, _ = await _two_cycle_generated_plan(db_session)
    source_cycle = min(plan.cycles, key=lambda item: item.sequence)
    plan_id = plan.id
    original_warnings = list(plan.validation_warnings)
    original_version = plan.version

    preview = await planner.apply_simulation_delay(
        db_session,
        plan_id,
        ManualChangeCommand(
            expected_version=plan.version,
            change_type="DRIVER_UNAVAILABLE",
            payload={
                "driver_shift_id": str(source_cycle.driver_shift_id),
                "effective_at": source_cycle.planned_start.isoformat(),
                "persist": False,
            },
            reason="Проверка отсутствия водителя",
            changed_by=TEST_ACTOR,
        ),
    )

    warning = preview.validation_warnings[-1]
    assert warning["code"] == "DRIVER_UNAVAILABLE"
    assert warning["affected_task_ids"]
    assert preview.version == original_version

    db_session.expire_all()
    persisted = await plans.get_plan(db_session, plan_id)
    assert persisted.validation_warnings == original_warnings
    assert persisted.version == original_version


@pytest.mark.asyncio
async def test_nearest_option_is_a_typed_readable_timestamp(
    db_session: AsyncSession,
) -> None:
    """Serialize possible_at as a response object instead of a raw JSON string."""

    plan, _ = await _unassigned_plan(db_session, mandatory=False)

    response = plans.plan_read(await plans.get_plan(db_session, plan.id))

    nearest = response.unassigned_tasks[0].nearest_option
    assert nearest is not None
    assert nearest.possible_at == datetime(
        2026,
        8,
        30,
        18,
        19,
        55,
        tzinfo=nearest.possible_at.tzinfo,
    )
    assert nearest.model_dump(mode="json") == {
        "possible_at": "2026-08-30T18:19:55+03:00"
    }


@pytest.mark.asyncio
async def test_manual_reset_archives_source_and_is_retry_safe(
    db_session: AsyncSession,
) -> None:
    """A retried reset returns the same automatic replacement without regenerating it."""

    source, _ = await _unassigned_plan(
        db_session,
        mandatory=False,
        manually_changed=True,
    )
    planner = RuntimePlannerFacade()
    planner._load_snapshot = AsyncMock(return_value=object())  # type: ignore[method-assign]

    async def replace_head(*args: object, **kwargs: object) -> object:
        """Model the atomic head swap while generation itself is mocked."""

        assert kwargs["supersedes_plan_id"] == source.id
        source.status = PlanStatus.ARCHIVED
        source.version += 1
        replacement = RoutePlan(
            warehouse_id=source.warehouse_id,
            supersedes_plan_id=source.id,
            date=source.date,
            name="Automatic replacement",
            version=1,
            status=PlanStatus.GENERATED,
            score=1,
            metrics={},
            validation_errors=[],
            validation_warnings=[],
            manually_changed=False,
        )
        db_session.add(replacement)
        await db_session.flush()
        return SimpleNamespace(plan_id=replacement.id, error_message=None)

    planner._execute_generation = AsyncMock(  # type: ignore[method-assign]
        side_effect=replace_head
    )

    first = await planner.reset_manual_changes(db_session, source.id, source.version)
    retried = await planner.reset_manual_changes(db_session, source.id, 2)

    assert first.supersedes_plan_id == source.id
    assert retried.id == first.id
    assert source.status == PlanStatus.ARCHIVED
    assert source.version == 3
    assert source.metrics["manual_reset"] == {
        "expected_version": 2,
        "replacement_plan_id": str(first.id),
    }
    planner._load_snapshot.assert_awaited_once()
    planner._execute_generation.assert_awaited_once()


@pytest.mark.asyncio
async def test_confirmed_plan_rejects_manual_reset(db_session: AsyncSession) -> None:
    """Confirmed plans remain immutable even if their historical edit flag is true."""

    source, _ = await _unassigned_plan(
        db_session,
        mandatory=False,
        manually_changed=True,
        status=PlanStatus.CONFIRMED,
    )

    with pytest.raises(ApiError) as rejected:
        await RuntimePlannerFacade().reset_manual_changes(
            db_session,
            source.id,
            source.version,
        )

    assert rejected.value.status_code == 409
    assert rejected.value.code == "PLAN_ALREADY_CONFIRMED"
