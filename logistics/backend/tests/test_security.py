"""Authentication, warehouse isolation, and server-owned audit regression tests."""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import UTC, date, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import FastAPI
from fastapi.testclient import TestClient
from httpx import ASGITransport, AsyncClient
from pydantic import ValidationError
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.db import get_session
from app.errors import ApiError
from app.main import create_app
from app.models import PlanningDayClosure, RoutePlan, UnassignedTask
from app.schemas.domain import ConfirmPlanRequest, ManualChangeRequest
from app.security import JwksAccessTokenVerifier
from tests.auth import (
    TEST_USER_ID,
    StaticAccessTokenVerifier,
    user_claims,
)
from tests.factories import make_request, make_warehouse


def _strict_application(claims: dict[str, object]) -> FastAPI:
    """Create an app whose only accepted credential is the deterministic test Bearer."""

    return create_app(
        access_token_verifier=StaticAccessTokenVerifier(
            claims,
            require_token=True,
        )
    )


def _authorization() -> dict[str, str]:
    """Return the accepted Authorization header for strict verifier tests."""

    return {"Authorization": "Bearer test-access-token"}


def _warehouse_user_claims(
    external_warehouse_id: object,
    *,
    level: str = "VIEW",
) -> dict[str, object]:
    """Build a non-admin operator restricted to one canonical warehouse."""

    return user_claims(
        global_role="WAREHOUSE_MANAGER",
        warehouse_access=[
            {
                "warehouseId": str(external_warehouse_id),
                "level": level,
            }
        ],
    )


class StaticSigningKeyClient:
    """Return one RSA public key without network-backed JWKS discovery."""

    def __init__(self, key: object) -> None:
        self._key = key

    def get_signing_key_from_jwt(self, token: str) -> object:
        """Return a PyJWK-compatible key wrapper for any presented token."""

        del token
        return SimpleNamespace(key=self._key)


