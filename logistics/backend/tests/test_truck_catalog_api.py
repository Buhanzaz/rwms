"""PostGIS HTTP tests for warehouse truck, trailer, cargo, and axle profiles."""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import date

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.main import create_app
from app.models import Trailer
from tests.auth import admin_access_token_verifier
from tests.factories import make_warehouse

pytestmark = pytest.mark.integration


def _application(session: AsyncSession) -> FastAPI:
    """Bind HTTP requests to the rollback-isolated integration session."""

    application = create_app(access_token_verifier=admin_access_token_verifier())

    async def session_override() -> AsyncIterator[AsyncSession]:
        """Share one test transaction across endpoint calls."""

        yield session

    application.dependency_overrides[get_session] = session_override
    return application


def _trailer_payload(registration_number: str = "PR1000") -> dict[str, object]:
    """Build a complete warehouse trailer payload."""

    return {
        "name": "Низкорамный прицеп",
        "registration_number": registration_number,
        "active": True,
        "tare_weight_kg": 3_000,
        "max_gross_weight_kg": 10_000,
        "length_mm": 8_000,
        "width_mm": 2_500,
        "height_mm": 1_800,
        "platform_length_mm": 6_000,
        "platform_width_mm": 2_500,
        "platform_height_from_ground_mm": 900,
        "max_platform_payload_kg": 6_000,
        "payload_capacity_kg": 7_000,
        "axle_count": 2,
        "max_axle_load_kg": 10_000,
        "max_cargo_length_mm": 7_000,
        "max_cargo_width_mm": 2_600,
        "max_cargo_height_mm": 3_000,
        "max_cargo_weight_kg": 6_000,
    }


def _vehicle_payload(trailer_id: str) -> dict[str, object]:
    """Build a complete truck configuration referencing one warehouse trailer."""

    return {
        "vehicle": {
            "name": "Манипулятор 1",
            "registration_number": "A123BC77",
            "capacity": 2,
            "active": True,
            "average_speed_city": 30,
            "average_speed_region": 60,
            "vehicle_type": "FLATBED_CRANE",
            "manufacturer": "MAN",
            "model": "TGS",
            "is_hgv": True,
            "tare_weight_kg": 12_000,
            "max_gross_weight_kg": 22_000,
            "length_mm": 9_200,
            "width_mm": 2_500,
            "height_mm": 3_200,
            "axle_count": 3,
            "max_axle_load_kg": 10_000,
            "payload_capacity_kg": 8_000,
            "platform_length_mm": 6_000,
            "platform_width_mm": 2_500,
            "platform_height_from_ground_mm": 1_300,
            "max_platform_payload_kg": 7_000,
            "max_cargo_length_mm": 7_000,
            "max_cargo_width_mm": 2_600,
            "max_cargo_height_mm": 3_000,
            "max_cargo_weight_kg": 6_000,
            "can_use_trailer": True,
            "default_trailer_id": trailer_id,
            "combined_length_with_trailer_mm": 18_500,
            "coupling_length_mm": 1_300,
        },
        "load_profiles": [
            {"configuration_type": "EMPTY_TRUCK", "max_actual_axle_load_kg": 6_000},
            {"configuration_type": "CARGO_ON_TRUCK", "max_actual_axle_load_kg": 8_000},
            {"configuration_type": "EMPTY_COMBINATION", "max_actual_axle_load_kg": 7_000},
            {
                "configuration_type": "TWO_CARGO_SPLIT",
                "max_actual_axle_load_kg": 9_000,
            },
        ],
    }


@pytest.mark.asyncio
async def test_full_vehicle_configuration_and_cargo_flow(db_session: AsyncSession) -> None:
    """Workspace reads expose full truck configuration and mandatory cargo tasks."""

    warehouse = await make_warehouse(db_session)
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session)),
        base_url="http://test",
    ) as client:
        trailer = await client.post(
            f"/api/warehouses/{warehouse.id}/trailers",
            json=_trailer_payload(),
            headers={"Idempotency-Key": "truck-flow-trailer"},
        )
        assert trailer.status_code == 201, trailer.text
        vehicle = await client.post(
            f"/api/warehouses/{warehouse.id}/vehicle-configurations",
            json=_vehicle_payload(trailer.json()["id"]),
            headers={"Idempotency-Key": "truck-flow-vehicle"},
        )
        assert vehicle.status_code == 201, vehicle.text
        assert len(vehicle.json()["load_profiles"]) == 4
        assert all(
            set(profile) == {"configuration_type", "max_actual_axle_load_kg"}
            for profile in vehicle.json()["load_profiles"]
        )

        request = await client.post(
            f"/api/warehouses/{warehouse.id}/requests",
            json={
                "type": "DELIVERY",
                "name": "Two cabins",
                "address_label": "Test",
                "latitude": 59.94,
                "longitude": 30.33,
                "quantity": 2,
                "cargo_length_mm": 6_000,
                "cargo_width_mm": 2_400,
                "cargo_height_mm": 2_400,
                "cargo_weight_kg": 1_200,
                "mandatory": True,
                "trailer_access_allowed": True,
                "date_options": [{"date": date(2026, 8, 30).isoformat()}],
            },
            headers={"Idempotency-Key": "truck-flow-request"},
        )
        assert request.status_code == 201, request.text
        assert request.json()["mandatory"] is True
        assert all(task["mandatory"] is True for task in request.json()["tasks"])

        workspace = await client.get(
            f"/api/warehouses/{warehouse.id}/workspace",
            params={"refresh_rwms": "false"},
        )
        assert workspace.status_code == 200, workspace.text
        assert [item["id"] for item in workspace.json()["vehicles"]] == [vehicle.json()["id"]]
        assert workspace.json()["vehicles"][0]["load_profiles"] == vehicle.json()["load_profiles"]
        assert [item["id"] for item in workspace.json()["trailers"]] == [trailer.json()["id"]]


