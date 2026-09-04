"""Derive vehicle placement from RWMS assignments without rewriting local ownership."""

from __future__ import annotations

import asyncio
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime, time, timedelta
from hashlib import sha256
from typing import TYPE_CHECKING
from uuid import UUID
from zoneinfo import ZoneInfo

from app.schemas.domain import RwmsVehicleOperationalAssignment

if TYPE_CHECKING:
    from app.integrations.rwms import RwmsPlanningClient

_PENDING_STATUSES = frozenset({"PLANNED", "IN_TRANSIT"})


class VehicleAssignmentDataError(ValueError):
    """Signal contradictory assignment facts that must fail planning closed."""


@dataclass(frozen=True, slots=True)
class VehiclePlacement:
    """Guaranteed operational warehouse at one instant, or an unavailable marker."""

    warehouse_id: UUID | None
    assignment_id: UUID | None = None

    @property
    def available(self) -> bool:
        """Return whether the vehicle has a guaranteed operational warehouse."""

        return self.warehouse_id is not None


def local_shift_interval(
    planning_date: date,
    start_time: time,
    end_time: time,
    zone: ZoneInfo,
) -> tuple[datetime, datetime]:
    """Resolve a positive half-open local shift, carrying an earlier end overnight."""

    start_at = datetime.combine(planning_date, start_time, tzinfo=zone)
    if end_time == start_time:
        raise ValueError("warehouse shift must have a non-zero duration")
    end_date = planning_date + timedelta(days=1) if end_time < start_time else planning_date
    return start_at, datetime.combine(end_date, end_time, tzinfo=zone)


def recurring_shift_intervals(
    date_from: date,
    date_to: date,
    start_time: time,
    end_time: time,
    zone: ZoneInfo,
) -> tuple[tuple[datetime, datetime], ...]:
    """Expand one bounded recurring shift into exact aware half-open intervals."""

    if date_to < date_from:
        raise ValueError("date_to must be on or after date_from")
    return tuple(
        local_shift_interval(date_from + timedelta(days=offset), start_time, end_time, zone)
        for offset in range((date_to - date_from).days + 1)
    )


def covering_window(
    intervals: Iterable[tuple[datetime, datetime]],
) -> tuple[datetime, datetime]:
    """Return the smallest aware half-open window covering every supplied interval."""

    values = tuple(intervals)
    if not values:
        raise ValueError("at least one interval is required")
    if any(
        start.utcoffset() is None or end.utcoffset() is None or start >= end
        for start, end in values
    ):
        raise ValueError("intervals must contain positive timezone-aware bounds")
    return min(start for start, _ in values), max(end for _, end in values)


def _utc_observation(value: datetime | None) -> datetime:
    """Capture or normalize one aware UTC observation for the whole policy snapshot."""

    observed_at = value or datetime.now(UTC)
    if observed_at.utcoffset() is None:
        raise ValueError("observed_at must include a UTC offset")
    return observed_at.astimezone(UTC)


def _pending_reservation_end(
    assignment: RwmsVehicleOperationalAssignment,
    observed_at: datetime,
) -> datetime | None:
    """Return the status-aware reservation end, with unknown arrival represented as open."""

    if assignment.status == "IN_TRANSIT":
        return None
    if (
        assignment.status == "PLANNED"
        and assignment.mode == "TRIP_ONLY"
        and observed_at < assignment.effective_from
    ):
        return assignment.effective_from
    return None


def _active_source_at(
    assignment: RwmsVehicleOperationalAssignment,
    departure_at: datetime,
) -> UUID | None:
    """Resolve the only valid successor source from one ACTIVE placement."""

    if departure_at < assignment.effective_from:
        return None
    if assignment.mode == "TEMPORARY":
        if assignment.effective_until is None:
            return None
        if departure_at >= assignment.effective_until:
            return assignment.source_warehouse_id
        return assignment.destination_warehouse_id
    if assignment.effective_until is not None and departure_at >= assignment.effective_until:
        return None
    return assignment.destination_warehouse_id


def _reservation_covers(
    assignment: RwmsVehicleOperationalAssignment,
    at: datetime,
    observed_at: datetime,
) -> bool:
    """Return whether travel or unresolved arrival makes the vehicle unavailable."""

    if assignment.status == "CANCELLED" or at < assignment.travel_starts_at:
        return False
    if assignment.status in _PENDING_STATUSES:
        reservation_end = _pending_reservation_end(assignment, observed_at)
        return reservation_end is None or at < reservation_end
    return assignment.status in {"ACTIVE", "COMPLETED"} and at < assignment.effective_from


