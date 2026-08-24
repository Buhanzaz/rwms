"""Immutable simulation overrides and derived-state value objects."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import StrEnum

from app.routing import GeoJsonLineString, GeoPoint
from app.routing.models import require_aware


class SimulationStatus(StrEnum):
    """Vehicle activity visible at a selected simulation timestamp."""

    WAITING_SHIFT = "WAITING_SHIFT"
    LOADING = "LOADING"
    DRIVING = "DRIVING"
    DELIVERING = "DELIVERING"
    PICKING_UP = "PICKING_UP"
    RETURNING = "RETURNING"
    UNLOADING = "UNLOADING"
    BREAK = "BREAK"
    FINISHED = "FINISHED"
    DELAYED = "DELAYED"
    UNAVAILABLE = "UNAVAILABLE"


@dataclass(frozen=True, slots=True)
class DelayOverride:
    """A non-persistent delay propagated through one driver's future schedule."""

    id: str
    driver_shift_id: str
    delay: timedelta
    effective_at: datetime | None = None
    cycle_id: str | None = None
    stop_sequence: int | None = None
    reason: str = ""

    def __post_init__(self) -> None:
        require_aware(self.effective_at, "effective_at")
        if self.delay <= timedelta(0):
            raise ValueError("delay must be positive")
        if self.stop_sequence is not None and self.cycle_id is None:
            raise ValueError("stop_sequence requires cycle_id")
        if self.effective_at is None and self.cycle_id is None:
            raise ValueError("delay requires effective_at or cycle_id")


@dataclass(frozen=True, slots=True)
class DriverUnavailableOverride:
    """A simulation-only event making one driver unavailable from a timestamp."""

    id: str
    driver_shift_id: str
    effective_at: datetime
    reason: str = ""

    def __post_init__(self) -> None:
        require_aware(self.effective_at, "effective_at")


type SimulationOverride = DelayOverride | DriverUnavailableOverride


@dataclass(frozen=True, slots=True)
class SimulationEvent:
    """One deterministic timeline event derived from route times and overrides."""

    event_at: datetime
    driver_shift_id: str
    cycle_id: str | None
    event_type: str
    message_ru: str
    task_id: str | None = None

    def __post_init__(self) -> None:
        require_aware(self.event_at, "event_at")


@dataclass(frozen=True, slots=True)
class VehicleSimulationState:
    """Complete map/sidebar state for one driver-vehicle assignment."""

    driver_shift_id: str
    driver_id: str
    vehicle_id: str
    position: GeoPoint
    status: SimulationStatus
    current_load: int
    next_address: str | None
    eta: datetime | None
    active_cycle_id: str | None
    active_leg_index: int | None
    completed_task_ids: tuple[str, ...]
    traversed_geometry: GeoJsonLineString
    active_geometry: GeoJsonLineString
    remaining_geometry: GeoJsonLineString
    delayed: bool = False
    unavailable: bool = False
    schedule_offset_seconds: int = 0

    def __post_init__(self) -> None:
        require_aware(self.eta, "eta")
        if self.current_load < 0:
            raise ValueError("simulated load cannot be negative")


@dataclass(frozen=True, slots=True)
class SimulationSnapshot:
    """Pure reproducible state of the complete plan at one timestamp."""

    timestamp: datetime
    vehicles: tuple[VehicleSimulationState, ...]
    elapsed_events: tuple[SimulationEvent, ...]
    previous_event_at: datetime | None
    next_event_at: datetime | None

    def __post_init__(self) -> None:
        require_aware(self.timestamp, "timestamp")
        require_aware(self.previous_event_at, "previous_event_at")
        require_aware(self.next_event_at, "next_event_at")
