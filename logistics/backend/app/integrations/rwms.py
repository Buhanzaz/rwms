"""Authenticated HTTP adapter for the RWMS logistics planning boundary."""

from __future__ import annotations

import asyncio
import logging
import re
from collections.abc import Mapping
from datetime import datetime
from functools import lru_cache
from time import monotonic
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict, Field

from app.config import Settings
from app.errors import ApiError
from app.schemas.domain import (
    RwmsApplyResult,
    RwmsAssignmentsCommand,
    RwmsCapacitySnapshotCommand,
    RwmsCapacitySnapshotResult,
    RwmsDriverIdentity,
    RwmsPlanningAssignmentStatusFeed,
    RwmsPlanningFeed,
    RwmsWarehouseIdentity,
    RwmsWarehouseSupportLink,
)

RWMS_PLANNING_SCOPE = "logistics.planning"
_UPSTREAM_CODE = re.compile(r"^[A-Z][A-Z0-9_]{0,95}$")
logger = logging.getLogger(__name__)


class _TokenResponse(BaseModel):
    """Minimal OAuth client-credentials response retained only in memory."""

    model_config = ConfigDict(extra="ignore")

    access_token: str = Field(min_length=1)
    token_type: str = "Bearer"
    expires_in: float = Field(gt=0)


