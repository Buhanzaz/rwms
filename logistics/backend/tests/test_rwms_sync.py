"""Focused tests for authenticated RWMS synchronization and plan application."""

from __future__ import annotations

from datetime import UTC, date, datetime
from unittest.mock import AsyncMock
from urllib.parse import parse_qs
from uuid import UUID, uuid4

import httpx
import pytest
from sqlalchemy.ext.asyncio import AsyncSession

import app.integrations.rwms_sync as rwms_sync_module
from app.config import Settings
from app.errors import ApiError
from app.integrations.rwms import RWMS_PLANNING_SCOPE, RwmsPlanningClient
from app.integrations.rwms_sync import (
    apply_plan_to_rwms,
    build_assignments_command,
    get_plan_rwms_status,
    rwms_plan_idempotency_key,
    sync_scenario_requests,
)
from app.main import create_app
from app.models import (
    Driver,
    DriverShift,
    LogisticsRequest,
    PlanningTask,
    RouteCycle,
    RoutePlan,
    RouteStop,
    UnassignedTask,
    Warehouse,
)
from app.schemas.domain import (
    GeoJsonGeometry,
    RwmsApplyResult,
    RwmsPlanApplyRequest,
    RwmsPlanningAssignmentStatus,
    RwmsPlanningAssignmentStatusFeed,
    RwmsSyncRequest,
    ScenarioCreate,
    WarehouseCreate,
    ZoneCreate,
)
from app.services import catalog, scenarios


def _enabled_settings() -> Settings:
    """Build a fully configured integration without reading real credentials."""

    return Settings(
        rwms_sync_enabled=True,
        rwms_logistics_base_url="https://rwms.internal",
        rwms_token_url="https://auth.internal/oauth2/token",
        rwms_client_id="logistics-planner",
        rwms_client_secret="test-secret",
    )


@pytest.mark.asyncio
async def test_disabled_integration_fails_without_http_request() -> None:
    """Keep synchronization opt-in and never fall back to fabricated source data."""

    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(500, request=request)

    client = RwmsPlanningClient(
        Settings(rwms_sync_enabled=False),
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(ApiError) as error:
        await client.get_planning_requests(
            warehouse_id=uuid4(),
            date_from=date(2026, 8, 25),
            date_to=date(2026, 8, 26),
        )
    assert error.value.code == "RWMS_SYNC_DISABLED"
    assert calls == 0


@pytest.mark.asyncio
async def test_sync_endpoint_reports_disabled_integration() -> None:
    """Expose the opt-in guard through the local REST boundary as Problem Details."""

    transport = httpx.ASGITransport(app=create_app())
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            f"/api/scenarios/{uuid4()}/rwms/sync",
            json={
                "warehouse_id": str(uuid4()),
                "date_from": "2026-08-25",
                "date_to": "2026-08-26",
            },
        )
    assert response.status_code == 503
    assert response.json()["code"] == "RWMS_SYNC_DISABLED"


@pytest.mark.asyncio
async def test_plan_status_endpoint_requires_enabled_integration() -> None:
    """Expose the exact-version status read without falling back to fabricated task states."""

    transport = httpx.ASGITransport(app=create_app())
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            f"/api/plans/{uuid4()}/rwms/status",
            params={"expected_version": 1},
        )
    assert response.status_code == 503
    assert response.json()["code"] == "RWMS_SYNC_DISABLED"


@pytest.mark.asyncio
async def test_oauth_failure_is_sanitized_and_stops_upstream_call() -> None:
    """Surface a stable token error without continuing to the planning feed."""

    paths: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        paths.append(request.url.path)
        return httpx.Response(401, json={"error": "invalid_client"}, request=request)

    client = RwmsPlanningClient(_enabled_settings(), transport=httpx.MockTransport(handler))
    with pytest.raises(ApiError) as error:
        await client.get_planning_requests(
            warehouse_id=uuid4(),
            date_from=date(2026, 8, 25),
            date_to=date(2026, 8, 26),
        )
    assert error.value.code == "RWMS_TOKEN_REQUEST_FAILED"
    assert "test-secret" not in error.value.detail
    assert paths == ["/oauth2/token"]


