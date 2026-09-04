"""Admin-client catalog CRUD and permanent resource relocation regressions."""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import UTC, date, datetime, time
from unittest.mock import AsyncMock
from uuid import UUID, uuid4

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_capacity_rwms_client
from app.db import get_session
from app.main import create_app, openapi_document
from app.models import DriverShift, RouteCycle, RoutePlan, Vehicle, Warehouse
from app.models.domain import PlanStatus
from app.schemas.domain import (
    RwmsWarehouseIdentity,
    ShiftCreate,
    TrailerCreate,
    VehicleUpdate,
)
from app.services import catalog
from tests.auth import (
    StaticAccessTokenVerifier,
    administration_access_token_verifier,
    user_claims,
)
from tests.factories import make_driver, make_vehicle, make_warehouse

pytestmark = pytest.mark.integration
AUTHORIZATION = {"Authorization": "Bearer test-access-token"}


def _identity(
    warehouse: Warehouse,
    *,
    representative: bool = False,
) -> RwmsWarehouseIdentity:
    """Build an authoritative active directory identity for a local test warehouse."""

    return RwmsWarehouseIdentity(
        warehouseId=warehouse.external_warehouse_id,
        warehouseVersion=1,
        name=warehouse.name,
        city=warehouse.city,
        address=warehouse.address,
        latitude=warehouse.latitude,
        longitude=warehouse.longitude,
        timeZone=warehouse.timezone,
        representative=representative,
        routingReady=True,
    )


def _application(
    session: AsyncSession,
    directory: AsyncMock,
    *,
    verifier: StaticAccessTokenVerifier | None = None,
) -> FastAPI:
    """Bind admin authentication, the canonical directory, and one test transaction."""

    application = create_app(
        access_token_verifier=verifier or administration_access_token_verifier(require_token=True)
    )

    async def session_override() -> AsyncIterator[AsyncSession]:
        """Share the rollback-isolated integration transaction."""

        yield session

    application.dependency_overrides[get_session] = session_override
    application.dependency_overrides[get_capacity_rwms_client] = lambda: directory
    return application


def _trailer_payload(registration_number: str = "ADMIN-TRAILER") -> dict[str, object]:
    """Return the minimum valid trailer editor payload."""

    return {
        "name": "Административный прицеп",
        "registration_number": registration_number,
        "active": True,
    }


def _vehicle_configuration_payload(
    registration_number: str = "ADMIN-VEHICLE",
) -> dict[str, object]:
    """Return the minimum valid atomic vehicle configuration payload."""

    return {
        "vehicle": {
            "name": "Административный транспорт",
            "registration_number": registration_number,
            "capacity": 2,
            "active": True,
        },
        "load_profiles": [],
    }


def test_openapi_exposes_isolated_admin_catalog_contract() -> None:
    """Document canonical warehouse paths, CRUD, and both relocation commands."""

    document = openapi_document()
    paths = document["paths"]
    expected_methods = {
        "/api/admin/warehouses/{warehouse_id}/vehicles": {"get"},
        "/api/admin/warehouses/{warehouse_id}/vehicle-configurations": {"post"},
        "/api/admin/vehicles/{vehicle_id}": {"patch", "delete"},
        "/api/admin/vehicles/{vehicle_id}/configuration": {"put"},
        "/api/admin/vehicles/{vehicle_id}/relocate": {"post"},
        "/api/admin/warehouses/{warehouse_id}/trailers": {"get", "post"},
        "/api/admin/trailers/{trailer_id}": {"patch", "delete"},
        "/api/admin/trailers/{trailer_id}/relocate": {"post"},
    }
    for path, methods in expected_methods.items():
        assert methods <= paths[path].keys()
        for method in methods:
            assert paths[path][method]["security"] == [{"HTTPBearer": []}]
    relocation = document["components"]["schemas"]["AdminCatalogRelocationRequest"]
    assert set(relocation["required"]) == {"expected_version", "target_warehouse_id"}
    assert relocation["properties"]["target_warehouse_id"]["format"] == "uuid"
    vehicle = document["components"]["schemas"]["AdminVehicleRead"]
    trailer = document["components"]["schemas"]["AdminTrailerRead"]
    assert {
        "id",
        "version",
        "warehouse_id",
        "name",
        "registration_number",
        "capacity",
        "active",
        "notes",
        "load_profiles",
    } <= set(vehicle["required"])
    assert {
        "id",
        "version",
        "warehouse_id",
        "name",
        "registration_number",
        "active",
        "notes",
    } <= set(trailer["required"])


