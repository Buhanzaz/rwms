"""Focused tests for warehouse-scoped RWMS synchronization and publication."""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from datetime import UTC, date, datetime, time, timedelta
from decimal import Decimal
from types import SimpleNamespace
from typing import Literal
from unittest.mock import AsyncMock, call
from uuid import UUID, uuid4

import httpx
import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

import app.api.catalog as catalog_api
import app.api.rwms as rwms_api
from app.config import Settings
from app.db import get_session
from app.errors import ApiError
from app.integrations.rwms import RWMS_PLANNING_SCOPE, RwmsPlanningClient
from app.integrations.rwms_sync import (
    _build_driver_shift_plans,
    _vehicle_configuration_type,
    build_assignments_command,
    prepare_plan_for_rwms_apply,
    sync_warehouse_requests,
)
from app.main import create_app
from app.models import (
    CustomerDeliveryPurpose,
    Driver,
    DriverShift,
    LogisticsRequest,
    PlanningTask,
    RouteCycle,
    RoutePlan,
    RouteStop,
    Trailer,
    UnassignedTask,
    Vehicle,
    Warehouse,
)
from app.schemas.domain import (
    RequestDateOptionInput,
    RequestDateOptionUpdate,
    RequestPlanningDetailsInput,
    RwmsAssignmentsCommand,
    RwmsCapacitySnapshotCommand,
    RwmsCapacitySnapshotResult,
    RwmsDriverShiftPlanVehicle,
    RwmsDriverShiftRouteOperation,
    RwmsIsochroneTariff,
    RwmsPlanApplyRequest,
    RwmsPlanningCapacityJob,
    RwmsPlanningCapacityShift,
    RwmsPlanningDateOption,
    RwmsPlanningFeed,
    RwmsPlanningRequest,
    RwmsPlanningUnitReservation,
    RwmsSyncFailure,
    RwmsSyncRequest,
    RwmsSyncResult,
    RwmsVehicleOperationalAssignment,
    RwmsWarehouseIdentity,
    RwmsWarehouseRefreshResult,
    RwmsWarehouseSyncResult,
)
from app.schemas.geocoding import ResolvedAddress
from app.security import WarehouseAccessLevel
from app.services import catalog
from app.services.plans import UnavailablePlannerFacade
from tests.auth import admin_access_token_verifier, admin_principal
from tests.factories import make_vehicle, make_warehouse


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
    inventory_source_by_unit: dict[UUID, UUID] | None = None,
) -> RwmsPlanningRequest:
    """Build one strict upstream request for synchronization and assignment tests."""

    resolved_units = unit_ids or [uuid4()]
    default_source = uuid4()
    source_by_unit = inventory_source_by_unit or {
        unit_id: default_source for unit_id in resolved_units
    }
    return RwmsPlanningRequest(
        order_id=order_id or uuid4(),
        order_version=7,
        source_revision="a" * 64,
        customer_delivery_purpose="RENTAL_DELIVERY",
        order_number="R-142",
        client_name="Тестовый клиент",
        client_type="LEGAL_ENTITY",
        address="Санкт-Петербург, тестовый адрес",
        latitude=latitude,
        longitude=longitude,
        quantity=len(resolved_units),
        unit_ids=resolved_units,
        unit_reservations=[
            RwmsPlanningUnitReservation(
                unit_id=unit_id,
                inventory_source_warehouse_id=source_by_unit[unit_id],
            )
            for unit_id in resolved_units
        ],
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


def _planning_feed_client(
    warehouse: Warehouse,
    requests: list[RwmsPlanningRequest],
) -> RwmsPlanningClient:
    """Return an enabled client serving one complete warehouse planning snapshot."""

    client = RwmsPlanningClient(_enabled_settings())
    client.get_planning_requests = AsyncMock(
        return_value=RwmsPlanningFeed(
            warehouse_id=warehouse.external_warehouse_id,
            time_zone=warehouse.timezone,
            generated_at=datetime(2026, 8, 28, 8, tzinfo=UTC),
            requests=requests,
        )
    )
    return client


def _vehicle_assignment_payload(
    *,
    vehicle_id: UUID,
    source_warehouse_id: UUID,
    destination_warehouse_id: UUID,
    assignment_id: UUID | None = None,
    mode: str = "TEMPORARY",
    status: str = "ACTIVE",
    travel_starts_at: datetime = datetime(2026, 9, 1, 6, tzinfo=UTC),
    effective_from: datetime = datetime(2026, 9, 1, 8, tzinfo=UTC),
    effective_until: datetime | None = datetime(2026, 9, 3, 8, tzinfo=UTC),
) -> dict[str, object]:
    """Build one canonical vehicle-assignment transport payload."""

    resolved_until = effective_until
    if mode == "PERMANENT":
        resolved_until = None
    elif mode == "TRIP_ONLY":
        resolved_until = effective_from
    return {
        "assignmentId": str(assignment_id or uuid4()),
        "version": 1,
        "transferId": str(uuid4()),
        "vehicleId": str(vehicle_id),
        "sourceWarehouseId": str(source_warehouse_id),
        "destinationWarehouseId": str(destination_warehouse_id),
        "mode": mode,
        "status": status,
        "travelStartsAt": travel_starts_at.isoformat(),
        "effectiveFrom": effective_from.isoformat(),
        "effectiveUntil": resolved_until.isoformat() if resolved_until is not None else None,
        "createdAt": (travel_starts_at - timedelta(days=1)).isoformat(),
        "updatedAt": effective_from.isoformat(),
    }


def test_planning_request_requires_complete_unique_unit_source_mapping() -> None:
    """Reject missing, incomplete, extra, or repeated concrete-cabin source facts."""

    units = [uuid4(), uuid4()]
    source = _source_request(unit_ids=units)
    valid = source.model_dump(mode="json", by_alias=True)

    missing_purpose = dict(valid)
    missing_purpose.pop("customerDeliveryPurpose")
    with pytest.raises(ValueError, match="customerDeliveryPurpose"):
        RwmsPlanningRequest.model_validate(missing_purpose)

    invalid_purpose = {**valid, "customerDeliveryPurpose": "WAREHOUSE_TRANSFER"}
    with pytest.raises(ValueError, match="customerDeliveryPurpose"):
        RwmsPlanningRequest.model_validate(invalid_purpose)

    missing_field = dict(valid)
    missing_field.pop("unitReservations")
    with pytest.raises(ValueError, match="unitReservations"):
        RwmsPlanningRequest.model_validate(missing_field)

    incomplete = dict(valid)
    incomplete["unitReservations"] = valid["unitReservations"][:1]
    with pytest.raises(ValueError, match="map exactly every unitId"):
        RwmsPlanningRequest.model_validate(incomplete)

    duplicate = dict(valid)
    duplicate["unitReservations"] = [
        valid["unitReservations"][0],
        valid["unitReservations"][0],
    ]
    with pytest.raises(ValueError, match="unitId values must be unique"):
        RwmsPlanningRequest.model_validate(duplicate)

    extra = dict(valid)
    extra["unitReservations"] = [
        *valid["unitReservations"],
        {
            "unitId": str(uuid4()),
            "inventorySourceWarehouseId": str(uuid4()),
        },
    ]
    with pytest.raises(ValueError, match="map exactly every unitId"):
        RwmsPlanningRequest.model_validate(extra)


@pytest.mark.parametrize(
    ("kind", "load_before", "load_after"),
    [("TRANSFER_LOAD", 0, 2), ("TRANSFER_UNLOAD", 2, 0)],
)
def test_driver_shift_route_operation_serializes_owner_transfer_identity(
    kind: Literal["TRANSFER_LOAD", "TRANSFER_UNLOAD"],
    load_before: int,
    load_after: int,
) -> None:
    """Accept owner-enriched transfer operations and preserve their canonical identity."""

    transfer_id = uuid4()
    operation = RwmsDriverShiftRouteOperation(
        sequence=1,
        kind=kind,
        warehouse_id=uuid4(),
        source_transfer_id=transfer_id,
        location_label="Склад назначения",
        planned_arrival=datetime(2026, 8, 30, 9, tzinfo=UTC),
        planned_departure=datetime(2026, 8, 30, 9, 30, tzinfo=UTC),
        load_before=load_before,
        load_after=load_after,
    )

    payload = operation.model_dump(mode="json", by_alias=True)

    assert payload["kind"] == kind
    assert payload["sourceTransferId"] == str(transfer_id)
    assert payload["sourceTaskId"] is None


def test_driver_shift_models_accept_legacy_additive_field_omission() -> None:
    """Keep pre-capacity vehicles and pre-transfer depot operations valid on decode."""

    vehicle = RwmsDriverShiftPlanVehicle.model_validate(
        {
            "id": str(uuid4()),
            "name": "MAN TGS",
            "registrationNumber": "A123AA78",
            "configurationType": "TRUCK",
        }
    )
    operation = RwmsDriverShiftRouteOperation.model_validate(
        {
            "sequence": 1,
            "kind": "DEPOT_LOAD",
            "warehouseId": str(uuid4()),
            "locationLabel": "Склад",
            "plannedArrival": "2026-08-30T09:00:00Z",
            "plannedDeparture": "2026-08-30T09:30:00Z",
            "loadBefore": 0,
            "loadAfter": 1,
        }
    )

    assert vehicle.cabin_capacity is None
    assert "cabin_capacity" not in vehicle.model_fields_set
    assert operation.source_transfer_id is None
    assert "source_transfer_id" not in operation.model_fields_set


async def _stored_rwms_request(
    session: AsyncSession,
    order_id: UUID,
) -> LogisticsRequest:
    """Reload one synchronized request with the historical child rows under test."""

    request = await session.scalar(
        select(LogisticsRequest)
        .where(LogisticsRequest.external_id == order_id)
        .options(
            selectinload(LogisticsRequest.date_options),
            selectinload(LogisticsRequest.tasks),
        )
    )
    assert request is not None
    return request


@pytest.mark.asyncio
async def test_rwms_publication_requires_confirmed_plan(
    db_session: AsyncSession,
) -> None:
    """A generated draft cannot cross the RWMS publication boundary."""

    warehouse = await make_warehouse(db_session)
    plan = RoutePlan(
        warehouse_id=warehouse.id,
        date=date(2026, 8, 30),
        name="Unconfirmed",
        version=1,
        status="GENERATED",
    )
    db_session.add(plan)
    await db_session.flush()

    with pytest.raises(ApiError) as rejected:
        await prepare_plan_for_rwms_apply(
            db_session,
            plan.id,
            RwmsPlanApplyRequest(expected_version=plan.version),
            planner=UnavailablePlannerFacade(),
        )

    assert rejected.value.code == "PLAN_NOT_CONFIRMED"


def test_capacity_publication_requires_rwms_sync_configuration() -> None:
    """A publisher cannot be enabled without its authenticated RWMS transport."""

    assert Settings().rwms_capacity_publish_enabled is False
    with pytest.raises(ValueError, match="requires RWMS_SYNC_ENABLED"):
        Settings(rwms_sync_enabled=False, rwms_capacity_publish_enabled=True)
    for invalid_interval in (0.5, 301):
        with pytest.raises(ValueError, match="between 1 and 300"):
            Settings(rwms_capacity_retry_interval_seconds=invalid_interval)


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
    ),
)
def test_rwms_travel_zone_rejects_invalid_value_or_soft_fixed_window(
    payload: dict[str, object],
) -> None:
    """Reject an invalid travel band or a soft option that fixes a window."""

    with pytest.raises(ValueError):
        RwmsPlanningDateOption.model_validate(payload)


