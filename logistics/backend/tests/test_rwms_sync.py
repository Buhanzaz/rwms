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
from app.integrations.rwms_sync import (
    _build_driver_shift_plans,
    _vehicle_configuration_type,
    build_assignments_command,
    sync_warehouse_requests,
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
    Trailer,
    Vehicle,
    Warehouse,
)
from app.schemas.domain import (
    RequestDateOptionInput,
    RequestDateOptionUpdate,
    RwmsAssignmentsCommand,
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
    RwmsSyncResult,
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
    delivery_price_rubles: int | None = None,
    price_isochrone_minutes: int | None = None,
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
        delivery_price_rubles=delivery_price_rubles,
        price_isochrone_minutes=price_isochrone_minutes,
        created_at=datetime(2026, 8, 28, 8, tzinfo=UTC),
    )


def test_capacity_publication_requires_rwms_sync_configuration() -> None:
    """A publisher cannot be enabled without its authenticated RWMS transport."""

    assert Settings().rwms_capacity_publish_enabled is False
    with pytest.raises(ValueError, match="requires RWMS_SYNC_ENABLED"):
        Settings(rwms_sync_enabled=False, rwms_capacity_publish_enabled=True)


def test_planning_request_accepts_confirmed_price_without_historical_tier() -> None:
    """Keep an authoritative amount usable when an older slot has no tier snapshot."""

    request = _source_request(delivery_price_rubles=20_000)

    assert request.delivery_price_rubles == 20_000
    assert request.price_isochrone_minutes is None


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


@pytest.mark.asyncio
async def test_directory_client_reads_frozen_adjacent_support_network() -> None:
    """Read active adjacent support edges without adding a second local warehouse graph."""

    root_id = uuid4()
    served_id = uuid4()
    link_id = uuid4()

    def identity(identifier: UUID, *, representative: bool) -> dict[str, object]:
        """Build one strict warehouse identity nested in the network response."""

        return {
            "warehouseId": str(identifier),
            "warehouseVersion": 1,
            "name": "Representative" if representative else "Main",
            "city": "Test city",
            "address": None,
            "latitude": 59.93,
            "longitude": 30.32,
            "timeZone": "Europe/Moscow",
            "representative": representative,
            "routingReady": True,
        }

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "auth.internal":
            return httpx.Response(
                200,
                json={"access_token": "opaque-token", "expires_in": 300},
                request=request,
            )
        assert request.method == "GET"
        assert request.url.path.endswith(f"/{root_id}/support-network")
        return httpx.Response(
            200,
            json=[
                {
                    "supportLinkId": str(link_id),
                    "supportLinkVersion": 1,
                    "supportWarehouse": identity(root_id, representative=False),
                    "servedWarehouse": identity(served_id, representative=True),
                    "priority": 1,
                    "allowDrivers": True,
                    "allowVehicles": True,
                    "allowInventory": True,
                    "allowDirectFulfillment": True,
                    "allowInterwarehouseTransfer": True,
                    "allowContractorFallback": True,
                    "allowedWeekdays": [],
                    "allowedDates": [],
                    "excludedDates": [],
                    "serviceStart": None,
                    "serviceEnd": None,
                }
            ],
            request=request,
        )

    client = RwmsPlanningClient(
        _enabled_settings(),
        transport=httpx.MockTransport(handler),
    )

    links = await client.list_support_network(root_id)

    assert [link.support_link_id for link in links] == [link_id]
    assert links[0].support_warehouse.warehouse_id == root_id
    assert links[0].served_warehouse.warehouse_id == served_id


