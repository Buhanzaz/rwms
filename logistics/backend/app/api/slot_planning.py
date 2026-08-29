"""HTTP boundary for dynamic delivery-slot calculation and versioned holds."""

from uuid import UUID

from fastapi import APIRouter

from app.api.dependencies import SessionDep, SettingsDep
from app.schemas.slot_planning import (
    SlotAvailabilityRead,
    SlotAvailabilityRequest,
    SlotConfirmRead,
    SlotConfirmRequest,
    SlotHoldCreate,
    SlotHoldRead,
)
from app.slot_planning.application import SlotPlanningApplication

router = APIRouter(prefix="/planning", tags=["slot-planning"])


@router.post("/slot-availability", response_model=SlotAvailabilityRead)
async def calculate_slot_availability(
    payload: SlotAvailabilityRequest,
    session: SessionDep,
    settings: SettingsDep,
) -> SlotAvailabilityRead:
    """Return only slots that pass complete truck schedule simulation."""

    return await SlotPlanningApplication(settings).calculate(session, payload)


@router.post("/slot-holds", response_model=SlotHoldRead, status_code=201)
async def hold_slot(
    payload: SlotHoldCreate,
    session: SessionDep,
    settings: SettingsDep,
) -> SlotHoldRead:
    """Recalculate and hold one best insertion for a bounded TTL."""

    return await SlotPlanningApplication(settings).create_hold(session, payload)


@router.post("/slot-holds/{hold_id}/confirm", response_model=SlotConfirmRead)
async def confirm_slot(
    hold_id: UUID,
    payload: SlotConfirmRequest,
    session: SessionDep,
    settings: SettingsDep,
) -> SlotConfirmRead:
    """Confirm a hold atomically after version and route revalidation."""

    return await SlotPlanningApplication(settings).confirm_hold(session, hold_id, payload)
