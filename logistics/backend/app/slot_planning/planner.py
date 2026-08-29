"""Deterministic dynamic-slot search and complete driver-day schedule simulation."""

# ruff: noqa: RUF001 -- Russian planner explanations intentionally use Cyrillic.

from __future__ import annotations

from dataclasses import replace
from datetime import date, datetime, timedelta
from math import ceil
from zoneinfo import ZoneInfo

from app.routing import GeoPoint

from .models import (
    REASON_MESSAGES_RU,
    DayPlan,
    DriverPlan,
    FailureAccumulator,
    PlanningReason,
    RoadMetric,
    ScheduledDriverDay,
    ScheduledTrip,
    SlotAvailabilityStatus,
    SlotCandidate,
    SlotEvaluation,
    SlotPlanningResult,
    SlotTask,
    SlotTaskType,
    TimelineStop,
    TimelineStopType,
    TimeWindow,
    TravelTimeUnavailable,
    TripPlan,
    VehicleLegState,
    WarehouseSlotConfiguration,
)
from .ports import TravelTimeProvider


class ScheduleInfeasible(RuntimeError):
    """Internal control-flow exception carrying one stable hard-constraint reason."""

    def __init__(self, reason: PlanningReason) -> None:
        super().__init__(reason.value)
        self.reason = reason


