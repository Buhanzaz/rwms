"""Focused tests for non-persisting vehicle operational placement."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest
from pydantic import ValidationError

from app.schemas.domain import RwmsVehicleOperationalAssignment
from app.services.vehicle_availability import (
    VehicleAssignmentDataError,
    VehicleAvailabilityPolicy,
    merge_vehicle_assignments,
)

BASE = datetime(2026, 9, 14, 8, tzinfo=UTC)


def assignment(
    *,
    vehicle_id: UUID,
    source_id: UUID,
    destination_id: UUID,
    mode: str,
    status: str,
    travel_start: datetime = BASE,
    effective_from: datetime = BASE + timedelta(hours=2),
    effective_until: datetime | None = None,
    assignment_id: UUID | None = None,
) -> RwmsVehicleOperationalAssignment:
    """Build one strict upstream fact with mode-appropriate default bounds."""

    resolved_until = effective_until
    if mode == "TEMPORARY" and resolved_until is None:
        resolved_until = effective_from + timedelta(days=2)
    elif mode == "TRIP_ONLY":
        resolved_until = effective_from
    return RwmsVehicleOperationalAssignment.model_validate(
        {
            "assignmentId": str(assignment_id or uuid4()),
            "version": 1,
            "transferId": str(uuid4()),
            "vehicleId": str(vehicle_id),
            "sourceWarehouseId": str(source_id),
            "destinationWarehouseId": str(destination_id),
            "mode": mode,
            "status": status,
            "travelStartsAt": travel_start.isoformat(),
            "effectiveFrom": effective_from.isoformat(),
            "effectiveUntil": (resolved_until.isoformat() if resolved_until is not None else None),
            "createdAt": (travel_start - timedelta(days=1)).isoformat(),
            "updatedAt": (travel_start - timedelta(hours=1)).isoformat(),
        }
    )


def test_incoming_vehicle_is_not_a_destination_resource_before_active_arrival() -> None:
    """A planned reposition stays at source before travel and unavailable afterward."""

    vehicle_id, source_id, destination_id = uuid4(), uuid4(), uuid4()
    policy = VehicleAvailabilityPolicy(
        (
            assignment(
                vehicle_id=vehicle_id,
                source_id=source_id,
                destination_id=destination_id,
                mode="TEMPORARY",
                status="PLANNED",
            ),
        ),
        observed_at=BASE - timedelta(hours=1),
    )

    before_departure = policy.placement_at(
        vehicle_id,
        source_id,
        BASE - timedelta(hours=1),
    )
    assert before_departure.warehouse_id == source_id
    assert not policy.placement_at(
        vehicle_id,
        source_id,
        BASE + timedelta(hours=1),
    ).available
    assert not policy.placement_at(
        vehicle_id,
        source_id,
        BASE + timedelta(hours=3),
    ).available
    assert not policy.available_for_interval(
        vehicle_id,
        source_id,
        destination_id,
        BASE + timedelta(hours=1),
        BASE + timedelta(hours=4),
    )


def test_active_temporary_vehicle_is_destination_based_only_inside_effective_interval() -> None:
    """ACTIVE placement enables destination work, excludes source, and returns at its end."""

    vehicle_id, source_id, destination_id = uuid4(), uuid4(), uuid4()
    effective_until = BASE + timedelta(hours=12)
    policy = VehicleAvailabilityPolicy(
        (
            assignment(
                vehicle_id=vehicle_id,
                source_id=source_id,
                destination_id=destination_id,
                mode="TEMPORARY",
                status="ACTIVE",
                effective_until=effective_until,
            ),
        ),
        observed_at=BASE + timedelta(hours=3),
    )

    active_start = BASE + timedelta(hours=3)
    active_end = BASE + timedelta(hours=8)
    assert policy.placement_at(vehicle_id, source_id, active_start).warehouse_id == destination_id
    assert policy.available_for_interval(
        vehicle_id,
        source_id,
        destination_id,
        active_start,
        active_end,
    )
    assert not policy.available_for_interval(
        vehicle_id,
        source_id,
        source_id,
        active_start,
        active_end,
    )
    assert policy.placement_at(vehicle_id, source_id, effective_until).warehouse_id == source_id


def test_observed_overdue_planned_trip_stays_reserved_open_ended() -> None:
    """A trip may be assumed returned only while its planned arrival is still future."""

    vehicle_id, source_id, destination_id = uuid4(), uuid4(), uuid4()
    planned_trip = assignment(
        vehicle_id=vehicle_id,
        source_id=source_id,
        destination_id=destination_id,
        mode="TRIP_ONLY",
        status="PLANNED",
    )
    timely = VehicleAvailabilityPolicy(
        (planned_trip,),
        observed_at=BASE + timedelta(hours=1),
    )
    overdue = VehicleAvailabilityPolicy(
        (planned_trip,),
        observed_at=BASE + timedelta(hours=2),
    )

    after_planned_return = BASE + timedelta(hours=3)
    assert (
        timely.placement_at(vehicle_id, source_id, after_planned_return).warehouse_id == source_id
    )
    assert not overdue.placement_at(
        vehicle_id,
        source_id,
        after_planned_return,
    ).available
    assert timely.source_revision != overdue.source_revision


def test_active_predecessor_allows_sequential_trips_then_one_pending_reposition() -> None:
    """Nonoverlapping trips return to the predecessor before a terminal reposition."""

    vehicle_id, home_id, placed_id, final_id = uuid4(), uuid4(), uuid4(), uuid4()
    active = assignment(
        vehicle_id=vehicle_id,
        source_id=home_id,
        destination_id=placed_id,
        mode="PERMANENT",
        status="ACTIVE",
        travel_start=BASE - timedelta(hours=4),
        effective_from=BASE - timedelta(hours=3),
    )
    first_trip = assignment(
        vehicle_id=vehicle_id,
        source_id=placed_id,
        destination_id=uuid4(),
        mode="TRIP_ONLY",
        status="PLANNED",
        travel_start=BASE + timedelta(hours=1),
        effective_from=BASE + timedelta(hours=2),
    )
    second_trip = assignment(
        vehicle_id=vehicle_id,
        source_id=placed_id,
        destination_id=uuid4(),
        mode="TRIP_ONLY",
        status="PLANNED",
        travel_start=BASE + timedelta(hours=3),
        effective_from=BASE + timedelta(hours=4),
    )
    reposition = assignment(
        vehicle_id=vehicle_id,
        source_id=placed_id,
        destination_id=final_id,
        mode="TEMPORARY",
        status="PLANNED",
        travel_start=BASE + timedelta(hours=5),
        effective_from=BASE + timedelta(hours=6),
    )
    policy = VehicleAvailabilityPolicy(
        (active, first_trip, second_trip, reposition),
        observed_at=BASE,
    )

    assert policy.placement_at(vehicle_id, home_id, BASE).warehouse_id == placed_id
    assert not policy.placement_at(
        vehicle_id,
        home_id,
        BASE + timedelta(hours=1, minutes=30),
    ).available
    assert (
        policy.placement_at(
            vehicle_id,
            home_id,
            BASE + timedelta(hours=2, minutes=30),
        ).warehouse_id
        == placed_id
    )
    assert (
        policy.placement_at(
            vehicle_id,
            home_id,
            BASE + timedelta(hours=4, minutes=30),
        ).warehouse_id
        == placed_id
    )
    assert not policy.placement_at(
        vehicle_id,
        home_id,
        BASE + timedelta(hours=7),
    ).available


def test_expired_temporary_predecessor_requires_successor_from_source() -> None:
    """A successor at the temporary end departs from source rather than destination."""

    vehicle_id, source_id, destination_id = uuid4(), uuid4(), uuid4()
    active = assignment(
        vehicle_id=vehicle_id,
        source_id=source_id,
        destination_id=destination_id,
        mode="TEMPORARY",
        status="ACTIVE",
        travel_start=BASE - timedelta(hours=4),
        effective_from=BASE - timedelta(hours=3),
        effective_until=BASE + timedelta(hours=1),
    )
    source_trip = assignment(
        vehicle_id=vehicle_id,
        source_id=source_id,
        destination_id=uuid4(),
        mode="TRIP_ONLY",
        status="PLANNED",
        travel_start=BASE + timedelta(hours=1),
        effective_from=BASE + timedelta(hours=2),
    )
    policy = VehicleAvailabilityPolicy(
        (active, source_trip),
        observed_at=BASE,
    )

    assert (
        policy.placement_at(
            vehicle_id,
            source_id,
            BASE + timedelta(hours=2, minutes=30),
        ).warehouse_id
        == source_id
    )

    wrong_source = source_trip.model_copy(
        update={
            "assignment_id": uuid4(),
            "source_warehouse_id": destination_id,
        }
    )
    with pytest.raises(VehicleAssignmentDataError, match="operational placement"):
        VehicleAvailabilityPolicy((active, wrong_source), observed_at=BASE)


def test_bounded_completed_permanent_is_history_below_active_precedence() -> None:
    """A superseded permanent fact validates but cannot override the one live placement."""

    vehicle_id, home_id, middle_id, current_id = uuid4(), uuid4(), uuid4(), uuid4()
    completed = assignment(
        vehicle_id=vehicle_id,
        source_id=home_id,
        destination_id=middle_id,
        mode="PERMANENT",
        status="COMPLETED",
        travel_start=BASE - timedelta(hours=8),
        effective_from=BASE - timedelta(hours=7),
        effective_until=BASE + timedelta(hours=2),
    )
    active = assignment(
        vehicle_id=vehicle_id,
        source_id=middle_id,
        destination_id=current_id,
        mode="PERMANENT",
        status="ACTIVE",
        travel_start=BASE - timedelta(hours=2),
        effective_from=BASE - timedelta(hours=1),
    )
    policy = VehicleAvailabilityPolicy(
        (completed, active),
        observed_at=BASE,
    )

    assert policy.placement_at(vehicle_id, home_id, BASE).warehouse_id == current_id

    with pytest.raises(ValidationError, match="only COMPLETED PERMANENT"):
        assignment(
            vehicle_id=vehicle_id,
            source_id=home_id,
            destination_id=middle_id,
            mode="PERMANENT",
            status="ACTIVE",
            travel_start=BASE - timedelta(hours=8),
            effective_from=BASE - timedelta(hours=7),
            effective_until=BASE + timedelta(hours=2),
        )


def test_conflicting_duplicates_and_malformed_live_chains_fail_closed() -> None:
    """Changed identities, overlap, topology, and nonterminal collisions are rejected."""

    vehicle_id, source_id, placed_id = uuid4(), uuid4(), uuid4()
    shared_id = uuid4()
    active = assignment(
        assignment_id=shared_id,
        vehicle_id=vehicle_id,
        source_id=source_id,
        destination_id=placed_id,
        mode="PERMANENT",
        status="ACTIVE",
        travel_start=BASE - timedelta(hours=4),
        effective_from=BASE - timedelta(hours=3),
    )
    changed = active.model_copy(update={"version": 2})
    with pytest.raises(VehicleAssignmentDataError, match="conflicting"):
        merge_vehicle_assignments(
            ((active,), (changed,)),
            observed_at=BASE,
        )
    assert (
        len(
            merge_vehicle_assignments(
                ((active,), (active,)),
                observed_at=BASE,
            )
        )
        == 1
    )

    first_trip = assignment(
        vehicle_id=vehicle_id,
        source_id=placed_id,
        destination_id=uuid4(),
        mode="TRIP_ONLY",
        status="PLANNED",
        travel_start=BASE + timedelta(hours=1),
        effective_from=BASE + timedelta(hours=3),
    )
    overlapping_trip = assignment(
        vehicle_id=vehicle_id,
        source_id=placed_id,
        destination_id=uuid4(),
        mode="TRIP_ONLY",
        status="PLANNED",
        travel_start=BASE + timedelta(hours=2),
        effective_from=BASE + timedelta(hours=4),
    )
    with pytest.raises(VehicleAssignmentDataError, match="overlapping"):
        VehicleAvailabilityPolicy(
            (active, first_trip, overlapping_trip),
            observed_at=BASE,
        )

    wrong_source = first_trip.model_copy(
        update={"assignment_id": uuid4(), "source_warehouse_id": source_id}
    )
    with pytest.raises(VehicleAssignmentDataError, match="operational placement"):
        VehicleAvailabilityPolicy((active, wrong_source), observed_at=BASE)

    reposition = assignment(
        vehicle_id=vehicle_id,
        source_id=placed_id,
        destination_id=uuid4(),
        mode="PERMANENT",
        status="PLANNED",
        travel_start=BASE + timedelta(hours=1),
        effective_from=BASE + timedelta(hours=2),
    )
    later_trip = assignment(
        vehicle_id=vehicle_id,
        source_id=placed_id,
        destination_id=uuid4(),
        mode="TRIP_ONLY",
        status="PLANNED",
        travel_start=BASE + timedelta(hours=3),
        effective_from=BASE + timedelta(hours=4),
    )
    with pytest.raises(VehicleAssignmentDataError, match="final"):
        VehicleAvailabilityPolicy(
            (active, reposition, later_trip),
            observed_at=BASE,
        )

    in_transit = reposition.model_copy(update={"assignment_id": uuid4(), "status": "IN_TRANSIT"})
    in_transit_policy = VehicleAvailabilityPolicy(
        (active, in_transit),
        observed_at=BASE,
    )
    assert not in_transit_policy.placement_at(
        vehicle_id,
        source_id,
        BASE + timedelta(hours=3),
    ).available
    with pytest.raises(VehicleAssignmentDataError, match="IN_TRANSIT"):
        VehicleAvailabilityPolicy(
            (active, in_transit, later_trip),
            observed_at=BASE,
        )

    with pytest.raises(VehicleAssignmentDataError, match="overdue"):
        VehicleAvailabilityPolicy(
            (active, first_trip, later_trip),
            observed_at=BASE + timedelta(hours=5),
        )

    second_active = active.model_copy(update={"assignment_id": uuid4()})
    with pytest.raises(VehicleAssignmentDataError, match="ACTIVE"):
        VehicleAvailabilityPolicy((active, second_active), observed_at=BASE)

    cancelled = later_trip.model_copy(update={"assignment_id": uuid4(), "status": "CANCELLED"})
    assert (
        len(
            VehicleAvailabilityPolicy(
                (active, cancelled),
                observed_at=BASE,
            ).assignments
        )
        == 2
    )


def test_pending_chain_without_active_predecessor_must_start_from_home() -> None:
    """An incomplete live closure cannot make an unrelated source or home available."""

    vehicle_id, home_id, unrelated_source_id = uuid4(), uuid4(), uuid4()
    orphaned = assignment(
        vehicle_id=vehicle_id,
        source_id=unrelated_source_id,
        destination_id=uuid4(),
        mode="TRIP_ONLY",
        status="PLANNED",
        travel_start=BASE + timedelta(hours=2),
        effective_from=BASE + timedelta(hours=3),
    )
    policy = VehicleAvailabilityPolicy((orphaned,), observed_at=BASE)

    assert not policy.placement_at(vehicle_id, home_id, BASE).available