@pytest.mark.asyncio
async def test_vehicle_cannot_reference_another_warehouse_trailer(
    db_session: AsyncSession,
) -> None:
    """Warehouse isolation rejects cross-root trailer configuration."""

    first = await make_warehouse(db_session, name="First")
    second = await make_warehouse(db_session, name="Second")
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session)),
        base_url="http://test",
    ) as client:
        foreign_trailer = await client.post(
            f"/api/warehouses/{second.id}/trailers",
            json=_trailer_payload("FOREIGN"),
            headers={"Idempotency-Key": "foreign-trailer"},
        )
        assert foreign_trailer.status_code == 201
        rejected = await client.post(
            f"/api/warehouses/{first.id}/vehicle-configurations",
            json=_vehicle_payload(foreign_trailer.json()["id"]),
            headers={"Idempotency-Key": "foreign-vehicle"},
        )
    assert rejected.status_code == 422
    assert rejected.json()["code"] == "TRAILER_WAREHOUSE_MISMATCH"


@pytest.mark.asyncio
async def test_retryable_create_replays_and_rejects_changed_payload(
    db_session: AsyncSession,
) -> None:
    """One actor/key creates once, replays the resource, and conflicts on payload drift."""

    warehouse = await make_warehouse(db_session)
    url = f"/api/warehouses/{warehouse.id}/trailers"
    headers = {"Idempotency-Key": "stable-trailer-create"}
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session)),
        base_url="http://test",
    ) as client:
        created = await client.post(url, json=_trailer_payload(), headers=headers)
        replayed = await client.post(url, json=_trailer_payload(), headers=headers)
        changed = await client.post(
            url,
            json=_trailer_payload("CHANGED"),
            headers=headers,
        )

    assert created.status_code == 201, created.text
    assert replayed.status_code == 201, replayed.text
    assert replayed.json() == created.json()
    assert changed.status_code == 409, changed.text
    assert changed.json()["code"] == "IDEMPOTENCY_KEY_CONFLICT"
    assert (
        await db_session.scalar(
            select(func.count(Trailer.id)).where(Trailer.warehouse_id == warehouse.id)
        )
        == 1
    )


@pytest.mark.asyncio
async def test_stale_trailer_update_and_delete_are_version_fenced(
    db_session: AsyncSession,
) -> None:
    """A stale editor cannot overwrite or delete a newer catalog aggregate revision."""

    warehouse = await make_warehouse(db_session)
    url = f"/api/warehouses/{warehouse.id}/trailers"
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session)),
        base_url="http://test",
    ) as client:
        created = await client.post(
            url,
            json=_trailer_payload(),
            headers={"Idempotency-Key": "versioned-trailer-create"},
        )
        trailer_id = created.json()["id"]
        updated = await client.patch(
            f"/api/trailers/{trailer_id}",
            json={"expected_version": 1, "name": "Updated trailer"},
        )
        stale_update = await client.patch(
            f"/api/trailers/{trailer_id}",
            json={"expected_version": 1, "name": "Stale label"},
        )
        stale_delete = await client.delete(
            f"/api/trailers/{trailer_id}",
            params={"expected_version": 1},
        )
        deleted = await client.delete(
            f"/api/trailers/{trailer_id}",
            params={"expected_version": 2},
        )

    assert created.status_code == 201, created.text
    assert updated.status_code == 200, updated.text
    assert updated.json()["version"] == 2
    assert stale_update.status_code == 409
    assert stale_update.json()["code"] == "CATALOG_VERSION_CONFLICT"
    assert stale_delete.status_code == 409
    assert stale_delete.json()["code"] == "CATALOG_VERSION_CONFLICT"
    assert deleted.status_code == 204, deleted.text