class FeasibleSlotPlanner:
    """Find every feasible delivery insertion by resimulating complete driver days.

    Isochrones deliberately do not participate here.  The only geographic input is
    the exact truck-road travel provider; tariff zones are therefore unable to make
    a slot available or unavailable.
    """

    def __init__(
        self,
        travel_time_provider: TravelTimeProvider,
        configuration: WarehouseSlotConfiguration,
    ) -> None:
        self._travel = travel_time_provider
        self._configuration = configuration

    async def calculate(
        self,
        day_plan: DayPlan,
        *,
        new_task_id: str,
        address: str,
        point: GeoPoint,
        cabin_count: int,
        site_cabin_capacity: int,
        service_duration_minutes: int | None = None,
        trailer_access_allowed: bool | None = None,
    ) -> SlotPlanningResult:
        """Calculate three slots while enforcing site and exceptional-zone trailer access."""

        if cabin_count < 1 or site_cabin_capacity not in {1, 2}:
            raise ValueError("cabin_count must be positive and site capacity must be one or two")

        effective_site_capacity = min(
            site_cabin_capacity,
            2 if trailer_access_allowed is not False else 1,
        )
        normalized, baseline_days, baseline_failure = await self._normalize_existing(day_plan)
        if baseline_failure is not None:
            return SlotPlanningResult(
                date=day_plan.planning_date,
                plan_version=day_plan.version,
                slots=tuple(
                    SlotEvaluation(
                        window=window,
                        status=SlotAvailabilityStatus.UNAVAILABLE,
                        reasons=(PlanningReason.INVALID_DAY_PLAN, baseline_failure),
                        explanation=(
                            REASON_MESSAGES_RU[PlanningReason.INVALID_DAY_PLAN],
                            REASON_MESSAGES_RU[baseline_failure],
                        ),
                    )
                    for window in self._standard_windows(day_plan.planning_date)
                ),
                baseline=normalized,
            )

        evaluations: list[SlotEvaluation] = []
        for window in self._standard_windows(day_plan.planning_date):
            requires_split = cabin_count > effective_site_capacity
            task = SlotTask(
                id=new_task_id,
                task_type=SlotTaskType.DELIVERY,
                point=point,
                quantity=1 if requires_split else cabin_count,
                window=window,
                service_minutes=(
                    service_duration_minutes
                    if service_duration_minutes is not None
                    else self._configuration.delivery_service_minutes
                ),
                priority=10_000,
                address=address,
                mandatory=True,
                trailer_access_allowed=effective_site_capacity == 2,
            )
            failures = FailureAccumulator()
            failures.add(*normalized.availability_reasons)
            if requires_split:
                candidates = await self._split_delivery_candidates(
                    normalized,
                    baseline_days,
                    task,
                    cabin_count,
                    effective_site_capacity,
                    failures,
                )
            else:
                candidates = await self._delivery_candidates(
                    normalized,
                    baseline_days,
                    task,
                    failures,
                )
            if candidates:
                best = min(candidates, key=lambda item: item.score_key)
                evaluations.append(
                    SlotEvaluation(
                        window=window,
                        status=SlotAvailabilityStatus.AVAILABLE,
                        candidates=tuple(sorted(candidates, key=lambda item: item.score_key)),
                        explanation=best.explanation,
                    )
                )
            else:
                reasons = tuple(
                    sorted(
                        failures.reasons
                        or {PlanningReason.NO_FREE_DRIVER, PlanningReason.NO_FREE_TRIP_CAPACITY},
                        key=lambda item: item.value,
                    )
                )
                evaluations.append(
                    SlotEvaluation(
                        window=window,
                        status=SlotAvailabilityStatus.UNAVAILABLE,
                        reasons=reasons,
                        explanation=tuple(REASON_MESSAGES_RU[reason] for reason in reasons),
                    )
                )
        return SlotPlanningResult(
            date=day_plan.planning_date,
            plan_version=day_plan.version,
            slots=tuple(evaluations),
            baseline=normalized,
        )

    def _standard_windows(self, planning_date: date) -> tuple[TimeWindow, ...]:
        """Build the three configured warehouse-local client windows."""

        zone = ZoneInfo(self._configuration.timezone)
        return tuple(
            TimeWindow(
                start=datetime.combine(planning_date, start, tzinfo=zone),
                end=datetime.combine(planning_date, end, tzinfo=zone),
            )
            for start, end in self._configuration.customer_slots
        )

    async def _normalize_existing(
        self,
        day_plan: DayPlan,
    ) -> tuple[DayPlan, dict[str, ScheduledDriverDay], PlanningReason | None]:
        """Assign every unplanned delivery before considering a new customer."""

        normalized = day_plan
        scheduled: dict[str, ScheduledDriverDay] = {}
        for driver in normalized.drivers:
            try:
                scheduled[driver.driver_id] = await self._schedule_with_pickups(
                    driver,
                    (),
                    accepting_requests=day_plan.accepting_requests,
                )
            except ScheduleInfeasible as exc:
                return normalized, scheduled, exc.reason

        for task in sorted(
            normalized.unassigned_deliveries,
            key=lambda item: (-item.priority, item.window.end, item.id),
        ):
            choice = await self._best_existing_insertion(normalized, scheduled, task)
            if choice is None:
                return normalized, scheduled, PlanningReason.DELIVERY_WINDOW_MISSED
            driver, scheduled_day = choice
            normalized = replace(
                normalized,
                drivers=tuple(
                    driver if item.driver_id == driver.driver_id else item
                    for item in normalized.drivers
                ),
                unassigned_deliveries=tuple(
                    item for item in normalized.unassigned_deliveries if item.id != task.id
                ),
            )
            scheduled[driver.driver_id] = scheduled_day
        return normalized, scheduled, None

    async def _best_existing_insertion(
        self,
        day_plan: DayPlan,
        scheduled: dict[str, ScheduledDriverDay],
        task: SlotTask,
    ) -> tuple[DriverPlan, ScheduledDriverDay] | None:
        """Find a feasible home for one already-promised but unassigned delivery."""

        choices: list[tuple[tuple[object, ...], DriverPlan, ScheduledDriverDay]] = []
        for driver in day_plan.drivers:
            if task.assigned_driver_id is not None and task.assigned_driver_id != driver.driver_id:
                continue
            for variant, _, _ in self._driver_insertion_variants(driver, task):
                try:
                    candidate = await self._schedule_with_pickups(
                        variant,
                        day_plan.pickup_pool,
                        accepting_requests=day_plan.accepting_requests,
                    )
                except ScheduleInfeasible:
                    continue
                baseline = scheduled[driver.driver_id]
                choices.append(
                    (
                        (
                            self._travel_seconds(candidate) - self._travel_seconds(baseline),
                            candidate.finish,
                            driver.driver_id,
                        ),
                        variant,
                        candidate,
                    )
                )
        if not choices:
            return None
        _, driver, scheduled_day = min(choices, key=lambda item: item[0])
        return driver, scheduled_day

    async def _delivery_candidates(
        self,
        day_plan: DayPlan,
        baseline_days: dict[str, ScheduledDriverDay],
        task: SlotTask,
        failures: FailureAccumulator,
    ) -> list[SlotCandidate]:
        """Enumerate existing-trip and new-trip insertion positions across drivers."""

        candidates: list[SlotCandidate] = []
        if not day_plan.drivers:
            failures.add(PlanningReason.NO_FREE_DRIVER)
            return candidates
        for driver in day_plan.drivers:
            if task.quantity > driver.vehicle_capacity:
                failures.add(PlanningReason.NO_COMPATIBLE_VEHICLE)
                continue
            if task.quantity == 2 and not driver.has_trailer:
                failures.add(PlanningReason.NO_COMPATIBLE_VEHICLE)
                continue
            for variant, after_id, before_id in self._driver_insertion_variants(driver, task):
                try:
                    scheduled = await self._schedule_with_pickups(
                        variant,
                        day_plan.pickup_pool,
                        accepting_requests=day_plan.accepting_requests,
                    )
                except ScheduleInfeasible as exc:
                    failures.add(exc.reason)
                    continue
                baseline = baseline_days[driver.driver_id]
                timeline = tuple(
                    stop
                    for trip in scheduled.trips
                    for stop in trip.stops
                    if stop.task_id == task.id
                )
                if len(timeline) != 1:
                    failures.add(PlanningReason.INVALID_DAY_PLAN)
                    continue
                stop = timeline[0]
                scheduled_trip = next(
                    trip
                    for trip in scheduled.trips
                    if any(item.task_id == task.id for item in trip.stops)
                )
                affected = tuple(
                    item.id
                    for trip in scheduled.trips
                    if trip.finish >= scheduled_trip.finish
                    for item in trip.stops
                    if item.task_id is not None
                )
                minimum_slack = self._minimum_delivery_slack(variant, scheduled)
                candidates.append(
                    SlotCandidate(
                        driver_plan=variant,
                        baseline_scheduled_day=baseline,
                        scheduled_day=scheduled,
                        trip_id=scheduled_trip.trip_id,
                        insert_after_stop_id=after_id,
                        insert_before_stop_id=before_id,
                        new_task_id=task.id,
                        inserted_task_ids=(task.id,),
                        estimated_service_start=stop.service_start,
                        estimated_finish=stop.service_end,
                        warehouse_return_time=scheduled_trip.finish,
                        minimum_slack_minutes=minimum_slack,
                        incremental_travel_minutes=max(
                            0,
                            ceil(
                                (
                                    self._travel_seconds(scheduled)
                                    - self._travel_seconds(baseline)
                                )
                                / 60
                            ),
                        ),
                        incremental_distance_meters=max(
                            0,
                            self._distance_meters(scheduled) - self._distance_meters(baseline),
                        ),
                        waiting_minutes=sum(trip.waiting_minutes for trip in scheduled.trips),
                        pickup_count=scheduled.pickup_count,
                        affected_stop_ids=affected,
                        explanation=self._candidate_explanation(
                            variant,
                            scheduled_trip,
                            task,
                            after_id,
                            before_id,
                        ),
                    )
                )
        return candidates

    async def _split_delivery_candidates(
        self,
        day_plan: DayPlan,
        baseline_days: dict[str, ScheduledDriverDay],
        task: SlotTask,
        cabin_count: int,
        site_cabin_capacity: int,
        failures: FailureAccumulator,
    ) -> list[SlotCandidate]:
        """Split a multi-cabin order into consecutive site-compatible depot trips."""

        candidates: list[SlotCandidate] = []
        if not day_plan.drivers:
            failures.add(PlanningReason.NO_FREE_DRIVER)
            return candidates
        for driver in day_plan.drivers:
            if driver.vehicle_capacity < 1:
                failures.add(PlanningReason.NO_COMPATIBLE_VEHICLE)
                continue
            per_trip_capacity = (
                2
                if site_cabin_capacity == 2
                and driver.vehicle_capacity >= 2
                and driver.has_trailer
                else 1
            )
            quantities: list[int] = []
            remaining = cabin_count
            while remaining:
                quantity = min(per_trip_capacity, remaining)
                quantities.append(quantity)
                remaining -= quantity
            task_ids = tuple(
                f"{task.id}:part:{part_number}"
                for part_number in range(1, len(quantities) + 1)
            )
            tasks = tuple(
                replace(
                    task,
                    id=task_id,
                    quantity=quantity,
                    trailer_access_allowed=site_cabin_capacity == 2,
                )
                for task_id, quantity in zip(task_ids, quantities, strict=True)
            )
            baseline = baseline_days[driver.driver_id]
            for position in range(len(driver.trips) + 1):
                insertion = tuple(
                    TripPlan(
                        id=(
                            f"slot-trip:{driver.shift_id}:{task.id}:part:"
                            f"{part_number}:{position}"
                        ),
                        deliveries=(part,),
                    )
                    for part_number, part in enumerate(tasks, start=1)
                )
                variant = replace(
                    driver,
                    trips=(
                        *driver.trips[:position],
                        *insertion,
                        *driver.trips[position:],
                    ),
                )
                try:
                    scheduled = await self._schedule_with_pickups(
                        variant,
                        day_plan.pickup_pool,
                        accepting_requests=day_plan.accepting_requests,
                    )
                except ScheduleInfeasible as exc:
                    failures.add(exc.reason)
                    continue
                customer_stops = tuple(
                    stop
                    for scheduled_trip in scheduled.trips
                    for stop in scheduled_trip.stops
                    if stop.task_id in task_ids
                )
                if len(customer_stops) != len(tasks):
                    failures.add(PlanningReason.INVALID_DAY_PLAN)
                    continue
                insertion_trips = tuple(
                    scheduled_trip
                    for scheduled_trip in scheduled.trips
                    if any(stop.task_id in task_ids for stop in scheduled_trip.stops)
                )
                if len(insertion_trips) != len(tasks):
                    failures.add(PlanningReason.INVALID_DAY_PLAN)
                    continue
                earliest_finish = min(item.finish for item in insertion_trips)
                affected = tuple(
                    stop.task_id
                    for scheduled_trip in scheduled.trips
                    if scheduled_trip.finish >= earliest_finish
                    for stop in scheduled_trip.stops
                    if stop.task_id is not None
                )
                batch_label = ", ".join(str(quantity) for quantity in quantities)
                candidates.append(
                    SlotCandidate(
                        driver_plan=variant,
                        baseline_scheduled_day=baseline_days[driver.driver_id],
                        scheduled_day=scheduled,
                        trip_id=insertion_trips[0].trip_id,
                        insert_after_stop_id=None,
                        insert_before_stop_id=None,
                        new_task_id=task.id,
                        inserted_task_ids=task_ids,
                        estimated_service_start=min(
                            stop.service_start for stop in customer_stops
                        ),
                        estimated_finish=max(stop.service_end for stop in customer_stops),
                        warehouse_return_time=insertion_trips[-1].finish,
                        minimum_slack_minutes=self._minimum_delivery_slack(
                            variant,
                            scheduled,
                        ),
                        incremental_travel_minutes=max(
                            0,
                            ceil(
                                (
                                    self._travel_seconds(scheduled)
                                    - self._travel_seconds(baseline)
                                )
                                / 60
                            ),
                        ),
                        incremental_distance_meters=max(
                            0,
                            self._distance_meters(scheduled)
                            - self._distance_meters(baseline),
                        ),
                        waiting_minutes=sum(item.waiting_minutes for item in scheduled.trips),
                        pickup_count=scheduled.pickup_count,
                        affected_stop_ids=affected,
                        explanation=(
                            (
                                f"{cabin_count} БК доставляются {len(tasks)} "
                                "последовательными складскими ходками."
                            ),
                            f"Загрузка по ходкам: {batch_label} БК.",
                            "Начало каждой разгрузки находится внутри выбранного окна.",
                            (
                                "Возвращение после последней ходки на склад в "
                                f"{insertion_trips[-1].finish.strftime('%H:%M')}."
                            ),
                            f"Адрес принимает одновременно {site_cabin_capacity} БК.",
                        ),
                    )
                )
        return candidates

    def _driver_insertion_variants(
        self,
        driver: DriverPlan,
        task: SlotTask,
    ) -> tuple[tuple[DriverPlan, str | None, str | None], ...]:
        """Build insertion variants without mutating any existing task assignment."""

        variants: list[tuple[DriverPlan, str | None, str | None]] = []
        for trip_index, trip in enumerate(driver.trips):
            if trip.trip_locked or task.assigned_trip_id not in {None, trip.id}:
                continue
            if trip.outbound_load + task.quantity > driver.vehicle_capacity:
                continue
            for position in range(len(trip.deliveries) + 1):
                deliveries = (
                    *trip.deliveries[:position],
                    task,
                    *trip.deliveries[position:],
                )
                replacement = replace(trip, deliveries=deliveries)
                after_id = trip.deliveries[position - 1].id if position > 0 else None
                before_id = (
                    trip.deliveries[position].id if position < len(trip.deliveries) else None
                )
                variants.append(
                    (
                        replace(
                            driver,
                            trips=tuple(
                                replacement if index == trip_index else item
                                for index, item in enumerate(driver.trips)
                            ),
                        ),
                        after_id,
                        before_id,
                    )
                )
        if task.assigned_trip_id is None:
            for position in range(len(driver.trips) + 1):
                trip = TripPlan(
                    id=f"slot-trip:{driver.shift_id}:{task.id}:{position}",
                    deliveries=(task,),
                )
                variants.append(
                    (
                        replace(
                            driver,
                            trips=(*driver.trips[:position], trip, *driver.trips[position:]),
                        ),
                        None,
                        None,
                    )
                )
        return tuple(variants)

    async def _schedule_with_pickups(
        self,
        driver: DriverPlan,
        pickup_pool: tuple[SlotTask, ...],
        *,
        accepting_requests: bool = False,
    ) -> ScheduledDriverDay:
        """Schedule all deliveries first, then add only return-leg pickups that fit."""

        mandatory_by_trip = tuple(
            normalized
            for trip in driver.trips
            if (
                normalized := replace(
                    trip,
                    pickups=tuple(item for item in trip.pickups if item.mandatory),
                )
            ).deliveries
            or normalized.pickups
        )
        working = replace(driver, trips=mandatory_by_trip)
        scheduled = await self._simulate_day(working)
        assigned_ids = {task.id for trip in working.trips for task in trip.pickups}
        candidates = {
            task.id: task
            for task in (
                *pickup_pool,
                *(task for trip in driver.trips for task in trip.pickups if not task.mandatory),
            )
            if task.id not in assigned_ids
        }
        deferred: list[str] = []
        for pickup in sorted(
            candidates.values(),
            key=lambda item: (not item.mandatory, -item.priority, item.window.end, item.id),
        ):
            choices: list[tuple[tuple[object, ...], DriverPlan, ScheduledDriverDay]] = []
            for trip_index, trip in enumerate(working.trips):
                if (
                    trip.trip_locked
                    or not trip.deliveries
                    or (
                        accepting_requests
                        and trip.outbound_load < driver.vehicle_capacity
                    )
                    or sum(item.quantity for item in trip.pickups) + pickup.quantity > (
                    driver.vehicle_capacity
                    )
                ):
                    continue
                for position in range(len(trip.pickups) + 1):
                    replacement = replace(
                        trip,
                        pickups=(*trip.pickups[:position], pickup, *trip.pickups[position:]),
                    )
                    variant = replace(
                        working,
                        trips=tuple(
                            replacement if index == trip_index else item
                            for index, item in enumerate(working.trips)
                        ),
                    )
                    try:
                        probe = await self._simulate_day(variant)
                    except ScheduleInfeasible:
                        continue
                    choices.append(
                        (
                            (
                                probe.finish,
                                self._travel_seconds(probe),
                                trip_index,
                                position,
                            ),
                            variant,
                            probe,
                        )
                    )
            if choices:
                _, working, scheduled = min(choices, key=lambda item: item[0])
            elif pickup.mandatory:
                raise ScheduleInfeasible(PlanningReason.NEXT_TRIP_AT_RISK)
            else:
                deferred.append(pickup.id)
        return replace(
            scheduled,
            pickup_count=sum(len(trip.pickups) for trip in working.trips),
            deferred_pickup_ids=tuple(sorted(deferred)),
        )

    async def _simulate_day(self, driver: DriverPlan) -> ScheduledDriverDay:
        """Resimulate every trip and every suffix stop from the beginning of the shift."""

        cursor = max(driver.shift_start, self._local_at(driver.shift_start.date(), "driver_start"))
        hard_finish = min(
            driver.shift_end,
            self._local_at(driver.shift_start.date(), "hard_finish"),
        )
        trips: list[ScheduledTrip] = []
        for index, trip in enumerate(driver.trips):
            if index:
                cursor += timedelta(minutes=self._configuration.turnaround_minutes)
                if cursor >= hard_finish:
                    raise ScheduleInfeasible(PlanningReason.WAREHOUSE_TURNAROUND_TOO_LONG)
            scheduled = await self._simulate_trip(driver, trip, cursor, hard_finish)
            trips.append(scheduled)
            cursor = scheduled.finish
        return ScheduledDriverDay(
            driver=driver,
            trips=tuple(trips),
            pickup_count=sum(len(trip.pickups) for trip in driver.trips),
            deferred_pickup_ids=(),
        )

    async def _simulate_trip(
        self,
        driver: DriverPlan,
        trip: TripPlan,
        start_at: datetime,
        hard_finish: datetime,
    ) -> ScheduledTrip:
        """Simulate one depot trip with live load, waits, windows, and exact roads."""

        if not trip.deliveries and not trip.pickups:
            raise ScheduleInfeasible(PlanningReason.INVALID_DAY_PLAN)
        if trip.pickups and not trip.deliveries:
            raise ScheduleInfeasible(PlanningReason.NEXT_TRIP_AT_RISK)
        outbound = trip.outbound_load
        pickup_load = sum(task.quantity for task in trip.pickups)
        peak_load = max(outbound, pickup_load)
        if peak_load > driver.vehicle_capacity:
            raise ScheduleInfeasible(PlanningReason.VEHICLE_CAPACITY_EXCEEDED)
        trailer_attached = peak_load > 1
        if trailer_attached and not driver.has_trailer:
            raise ScheduleInfeasible(PlanningReason.NO_COMPATIBLE_VEHICLE)
        if trailer_attached and any(
            not task.trailer_access_allowed for task in (*trip.deliveries, *trip.pickups)
        ):
            raise ScheduleInfeasible(PlanningReason.TRAILER_ACCESS_NOT_ALLOWED)

        load_minutes = self._configuration.load_minutes(outbound)
        cursor = start_at
        stops: list[TimelineStop] = [
            TimelineStop(
                id=f"{trip.id}:warehouse-load",
                stop_type=TimelineStopType.WAREHOUSE_LOAD,
                point=self._configuration.point,
                arrival=cursor,
                service_start=cursor,
                service_end=cursor + timedelta(minutes=load_minutes),
                departure=cursor + timedelta(minutes=load_minutes),
                waiting_minutes=0,
                load_before=0,
                load_after=outbound,
                service_minutes=load_minutes,
            )
        ]
        cursor = stops[-1].departure
        current_point = self._configuration.point
        current_load = outbound
        travel_seconds = 0
        distance_meters = 0
        waiting_minutes = 0
        minimum_slack = 24 * 60
        for task in (*trip.deliveries, *trip.pickups):
            state = VehicleLegState(
                vehicle_id=driver.vehicle_id,
                trailer_attached=trailer_attached,
                current_load=current_load,
                trip_peak_load=peak_load,
            )
            metric = await self._road_metric(current_point, task.point, cursor, state)
            buffered_seconds = self._buffered_travel_seconds(metric.travel_seconds)
            arrival = cursor + timedelta(seconds=buffered_seconds)
            if task.time_locked:
                assert task.locked_service_start is not None
                if arrival > task.locked_service_start:
                    raise ScheduleInfeasible(PlanningReason.LOCKED_STOP_CONFLICT)
                service_start = task.locked_service_start
            else:
                service_start = max(arrival, task.window.start)
            if service_start > task.window.end:
                reason = (
                    PlanningReason.DELIVERY_WINDOW_MISSED
                    if task.task_type is SlotTaskType.DELIVERY
                    else PlanningReason.NEXT_TRIP_AT_RISK
                )
                raise ScheduleInfeasible(reason)
            wait = max(0, ceil((service_start - arrival).total_seconds() / 60))
            service_end = service_start + timedelta(minutes=task.service_minutes)
            delta = -task.quantity if task.task_type is SlotTaskType.DELIVERY else task.quantity
            next_load = current_load + delta
            if next_load < 0 or next_load > driver.vehicle_capacity:
                raise ScheduleInfeasible(PlanningReason.VEHICLE_CAPACITY_EXCEEDED)
            stop_type = (
                TimelineStopType.DELIVERY
                if task.task_type is SlotTaskType.DELIVERY
                else TimelineStopType.PICKUP
            )
            stops.append(
                TimelineStop(
                    id=f"{trip.id}:{task.id}",
                    stop_type=stop_type,
                    point=task.point,
                    arrival=arrival,
                    service_start=service_start,
                    service_end=service_end,
                    departure=service_end,
                    waiting_minutes=wait,
                    load_before=current_load,
                    load_after=next_load,
                    service_minutes=task.service_minutes,
                    task_id=task.id,
                    locked=task.time_locked or task.order_locked,
                )
            )
            travel_seconds += buffered_seconds
            distance_meters += metric.distance_meters
            waiting_minutes += wait
            if task.task_type is SlotTaskType.DELIVERY:
                minimum_slack = min(
                    minimum_slack,
                    max(0, round((task.window.end - service_start).total_seconds() / 60)),
                )
            cursor = service_end
            current_point = task.point
            current_load = next_load

        state = VehicleLegState(
            vehicle_id=driver.vehicle_id,
            trailer_attached=trailer_attached,
            current_load=current_load,
            trip_peak_load=peak_load,
        )
        metric = await self._road_metric(
            current_point,
            self._configuration.point,
            cursor,
            state,
        )
        buffered_seconds = self._buffered_travel_seconds(metric.travel_seconds)
        arrival = cursor + timedelta(seconds=buffered_seconds)
        travel_seconds += buffered_seconds
        distance_meters += metric.distance_meters
        unload_minutes = current_load * self._configuration.unload_per_cabin_minutes
        if unload_minutes:
            unload_end = arrival + timedelta(minutes=unload_minutes)
            stops.append(
                TimelineStop(
                    id=f"{trip.id}:warehouse-unload",
                    stop_type=TimelineStopType.WAREHOUSE_UNLOAD,
                    point=self._configuration.point,
                    arrival=arrival,
                    service_start=arrival,
                    service_end=unload_end,
                    departure=unload_end,
                    waiting_minutes=0,
                    load_before=current_load,
                    load_after=0,
                    service_minutes=unload_minutes,
                )
            )
            finish = unload_end
        else:
            finish = arrival
        stops.append(
            TimelineStop(
                id=f"{trip.id}:warehouse-finish",
                stop_type=TimelineStopType.WAREHOUSE_FINISH,
                point=self._configuration.point,
                arrival=finish,
                service_start=finish,
                service_end=finish,
                departure=finish,
                waiting_minutes=0,
                load_before=0,
                load_after=0,
                service_minutes=0,
            )
        )
        if finish > hard_finish:
            raise ScheduleInfeasible(PlanningReason.SHIFT_END_EXCEEDED)
        return ScheduledTrip(
            trip_id=trip.id,
            stops=tuple(stops),
            travel_seconds=travel_seconds,
            distance_meters=distance_meters,
            waiting_minutes=waiting_minutes,
            minimum_slack_minutes=(minimum_slack if minimum_slack < 24 * 60 else 0),
            finish=finish,
        )

    async def _road_metric(
        self,
        origin: GeoPoint,
        destination: GeoPoint,
        departure_at: datetime,
        state: VehicleLegState,
    ) -> RoadMetric:
        """Translate provider failures into the planner's no-truck-route reason."""

        try:
            return await self._travel.travel_time(origin, destination, departure_at, state)
        except TravelTimeUnavailable as exc:
            raise ScheduleInfeasible(exc.reason) from exc
        except (TimeoutError, ConnectionError) as exc:
            raise ScheduleInfeasible(PlanningReason.TRUCK_ROUTE_NOT_FOUND) from exc

    def _buffered_travel_seconds(self, raw_seconds: int) -> int:
        """Apply warehouse-owned conservative multiplier and fixed travel buffer."""

        return ceil(
            raw_seconds * self._configuration.travel_time_multiplier
            + self._configuration.fixed_travel_buffer_minutes * 60
        )

    def _local_at(self, value: date, field: str) -> datetime:
        """Resolve one configured local wall-clock boundary on the planning date."""

        configured = {
            "driver_start": self._configuration.driver_day_start,
            "hard_finish": self._configuration.hard_finish,
        }[field]
        return datetime.combine(value, configured, tzinfo=ZoneInfo(self._configuration.timezone))

    @staticmethod
    def _travel_seconds(day: ScheduledDriverDay) -> int:
        """Return total routed time across a scheduled driver's trips."""

        return sum(trip.travel_seconds for trip in day.trips)

    @staticmethod
    def _distance_meters(day: ScheduledDriverDay) -> int:
        """Return total road distance across a scheduled driver's trips."""

        return sum(trip.distance_meters for trip in day.trips)

    @staticmethod
    def _minimum_delivery_slack(
        driver: DriverPlan,
        scheduled: ScheduledDriverDay,
    ) -> int:
        """Return minimum service-start reserve across every delivery in the day."""

        windows = {
            task.id: task.window
            for trip in driver.trips
            for task in trip.deliveries
        }
        values = [
            max(0, round((windows[stop.task_id].end - stop.service_start).total_seconds() / 60))
            for trip in scheduled.trips
            for stop in trip.stops
            if stop.task_id in windows
        ]
        return min(values, default=0)

    @staticmethod
    def _candidate_explanation(
        driver: DriverPlan,
        trip: ScheduledTrip,
        task: SlotTask,
        after_id: str | None,
        before_id: str | None,
    ) -> tuple[str, ...]:
        """Build concise Russian facts from the winning schedule, never from a zone."""

        if after_id is None and before_id is not None:
            position = "Новая доставка помещается перед первой доставкой ходки."
        elif after_id is not None and before_id is not None:
            position = "Новая доставка помещается между двумя доставками ходки."
        elif after_id is not None:
            position = "Новая доставка помещается после существующей доставки."
        else:
            position = "Для доставки создаётся отдельная складская ходка."
        source_trip = next(item for item in driver.trips if item.id == trip.trip_id)
        trailer = source_trip.outbound_load > 1
        resource = tuple(REASON_MESSAGES_RU[reason] for reason in driver.reason_codes)
        return (
            *resource,
            position,
            f"Вместимость ходки после добавления: {source_trip.outbound_load} из "
            f"{driver.vehicle_capacity}.",
            "Маршрут рассчитан с прицепом." if trailer else "Маршрут рассчитан без прицепа.",
            f"Возвращение на склад в {trip.finish.strftime('%H:%M')} до конца смены.",
            f"Адрес принимает одновременно: {2 if task.trailer_access_allowed else 1} БК.",
        )
