"""Exceptional warehouse policy persistence, publication, and planning tests."""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import UTC, date, datetime, time, timedelta
from unittest.mock import AsyncMock
from uuid import UUID, uuid4

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from shapely.geometry import MultiPolygon, Polygon
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_capacity_rwms_client
from app.config import Settings, get_settings
from app.db import get_session, utc_now
from app.errors import ApiError
from app.geo.policy_classification import (
    InMemoryPolicyZone,
    build_policy_classification_statement,
    classify_policy_points,
    classify_policy_shapes,
    geometry_from_geojson,
)
from app.main import create_app
from app.models import (
    RouteCycle,
    RoutePlan,
    RouteStop,
    SlotDayPlan,
    SlotHold,
    UnassignedTask,
    WarehousePolicyZone,
)
from app.models.domain import PlanStatus, RequestStatus
from app.models.policy_zone import PolicyZoneKind
from app.schemas import policy_zones as policy_zone_schemas
from app.schemas.domain import GeneratePlanRequest
from app.schemas.policy_zones import (
    GeoJsonMultiPolygon,
    PolicyZoneCreate,
    PolicyZoneUpdate,
)
from app.schemas.slot_planning import SlotAvailabilityRequest, SlotHoldCreate
from app.services import policy_zones as policy_zone_service
from app.services.capacity_projection import build_capacity_projection
from app.services.planner_runtime import RuntimePlannerFacade
from app.services.plans import get_plan
from app.slot_planning import application as slot_application_module
from app.slot_planning.application import SlotPlanningApplication
from tests.auth import admin_access_token_verifier
from tests.factories import (
    make_driver,
    make_request,
    make_routable_vehicle,
    make_shift,
    make_vehicle,
    make_warehouse,
)

pytestmark = pytest.mark.integration


def _geometry(
    *,
    west: float = 30.30,
    south: float = 59.90,
    east: float = 30.36,
    north: float = 59.97,
) -> GeoJsonMultiPolygon:
    """Build one exact closed WGS84 test square."""

    return GeoJsonMultiPolygon.model_validate(
        {
            "type": "MultiPolygon",
            "coordinates": [
                [
                    [
                        [west, south],
                        [east, south],
                        [east, north],
                        [west, north],
                        [west, south],
                    ]
                ]
            ],
        }
    )


def _zone(
    warehouse_id: UUID,
    kind: PolicyZoneKind,
    name: str,
    *,
    geometry: GeoJsonMultiPolygon | None = None,
    delivery_price_rubles: int | None = None,
    pickup_price_rubles: int | None = None,
) -> WarehousePolicyZone:
    """Build one persisted policy row without bypassing geometry validation."""

    return WarehousePolicyZone(
        warehouse_id=warehouse_id,
        name=name,
        kind=kind,
        color="#2563EB" if kind is PolicyZoneKind.SPECIAL_PRICE else "#DC2626",
        geometry=geometry_from_geojson(geometry or _geometry()),
        version=1,
        delivery_price_rubles=delivery_price_rubles,
        pickup_price_rubles=pickup_price_rubles,
    )


def _application(session: AsyncSession) -> FastAPI:
    """Bind HTTP dependencies to the isolated PostGIS transaction."""

    application = create_app(access_token_verifier=admin_access_token_verifier())

    async def session_override() -> AsyncIterator[AsyncSession]:
        """Share one rollback-isolated session without committing it."""

        yield session

    application.dependency_overrides[get_session] = session_override
    application.dependency_overrides[get_capacity_rwms_client] = lambda: AsyncMock()
    application.dependency_overrides[get_settings] = lambda: Settings(
        routing_provider="mock"
    )
    return application


def test_policy_geometry_is_exact_and_restrictions_reject_partial_money() -> None:
    """Validation preserves rings and never accepts money on a route restriction."""

    geometry = _geometry()
    assert geometry.model_dump(mode="json")["coordinates"][0][0][0] == [30.3, 59.9]
    with pytest.raises(ValueError, match="restrictions must omit prices"):
        PolicyZoneCreate(
            name="Запрет",
            kind=PolicyZoneKind.FORBIDDEN,
            geometry=geometry,
            delivery_price_rubles=1,
        )


