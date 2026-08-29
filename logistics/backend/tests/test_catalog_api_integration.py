"""HTTP integration tests for atomic warehouse binding and owner-scoped zones."""

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
from tests.factories import make_warehouse, make_zone

pytestmark = pytest.mark.integration


def _application(
    session: AsyncSession,
    directory: AsyncMock,
    geocoder: AsyncMock,
) -> FastAPI:
    """Bind external boundaries and persistence to deterministic test doubles."""

    application = create_app()

    async def session_override() -> AsyncIterator[AsyncSession]:
        """Share the rollback-isolated test transaction."""

        yield session

    application.dependency_overrides[get_session] = session_override
    application.dependency_overrides[get_capacity_rwms_client] = lambda: directory
    application.dependency_overrides[get_yandex_geocoding_client] = lambda: geocoder
    return application


def _zone_payload() -> dict[str, object]:
    """Return one initial owner zone covering the canonical warehouse address."""

    return {
        "name": "Санкт-Петербург",
        "color": "#3B82F6",
        "delivery_price": 5_000,
        "pickup_price": 3_000,
        "geometry": {
            "type": "Polygon",
            "coordinates": [
                [[29.0, 59.0], [32.0, 59.0], [32.0, 61.0], [29.0, 61.0], [29.0, 59.0]]
            ],
        },
    }


@pytest.mark.asyncio
async def test_binding_atomically_creates_first_zone_from_canonical_identity(
    db_session: AsyncSession,
) -> None:
    """Directory discovery materializes coordinates and invalidates only changed route plans."""

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
    app = _application(db_session, directory, geocoder)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        available = await client.get("/api/warehouses/available")
        assert available.status_code == 200
        assert available.json()[0]["warehouse_id"] == str(external_id)
        assert available.json()[0]["routing_ready"] is True
        assert available.json()[0]["routing_unavailable_reason"] is None
        assert available.json()[0]["local_warehouse_id"] is not None
        listed = await client.get("/api/warehouses")
        assert listed.status_code == 200, listed.text
        body = listed.json()[0]
        assert body["name"] == identity.name
        assert body["address"] == identity.address
        assert body["latitude"] == 59.93
        assert body["external_warehouse_id"] == str(external_id)
        assert body["external_warehouse_version"] == 1
        assert body["representative"] is True

        route_plan = RoutePlan(
            warehouse_id=body["id"],
            date=date(2026, 9, 1),
            name="Старый расчёт",
        )
        db_session.add(route_plan)
        unrelated_warehouse = await make_warehouse(db_session, name="Другой склад")
        unrelated_plan = RoutePlan(
            warehouse_id=unrelated_warehouse.id,
            date=date(2026, 9, 1),
            name="Независимый расчёт",
        )
        db_session.add(unrelated_plan)
        await db_session.flush()
        route_plan_id = route_plan.id
        unrelated_plan_id = unrelated_plan.id

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
        assert refreshed.json()[0]["external_warehouse_version"] == 2
        assert refreshed.json()[0]["latitude"] == 58.52
        assert refreshed.json()[0]["longitude"] == 31.27
        assert (
            await db_session.scalar(select(RoutePlan.id).where(RoutePlan.id == route_plan_id))
            is None
        )
        assert (
            await db_session.scalar(
                select(RoutePlan.id).where(RoutePlan.id == unrelated_plan_id)
            )
            == unrelated_plan_id
        )

        workspace = await client.get(
            f"/api/warehouses/{body['id']}/workspace",
            params={"refresh_rwms": "false"},
        )
        assert workspace.status_code == 200, workspace.text
        workspace_body = workspace.json()
        assert workspace_body["warehouse"]["id"] == body["id"]
        assert workspace_body["zones"] == []
        assert workspace_body["drivers"] == []
        assert workspace_body["requests"] == []

    directory.list_warehouses.assert_awaited()
    geocoder.forward.assert_not_awaited()


