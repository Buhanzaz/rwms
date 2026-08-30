"""Focused tests for warehouse-scoped RWMS synchronization and publication."""

from __future__ import annotations

import json
from datetime import UTC, date, datetime, time
from decimal import Decimal
from unittest.mock import AsyncMock
from uuid import UUID, uuid4

import httpx
import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

import app.api.catalog as catalog_api
import app.api.rwms as rwms_api
from app.config import Settings
from app.errors import ApiError
from app.integrations.rwms import RWMS_PLANNING_SCOPE, RwmsPlanningClient
from app.integrations.rwms_sync import build_assignments_command, sync_warehouse_requests
from app.main import create_app
from app.models import (
    Driver,
    DriverShift,
    LogisticsRequest,
    PlanningTask,
    RouteCycle,
    RoutePlan,
    RouteStop,
    Warehouse,
)
from app.schemas.domain import (
    RequestDateOptionInput,
    RequestDateOptionUpdate,
    RwmsCapacitySnapshotCommand,
    RwmsCapacitySnapshotResult,
    RwmsIsochroneTariff,
    RwmsPlanningCapacityJob,
    RwmsPlanningCapacityShift,
    RwmsPlanningDateOption,
    RwmsPlanningFeed,
    RwmsPlanningRequest,
    RwmsSyncFailure,
    RwmsSyncRequest,
    RwmsWarehouseIdentity,
    RwmsWarehouseRefreshResult,
    RwmsWarehouseSyncResult,
)
from tests.factories import make_warehouse


def _enabled_settings(*, capacity_publish_enabled: bool = False) -> Settings:
    """Build a fully configured integration without reading real credentials."""

    return Settings(
        rwms_sync_enabled=True,
        rwms_capacity_publish_enabled=capacity_publish_enabled,
        rwms_logistics_base_url="https://rwms.internal",
        rwms_token_url="https://auth.internal/oauth2/token",
        rwms_client_id="logistics-planner",
        rwms_client_secret="test-secret",
    )


def _source_request(
    *,
    order_id: UUID | None = None,
    unit_ids: list[UUID] | None = None,
    latitude: float | None = 59.94,
    longitude: float | None = 30.33,
    planning_date: date = date(2026, 8, 30),
) -> RwmsPlanningRequest:
    """Build one strict upstream request for synchronization and assignment tests."""

    resolved_units = unit_ids or [uuid4()]
    return RwmsPlanningRequest(
        order_id=order_id or uuid4(),
        order_version=7,
        source_revision="a" * 64,
        order_number="R-142",
        client_name="Тестовый клиент",
        address="Санкт-Петербург, тестовый адрес",
        latitude=latitude,
        longitude=longitude,
        quantity=len(resolved_units),
        unit_ids=resolved_units,
        date_options=[
            RwmsPlanningDateOption(
                date=planning_date,
                priority=1,
                is_hard=True,
                window_start=time(10),
                window_end=time(14),
            )
        ],
        trailer_access_allowed=True,
        created_at=datetime(2026, 8, 28, 8, tzinfo=UTC),
    )


def test_capacity_publication_requires_rwms_sync_configuration() -> None:
    """A publisher cannot be enabled without its authenticated RWMS transport."""

    assert Settings().rwms_capacity_publish_enabled is False
    with pytest.raises(ValueError, match="requires RWMS_SYNC_ENABLED"):
        Settings(rwms_sync_enabled=False, rwms_capacity_publish_enabled=True)


@pytest.mark.parametrize(
    "payload",
    (
        {
            "date": "2026-08-25",
            "priority": 1,
            "isHard": True,
            "windowStart": "09:00:00",
            "windowEnd": "12:00:00",
            "travelZoneHours": 0,
        },
        {
            "date": "2026-08-25",
            "priority": 1,
            "isHard": False,
            "windowStart": "09:00:00",
            "windowEnd": "12:00:00",
            "travelZoneHours": 2,
        },
        {
            "date": "2026-08-25",
            "priority": 1,
            "isHard": True,
            "travelZoneHours": 2,
        },
    ),
)
def test_rwms_travel_zone_requires_supported_hard_fixed_window(
    payload: dict[str, object],
) -> None:
    """Reject a CustomerApp band that cannot be reproduced by the planner."""

    with pytest.raises(ValueError):
        RwmsPlanningDateOption.model_validate(payload)


