"""PostGIS HTTP checks for dynamic warehouse slots and versioned holds."""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import date
from uuid import UUID, uuid4

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.db import get_session
from app.geo import geometry_from_geojson
from app.main import create_app
from app.models import PlanningTask, Warehouse, Zone, ZoneKind
from app.schemas.domain import GeoJsonGeometry
from tests.factories import (
    make_driver,
    make_routable_vehicle,
    make_shift,
    make_warehouse,
    make_zone,
)

pytestmark = pytest.mark.integration
PLANNING_DATE = date(2026, 8, 29)


async def _slot_workspace(session: AsyncSession) -> tuple[Warehouse, Zone]:
    """Create one warehouse with a complete solo vehicle and owned tariff zone."""

    warehouse = await make_warehouse(session, default_planning_date=PLANNING_DATE)
    zone = await make_zone(session, warehouse, name="Central", color="#A855F7")
    zone.delivery_price = 4_800
    zone.pickup_price = 3_200
    driver = await make_driver(session, warehouse)
    vehicle = await make_routable_vehicle(session, warehouse)
    await make_shift(
        session,
        warehouse,
        driver,
        vehicle,
        date_from=PLANNING_DATE,
        date_to=PLANNING_DATE,
    )
    await session.flush()
    return warehouse, zone


def _application(session: AsyncSession) -> FastAPI:
    """Bind endpoint requests to the rollback fixture and deterministic truck router."""

    application = create_app()

    async def session_override() -> AsyncIterator[AsyncSession]:
        """Share the current integration transaction with endpoint dependencies."""

        yield session

    def settings_override() -> Settings:
        """Use deterministic exact-profile routing without an external network."""

        return Settings(routing_provider="mock")

    application.dependency_overrides[get_session] = session_override
    application.dependency_overrides[get_settings] = settings_override
    return application


def _availability_payload(warehouse_id: UUID) -> dict[str, object]:
    """Build a warehouse-only availability request inside the central tariff zone."""

    return {
        "warehouse_id": str(warehouse_id),
        "date": PLANNING_DATE.isoformat(),
        "address": "Санкт-Петербург, тестовый адрес",
        "latitude": 59.94,
        "longitude": 30.33,
        "cabin_count": 1,
        "site_cabin_capacity": 1,
        "service_duration_minutes": 60,
    }


@pytest.mark.asyncio
async def test_availability_returns_zone_uuid_and_name_without_affecting_feasibility(
    db_session: AsyncSession,
) -> None:
    """Tariff classification is descriptive while exact slots remain route-derived."""

    warehouse, zone = await _slot_workspace(db_session)
    payload = _availability_payload(warehouse.id)
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session)),
        base_url="http://test",
    ) as client:
        first = await client.post("/api/planning/slot-availability", json=payload)
        assert first.status_code == 200, first.text
        body = first.json()
        assert body["delivery_price_rubles"] == 4_800
        assert body["price_isochrone_minutes"] is None
        assert body["price_zone_id"] == str(zone.id)
        assert body["price_zone_name"] == "Central"
        assert body["trailer_access_allowed"] is True
        assert [(item["start"], item["end"]) for item in body["slots"]] == [
            ("09:00:00", "12:00:00"),
            ("12:00:00", "15:00:00"),
            ("15:00:00", "18:00:00"),
        ]
        statuses = [item["status"] for item in body["slots"]]

        zone.geometry = geometry_from_geojson(
            GeoJsonGeometry(
                type="Polygon",
                coordinates=[
                    [[35.0, 55.0], [36.0, 55.0], [36.0, 56.0], [35.0, 56.0], [35.0, 55.0]]
                ],
            )
        )
        zone.version += 1
        await db_session.flush()
        outside = await client.post("/api/planning/slot-availability", json=payload)
        assert outside.status_code == 200, outside.text
        outside_body = outside.json()
        assert outside_body["delivery_price_rubles"] == 10_000
        assert outside_body["price_isochrone_minutes"] == 60
        assert outside_body["price_zone_id"] is None
        assert outside_body["price_zone_name"] is None
        assert [item["status"] for item in outside_body["slots"]] == statuses