@pytest.mark.asyncio
async def test_http_warehouse_binding_accepts_no_initial_zone(
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
async def test_http_warehouse_binding_geocodes_canonical_address_without_coordinates(
    db_session: AsyncSession,
) -> None:
    """The existing server geocoder makes an address-only RWMS warehouse routable."""

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
    assert available_body["routing_ready"] is True
    assert available_body["routing_unavailable_reason"] is None
    assert available_body["latitude"] == 58.5544
    assert available_body["longitude"] == 31.2698
    assert available_body["local_warehouse_id"] is not None
    assert response.status_code == 200, response.text
    body = response.json()[0]
    assert body["external_warehouse_id"] == str(external_id)
    assert body["latitude"] == 58.5544
    assert body["longitude"] == 31.2698
    assert body["routing_ready"] is True
    geocoder.forward.assert_awaited_once_with(
        "Великий Новгород, Большая Санкт-Петербургская улица, 82"
    )

    directory.list_warehouses.return_value = [identity.model_copy(update={"warehouse_version": 5})]
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        listed = await client.get("/api/warehouses")

    assert listed.status_code == 200, listed.text
    assert listed.json()[0]["latitude"] == 58.5544
    assert listed.json()[0]["longitude"] == 31.2698
    assert listed.json()[0]["routing_ready"] is True
    geocoder.forward.assert_awaited_once()


@pytest.mark.asyncio
async def test_warehouse_geocoding_failure_does_not_hide_routing_ready_siblings(
    db_session: AsyncSession,
) -> None:
    """One unavailable address stays explicit while coordinate-ready siblings materialize."""

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
    assert [item["external_warehouse_id"] for item in listed.json()] == [str(ready_id)]
    geocoder.forward.assert_awaited()


@pytest.mark.asyncio
async def test_nested_zone_paths_hide_other_warehouse_zones(
    db_session: AsyncSession,
) -> None:
    """Lists are owner-scoped and a foreign zone UUID returns the normal not-found response."""

    first = await make_warehouse(db_session, name="First")
    second = await make_warehouse(db_session, name="Second")
    first_zone = await make_zone(db_session, first, name="First zone")
    second_zone = await make_zone(db_session, second, name="Second zone")
    app = _application(db_session, AsyncMock(), AsyncMock())

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        listed = await client.get(f"/api/warehouses/{first.id}/zones")
        hidden = await client.get(
            f"/api/warehouses/{first.id}/zones/{second_zone.id}"
        )

    assert listed.status_code == 200
    assert [item["id"] for item in listed.json()] == [str(first_zone.id)]
    assert hidden.status_code == 404
    assert hidden.json()["code"] == "ZONE_NOT_FOUND"


@pytest.mark.asyncio
async def test_capacity_mutations_advance_owner_generation_but_lock_does_not(
    db_session: AsyncSession,
) -> None:
    """Every geometry/tariff mutation is fenced once while the editor lock is metadata only."""

    warehouse = await make_warehouse(db_session)
    await make_zone(db_session, warehouse, name="Covering zone")
    app = _application(db_session, AsyncMock(), AsyncMock())

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        created = await client.post(
            f"/api/warehouses/{warehouse.id}/zones",
            json={**_zone_payload(), "name": "Mutable zone"},
        )
        assert created.status_code == 201, created.text
        zone_id = created.json()["id"]
        await db_session.refresh(warehouse)
        after_create = warehouse.capacity_generation

        locked = await client.post(
            f"/api/warehouses/{warehouse.id}/zones/{zone_id}/lock",
            json={"locked": True},
        )
        assert locked.status_code == 200, locked.text
        await db_session.refresh(warehouse)
        after_lock = warehouse.capacity_generation

        assert (
            await client.post(
                f"/api/warehouses/{warehouse.id}/zones/{zone_id}/lock",
                json={"locked": False},
            )
        ).status_code == 200
        updated = await client.patch(
            f"/api/warehouses/{warehouse.id}/zones/{zone_id}",
            json={"delivery_price": 7_500},
        )
        assert updated.status_code == 200, updated.text
        await db_session.refresh(warehouse)
        after_update = warehouse.capacity_generation

    assert after_create > 0
    assert after_lock == after_create
    assert after_update > after_lock


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
                "initial_zone": _zone_payload(),
                "name": "Browser depot",
                "address": "Browser address",
                "latitude": 59.9,
                "longitude": 30.3,
            },
        )
    assert response.status_code == 422