def test_rwms_travel_zone_accepts_soft_date_only_option() -> None:
    """Accept informational travel-band metadata without a fixed customer window."""

    option = RwmsPlanningDateOption.model_validate(
        {
            "date": "2026-08-25",
            "priority": 1,
            "isHard": False,
            "travelZoneHours": 2,
        }
    )

    assert option.is_hard is False
    assert option.window_start is None
    assert option.window_end is None
    assert option.travel_zone_hours == 2


def test_manual_date_option_may_clear_travel_zone() -> None:
    """Keep optional CustomerApp travel-band metadata explicit and nullable."""

    created = RequestDateOptionInput(
        date=date(2026, 8, 25),
        is_hard=False,
        travel_zone_hours=2,
    )
    cleared = RequestDateOptionUpdate(expected_version=1, travel_zone_hours=None)
    assert created.window_start is None
    assert created.window_end is None
    assert created.travel_zone_hours == 2
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
async def test_vehicle_assignment_client_uses_bounded_operational_contract() -> None:
    """Decode a chain-closed fact that need not directly touch the queried warehouse."""

    historical_id, source_id, destination_id, vehicle_id = (
        uuid4(),
        uuid4(),
        uuid4(),
        uuid4(),
    )
    window_start = datetime(2026, 9, 1, 5, tzinfo=UTC)
    window_end = datetime(2026, 9, 2, 5, tzinfo=UTC)
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.host == "auth.internal":
            return httpx.Response(
                200,
                json={"access_token": "opaque-token", "expires_in": 300},
                request=request,
            )
        assert request.url.path.endswith("/vehicle-assignments")
        assert dict(request.url.params) == {
            "warehouseId": str(historical_id),
            "windowStart": window_start.isoformat(),
            "windowEnd": window_end.isoformat(),
        }
        return httpx.Response(
            200,
            json=[
                _vehicle_assignment_payload(
                    vehicle_id=vehicle_id,
                    source_warehouse_id=source_id,
                    destination_warehouse_id=destination_id,
                )
            ],
            request=request,
        )

    client = RwmsPlanningClient(_enabled_settings(), transport=httpx.MockTransport(handler))

    assignments = await client.list_vehicle_assignments(
        historical_id,
        window_start=window_start,
        window_end=window_end,
    )

    assert assignments[0].vehicle_id == vehicle_id
    assert assignments[0].destination_warehouse_id == destination_id
    assert [request.url.path for request in requests].count("/oauth2/token") == 1