def test_manual_date_option_may_clear_travel_zone() -> None:
    """Keep optional CustomerApp travel-band metadata explicit and nullable."""

    created = RequestDateOptionInput(date=date(2026, 8, 25))
    cleared = RequestDateOptionUpdate(travel_zone_hours=None)
    assert created.travel_zone_hours is None
    assert cleared.travel_zone_hours is None
    assert "travel_zone_hours" in cleared.model_fields_set


def test_rwms_sync_range_is_bounded_to_31_inclusive_days() -> None:
    """Keep imports within the server-owned planning horizon."""

    accepted = RwmsSyncRequest(
        warehouse_id=uuid4(), date_from=date(2026, 8, 1), date_to=date(2026, 8, 31)
    )
    assert (accepted.date_to - accepted.date_from).days == 30
    with pytest.raises(ValueError, match="cannot exceed 31 inclusive days"):
        RwmsSyncRequest(
            warehouse_id=uuid4(),
            date_from=date(2026, 8, 1),
            date_to=date(2026, 9, 1),
        )


@pytest.mark.asyncio
async def test_directory_client_uses_frozen_warehouse_and_driver_contract() -> None:
    """Read strict canonical identities using the authoritative external warehouse UUID."""

    warehouse_id = uuid4()
    support_warehouse_id = uuid4()
    support_link_id = uuid4()
    worker_id = uuid4()
    planning_at = datetime(2026, 9, 1, 8, 30, tzinfo=UTC)
    requests: list[httpx.Request] = []

    def warehouse_payload(identifier: UUID, name: str) -> dict[str, object]:
        """Return one exact strict directory resource."""

        return {
            "warehouseId": str(identifier),
            "warehouseVersion": 4,
            "name": name,
            "city": "Санкт-Петербург",
            "address": None,
            "latitude": 59.93,
            "longitude": 30.32,
            "timeZone": "Europe/Moscow",
            "representative": identifier == warehouse_id,
            "routingReady": True,
        }

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.host == "auth.internal":
            assert request.url.path == "/oauth2/token"
            assert request.content.decode().count(f"scope={RWMS_PLANNING_SCOPE}") == 1
            return httpx.Response(
                200,
                json={"access_token": "opaque-token", "expires_in": 300},
                request=request,
            )
        assert request.headers["Authorization"] == "Bearer opaque-token"
        if request.url.path.endswith("/warehouses"):
            return httpx.Response(
                200,
                json=[warehouse_payload(warehouse_id, "Склад СПб")],
                request=request,
            )
        if request.url.path.endswith("/support-links"):
            assert dict(request.url.params) == {"at": planning_at.isoformat()}
            return httpx.Response(
                200,
                json=[
                    {
                        "supportLinkId": str(support_link_id),
                        "supportLinkVersion": 2,
                        "supportWarehouse": warehouse_payload(
                            support_warehouse_id, "Опорный склад"
                        ),
                        "servedWarehouse": warehouse_payload(warehouse_id, "Склад СПб"),
                        "priority": 1,
                        "allowDrivers": True,
                        "allowVehicles": True,
                        "allowInventory": True,
                        "allowDirectFulfillment": True,
                        "allowInterwarehouseTransfer": True,
                        "allowContractorFallback": False,
                        "allowedWeekdays": ["TUESDAY", "THURSDAY"],
                        "allowedDates": [],
                        "excludedDates": [],
                        "serviceStart": "08:00:00",
                        "serviceEnd": "20:00:00",
                    }
                ],
                request=request,
            )
        assert request.url.path.endswith("/drivers")
        assert dict(request.url.params) == {
            "warehouseId": str(warehouse_id),
            "includeIncoming": "true",
            "at": planning_at.isoformat(),
        }
        return httpx.Response(
            200,
            json=[
                {
                    "workerId": str(worker_id),
                    "displayName": "Иван Иванов",
                    "employmentType": "STAFF",
                    "phone": None,
                    "operationalWarehouseId": str(warehouse_id),
                    "availableFrom": None,
                    "availableUntil": None,
                    "availabilityKind": "HOME",
                }
            ],
            request=request,
        )

    client = RwmsPlanningClient(_enabled_settings(), transport=httpx.MockTransport(handler))
    warehouses = await client.list_warehouses()
    links = await client.list_support_links(warehouse_id, at=planning_at)
    drivers = await client.list_drivers(
        warehouse_id,
        at=planning_at,
        include_incoming=True,
    )

    assert warehouses[0].warehouse_id == warehouse_id
    assert warehouses[0].timezone == "Europe/Moscow"
    assert warehouses[0].address is None
    assert links[0].support_link_id == support_link_id
    assert links[0].support_warehouse.warehouse_id == support_warehouse_id
    assert drivers[0].worker_id == worker_id
    assert drivers[0].display_name == "Иван Иванов"
    assert [request.url.path for request in requests].count("/oauth2/token") == 1


