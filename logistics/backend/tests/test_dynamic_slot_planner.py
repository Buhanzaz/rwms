"""Pure dynamic-slot invariants with no database or routing network."""

from __future__ import annotations

from dataclasses import replace
from datetime import UTC, date, datetime, time

import pytest

from app.routing import GeoPoint
from app.slot_planning.models import (
    DayPlan,
    DriverPlan,
    PlanningReason,
    RoadMetric,
    SlotAvailabilityStatus,
    SlotTask,
    SlotTaskType,
    TimeWindow,
    TravelTimeUnavailable,
    TripPlan,
    VehicleLegState,
    WarehouseSlotConfiguration,
)
from app.slot_planning.planner import FeasibleSlotPlanner

PLANNING_DATE = date(2026, 8, 29)
DEPOT = GeoPoint(30.0, 59.0)
NEW = GeoPoint(30.1, 59.1)
EXISTING = GeoPoint(30.2, 59.2)
PICKUP_ONE = GeoPoint(30.3, 59.3)
PICKUP_TWO = GeoPoint(30.4, 59.4)


class FakeTruckTravelTimeProvider:
    """Directed minute table recording every exact vehicle state requested."""

    def __init__(self, default_minutes: int = 10) -> None:
        self.default_minutes = default_minutes
        self.minutes: dict[tuple[tuple[float, float], tuple[float, float]], int] = {}
        self.unavailable: set[tuple[tuple[float, float], tuple[float, float]]] = set()
        self.calls: list[VehicleLegState] = []

    def set_minutes(self, origin: GeoPoint, destination: GeoPoint, minutes: int) -> None:
        """Configure one direction without implicitly changing its reverse."""

        self.minutes[(origin.coordinates, destination.coordinates)] = minutes

    async def travel_time(
        self,
        origin: GeoPoint,
        destination: GeoPoint,
        departure_at: datetime,
        vehicle_state: VehicleLegState,
    ) -> RoadMetric:
        """Return deterministic road metrics or an explicit no-truck-route signal."""

        del departure_at
        self.calls.append(vehicle_state)
        key = (origin.coordinates, destination.coordinates)
        if key in self.unavailable:
            raise TravelTimeUnavailable()
        minutes = self.minutes.get(key, self.default_minutes)
        return RoadMetric(travel_seconds=minutes * 60, distance_meters=minutes * 1_000)


def at(hour: int, minute: int = 0) -> datetime:
    """Build one aware timestamp on the shared planning date."""

    return datetime(2026, 8, 29, hour, minute, tzinfo=UTC)


def window(start: int, end: int) -> TimeWindow:
    """Build one hard service-start window."""

    return TimeWindow(at(start), at(end))


def delivery(
    task_id: str,
    point: GeoPoint,
    start: int,
    end: int,
    *,
    quantity: int = 1,
    trailer_access_allowed: bool = True,
    locked_at: datetime | None = None,
) -> SlotTask:
    """Create one conservative delivery task for focused tests."""

    return SlotTask(
        id=task_id,
        task_type=SlotTaskType.DELIVERY,
        point=point,
        quantity=quantity,
        window=window(start, end),
        service_minutes=60,
        address=task_id,
        trailer_access_allowed=trailer_access_allowed,
        time_locked=locked_at is not None,
        locked_service_start=locked_at,
    )


def pickup(
    task_id: str,
    point: GeoPoint,
    start: int = 9,
    end: int = 18,
    *,
    mandatory: bool = False,
) -> SlotTask:
    """Create one return-leg pickup task."""

    return SlotTask(
        id=task_id,
        task_type=SlotTaskType.PICKUP,
        point=point,
        quantity=1,
        window=window(start, end),
        service_minutes=60,
        address=task_id,
        mandatory=mandatory,
    )


def driver(
    *trips: TripPlan,
    driver_id: str = "driver-1",
    capacity: int = 2,
    shift_end: datetime | None = None,
) -> DriverPlan:
    """Create one driver whose two-cabin capacity is backed by a trailer."""

    return DriverPlan(
        driver_id=driver_id,
        shift_id=f"shift-{driver_id}",
        vehicle_id=f"vehicle-{driver_id}",
        shift_start=at(8),
        shift_end=shift_end or at(20),
        vehicle_capacity=capacity,
        has_trailer=capacity == 2,
        trips=tuple(trips),
    )