@pytest.mark.asyncio
async def test_assignment_status_client_uses_exact_query_and_strict_response() -> None:
    """Call the private status read with Bearer auth and reject ambiguous ownership."""

    warehouse_id = uuid4()
    order_id = uuid4()
    document_id = uuid4()
    worker_id = uuid4()
    unit_id = uuid4()
    invalid_audience = False

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/oauth2/token":
            return httpx.Response(
                200,
                json={"access_token": "opaque-token", "expires_in": 300},
                request=request,
            )
        assert request.url.path == "/api/internal/logistics/v1/planning/assignments"
        assert dict(request.url.params) == {
            "warehouseId": str(warehouse_id),
            "date": "2026-08-25",
        }
        assert request.headers["Authorization"] == "Bearer opaque-token"
        return httpx.Response(
            200,
            json={
                "warehouseId": str(warehouse_id),
                "date": "2026-08-25",
                "assignments": [
                    {
                        "orderId": str(order_id),
                        "documentId": str(document_id),
                        "scheduledDate": "2026-08-25",
                        "unitIds": [str(unit_id)],
                        "driverAudienceMode": "ASSIGNED_DRIVER",
                        "driverWorkerId": None if invalid_audience else str(worker_id),
                        "driverName": "Водитель",
                        "taskState": "SCHEDULED",
                    }
                ],
            },
            request=request,
        )

    client = RwmsPlanningClient(_enabled_settings(), transport=httpx.MockTransport(handler))
    result = await client.get_assignment_statuses(
        warehouse_id=warehouse_id,
        date=date(2026, 8, 25),
    )
    assert result.assignments[0].document_id == document_id

    invalid_audience = True
    with pytest.raises(ApiError) as error:
        await client.get_assignment_statuses(
            warehouse_id=warehouse_id,
            date=date(2026, 8, 25),
        )
    assert error.value.code == "RWMS_ASSIGNMENT_STATUS_RESPONSE_INVALID"


def _rwms_source_payload(
    order_id: UUID,
    unit_ids: list[UUID],
    *,
    order_version: int = 7,
) -> dict[str, object]:
    """Build normalized source metadata retained by a synchronized request."""

    return {
        "orderId": str(order_id),
        "orderVersion": order_version,
        "orderNumber": "R-142",
        "clientName": "Тестовый клиент",
        "address": "Москва, тестовый адрес",
        "latitude": 55.75,
        "longitude": 37.61,
        "quantity": len(unit_ids),
        "unitIds": [str(unit_id) for unit_id in unit_ids],
        "dateOptions": [{"date": "2026-08-25", "priority": 1, "isHard": True}],
        "createdAt": "2026-08-23T08:00:00Z",
    }


