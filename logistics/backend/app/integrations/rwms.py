"""Authenticated HTTP adapter for the RWMS logistics planning boundary."""

from __future__ import annotations

import asyncio
import logging
import re
from collections.abc import Mapping
from datetime import UTC, datetime
from functools import lru_cache
from time import monotonic
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

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
    RwmsReplacePlanningAssignmentsCommand,
    RwmsReplacePlanningAssignmentsResult,
    RwmsVehicleOperationalAssignment,
    RwmsWarehouseIdentity,
    RwmsWarehouseSupportLink,
)
from app.schemas.operations import (
    RwmsPlanningBaseTask,
    RwmsPublishedAssignmentWithdrawal,
    RwmsPublishedAssignmentWithdrawalResult,
    RwmsRescheduleCommand,
    RwmsRescheduleOptions,
    RwmsRescheduleResult,
)

RWMS_PLANNING_SCOPE = "logistics.planning"
_UPSTREAM_CODE = re.compile(r"^[A-Z][A-Z0-9_]{0,95}$")
logger = logging.getLogger(__name__)


class _TokenResponse(BaseModel):
    """Minimal OAuth client-credentials response retained only in memory."""

    model_config = ConfigDict(extra="ignore", hide_input_in_errors=True)

    access_token: str = Field(min_length=1)
    token_type: str = "Bearer"
    expires_in: float = Field(gt=0)


def _safe_validation_diagnostics(error: ValidationError) -> dict[str, object]:
    """Return bounded validation metadata without preserving upstream values or keys."""

    diagnostics: list[dict[str, object]] = []
    for item in error.errors(include_context=False, include_input=False, include_url=False)[:10]:
        location = item.get("loc", ())
        diagnostics.append(
            {
                "location": [
                    "index" if isinstance(part, int) else "field" for part in location[:8]
                ],
                "type": item.get("type", "validation_error"),
            }
        )
    return {"validation_errors": diagnostics}


