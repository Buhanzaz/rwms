"""HTTP boundary for dynamic delivery-slot calculation and versioned holds."""

from uuid import UUID

from fastapi import APIRouter

from app.api.authorization import require_local_warehouse_access, require_slot_hold_access
from app.api.dependencies import CurrentUserDep, SessionDep, SettingsDep
from app.schemas.slot_planning import (
    SlotAvailabilityRead,
    SlotAvailabilityRequest,
    SlotConfirmRead,
    SlotConfirmRequest,
    SlotHoldCreate,
    SlotHoldRead,
)
from app.security import WarehouseAccessLevel
from app.slot_planning.application import SlotPlanningApplication

router = APIRouter(prefix="/planning", tags=["slot-planning"])


@router.post("/slot-availability", response_model=SlotAvailabilityRead)
async def calculate_slot_availability(
    payload: SlotAvailabilityRequest,
    session: SessionDep,
    settings: SettingsDep,
    principal: CurrentUserDep,
) -> SlotAvailabilityRead:
    """Return only slots that pass complete truck schedule simulation."""

    await require_local_warehouse_access(
        session, principal, payload.warehouse_id, WarehouseAccessLevel.VIEW
    )
    return await SlotPlanningApplication(settings).calculate(session, payload)


@router.post("/slot-holds", response_model=SlotHoldRead, status_code=201)
async def hold_slot(
    payload: SlotHoldCreate,
    session: SessionDep,
    settings: SettingsDep,
    principal: CurrentUserDep,
) -> SlotHoldRead:
    """Recalculate and hold one best insertion for a bounded TTL."""

    await require_local_warehouse_access(
        session, principal, payload.warehouse_id, WarehouseAccessLevel.EDIT
    )
    return await SlotPlanningApplication(settings).create_hold(session, payload)


@router.post("/slot-holds/{hold_id}/confirm", response_model=SlotConfirmRead)
async def confirm_slot(
    hold_id: UUID,
    payload: SlotConfirmRequest,
    session: SessionDep,
    settings: SettingsDep,
    principal: CurrentUserDep,
) -> SlotConfirmRead:
    """Confirm a hold atomically after version and route revalidation."""

    await require_slot_hold_access(
        session, principal, hold_id, WarehouseAccessLevel.EDIT
    )
    return await SlotPlanningApplication(settings).confirm_hold(session, hold_id, payload)