@pytest.mark.asyncio
async def test_vehicle_assignment_client_rejects_duplicate_or_overlapping_facts() -> None:
    """Terminal, duplicate, and contradictory live rows fail at the HTTP boundary."""

    source_id, destination_id, vehicle_id = uuid4(), uuid4(), uuid4()
    window_start = datetime(2026, 9, 1, 5, tzinfo=UTC)
    window_end = datetime(2026, 9, 2, 5, tzinfo=UTC)
    first = _vehicle_assignment_payload(
        vehicle_id=vehicle_id,
        source_warehouse_id=source_id,
        destination_warehouse_id=destination_id,
        status="PLANNED",
    )
    overlapping = _vehicle_assignment_payload(
        vehicle_id=vehicle_id,
        source_warehouse_id=source_id,
        destination_warehouse_id=destination_id,
        status="IN_TRANSIT",
        travel_starts_at=datetime(2026, 9, 1, 7, tzinfo=UTC),
        effective_from=datetime(2026, 9, 1, 9, tzinfo=UTC),
    )
    terminal = {
        **first,
        "assignmentId": str(uuid4()),
        "status": "COMPLETED",
    }

    for payload in ([terminal], [first, first], [first, overlapping]):

        def handler(
            request: httpx.Request,
            response_payload: list[dict[str, object]] = payload,
        ) -> httpx.Response:
            if request.url.host == "auth.internal":
                return httpx.Response(
                    200,
                    json={"access_token": "opaque-token", "expires_in": 300},
                    request=request,
                )
            return httpx.Response(200, json=response_payload, request=request)

        client = RwmsPlanningClient(
            _enabled_settings(),
            transport=httpx.MockTransport(handler),
        )
        with pytest.raises(ApiError) as failure:
            await client.list_vehicle_assignments(
                destination_id,
                window_start=window_start,
                window_end=window_end,
            )
        assert failure.value.code == "RWMS_VEHICLE_ASSIGNMENT_RESPONSE_INVALID"


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
    with pytest.raises(ValueError, match="placeholder coordinates 0,0"):
        RwmsWarehouseIdentity.model_validate(
            {**base, "latitude": 0, "longitude": 0}
        )


