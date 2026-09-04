"""HTTP integration tests for canonical warehouse projection and tariffs."""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import date
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_capacity_rwms_client
from app.api.geocoding import get_yandex_geocoding_client
from app.db import get_session
from app.errors import ApiError
from app.main import create_app
from app.models import RoutePlan
from app.schemas.domain import RwmsWarehouseIdentity
from app.schemas.geocoding import ResolvedAddress
from tests.auth import admin_access_token_verifier
from tests.factories import make_warehouse

pytestmark = pytest.mark.integration


def _application(
    session: AsyncSession,
    directory: AsyncMock,
    geocoder: AsyncMock,
) -> FastAPI:
    """Bind external boundaries and persistence to deterministic test doubles."""

    application = create_app(access_token_verifier=admin_access_token_verifier())

    async def session_override() -> AsyncIterator[AsyncSession]:
        """Share the rollback-isolated test transaction."""

        yield session

    application.dependency_overrides[get_session] = session_override
    application.dependency_overrides[get_capacity_rwms_client] = lambda: directory
    application.dependency_overrides[get_yandex_geocoding_client] = lambda: geocoder
    return application


@pytest.mark.asyncio
async def test_polled_catalog_reads_preserve_persisted_binding_and_plan(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Available, catalog, and workspace GETs do not reconcile or invalidate local state."""

    external_id = uuid4()
    identity = RwmsWarehouseIdentity(
        warehouseId=external_id,
        warehouseVersion=1,
        name="Склад СПб",
        city="Санкт-Петербург",
        address=None,
        latitude=59.93,
        longitude=30.32,
        timeZone="Europe/Moscow",
        representative=True,
        routingReady=True,
    )
    directory = AsyncMock()
    directory.list_warehouses.return_value = [identity]
    geocoder = AsyncMock()
    local = await make_warehouse(db_session, name=identity.name)
    local.external_warehouse_id = external_id
    local.external_warehouse_version = identity.warehouse_version
    local.address = identity.address
    local.representative = True
    route_plan = RoutePlan(
        warehouse_id=local.id,
        date=date(2026, 9, 1),
        name="Стабильный расчёт",
    )
    db_session.add(route_plan)
    await db_session.flush()
    route_plan_id = route_plan.id
    flush = AsyncMock(side_effect=AssertionError("polled GET must not flush"))
    commit = AsyncMock(side_effect=AssertionError("polled GET must not commit"))
    monkeypatch.setattr(db_session, "flush", flush)
    monkeypatch.setattr(db_session, "commit", commit)
    app = _application(db_session, directory, geocoder)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        available = await client.get("/api/warehouses/available")
        assert available.status_code == 200
        assert available.json()[0]["warehouse_id"] == str(external_id)
        assert available.json()[0]["routing_ready"] is True
        assert available.json()[0]["routing_unavailable_reason"] is None
        assert available.json()[0]["local_warehouse_id"] == str(local.id)
        listed = await client.get("/api/warehouses")
        assert listed.status_code == 200, listed.text
        body = listed.json()[0]
        assert body["name"] == identity.name
        assert body["address"] == identity.address
        assert body["latitude"] == local.latitude
        assert body["external_warehouse_id"] == str(external_id)
        assert body["external_warehouse_version"] == 1
        assert body["representative"] is True
        assert body["isochrone_tariffs"] == [
            {"travel_minutes": 60, "price_rubles": 10_000},
            {"travel_minutes": 120, "price_rubles": 15_000},
            {"travel_minutes": 180, "price_rubles": 20_000},
            {"travel_minutes": 240, "price_rubles": 25_000},
        ]

        directory.list_warehouses.return_value = [
            identity.model_copy(
                update={
                    "warehouse_version": 2,
                    "latitude": 58.52,
                    "longitude": 31.27,
                }
            )
        ]
        refreshed = await client.get("/api/warehouses")
        assert refreshed.status_code == 200, refreshed.text
        assert refreshed.json()[0]["external_warehouse_version"] == 1
        assert refreshed.json()[0]["latitude"] == local.latitude
        assert refreshed.json()[0]["longitude"] == local.longitude
        assert (
            await db_session.scalar(select(RoutePlan.id).where(RoutePlan.id == route_plan_id))
            == route_plan_id
        )

        workspace = await client.get(
            f"/api/warehouses/{body['id']}/workspace",
            params={"planning_date": "2026-09-01"},
        )
        assert workspace.status_code == 200, workspace.text
        workspace_body = workspace.json()
        assert workspace_body["warehouse"]["id"] == body["id"]
        assert "zones" not in workspace_body
        assert workspace_body["drivers"] == []
        assert workspace_body["requests"] == []

    directory.list_warehouses.assert_awaited_once()
    geocoder.forward.assert_not_awaited()
    flush.assert_not_awaited()
    commit.assert_not_awaited()


@pytest.mark.asyncio
async def test_http_warehouse_binding_keeps_coordinate_less_identity_available(
    db_session: AsyncSession,
) -> None:
    """A directory warehouse without coordinates stays visible but is not routed or projected."""

    external_id = uuid4()
    identity = RwmsWarehouseIdentity(
        warehouseId=external_id,
        warehouseVersion=3,
        name="Far depot",
        city="Москва",
        address=None,
        latitude=None,
        longitude=None,
        timeZone="Europe/Moscow",
        representative=True,
        routingReady=False,
    )
    directory = AsyncMock()
    directory.list_warehouses.return_value = [identity]
    geocoder = AsyncMock()
    app = _application(db_session, directory, geocoder)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        available = await client.get("/api/warehouses/available")
        warehouses = await client.get("/api/warehouses")
    assert available.status_code == 200, available.text
    assert available.json()[0]["routing_ready"] is False
    assert available.json()[0]["local_warehouse_id"] is None
    assert available.json()[0]["routing_unavailable_reason"] == (
        "Не заданы координаты для использования склада в логистике"  # noqa: RUF001
    )
    assert warehouses.status_code == 200, warehouses.text
    assert warehouses.json() == []
    geocoder.forward.assert_not_awaited()


@pytest.mark.asyncio
async def test_available_address_only_warehouse_stays_unbound_until_server_ingestion(
    db_session: AsyncSession,
) -> None:
    """Polling reports authoritative facts without geocoding or persisting a binding."""

    external_id = uuid4()
    identity = RwmsWarehouseIdentity(
        warehouseId=external_id,
        warehouseVersion=4,
        name="Address-only depot",
        city="Великий Новгород",
        address="Большая Санкт-Петербургская улица, 82",
        latitude=None,
        longitude=None,
        timeZone="Europe/Moscow",
        representative=True,
        routingReady=False,
    )
    directory = AsyncMock()
    directory.list_warehouses.return_value = [identity]
    geocoder = AsyncMock()
    geocoder.forward.return_value = ResolvedAddress(
        address="Великий Новгород, Большая Санкт-Петербургская улица, 82",
        latitude=58.5544,
        longitude=31.2698,
    )
    app = _application(db_session, directory, geocoder)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        available = await client.get("/api/warehouses/available")
        response = await client.get("/api/warehouses")

    assert available.status_code == 200, available.text
    available_body = available.json()[0]
    assert available_body["routing_ready"] is False
    assert available_body["routing_unavailable_reason"] == (
        "Не заданы координаты для использования склада в логистике"  # noqa: RUF001
    )
    assert available_body["latitude"] is None
    assert available_body["longitude"] is None
    assert available_body["local_warehouse_id"] is None
    assert response.status_code == 200, response.text
    assert response.json() == []
    geocoder.forward.assert_not_awaited()

    directory.list_warehouses.return_value = [identity.model_copy(update={"warehouse_version": 5})]
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        listed = await client.get("/api/warehouses")

    assert listed.status_code == 200, listed.text
    assert listed.json() == []
    geocoder.forward.assert_not_awaited()


@pytest.mark.asyncio
async def test_available_poll_does_not_materialize_coordinate_ready_candidates(
    db_session: AsyncSession,
) -> None:
    """Authoritative candidates stay visible without browser-owned projection writes."""

    unavailable_id = uuid4()
    ready_id = uuid4()
    directory = AsyncMock()
    directory.list_warehouses.return_value = [
        RwmsWarehouseIdentity(
            warehouseId=unavailable_id,
            warehouseVersion=1,
            name="Address unavailable",
            city="Первый город",
            address="Неизвестная улица, 1",
            latitude=None,
            longitude=None,
            timeZone="Europe/Moscow",
            representative=False,
            routingReady=False,
        ),
        RwmsWarehouseIdentity(
            warehouseId=ready_id,
            warehouseVersion=1,
            name="Coordinate ready",
            city="Второй город",
            address=None,
            latitude=55.75,
            longitude=37.62,
            timeZone="Europe/Moscow",
            representative=False,
            routingReady=True,
        ),
    ]
    geocoder = AsyncMock()
    geocoder.forward.side_effect = ApiError(
        503,
        "GEOCODING_PROVIDER_UNAVAILABLE",
        "Provider unavailable",
    )
    app = _application(db_session, directory, geocoder)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        available = await client.get("/api/warehouses/available")
        listed = await client.get("/api/warehouses")

    assert available.status_code == 200, available.text
    available_by_id = {item["warehouse_id"]: item for item in available.json()}
    unavailable = available_by_id[str(unavailable_id)]
    assert unavailable["routing_ready"] is False
    assert unavailable["routing_unavailable_reason"] == (
        "Не заданы координаты для использования склада в логистике"  # noqa: RUF001
    )
    assert unavailable["local_warehouse_id"] is None
    assert listed.status_code == 200, listed.text
    assert listed.json() == []
    assert available_by_id[str(ready_id)]["routing_ready"] is True
    assert available_by_id[str(ready_id)]["local_warehouse_id"] is None
    geocoder.forward.assert_not_awaited()


@pytest.mark.asyncio
async def test_warehouse_create_rejects_browser_owned_identity_fields(
    db_session: AsyncSession,
) -> None:
    """Name, address, and coordinates are not accepted in the public create schema."""

    directory = AsyncMock()
    directory.list_warehouses.return_value = []
    geocoder = AsyncMock()
    app = _application(db_session, directory, geocoder)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(
            "/api/warehouses",
            json={
                "external_warehouse_id": str(uuid4()),
                "name": "Browser depot",
                "address": "Browser address",
                "latitude": 59.9,
                "longitude": 30.3,
            },
            headers={"Idempotency-Key": "invalid-browser-warehouse"},
        )
    assert response.status_code == 422