def test_warehouse_directory_schema_rejects_partial_or_inconsistent_coordinates() -> None:
    """Treat every frozen directory field and a complete routing pair as mandatory."""

    base = {
        "warehouseId": str(uuid4()),
        "warehouseVersion": 1,
        "name": "Склад",
        "city": "Город",
        "address": None,
        "latitude": 59.93,
        "longitude": 30.32,
        "timeZone": "Europe/Moscow",
        "representative": False,
        "routingReady": True,
    }
    with pytest.raises(ValueError):
        RwmsWarehouseIdentity.model_validate(
            {key: value for key, value in base.items() if key != "routingReady"}
        )
    with pytest.raises(ValueError, match="both be set"):
        RwmsWarehouseIdentity.model_validate({**base, "longitude": None})


@pytest.mark.asyncio
async def test_capacity_client_uses_path_authority_and_dynamic_tariff_shape() -> None:
    """Use path authority and publish only the ordered dynamic tariff fields."""

    warehouse_id = uuid4()
    source_job_id = uuid4()
    source_shift_id = uuid4()
    idempotency_key = uuid4()
    revision = "b" * 64

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "auth.internal":
            return httpx.Response(
                200,
                json={"access_token": "opaque-token", "expires_in": 300},
                request=request,
            )
        assert request.method == "PUT"
        assert request.url.path.endswith(f"/capacity-snapshots/{warehouse_id}")
        assert request.headers["Idempotency-Key"] == str(idempotency_key)
        body = json.loads(request.content)
        assert set(body) == {
            "sourceGeneration",
            "sourceRevision",
            "jobs",
            "shifts",
            "isochroneTariffs",
        }
        assert body["isochroneTariffs"][-1] == {
            "travelMinutes": 300,
            "priceRubles": 30_000,
        }
        return httpx.Response(
            200,
            json={
                "warehouseId": str(warehouse_id),
                "sourceGeneration": 7,
                "version": 1,
                "sourceRevision": revision,
                "jobCount": 1,
                "shiftCount": 1,
                "isochroneTariffCount": 5,
                "replayed": False,
                "updatedAt": "2026-08-30T08:00:00Z",
            },
            request=request,
        )

    command = RwmsCapacitySnapshotCommand(
        source_generation=7,
        source_revision=revision,
        jobs=[
            RwmsPlanningCapacityJob(
                source_job_id=source_job_id,
                delivery_date=date(2026, 8, 30),
                latitude=Decimal("59.941234"),
                longitude=Decimal("30.331234"),
                cabin_count=2,
                window_start=time(9),
                window_end=time(12),
                service_minutes=30,
                task_type="DELIVERY",
                trailer_access_allowed=True,
                priority=50,
                mandatory=True,
            )
        ],
        shifts=[
            RwmsPlanningCapacityShift(
                source_shift_id=source_shift_id,
                delivery_date=date(2026, 8, 30),
                shift_start=time(8),
                shift_end=time(20),
                break_minutes=30,
                cabin_capacity=2,
            )
        ],
        isochrone_tariffs=[
            RwmsIsochroneTariff(travel_minutes=minutes, price_rubles=price)
            for minutes, price in (
                (60, 10_000),
                (120, 15_000),
                (180, 20_000),
                (240, 25_000),
                (300, 30_000),
            )
        ],
    )
    client = RwmsPlanningClient(
        _enabled_settings(capacity_publish_enabled=True),
        transport=httpx.MockTransport(handler),
    )
    result = await client.replace_capacity_snapshot(
        warehouse_id, command, idempotency_key=idempotency_key
    )

    assert isinstance(result, RwmsCapacitySnapshotResult)
    assert result.warehouse_id == warehouse_id
    assert result.isochrone_tariff_count == 5
    assert set(type(result).model_fields) == {
        "warehouse_id",
        "source_generation",
        "version",
        "source_revision",
        "job_count",
        "shift_count",
        "isochrone_tariff_count",
        "replayed",
        "updated_at",
    }