def _plan_with_split_rwms_delivery() -> tuple[RoutePlan, UUID, list[UUID]]:
    """Create an in-memory plan graph with one three-unit order split across two drivers."""

    scenario_id = uuid4()
    warehouse_external_id = uuid4()
    order_id = uuid4()
    unit_ids = [uuid4(), uuid4(), uuid4()]
    warehouse = Warehouse(
        id=uuid4(),
        scenario_id=scenario_id,
        external_warehouse_id=warehouse_external_id,
        name="Склад",
        latitude=55.7,
        longitude=37.6,
    )
    request = LogisticsRequest(
        id=uuid4(),
        scenario_id=scenario_id,
        source_system="RWMS",
        external_id=order_id,
        external_version=7,
        external_payload=_rwms_source_payload(order_id, unit_ids),
        type="DELIVERY",
        name="Заказ R-142",
        address_label="Москва, тестовый адрес",
        latitude=55.75,
        longitude=37.61,
        quantity=3,
        service_minutes=30,
        priority=0,
        status="READY",
        split_allowed=True,
        notes="",
    )
    first_task = PlanningTask(
        id=uuid4(),
        request=request,
        part_number=1,
        quantity=2,
        type="DELIVERY",
        latitude=55.75,
        longitude=37.61,
        service_minutes=30,
        priority=0,
        status="READY",
    )
    second_task = PlanningTask(
        id=uuid4(),
        request=request,
        part_number=2,
        quantity=1,
        type="DELIVERY",
        latitude=55.75,
        longitude=37.61,
        service_minutes=30,
        priority=0,
        status="READY",
    )
    first_driver = Driver(
        id=uuid4(),
        scenario_id=scenario_id,
        external_worker_id=uuid4(),
        name="Водитель 1",
    )
    second_driver = Driver(
        id=uuid4(),
        scenario_id=scenario_id,
        external_worker_id=uuid4(),
        name="Водитель 2",
    )
    first_shift = DriverShift(
        id=uuid4(),
        scenario_id=scenario_id,
        driver=first_driver,
        driver_id=first_driver.id,
        vehicle_id=uuid4(),
        date=date(2026, 8, 25),
        start_at=datetime(2026, 8, 25, 8, tzinfo=UTC),
        end_at=datetime(2026, 8, 25, 20, tzinfo=UTC),
    )
    second_shift = DriverShift(
        id=uuid4(),
        scenario_id=scenario_id,
        driver=second_driver,
        driver_id=second_driver.id,
        vehicle_id=uuid4(),
        date=date(2026, 8, 25),
        start_at=datetime(2026, 8, 25, 8, tzinfo=UTC),
        end_at=datetime(2026, 8, 25, 20, tzinfo=UTC),
    )
    plan = RoutePlan(
        id=uuid4(),
        scenario_id=scenario_id,
        warehouse=warehouse,
        warehouse_id=warehouse.id,
        date=date(2026, 8, 25),
        version=3,
    )
    for sequence, (task, shift) in enumerate(
        ((second_task, second_shift), (first_task, first_shift)), start=1
    ):
        cycle = RouteCycle(
            id=uuid4(),
            route_plan=plan,
            driver_shift=shift,
            driver_shift_id=shift.id,
            sequence=sequence,
            planned_start=datetime(2026, 8, 25, 8 + sequence, tzinfo=UTC),
            planned_finish=datetime(2026, 8, 25, 9 + sequence, tzinfo=UTC),
        )
        RouteStop(
            id=uuid4(),
            route_cycle=cycle,
            sequence=1,
            task=task,
            task_id=task.id,
            stop_type="DELIVERY",
            planned_arrival=cycle.planned_start,
            planned_departure=cycle.planned_finish,
            service_seconds=30 * 60,
            quantity_delta=-task.quantity,
            load_before=task.quantity,
            load_after=0,
            latitude=task.latitude,
            longitude=task.longitude,
        )
    return plan, order_id, unit_ids


def _add_unassigned_rwms_delivery(plan: RoutePlan) -> tuple[PlanningTask, UUID, UUID]:
    """Append one RWMS delivery part that the exact plan left unassigned."""

    order_id = uuid4()
    unit_id = uuid4()
    request = LogisticsRequest(
        id=uuid4(),
        scenario_id=plan.scenario_id,
        source_system="RWMS",
        external_id=order_id,
        external_version=4,
        external_payload=_rwms_source_payload(order_id, [unit_id], order_version=4),
        type="DELIVERY",
        name="Заказ R-POOL",
        address_label="Москва, адрес свободной доставки",
        latitude=55.76,
        longitude=37.62,
        quantity=1,
        service_minutes=30,
        priority=0,
        status="READY",
        split_allowed=True,
        notes="",
    )
    task = PlanningTask(
        id=uuid4(),
        request=request,
        part_number=1,
        quantity=1,
        type="DELIVERY",
        latitude=55.76,
        longitude=37.62,
        service_minutes=30,
        priority=0,
        status="READY",
    )
    UnassignedTask(
        id=uuid4(),
        route_plan=plan,
        task=task,
        reason_codes=["NO_SHIFT_CAPACITY"],
        descriptions_ru=["No shift capacity"],
        recommendation_ru="Publish to available drivers",
    )
    return task, order_id, unit_id


