"""Fail-closed authentication and warehouse authorization for operator APIs."""

from __future__ import annotations

import asyncio
from collections.abc import Collection, Mapping
from dataclasses import dataclass
from enum import IntEnum
from typing import Any, Protocol
from uuid import UUID

import jwt
from jwt import PyJWKClient
from jwt.exceptions import PyJWTError

from app.config import Settings
from app.errors import ApiError

_ADMIN_ROLES = frozenset({"SYSTEM_ADMIN", "WMS_ADMIN"})
_OPERATOR_ROLES = frozenset(
    {"SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER", "RENTAL_MANAGER", "VIEWER"}
)
_ADMIN_WEB_CLIENT_ID = "rwms-admin-web"
_INTERACTIVE_PROTOCOL_SCOPES = frozenset({"openid", "profile", "offline_access"})


class WarehouseAccessLevel(IntEnum):
    """Ordered effective authority for one canonical RWMS warehouse."""

    VIEW = 1
    EDIT = 2
    MANAGE = 3


class AccessTokenVerifier(Protocol):
    """Boundary for signature and registered-claim validation of one Bearer token."""

    async def verify(self, token: str | None) -> Mapping[str, Any]:
        """Return verified claims or raise a sanitized authentication error."""


class JwksAccessTokenVerifier:
    """Validate panel access tokens against the configured RWMS authorization server."""

    def __init__(self, settings: Settings) -> None:
        self._issuer = settings.auth_issuer
        self._audience = settings.auth_audience
        self._jwks = PyJWKClient(
            settings.effective_auth_jwks_url,
            cache_keys=True,
            max_cached_keys=16,
            lifespan=300,
            timeout=settings.auth_jwks_timeout_seconds,
        )

    async def verify(self, token: str | None) -> Mapping[str, Any]:
        """Validate a signed RS256 access token without blocking the event loop."""

        if token is None or not token.strip():
            raise unauthenticated()
        try:
            return await asyncio.to_thread(self._decode, token)
        except ApiError:
            raise
        except (PyJWTError, ValueError, OSError) as exc:
            raise unauthenticated() from exc

    def _decode(self, token: str) -> Mapping[str, Any]:
        """Resolve the cached signing key and validate registered JWT claims."""

        key = self._jwks.get_signing_key_from_jwt(token)
        claims = jwt.decode(
            token,
            key.key,
            algorithms=["RS256"],
            audience=self._audience,
            issuer=self._issuer,
            options={"require": ["exp", "iss", "aud", "sub"]},
        )
        if not isinstance(claims, Mapping):
            raise ValueError("JWT payload must be an object")
        return claims


@dataclass(frozen=True, slots=True)
class CurrentUserPrincipal:
    """Verified interactive-user identity and effective warehouse grants."""

    subject_id: UUID
    username: str
    global_role: str
    scopes: frozenset[str]
    warehouse_access: Mapping[UUID, WarehouseAccessLevel]

    @classmethod
    def from_claims(
        cls,
        claims: Mapping[str, Any],
        *,
        panel_client_id: str,
    ) -> CurrentUserPrincipal:
        """Build a strict operator principal from signed canonical RWMS claims."""

        if claims.get("principal_type") != "USER" or claims.get("client_id") != panel_client_id:
            raise forbidden("Доступ разрешён только пользователю панели RWMS.")
        try:
            subject_id = UUID(str(claims.get("sub", "")))
        except ValueError as exc:
            raise unauthenticated() from exc
        username = claims.get("preferred_username")
        global_role = claims.get("global_role")
        if not isinstance(username, str) or not username.strip():
            raise unauthenticated()
        if not isinstance(global_role, str) or global_role not in _OPERATOR_ROLES:
            raise forbidden("Роль пользователя не разрешает работу с логистикой.")  # noqa: RUF001
        return cls(
            subject_id=subject_id,
            username=username.strip(),
            global_role=global_role,
            scopes=_scopes(claims),
            warehouse_access=_warehouse_access(claims),
        )

    @classmethod
    def from_admin_claims(cls, claims: Mapping[str, Any]) -> CurrentUserPrincipal:
        """Build the isolated administration principal without granting planner scopes."""

        principal = cls.from_claims(claims, panel_client_id=_ADMIN_WEB_CLIENT_ID)
        business_scopes = principal.scopes.difference(_INTERACTIVE_PROTOCOL_SCOPES)
        if principal.global_role != "SYSTEM_ADMIN" or business_scopes != {"admin.manage"}:
            raise forbidden("Доступ разрешён только администратору RWMS.")
        return principal

    @property
    def audit_actor(self) -> str:
        """Return a signed, stable and readable actor reference for audit rows."""

        return f"{self.username}<{self.subject_id}>"

    @property
    def has_global_warehouse_access(self) -> bool:
        """Return whether the verified global role grants every warehouse."""

        return self.global_role in _ADMIN_ROLES

    def can_access(self, warehouse_id: UUID, required: WarehouseAccessLevel) -> bool:
        """Check one canonical warehouse grant without raising an exception."""

        if self.has_global_warehouse_access:
            return True
        granted = self.warehouse_access.get(warehouse_id)
        return granted is not None and granted >= required

    def require_scope(self, required_scope: str) -> None:
        """Require one signed OAuth scope from the interactive access token."""

        if required_scope not in self.scopes:
            raise forbidden("Недостаточно прав для этой операции.")

    def require_warehouse(self, warehouse_id: UUID, required: WarehouseAccessLevel) -> None:
        """Require a canonical warehouse grant at or above the requested level."""

        if not self.can_access(warehouse_id, required):
            raise forbidden("Нет доступа к выбранному складу.")


def unauthenticated() -> ApiError:
    """Create the uniform RFC Problem response for a missing or invalid Bearer."""

    return ApiError(
        401,
        "AUTHENTICATION_REQUIRED",
        "Войдите в RWMS, чтобы открыть логистику.",
        headers={"WWW-Authenticate": "Bearer"},
    )


def forbidden(detail: str) -> ApiError:
    """Create a sanitized warehouse/role authorization failure."""

    return ApiError(403, "ACCESS_DENIED", detail)


def _scopes(claims: Mapping[str, Any]) -> frozenset[str]:
    """Normalize Spring Authorization Server scope representations."""

    raw = claims.get("scope", claims.get("scp"))
    if isinstance(raw, str):
        return frozenset(item for item in raw.split() if item)
    if isinstance(raw, Collection) and not isinstance(raw, (str, bytes, Mapping)):
        return frozenset(item for item in raw if isinstance(item, str) and item)
    return frozenset()


def _warehouse_access(claims: Mapping[str, Any]) -> Mapping[UUID, WarehouseAccessLevel]:
    """Parse signed grants and ignore malformed entries without granting access."""

    parsed: dict[UUID, WarehouseAccessLevel] = {}
    raw = claims.get("warehouse_access")
    if not isinstance(raw, Collection) or isinstance(raw, (str, bytes, Mapping)):
        return parsed
    for entry in raw:
        if not isinstance(entry, Mapping):
            continue
        try:
            warehouse_id = UUID(str(entry.get("warehouseId", "")))
            level = WarehouseAccessLevel[str(entry.get("level", ""))]
        except (ValueError, KeyError):
            continue
        previous = parsed.get(warehouse_id)
        if previous is None or level > previous:
            parsed[warehouse_id] = level
    return parsed