def test_boundary_inclusion_and_smallest_same_kind_policy_are_deterministic() -> None:
    """A boundary is covered and nested policies select the smallest geometry per kind."""

    outer_id = uuid4()
    inner_id = uuid4()
    forbidden_id = uuid4()
    outer = MultiPolygon([Polygon([(0, 0), (4, 0), (4, 4), (0, 4), (0, 0)])])
    inner = MultiPolygon([Polygon([(1, 1), (2, 1), (2, 2), (1, 2), (1, 1)])])
    zones = (
        InMemoryPolicyZone(outer_id, PolicyZoneKind.SPECIAL_PRICE, outer),
        InMemoryPolicyZone(inner_id, PolicyZoneKind.SPECIAL_PRICE, inner),
        InMemoryPolicyZone(forbidden_id, PolicyZoneKind.FORBIDDEN, outer),
    )

    assert classify_policy_shapes(zones, 1, 1) == (
        forbidden_id,
        None,
        inner_id,
    )
    statement = str(
        build_policy_classification_statement(uuid4(), 1, 1)
    ).upper()
    assert "ST_AREA" in statement
    assert "ST_TRANSFORM" not in statement


@pytest.mark.asyncio
async def test_bulk_policy_classification_uses_one_matching_spatial_statement() -> None:
    """A day snapshot classifies all request points without per-request PostGIS calls."""

    session = AsyncMock(spec=AsyncSession)
    session.execute.return_value = []
    warehouse_id = uuid4()
    first_request_id = uuid4()
    second_request_id = uuid4()

    result = await classify_policy_points(
        session,
        (
            (first_request_id, warehouse_id, 59.94, 30.33),
            (second_request_id, warehouse_id, 60.01, 30.51),
        ),
    )

    session.execute.assert_awaited_once()
    statement = str(session.execute.await_args.args[0]).upper()
    assert "ST_COVERS" in statement
    assert "ST_AREA" in statement
    assert "ST_TRANSFORM" not in statement
    assert result[first_request_id].forbidden is None
    assert result[second_request_id].special_price is None