@pytest.mark.parametrize(
    "verifier",
    [
        StaticAccessTokenVerifier(
            user_claims(global_role="SYSTEM_ADMIN"),
            require_token=True,
        ),
        administration_access_token_verifier(
            global_role="WAREHOUSE_MANAGER",
            require_token=True,
        ),
        administration_access_token_verifier(
            global_role="WMS_ADMIN",
            require_token=True,
        ),
        administration_access_token_verifier(
            scopes="openid profile offline_access admin.manage rwms.read",
            require_token=True,
        ),
    ],
)
def test_admin_catalog_rejects_wrong_client_role_or_business_scope(
    verifier: StaticAccessTokenVerifier,
) -> None:
    """Never reuse planner credentials or broaden the isolated admin token."""

    application = create_app(access_token_verifier=verifier)
    with TestClient(application) as client:
        response = client.get(
            f"/api/admin/warehouses/{uuid4()}/vehicles",
            headers=AUTHORIZATION,
        )
    assert response.status_code == 403
    assert response.json()["code"] == "ACCESS_DENIED"


def test_admin_catalog_requires_bearer_authentication() -> None:
    """A valid admin claim set is insufficient when no Bearer was presented."""

    application = create_app(
        access_token_verifier=administration_access_token_verifier(require_token=True)
    )
    with TestClient(application) as client:
        response = client.get(f"/api/admin/warehouses/{uuid4()}/vehicles")
    assert response.status_code == 401
    assert response.json()["code"] == "AUTHENTICATION_REQUIRED"


@pytest.mark.asyncio
async def test_admin_catalog_crud_uses_canonical_warehouse_ids(
    db_session: AsyncSession,
) -> None:
    """Admin list and mutation responses never leak planner-local warehouse UUIDs."""

    warehouse = await make_warehouse(db_session, name="Admin catalog")
    directory = AsyncMock()
    directory.list_warehouses.return_value = [_identity(warehouse)]
    app = _application(db_session, directory)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        trailer = await client.post(
            f"/api/admin/warehouses/{warehouse.external_warehouse_id}/trailers",
            headers={**AUTHORIZATION, "Idempotency-Key": "admin-trailer-create"},
            json=_trailer_payload(),
        )
        assert trailer.status_code == 201, trailer.text
        assert trailer.json()["warehouse_id"] == str(warehouse.external_warehouse_id)
        assert trailer.json()["warehouse_id"] != str(warehouse.id)

        updated_trailer = await client.patch(
            f"/api/admin/trailers/{trailer.json()['id']}",
            headers=AUTHORIZATION,
            json={"expected_version": 1, "name": "Обновлённый прицеп"},
        )
        assert updated_trailer.status_code == 200, updated_trailer.text
        assert updated_trailer.json()["version"] == 2

        vehicle = await client.post(
            f"/api/admin/warehouses/{warehouse.external_warehouse_id}/vehicle-configurations",
            headers={**AUTHORIZATION, "Idempotency-Key": "admin-vehicle-create"},
            json=_vehicle_configuration_payload(),
        )
        assert vehicle.status_code == 201, vehicle.text
        assert vehicle.json()["warehouse_id"] == str(warehouse.external_warehouse_id)

        updated_vehicle = await client.patch(
            f"/api/admin/vehicles/{vehicle.json()['id']}",
            headers=AUTHORIZATION,
            json={"expected_version": 1, "name": "Обновлённый транспорт"},
        )
        assert updated_vehicle.status_code == 200, updated_vehicle.text
        assert updated_vehicle.json()["version"] == 2

        vehicles = await client.get(
            f"/api/admin/warehouses/{warehouse.external_warehouse_id}/vehicles",
            headers=AUTHORIZATION,
        )
        trailers = await client.get(
            f"/api/admin/warehouses/{warehouse.external_warehouse_id}/trailers",
            headers=AUTHORIZATION,
        )
        assert vehicles.status_code == 200, vehicles.text
        assert trailers.status_code == 200, trailers.text
        assert [item["id"] for item in vehicles.json()] == [vehicle.json()["id"]]
        assert [item["id"] for item in trailers.json()] == [trailer.json()["id"]]

        deleted_vehicle = await client.delete(
            f"/api/admin/vehicles/{vehicle.json()['id']}",
            headers=AUTHORIZATION,
            params={"expected_version": 2},
        )
        deleted_trailer = await client.delete(
            f"/api/admin/trailers/{trailer.json()['id']}",
            headers=AUTHORIZATION,
            params={"expected_version": 2},
        )

    assert deleted_vehicle.status_code == 204, deleted_vehicle.text
    assert deleted_trailer.status_code == 204, deleted_trailer.text