def configuration(**overrides: object) -> WarehouseSlotConfiguration:
    """Create zero-extra-buffer test settings while retaining nonzero depot work."""

    values: dict[str, object] = {
        "warehouse_id": "warehouse",
        "point": DEPOT,
        "timezone": "UTC",
        "driver_day_start": time(8),
        "delivery_day_start": time(9),
        "delivery_day_end": time(18),
        "hard_finish": time(20),
        "delivery_service_minutes": 60,
        "pickup_service_minutes": 60,
        "load_one_minutes": 30,
        "load_two_minutes": 30,
        "unload_per_cabin_minutes": 30,
        "turnaround_minutes": 15,
        "travel_time_multiplier": 1.0,
        "fixed_travel_buffer_minutes": 0,
    }
    values.update(overrides)
    return WarehouseSlotConfiguration(**values)  # type: ignore[arg-type]


async def calculate(
    provider: FakeTruckTravelTimeProvider,
    day: DayPlan,
    *,
    site_capacity: int = 2,
    cabin_count: int = 1,
    config: WarehouseSlotConfiguration | None = None,
):  # type: ignore[no-untyped-def]
    """Run the pure planner for the shared new address."""

    planner = FeasibleSlotPlanner(provider, config or configuration())
    return await planner.calculate(
        day,
        new_task_id="new",
        address="Новый клиент",
        point=NEW,
        cabin_count=cabin_count,
        site_cabin_capacity=site_capacity,
    )


@pytest.mark.asyncio
async def test_new_delivery_is_inserted_before_existing_midday_delivery() -> None:
    """A morning order shares the outbound trailer load and preserves the next window."""

    existing = delivery("d2", EXISTING, 12, 15)
    day = DayPlan(PLANNING_DATE, (driver(TripPlan("trip-1", (existing,))),))
    result = await calculate(FakeTruckTravelTimeProvider(20), day)

    morning = result.slots[0]
    assert morning.status is SlotAvailabilityStatus.AVAILABLE
    assert morning.best_candidate is not None
    assert morning.best_candidate.insert_before_stop_id == "d2"
    trip = next(
        item
        for item in morning.best_candidate.driver_plan.trips
        if item.id == morning.best_candidate.trip_id
    )
    assert trip.outbound_load == 2


@pytest.mark.asyncio
async def test_far_address_misses_existing_delivery_window() -> None:
    """No insertion is offered when every morning variant delays the promised delivery."""

    provider = FakeTruckTravelTimeProvider(20)
    provider.set_minutes(DEPOT, NEW, 180)
    provider.set_minutes(NEW, DEPOT, 180)
    provider.set_minutes(NEW, EXISTING, 180)
    existing = delivery("d2", EXISTING, 12, 15)
    day = DayPlan(PLANNING_DATE, (driver(TripPlan("trip-1", (existing,))),))

    result = await calculate(provider, day)

    assert result.slots[0].status is SlotAvailabilityStatus.UNAVAILABLE
    assert PlanningReason.DELIVERY_WINDOW_MISSED in result.slots[0].reasons


@pytest.mark.asyncio
async def test_full_existing_trip_forces_separate_trip_or_other_driver() -> None:
    """A third cabin never enters a two-cabin outbound load."""

    first = delivery("d1", GeoPoint(30.15, 59.15), 9, 12)
    second = delivery("d2", EXISTING, 12, 15)
    day = DayPlan(PLANNING_DATE, (driver(TripPlan("full-trip", (first, second))),))

    result = await calculate(FakeTruckTravelTimeProvider(), day)

    candidate = result.slots[0].best_candidate
    assert candidate is not None
    assert candidate.trip_id.startswith("slot-trip:")
    full = next(item for item in candidate.driver_plan.trips if item.id == "full-trip")
    assert full.outbound_load == 2