@pytest.mark.asyncio
async def test_directory_reconciliation_disables_existing_zero_origin(
    db_session: AsyncSession,
) -> None:
    """An old 0,0 row stays in history but immediately leaves the routable directory."""

    warehouse = await make_warehouse(db_session)
    warehouse.latitude = 0
    warehouse.longitude = 0
    warehouse.routing_ready = True
    await db_session.flush()
    identity = RwmsWarehouseIdentity(
        warehouseId=warehouse.external_warehouse_id,
        warehouseVersion=warehouse.external_warehouse_version + 1,
        name=warehouse.name,
        city=warehouse.city,
        address=warehouse.address,
        latitude=0,
        longitude=0,
        timeZone=warehouse.timezone,
        representative=warehouse.representative,
        routingReady=False,
    )

    visible = await catalog.reconcile_warehouse_directory(db_session, [identity])

    assert visible == []
    assert warehouse.routing_ready is False


@pytest.mark.asyncio
async def test_directory_reconciliation_rejects_zero_geocoder_origin(
    db_session: AsyncSession,
) -> None:
    """A geocoder placeholder cannot make an address-only warehouse routable."""

    external_id = uuid4()
    identity = RwmsWarehouseIdentity(
        warehouseId=external_id,
        warehouseVersion=1,
        name="Склад без точки",
        city="Санкт-Петербург",
        address="Тестовый адрес",
        latitude=None,
        longitude=None,
        timeZone="Europe/Moscow",
        representative=True,
        routingReady=False,
    )
    resolver = AsyncMock(
        return_value=ResolvedAddress(
            address="Тестовый адрес", latitude=0, longitude=0
        )
    )

    visible = await catalog.reconcile_warehouse_directory(
        db_session, [identity], resolver
    )

    assert visible == []
    resolver.assert_awaited_once_with(identity)
    assert await db_session.scalar(
        select(Warehouse.id).where(
            Warehouse.external_warehouse_id == external_id
        )
    ) is None


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
            "priceZones",
            "restrictionZones",
        }
        assert body["priceZones"] == []
        assert body["restrictionZones"] == []
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
                "priceZoneCount": 0,
                "restrictionZoneCount": 0,
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
        "price_zone_count",
        "restriction_zone_count",
        "replayed",
        "updated_at",
    }


@pytest.mark.asyncio
async def test_sync_geocodes_address_only_request_without_rewriting_source_payload(
    db_session: AsyncSession,
) -> None:
    """Use a derived point operationally while retaining the exact authoritative feed facts."""

    warehouse = await make_warehouse(db_session)
    source = _source_request(latitude=None, longitude=None)
    resolver = AsyncMock(
        return_value=ResolvedAddress(
            address="Resolved provider address",
            latitude=55.7558,
            longitude=37.6173,
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
        _planning_feed_client(warehouse, [source]),
        resolve_address=resolver,
    )
    stored = await _stored_rwms_request(db_session, source.order_id)

    assert result.imported == 1
    assert result.failures == []
    resolver.assert_awaited_once_with(source)
    assert (stored.latitude, stored.longitude) == (55.7558, 37.6173)
    assert stored.address_label == source.address
    assert stored.external_payload["address"] == source.address
    assert stored.external_payload["latitude"] is None
    assert stored.external_payload["longitude"] is None
    assert stored.external_payload["sourceRevision"] == source.source_revision
    assert stored.customer_delivery_purpose == "RENTAL_DELIVERY"
    assert stored.external_payload["customerDeliveryPurpose"] == "RENTAL_DELIVERY"
    assert stored.external_payload["unitReservations"] == source.model_dump(
        mode="json", by_alias=True
    )["unitReservations"]


@pytest.mark.asyncio
async def test_sync_persists_soft_date_only_travel_band(
    db_session: AsyncSession,
) -> None:
    """Carry an informational RWMS travel band through import and planning details."""

    warehouse = await make_warehouse(db_session)
    planning_date = date(2026, 8, 30)
    source = _source_request(planning_date=planning_date).model_copy(
        update={
            "date_options": [
                RwmsPlanningDateOption(
                    date=planning_date,
                    priority=1,
                    is_hard=False,
                    travel_zone_hours=2,
                )
            ]
        }
    )

    result = await sync_warehouse_requests(
        db_session,
        warehouse.id,
        RwmsSyncRequest(
            warehouse_id=warehouse.external_warehouse_id,
            date_from=planning_date,
            date_to=planning_date,
        ),
        _planning_feed_client(warehouse, [source]),
    )
    stored = await _stored_rwms_request(db_session, source.order_id)
    option = stored.date_options[0]

    assert result.imported == 1
    assert option.is_hard is False
    assert option.window_start is None
    assert option.window_end is None
    assert option.travel_zone_hours == 2

    planned = await catalog.set_request_planning_details(
        db_session,
        stored.id,
        RequestPlanningDetailsInput(
            expected_version=stored.version,
            date=planning_date,
            is_hard=False,
            mandatory=True,
            trailer_access_allowed=True,
            contact_name="Тестовый клиент",
            contact_phone="+79990000000",
        ),
    )
    planned_option = planned.date_options[0]
    assert planned_option.is_hard is False
    assert planned_option.window_start is None
    assert planned_option.window_end is None
    assert planned_option.travel_zone_hours == 2


@pytest.mark.asyncio
async def test_sync_fences_customer_delivery_purpose_by_source_revision(
    db_session: AsyncSession,
) -> None:
    """A purpose change is owner data and must advance the exact planning revision."""

    warehouse = await make_warehouse(db_session)
    source = _source_request()
    command = RwmsSyncRequest(
        warehouse_id=warehouse.external_warehouse_id,
        date_from=date(2026, 8, 30),
        date_to=date(2026, 8, 30),
    )
    await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, [source]),
    )

    conflicting = source.model_copy(
        update={"customer_delivery_purpose": CustomerDeliveryPurpose.SALE_DELIVERY}
    )
    conflict = await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, [conflicting]),
    )
    stored = await _stored_rwms_request(db_session, source.order_id)

    assert conflict.updated == 0
    assert [failure.code for failure in conflict.failures] == [
        "RWMS_SOURCE_VERSION_CONFLICT"
    ]
    assert stored.customer_delivery_purpose == "RENTAL_DELIVERY"

    advanced = conflicting.model_copy(update={"source_revision": "b" * 64})
    refreshed = await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, [advanced]),
    )
    current = await _stored_rwms_request(db_session, source.order_id)

    assert refreshed.updated == 1
    assert refreshed.failures == []
    assert current.customer_delivery_purpose == "SALE_DELIVERY"
    assert current.external_payload["customerDeliveryPurpose"] == "SALE_DELIVERY"


