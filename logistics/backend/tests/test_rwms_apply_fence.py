"""Publication rechecks current facts without replaying unsafe work or changing history."""

from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import httpx
import pytest
from sqlalchemy import update
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError
from app.integrations import rwms_sync
from app.integrations.rwms import RwmsPlanningClient
from app.models import (
    PlanningDayPolicy,
    RoutePlan,
    Trailer,
    VehicleLoadProfile,
    WarehousePolicyZone,
)
from app.models.domain import PlanStatus, RequestStatus, TaskStatus
from app.models.operations import PlanningDayMode
from app.models.policy_zone import PolicyZoneKind
from app.schemas.domain import (
    GeneratePlanRequest,
    RwmsApplyResult,
    RwmsPlanApplyRequest,
    RwmsSyncRequest,
)
from app.services import plans
from app.services.planner_runtime import RuntimePlannerFacade
from tests.factories import (
    make_driver,
    make_request,
    make_routable_vehicle,
    make_shift,
    make_warehouse,
)
from tests.test_policy_zones import _zone
from tests.test_rwms_sync import _planning_feed_client, _source_request
from tests.test_truck_cycle_router import RecordingTruckProvider


async def _confirmed_plan(
    session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
    *,
    with_trailer: bool = False,
) -> tuple[RoutePlan, RuntimePlannerFacade, RwmsPlanningClient, RecordingTruckProvider]:
    """Build a real persisted owner-bound plan with load-specific route evidence."""

    day = date(2026, 8, 30)
    warehouse = await make_warehouse(session, default_planning_date=day)
    client = _planning_feed_client(
        warehouse,
        [
            _source_request(
                planning_date=day,
                unit_ids=[uuid4(), uuid4()] if with_trailer else [uuid4()],
            )
        ],
    )
    result = await rwms_sync.sync_warehouse_requests(
        session,
        warehouse.id,
        RwmsSyncRequest(
            warehouse_id=warehouse.external_warehouse_id,
            date_from=day,
            date_to=day,
        ),
        client,
    )
    assert result.imported == 1
    driver = await make_driver(session, warehouse)
    vehicle = await make_routable_vehicle(session, warehouse)
    if with_trailer:
        trailer = Trailer(
            warehouse_id=warehouse.id,
            name="Test trailer",
            registration_number=str(uuid4()),
            tare_weight_kg=3_000,
            max_gross_weight_kg=10_000,
            length_mm=7_000,
            width_mm=2_500,
            height_mm=1_600,
            platform_length_mm=6_500,
            platform_width_mm=2_500,
            platform_height_from_ground_mm=900,
            max_platform_payload_kg=5_000,
            payload_capacity_kg=5_000,
            axle_count=2,
            max_axle_load_kg=8_000,
            max_cargo_length_mm=6_500,
            max_cargo_width_mm=2_500,
            max_cargo_height_mm=2_600,
            max_cargo_weight_kg=5_000,
        )
        session.add(trailer)
        await session.flush()
        vehicle.capacity = 2
        vehicle.can_use_trailer = True
        vehicle.default_trailer = trailer
        vehicle.combined_length_with_trailer_mm = 15_000
        session.add_all(
            [
                VehicleLoadProfile(
                    vehicle_id=vehicle.id,
                    configuration_type=configuration,
                    max_actual_axle_load_kg=7_500,
                )
                for configuration in (
                    "EMPTY_COMBINATION",
                    "CARGO_ON_TRUCK_WITH_TRAILER",
                    "CARGO_ON_TRAILER_WITH_TRAILER",
                    "TWO_CARGO_SPLIT",
                )
            ]
        )
        await session.flush()
    await make_shift(session, warehouse, driver, vehicle, date_from=day, date_to=day)
    runtime = RuntimePlannerFacade(routing_provider="valhalla", osm_data_version="test-graph")
    provider = RecordingTruckProvider()
    monkeypatch.setattr(provider, "aclose", AsyncMock(), raising=False)
    monkeypatch.setattr(runtime, "_provider", lambda _snapshot: provider)
    run = await runtime.generate_plan(
        session,
        warehouse.id,
        GeneratePlanRequest(date=day, seed=warehouse.seed),
    )
    assert run.plan_id is not None
    draft = await plans.get_plan(session, run.plan_id)
    assert draft.cycles
    confirmed = await plans.confirm_plan(
        session,
        draft.id,
        draft.version,
        planner=runtime,
        accept_warnings=True,
        empty_positioning_reason=None,
        confirmed_by="test-operator",
    )
    assert confirmed.status == PlanStatus.CONFIRMED
    if with_trailer:
        assert any(
            segment.routing_profile_snapshot.get("trailerId")
            for cycle in confirmed.cycles
            for segment in cycle.segments
        )
    client.apply_assignments = AsyncMock(return_value=RwmsApplyResult())
    return confirmed, runtime, client, provider