@pytest.mark.asyncio
async def test_late_delivery_can_follow_existing_delivery_and_return_before_twenty() -> None:
    """The 15-18 slot is evaluated after an existing midday stop and full depot return."""

    existing = delivery("d1", EXISTING, 12, 15)
    day = DayPlan(PLANNING_DATE, (driver(TripPlan("trip-1", (existing,))),))

    result = await calculate(FakeTruckTravelTimeProvider(25), day)

    late = result.slots[2]
    assert late.status is SlotAvailabilityStatus.AVAILABLE
    assert late.best_candidate is not None
    assert late.best_candidate.warehouse_return_time <= at(20)


@pytest.mark.asyncio
async def test_one_pickup_is_kept_when_two_would_exceed_shift() -> None:
    """Return-leg enumeration chooses one feasible pickup and defers the second."""

    base = delivery("d1", EXISTING, 9, 12)
    day = DayPlan(
        PLANNING_DATE,
        (driver(TripPlan("trip-1", (base,)), shift_end=at(14)),),
        pickup_pool=(pickup("p1", PICKUP_ONE), pickup("p2", PICKUP_TWO)),
    )

    result = await calculate(FakeTruckTravelTimeProvider(), day)

    candidate = result.slots[0].best_candidate
    assert candidate is not None
    assert candidate.pickup_count == 1
    assert len(candidate.scheduled_day.deferred_pickup_ids) == 1


@pytest.mark.asyncio
async def test_pickup_is_deferred_when_it_risks_next_locked_trip() -> None:
    """A pickup that fits before 20:00 is still rejected when the next trip is locked."""

    first = delivery("d1", EXISTING, 9, 12)
    next_delivery = delivery("d2", GeoPoint(30.5, 59.5), 12, 15, locked_at=at(12))
    day = DayPlan(
        PLANNING_DATE,
        (
            driver(
                TripPlan("trip-1", (first,)),
                TripPlan("trip-2", (next_delivery,), trip_locked=True),
            ),
        ),
        pickup_pool=(pickup("p1", PICKUP_ONE, 9, 11),),
    )

    result = await calculate(FakeTruckTravelTimeProvider(), day)

    candidate = result.slots[1].best_candidate
    assert candidate is not None
    assert candidate.pickup_count == 0
    assert candidate.scheduled_day.deferred_pickup_ids == ("p1",)


@pytest.mark.asyncio
async def test_existing_driver_assignment_is_never_moved() -> None:
    """New search may use another driver but retains the original assigned trip."""

    fixed = replace(
        delivery("fixed", EXISTING, 12, 15),
        assigned_driver_id="driver-1",
        assigned_trip_id="fixed-trip",
    )
    first = driver(TripPlan("fixed-trip", (fixed,), trip_locked=True), driver_id="driver-1")
    second = driver(driver_id="driver-2")
    day = DayPlan(PLANNING_DATE, (first, second))

    result = await calculate(FakeTruckTravelTimeProvider(), day)

    assert result.slots[0].best_candidate is not None
    assert first.trips[0].deliveries[0].assigned_driver_id == "driver-1"
    assert first.trips[0].deliveries[0].assigned_trip_id == "fixed-trip"


@pytest.mark.asyncio
async def test_missing_truck_route_never_falls_back_to_straight_line() -> None:
    """A provider no-route signal produces a structured unavailable reason."""

    provider = FakeTruckTravelTimeProvider()
    provider.unavailable.add((DEPOT.coordinates, NEW.coordinates))
    day = DayPlan(PLANNING_DATE, (driver(),))

    result = await calculate(provider, day)

    assert all(slot.status is SlotAvailabilityStatus.UNAVAILABLE for slot in result.slots)
    assert all(PlanningReason.TRUCK_ROUTE_NOT_FOUND in slot.reasons for slot in result.slots)