def test_geometry_rejects_payloads_above_the_shared_contract_bound(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Oversized exact polygons fail before a local mutation can be committed."""

    monkeypatch.setattr(
        policy_zone_schemas,
        "MAX_POLICY_ZONE_GEOMETRY_CHARS",
        40,
    )
    with pytest.raises(ValueError, match="2000000 character contract limit"):
        _geometry()


@pytest.mark.asyncio
async def test_category_limits_reject_create_and_kind_change_before_mutation(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Each canonical array remains publishable when a warehouse reaches its limit."""

    monkeypatch.setattr(policy_zone_service, "_POLICY_ZONE_CATEGORY_LIMIT", 1)
    warehouse = await make_warehouse(db_session)
    special = _zone(
        warehouse.id,
        PolicyZoneKind.SPECIAL_PRICE,
        "Price one",
        delivery_price_rubles=1,
        pickup_price_rubles=2,
    )
    restriction = _zone(
        warehouse.id,
        PolicyZoneKind.FORBIDDEN,
        "Restriction one",
    )
    db_session.add_all([special, restriction])
    await db_session.flush()

    with pytest.raises(ApiError, match="more than 1 price zones") as create_error:
        await policy_zone_service.create_policy_zone(
            db_session,
            warehouse.id,
            PolicyZoneCreate(
                name="Price two",
                kind=PolicyZoneKind.SPECIAL_PRICE,
                geometry=_geometry(),
                delivery_price_rubles=3,
                pickup_price_rubles=4,
            ),
        )
    assert getattr(create_error.value, "code", None) == "POLICY_ZONE_LIMIT_EXCEEDED"

    with pytest.raises(ApiError, match="more than 1 restriction zones") as update_error:
        await policy_zone_service.update_policy_zone(
            db_session,
            warehouse.id,
            special.id,
            PolicyZoneUpdate(
                expected_version=1,
                kind=PolicyZoneKind.NO_TRAILER,
                delivery_price_rubles=None,
                pickup_price_rubles=None,
            ),
        )
    assert getattr(update_error.value, "code", None) == "POLICY_ZONE_LIMIT_EXCEEDED"
    assert special.kind == PolicyZoneKind.SPECIAL_PRICE
    assert special.version == 1


@pytest.mark.asyncio
async def test_capacity_contains_exact_policy_families_and_revision(
    db_session: AsyncSession,
) -> None:
    """Publication separates money and restrictions and hashes every exact policy fact."""

    warehouse = await make_warehouse(db_session)
    warehouse.capacity_generation = 7
    special = _zone(
        warehouse.id,
        PolicyZoneKind.SPECIAL_PRICE,
        "Особая цена",
        delivery_price_rubles=12_345,
        pickup_price_rubles=6_789,
    )
    forbidden = _zone(
        warehouse.id,
        PolicyZoneKind.FORBIDDEN,
        "Запрет",
        geometry=_geometry(west=30.4, east=30.5),
    )
    db_session.add_all([special, forbidden])
    await db_session.flush()

    first = await build_capacity_projection(db_session, warehouse.id)
    payload = first.command.model_dump(mode="json", by_alias=True)

    assert payload["priceZones"] == [
        {
            "sourceZoneId": str(special.id),
            "sourceZoneVersion": 1,
            "deliveryPriceRubles": 12_345,
            "pickupPriceRubles": 6_789,
            "geometry": _geometry().model_dump(mode="json"),
        }
    ]
    assert payload["restrictionZones"] == [
        {
            "sourceZoneId": str(forbidden.id),
            "sourceZoneVersion": 1,
            "kind": "FORBIDDEN",
            "geometry": _geometry(west=30.4, east=30.5).model_dump(mode="json"),
        }
    ]

    special.delivery_price_rubles = 12_346
    special.version = 2
    await db_session.flush()
    changed = await build_capacity_projection(db_session, warehouse.id)
    assert changed.command.source_revision != first.command.source_revision
    assert changed.idempotency_key != first.idempotency_key


@pytest.mark.asyncio
async def test_create_replay_mutations_and_group_root_invalidation(
    db_session: AsyncSession,
) -> None:
    """Durable replay advances capacity once and archives only affected mutable root heads."""

    root = await make_warehouse(db_session, name="Главный")
    representative = await make_warehouse(db_session, name="Региональный")
    representative.representative = True
    request = await make_request(db_session, representative, quantity=1)
    mutable = RoutePlan(
        warehouse_id=root.id,
        date=date(2026, 8, 30),
        name="Черновик группы",
        status=PlanStatus.DRAFT,
    )
    confirmed = RoutePlan(
        warehouse_id=root.id,
        date=date(2026, 8, 31),
        name="Подтверждённый план",
        status=PlanStatus.CONFIRMED,
    )
    db_session.add_all([mutable, confirmed])
    await db_session.flush()
    task_id = request.tasks[0].id
    db_session.add_all(
        [
            UnassignedTask(
                route_plan_id=mutable.id,
                task_id=task_id,
                reason_codes=["TEST"],
                descriptions_ru=["test"],
            ),
            UnassignedTask(
                route_plan_id=confirmed.id,
                task_id=task_id,
                reason_codes=["TEST"],
                descriptions_ru=["test"],
            ),
        ]
    )
    await db_session.flush()
    application = _application(db_session)
    body = {
        "name": "Без прицепа",
        "kind": "NO_TRAILER",
        "color": "#F59E0B",
        "geometry": _geometry().model_dump(mode="json"),
        "delivery_price_rubles": None,
        "pickup_price_rubles": None,
    }
    headers = {"Idempotency-Key": "policy-command-1"}
    generation_before = representative.capacity_generation

    async with AsyncClient(
        transport=ASGITransport(app=application),
        base_url="http://test",
    ) as client:
        created = await client.post(
            f"/api/warehouses/{representative.id}/policy-zones",
            json=body,
            headers=headers,
        )
        generation_after_create = representative.capacity_generation
        replay = await client.post(
            f"/api/warehouses/{representative.id}/policy-zones",
            json=body,
            headers=headers,
        )
        assert representative.capacity_generation == generation_after_create
        conflict = await client.post(
            f"/api/warehouses/{representative.id}/policy-zones",
            json={**body, "name": "Другое"},
            headers=headers,
        )

    assert created.status_code == 201, created.text
    assert replay.status_code == 201, replay.text
    assert replay.json() == created.json()
    assert conflict.status_code == 409
    assert representative.capacity_generation > generation_before
    assert replay.json()["version"] == 1
    await db_session.refresh(mutable)
    await db_session.refresh(confirmed)
    assert mutable.status == PlanStatus.ARCHIVED
    assert confirmed.status == PlanStatus.CONFIRMED

    zone_id = created.json()["id"]
    async with AsyncClient(
        transport=ASGITransport(app=application),
        base_url="http://test",
    ) as client:
        updated = await client.patch(
            f"/api/warehouses/{representative.id}/policy-zones/{zone_id}",
            json={**body, "name": "Без прицепа 2", "expected_version": 1},
        )
        stale_delete = await client.delete(
            f"/api/warehouses/{representative.id}/policy-zones/{zone_id}",
            params={"expected_version": 1},
        )
        removed = await client.delete(
            f"/api/warehouses/{representative.id}/policy-zones/{zone_id}",
            params={"expected_version": 2},
        )

    assert updated.status_code == 200, updated.text
    assert updated.json()["version"] == 2
    assert representative.capacity_generation > generation_after_create
    assert stale_delete.status_code == 409
    assert removed.status_code == 204


@pytest.mark.asyncio
async def test_mixed_forbidden_demand_keeps_allowed_request_plannable(
    db_session: AsyncSession,
) -> None:
    """FORBIDDEN demand becomes unassigned without aborting the rest of the day."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    blocked = await make_request(
        db_session,
        warehouse,
        planning_date=planning_date,
        quantity=1,
    )
    blocked.name = "Запрещённая доставка"
    blocked.trailer_access_allowed = None
    allowed = await make_request(
        db_session,
        warehouse,
        planning_date=planning_date,
        quantity=1,
    )
    allowed.name = "Разрешённая доставка"
    allowed.latitude = 60.10
    allowed.longitude = 30.70
    for task in allowed.tasks:
        task.latitude = allowed.latitude
        task.longitude = allowed.longitude
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
    db_session.add(_zone(warehouse.id, PolicyZoneKind.FORBIDDEN, "Закрытый район"))
    await db_session.flush()

    run = await RuntimePlannerFacade().generate_plan(
        db_session,
        warehouse.id,
        GeneratePlanRequest(date=planning_date, seed=warehouse.seed),
    )

    assert run.plan_id is not None
    plan = await get_plan(db_session, run.plan_id)
    assigned_task_ids = {
        stop.task_id
        for cycle in plan.cycles
        for stop in cycle.stops
        if stop.task_id is not None
    }
    assert allowed.tasks[0].id in assigned_task_ids
    forbidden = next(item for item in plan.unassigned_tasks if item.task_id == blocked.tasks[0].id)
    assert forbidden.reason_codes == ["FORBIDDEN_POLICY_ZONE"]
    assert "Закрытый район" in forbidden.descriptions_ru[0]
    assert plan.status == PlanStatus.GENERATED


@pytest.mark.asyncio
async def test_no_trailer_policy_overrides_request_feasibility(
    db_session: AsyncSession,
) -> None:
    """A covered request reaches the planner with trailer access forced off."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    request = await make_request(
        db_session,
        warehouse,
        planning_date=planning_date,
        quantity=1,
    )
    request.trailer_access_allowed = None
    db_session.add(_zone(warehouse.id, PolicyZoneKind.NO_TRAILER, "Узкий проезд"))
    await db_session.flush()

    snapshot = await RuntimePlannerFacade()._load_snapshot(
        db_session,
        warehouse.id,
        planning_date,
        None,
        None,
    )

    core_request = next(item for item in snapshot.input_data.requests if item.id == str(request.id))
    assert core_request.trailer_access_allowed is False
    assert snapshot.policy_unassigned_by_task == {}