@pytest.mark.asyncio
async def test_forbidden_and_no_trailer_zones_apply_independent_policies(
    db_session: AsyncSession,
) -> None:
    """A prohibition blocks slots while a no-trailer polygon keeps solo slots possible."""

    warehouse, _ = await _slot_workspace(db_session)
    forbidden = await make_zone(
        db_session,
        warehouse,
        name="Forbidden",
        kind=ZoneKind.FORBIDDEN,
        west=30.30,
        south=59.90,
        east=30.36,
        north=59.98,
    )
    payload = _availability_payload(warehouse.id)
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session)),
        base_url="http://test",
    ) as client:
        rejected = await client.post("/api/planning/slot-availability", json=payload)
        assert rejected.status_code == 422
        assert rejected.json()["code"] == "DELIVERY_FORBIDDEN_ZONE"

        await db_session.delete(forbidden)
        await db_session.flush()
        await make_zone(
            db_session,
            warehouse,
            name="No trailer",
            kind=ZoneKind.NO_TRAILER,
            west=30.30,
            south=59.90,
            east=30.36,
            north=59.98,
        )
        allowed = await client.post(
            "/api/planning/slot-availability",
            json={**payload, "cabin_count": 2, "site_cabin_capacity": 2},
        )

    assert allowed.status_code == 200, allowed.text
    body = allowed.json()
    assert body["trailer_access_allowed"] is False
    assert any(item["status"] == "AVAILABLE" for item in body["slots"])


@pytest.mark.asyncio
async def test_warehouse_isochrone_prices_are_editable_and_hold_the_selected_band(
    db_session: AsyncSession,
) -> None:
    """A custom one-hour tariff is returned and frozen on the capacity hold."""

    warehouse, zone = await _slot_workspace(db_session)
    zone.geometry = geometry_from_geojson(
        GeoJsonGeometry(
            type="Polygon",
            coordinates=[
                [[35.0, 55.0], [36.0, 55.0], [36.0, 56.0], [35.0, 56.0], [35.0, 55.0]]
            ],
        )
    )
    warehouse.isochrone_price_60_minutes = 12_345
    await db_session.flush()
    hold_payload = {
        **_availability_payload(warehouse.id),
        "slot_start": "09:00:00",
        "slot_end": "12:00:00",
        "client_session_id": "isochrone-price-session",
    }
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session)),
        base_url="http://test",
    ) as client:
        held = await client.post("/api/planning/slot-holds", json=hold_payload)

    assert held.status_code == 201, held.text
    assert held.json()["delivery_price_rubles"] == 12_345
    assert held.json()["price_isochrone_minutes"] == 60
    assert held.json()["price_zone_id"] is None


@pytest.mark.asyncio
async def test_hold_confirmation_is_versioned_and_idempotent(
    db_session: AsyncSession,
) -> None:
    """A live hold confirms once and the same confirmation key replays safely."""

    warehouse, zone = await _slot_workspace(db_session)
    hold_payload = {
        **_availability_payload(warehouse.id),
        "slot_start": "09:00:00",
        "slot_end": "12:00:00",
        "client_session_id": "customer-session-1",
    }
    confirmation_key = uuid4()
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session)),
        base_url="http://test",
    ) as client:
        held = await client.post("/api/planning/slot-holds", json=hold_payload)
        assert held.status_code == 201, held.text
        assert held.json()["price_zone_id"] == str(zone.id)
        confirm_url = f"/api/planning/slot-holds/{held.json()['hold_id']}/confirm"
        confirmed = await client.post(
            confirm_url,
            json={"confirmation_key": str(confirmation_key)},
        )
        assert confirmed.status_code == 200, confirmed.text
        assert confirmed.json()["plan_version"] == 2
        assert confirmed.json()["replayed"] is False
        replay = await client.post(
            confirm_url,
            json={"confirmation_key": str(confirmation_key)},
        )
        assert replay.status_code == 200
        assert replay.json() == {**confirmed.json(), "replayed": True}


@pytest.mark.asyncio
async def test_confirmed_site_capacity_split_persists_vehicle_sized_tasks(
    db_session: AsyncSession,
) -> None:
    """Two cabins at a solo-access site become two one-cabin planning tasks."""

    warehouse, _ = await _slot_workspace(db_session)
    payload = {
        **_availability_payload(warehouse.id),
        "cabin_count": 2,
        "site_cabin_capacity": 1,
        "slot_start": "09:00:00",
        "slot_end": "12:00:00",
        "client_session_id": "split-session",
    }
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session)),
        base_url="http://test",
    ) as client:
        held = await client.post("/api/planning/slot-holds", json=payload)
        assert held.status_code == 201, held.text
        confirmed = await client.post(
            f"/api/planning/slot-holds/{held.json()['hold_id']}/confirm",
            json={"confirmation_key": str(uuid4())},
        )
        assert confirmed.status_code == 200, confirmed.text

    tasks = list(
        await db_session.scalars(
            select(PlanningTask)
            .where(PlanningTask.request_id == UUID(confirmed.json()["request_id"]))
            .order_by(PlanningTask.part_number)
        )
    )
    assert [task.quantity for task in tasks] == [1, 1]