@pytest.mark.asyncio
async def test_admin_vehicle_delete_rejects_active_and_retained_shift_history(
    db_session: AsyncSession,
) -> None:
    """An active or inactive shift keeps its vehicle, including a saved route cycle, immutable."""

    warehouse = await make_warehouse(db_session, name="Vehicle delete guard")
    vehicle = await make_vehicle(db_session, warehouse)
    driver = await make_driver(db_session, warehouse)
    shift = await catalog.create_shift(
        db_session,
        warehouse.id,
        ShiftCreate(
            driver_id=driver.id,
            vehicle_id=vehicle.id,
            date_from=date(2099, 1, 1),
            date_to=date(2099, 1, 1),
            start_time=time(8),
            end_time=time(20),
        ),
    )
    directory = AsyncMock()
    directory.list_warehouses.return_value = [_identity(warehouse)]
    app = _application(db_session, directory)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        active = await client.delete(
            f"/api/admin/vehicles/{vehicle.id}",
            headers=AUTHORIZATION,
            params={"expected_version": 1},
        )

        shift.active = False
        route_plan = RoutePlan(
            warehouse_id=warehouse.id,
            date=date(2099, 1, 1),
            name="Retained shift history",
            status=PlanStatus.DRAFT,
        )
        db_session.add(route_plan)
        await db_session.flush()
        route_cycle = RouteCycle(
            route_plan_id=route_plan.id,
            driver_shift_id=shift.id,
            sequence=1,
            planned_start=datetime(2099, 1, 1, 8, tzinfo=UTC),
            planned_finish=datetime(2099, 1, 1, 9, tzinfo=UTC),
        )
        db_session.add(route_cycle)
        await db_session.flush()

        retained = await client.delete(
            f"/api/admin/vehicles/{vehicle.id}",
            headers=AUTHORIZATION,
            params={"expected_version": 1},
        )

    assert active.status_code == 409
    assert active.json()["code"] == "VEHICLE_HAS_ACTIVE_SHIFTS"
    assert active.json()["shift_id"] == str(shift.id)
    assert retained.status_code == 409
    assert retained.json()["code"] == "VEHICLE_HAS_LINKED_SHIFTS"
    assert retained.json()["shift_id"] == str(shift.id)
    assert await db_session.get(Vehicle, vehicle.id) is not None
    assert await db_session.get(DriverShift, shift.id) is not None
    assert await db_session.get(RouteCycle, route_cycle.id) is not None


@pytest.mark.asyncio
async def test_admin_trailer_delete_rejects_default_assignment_and_honors_version(
    db_session: AsyncSession,
) -> None:
    """The trailer assignment lock preserves the default link and delete fencing."""

    warehouse = await make_warehouse(db_session, name="Trailer delete guard")
    vehicle = await make_vehicle(db_session, warehouse)
    trailer = await catalog.create_trailer(
        db_session,
        warehouse.id,
        TrailerCreate(name="Default trailer", registration_number="DELETE-GUARD"),
    )
    await catalog.update_vehicle(
        db_session,
        vehicle.id,
        VehicleUpdate(
            expected_version=1,
            can_use_trailer=True,
            default_trailer_id=trailer.id,
        ),
    )
    free_vehicle = await make_vehicle(db_session, warehouse)
    directory = AsyncMock()
    directory.list_warehouses.return_value = [_identity(warehouse)]
    app = _application(db_session, directory)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        assigned = await client.delete(
            f"/api/admin/trailers/{trailer.id}",
            headers=AUTHORIZATION,
            params={"expected_version": 1},
        )
        stale = await client.delete(
            f"/api/admin/vehicles/{free_vehicle.id}",
            headers=AUTHORIZATION,
            params={"expected_version": 2},
        )
        deleted = await client.delete(
            f"/api/admin/vehicles/{free_vehicle.id}",
            headers=AUTHORIZATION,
            params={"expected_version": 1},
        )

    assert assigned.status_code == 409
    assert assigned.json()["code"] == "TRAILER_IS_DEFAULT_FOR_VEHICLE"
    assert assigned.json()["vehicle_id"] == str(vehicle.id)
    assert stale.status_code == 409
    assert stale.json()["code"] == "CATALOG_VERSION_CONFLICT"
    assert deleted.status_code == 204, deleted.text
    assert await db_session.get(Vehicle, free_vehicle.id) is None