@pytest.mark.asyncio
async def test_sync_prefers_authoritative_feed_coordinates_without_geocoding(
    db_session: AsyncSession,
) -> None:
    """A complete source point bypasses the derived-address path and remains authoritative."""

    warehouse = await make_warehouse(db_session)
    source = _source_request(latitude=59.9386, longitude=30.3141)
    resolver = AsyncMock(
        return_value=ResolvedAddress(
            address="Ignored provider address",
            latitude=55.7558,
            longitude=37.6173,
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
        _planning_feed_client(warehouse, [source]),
        resolve_address=resolver,
    )
    stored = await _stored_rwms_request(db_session, source.order_id)

    assert result.imported == 1
    resolver.assert_not_awaited()
    assert (stored.latitude, stored.longitude) == (59.9386, 30.3141)


@pytest.mark.asyncio
async def test_sync_imports_valid_sibling_and_reports_failed_address_geocoding(
    db_session: AsyncSession,
) -> None:
    """Persist a valid sibling while reporting an address-provider failure per order."""

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
    resolver = AsyncMock(
        side_effect=ApiError(
            503,
            "GEOCODING_PROVIDER_UNAVAILABLE",
            "Yandex geocoding provider is unavailable.",
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
        resolve_address=resolver,
    )

    stored = list(
        await db_session.scalars(
            select(LogisticsRequest).where(LogisticsRequest.warehouse_id == warehouse.id)
        )
    )
    assert (result.imported, result.updated, result.skipped) == (1, 0, 0)
    assert [failure.code for failure in result.failures] == [
        "GEOCODING_PROVIDER_UNAVAILABLE"
    ]
    resolver.assert_awaited_once_with(missing)
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


@pytest.mark.asyncio
async def test_complete_feed_retires_omitted_request_idempotently(
    db_session: AsyncSession,
) -> None:
    """A full empty snapshot cancels mutable demand once without deleting its source facts."""

    warehouse = await make_warehouse(db_session)
    source = _source_request(unit_ids=[uuid4(), uuid4(), uuid4()])
    command = RwmsSyncRequest(
        warehouse_id=warehouse.external_warehouse_id,
        date_from=date(2026, 8, 30),
        date_to=date(2026, 8, 30),
    )
    await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, [source]),
    )
    stored = await _stored_rwms_request(db_session, source.order_id)
    original_payload = stored.external_payload
    original_option_ids = [option.id for option in stored.date_options]
    original_task_ids = [task.id for task in stored.tasks]

    retired = await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, []),
    )
    repeated = await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, []),
    )
    current = await _stored_rwms_request(db_session, source.order_id)

    assert (retired.imported, retired.updated, retired.skipped) == (0, 1, 0)
    assert (repeated.imported, repeated.updated, repeated.skipped) == (0, 0, 0)
    assert current.status == "CANCELLED"
    assert [task.status for task in current.tasks] == ["CANCELLED", "CANCELLED"]
    assert current.external_payload == original_payload
    assert [option.id for option in current.date_options] == original_option_ids
    assert [task.id for task in current.tasks] == original_task_ids


@pytest.mark.asyncio
async def test_present_invalid_feed_row_is_not_retired(
    db_session: AsyncSession,
) -> None:
    """Presence uses source identity even when the row's address cannot be geocoded."""

    warehouse = await make_warehouse(db_session)
    source = _source_request()
    command = RwmsSyncRequest(
        warehouse_id=warehouse.external_warehouse_id,
        date_from=date(2026, 8, 30),
        date_to=date(2026, 8, 30),
    )
    await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, [source]),
    )
    invalid = _source_request(
        order_id=source.order_id,
        unit_ids=source.unit_ids,
        latitude=None,
        longitude=None,
    )

    result = await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, [invalid]),
        resolve_address=AsyncMock(
            side_effect=ApiError(404, "ADDRESS_NOT_FOUND", "No matching address")
        ),
    )
    current = await _stored_rwms_request(db_session, source.order_id)

    assert result.updated == 0
    assert [failure.code for failure in result.failures] == ["ADDRESS_NOT_FOUND"]
    assert current.status == "READY"
    assert [task.status for task in current.tasks] == ["READY"]