@pytest.mark.asyncio
async def test_plan_apply_maps_parts_deterministically_and_returns_rejections() -> None:
    """Slice exact units by part number and retain mixed applied/rejected upstream outcomes."""

    plan, order_id, unit_ids = _plan_with_split_rwms_delivery()
    command = build_assignments_command(plan)
    assert [assignment.order_id for assignment in command.assignments] == [order_id, order_id]
    assert [assignment.unit_ids for assignment in command.assignments] == [
        unit_ids[:2],
        unit_ids[2:],
    ]

    token_calls = 0
    apply_calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal token_calls, apply_calls
        if request.url.path == "/oauth2/token":
            token_calls += 1
            form = parse_qs(request.content.decode())
            assert form["scope"] == [RWMS_PLANNING_SCOPE]
            assert form["grant_type"] == ["client_credentials"]
            assert "client_id" not in form
            assert "client_secret" not in form
            assert request.headers["Authorization"].startswith("Basic ")
            return httpx.Response(
                200,
                json={"access_token": "opaque-token", "expires_in": 300},
                request=request,
            )
        apply_calls += 1
        assert request.headers["Authorization"] == "Bearer opaque-token"
        assert request.headers["Idempotency-Key"] == str(
            rwms_plan_idempotency_key(plan.id, 3)
        )
        body = request.read()
        assert body.count(str(order_id).encode()) == 2
        return httpx.Response(
            200,
            json={
                "applied": [
                    {"orderId": str(order_id), "documentId": str(uuid4()), "replayed": False}
                ],
                "rejected": [
                    {
                        "orderId": str(order_id),
                        "code": "ORDER_VERSION_CONFLICT",
                        "message": "order changed",
                    }
                ],
            },
            request=request,
        )

    client = RwmsPlanningClient(_enabled_settings(), transport=httpx.MockTransport(handler))
    result = await client.apply_assignments(
        command,
        idempotency_key=str(rwms_plan_idempotency_key(plan.id, 3)),
    )
    assert len(result.applied) == 1
    assert [(item.code, item.message) for item in result.rejected] == [
        ("ORDER_VERSION_CONFLICT", "order changed")
    ]
    assert token_calls == 1
    assert apply_calls == 1


def test_plan_apply_publishes_only_selected_unassigned_delivery_as_shared() -> None:
    """Keep leftovers hidden unless the operator explicitly selects the exact task part."""

    plan, _, _ = _plan_with_split_rwms_delivery()
    task, order_id, unit_id = _add_unassigned_rwms_delivery(plan)

    without_publication = build_assignments_command(plan)
    assert all(item.order_id != order_id for item in without_publication.assignments)

    command = build_assignments_command(plan, {task.id})
    shared = [item for item in command.assignments if item.order_id == order_id]
    assert len(shared) == 1
    assert shared[0].driver_audience_mode == "WAREHOUSE_DRIVERS"
    assert shared[0].driver_worker_id is None
    assert shared[0].unit_ids == [unit_id]


def test_plan_apply_rejects_stale_unassigned_selection() -> None:
    """Fence publication against a task that is absent from the exact plan version."""

    plan, _, _ = _plan_with_split_rwms_delivery()
    with pytest.raises(ApiError) as error:
        build_assignments_command(plan, {uuid4()})
    assert error.value.code == "RWMS_UNASSIGNED_SELECTION_INVALID"