@pytest.mark.asyncio
async def test_fresh_confirmed_plan_is_published_without_extra_road_queries(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Read-only physical proof validation preserves the exact confirmed revision."""

    plan, runtime, client, provider = await _confirmed_plan(db_session, monkeypatch)
    version = plan.version
    road_calls = len(provider.profiles)
    command = RwmsPlanApplyRequest(expected_version=version)
    await rwms_sync.apply_plan_to_rwms(db_session, plan.id, command, client, planner=runtime)
    await rwms_sync.apply_plan_to_rwms(db_session, plan.id, command, client, planner=runtime)
    assert client.apply_assignments.await_count == 2
    first, second = client.apply_assignments.await_args_list
    assert first == second
    assert len(first.args[0].assignments) == 1
    assert len(provider.profiles) == road_calls
    assert (plan.status, plan.version) == (PlanStatus.CONFIRMED, version)


@pytest.mark.asyncio
@pytest.mark.parametrize("change", ("disabled_vehicle", "truck_height", "forbidden", "day_mode"))
async def test_post_confirmation_change_prevents_owner_post(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
    change: str,
) -> None:
    """Current truck, zone and day policy changes cannot hide behind stored valid=True."""

    plan, runtime, client, provider = await _confirmed_plan(db_session, monkeypatch)
    version = plan.version
    stop = next(stop for cycle in plan.cycles for stop in cycle.stops if stop.task is not None)
    task = stop.task
    request = task.request
    before = (task.status, request.status, request.scheduled_date)
    if change == "disabled_vehicle":
        plan.cycles[0].driver_shift.vehicle.active = False
    elif change == "truck_height":
        plan.cycles[0].driver_shift.vehicle.height_mm += 100
    elif change == "forbidden":
        zone = _zone(plan.warehouse_id, PolicyZoneKind.NO_TRAILER, "Changed restriction")
        db_session.add(zone)
        await db_session.flush()
        # Retain the old entity in the identity map while changing the database.
        await db_session.execute(
            update(WarehousePolicyZone)
            .where(WarehousePolicyZone.id == zone.id)
            .values(kind=PolicyZoneKind.FORBIDDEN)
            .execution_options(synchronize_session=False)
        )
        assert zone.kind == PolicyZoneKind.NO_TRAILER
    else:
        policy = PlanningDayPolicy(
            warehouse_id=plan.warehouse_id,
            date=plan.date,
            mode=PlanningDayMode.DELIVERIES_AND_PICKUPS,
            changed_by="test-operator",
        )
        db_session.add(policy)
        await db_session.flush()
        await db_session.execute(
            update(PlanningDayPolicy)
            .where(PlanningDayPolicy.id == policy.id)
            .values(mode=PlanningDayMode.PICKUPS_ONLY)
            .execution_options(synchronize_session=False)
        )
        assert policy.mode == PlanningDayMode.DELIVERIES_AND_PICKUPS
    await db_session.flush()
    road_calls = len(provider.profiles)
    with pytest.raises(ApiError) as rejected:
        await rwms_sync.apply_plan_to_rwms(
            db_session,
            plan.id,
            RwmsPlanApplyRequest(expected_version=version),
            client,
            planner=runtime,
        )
    assert rejected.value.code in ("PLAN_REFRESH_REQUIRED", "PLAN_TRUCK_ROUTE_STALE")
    client.apply_assignments.assert_not_awaited()
    assert len(provider.profiles) == road_calls
    assert (plan.status, plan.version) == (PlanStatus.CONFIRMED, version)
    await db_session.refresh(task)
    assert task.status == before[0]
    await db_session.refresh(request)
    assert (request.status, request.scheduled_date) == before[1:]


@pytest.mark.asyncio
async def test_unrelated_incomplete_new_request_does_not_block_an_accepted_route(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Revalidating a confirmed route does not admit unrelated new demand into its inputs."""

    plan, runtime, client, _ = await _confirmed_plan(db_session, monkeypatch)
    unrelated = await make_request(db_session, plan.warehouse, planning_date=plan.date, quantity=1)
    unrelated.trailer_access_allowed = None
    await db_session.flush()
    await rwms_sync.apply_plan_to_rwms(
        db_session,
        plan.id,
        RwmsPlanApplyRequest(expected_version=plan.version),
        client,
        planner=runtime,
    )
    client.apply_assignments.assert_awaited_once()
    assert unrelated.status == RequestStatus.READY
    assert unrelated.trailer_access_allowed is None


@pytest.mark.asyncio
@pytest.mark.parametrize("change", ("no_trailer_zone", "disabled_trailer"))
async def test_confirmed_combination_rechecks_trailer_and_access(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
    change: str,
) -> None:
    """Neither a new trailer ban nor a disabled trailer may reuse a loaded proof."""

    plan, runtime, client, _ = await _confirmed_plan(db_session, monkeypatch, with_trailer=True)
    if change == "no_trailer_zone":
        db_session.add(_zone(plan.warehouse_id, PolicyZoneKind.NO_TRAILER, "No trailer access"))
    else:
        plan.cycles[0].driver_shift.vehicle.default_trailer.active = False
    await db_session.flush()
    with pytest.raises(ApiError) as rejected:
        await rwms_sync.apply_plan_to_rwms(
            db_session,
            plan.id,
            RwmsPlanApplyRequest(expected_version=plan.version),
            client,
            planner=runtime,
        )
    assert rejected.value.code in ("PLAN_REFRESH_REQUIRED", "PLAN_TRUCK_ROUTE_STALE")
    client.apply_assignments.assert_not_awaited()
    assert plan.status == PlanStatus.CONFIRMED


@pytest.mark.asyncio
async def test_lost_response_then_breakdown_does_not_repeat_owner_post(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A prior unknown owner outcome needs reconciliation, not blind stale replay."""

    plan, runtime, client, _ = await _confirmed_plan(db_session, monkeypatch)
    command = RwmsPlanApplyRequest(expected_version=plan.version)
    client.apply_assignments.side_effect = httpx.ReadTimeout(
        "Response lost after possible acceptance"
    )
    with pytest.raises(httpx.ReadTimeout):
        await rwms_sync.apply_plan_to_rwms(db_session, plan.id, command, client, planner=runtime)
    plan.cycles[0].driver_shift.vehicle.active = False
    await db_session.flush()
    with pytest.raises(ApiError, match="no longer available"):
        await rwms_sync.apply_plan_to_rwms(db_session, plan.id, command, client, planner=runtime)
    client.apply_assignments.assert_awaited_once()
    assert plan.status == PlanStatus.CONFIRMED


@pytest.mark.asyncio
async def test_execution_fence_reloads_the_plan_version_from_the_database(
    db_session: AsyncSession,
) -> None:
    """An old identity-map value is not a valid optimistic-concurrency fence."""

    warehouse = await make_warehouse(db_session)
    plan = RoutePlan(warehouse_id=warehouse.id, date=date(2026, 8, 30), name="Plan", version=1)
    db_session.add(plan)
    await db_session.flush()
    await db_session.execute(
        update(RoutePlan)
        .where(RoutePlan.id == plan.id)
        .values(version=2)
        .execution_options(synchronize_session=False)
    )
    assert plan.version == 1
    with pytest.raises(ApiError) as rejected:
        await plans.lock_plan_execution_resources(db_session, plan.id, 1)
    assert rejected.value.code == "PLAN_VERSION_CONFLICT"
    assert rejected.value.extra["actual_version"] == 2


@pytest.mark.parametrize("state", ("READY", "UNASSIGNED", "IN_PROGRESS", "COMPLETED", "CANCELLED"))
@pytest.mark.parametrize("changed", ("task", "request"))
def test_assigned_execution_state_cannot_be_reset_by_recovery_projection(
    state: str,
    changed: str,
) -> None:
    """Local terminal or started facts are never normalized to READY for admission."""

    day = date(2026, 8, 30)
    request = SimpleNamespace(id=uuid4(), status=RequestStatus.PLANNED, scheduled_date=day)
    task = SimpleNamespace(
        id=uuid4(), request_id=request.id, request=request, status=TaskStatus.PLANNED
    )
    changed_entity = task if changed == "task" else request
    changed_entity.status = state
    plan = SimpleNamespace(
        date=day,
        cycles=[SimpleNamespace(stops=[SimpleNamespace(task_id=task.id, task=task)])],
        unassigned_tasks=[],
    )
    with pytest.raises(ApiError) as rejected:
        rwms_sync._require_publishable_task_states(plan, set())
    assert rejected.value.code == "PLAN_REFRESH_REQUIRED"
    assert changed_entity.status == state


@pytest.mark.parametrize("state", ("READY", "UNASSIGNED"))
def test_unassigned_publication_remains_distinct_from_reserved_cycles(state: str) -> None:
    """Fully unassigned work was not reserved by confirmation and remains publishable."""

    request = SimpleNamespace(id=uuid4(), status=state)
    task = SimpleNamespace(id=uuid4(), request=request, status=state)
    plan = SimpleNamespace(
        cycles=[], unassigned_tasks=[SimpleNamespace(task_id=task.id, task=task)]
    )
    rwms_sync._require_publishable_task_states(plan, {task.id})