@pytest.mark.asyncio
async def test_admin_vehicle_and_trailer_relocation_is_permanent_and_versioned(
    db_session: AsyncSession,
) -> None:
    """Both resources change local custody while exposing only the canonical target UUID."""

    source = await make_warehouse(db_session, name="Relocation source")
    target = await make_warehouse(db_session, name="Relocation target")
    vehicle = await make_vehicle(db_session, source)
    trailer = await catalog.create_trailer(
        db_session,
        source.id,
        TrailerCreate(name="Свободный прицеп", registration_number="MOVE-TRAILER"),
    )
    directory = AsyncMock()
    directory.list_warehouses.return_value = [_identity(source), _identity(target)]
    app = _application(db_session, directory)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        moved_vehicle = await client.post(
            f"/api/admin/vehicles/{vehicle.id}/relocate",
            headers=AUTHORIZATION,
            json={
                "expected_version": 1,
                "target_warehouse_id": str(target.external_warehouse_id),
            },
        )
        moved_trailer = await client.post(
            f"/api/admin/trailers/{trailer.id}/relocate",
            headers=AUTHORIZATION,
            json={
                "expected_version": 1,
                "target_warehouse_id": str(target.external_warehouse_id),
            },
        )
        stale_vehicle = await client.post(
            f"/api/admin/vehicles/{vehicle.id}/relocate",
            headers=AUTHORIZATION,
            json={
                "expected_version": 1,
                "target_warehouse_id": str(source.external_warehouse_id),
            },
        )
        unchanged_trailer = await client.post(
            f"/api/admin/trailers/{trailer.id}/relocate",
            headers=AUTHORIZATION,
            json={
                "expected_version": 2,
                "target_warehouse_id": str(target.external_warehouse_id),
            },
        )

    assert moved_vehicle.status_code == 200, moved_vehicle.text
    assert moved_vehicle.json()["warehouse_id"] == str(target.external_warehouse_id)
    assert moved_vehicle.json()["version"] == 2
    assert moved_trailer.status_code == 200, moved_trailer.text
    assert moved_trailer.json()["warehouse_id"] == str(target.external_warehouse_id)
    assert moved_trailer.json()["version"] == 2
    assert stale_vehicle.status_code == 409
    assert stale_vehicle.json()["code"] == "CATALOG_VERSION_CONFLICT"
    assert unchanged_trailer.status_code == 409
    assert unchanged_trailer.json()["code"] == "CATALOG_RELOCATION_TARGET_UNCHANGED"

    await db_session.refresh(vehicle)
    await db_session.refresh(trailer)
    assert vehicle.warehouse_id == target.id
    assert trailer.warehouse_id == target.id