def _invalid_upstream_response(code: str, detail: str, error: ValueError) -> ApiError:
    """Translate malformed upstream input without retaining its body or exception chain."""

    extra = _safe_validation_diagnostics(error) if isinstance(error, ValidationError) else None
    return ApiError(502, code, detail, extra=extra)


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
            (f"/api/internal/logistics/v1/planning/warehouses/{served_warehouse_id}/support-links"),
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
            (f"/api/internal/logistics/v1/planning/warehouses/{warehouse_id}/support-network"),
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

    async def list_vehicle_assignments(
        self,
        warehouse_id: UUID,
        *,
        window_start: datetime,
        window_end: datetime,
    ) -> list[RwmsVehicleOperationalAssignment]:
        """Read bounded vehicle placement facts and reject contradictory upstream state."""

        if window_start.utcoffset() is None or window_end.utcoffset() is None:
            raise ValueError("vehicle assignment window must include UTC offsets")
        if window_start >= window_end:
            raise ValueError("vehicle assignment windowStart must precede windowEnd")
        self.ensure_enabled()
        response = await self._authorized_request(
            "GET",
            "/api/internal/logistics/v1/planning/vehicle-assignments",
            params={
                "warehouseId": str(warehouse_id),
                "windowStart": window_start.isoformat(),
                "windowEnd": window_end.isoformat(),
            },
        )
        assignments = self._validate_response_list(
            response,
            RwmsVehicleOperationalAssignment,
            "RWMS_VEHICLE_ASSIGNMENT_RESPONSE_INVALID",
        )
        self._validate_vehicle_assignment_snapshot(
            assignments,
            window_end=window_end,
        )
        return assignments

    async def list_base_tasks(
        self,
        warehouse_id: UUID,
        *,
        available_at: datetime,
        limit: int = 20,
    ) -> list[RwmsPlanningBaseTask]:
        """Read existing low-priority base work without creating planner-owned duplicates."""

        if available_at.utcoffset() is None:
            raise ValueError("available_at must include a UTC offset")
        if not 1 <= limit <= 100:
            raise ValueError("limit must be between 1 and 100")
        self.ensure_enabled()
        response = await self._authorized_request(
            "GET",
            "/api/internal/logistics/v1/planning/base-tasks",
            params={
                "warehouseId": str(warehouse_id),
                "availableAt": available_at.isoformat(),
                "limit": str(limit),
            },
        )
        tasks = self._validate_response_list(
            response,
            RwmsPlanningBaseTask,
            "RWMS_BASE_TASK_RESPONSE_INVALID",
        )
        if len({task.task_id for task in tasks}) != len(tasks):
            raise ApiError(
                502,
                "RWMS_BASE_TASK_RESPONSE_INVALID",
                "RWMS logistics-service response contains duplicate base tasks",
            )
        return tasks

    async def get_reschedule_options(
        self,
        order_id: UUID,
        *,
        expected_order_version: int,
    ) -> RwmsRescheduleOptions:
        """Read authoritative slot choices before proposing a customer date change."""

        if expected_order_version < 0:
            raise ValueError("expected_order_version must be non-negative")
        self.ensure_enabled()
        response = await self._authorized_request(
            "GET",
            f"/api/internal/logistics/v1/planning/orders/{order_id}/reschedule-options",
            params={"expectedOrderVersion": str(expected_order_version)},
        )
        result = self._validate_response(
            response,
            RwmsRescheduleOptions,
            "RWMS_RESCHEDULE_OPTIONS_RESPONSE_INVALID",
        )
        if result.order_id != order_id or result.order_version != expected_order_version:
            raise ApiError(
                502,
                "RWMS_RESCHEDULE_OPTIONS_RESPONSE_INVALID",
                "RWMS reschedule options do not match the requested order fence",
            )
        return result

    async def reschedule_order(
        self,
        order_id: UUID,
        command: RwmsRescheduleCommand,
        *,
        idempotency_key: str,
    ) -> RwmsRescheduleResult:
        """Apply one customer-agreed date through the authoritative order owner."""

        self.ensure_enabled()
        response = await self._authorized_request(
            "POST",
            f"/api/internal/logistics/v1/planning/orders/{order_id}/reschedule",
            headers={"Idempotency-Key": idempotency_key},
            json_body=command.model_dump(mode="json", by_alias=True),
        )
        result = self._validate_response(
            response,
            RwmsRescheduleResult,
            "RWMS_RESCHEDULE_RESPONSE_INVALID",
        )
        if result.order_id != order_id:
            raise ApiError(
                502,
                "RWMS_RESCHEDULE_RESPONSE_INVALID",
                "RWMS reschedule result belongs to another order",
            )
        return result

    @staticmethod
    def _validate_vehicle_assignment_snapshot(
        assignments: list[RwmsVehicleOperationalAssignment],
        *,
        window_end: datetime,
    ) -> None:
        """Fail closed on duplicate, out-of-window, or contradictory chain facts."""

        invalid = any(
            assignment.status not in {"PLANNED", "IN_TRANSIT", "ACTIVE"}
            or assignment.travel_starts_at >= window_end
            for assignment in assignments
        )
        duplicate_ids = len({item.assignment_id for item in assignments}) != len(assignments)
        contradictory = False
        if not duplicate_ids:
            from app.services.vehicle_availability import (
                VehicleAssignmentDataError,
                merge_vehicle_assignments,
            )

            try:
                merge_vehicle_assignments(
                    (assignments,),
                    observed_at=datetime.min.replace(tzinfo=UTC),
                )
            except VehicleAssignmentDataError:
                contradictory = True
        if invalid or duplicate_ids or contradictory:
            raise ApiError(
                502,
                "RWMS_VEHICLE_ASSIGNMENT_RESPONSE_INVALID",
                "RWMS logistics-service vehicle assignment response is invalid",
            )

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

    async def replace_assignments(
        self,
        source_plan_id: UUID,
        command: RwmsReplacePlanningAssignmentsCommand,
        *,
        idempotency_key: UUID,
    ) -> RwmsReplacePlanningAssignmentsResult:
        """Atomically replace one complete still-unstarted published plan revision."""

        self.ensure_enabled()
        response = await self._authorized_request(
            "PUT",
            f"/api/internal/logistics/v1/planning/assignments/{source_plan_id}",
            headers={"Idempotency-Key": str(idempotency_key)},
            json_body=command.model_dump(mode="json", by_alias=True),
        )
        result = self._validate_response(
            response,
            RwmsReplacePlanningAssignmentsResult,
            "RWMS_ASSIGNMENT_REPLACEMENT_RESPONSE_INVALID",
        )
        requested_ids = {item.external_task_id for item in command.assignments}
        returned_ids = {item.external_task_id for item in result.assignments}
        if (
            result.source_plan_id != source_plan_id
            or result.source_plan_version != command.replacement_plan_version
            or result.warehouse_id != command.warehouse_id
            or result.date != command.date
            or returned_ids != requested_ids
        ):
            raise ApiError(
                502,
                "RWMS_ASSIGNMENT_REPLACEMENT_RESPONSE_INVALID",
                "RWMS assignment replacement response does not match the submitted revision",
            )
        return result

    async def withdraw_cancelled_assignment(
        self,
        source_plan_id: UUID,
        command: RwmsPublishedAssignmentWithdrawal,
        *,
        idempotency_key: UUID,
    ) -> RwmsPublishedAssignmentWithdrawalResult:
        """Withdraw one owner-cancelled member through the durable published-plan saga."""

        self.ensure_enabled()
        response = await self._authorized_request(
            "POST",
            (
                "/api/internal/logistics/v1/planning/assignments/"
                f"{source_plan_id}/withdraw-cancelled"
            ),
            headers={"Idempotency-Key": str(idempotency_key)},
            json_body=command.model_dump(mode="json", by_alias=True),
        )
        result = self._validate_response(
            response,
            RwmsPublishedAssignmentWithdrawalResult,
            "RWMS_CANCELLATION_WITHDRAWAL_RESPONSE_INVALID",
        )
        if (
            command.source_plan_id != source_plan_id
            or result.source_plan_id != source_plan_id
            or result.source_plan_version != command.replacement_plan_version
            or result.removed_external_task_id != command.removed_assignment.external_task_id
            or result.state != "COMPLETE"
        ):
            raise ApiError(
                502,
                "RWMS_CANCELLATION_WITHDRAWAL_RESPONSE_INVALID",
                "RWMS cancellation withdrawal response does not match the submitted revision",
            )
        return result

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
                    **({"upstream_code": upstream_code} if upstream_code is not None else {}),
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
            raise _invalid_upstream_response(
                "RWMS_TOKEN_RESPONSE_INVALID",
                "RWMS OAuth token response is invalid",
                exc,
            ) from None

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
            raise _invalid_upstream_response(
                code,
                "RWMS logistics-service response is invalid",
                exc,
            ) from None

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
            raise _invalid_upstream_response(
                code,
                "RWMS logistics-service response is invalid",
                exc,
            ) from None


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