@pytest.mark.asyncio
async def test_solo_and_trailer_configurations_are_not_mixed() -> None:
    """The road provider sees distinct per-leg states for solo and trailer visits."""

    provider = FakeTruckTravelTimeProvider()
    day = DayPlan(PLANNING_DATE, (driver(),))
    await calculate(provider, day, site_capacity=1)
    solo_states = tuple(provider.calls)
    provider.calls.clear()
    await calculate(provider, day, site_capacity=2, cabin_count=2)
    trailer_states = tuple(provider.calls)

    assert solo_states and all(not state.trailer_attached for state in solo_states)
    assert trailer_states and all(state.trailer_attached for state in trailer_states)


@pytest.mark.asyncio
async def test_two_cabins_to_single_capacity_site_use_two_solo_trips() -> None:
    """A two-cabin order remains feasible as sequential no-trailer depot trips."""

    provider = FakeTruckTravelTimeProvider()
    result = await calculate(
        provider,
        DayPlan(PLANNING_DATE, (driver(),)),
        site_capacity=1,
        cabin_count=2,
    )

    morning = result.slots[0]
    assert morning.status is SlotAvailabilityStatus.AVAILABLE
    candidate = morning.best_candidate
    assert candidate is not None
    assert len(candidate.inserted_task_ids) == 2
    insertion_trips = [
        trip
        for trip in candidate.driver_plan.trips
        if any(task.id in candidate.inserted_task_ids for task in trip.deliveries)
    ]
    assert len(insertion_trips) == 2
    assert all(trip.outbound_load == 1 for trip in insertion_trips)
    customer_stops = [
        stop
        for trip in candidate.scheduled_day.trips
        for stop in trip.stops
        if stop.task_id in candidate.inserted_task_ids
    ]
    assert len(customer_stops) == 2
    assert all(stop.service_start <= at(12) for stop in customer_stops)
    assert provider.calls and all(not state.trailer_attached for state in provider.calls)


@pytest.mark.asyncio
async def test_three_cabins_to_single_capacity_site_use_three_solo_trips() -> None:
    """A 2+ cabin order is split into as many solo depot trips as the site requires."""

    provider = FakeTruckTravelTimeProvider(1)
    result = await calculate(
        provider,
        DayPlan(PLANNING_DATE, (driver(),)),
        site_capacity=1,
        cabin_count=3,
        config=configuration(
            delivery_service_minutes=30,
            load_one_minutes=1,
            turnaround_minutes=0,
        ),
    )

    morning = result.slots[0]
    assert morning.status is SlotAvailabilityStatus.AVAILABLE
    candidate = morning.best_candidate
    assert candidate is not None
    assert len(candidate.inserted_task_ids) == 3
    insertion_trips = [
        trip
        for trip in candidate.driver_plan.trips
        if any(task.id in candidate.inserted_task_ids for task in trip.deliveries)
    ]
    assert len(insertion_trips) == 3
    assert [trip.outbound_load for trip in insertion_trips] == [1, 1, 1]
    assert provider.calls and all(not state.trailer_attached for state in provider.calls)


@pytest.mark.asyncio
async def test_early_arrival_creates_waiting_without_rejecting_slot() -> None:
    """Waiting until 09:00 is visible and remains a feasible START_WITHIN_SLOT schedule."""

    day = DayPlan(PLANNING_DATE, (driver(),))
    result = await calculate(FakeTruckTravelTimeProvider(10), day)

    candidate = result.slots[0].best_candidate
    assert candidate is not None
    new_stop = next(
        stop
        for trip in candidate.scheduled_day.trips
        for stop in trip.stops
        if stop.task_id == "new"
    )
    assert new_stop.arrival == at(8, 40)
    assert new_stop.service_start == at(9)
    assert new_stop.waiting_minutes == 20


@pytest.mark.asyncio
async def test_new_trip_is_inserted_between_two_locked_trips() -> None:
    """Full suffix simulation finds the only feasible warehouse turn between trips."""

    first = delivery("d1", EXISTING, 9, 12, locked_at=at(9))
    last = delivery("d3", GeoPoint(30.6, 59.6), 15, 18, locked_at=at(15))
    day = DayPlan(
        PLANNING_DATE,
        (
            driver(
                TripPlan("trip-1", (first,), trip_locked=True),
                TripPlan("trip-3", (last,), trip_locked=True),
            ),
        ),
    )

    result = await calculate(FakeTruckTravelTimeProvider(), day)

    candidate = result.slots[1].best_candidate
    assert candidate is not None
    assert candidate.trip_id.startswith("slot-trip:")
    assert candidate.driver_plan.trips[1].id == candidate.trip_id


