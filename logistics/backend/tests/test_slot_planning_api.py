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
from app.main import create_app
from app.models import PlanningTask, Warehouse, WarehouseIsochroneTariff
from app.slot_planning.application import SlotPlanningApplication
from tests.factories import (
    make_driver,
    make_routable_vehicle,
    make_shift,
    make_warehouse,
)

pytestmark = pytest.mark.integration
PLANNING_DATE = date(2026, 8, 29)


async def _slot_workspace(session: AsyncSession) -> Warehouse:
    """Create one warehouse with a complete solo vehicle and ordered tariffs."""

    warehouse = await make_warehouse(session, default_planning_date=PLANNING_DATE)
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
    return warehouse


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
    """Build a warehouse-only availability request inside the first tariff tier."""

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
async def test_availability_returns_first_matching_isochrone_tariff(
    db_session: AsyncSession,
) -> None:
    """Near road travel selects the first configured tier without zone metadata."""

    warehouse = await _slot_workspace(db_session)
    payload = _availability_payload(warehouse.id)
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session)),
        base_url="http://test",
    ) as client:
        first = await client.post("/api/planning/slot-availability", json=payload)
        assert first.status_code == 200, first.text
        body = first.json()
        assert body["delivery_price_rubles"] == 10_000
        assert body["price_isochrone_minutes"] == 60
        assert "price_zone_id" not in body
        assert "price_zone_name" not in body
        assert body["trailer_access_allowed"] is True
        assert [(item["start"], item["end"]) for item in body["slots"]] == [
            ("09:00:00", "12:00:00"),
            ("12:00:00", "15:00:00"),
            ("15:00:00", "18:00:00"),
        ]


@pytest.mark.asyncio
async def test_warehouse_isochrone_prices_are_editable_and_hold_the_selected_band(
    db_session: AsyncSession,
) -> None:
    """A custom one-hour tariff is returned and frozen on the capacity hold."""

    warehouse = await _slot_workspace(db_session)
    warehouse.isochrone_tariffs[0].price_rubles = 12_345
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


def test_five_hour_tier_and_configured_max_are_hard_boundaries() -> None:
    """The fifth hour is priced while one second beyond the configured max is rejected."""

    warehouse = Warehouse(
        isochrone_tariffs=[
            WarehouseIsochroneTariff(travel_minutes=minutes, price_rubles=minutes * 100)
            for minutes in (60, 120, 180, 240, 300)
        ]
    )
    assert SlotPlanningApplication._isochrone_tier(warehouse, 4 * 3600 + 1) == 300
    assert SlotPlanningApplication._isochrone_tier(warehouse, 5 * 3600) == 300
    assert SlotPlanningApplication._isochrone_tier(warehouse, 5 * 3600 + 1) is None


@pytest.mark.asyncio
async def test_hold_confirmation_is_versioned_and_idempotent(
    db_session: AsyncSession,
) -> None:
    """A live hold confirms once and the same confirmation key replays safely."""

    warehouse = await _slot_workspace(db_session)
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

    warehouse = await _slot_workspace(db_session)
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