@pytest.mark.asyncio
async def test_omission_outside_requested_date_range_is_not_retired(
    db_session: AsyncSession,
) -> None:
    """An empty snapshot is authoritative only for its inclusive requested date range."""

    warehouse = await make_warehouse(db_session)
    source = _source_request(planning_date=date(2026, 8, 31))
    await sync_warehouse_requests(
        db_session,
        warehouse.id,
        RwmsSyncRequest(
            warehouse_id=warehouse.external_warehouse_id,
            date_from=date(2026, 8, 31),
            date_to=date(2026, 8, 31),
        ),
        _planning_feed_client(warehouse, [source]),
    )

    result = await sync_warehouse_requests(
        db_session,
        warehouse.id,
        RwmsSyncRequest(
            warehouse_id=warehouse.external_warehouse_id,
            date_from=date(2026, 8, 30),
            date_to=date(2026, 8, 30),
        ),
        _planning_feed_client(warehouse, []),
    )
    current = await _stored_rwms_request(db_session, source.order_id)

    assert result.updated == 0
    assert current.status == "READY"


@pytest.mark.asyncio
@pytest.mark.parametrize("immutable_status", ["PLANNED", "IN_PROGRESS", "COMPLETED"])
async def test_omission_does_not_mutate_non_plannable_request_lifecycle(
    db_session: AsyncSession,
    immutable_status: str,
) -> None:
    """A feed omission cannot roll back requests that already crossed the planning boundary."""

    warehouse = await make_warehouse(db_session)
    source = _source_request()
    command = RwmsSyncRequest(
        warehouse_id=warehouse.external_warehouse_id,
        date_from=date(2026, 8, 30),
        date_to=date(2026, 8, 30),
    )
    await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, [source]),
    )
    request = await _stored_rwms_request(db_session, source.order_id)
    request.status = immutable_status
    for task in request.tasks:
        task.status = immutable_status
    await db_session.flush()

    result = await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, []),
    )
    current = await _stored_rwms_request(db_session, source.order_id)

    assert result.updated == 0
    assert current.status == immutable_status
    assert [task.status for task in current.tasks] == [immutable_status]


@pytest.mark.asyncio
async def test_reappearing_omission_cancelled_request_is_restored_without_duplicate(
    db_session: AsyncSession,
) -> None:
    """The same authoritative identity restores its retained request and task rows to READY."""

    warehouse = await make_warehouse(db_session)
    source = _source_request()
    command = RwmsSyncRequest(
        warehouse_id=warehouse.external_warehouse_id,
        date_from=date(2026, 8, 30),
        date_to=date(2026, 8, 30),
    )
    await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, [source]),
    )
    original = await _stored_rwms_request(db_session, source.order_id)
    request_id = original.id
    task_ids = [task.id for task in original.tasks]
    await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, []),
    )

    restored = await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, [source]),
    )
    repeated = await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, [source]),
    )
    current = await _stored_rwms_request(db_session, source.order_id)
    matching_ids = list(
        await db_session.scalars(
            select(LogisticsRequest.id).where(
                LogisticsRequest.warehouse_id == warehouse.id,
                LogisticsRequest.external_id == source.order_id,
            )
        )
    )

    assert restored.updated == 1
    assert repeated.skipped == 1
    assert matching_ids == [request_id]
    assert current.status == "READY"
    assert [task.status for task in current.tasks] == ["READY"]
    assert [task.id for task in current.tasks] == task_ids


@pytest.mark.asyncio
async def test_omission_archives_mutable_plan_but_preserves_history(
    db_session: AsyncSession,
) -> None:
    """Retirement archives only the active mutable head and retains its request references."""

    warehouse = await make_warehouse(db_session)
    source = _source_request()
    command = RwmsSyncRequest(
        warehouse_id=warehouse.external_warehouse_id,
        date_from=date(2026, 8, 30),
        date_to=date(2026, 8, 30),
    )
    await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, [source]),
    )
    request = await _stored_rwms_request(db_session, source.order_id)
    task = request.tasks[0]
    original_task_id = task.id
    plan = RoutePlan(
        warehouse_id=warehouse.id,
        date=date(2026, 8, 30),
        name="Mutable head",
        status="GENERATED",
    )
    db_session.add(plan)
    await db_session.flush()
    history = UnassignedTask(
        route_plan_id=plan.id,
        task_id=task.id,
        reason_codes=["NO_SHIFT_CAPACITY"],
        descriptions_ru=["Нет доступной смены"],
    )
    db_session.add(history)
    await db_session.flush()

    result = await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, []),
    )
    await db_session.refresh(plan)
    current = await _stored_rwms_request(db_session, source.order_id)

    assert result.updated == 1
    assert plan.status == "ARCHIVED"
    assert await db_session.get(UnassignedTask, history.id) is history
    assert current.id == request.id
    assert [task.id for task in current.tasks] == [original_task_id]
    assert [option.date for option in current.date_options] == [date(2026, 8, 30)]