@pytest.mark.asyncio
async def test_concurrent_last_slot_first_booking_wins_second_recalculates_unavailable() -> None:
    """A second customer cannot reuse capacity after the first delivery becomes a day fact."""

    one_trip_only = configuration(turnaround_minutes=120)
    constrained_driver = driver(shift_end=at(12, 30), capacity=1)
    initial = DayPlan(PLANNING_DATE, (constrained_driver,), version=17)
    provider = FakeTruckTravelTimeProvider(30)
    first = await calculate(provider, initial, site_capacity=1, config=one_trip_only)
    first_candidate = first.slots[0].best_candidate
    assert first_candidate is not None

    confirmed = delivery(
        "confirmed-first",
        NEW,
        9,
        12,
        trailer_access_allowed=False,
    )
    updated = DayPlan(
        PLANNING_DATE,
        (replace(constrained_driver, trips=(TripPlan("confirmed", (confirmed,)),)),),
        version=18,
    )
    second = await FeasibleSlotPlanner(provider, one_trip_only).calculate(
        updated,
        new_task_id="second",
        address="Второй клиент",
        point=GeoPoint(31.0, 60.0),
        cabin_count=1,
        site_cabin_capacity=1,
    )

    assert second.plan_version == 18
    assert second.slots[0].status is SlotAvailabilityStatus.UNAVAILABLE


@pytest.mark.asyncio
async def test_optional_pickup_never_displaces_available_delivery() -> None:
    """An impossible return pickup is deferred while the customer slot stays available."""

    provider = FakeTruckTravelTimeProvider()
    provider.set_minutes(NEW, PICKUP_ONE, 600)
    provider.set_minutes(EXISTING, PICKUP_ONE, 600)
    day = DayPlan(
        PLANNING_DATE,
        (driver(TripPlan("trip-1", (delivery("d1", EXISTING, 12, 15),))),),
        pickup_pool=(pickup("far-pickup", PICKUP_ONE),),
    )

    result = await calculate(provider, day)

    assert result.slots[0].status is SlotAvailabilityStatus.AVAILABLE
    assert result.slots[0].best_candidate is not None
    assert result.slots[0].best_candidate.pickup_count == 0


@pytest.mark.asyncio
async def test_open_day_adds_pickup_only_after_new_delivery_fills_outbound_load() -> None:
    """A new customer may unlock a return pickup by completing the two-cabin trip."""

    existing = delivery("existing", EXISTING, 12, 15)
    day = DayPlan(
        PLANNING_DATE,
        (driver(TripPlan("trip-1", (existing,))),),
        pickup_pool=(pickup("return", PICKUP_ONE),),
        accepting_requests=True,
    )

    result = await calculate(FakeTruckTravelTimeProvider(), day)

    candidate = result.slots[0].best_candidate
    assert candidate is not None
    assert candidate.baseline_scheduled_day.pickup_count == 0
    assert candidate.pickup_count == 1
    filled_trip = next(
        trip for trip in candidate.driver_plan.trips if trip.id == candidate.trip_id
    )
    assert filled_trip.outbound_load == 2


@pytest.mark.asyncio
async def test_tariff_changes_cannot_enter_pure_feasibility_input() -> None:
    """The same road/day snapshot is invariant because pricing is outside feasibility."""

    day = DayPlan(PLANNING_DATE, (driver(),))
    first = await calculate(FakeTruckTravelTimeProvider(), day)
    second = await calculate(FakeTruckTravelTimeProvider(), day)

    assert [slot.status for slot in first.slots] == [slot.status for slot in second.slots]
    assert [len(slot.candidates) for slot in first.slots] == [
        len(slot.candidates) for slot in second.slots
    ]
