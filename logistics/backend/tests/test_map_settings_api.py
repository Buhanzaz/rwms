"""Map selection is global, persistent, admin-only and fenced independently of planning."""

from collections.abc import AsyncIterator

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.main import create_app
from app.models.map_settings import MapDisplaySettings
from tests.auth import (
    StaticAccessTokenVerifier,
    administration_access_token_verifier,
    user_claims,
)

AUTHORIZATION = {"Authorization": "Bearer test-access-token"}
ADMIN_PATH = "/api/admin/map-settings"
MAP_PATH = "/api/map-settings"


def _application(session: AsyncSession, *, admin: bool) -> FastAPI:
    verifier = (
        administration_access_token_verifier(require_token=True)
        if admin
        else StaticAccessTokenVerifier(user_claims(scopes="rwms.read"), require_token=True)
    )
    application = create_app(access_token_verifier=verifier)

    async def session_override() -> AsyncIterator[AsyncSession]:
        yield session

    application.dependency_overrides[get_session] = session_override
    return application


def test_anonymous_cannot_read_or_change_map_settings() -> None:
    application = create_app(
        access_token_verifier=administration_access_token_verifier(require_token=True)
    )
    with TestClient(application) as client:
        assert client.get(ADMIN_PATH).status_code == 401
        assert client.get(MAP_PATH).status_code == 401
        assert (
            client.put(ADMIN_PATH, json={"provider": "STANDARD", "expected_version": 1}).status_code
            == 401
        )


@pytest.mark.parametrize(
    "claims",
    [
        user_claims(),
        user_claims(client_id="rwms-admin-web", global_role="WMS_ADMIN", scopes="admin.manage"),
        user_claims(client_id="rwms-admin-web", scopes="admin.manage rwms.write"),
    ],
)
def test_only_isolated_system_admin_can_manage_map_settings(claims: dict[str, object]) -> None:
    application = create_app(
        access_token_verifier=StaticAccessTokenVerifier(claims, require_token=True)
    )
    with TestClient(application) as client:
        assert client.get(ADMIN_PATH, headers=AUTHORIZATION).status_code == 403
        assert (
            client.put(
                ADMIN_PATH,
                headers=AUTHORIZATION,
                json={"provider": "STANDARD", "expected_version": 1},
            ).status_code
            == 403
        )


def test_operator_read_requires_read_scope() -> None:
    application = create_app(
        access_token_verifier=StaticAccessTokenVerifier(user_claims(scopes="rwms.write"))
    )
    with TestClient(application) as client:
        assert client.get(MAP_PATH, headers=AUTHORIZATION).status_code == 403


@pytest.mark.integration
async def test_switch_key_replacement_and_return_to_standard_preserve_version_and_key(
    db_session: AsyncSession,
) -> None:
    async with (
        AsyncClient(
            transport=ASGITransport(app=_application(db_session, admin=True)),
            base_url="http://test",
        ) as admin,
        AsyncClient(
            transport=ASGITransport(app=_application(db_session, admin=False)),
            base_url="http://test",
        ) as operator,
    ):
        initial = await admin.get(ADMIN_PATH, headers=AUTHORIZATION)
        assert initial.status_code == 200
        assert initial.headers["Cache-Control"] == "no-store"
        assert initial.json() == {
            "version": 1,
            "provider": "STANDARD",
            "yandex_api_key_configured": False,
        }
        payload = {"provider": "YANDEX", "expected_version": 1, "yandex_api_key": "  test-js-key  "}
        saved = await admin.put(ADMIN_PATH, headers=AUTHORIZATION, json=payload)
        assert saved.status_code == 200
        assert saved.json() == {
            "version": 2,
            "provider": "YANDEX",
            "yandex_api_key_configured": True,
        }
        assert "test-js-key" not in saved.text
        displayed = await operator.get(MAP_PATH, headers=AUTHORIZATION)
        assert displayed.headers["Cache-Control"] == "no-store"
        assert displayed.json() == {
            "version": 2,
            "provider": "YANDEX",
            "yandex_api_key": "test-js-key",
        }

        stale = await admin.put(
            ADMIN_PATH, headers=AUTHORIZATION, json={**payload, "yandex_api_key": "stale-key"}
        )
        assert stale.status_code == 409
        assert stale.json()["code"] == "MAP_SETTINGS_VERSION_CONFLICT"
        assert (await operator.get(MAP_PATH, headers=AUTHORIZATION)).json() == displayed.json()

        replaced = await admin.put(
            ADMIN_PATH,
            headers=AUTHORIZATION,
            json={**payload, "expected_version": 2, "yandex_api_key": "replacement-js-key"},
        )
        assert replaced.json()["version"] == 3
        assert (await operator.get(MAP_PATH, headers=AUTHORIZATION)).json()[
            "yandex_api_key"
        ] == "replacement-js-key"

        standard = await admin.put(
            ADMIN_PATH,
            headers=AUTHORIZATION,
            json={"provider": "STANDARD", "expected_version": 3, "yandex_api_key": " "},
        )
        assert standard.json() == {
            "version": 4,
            "provider": "STANDARD",
            "yandex_api_key_configured": True,
        }
        assert (await operator.get(MAP_PATH, headers=AUTHORIZATION)).json() == {
            "version": 4,
            "provider": "STANDARD",
            "yandex_api_key": None,
        }
        restored = await admin.put(
            ADMIN_PATH, headers=AUTHORIZATION, json={"provider": "YANDEX", "expected_version": 4}
        )
        assert restored.status_code == 200
        assert (await operator.get(MAP_PATH, headers=AUTHORIZATION)).json() == {
            "version": 5,
            "provider": "YANDEX",
            "yandex_api_key": "replacement-js-key",
        }


@pytest.mark.integration
async def test_yandex_requires_a_key_and_rejects_invalid_settings(db_session: AsyncSession) -> None:
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session, admin=True)), base_url="http://test"
    ) as client:
        for key in (None, "", "  "):
            response = await client.put(
                ADMIN_PATH,
                headers=AUTHORIZATION,
                json={"provider": "YANDEX", "expected_version": 1, "yandex_api_key": key},
            )
            assert response.status_code == 422
            assert response.json()["code"] == "YANDEX_MAP_KEY_REQUIRED"
        for invalid in (
            {"provider": "OTHER", "expected_version": 1},
            {"provider": "STANDARD", "expected_version": 0},
            {"provider": "YANDEX", "expected_version": 1, "yandex_api_key": "a" * 257},
        ):
            assert (
                await client.put(ADMIN_PATH, headers=AUTHORIZATION, json=invalid)
            ).status_code == 422
        assert (await client.get(ADMIN_PATH, headers=AUTHORIZATION)).json()["version"] == 1


@pytest.mark.integration
async def test_missing_persisted_configuration_is_an_explicit_error(
    db_session: AsyncSession,
) -> None:
    row = await db_session.get(MapDisplaySettings, 1)
    assert row is not None
    await db_session.delete(row)
    await db_session.flush()
    async with AsyncClient(
        transport=ASGITransport(app=_application(db_session, admin=False)), base_url="http://test"
    ) as client:
        response = await client.get(MAP_PATH, headers=AUTHORIZATION)
        assert response.status_code == 503
        assert response.json()["code"] == "MAP_SETTINGS_UNAVAILABLE"