@pytest.mark.asyncio
async def test_plan_apply_releases_the_local_snapshot_before_remote_io(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Never retain the simulator row lock while waiting for the RWMS HTTP response."""

    plan, _, _ = _plan_with_split_rwms_delivery()
    session = AsyncMock(spec=AsyncSession)
    client = AsyncMock(spec=RwmsPlanningClient)
    events: list[str] = []

    async def load_plan(_: AsyncSession, plan_id: UUID) -> RoutePlan:
        assert plan_id == plan.id
        return plan

    async def commit_snapshot() -> None:
        events.append("commit")

    async def apply_remote(
        command: object,
        *,
        idempotency_key: str,
    ) -> RwmsApplyResult:
        events.append("remote")
        assert command == build_assignments_command(plan)
        assert idempotency_key == str(rwms_plan_idempotency_key(plan.id, plan.version))
        return RwmsApplyResult(applied=[], rejected=[])

    monkeypatch.setattr(rwms_sync_module, "_load_plan_for_rwms_apply", load_plan)
    session.commit.side_effect = commit_snapshot
    client.apply_assignments.side_effect = apply_remote

    result = await apply_plan_to_rwms(
        session,
        plan.id,
        RwmsPlanApplyRequest(expected_version=plan.version),
        client,
    )

    assert result == RwmsApplyResult(applied=[], rejected=[])
    assert events == ["commit", "remote"]


@pytest.mark.asyncio
async def test_plan_status_maps_exact_unit_slices_and_ignores_other_assignments(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Expose only exact-plan task slices and close the DB snapshot before remote I/O."""

    plan, order_id, unit_ids = _plan_with_split_rwms_delivery()
    shared_task, shared_order_id, shared_unit_id = _add_unassigned_rwms_delivery(plan)
    assigned_tasks = {
        stop.task.quantity: stop.task
        for cycle in plan.cycles
        for stop in cycle.stops
        if stop.task is not None
    }
    two_unit_task = assigned_tasks[2]
    assigned_document_id = uuid4()
    shared_document_id = uuid4()
    assigned_worker_id = uuid4()
    session = AsyncMock(spec=AsyncSession)
    client = AsyncMock(spec=RwmsPlanningClient)
    events: list[str] = []

    async def load_plan(_: AsyncSession, plan_id: UUID) -> RoutePlan:
        assert plan_id == plan.id
        return plan

    async def commit_snapshot() -> None:
        events.append("commit")

    async def remote_statuses(
        *,
        warehouse_id: object,
        date: object,
    ) -> RwmsPlanningAssignmentStatusFeed:
        events.append("remote")
        assert warehouse_id == plan.warehouse.external_warehouse_id
        assert date == plan.date
        return RwmsPlanningAssignmentStatusFeed(
            warehouse_id=plan.warehouse.external_warehouse_id,
            date=plan.date,
            assignments=[
                RwmsPlanningAssignmentStatus(
                    order_id=uuid4(),
                    document_id=uuid4(),
                    scheduled_date=plan.date,
                    unit_ids=[uuid4()],
                    driver_audience_mode="WAREHOUSE_DRIVERS",
                    driver_worker_id=None,
                    driver_name="Чужая доставка",
                    task_state="SCHEDULED",
                ),
                RwmsPlanningAssignmentStatus(
                    order_id=order_id,
                    document_id=assigned_document_id,
                    scheduled_date=plan.date,
                    unit_ids=unit_ids[:2],
                    driver_audience_mode="ASSIGNED_DRIVER",
                    driver_worker_id=assigned_worker_id,
                    driver_name="Водитель RWMS",
                    task_state="SCHEDULED",
                ),
                RwmsPlanningAssignmentStatus(
                    order_id=shared_order_id,
                    document_id=shared_document_id,
                    scheduled_date=plan.date,
                    unit_ids=[shared_unit_id],
                    driver_audience_mode="WAREHOUSE_DRIVERS",
                    driver_worker_id=None,
                    driver_name="Свободная доставка",
                    task_state="SCHEDULED",
                ),
            ],
        )

    monkeypatch.setattr(rwms_sync_module, "_load_plan_for_rwms_status", load_plan)
    session.commit.side_effect = commit_snapshot
    client.get_assignment_statuses.side_effect = remote_statuses

    result = await get_plan_rwms_status(
        session,
        plan.id,
        plan.version,
        client,
    )

    assert events == ["commit", "remote"]
    assert result.plan_id == plan.id
    assert result.plan_version == plan.version
    assert {task.task_id for task in result.tasks} == {two_unit_task.id, shared_task.id}
    mapped = {task.task_id: task for task in result.tasks}
    assert mapped[two_unit_task.id].document_id == assigned_document_id
    assert mapped[two_unit_task.id].driver_worker_id == assigned_worker_id
    assert mapped[shared_task.id].request_id == shared_task.request.id
    assert mapped[shared_task.id].driver_audience_mode == "WAREHOUSE_DRIVERS"


@pytest.mark.asyncio
async def test_plan_status_rejects_conflicting_duplicate_remote_mapping(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Fail explicitly when RWMS reports two different documents for one task slice."""

    plan, order_id, unit_ids = _plan_with_split_rwms_delivery()
    session = AsyncMock(spec=AsyncSession)
    client = AsyncMock(spec=RwmsPlanningClient)

    async def load_plan(_: AsyncSession, __: UUID) -> RoutePlan:
        return plan

    client.get_assignment_statuses.return_value = RwmsPlanningAssignmentStatusFeed(
        warehouse_id=plan.warehouse.external_warehouse_id,
        date=plan.date,
        assignments=[
            RwmsPlanningAssignmentStatus(
                order_id=order_id,
                document_id=uuid4(),
                scheduled_date=plan.date,
                unit_ids=unit_ids[:2],
                driver_audience_mode="WAREHOUSE_DRIVERS",
                driver_worker_id=None,
                driver_name="Свободная доставка",
                task_state="SCHEDULED",
            ),
            RwmsPlanningAssignmentStatus(
                order_id=order_id,
                document_id=uuid4(),
                scheduled_date=plan.date,
                unit_ids=unit_ids[:2],
                driver_audience_mode="ASSIGNED_DRIVER",
                driver_worker_id=uuid4(),
                driver_name="Водитель забрал",
                task_state="SCHEDULED",
            ),
        ],
    )
    monkeypatch.setattr(rwms_sync_module, "_load_plan_for_rwms_status", load_plan)

    with pytest.raises(ApiError) as error:
        await get_plan_rwms_status(session, plan.id, plan.version, client)

    assert error.value.status_code == 502
    assert error.value.code == "RWMS_ASSIGNMENT_STATUS_CONFLICT"


@pytest.mark.asyncio
async def test_plan_status_rejects_stale_expected_version_before_remote_io(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Fence a status refresh to the same plan version shown by the operator UI."""

    plan, _, _ = _plan_with_split_rwms_delivery()
    session = AsyncMock(spec=AsyncSession)
    client = AsyncMock(spec=RwmsPlanningClient)

    async def load_plan(_: AsyncSession, __: UUID) -> RoutePlan:
        return plan

    monkeypatch.setattr(rwms_sync_module, "_load_plan_for_rwms_status", load_plan)

    with pytest.raises(ApiError) as error:
        await get_plan_rwms_status(session, plan.id, plan.version - 1, client)

    assert error.value.status_code == 409
    assert error.value.code == "PLAN_VERSION_CONFLICT"
    session.commit.assert_not_awaited()
    client.get_assignment_statuses.assert_not_awaited()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_sync_uses_coordinates_and_is_idempotent(db_session: AsyncSession) -> None:
    """Classify by coordinates, report address-only rows, and upsert stable order IDs."""

    scenario = await scenarios.create_scenario(
        db_session,
        ScenarioCreate(name="RWMS sync", timezone="Europe/Moscow"),
        Settings(),
    )
    external_warehouse_id = uuid4()
    await catalog.create_warehouse(
        db_session,
        scenario.id,
        WarehouseCreate(
            name="RWMS warehouse",
            external_warehouse_id=external_warehouse_id,
            latitude=55.7,
            longitude=37.6,
        ),
    )
    zone = await catalog.create_zone(
        db_session,
        scenario.id,
        ZoneCreate(
            name="Moscow zone",
            code="MSK",
            route_group="CITY",
            geometry=GeoJsonGeometry(
                type="Polygon",
                coordinates=[
                    [
                        (37.0, 55.0),
                        (38.0, 55.0),
                        (38.0, 56.0),
                        (37.0, 56.0),
                        (37.0, 55.0),
                    ]
                ],
            ),
        ),
    )
    order_id = uuid4()
    missing_coordinates_order_id = uuid4()
    source_unit_id = uuid4()
    missing_coordinates_unit_id = uuid4()
    source_version = 1
    source_latitude: float | None = 55.75
    source_longitude: float | None = 37.61

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/oauth2/token":
            return httpx.Response(
                200,
                json={"access_token": "opaque-token", "expires_in": 300},
                request=request,
            )
        return httpx.Response(
            200,
            json={
                "warehouseId": str(external_warehouse_id),
                "timeZone": "Europe/Moscow",
                "generatedAt": "2026-08-23T08:00:00Z",
                "requests": [
                    {
                        "orderId": str(order_id),
                        "orderVersion": source_version,
                        "orderNumber": "R-200",
                        "clientName": "Клиент",
                        "address": "Адрес намеренно не используется для классификации",
                        "latitude": source_latitude,
                        "longitude": source_longitude,
                        "quantity": 1,
                        "unitIds": [str(source_unit_id)],
                        "dateOptions": [{"date": "2026-08-25", "priority": 1, "isHard": False}],
                        "createdAt": "2026-08-23T08:00:00Z",
                    },
                    {
                        "orderId": str(missing_coordinates_order_id),
                        "orderVersion": 1,
                        "orderNumber": "R-201",
                        "clientName": "Без координат",
                        "address": "Только текстовый адрес",
                        "latitude": None,
                        "longitude": None,
                        "quantity": 1,
                        "unitIds": [str(missing_coordinates_unit_id)],
                        "dateOptions": [{"date": "2026-08-25", "priority": 1, "isHard": False}],
                        "createdAt": "2026-08-23T08:00:00Z",
                    },
                ],
            },
            request=request,
        )

    client = RwmsPlanningClient(_enabled_settings(), transport=httpx.MockTransport(handler))
    command = RwmsSyncRequest(
        warehouse_id=external_warehouse_id,
        date_from=date(2026, 8, 25),
        date_to=date(2026, 8, 26),
    )
    first = await sync_scenario_requests(db_session, scenario.id, command, client)
    assert (first.imported, first.updated, first.skipped) == (1, 0, 0)
    assert [failure.code for failure in first.failures] == ["COORDINATES_REQUIRED"]
    imported = (await catalog.list_requests(db_session, scenario.id))[0]
    assert imported.zone_id == zone.id
    assert imported.external_id == order_id

    second = await sync_scenario_requests(db_session, scenario.id, command, client)
    assert (second.imported, second.updated, second.skipped) == (0, 0, 1)

    source_version = 2
    source_latitude = 57.0
    source_longitude = 40.0
    third = await sync_scenario_requests(db_session, scenario.id, command, client)
    assert (third.imported, third.updated, third.skipped) == (0, 1, 0)
    updated = (await catalog.list_requests(db_session, scenario.id))[0]
    assert updated.zone_id is None
    assert updated.zone_classification_status == "OUTSIDE_ZONES"
    assert len(updated.date_options) == 1