def _validate_vehicle_chain(
    assignments: Sequence[RwmsVehicleOperationalAssignment],
    observed_at: datetime,
) -> None:
    """Reject ambiguous live chains while permitting sequential trips from one placement."""

    active = tuple(item for item in assignments if item.status == "ACTIVE")
    if len(active) > 1:
        raise VehicleAssignmentDataError("one vehicle has more than one ACTIVE placement")

    pending = tuple(
        sorted(
            (item for item in assignments if item.status in _PENDING_STATUSES),
            key=lambda item: (item.travel_starts_at, str(item.assignment_id)),
        )
    )
    if not pending:
        return
    if any(item.status == "IN_TRANSIT" for item in pending) and len(pending) > 1:
        raise VehicleAssignmentDataError(
            "an IN_TRANSIT assignment collides with another vehicle reservation"
        )
    if (
        any(
            item.status == "PLANNED"
            and item.mode == "TRIP_ONLY"
            and observed_at >= item.effective_from
            for item in pending
        )
        and len(pending) > 1
    ):
        raise VehicleAssignmentDataError(
            "an overdue PLANNED trip collides with another vehicle reservation"
        )

    active_assignment = active[0] if active else None
    fallback_source = pending[0].source_warehouse_id
    previous_trip_end: datetime | None = None
    for index, assignment in enumerate(pending):
        expected_source = (
            _active_source_at(active_assignment, assignment.travel_starts_at)
            if active_assignment is not None
            else fallback_source
        )
        if assignment.source_warehouse_id != expected_source:
            raise VehicleAssignmentDataError(
                "a vehicle successor does not depart from its operational placement"
            )
        if assignment.status == "IN_TRANSIT":
            continue
        if assignment.mode != "TRIP_ONLY":
            if index != len(pending) - 1:
                raise VehicleAssignmentDataError(
                    "a pending reposition must be the final vehicle reservation"
                )
            if previous_trip_end is not None and assignment.travel_starts_at < previous_trip_end:
                raise VehicleAssignmentDataError(
                    "a pending reposition overlaps a planned trip reservation"
                )
            continue
        if previous_trip_end is not None and assignment.travel_starts_at < previous_trip_end:
            raise VehicleAssignmentDataError(
                "one vehicle has overlapping planned trip reservations"
            )
        previous_trip_end = assignment.effective_from


def merge_vehicle_assignments(
    assignment_groups: Iterable[Iterable[RwmsVehicleOperationalAssignment]],
    *,
    observed_at: datetime | None = None,
) -> tuple[RwmsVehicleOperationalAssignment, ...]:
    """Merge chain-closed warehouse reads and reject contradictory live facts."""

    observation = _utc_observation(observed_at)

    by_id: dict[UUID, RwmsVehicleOperationalAssignment] = {}
    for assignment in (item for group in assignment_groups for item in group):
        previous = by_id.get(assignment.assignment_id)
        if previous is not None and previous != assignment:
            raise VehicleAssignmentDataError(
                "the same assignmentId has conflicting vehicle assignment facts"
            )
        by_id[assignment.assignment_id] = assignment

    by_vehicle: dict[UUID, list[RwmsVehicleOperationalAssignment]] = {}
    for assignment in by_id.values():
        by_vehicle.setdefault(assignment.vehicle_id, []).append(assignment)
    for assignments in by_vehicle.values():
        _validate_vehicle_chain(assignments, observation)

    return tuple(
        sorted(
            by_id.values(),
            key=lambda item: (
                str(item.vehicle_id),
                item.travel_starts_at,
                str(item.assignment_id),
            ),
        )
    )