@pytest.mark.asyncio
async def test_rwms_client_retains_safe_problem_code_without_raw_http_detail() -> None:
    """Upstream Problem Details remains classifiable without exposing HTTP-only diagnostics."""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "auth.internal":
            return httpx.Response(
                200,
                json={"access_token": "opaque-token", "expires_in": 300},
                request=request,
            )
        return httpx.Response(
            400,
            json={
                "code": "LOGISTICS_INVALID_REQUEST",
                "detail": "internal implementation detail",
            },
            request=request,
        )

    client = RwmsPlanningClient(
        _enabled_settings(),
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(ApiError) as failure:
        await client.list_warehouses()

    assert failure.value.code == "RWMS_LOGISTICS_INVALID_REQUEST"
    assert failure.value.detail == "RWMS logistics-service rejected the request"
    assert failure.value.extra == {
        "upstream_status": 400,
        "upstream_code": "LOGISTICS_INVALID_REQUEST",
    }
    assert "HTTP 400" not in failure.value.detail


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


@pytest.mark.asyncio
async def test_sync_persists_and_refreshes_calculated_delivery_price(
    db_session: AsyncSession,
) -> None:
    """Required nullable feed pricing survives import, refresh, and a fresh ORM read."""

    warehouse = await make_warehouse(db_session)
    source = _source_request(
        delivery_price_rubles=28_500,
        price_isochrone_minutes=180,
    )
    client = RwmsPlanningClient(_enabled_settings())
    client.get_planning_requests = AsyncMock(
        return_value=RwmsPlanningFeed(
            warehouse_id=warehouse.external_warehouse_id,
            time_zone=warehouse.timezone,
            generated_at=datetime(2026, 8, 28, 8, tzinfo=UTC),
            requests=[source],
        )
    )
    command = RwmsSyncRequest(
        warehouse_id=warehouse.external_warehouse_id,
        date_from=date(2026, 8, 30),
        date_to=date(2026, 8, 30),
    )

    imported = await sync_warehouse_requests(db_session, warehouse.id, command, client)
    assert imported.imported == 1
    stored = await db_session.scalar(
        select(LogisticsRequest).where(LogisticsRequest.external_id == source.order_id)
    )
    assert stored is not None
    assert stored.delivery_price_rubles == 28_500
    assert stored.price_isochrone_minutes == 180

    updated_source = source.model_copy(
        update={
            "source_revision": "b" * 64,
            "delivery_price_rubles": 31_000,
            "price_isochrone_minutes": 240,
        }
    )
    client.get_planning_requests = AsyncMock(
        return_value=RwmsPlanningFeed(
            warehouse_id=warehouse.external_warehouse_id,
            time_zone=warehouse.timezone,
            generated_at=datetime(2026, 8, 28, 9, tzinfo=UTC),
            requests=[updated_source],
        )
    )
    refreshed = await sync_warehouse_requests(db_session, warehouse.id, command, client)
    assert refreshed.updated == 1
    db_session.expire_all()
    reloaded = await db_session.scalar(
        select(LogisticsRequest).where(LogisticsRequest.external_id == source.order_id)
    )
    assert reloaded is not None
    assert reloaded.delivery_price_rubles == 31_000
    assert reloaded.price_isochrone_minutes == 240


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
    assert command.assignments[0].service_warehouse_id == warehouse.external_warehouse_id
    assert command.assignments[0].driver_audience_mode == "WAREHOUSE_DRIVERS"
    assert command.assignments[0].driver_worker_id is None
    assert command.driver_shift_plans == []


def test_assigned_driver_command_contains_one_exact_vehicle_shift_snapshot() -> None:
    """Aggregate route cycles without leaking credentials or duplicating fleet ownership."""

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
    worker_id = uuid4()
    driver = Driver(
        id=uuid4(),
        warehouse_id=warehouse.id,
        external_worker_id=worker_id,
        rwms_assignment_mode="ASSIGNED_DRIVER",
        name="Александр Водитель",
    )
    trailer = Trailer(
        id=uuid4(),
        warehouse_id=warehouse.id,
        name="Прицеп Schmitz",
        registration_number="B456BB78",
    )
    vehicle = Vehicle(
        id=uuid4(),
        warehouse_id=warehouse.id,
        name="MAN TGS",
        registration_number="A123AA78",
        vehicle_type="FLATBED_CRANE",
        manufacturer="MAN",
        model="TGS",
        default_trailer=trailer,
        default_trailer_id=trailer.id,
    )
    shift = DriverShift(
        id=uuid4(),
        warehouse_id=warehouse.id,
        driver=driver,
        driver_id=driver.id,
        vehicle=vehicle,
        vehicle_id=vehicle.id,
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
    first_cycle = RouteCycle(
        id=uuid4(),
        route_plan=plan,
        driver_shift=shift,
        driver_shift_id=shift.id,
        sequence=1,
        planned_start=datetime(2026, 8, 30, 9, tzinfo=UTC),
        planned_finish=datetime(2026, 8, 30, 11, tzinfo=UTC),
        total_distance_meters=123_456,
    )
    RouteStop(
        id=uuid4(),
        route_cycle=first_cycle,
        sequence=1,
        task=task,
        task_id=task.id,
        stop_type="DELIVERY",
        planned_arrival=first_cycle.planned_start,
        planned_departure=first_cycle.planned_finish,
        service_seconds=30 * 60,
        quantity_delta=-1,
        load_before=1,
        load_after=0,
        latitude=task.latitude,
        longitude=task.longitude,
    )
    RouteCycle(
        id=uuid4(),
        route_plan=plan,
        driver_shift=shift,
        driver_shift_id=shift.id,
        sequence=2,
        planned_start=datetime(2026, 8, 30, 12, tzinfo=UTC),
        planned_finish=datetime(2026, 8, 30, 14, tzinfo=UTC),
        total_distance_meters=76_600,
    )

    command = build_assignments_command(plan)
    payload = command.model_dump(mode="json", by_alias=True)

    assert len(command.driver_shift_plans) == 1
    shift_plan = command.driver_shift_plans[0]
    assert shift_plan.source_shift_id == shift.id
    assert shift_plan.source_plan_id == plan.id
    assert shift_plan.source_plan_version == plan.version
    assert shift_plan.warehouse_id == warehouse.external_warehouse_id
    assert shift_plan.driver_id == worker_id
    assert shift_plan.driver_name == "Александр Водитель"
    assert shift_plan.work_date == planning_date
    assert shift_plan.trip_count == 2
    assert shift_plan.route_distance_meters == 200_056
    assert shift_plan.vehicle.id == vehicle.id
    assert shift_plan.vehicle.registration_number == "A123AA78"
    assert shift_plan.vehicle.configuration_type == "TRUCK_WITH_TRAILER"
    assert shift_plan.vehicle.start_odometer is None
    assert shift_plan.trailer is not None
    assert shift_plan.trailer.id == trailer.id
    assert shift_plan.trailer.registration_number == "B456BB78"
    assert set(payload) == {
        "warehouseId",
        "planId",
        "planVersion",
        "assignments",
        "driverShiftPlans",
    }
    serialized_shift = payload["driverShiftPlans"][0]
    assert serialized_shift["routeDistanceMeters"] == 200_056
    assert "routeDistanceKm" not in serialized_shift
    serialized = json.dumps(payload)
    assert "apiKey" not in serialized
    assert "clientSecret" not in serialized


def test_assignments_command_accepts_legacy_payload_without_shift_plans() -> None:
    """Keep old simulator callers valid while defaulting the new snapshot collection to empty."""

    command = RwmsAssignmentsCommand.model_validate(
        {
            "warehouseId": str(uuid4()),
            "planId": str(uuid4()),
            "planVersion": 1,
            "assignments": [],
        }
    )

    assert command.driver_shift_plans == []


def test_driver_shift_snapshot_rejects_conflicting_duplicate_source_identity() -> None:
    """Fail closed if one source shift resolves to different vehicle snapshots."""

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
    worker_id = uuid4()
    driver = Driver(
        id=uuid4(),
        warehouse_id=warehouse.id,
        external_worker_id=worker_id,
        rwms_assignment_mode="ASSIGNED_DRIVER",
        name="Александр Водитель",
    )
    shared_shift_id = uuid4()
    first_vehicle = Vehicle(
        id=uuid4(),
        warehouse_id=warehouse.id,
        name="MAN TGS",
        registration_number="A123AA78",
    )
    second_vehicle = Vehicle(
        id=uuid4(),
        warehouse_id=warehouse.id,
        name="KAMAZ",
        registration_number="C789CC78",
    )

    def shift(vehicle: Vehicle) -> DriverShift:
        """Build one deliberately conflicting in-memory source identity."""

        return DriverShift(
            id=shared_shift_id,
            warehouse_id=warehouse.id,
            driver=driver,
            driver_id=driver.id,
            vehicle=vehicle,
            vehicle_id=vehicle.id,
            date_from=date(2026, 8, 1),
            date_to=date(2026, 8, 31),
            start_time=time(8),
            end_time=time(20),
        )

    plan = RoutePlan(
        id=uuid4(),
        warehouse=warehouse,
        warehouse_id=warehouse.id,
        date=date(2026, 8, 30),
        version=1,
    )
    for sequence, vehicle in enumerate((first_vehicle, second_vehicle), start=1):
        RouteCycle(
            id=uuid4(),
            route_plan=plan,
            driver_shift=shift(vehicle),
            driver_shift_id=shared_shift_id,
            sequence=sequence,
            planned_start=datetime(2026, 8, 30, 8 + sequence, tzinfo=UTC),
            planned_finish=datetime(2026, 8, 30, 9 + sequence, tzinfo=UTC),
            total_distance_meters=10_000,
        )

    with pytest.raises(ApiError) as error:
        _build_driver_shift_plans(plan, warehouse.external_warehouse_id)

    assert error.value.code == "DUPLICATE_DRIVER_SHIFT_CONFLICT"


def test_crane_configuration_requires_the_known_explicit_vehicle_type() -> None:
    """Do not infer a crane inspection template from ambiguous free-form catalog text."""

    explicit = Vehicle(
        id=uuid4(),
        warehouse_id=uuid4(),
        name="Кран-манипулятор",
        registration_number="A123AA78",
        vehicle_type="FLATBED_CRANE",
    )
    ambiguous = Vehicle(
        id=uuid4(),
        warehouse_id=uuid4(),
        name="Грузовик",
        registration_number="B456BB78",
        vehicle_type="CRANE_READY",
    )

    assert _vehicle_configuration_type(explicit) == "TRUCK_WITH_CRANE"
    assert _vehicle_configuration_type(ambiguous) == "TRUCK"


@pytest.mark.asyncio
async def test_workspace_default_refresh_failure_has_explicit_recovery_read(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Refresh once by default, then allow a deliberate persisted-state recovery read."""

    warehouse = await make_warehouse(db_session)
    calls = 0

    async def refresh_directory(*args: object, **kwargs: object) -> list[Warehouse]:
        """Keep the already persisted warehouse as the reconciled directory result."""

        return [warehouse]

    async def refresh(*args: object, **kwargs: object) -> RwmsSyncResult:
        nonlocal calls
        calls += 1
        return RwmsSyncResult(
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

    monkeypatch.setattr(catalog_api, "refresh_warehouse_directory", refresh_directory)
    monkeypatch.setattr(catalog_api, "sync_warehouse_requests", refresh)
    directory = AsyncMock()
    directory.list_support_network.return_value = []
    with pytest.raises(ApiError) as error:
        await catalog_api.get_warehouse_workspace(
            warehouse.id,
            db_session,
            object(),
            _enabled_settings(),
            directory,
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
        directory,
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