@pytest.mark.asyncio
async def test_confirmed_plan_fences_omission_retirement(
    db_session: AsyncSession,
) -> None:
    """A confirmed historical owner prevents feed omission from mutating request lifecycle."""

    warehouse = await make_warehouse(db_session)
    source = _source_request()
    command = RwmsSyncRequest(
        warehouse_id=warehouse.external_warehouse_id,
        date_from=date(2026, 8, 30),
        date_to=date(2026, 8, 30),
    )
    await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, [source]),
    )
    request = await _stored_rwms_request(db_session, source.order_id)
    plan = RoutePlan(
        warehouse_id=warehouse.id,
        date=date(2026, 8, 30),
        name="Confirmed plan",
        status="CONFIRMED",
    )
    db_session.add(plan)
    await db_session.flush()
    db_session.add(
        UnassignedTask(
            route_plan_id=plan.id,
            task_id=request.tasks[0].id,
            reason_codes=["CONFIRMED_EXCEPTION"],
            descriptions_ru=["Зафиксировано диспетчером"],
        )
    )
    await db_session.flush()

    result = await sync_warehouse_requests(
        db_session,
        warehouse.id,
        command,
        _planning_feed_client(warehouse, []),
    )
    current = await _stored_rwms_request(db_session, source.order_id)

    assert result.updated == 0
    assert current.status == "READY"
    assert [task.status for task in current.tasks] == ["READY"]
    assert plan.status == "CONFIRMED"


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
        customer_delivery_purpose=source.customer_delivery_purpose,
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

    unsupported = source.model_copy(
        update={"customer_delivery_purpose": CustomerDeliveryPurpose.CUSTOMER_RELOCATION}
    )
    request.external_payload = unsupported.model_dump(mode="json", by_alias=True)
    request.customer_delivery_purpose = unsupported.customer_delivery_purpose
    with pytest.raises(ApiError) as error:
        build_assignments_command(plan)
    assert error.value.code == "RWMS_DELIVERY_PURPOSE_UNSUPPORTED"


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
        customer_delivery_purpose=source.customer_delivery_purpose,
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
        capacity=2,
        can_use_trailer=False,
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
    assert shift_plan.vehicle.cabin_capacity == 1
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
    assert serialized_shift["vehicle"]["cabinCapacity"] == 1
    assert "routeDistanceKm" not in serialized_shift
    assert [operation["kind"] for operation in serialized_shift["operations"]] == ["DELIVERY"]
    assert all(
        operation["sourceTransferId"] is None for operation in serialized_shift["operations"]
    )
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
        capacity=1,
    )
    second_vehicle = Vehicle(
        id=uuid4(),
        warehouse_id=warehouse.id,
        name="KAMAZ",
        registration_number="C789CC78",
        capacity=1,
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
async def test_workspace_repeated_reads_never_synchronize_rwms(
    db_session: AsyncSession,
) -> None:
    """The browser projection stays read-only even while RWMS integration is enabled."""

    warehouse = await make_warehouse(db_session)
    directory = AsyncMock()
    directory.list_support_network.return_value = []
    directory.list_vehicle_assignments.return_value = []
    geocoder = SimpleNamespace(
        forward=AsyncMock()
    )
    for _ in range(2):
        workspace = await catalog_api.get_warehouse_workspace(
            warehouse.id,
            db_session,
            _enabled_settings(),
            directory,
            admin_principal(),
        )
        assert workspace.warehouse.id == warehouse.id
    directory.list_warehouses.assert_not_awaited()
    directory.get_planning_requests.assert_not_awaited()
    geocoder.forward.assert_not_awaited()


@pytest.mark.asyncio
async def test_workspace_overlays_active_vehicle_at_destination_without_rewriting_home(
    db_session: AsyncSession,
) -> None:
    """The dated projection moves one vehicle response while its persisted owner stays source."""

    source = await make_warehouse(db_session, name="Source")
    destination = await make_warehouse(db_session, name="Destination")
    vehicle = await make_vehicle(db_session, source)
    fact = RwmsVehicleOperationalAssignment.model_validate(
        _vehicle_assignment_payload(
            vehicle_id=vehicle.id,
            source_warehouse_id=source.external_warehouse_id,
            destination_warehouse_id=destination.external_warehouse_id,
            status="ACTIVE",
            travel_starts_at=datetime(2026, 9, 1, 2, tzinfo=UTC),
            effective_from=datetime(2026, 9, 1, 4, tzinfo=UTC),
        )
    )
    directory = AsyncMock()
    directory.list_support_network.return_value = []
    directory.list_vehicle_assignments.return_value = [fact]

    destination_workspace = await catalog_api.get_warehouse_workspace(
        destination.id,
        db_session,
        _enabled_settings(),
        directory,
        admin_principal(),
        planning_date=date(2026, 9, 1),
    )
    source_workspace = await catalog_api.get_warehouse_workspace(
        source.id,
        db_session,
        _enabled_settings(),
        directory,
        admin_principal(),
        planning_date=date(2026, 9, 1),
    )

    assert [(item.id, item.warehouse_id) for item in destination_workspace.vehicles] == [
        (vehicle.id, destination.id)
    ]
    assert source_workspace.vehicles == []
    assert vehicle.warehouse_id == source.id


@pytest.mark.asyncio
async def test_sync_endpoint_passes_geocoder_after_warehouse_authorization(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Keep both warehouse fences and pass the existing adapter into order synchronization."""

    local_warehouse_id = uuid4()
    external_warehouse_id = uuid4()
    session = AsyncMock(spec=AsyncSession)
    principal = admin_principal()
    authorize = AsyncMock(
        return_value=SimpleNamespace(external_warehouse_id=external_warehouse_id)
    )
    synchronize = AsyncMock(
        return_value=RwmsSyncResult(imported=1, updated=0, skipped=0)
    )
    monkeypatch.setattr(rwms_api, "require_local_warehouse_access", authorize)
    monkeypatch.setattr(rwms_api, "sync_warehouse_requests", synchronize)
    geocoder = SimpleNamespace(
        forward=AsyncMock(
            return_value=ResolvedAddress(
                address="Resolved address",
                latitude=55.7558,
                longitude=37.6173,
            )
        )
    )
    payload = RwmsSyncRequest(
        warehouse_id=external_warehouse_id,
        date_from=date(2026, 8, 30),
        date_to=date(2026, 8, 30),
    )

    result = await rwms_api.sync_rwms_requests(
        local_warehouse_id,
        payload,
        session,
        object(),
        object(),
        geocoder,
        principal,
    )

    authorize.assert_awaited_once_with(
        session,
        principal,
        local_warehouse_id,
        WarehouseAccessLevel.EDIT,
    )
    synchronize.assert_awaited_once()
    resolver = synchronize.await_args.kwargs["resolve_address"]
    address_only = _source_request(latitude=None, longitude=None)
    await resolver(address_only)
    geocoder.forward.assert_awaited_once_with(address_only.address)
    assert result.imported == 1


@pytest.mark.asyncio
async def test_refresh_endpoint_commits_valid_siblings_before_incomplete_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Expose partial refresh failures while keeping successfully imported siblings durable."""

    warehouse_id = uuid4()
    session = AsyncMock(spec=AsyncSession)
    warehouse_resolvers: list[object] = []
    request_resolvers: list[object] = []

    async def refresh(*args: object, **kwargs: object) -> RwmsWarehouseRefreshResult:
        warehouse_resolvers.append(kwargs["resolve_warehouse_address"])
        request_resolvers.append(kwargs["resolve_request_address"])
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

    async def authorize(*args: object, **kwargs: object) -> object:
        """Bypass catalogue lookup while retaining this test's failure-orchestration scope."""

        return SimpleNamespace(id=warehouse_id)

    async def authorize_group(*args: object, **kwargs: object) -> None:
        """Accept the one synthetic group already covered by the admin principal."""

    async def planning_group(*args: object, **kwargs: object) -> object:
        """Return the single authorized synthetic warehouse."""

        member = SimpleNamespace(id=warehouse_id)
        return SimpleNamespace(root=member, members=(member,))

    monkeypatch.setattr(rwms_api, "require_local_warehouse_access", authorize)
    monkeypatch.setattr(rwms_api, "require_local_warehouse_set_access", authorize_group)
    monkeypatch.setattr(rwms_api, "resolve_planning_warehouse_group", planning_group)
    geocoder = SimpleNamespace(
        forward=AsyncMock(
            return_value=ResolvedAddress(
                address="Resolved address",
                latitude=55.7558,
                longitude=37.6173,
            )
        )
    )
    with pytest.raises(ApiError) as error:
        await rwms_api.refresh_rwms_requests(
            warehouse_id,
            session,
            object(),
            object(),
            geocoder,
            admin_principal(),
        )
    assert error.value.code == "RWMS_WORKSPACE_SYNC_INCOMPLETE"
    session.commit.assert_awaited_once_with()
    identity = RwmsWarehouseIdentity(
        warehouseId=warehouse_id,
        warehouseVersion=1,
        name="Test warehouse",
        city="Москва",
        address="Тверская, 1",
        latitude=None,
        longitude=None,
        timeZone="Europe/Moscow",
        representative=False,
        routingReady=False,
    )
    address_only = _source_request(latitude=None, longitude=None)
    await warehouse_resolvers[0](identity)  # type: ignore[operator]
    await request_resolvers[0](address_only)  # type: ignore[operator]
    assert geocoder.forward.await_args_list == [
        call("Москва, Тверская, 1"),
        call(address_only.address),
    ]


@pytest.mark.asyncio
async def test_disabled_endpoints_fail_after_authorized_warehouse_lookup(
    db_session: AsyncSession,
) -> None:
    """Never fabricate synchronization or publication for an authorized warehouse."""

    warehouse = await make_warehouse(db_session)
    application = create_app(access_token_verifier=admin_access_token_verifier())

    async def session_override() -> AsyncIterator[AsyncSession]:
        """Share the rollback-isolated integration transaction."""

        yield db_session

    application.dependency_overrides[get_session] = session_override

    transport = httpx.ASGITransport(app=application)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        sync = await client.post(
            f"/api/warehouses/{warehouse.id}/rwms/sync",
            json={
                "warehouse_id": str(warehouse.external_warehouse_id),
                "date_from": "2026-08-25",
                "date_to": "2026-08-26",
            },
        )
        capacity = await client.post(
            f"/api/warehouses/{warehouse.id}/rwms/capacity"
        )
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