@pytest.mark.asyncio
async def test_sync_imports_valid_rows_and_reports_missing_coordinates(
    db_session: AsyncSession,
) -> None:
    """Persist valid sibling orders while reporting incomplete upstream rows."""

    warehouse = await make_warehouse(db_session)
    valid = _source_request()
    missing = _source_request(latitude=None, longitude=None)
    client = RwmsPlanningClient(_enabled_settings())
    client.get_planning_requests = AsyncMock(
        return_value=RwmsPlanningFeed(
            warehouse_id=warehouse.external_warehouse_id,
            time_zone=warehouse.timezone,
            generated_at=datetime(2026, 8, 28, 8, tzinfo=UTC),
            requests=[valid, missing],
        )
    )

    result = await sync_warehouse_requests(
        db_session,
        warehouse.id,
        RwmsSyncRequest(
            warehouse_id=warehouse.external_warehouse_id,
            date_from=date(2026, 8, 30),
            date_to=date(2026, 8, 30),
        ),
        client,
    )

    stored = list(
        await db_session.scalars(
            select(LogisticsRequest).where(LogisticsRequest.warehouse_id == warehouse.id)
        )
    )
    assert (result.imported, result.updated, result.skipped) == (1, 0, 0)
    assert [failure.code for failure in result.failures] == ["COORDINATES_REQUIRED"]
    assert [request.external_id for request in stored] == [valid.order_id]


def test_shared_driver_builds_explicit_warehouse_audience_assignment() -> None:
    """Close/apply plans for shared drivers without a fabricated RWMS worker UUID."""

    planning_date = date(2026, 8, 30)
    warehouse = Warehouse(
        id=uuid4(),
        external_warehouse_id=uuid4(),
        name="Склад СПб",
        city="Санкт-Петербург",
        address="Тестовый адрес",
        timezone="Europe/Moscow",
        latitude=59.93,
        longitude=30.32,
    )
    source = _source_request(planning_date=planning_date)
    request = LogisticsRequest(
        id=uuid4(),
        warehouse_id=warehouse.id,
        source_system="RWMS",
        external_id=source.order_id,
        external_version=source.order_version,
        external_payload=source.model_dump(mode="json", by_alias=True),
        type="DELIVERY",
        name="Заказ R-142",
        address_label=source.address,
        latitude=source.latitude,
        longitude=source.longitude,
        quantity=1,
        service_minutes=30,
        priority=0,
        mandatory=False,
        status="READY",
        split_allowed=True,
    )
    task = PlanningTask(
        id=uuid4(),
        request=request,
        part_number=1,
        quantity=1,
        type="DELIVERY",
        latitude=59.94,
        longitude=30.33,
        service_minutes=30,
        priority=0,
        mandatory=False,
        status="READY",
    )
    driver = Driver(
        id=uuid4(),
        warehouse_id=warehouse.id,
        external_worker_id=None,
        rwms_assignment_mode="WAREHOUSE_DRIVERS",
        name="Водители склада",
    )
    shift = DriverShift(
        id=uuid4(),
        warehouse_id=warehouse.id,
        driver=driver,
        driver_id=driver.id,
        vehicle_id=uuid4(),
        date_from=date(2026, 8, 1),
        date_to=date(2026, 8, 31),
        start_time=time(8),
        end_time=time(20),
    )
    plan = RoutePlan(
        id=uuid4(),
        warehouse=warehouse,
        warehouse_id=warehouse.id,
        date=planning_date,
        version=3,
    )
    cycle = RouteCycle(
        id=uuid4(),
        route_plan=plan,
        driver_shift=shift,
        driver_shift_id=shift.id,
        sequence=1,
        planned_start=datetime(2026, 8, 30, 9, tzinfo=UTC),
        planned_finish=datetime(2026, 8, 30, 11, tzinfo=UTC),
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
        quantity_delta=-1,
        load_before=1,
        load_after=0,
        latitude=task.latitude,
        longitude=task.longitude,
    )

    command = build_assignments_command(plan)

    assert command.warehouse_id == warehouse.external_warehouse_id
    assert len(command.assignments) == 1
    assert command.assignments[0].driver_audience_mode == "WAREHOUSE_DRIVERS"
    assert command.assignments[0].driver_worker_id is None