@pytest.mark.asyncio
async def test_no_trailer_policy_fails_closed_for_persisted_two_unit_part(
    db_session: AsyncSession,
) -> None:
    """A stored two-unit task is unassigned with policy detail, never silently split."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    request = await make_request(
        db_session,
        warehouse,
        planning_date=planning_date,
        quantity=2,
    )
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
    db_session.add(_zone(warehouse.id, PolicyZoneKind.NO_TRAILER, "Узкий проезд"))
    await db_session.flush()

    run = await RuntimePlannerFacade().generate_plan(
        db_session,
        warehouse.id,
        GeneratePlanRequest(date=planning_date, seed=warehouse.seed),
    )

    assert run.plan_id is not None
    plan = await get_plan(db_session, run.plan_id)
    unassigned = next(
        item
        for item in plan.unassigned_tasks
        if item.task_id == request.tasks[0].id
    )
    assert unassigned.reason_codes == ["NO_TRAILER_POLICY_INCOMPATIBLE_PART"]
    assert "Узкий проезд" in unassigned.descriptions_ru[0]
    assert unassigned.recommendation_ru is not None
    assert "по 1 кабине" in unassigned.recommendation_ru
    assert len(request.tasks) == 1
    assert request.tasks[0].quantity == 2


@pytest.mark.asyncio
async def test_slot_day_plan_applies_policy_to_persisted_requests_in_bulk(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Existing forbidden demand is omitted and no-trailer demand is constrained."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    forbidden_request = await make_request(
        db_session,
        warehouse,
        planning_date=planning_date,
        quantity=1,
    )
    no_trailer_request = await make_request(
        db_session,
        warehouse,
        planning_date=planning_date,
        quantity=1,
    )
    no_trailer_request.longitude = 30.45
    for task in no_trailer_request.tasks:
        task.longitude = no_trailer_request.longitude
    db_session.add_all(
        [
            _zone(warehouse.id, PolicyZoneKind.FORBIDDEN, "Закрытый район"),
            _zone(
                warehouse.id,
                PolicyZoneKind.NO_TRAILER,
                "Без прицепа",
                geometry=_geometry(west=30.40, east=30.50),
            ),
        ]
    )
    await db_session.flush()
    bulk_classifier = AsyncMock(wraps=classify_policy_points)
    monkeypatch.setattr(
        slot_application_module,
        "classify_policy_points",
        bulk_classifier,
    )

    context = await SlotPlanningApplication(Settings(routing_provider="mock"))._load_context(
        db_session,
        SlotAvailabilityRequest(
            warehouse_id=warehouse.id,
            date=planning_date,
            address="Новый адрес вне политик",
            latitude=60.10,
            longitude=30.70,
            cabin_count=1,
            site_cabin_capacity=1,
            service_duration_minutes=60,
        ),
    )

    planned_tasks = {
        task.id: task
        for task in (
            *context.day_plan.unassigned_deliveries,
            *context.day_plan.pickup_pool,
            *(
                task
                for driver in context.day_plan.drivers
                for trip in driver.trips
                for task in (*trip.deliveries, *trip.pickups)
            ),
        )
    }
    assert str(forbidden_request.tasks[0].id) not in planned_tasks
    assert planned_tasks[str(no_trailer_request.tasks[0].id)].trailer_access_allowed is False
    bulk_classifier.assert_awaited_once()
    classified_points = bulk_classifier.await_args.args[1]
    assert {point[0] for point in classified_points} == {
        forbidden_request.id,
        no_trailer_request.id,
    }


@pytest.mark.asyncio
async def test_slot_day_plan_preserves_confirmed_workload_under_new_policies(
    db_session: AsyncSession,
) -> None:
    """Later restrictions cannot erase or alter an assigned confirmed-plan task."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    request = await make_request(
        db_session,
        warehouse,
        planning_date=planning_date,
        quantity=1,
    )
    request.status = RequestStatus.PLANNED
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_vehicle(db_session, warehouse)
    shift = await make_shift(
        db_session,
        warehouse,
        driver,
        vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    confirmed = RoutePlan(
        warehouse_id=warehouse.id,
        date=planning_date,
        name="Подтверждённый маршрут",
        status=PlanStatus.CONFIRMED,
    )
    db_session.add(confirmed)
    await db_session.flush()
    cycle = RouteCycle(
        route_plan_id=confirmed.id,
        driver_shift_id=shift.id,
        sequence=1,
        planned_start=datetime(2026, 8, 30, 7, tzinfo=UTC),
        planned_finish=datetime(2026, 8, 30, 8, tzinfo=UTC),
    )
    db_session.add(cycle)
    await db_session.flush()
    task = request.tasks[0]
    db_session.add_all(
        [
            RouteStop(
                route_cycle_id=cycle.id,
                sequence=1,
                task_id=task.id,
                stop_type="DELIVERY",
                planned_arrival=cycle.planned_start,
                planned_departure=cycle.planned_finish,
                service_seconds=60 * 60,
                quantity_delta=-1,
                load_before=1,
                load_after=0,
                latitude=task.latitude,
                longitude=task.longitude,
            ),
            _zone(warehouse.id, PolicyZoneKind.FORBIDDEN, "Новый запрет"),
            _zone(warehouse.id, PolicyZoneKind.NO_TRAILER, "Новый запрет прицепа"),
        ]
    )
    await db_session.flush()

    context = await SlotPlanningApplication(Settings(routing_provider="mock"))._load_context(
        db_session,
        SlotAvailabilityRequest(
            warehouse_id=warehouse.id,
            date=planning_date,
            address="Новый адрес вне политик",
            latitude=60.10,
            longitude=30.70,
            cabin_count=1,
            site_cabin_capacity=1,
            service_duration_minutes=60,
        ),
    )

    confirmed_task = next(
        item
        for driver_plan in context.day_plan.drivers
        for trip in driver_plan.trips
        for item in trip.deliveries
        if item.id == str(task.id)
    )
    assert confirmed_task.assigned_trip_id == str(cycle.id)
    assert confirmed_task.trailer_access_allowed is True
    assert confirmed.status == PlanStatus.CONFIRMED


@pytest.mark.asyncio
async def test_slot_day_plan_reclassifies_stale_active_holds(
    db_session: AsyncSession,
) -> None:
    """Current policies override stale hold snapshots without mutating their history."""

    planning_date = date(2026, 8, 30)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    stale_revision = "0" * 64
    day_fence = SlotDayPlan(
        warehouse_id=warehouse.id,
        date=planning_date,
        version=1,
        source_revision=stale_revision,
    )
    db_session.add(day_fence)
    await db_session.flush()
    forbidden_command = SlotHoldCreate(
        warehouse_id=warehouse.id,
        date=planning_date,
        address="Старый запрещённый hold",
        latitude=59.94,
        longitude=30.33,
        cabin_count=1,
        site_cabin_capacity=2,
        service_duration_minutes=60,
        slot_start=time(10),
        slot_end=time(14),
        client_session_id="stale-forbidden",
    )
    no_trailer_command = forbidden_command.model_copy(
        update={
            "address": "Старый hold без прицепа",
            "longitude": 30.45,
            "client_session_id": "stale-no-trailer",
        }
    )
    forbidden_hold = SlotHold(
        day_plan_id=day_fence.id,
        plan_version=day_fence.version,
        source_revision=stale_revision,
        client_session_id=forbidden_command.client_session_id,
        status="HELD",
        expires_at=utc_now() + timedelta(hours=1),
        request_snapshot=forbidden_command.model_dump(mode="json"),
        candidate_snapshot={"driver_id": str(uuid4()), "part_quantities": [1]},
    )
    no_trailer_hold = SlotHold(
        day_plan_id=day_fence.id,
        plan_version=day_fence.version,
        source_revision=stale_revision,
        client_session_id=no_trailer_command.client_session_id,
        status="HELD",
        expires_at=utc_now() + timedelta(hours=1),
        request_snapshot=no_trailer_command.model_dump(mode="json"),
        candidate_snapshot={"driver_id": str(uuid4()), "part_quantities": [1]},
    )
    db_session.add_all(
        [
            forbidden_hold,
            no_trailer_hold,
            _zone(warehouse.id, PolicyZoneKind.FORBIDDEN, "Закрытый район"),
            _zone(
                warehouse.id,
                PolicyZoneKind.NO_TRAILER,
                "Без прицепа",
                geometry=_geometry(west=30.40, east=30.50),
            ),
        ]
    )
    await db_session.flush()

    context = await SlotPlanningApplication(Settings(routing_provider="mock"))._load_context(
        db_session,
        SlotAvailabilityRequest(
            warehouse_id=warehouse.id,
            date=planning_date,
            address="Новый адрес вне политик",
            latitude=60.10,
            longitude=30.70,
            cabin_count=1,
            site_cabin_capacity=1,
            service_duration_minutes=60,
        ),
    )

    hold_tasks = {
        task.id: task
        for task in context.day_plan.unassigned_deliveries
        if task.id.startswith("hold:")
    }
    assert not any(task_id.startswith(f"hold:{forbidden_hold.id}:") for task_id in hold_tasks)
    constrained = next(
        task
        for task_id, task in hold_tasks.items()
        if task_id.startswith(f"hold:{no_trailer_hold.id}:")
    )
    assert constrained.trailer_access_allowed is False
    assert context.source_revision != stale_revision
    assert day_fence.version == 1
    assert forbidden_hold.status == "HELD"
    assert no_trailer_hold.status == "HELD"


@pytest.mark.asyncio
async def test_customer_slot_applies_policy_only_inside_normal_isochrone_reach(
    db_session: AsyncSession,
) -> None:
    """Special money and no-trailer override a reachable slot without extending reach."""

    planning_date = date(2026, 8, 29)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    driver = await make_driver(db_session, warehouse)
    vehicle = await make_routable_vehicle(db_session, warehouse)
    await make_shift(
        db_session,
        warehouse,
        driver,
        vehicle,
        date_from=planning_date,
        date_to=planning_date,
    )
    db_session.add_all(
        [
            _zone(
                warehouse.id,
                PolicyZoneKind.SPECIAL_PRICE,
                "Особая цена",
                delivery_price_rubles=33_000,
                pickup_price_rubles=21_000,
            ),
            _zone(warehouse.id, PolicyZoneKind.NO_TRAILER, "Без прицепа"),
        ]
    )
    await db_session.flush()
    payload = {
        "warehouse_id": str(warehouse.id),
        "date": planning_date.isoformat(),
        "address": "Тестовый адрес",
        "latitude": 59.94,
        "longitude": 30.33,
        "cabin_count": 1,
        "site_cabin_capacity": 1,
        "service_duration_minutes": 60,
    }

    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session)),
        base_url="http://test",
    ) as client:
        reachable = await client.post(
            "/api/planning/slot-availability",
            json=payload,
        )

    assert reachable.status_code == 200, reachable.text
    assert reachable.json()["delivery_price_rubles"] == 33_000
    assert reachable.json()["price_isochrone_minutes"] is None
    assert reachable.json()["trailer_access_allowed"] is False

    far_special = _zone(
        warehouse.id,
        PolicyZoneKind.SPECIAL_PRICE,
        "Далеко",
        geometry=_geometry(west=39.5, south=59.5, east=40.5, north=60.5),
        delivery_price_rubles=1,
        pickup_price_rubles=1,
    )
    db_session.add(far_special)
    await db_session.flush()
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session)),
        base_url="http://test",
    ) as client:
        outside = await client.post(
            "/api/planning/slot-availability",
            json={**payload, "latitude": 60.0, "longitude": 40.0},
        )

    assert outside.status_code == 422
    assert outside.json()["code"] == "DELIVERY_OUTSIDE_ISOCHRONE"


@pytest.mark.asyncio
async def test_customer_slot_rejects_forbidden_policy_before_routing(
    db_session: AsyncSession,
) -> None:
    """A forbidden destination cannot produce customer availability or a hold."""

    planning_date = date(2026, 8, 29)
    warehouse = await make_warehouse(db_session, default_planning_date=planning_date)
    db_session.add(_zone(warehouse.id, PolicyZoneKind.FORBIDDEN, "Закрытый район"))
    await db_session.flush()

    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session)),
        base_url="http://test",
    ) as client:
        response = await client.post(
            "/api/planning/slot-availability",
            json={
                "warehouse_id": str(warehouse.id),
                "date": planning_date.isoformat(),
                "address": "Закрытый адрес",
                "latitude": 59.94,
                "longitude": 30.33,
                "cabin_count": 1,
                "site_cabin_capacity": 1,
                "service_duration_minutes": 60,
            },
        )

    assert response.status_code == 422
    assert response.json()["code"] == "DELIVERY_FORBIDDEN_ZONE"