class RwmsPlanningClient:
    """Fetch RWMS planning inputs and submit versioned assignments with OAuth2."""

    def __init__(
        self,
        settings: Settings,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._enabled = settings.rwms_sync_enabled
        self._capacity_publish_enabled = settings.rwms_capacity_publish_enabled
        self._base_url = settings.rwms_logistics_base_url
        self._token_url = settings.rwms_token_url
        self._client_id = settings.rwms_client_id
        self._client_secret = settings.rwms_client_secret
        self._timeout = settings.rwms_timeout_seconds
        self._transport = transport
        self._access_token: str | None = None
        self._token_expires_at = 0.0
        self._token_lock = asyncio.Lock()

    def ensure_enabled(self) -> None:
        """Fail explicitly when the integration was not enabled by configuration."""

        if not self._enabled:
            raise ApiError(
                503,
                "RWMS_SYNC_DISABLED",
                "RWMS synchronization is disabled by runtime configuration",
            )

    def ensure_capacity_publish_enabled(self) -> None:
        """Fail explicitly when planning-capacity publication is not opted in."""

        if not self._capacity_publish_enabled:
            raise ApiError(
                503,
                "RWMS_CAPACITY_PUBLISH_DISABLED",
                "RWMS capacity publication is disabled by runtime configuration",
            )
        self.ensure_enabled()

    async def get_planning_requests(
        self,
        *,
        warehouse_id: object,
        date_from: object,
        date_to: object,
    ) -> RwmsPlanningFeed:
        """Read a warehouse/date-bounded planning feed from logistics-service."""

        self.ensure_enabled()
        response = await self._authorized_request(
            "GET",
            "/api/internal/logistics/v1/planning/requests",
            params={
                "warehouseId": str(warehouse_id),
                "dateFrom": str(date_from),
                "dateTo": str(date_to),
            },
        )
        return self._validate_response(response, RwmsPlanningFeed, "RWMS_PLANNING_RESPONSE_INVALID")

    async def list_warehouses(self) -> list[RwmsWarehouseIdentity]:
        """Read canonical warehouse identities available to the planning client."""

        self.ensure_enabled()
        response = await self._authorized_request(
            "GET", "/api/internal/logistics/v1/planning/warehouses"
        )
        warehouses = self._validate_response_list(
            response,
            RwmsWarehouseIdentity,
            "RWMS_WAREHOUSE_DIRECTORY_RESPONSE_INVALID",
        )
        if len({warehouse.warehouse_id for warehouse in warehouses}) != len(warehouses):
            raise ApiError(
                502,
                "RWMS_WAREHOUSE_DIRECTORY_RESPONSE_INVALID",
                "RWMS logistics-service response contains duplicate warehouses",
            )
        return warehouses

    async def list_support_links(
        self,
        served_warehouse_id: UUID,
        *,
        at: datetime,
    ) -> list[RwmsWarehouseSupportLink]:
        """Read owner-filtered support links for one exact timezone-aware planning instant."""

        if at.utcoffset() is None:
            raise ValueError("at must include a UTC offset")
        self.ensure_enabled()
        response = await self._authorized_request(
            "GET",
            (
                "/api/internal/logistics/v1/planning/warehouses/"
                f"{served_warehouse_id}/support-links"
            ),
            params={"at": at.isoformat()},
        )
        links = self._validate_response_list(
            response,
            RwmsWarehouseSupportLink,
            "RWMS_WAREHOUSE_SUPPORT_LINK_RESPONSE_INVALID",
        )
        if len({link.support_link_id for link in links}) != len(links):
            raise ApiError(
                502,
                "RWMS_WAREHOUSE_SUPPORT_LINK_RESPONSE_INVALID",
                "RWMS logistics-service response contains duplicate support links",
            )
        if any(link.served_warehouse.warehouse_id != served_warehouse_id for link in links):
            raise ApiError(
                502,
                "RWMS_WAREHOUSE_SUPPORT_LINK_RESPONSE_INVALID",
                "RWMS logistics-service response belongs to a different served warehouse",
            )
        return links

    async def list_support_network(
        self,
        warehouse_id: UUID,
    ) -> list[RwmsWarehouseSupportLink]:
        """Read active directed support edges adjacent to one canonical warehouse."""

        self.ensure_enabled()
        response = await self._authorized_request(
            "GET",
            (
                "/api/internal/logistics/v1/planning/warehouses/"
                f"{warehouse_id}/support-network"
            ),
        )
        links = self._validate_response_list(
            response,
            RwmsWarehouseSupportLink,
            "RWMS_WAREHOUSE_SUPPORT_NETWORK_RESPONSE_INVALID",
        )
        if len({link.support_link_id for link in links}) != len(links):
            raise ApiError(
                502,
                "RWMS_WAREHOUSE_SUPPORT_NETWORK_RESPONSE_INVALID",
                "RWMS logistics-service response contains duplicate support links",
            )
        if any(
            warehouse_id
            not in {
                link.support_warehouse.warehouse_id,
                link.served_warehouse.warehouse_id,
            }
            for link in links
        ):
            raise ApiError(
                502,
                "RWMS_WAREHOUSE_SUPPORT_NETWORK_RESPONSE_INVALID",
                "RWMS logistics-service response contains a disconnected support link",
            )
        return links

    async def list_drivers(
        self,
        warehouse_id: UUID,
        *,
        at: datetime | None = None,
        include_incoming: bool = False,
    ) -> list[RwmsDriverIdentity]:
        """Read canonical workers eligible at an optional exact planning instant."""

        if at is not None and at.utcoffset() is None:
            raise ValueError("at must include a UTC offset")

        self.ensure_enabled()
        params = {
            "warehouseId": str(warehouse_id),
            "includeIncoming": "true" if include_incoming else "false",
        }
        if at is not None:
            params["at"] = at.isoformat()
        response = await self._authorized_request(
            "GET",
            "/api/internal/logistics/v1/planning/drivers",
            params=params,
        )
        drivers = self._validate_response_list(
            response,
            RwmsDriverIdentity,
            "RWMS_DRIVER_DIRECTORY_RESPONSE_INVALID",
        )
        if len({driver.worker_id for driver in drivers}) != len(drivers):
            raise ApiError(
                502,
                "RWMS_DRIVER_DIRECTORY_RESPONSE_INVALID",
                "RWMS logistics-service response contains duplicate drivers",
            )
        return drivers

    async def apply_assignments(
        self,
        command: RwmsAssignmentsCommand,
        *,
        idempotency_key: str,
    ) -> RwmsApplyResult:
        """Submit assignments while preserving every applied and rejected outcome."""

        self.ensure_enabled()
        response = await self._authorized_request(
            "POST",
            "/api/internal/logistics/v1/planning/assignments",
            headers={"Idempotency-Key": idempotency_key},
            json_body=command.model_dump(mode="json", by_alias=True),
        )
        return self._validate_response(response, RwmsApplyResult, "RWMS_APPLY_RESPONSE_INVALID")

    async def get_assignment_statuses(
        self,
        *,
        warehouse_id: object,
        date: object,
    ) -> RwmsPlanningAssignmentStatusFeed:
        """Read current RWMS ownership for shipment parts on one warehouse-local date."""

        self.ensure_enabled()
        response = await self._authorized_request(
            "GET",
            "/api/internal/logistics/v1/planning/assignments",
            params={"warehouseId": str(warehouse_id), "date": str(date)},
        )
        return self._validate_response(
            response,
            RwmsPlanningAssignmentStatusFeed,
            "RWMS_ASSIGNMENT_STATUS_RESPONSE_INVALID",
        )

    async def replace_capacity_snapshot(
        self,
        warehouse_id: UUID,
        command: RwmsCapacitySnapshotCommand,
        *,
        idempotency_key: UUID,
    ) -> RwmsCapacitySnapshotResult:
        """Replace one warehouse's active anonymous capacity projection idempotently."""

        self.ensure_capacity_publish_enabled()
        response = await self._authorized_request(
            "PUT",
            f"/api/internal/logistics/v1/planning/capacity-snapshots/{warehouse_id}",
            headers={"Idempotency-Key": str(idempotency_key)},
            json_body=command.model_dump(mode="json", by_alias=True),
        )
        return self._validate_response(
            response,
            RwmsCapacitySnapshotResult,
            "RWMS_CAPACITY_RESPONSE_INVALID",
        )

    async def _authorized_request(
        self,
        method: str,
        path: str,
        *,
        params: Mapping[str, str] | None = None,
        headers: Mapping[str, str] | None = None,
        json_body: object | None = None,
    ) -> httpx.Response:
        """Execute one bounded Bearer request without exposing token material in errors."""

        token = await self._get_access_token()
        assert self._base_url is not None
        request_headers = dict(headers or {})
        request_headers["Authorization"] = f"Bearer {token}"
        try:
            async with httpx.AsyncClient(
                transport=self._transport,
                timeout=self._timeout,
                follow_redirects=False,
            ) as client:
                response = await client.request(
                    method,
                    f"{self._base_url}{path}",
                    params=params,
                    headers=request_headers,
                    json=json_body,
                )
        except httpx.RequestError as exc:
            raise ApiError(
                502,
                "RWMS_REQUEST_FAILED",
                "RWMS logistics-service could not be reached",
            ) from exc
        if response.is_error:
            upstream_code = self._safe_problem_code(response)
            logger.warning(
                "RWMS logistics-service rejected %s %s with status=%s code=%s",
                method,
                path,
                response.status_code,
                upstream_code or "UNKNOWN",
            )
            raise ApiError(
                502,
                f"RWMS_{upstream_code}" if upstream_code is not None else "RWMS_REQUEST_FAILED",
                "RWMS logistics-service rejected the request",
                extra={
                    "upstream_status": response.status_code,
                    **(
                        {"upstream_code": upstream_code}
                        if upstream_code is not None
                        else {}
                    ),
                },
            )
        return response

    @staticmethod
    def _safe_problem_code(response: httpx.Response) -> str | None:
        """Extract only a bounded machine code from upstream Problem Details."""

        try:
            payload = response.json()
        except ValueError:
            return None
        if not isinstance(payload, dict):
            return None
        candidate = payload.get("code") or payload.get("title")
        if not isinstance(candidate, str) or _UPSTREAM_CODE.fullmatch(candidate) is None:
            return None
        return candidate

    async def _get_access_token(self) -> str:
        """Return a cached token, refreshing it before its expiry margin."""

        self.ensure_enabled()
        now = monotonic()
        if self._access_token is not None and now < self._token_expires_at:
            return self._access_token
        async with self._token_lock:
            now = monotonic()
            if self._access_token is not None and now < self._token_expires_at:
                return self._access_token
            token = await self._request_access_token()
            margin = min(30.0, max(1.0, token.expires_in * 0.1))
            self._access_token = token.access_token
            self._token_expires_at = monotonic() + max(0.0, token.expires_in - margin)
            return token.access_token

    async def _request_access_token(self) -> _TokenResponse:
        """Request the fixed planning scope using the registered Basic client authentication."""

        assert self._token_url is not None
        assert self._client_secret is not None
        try:
            async with httpx.AsyncClient(
                transport=self._transport,
                timeout=self._timeout,
                follow_redirects=False,
            ) as client:
                response = await client.post(
                    self._token_url,
                    auth=httpx.BasicAuth(self._client_id, self._client_secret),
                    data={
                        "grant_type": "client_credentials",
                        "scope": RWMS_PLANNING_SCOPE,
                    },
                )
        except httpx.RequestError as exc:
            raise ApiError(
                502,
                "RWMS_TOKEN_REQUEST_FAILED",
                "RWMS OAuth token endpoint could not be reached",
            ) from exc
        if response.is_error:
            raise ApiError(
                502,
                "RWMS_TOKEN_REQUEST_FAILED",
                "RWMS OAuth token endpoint rejected the request",
                extra={"upstream_status": response.status_code},
            )
        try:
            return _TokenResponse.model_validate(response.json())
        except ValueError as exc:
            raise ApiError(
                502,
                "RWMS_TOKEN_RESPONSE_INVALID",
                "RWMS OAuth token response is invalid",
            ) from exc

    @staticmethod
    def _validate_response[ResponseModel: BaseModel](
        response: httpx.Response,
        model: type[ResponseModel],
        code: str,
    ) -> ResponseModel:
        """Validate upstream JSON instead of accepting partial or fabricated data."""

        try:
            return model.model_validate(response.json())
        except ValueError as exc:
            raise ApiError(502, code, "RWMS logistics-service response is invalid") from exc

    @staticmethod
    def _validate_response_list[ResponseModel: BaseModel](
        response: httpx.Response,
        model: type[ResponseModel],
        code: str,
    ) -> list[ResponseModel]:
        """Validate a strict upstream JSON array without accepting partial entries."""

        try:
            payload = response.json()
            if not isinstance(payload, list):
                raise ValueError("response must be a JSON array")
            return [model.model_validate(item) for item in payload]
        except ValueError as exc:
            raise ApiError(502, code, "RWMS logistics-service response is invalid") from exc


@lru_cache(maxsize=8)
def _cached_rwms_client(
    enabled: bool,
    capacity_publish_enabled: bool,
    base_url: str | None,
    token_url: str | None,
    client_id: str,
    client_secret: str | None,
    timeout: float,
) -> RwmsPlanningClient:
    """Retain the in-memory token cache for one immutable configuration tuple."""

    settings = Settings(
        rwms_sync_enabled=enabled,
        rwms_capacity_publish_enabled=capacity_publish_enabled,
        rwms_logistics_base_url=base_url,
        rwms_token_url=token_url,
        rwms_client_id=client_id,
        rwms_client_secret=client_secret,
        rwms_timeout_seconds=timeout,
    )
    return RwmsPlanningClient(settings)


def get_rwms_planning_client(settings: Settings) -> RwmsPlanningClient:
    """Resolve the process-wide client and its bounded OAuth token cache."""

    return _cached_rwms_client(
        settings.rwms_sync_enabled,
        settings.rwms_capacity_publish_enabled,
        settings.rwms_logistics_base_url,
        settings.rwms_token_url,
        settings.rwms_client_id,
        settings.rwms_client_secret,
        settings.rwms_timeout_seconds,
    )