class VehicleAvailabilityPolicy:
    """Resolve effective basing and travel reservations from immutable assignment facts."""

    def __init__(
        self,
        assignments: Iterable[RwmsVehicleOperationalAssignment] = (),
        *,
        observed_at: datetime | None = None,
    ) -> None:
        self._observed_at = _utc_observation(observed_at)
        merged = merge_vehicle_assignments(
            (assignments,),
            observed_at=self._observed_at,
        )
        by_vehicle: dict[UUID, list[RwmsVehicleOperationalAssignment]] = {}
        for assignment in merged:
            by_vehicle.setdefault(assignment.vehicle_id, []).append(assignment)
        self._assignments = merged
        self._by_vehicle = {
            vehicle_id: tuple(history) for vehicle_id, history in by_vehicle.items()
        }
        revision_input = "\n".join(
            "|".join(
                (
                    assignment.model_dump_json(by_alias=True),
                    (
                        "open"
                        if assignment.status in _PENDING_STATUSES
                        and _pending_reservation_end(assignment, self._observed_at) is None
                        else "bounded"
                    ),
                )
            )
            for assignment in merged
        )
        self._source_revision = sha256(revision_input.encode()).hexdigest()

    @property
    def observed_at(self) -> datetime:
        """Return the single UTC instant used to classify overdue reservations."""

        return self._observed_at

    @property
    def assignments(self) -> tuple[RwmsVehicleOperationalAssignment, ...]:
        """Return the normalized immutable assignment set."""

        return self._assignments

    @property
    def vehicle_ids(self) -> frozenset[UUID]:
        """Return every physical vehicle referenced by the assignment set."""

        return frozenset(self._by_vehicle)

    @property
    def source_revision(self) -> str:
        """Return a stable fingerprint suitable for planner snapshot fencing."""

        return self._source_revision

    def placement_at(
        self,
        vehicle_id: UUID,
        home_warehouse_id: UUID,
        at: datetime,
    ) -> VehiclePlacement:
        """Resolve one guaranteed placement while treating travel as unavailable."""

        if at.utcoffset() is None:
            raise ValueError("at must include a UTC offset")
        history = self._by_vehicle.get(vehicle_id, ())
        reservations = tuple(
            assignment
            for assignment in history
            if _reservation_covers(assignment, at, self._observed_at)
        )
        if reservations:
            assignment = min(
                reservations,
                key=lambda item: (item.travel_starts_at, str(item.assignment_id)),
            )
            return VehiclePlacement(None, assignment.assignment_id)

        active = next(
            (assignment for assignment in history if assignment.status == "ACTIVE"),
            None,
        )
        if active is not None:
            if at < active.travel_starts_at:
                return VehiclePlacement(
                    active.source_warehouse_id,
                    active.assignment_id,
                )
            if active.mode == "TEMPORARY":
                if active.effective_until is None:
                    return VehiclePlacement(None, active.assignment_id)
                operational_warehouse_id = (
                    active.destination_warehouse_id
                    if at < active.effective_until
                    else active.source_warehouse_id
                )
                return VehiclePlacement(
                    operational_warehouse_id,
                    active.assignment_id,
                )
            if active.effective_until is None or at < active.effective_until:
                return VehiclePlacement(
                    active.destination_warehouse_id,
                    active.assignment_id,
                )
            return VehiclePlacement(None, active.assignment_id)

        completed_placements = tuple(
            assignment
            for assignment in history
            if assignment.status == "COMPLETED"
            and assignment.mode in {"TEMPORARY", "PERMANENT"}
            and assignment.effective_from <= at
            and (assignment.effective_until is None or at < assignment.effective_until)
        )
        if completed_placements:
            assignment = max(
                completed_placements,
                key=lambda item: (item.effective_from, item.updated_at),
            )
            return VehiclePlacement(
                assignment.destination_warehouse_id,
                assignment.assignment_id,
            )
        orphaned_pending = next(
            (
                assignment
                for assignment in history
                if assignment.status in _PENDING_STATUSES
                and assignment.source_warehouse_id != home_warehouse_id
            ),
            None,
        )
        if orphaned_pending is not None:
            return VehiclePlacement(None, orphaned_pending.assignment_id)
        return VehiclePlacement(home_warehouse_id)

    def available_for_interval(
        self,
        vehicle_id: UUID,
        home_warehouse_id: UUID,
        required_warehouse_id: UUID,
        start_at: datetime,
        end_at: datetime,
    ) -> bool:
        """Require one operational warehouse throughout an exact half-open interval."""

        if start_at.utcoffset() is None or end_at.utcoffset() is None or start_at >= end_at:
            raise ValueError("vehicle availability requires a positive aware interval")
        history = self._by_vehicle.get(vehicle_id, ())
        boundaries = {start_at, end_at}
        for assignment in history:
            for boundary in (
                assignment.travel_starts_at,
                assignment.effective_from,
                assignment.effective_until,
            ):
                if boundary is not None and start_at < boundary < end_at:
                    boundaries.add(boundary)
        ordered = sorted(boundaries)
        return all(
            self.placement_at(vehicle_id, home_warehouse_id, boundary).warehouse_id
            == required_warehouse_id
            for boundary in ordered[:-1]
        )


async def load_vehicle_availability(
    client: RwmsPlanningClient,
    warehouse_ids: Sequence[UUID],
    *,
    window_start: datetime,
    window_end: datetime,
) -> VehicleAvailabilityPolicy:
    """Load chain-closed assignment facts under one captured UTC observation."""

    if (
        window_start.utcoffset() is None
        or window_end.utcoffset() is None
        or window_start >= window_end
    ):
        raise ValueError("vehicle assignment window must contain positive aware bounds")
    observed_at = datetime.now(UTC)
    unique_ids = tuple(dict.fromkeys(warehouse_ids))
    if not unique_ids:
        return VehicleAvailabilityPolicy(observed_at=observed_at)
    groups = await asyncio.gather(
        *(
            client.list_vehicle_assignments(
                warehouse_id,
                window_start=window_start,
                window_end=window_end,
            )
            for warehouse_id in unique_ids
        )
    )
    return VehicleAvailabilityPolicy(
        (assignment for group in groups for assignment in group),
        observed_at=observed_at,
    )