@pytest.mark.asyncio
async def test_jwks_verifier_enforces_signature_issuer_audience_and_expiry(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Accept only a non-expired RS256 token for the configured RWMS audience."""

    private_key = rsa.generate_private_key(public_exponent=65_537, key_size=2_048)
    settings = Settings(
        _env_file=None,
        auth_issuer="https://rwms.example/auth",
        auth_jwks_url="https://rwms.internal/auth/oauth2/jwks",
        auth_audience="rwms-services",
    )
    verifier = JwksAccessTokenVerifier(settings)
    monkeypatch.setattr(
        verifier,
        "_jwks",
        StaticSigningKeyClient(private_key.public_key()),
    )
    base_claims = {
        **user_claims(),
        "iss": settings.auth_issuer,
        "aud": settings.auth_audience,
        "exp": datetime.now(UTC) + timedelta(minutes=5),
    }
    valid = jwt.encode(base_claims, private_key, algorithm="RS256")

    claims = await verifier.verify(valid)

    assert claims["sub"] == str(TEST_USER_ID)
    for override in (
        {"iss": "https://attacker.example/auth"},
        {"aud": "another-service"},
        {"exp": datetime.now(UTC) - timedelta(seconds=1)},
    ):
        rejected = jwt.encode(
            {**base_claims, **override},
            private_key,
            algorithm="RS256",
        )
        with pytest.raises(ApiError) as error:
            await verifier.verify(rejected)
        assert error.value.status_code == 401
        assert error.value.code == "AUTHENTICATION_REQUIRED"


def test_health_is_public_but_operator_api_requires_bearer() -> None:
    """Expose liveness without exposing any operational catalogue or command."""

    app = _strict_application(user_claims())
    with TestClient(app) as client:
        assert client.get("/api/health").status_code == 200
        response = client.get("/api/warehouses")

    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json()["code"] == "AUTHENTICATION_REQUIRED"


@pytest.mark.parametrize(
    ("claims", "detail"),
    [
        (
            {
                **user_claims(),
                "principal_type": "SERVICE",
                "client_id": "logistics-planner",
            },
            "Доступ разрешён только пользователю панели RWMS.",
        ),
        (
            user_claims(global_role="CUSTOMER"),
            "Роль пользователя не разрешает работу с логистикой.",  # noqa: RUF001
        ),
        (
            user_claims(scopes="rwms.write"),
            "Недостаточно прав для этой операции.",
        ),
    ],
)
def test_non_operator_identity_and_missing_scope_are_rejected(
    claims: dict[str, object],
    detail: str,
) -> None:
    """Reject service/customer identities and tokens without baseline read scope."""

    with TestClient(_strict_application(claims)) as client:
        response = client.get("/api/warehouses", headers=_authorization())

    assert response.status_code == 403
    assert response.json()["code"] == "ACCESS_DENIED"
    assert response.json()["detail"] == detail


@pytest.mark.integration
@pytest.mark.asyncio
async def test_local_warehouse_id_cannot_bypass_signed_canonical_grant(
    db_session: AsyncSession,
) -> None:
    """A valid local UUID from another warehouse remains inaccessible by IDOR."""

    allowed = await make_warehouse(db_session, name="Allowed")
    denied = await make_warehouse(db_session, name="Denied")
    app = _strict_application(_warehouse_user_claims(allowed.external_warehouse_id))

    async def session_override() -> AsyncIterator[AsyncSession]:
        """Share the rollback-isolated integration transaction."""

        yield db_session

    app.dependency_overrides[get_session] = session_override
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        allowed_response = await client.get(
            f"/api/warehouses/{allowed.id}",
            headers=_authorization(),
        )
        denied_response = await client.get(
            f"/api/warehouses/{denied.id}",
            headers=_authorization(),
        )
        edit_response = await client.patch(
            f"/api/warehouses/{allowed.id}",
            headers=_authorization(),
            json={"expected_version": allowed.version},
        )

    assert allowed_response.status_code == 200
    assert denied_response.status_code == 403
    assert denied_response.json()["code"] == "ACCESS_DENIED"
    assert edit_response.status_code == 403


@pytest.mark.integration
@pytest.mark.asyncio
async def test_plan_access_requires_grants_for_every_represented_warehouse(
    db_session: AsyncSession,
) -> None:
    """A grouped plan cannot leak regional tasks through an authorized root warehouse."""

    root = await make_warehouse(db_session, name="Root")
    represented = await make_warehouse(db_session, name="Represented")
    request = await make_request(
        db_session,
        represented,
        planning_date=date(2026, 8, 31),
    )
    plan = RoutePlan(
        warehouse_id=root.id,
        date=date(2026, 8, 31),
        name="Grouped plan",
    )
    db_session.add(plan)
    await db_session.flush()
    db_session.add(
        UnassignedTask(
            route_plan_id=plan.id,
            task_id=request.tasks[0].id,
            reason_codes=["NO_SHIFT_CAPACITY"],
            descriptions_ru=["Нет доступного водителя."],
        )
    )
    await db_session.flush()
    app = _strict_application(_warehouse_user_claims(root.external_warehouse_id))

    async def session_override() -> AsyncIterator[AsyncSession]:
        """Share the rollback-isolated integration transaction."""

        yield db_session

    app.dependency_overrides[get_session] = session_override
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get(
            f"/api/plans/{plan.id}",
            headers=_authorization(),
        )

    assert response.status_code == 403
    assert response.json()["code"] == "ACCESS_DENIED"


@pytest.mark.integration
@pytest.mark.asyncio
async def test_close_day_persists_authenticated_actor_not_client_input(
    db_session: AsyncSession,
) -> None:
    """Persist the signed subject snapshot as the closure actor."""

    planning_date = date(2026, 8, 31)
    warehouse = await make_warehouse(
        db_session,
        default_planning_date=planning_date,
    )
    planner = AsyncMock()
    planner.generate_plan.return_value = None
    app = create_app(
        planner_facade=planner,
        access_token_verifier=StaticAccessTokenVerifier(
            _warehouse_user_claims(warehouse.external_warehouse_id, level="EDIT"),
            require_token=True,
        ),
    )

    async def session_override() -> AsyncIterator[AsyncSession]:
        """Share the rollback-isolated integration transaction."""

        yield db_session

    app.dependency_overrides[get_session] = session_override
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(
            f"/api/warehouses/{warehouse.id}/planning-days/{planning_date}/close",
            headers=_authorization(),
        )

    assert response.status_code == 200, response.text
    closure = await db_session.scalar(
        select(PlanningDayClosure).where(
            PlanningDayClosure.warehouse_id == warehouse.id,
            PlanningDayClosure.date == planning_date,
        )
    )
    assert closure is not None
    assert closure.closed_by == f"test.operator<{TEST_USER_ID}>"


def test_client_cannot_supply_manual_or_confirmation_actor() -> None:
    """Keep audit identities outside public plan command DTOs."""

    with pytest.raises(ValidationError):
        ManualChangeRequest.model_validate(
            {
                "expected_version": 1,
                "change_type": "MOVE_TASK",
                "payload": {},
                "reason": "Test",
                "changed_by": "forged-user",
            }
        )
    with pytest.raises(ValidationError):
        ConfirmPlanRequest.model_validate(
            {
                "expected_version": 1,
                "accept_warnings": True,
                "confirmed_by": "forged-user",
            }
        )
