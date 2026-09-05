"""Admin planner settings and policy editing preserve the existing city and version fences."""

from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.main import create_app
from app.services.capacity_mutations import CapacityPublicationOutcome
from tests.auth import StaticAccessTokenVerifier, administration_access_token_verifier, user_claims
from tests.factories import make_warehouse
from tests.test_admin_catalog_api import AUTHORIZATION, _application, _identity


@pytest.mark.parametrize(
    "verifier",
    [
        StaticAccessTokenVerifier(user_claims(), require_token=True),
        administration_access_token_verifier(global_role="WMS_ADMIN", require_token=True),
        administration_access_token_verifier(scopes="admin.manage rwms.write", require_token=True),
    ],
)
def test_configuration_requires_the_isolated_admin_client(
    verifier: StaticAccessTokenVerifier,
) -> None:
    with TestClient(create_app(access_token_verifier=verifier)) as client:
        path = f"/api/admin/warehouses/{uuid4()}"
        assert client.get(f"{path}/planning-settings", headers=AUTHORIZATION).status_code == 403
        assert client.get(f"{path}/policy-zones", headers=AUTHORIZATION).status_code == 403


def test_configuration_requires_authentication() -> None:
    app = create_app(access_token_verifier=administration_access_token_verifier(require_token=True))
    with TestClient(app) as client:
        assert client.get(f"/api/admin/warehouses/{uuid4()}/planning-settings").status_code == 401


@pytest.mark.integration
async def test_settings_and_tariffs_use_canonical_identity_and_one_version_fence(
    db_session: AsyncSession,
) -> None:
    warehouse = await make_warehouse(db_session)
    directory = AsyncMock(list_warehouses=AsyncMock(return_value=[_identity(warehouse)]))
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session, directory)), base_url="http://test"
    ) as client:
        path = f"/api/admin/warehouses/{warehouse.external_warehouse_id}/planning-settings"
        loaded = await client.get(path, headers=AUTHORIZATION)
        assert loaded.status_code == 200
        original = loaded.json()
        assert original["warehouse_id"] == str(warehouse.external_warehouse_id)
        assert "id" not in original
        settings = {**original["settings"], "default_load_minutes": 45}
        payload = {
            "expected_version": original["version"],
            "settings": settings,
            "isochrone_tariffs": [{"travel_minutes": 60, "price_rubles": 9000}],
        }
        saved = await client.put(path, headers=AUTHORIZATION, json=payload)
        assert saved.status_code == 200
        assert saved.json()["version"] == original["version"] + 1
        assert saved.json()["settings"]["default_load_minutes"] == 45
        assert saved.json()["isochrone_tariffs"] == payload["isochrone_tariffs"]
        assert warehouse.capacity_generation > 0
        conflict = await client.put(path, headers=AUTHORIZATION, json=payload)
        assert conflict.status_code == 409
        assert conflict.json()["code"] == "CATALOG_VERSION_CONFLICT"
        assert warehouse.settings["default_load_minutes"] == 45
        private_id = await client.get(
            f"/api/admin/warehouses/{warehouse.id}/planning-settings", headers=AUTHORIZATION
        )
        assert private_id.status_code == 404


@pytest.mark.integration
async def test_representative_keeps_its_own_settings_and_invalid_tariffs_are_rejected(
    db_session: AsyncSession,
) -> None:
    warehouse = await make_warehouse(db_session)
    directory = AsyncMock(
        list_warehouses=AsyncMock(return_value=[_identity(warehouse, representative=True)])
    )
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session, directory)), base_url="http://test"
    ) as client:
        path = f"/api/admin/warehouses/{warehouse.external_warehouse_id}/planning-settings"
        loaded = (await client.get(path, headers=AUTHORIZATION)).json()
        payload = {
            "expected_version": loaded["version"],
            "settings": loaded["settings"],
            "isochrone_tariffs": [{"travel_minutes": 120, "price_rubles": 9000}],
        }
        assert (await client.put(path, headers=AUTHORIZATION, json=payload)).status_code == 422
        payload["isochrone_tariffs"] = []
        assert (await client.put(path, headers=AUTHORIZATION, json=payload)).status_code == 422
        assert warehouse.version == loaded["version"]


@pytest.mark.integration
async def test_saved_configuration_reports_deferred_capacity_publication(
    db_session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    warehouse = await make_warehouse(db_session)
    directory = AsyncMock(list_warehouses=AsyncMock(return_value=[_identity(warehouse)]))
    publish = AsyncMock(return_value=CapacityPublicationOutcome(1, "FAILED"))
    monkeypatch.setattr("app.api.admin_settings.publish_capacity_after_mutation", publish)
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session, directory)), base_url="http://test"
    ) as client:
        path = f"/api/admin/warehouses/{warehouse.external_warehouse_id}/planning-settings"
        loaded = (await client.get(path, headers=AUTHORIZATION)).json()
        saved = await client.put(
            path,
            headers=AUTHORIZATION,
            json={
                "expected_version": loaded["version"],
                "settings": loaded["settings"],
                "isochrone_tariffs": loaded["isochrone_tariffs"],
            },
        )
        assert saved.status_code == 200
        assert saved.json()["capacity_publish_status"] == "FAILED"
        publish.assert_awaited_once()


@pytest.mark.integration
async def test_policy_crud_replays_once_and_cannot_edit_another_city(
    db_session: AsyncSession,
) -> None:
    warehouse = await make_warehouse(db_session)
    other = await make_warehouse(db_session, name="Other city")
    directory = AsyncMock(
        list_warehouses=AsyncMock(return_value=[_identity(warehouse), _identity(other)])
    )
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session, directory)), base_url="http://test"
    ) as client:
        path = f"/api/admin/warehouses/{warehouse.external_warehouse_id}/policy-zones"
        payload = {
            "name": "Без прицепа",
            "kind": "NO_TRAILER",
            "color": "#F59E0B",
            "geometry": {
                "type": "MultiPolygon",
                "coordinates": [[[[30.0, 59.0], [31.0, 59.0], [31.0, 60.0], [30.0, 59.0]]]],
            },
        }
        headers = {**AUTHORIZATION, "Idempotency-Key": str(uuid4())}
        created = await client.post(path, headers=headers, json=payload)
        assert created.status_code == 201
        zone = created.json()
        assert zone["warehouse_id"] == str(warehouse.external_warehouse_id)
        generation = warehouse.capacity_generation
        replayed = await client.post(path, headers=headers, json=payload)
        assert replayed.status_code == 201
        assert replayed.json()["id"] == zone["id"]
        assert warehouse.capacity_generation == generation
        assert len((await client.get(path, headers=AUTHORIZATION)).json()) == 1
        edit = {"expected_version": zone["version"], "name": "Новое название"}
        foreign = await client.patch(
            f"/api/admin/warehouses/{other.external_warehouse_id}/policy-zones/{zone['id']}",
            headers=AUTHORIZATION,
            json=edit,
        )
        assert foreign.status_code == 404
        saved = await client.patch(f"{path}/{zone['id']}", headers=AUTHORIZATION, json=edit)
        assert saved.status_code == 200
        assert (
            await client.patch(f"{path}/{zone['id']}", headers=AUTHORIZATION, json=edit)
        ).status_code == 409
        deleted = await client.delete(
            f"{path}/{zone['id']}",
            headers=AUTHORIZATION,
            params={"expected_version": saved.json()["version"]},
        )
        assert deleted.status_code == 204
        assert (await client.get(path, headers=AUTHORIZATION)).json() == []
