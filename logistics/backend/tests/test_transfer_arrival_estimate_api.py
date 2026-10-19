"""Focused HTTP tests for side-effect-free interwarehouse arrival estimates."""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import datetime
from types import SimpleNamespace
from typing import Any
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient

from app.api import routing as routing_api
from app.config import Settings, get_settings
from app.db import get_session
from app.main import create_app
from app.routing import GeoPoint
from app.slot_planning.models import (
    PlanningReason,
    RoadMetric,
    TravelTimeUnavailable,
    VehicleLegState,
)
from tests.auth import admin_access_token_verifier


class SequentialScalarSession:
    """Minimal async-session double returning prepared scalar query results in order."""

    def __init__(self, *results: object) -> None:
        self._results = list(results)
        self.scalar_calls = 0

    async def scalar(self, statement: object) -> object:
        """Return the next prepared result while retaining the number of reads."""

        del statement
        self.scalar_calls += 1
        return self._results.pop(0)


class RecordingTravelTimeProvider:
    """Exact-route adapter double recording load state, points, departure, and closure."""

    def __init__(
        self,
        metric: RoadMetric | None = None,
        failure: TravelTimeUnavailable | None = None,
    ) -> None:
        self.metric = metric or RoadMetric(travel_seconds=14_400, distance_meters=188_000)
        self.failure = failure
        self.calls: list[tuple[GeoPoint, GeoPoint, datetime, VehicleLegState]] = []
        self.closed = False

    async def travel_time(
        self,
        origin: GeoPoint,
        destination: GeoPoint,
        departure_at: datetime,
        vehicle_state: VehicleLegState,
    ) -> RoadMetric:
        """Return one configured exact metric or the configured stable failure."""

        self.calls.append((origin, destination, departure_at, vehicle_state))
        if self.failure is not None:
            raise self.failure
        return self.metric

    async def aclose(self) -> None:
        """Record deterministic provider cleanup after success or failure."""

        self.closed = True


def _warehouse(
    *,
    external_id: UUID | None = None,
    latitude: float = 59.93,
    longitude: float = 30.32,
    routing_ready: bool = True,
) -> SimpleNamespace:
    """Build the local projection of one canonical RWMS warehouse identity."""

    return SimpleNamespace(
        id=uuid4(),
        external_warehouse_id=external_id or uuid4(),
        latitude=latitude,
        longitude=longitude,
        routing_ready=routing_ready,
        settings={},
        seed=17,
    )


def _vehicle(
    source: SimpleNamespace,
    *,
    active: bool = True,
    capacity: int = 2,
    trailer: bool = True,
) -> SimpleNamespace:
    """Build a source-owned vehicle with an optional active default trailer."""

    return SimpleNamespace(
        id=uuid4(),
        warehouse_id=source.id,
        active=active,
        capacity=capacity,
        can_use_trailer=True,
        default_trailer=(
            SimpleNamespace(id=uuid4(), active=True, warehouse_id=source.id)
            if trailer
            else None
        ),
        load_profiles=[],
    )


def _payload(
    source: SimpleNamespace,
    destination: SimpleNamespace,
    vehicle: SimpleNamespace,
    *,
    cabin_count: int = 2,
) -> dict[str, Any]:
    """Build one valid JSON request using stable external warehouse identities."""

    return {
        "source_warehouse_id": str(source.external_warehouse_id),
        "destination_warehouse_id": str(destination.external_warehouse_id),
        "planned_departure_at": "2026-08-30T08:30:00+03:00",
        "vehicle_id": str(vehicle.id),
        "cabin_count": cabin_count,
    }


def _client(session: SequentialScalarSession, settings: Settings) -> TestClient:
    """Create an HTTP client whose reads stay inside the prepared session double."""

    application = create_app(access_token_verifier=admin_access_token_verifier())

    async def session_override() -> AsyncIterator[SequentialScalarSession]:
        """Yield the read-only sequential test session."""

        yield session

    application.dependency_overrides[get_session] = session_override
    application.dependency_overrides[get_settings] = lambda: settings
    return TestClient(application)