@pytest.mark.asyncio
async def test_relocation_rejects_active_shift_and_vehicle_trailer_links(
    db_session: AsyncSession,
) -> None:
    """A permanent move never leaves an active shift or default-trailer cross-object link."""

    source = await make_warehouse(db_session, name="Guard source")
    target = await make_warehouse(db_session, name="Guard target")
    vehicle = await make_vehicle(db_session, source)
    driver = await make_driver(db_session, source)
    shift = await catalog.create_shift(
        db_session,
        source.id,
        ShiftCreate(
            driver_id=driver.id,
            vehicle_id=vehicle.id,
            date_from=date(2099, 1, 1),
            date_to=date(2099, 1, 1),
            start_time=time(8),
            end_time=time(20),
        ),
    )
    trailer = await catalog.create_trailer(
        db_session,
        source.id,
        TrailerCreate(name="Связанный прицеп", registration_number="LINKED-TRAILER"),
    )
    directory = AsyncMock()
    directory.list_warehouses.return_value = [_identity(source), _identity(target)]
    app = _application(db_session, directory)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        active_shift = await client.post(
            f"/api/admin/vehicles/{vehicle.id}/relocate",
            headers=AUTHORIZATION,
            json={
                "expected_version": 1,
                "target_warehouse_id": str(target.external_warehouse_id),
            },
        )
        shift.active = False
        await db_session.flush()
        await catalog.update_vehicle(
            db_session,
            vehicle.id,
            VehicleUpdate(
                expected_version=1,
                can_use_trailer=True,
                default_trailer_id=trailer.id,
            ),
        )
        attached_vehicle = await client.post(
            f"/api/admin/vehicles/{vehicle.id}/relocate",
            headers=AUTHORIZATION,
            json={
                "expected_version": 2,
                "target_warehouse_id": str(target.external_warehouse_id),
            },
        )
        attached_trailer = await client.post(
            f"/api/admin/trailers/{trailer.id}/relocate",
            headers=AUTHORIZATION,
            json={
                "expected_version": 1,
                "target_warehouse_id": str(target.external_warehouse_id),
            },
        )

    assert active_shift.status_code == 409
    assert active_shift.json()["code"] == "VEHICLE_HAS_ACTIVE_SHIFTS"
    assert active_shift.json()["shift_id"] == str(shift.id)
    assert attached_vehicle.status_code == 409
    assert attached_vehicle.json()["code"] == "VEHICLE_HAS_DEFAULT_TRAILER"
    assert attached_trailer.status_code == 409
    assert attached_trailer.json()["code"] == "TRAILER_IS_DEFAULT_FOR_VEHICLE"
    assert attached_trailer.json()["vehicle_id"] == str(vehicle.id)


@pytest.mark.asyncio
async def test_relocation_requires_current_eligible_configured_target(
    db_session: AsyncSession,
) -> None:
    """Reject representatives, missing canonical objects, and unconfigured targets distinctly."""

    source = await make_warehouse(db_session, name="Target validation source")
    representative = await make_warehouse(db_session, name="Representative target")
    representative.representative = True
    absent_from_directory = await make_warehouse(db_session, name="Inactive target")
    vehicle = await make_vehicle(db_session, source)
    unconfigured_external_id = uuid4()
    unconfigured_identity = RwmsWarehouseIdentity(
        warehouseId=unconfigured_external_id,
        warehouseVersion=1,
        name="Unconfigured target",
        city="Москва",
        address="Тестовый адрес",
        latitude=55.75,
        longitude=37.62,
        timeZone="Europe/Moscow",
        representative=False,
        routingReady=True,
    )
    directory = AsyncMock()
    directory.list_warehouses.return_value = [
        _identity(source),
        _identity(representative, representative=True),
        unconfigured_identity,
    ]
    app = _application(db_session, directory)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        ineligible = await client.post(
            f"/api/admin/vehicles/{vehicle.id}/relocate",
            headers=AUTHORIZATION,
            json={
                "expected_version": 1,
                "target_warehouse_id": str(representative.external_warehouse_id),
            },
        )
        missing = await client.post(
            f"/api/admin/vehicles/{vehicle.id}/relocate",
            headers=AUTHORIZATION,
            json={
                "expected_version": 1,
                "target_warehouse_id": str(absent_from_directory.external_warehouse_id),
            },
        )
        unconfigured = await client.post(
            f"/api/admin/vehicles/{vehicle.id}/relocate",
            headers=AUTHORIZATION,
            json={
                "expected_version": 1,
                "target_warehouse_id": str(unconfigured_external_id),
            },
        )

    assert ineligible.status_code == 422
    assert ineligible.json()["code"] == "WAREHOUSE_NOT_ELIGIBLE_FOR_CATALOG"
    assert missing.status_code == 404
    assert missing.json()["code"] == "CANONICAL_WAREHOUSE_NOT_FOUND"
    assert unconfigured.status_code == 409
    assert unconfigured.json()["code"] == "WAREHOUSE_NOT_CONFIGURED_FOR_LOGISTICS"
    assert UUID(unconfigured.json()["warehouse_id"]) == unconfigured_external_id
