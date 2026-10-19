"""Authenticated facade for owner-calculated rescheduling of existing deliveries."""

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Header

from app.api.authorization import require_owned_entity_access
from app.api.dependencies import CapacityRwmsClientDep, CurrentUserDep, SessionDep
from app.models import LogisticsRequest
from app.schemas.operations import (
    RequestRescheduleApply,
    RequestRescheduleOptionsQuery,
    RequestRescheduleOptionsRead,
    RequestRescheduleResultRead,
    RequestRescheduleRetry,
)
from app.security import WarehouseAccessLevel
from app.services.request_rescheduling import ExistingRequestReschedulingService

router = APIRouter(tags=["request-rescheduling"])
IdempotencyKey = Annotated[UUID, Header(alias="Idempotency-Key")]


@router.post(
    "/requests/{request_id}/reschedule-options",
    response_model=RequestRescheduleOptionsRead,
)
async def request_reschedule_options(
    request_id: UUID,
    payload: RequestRescheduleOptionsQuery,
    session: SessionDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> RequestRescheduleOptionsRead:
    """Calculate all current owner slots for the dispatcher-selected date."""

    await require_owned_entity_access(
        session,
        principal,
        LogisticsRequest,
        request_id,
        "request",
        WarehouseAccessLevel.EDIT,
    )
    return await ExistingRequestReschedulingService(client).options(
        session,
        request_id,
        payload,
    )


@router.post(
    "/requests/{request_id}/reschedule",
    response_model=RequestRescheduleResultRead,
)
async def reschedule_request(
    request_id: UUID,
    payload: RequestRescheduleApply,
    idempotency_key: IdempotencyKey,
    session: SessionDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> RequestRescheduleResultRead:
    """Apply one explicitly selected owner slot and converge the local request."""

    await require_owned_entity_access(
        session,
        principal,
        LogisticsRequest,
        request_id,
        "request",
        WarehouseAccessLevel.EDIT,
    )
    return await ExistingRequestReschedulingService(client).apply(
        session,
        request_id,
        payload,
        actor_subject_id=principal.subject_id,
        idempotency_key=idempotency_key,
    )


@router.post(
    "/requests/{request_id}/reschedule-retry",
    response_model=RequestRescheduleResultRead,
)
async def retry_quarantined_request_reschedule(
    request_id: UUID,
    payload: RequestRescheduleRetry,
    idempotency_key: IdempotencyKey,
    session: SessionDep,
    client: CapacityRwmsClientDep,
    principal: CurrentUserDep,
) -> RequestRescheduleResultRead:
    """Safely replay the persisted owner command after bounded recovery quarantines it."""

    await require_owned_entity_access(
        session,
        principal,
        LogisticsRequest,
        request_id,
        "request",
        WarehouseAccessLevel.EDIT,
    )
    return await ExistingRequestReschedulingService(client).retry_quarantined(
        session,
        request_id,
        payload,
        requested_by=principal.subject_id,
        idempotency_key=idempotency_key,
    )