@pytest.mark.asyncio
async def test_workspace_default_refresh_failure_has_explicit_recovery_read(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Refresh once by default, then allow a deliberate persisted-state recovery read."""

    warehouse = await make_warehouse(db_session)
    calls = 0

    async def refresh(*args: object, **kwargs: object) -> RwmsWarehouseRefreshResult:
        nonlocal calls
        calls += 1
        return RwmsWarehouseRefreshResult(
            date_from=date(2026, 8, 28),
            date_to=date(2026, 9, 27),
            warehouses=[
                RwmsWarehouseSyncResult(
                    warehouse_id=warehouse.external_warehouse_id,
                    imported=1,
                    updated=0,
                    skipped=0,
                    failures=[
                        RwmsSyncFailure(
                            order_id=uuid4(),
                            code="COORDINATES_REQUIRED",
                            message="Coordinates are required",
                        )
                    ],
                )
            ],
        )

    monkeypatch.setattr(catalog_api, "refresh_warehouse_requests", refresh)
    with pytest.raises(ApiError) as error:
        await catalog_api.get_warehouse_workspace(
            warehouse.id,
            db_session,
            object(),
            _enabled_settings(),
            object(),
            True,
        )
    assert error.value.code == "RWMS_WORKSPACE_SYNC_INCOMPLETE"
    assert error.value.extra["failures"][0]["warehouse_id"] == str(
        warehouse.external_warehouse_id
    )

    recovered = await catalog_api.get_warehouse_workspace(
        warehouse.id,
        db_session,
        object(),
        _enabled_settings(),
        object(),
        False,
    )
    assert recovered.warehouse.id == warehouse.id
    assert calls == 1


@pytest.mark.asyncio
async def test_refresh_endpoint_commits_valid_siblings_before_incomplete_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Expose partial refresh failures while keeping successfully imported siblings durable."""

    warehouse_id = uuid4()
    session = AsyncMock(spec=AsyncSession)

    async def refresh(*args: object, **kwargs: object) -> RwmsWarehouseRefreshResult:
        return RwmsWarehouseRefreshResult(
            date_from=date(2026, 8, 28),
            date_to=date(2026, 9, 27),
            warehouses=[
                RwmsWarehouseSyncResult(
                    warehouse_id=warehouse_id,
                    imported=1,
                    updated=0,
                    skipped=0,
                    failures=[
                        RwmsSyncFailure(
                            order_id=uuid4(),
                            code="COORDINATES_REQUIRED",
                            message="Coordinates are required",
                        )
                    ],
                )
            ],
        )

    monkeypatch.setattr(rwms_api, "utc_today", lambda: date(2026, 8, 28))
    monkeypatch.setattr(rwms_api, "refresh_warehouse_requests", refresh)
    with pytest.raises(ApiError) as error:
        await rwms_api.refresh_rwms_requests(warehouse_id, session, object(), object())
    assert error.value.code == "RWMS_WORKSPACE_SYNC_INCOMPLETE"
    session.commit.assert_awaited_once_with()


@pytest.mark.asyncio
async def test_disabled_endpoints_fail_before_database_access() -> None:
    """Never fabricate synchronization or publication when integration is disabled."""

    transport = httpx.ASGITransport(app=create_app())
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        sync = await client.post(
            f"/api/warehouses/{uuid4()}/rwms/sync",
            json={
                "warehouse_id": str(uuid4()),
                "date_from": "2026-08-25",
                "date_to": "2026-08-26",
            },
        )
        capacity = await client.post(f"/api/warehouses/{uuid4()}/rwms/capacity")
    assert sync.status_code == 503
    assert sync.json()["code"] == "RWMS_SYNC_DISABLED"
    assert capacity.status_code == 503
    assert capacity.json()["code"] == "RWMS_CAPACITY_PUBLISH_DISABLED"


@pytest.mark.asyncio
async def test_oauth_failure_is_sanitized_and_stops_upstream_call() -> None:
    """Surface a stable token error without leaking a client secret."""

    paths: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        paths.append(request.url.path)
        return httpx.Response(401, json={"error": "invalid_client"}, request=request)

    client = RwmsPlanningClient(_enabled_settings(), transport=httpx.MockTransport(handler))
    with pytest.raises(ApiError) as error:
        await client.list_warehouses()
    assert error.value.code == "RWMS_TOKEN_REQUEST_FAILED"
    assert "test-secret" not in error.value.detail
    assert paths == ["/oauth2/token"]
