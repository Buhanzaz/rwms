"""Deterministic access-token verifier doubles for HTTP boundary tests."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any
from uuid import UUID

from app.security import CurrentUserPrincipal, unauthenticated

TEST_USER_ID = UUID("1e4a16d1-838f-47cb-b3c6-5edb01db9d41")


class StaticAccessTokenVerifier:
    """Return fixed already-verified claims with optional Bearer enforcement."""

    def __init__(
        self,
        claims: Mapping[str, Any],
        *,
        require_token: bool = False,
    ) -> None:
        self._claims = claims
        self._require_token = require_token

    async def verify(self, token: str | None) -> Mapping[str, Any]:
        """Return configured claims or reject a missing token in strict mode."""

        if self._require_token and token != "test-access-token":
            raise unauthenticated()
        return self._claims


def user_claims(
    *,
    global_role: str = "SYSTEM_ADMIN",
    scopes: str = "rwms.read rwms.write",
    warehouse_access: list[dict[str, str]] | None = None,
    client_id: str = "rwms-panel",
) -> dict[str, Any]:
    """Build canonical panel USER claims for one deterministic test operator."""

    return {
        "sub": str(TEST_USER_ID),
        "preferred_username": "test.operator",
        "principal_type": "USER",
        "client_id": client_id,
        "global_role": global_role,
        "scope": scopes,
        "warehouse_access": warehouse_access or [],
    }


def admin_access_token_verifier() -> StaticAccessTokenVerifier:
    """Allow legacy HTTP tests to execute as one signed global administrator."""

    return StaticAccessTokenVerifier(user_claims())


def admin_principal() -> CurrentUserPrincipal:
    """Build the authenticated administrator used by direct endpoint unit tests."""

    return CurrentUserPrincipal.from_claims(
        user_claims(),
        panel_client_id="rwms-panel",
    )


def administration_access_token_verifier(
    *,
    global_role: str = "SYSTEM_ADMIN",
    scopes: str = "openid profile offline_access admin.manage",
    require_token: bool = False,
) -> StaticAccessTokenVerifier:
    """Return the isolated administration-application verifier double."""

    return StaticAccessTokenVerifier(
        user_claims(
            global_role=global_role,
            scopes=scopes,
            client_id="rwms-admin-web",
        ),
        require_token=require_token,
    )