def test_two_cabin_estimate_uses_default_trailer_and_exact_truck_metric(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Two cabins attach the source vehicle's trailer and derive arrival from road time."""

    source = _warehouse(latitude=59.93, longitude=30.32)
    destination = _warehouse(latitude=58.52, longitude=31.28)
    vehicle = _vehicle(source)
    session = SequentialScalarSession(source, destination, vehicle)
    provider = RecordingTravelTimeProvider()
    equipment = object()
    captured: dict[str, object] = {}

    def snapshot(candidate: object, warehouse_settings: object) -> object:
        """Capture the selected vehicle and source settings at the shared snapshot boundary."""

        captured["snapshot"] = (candidate, warehouse_settings)
        return equipment

    def provider_factory(
        settings: object,
        warehouse: object,
        equipment_by_vehicle: object,
    ) -> RecordingTravelTimeProvider:
        """Capture the existing exact-provider construction inputs."""

        captured["provider"] = (settings, warehouse, equipment_by_vehicle)
        return provider

    monkeypatch.setattr(routing_api, "vehicle_equipment_snapshot", snapshot)
    monkeypatch.setattr(routing_api, "truck_travel_time_provider", provider_factory)
    settings = Settings(
        routing_provider="valhalla",
        valhalla_enabled=True,
        osm_data_version="northwest-2026-08-30",
    )
    client = _client(session, settings)
    try:
        response = client.post(
            "/api/routing/transfer-arrival-estimate",
            json=_payload(source, destination, vehicle),
        )
    finally:
        client.close()

    assert response.status_code == 200, response.text
    assert response.json() == {
        "departure_at": "2026-08-30T08:30:00+03:00",
        "estimated_arrival_at": "2026-08-30T12:30:00+03:00",
        "travel_seconds": 14_400,
        "distance_meters": 188_000,
        "vehicle_id": str(vehicle.id),
        "cabin_count": 2,
        "trailer_attached": True,
        "routing_provider": "valhalla",
        "osm_data_version": "northwest-2026-08-30",
    }
    assert captured["snapshot"] == (vehicle, source.settings)
    assert captured["provider"] == (
        settings,
        source,
        {str(vehicle.id): equipment},
    )
    assert provider.calls == [
        (
            GeoPoint(30.32, 59.93, is_city=True),
            GeoPoint(31.28, 58.52, is_city=True),
            datetime.fromisoformat("2026-08-30T08:30:00+03:00"),
            VehicleLegState(str(vehicle.id), True, 2, 2),
        )
    ]
    assert provider.closed is True
    assert session.scalar_calls == 3


def test_empty_transfer_leg_routes_without_attaching_a_trailer(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A resource-only transfer accepts zero cabins and preserves an empty load state."""

    source = _warehouse()
    destination = _warehouse()
    vehicle = _vehicle(source, capacity=1, trailer=False)
    provider = RecordingTravelTimeProvider(
        RoadMetric(travel_seconds=600, distance_meters=8_000)
    )
    monkeypatch.setattr(routing_api, "vehicle_equipment_snapshot", lambda *_: object())
    monkeypatch.setattr(routing_api, "truck_travel_time_provider", lambda *_: provider)
    client = _client(
        SequentialScalarSession(source, destination, vehicle),
        Settings(routing_provider="mock"),
    )
    try:
        response = client.post(
            "/api/routing/transfer-arrival-estimate",
            json=_payload(source, destination, vehicle, cabin_count=0),
        )
    finally:
        client.close()

    assert response.status_code == 200, response.text
    assert response.json()["trailer_attached"] is False
    assert response.json()["cabin_count"] == 0
    assert response.json()["osm_data_version"] is None
    assert provider.calls[0][3] == VehicleLegState(str(vehicle.id), False, 0, 0)


@pytest.mark.parametrize(
    ("source_result", "destination_result", "expected_code"),
    (
        (None, "present", "SOURCE_WAREHOUSE_NOT_FOUND"),
        ("source", None, "DESTINATION_WAREHOUSE_NOT_FOUND"),
        ("unroutable", "present", "WAREHOUSE_ROUTING_COORDINATES_MISSING"),
    ),
)
def test_warehouse_identity_and_routing_failures_are_explicit(
    source_result: str | None,
    destination_result: str | None,
    expected_code: str,
) -> None:
    """Missing canonical identities and absent routing coordinates never yield estimates."""

    source = _warehouse(routing_ready=source_result != "unroutable")
    destination = _warehouse()
    vehicle = _vehicle(source)
    first = None if source_result is None else source
    results: list[object] = [first]
    if source_result is not None:
        results.append(None if destination_result is None else destination)
    client = _client(SequentialScalarSession(*results), Settings())
    try:
        response = client.post(
            "/api/routing/transfer-arrival-estimate",
            json=_payload(source, destination, vehicle, cabin_count=0),
        )
    finally:
        client.close()

    assert response.status_code in {404, 422}
    assert response.json()["code"] == expected_code


@pytest.mark.parametrize(
    ("mutate", "expected_code"),
    (
        ("wrong_warehouse", "VEHICLE_SOURCE_WAREHOUSE_MISMATCH"),
        ("inactive", "VEHICLE_INACTIVE"),
        ("capacity_one", "VEHICLE_CAPACITY_EXCEEDED"),
        ("no_trailer", "TRAILER_REQUIRED"),
    ),
)
def test_vehicle_source_activity_and_two_cabin_capacity_are_enforced(
    mutate: str,
    expected_code: str,
) -> None:
    """Only an active source-owned vehicle with a usable trailer can carry two cabins."""

    source = _warehouse()
    destination = _warehouse()
    vehicle = _vehicle(source)
    if mutate == "wrong_warehouse":
        vehicle.warehouse_id = uuid4()
    elif mutate == "inactive":
        vehicle.active = False
    elif mutate == "capacity_one":
        vehicle.capacity = 1
    elif mutate == "no_trailer":
        vehicle.default_trailer = None
    client = _client(
        SequentialScalarSession(source, destination, vehicle),
        Settings(),
    )
    try:
        response = client.post(
            "/api/routing/transfer-arrival-estimate",
            json=_payload(source, destination, vehicle),
        )
    finally:
        client.close()

    assert response.status_code == 422
    assert response.json()["code"] == expected_code


def test_same_warehouse_and_invalid_request_shape_fail_before_database_read() -> None:
    """The API rejects a loop transfer, naive departure, and an unsupported cabin count."""

    source = _warehouse()
    vehicle = _vehicle(source)
    session = SequentialScalarSession()
    client = _client(session, Settings())
    loop_payload = _payload(source, source, vehicle, cabin_count=0)
    try:
        loop = client.post("/api/routing/transfer-arrival-estimate", json=loop_payload)
        naive = client.post(
            "/api/routing/transfer-arrival-estimate",
            json={
                **loop_payload,
                "destination_warehouse_id": str(uuid4()),
                "planned_departure_at": "2026-08-30T08:30:00",
            },
        )
        too_many = client.post(
            "/api/routing/transfer-arrival-estimate",
            json={**loop_payload, "destination_warehouse_id": str(uuid4()), "cabin_count": 3},
        )
    finally:
        client.close()

    assert loop.status_code == 422
    assert loop.json()["code"] == "TRANSFER_WAREHOUSES_MUST_DIFFER"
    assert naive.status_code == 422
    assert too_many.status_code == 422
    assert session.scalar_calls == 0


def test_exact_route_failure_is_stable_and_closes_provider(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """An unavailable exact truck leg returns a reason code without a generic fallback."""

    source = _warehouse()
    destination = _warehouse()
    vehicle = _vehicle(source, trailer=False)
    provider = RecordingTravelTimeProvider(
        failure=TravelTimeUnavailable(PlanningReason.TRUCK_ROUTE_NOT_FOUND)
    )
    monkeypatch.setattr(routing_api, "vehicle_equipment_snapshot", lambda *_: object())
    monkeypatch.setattr(routing_api, "truck_travel_time_provider", lambda *_: provider)
    client = _client(
        SequentialScalarSession(source, destination, vehicle),
        Settings(routing_provider="mock"),
    )
    try:
        response = client.post(
            "/api/routing/transfer-arrival-estimate",
            json=_payload(source, destination, vehicle, cabin_count=1),
        )
    finally:
        client.close()

    assert response.status_code == 422
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["code"] == "TRUCK_ROUTE_NOT_FOUND"
    assert provider.closed is True
