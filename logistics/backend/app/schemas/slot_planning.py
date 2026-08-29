"""Typed HTTP contracts for dynamic customer slot calculation and holding."""

from __future__ import annotations

from datetime import date, time
from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import AwareDatetime, Field, StringConstraints, model_validator

from app.schemas.domain import ApiModel

type NonBlank = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]


class SlotAvailabilityRequest(ApiModel):
    """Address and cabin facts needed to calculate three exact customer slots."""

    warehouse_id: UUID
    date: date
    address: NonBlank
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    cabin_count: int = Field(ge=1)
    site_cabin_capacity: int = Field(ge=1, le=2)
    service_duration_minutes: int | None = Field(default=None, ge=30, le=240)

class SlotTimelineStopRead(ApiModel):
    """One movement, wait, service, or warehouse operation in the best timeline."""

    id: str
    stop_type: Literal[
        "WAREHOUSE_LOAD",
        "DELIVERY",
        "PICKUP",
        "WAREHOUSE_UNLOAD",
        "WAREHOUSE_FINISH",
    ]
    task_id: str | None
    latitude: float
    longitude: float
    arrival: AwareDatetime
    service_start: AwareDatetime
    service_end: AwareDatetime
    departure: AwareDatetime
    waiting_minutes: int = Field(ge=0)
    load_before: int = Field(ge=0, le=2)
    load_after: int = Field(ge=0, le=2)
    service_minutes: int = Field(ge=0)
    locked: bool


class SlotBestCandidateRead(ApiModel):
    """Dispatcher-safe metrics and focused route detail for the best insertion."""

    driver_id: str
    shift_id: str
    vehicle_id: str
    resource_origin_warehouse_id: UUID | None
    available_from: AwareDatetime
    employment_type: Literal["STAFF", "CONTRACTOR"]
    availability_kind: Literal["HOME", "ACTIVE_ASSIGNMENT", "INCOMING"]
    support_link_id: UUID | None
    return_required: bool
    reason_codes: list[str] = Field(default_factory=list)
    trip_id: str
    insert_after_stop_id: str | None
    insert_before_stop_id: str | None
    estimated_service_start: AwareDatetime
    estimated_finish: AwareDatetime
    warehouse_return_time: AwareDatetime
    minimum_slack_minutes: int = Field(ge=0)
    incremental_travel_minutes: int = Field(ge=0)
    incremental_distance_meters: int = Field(ge=0)
    waiting_minutes: int = Field(ge=0)
    pickup_count: int = Field(ge=0, le=2)
    affected_stops: list[str]
    timeline: list[SlotTimelineStopRead]
    route_before: dict[str, Any] | None = None
    route_after: dict[str, Any] | None = None
    pickup_candidates_geojson: dict[str, Any] | None = None


class CustomerSlotRead(ApiModel):
    """One standard customer slot with feasibility and structured explanations."""

    start: time
    end: time
    status: Literal["AVAILABLE", "UNAVAILABLE"]
    candidate_count: int = Field(ge=0)
    best_candidate: SlotBestCandidateRead | None
    reasons: list[str] = Field(default_factory=list)
    explanation: list[str] = Field(default_factory=list)


class SlotAvailabilityRead(ApiModel):
    """Complete three-slot answer and price classified independently from routing."""

    date: date
    plan_version: int = Field(ge=1)
    delivery_price_rubles: int | None = Field(default=None, ge=0)
    price_isochrone_minutes: Literal[60, 120, 180, 240] | None = None
    price_zone_id: UUID | None = None
    price_zone_name: str | None = None
    trailer_access_allowed: bool = True
    slots: list[CustomerSlotRead] = Field(min_length=3, max_length=3)


class SlotHoldCreate(SlotAvailabilityRequest):
    """Command to recalculate and temporarily hold one available insertion."""

    slot_start: time
    slot_end: time
    client_session_id: NonBlank

    @model_validator(mode="after")
    def validate_slot(self) -> SlotHoldCreate:
        """Require an ordered interval; warehouse configuration validates membership."""

        if self.slot_start >= self.slot_end:
            raise ValueError("slot_start must precede slot_end")
        return self


class SlotHoldRead(ApiModel):
    """Expiring plan-versioned hold returned after successful recalculation."""

    hold_id: UUID
    status: Literal["HELD", "CONFIRMED", "EXPIRED"]
    plan_version: int = Field(ge=1)
    expires_at: AwareDatetime
    slot_start: time
    slot_end: time
    delivery_price_rubles: int | None = Field(default=None, ge=0)
    price_isochrone_minutes: Literal[60, 120, 180, 240] | None = None
    price_zone_id: UUID | None = None
    trailer_access_allowed: bool = True


class SlotConfirmRequest(ApiModel):
    """Idempotent confirmation key for one previously calculated slot hold."""

    confirmation_key: UUID


class SlotConfirmRead(ApiModel):
    """Atomic confirmation result and incremented day-plan version."""

    hold_id: UUID
    request_id: UUID
    status: Literal["CONFIRMED"]
    plan_version: int = Field(ge=2)
    replayed: bool
