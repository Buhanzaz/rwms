"""Exact per-leg truck routing for one proposed logistics cycle."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import replace
from datetime import datetime, timedelta
from itertools import pairwise

from app.planner.engine import CandidateRouteRejected
from app.planner.models import (
    DriverShift,
    PlannedLeg,
    PlanningSettings,
    PlanningTask,
    RouteCycle,
    RouteStop,
    StopType,
    UnassignedReasonCode,
    ValidationWarningCode,
    Vehicle,
)
from app.routing.models import RouteGeometry
from app.routing.provider import RoutingProvider
from app.routing.truck_profile import (
    CargoPlacement,
    CargoPosition,
    EffectiveTruckProfile,
    EffectiveTruckProfileCalculator,
    LoadConfiguration,
    TruckProfileError,
)

_MAX_WINDOW_DEPARTURE_ADJUSTMENTS = 4
_MAX_WAREHOUSE_DELAY_ADJUSTMENTS = 8


class ExactTruckCycleRouter:
    """Route and reschedule every leg using its current physical load state."""

    def __init__(
        self,
        provider: RoutingProvider,
        *,
        provider_name: str,
        osm_data_version: str,
        now: Callable[[], datetime],
        calculator: EffectiveTruckProfileCalculator | None = None,
    ) -> None:
        self._provider = provider
        self._provider_name = provider_name
        self._osm_data_version = osm_data_version
        self._now = now
        self._calculator = calculator or EffectiveTruckProfileCalculator()

    async def route_candidate(
        self,
        cycle: RouteCycle,
        *,
        tasks: tuple[PlanningTask, ...],
        vehicle: Vehicle,
        shift: DriverShift,
        settings: PlanningSettings,
    ) -> RouteCycle:
        """Return an exact truck-safe candidate or a stable rejection reason."""

        return await self._route_candidate(
            cycle,
            tasks=tasks,
            vehicle=vehicle,
            shift=shift,
            settings=settings,
            warehouse_delay_attempt=0,
        )

    async def _route_candidate(
        self,
        cycle: RouteCycle,
        *,
        tasks: tuple[PlanningTask, ...],
        vehicle: Vehicle,
        shift: DriverShift,
        settings: PlanningSettings,
        warehouse_delay_attempt: int,
    ) -> RouteCycle:
        """Route one candidate, retrying from a later depot start when feasible."""

        if vehicle.routing_spec is None:
            raise CandidateRouteRejected(
                UnassignedReasonCode.ROUTING_PROFILE_INCOMPLETE,
                "Vehicle has no physical truck-routing specification",
                missing_fields=("vehicle.routing_spec",),
            )
        ordered_stops = tuple(sorted(cycle.stops, key=lambda item: item.sequence))
        if len(ordered_stops) < 2:
            raise CandidateRouteRejected(
                UnassignedReasonCode.NO_SAFE_ROUTE,
                "A route cycle must contain at least two stops",
            )
        task_by_id = {task.id: task for task in tasks}
        trailer_attached = max(stop.load_after for stop in ordered_stops) > 1
        if trailer_attached and any(not task.trailer_access_allowed for task in tasks):
            raise CandidateRouteRejected(
                UnassignedReasonCode.TRAILER_ACCESS_NOT_ALLOWED,
                "Dispatcher did not approve trailer access for every visited address",
            )
        trailer = vehicle.default_trailer if trailer_attached else None
        if trailer_attached and trailer is None:
            raise CandidateRouteRejected(
                UnassignedReasonCode.NO_COMPATIBLE_TRAILER,
                "A two-unit route requires the vehicle's compatible trailer",
            )

        placements = self._initial_delivery_placements(
            ordered_stops,
            task_by_id,
            trailer_attached=trailer_attached,
        )
        if len(placements) != ordered_stops[0].load_after:
            raise CandidateRouteRejected(
                UnassignedReasonCode.ROUTING_PROFILE_INCOMPLETE,
                "Initial cargo placement does not match the planned depot load",
                missing_fields=("route.initial_cargo_placements",),
            )

        start = max(cycle.planned_start, shift.start_at)
        first = replace(
            ordered_stops[0],
            planned_arrival=start,
            planned_departure=start + timedelta(seconds=ordered_stops[0].service_seconds),
        )
        routed_stops: list[RouteStop] = [first]
        routed_legs: list[PlannedLeg] = []
        waiting_seconds = 0
        empty_distance_meters = 0
        warnings = set(cycle.warnings).difference(
            {
                ValidationWarningCode.SOFT_WINDOW_RISK,
                ValidationWarningCode.OVERTIME_WARNING,
            }
        )
        calculated_at = self._now()

        for source, target in pairwise(ordered_stops):
            routed_source = routed_stops[-1]
            try:
                profile = self._calculator.calculate(
                    vehicle=vehicle.routing_spec,
                    load=LoadConfiguration(
                        vehicle_id=vehicle.id,
                        trailer_attached=trailer_attached,
                        trailer_id=(trailer.trailer_id if trailer is not None else None),
                        cargo_placements=tuple(placements),
                    ),
                    axle_profiles=vehicle.axle_load_profiles,
                    trailer=trailer,
                )
            except TruckProfileError as exc:
                raise CandidateRouteRejected(
                    UnassignedReasonCode(exc.code.value),
                    exc.detail,
                    missing_fields=exc.missing_fields,
                ) from exc

            try:
                route, leg_departure, arrival = await self._route_leg(
                    source=source,
                    target=target,
                    source_ready_at=routed_source.planned_departure,
                    profile=profile,
                )
            except Exception as exc:
                code = getattr(exc, "code", None)
                if code in {"NO_SAFE_ROUTE", "ROUTING_PROFILE_INCOMPLETE"}:
                    raise CandidateRouteRejected(
                        UnassignedReasonCode(str(code)),
                        str(exc),
                        missing_fields=tuple(getattr(exc, "missing_fields", ())),
                    ) from exc
                raise
            if len(route.legs) != 1:
                raise RuntimeError("truck provider must return exactly one leg per segment")
            road_leg = route.legs[0]
            source_waiting_seconds = round(
                (leg_departure - routed_source.planned_departure).total_seconds()
            )
            if source_waiting_seconds:
                if (
                    len(routed_stops) == 1
                    and routed_source.stop_type is StopType.DEPOT_LOAD
                    and target.task_id is not None
                ):
                    shifted_start = leg_departure - timedelta(seconds=routed_source.service_seconds)
                    routed_source = replace(
                        routed_source,
                        planned_arrival=shifted_start,
                        planned_departure=leg_departure,
                    )
                    routed_stops[-1] = routed_source
                else:
                    warehouse_delay = self._warehouse_delay_before_source(
                        routed_stops,
                        timedelta(seconds=source_waiting_seconds),
                    )
                    if (
                        warehouse_delay > timedelta(0)
                        and warehouse_delay_attempt < _MAX_WAREHOUSE_DELAY_ADJUSTMENTS
                    ):
                        shifted_cycle = replace(
                            cycle,
                            planned_start=start + warehouse_delay,
                            planned_finish=cycle.planned_finish + warehouse_delay,
                        )
                        try:
                            return await self._route_candidate(
                                shifted_cycle,
                                tasks=tasks,
                                vehicle=vehicle,
                                shift=shift,
                                settings=settings,
                                warehouse_delay_attempt=warehouse_delay_attempt + 1,
                            )
                        except CandidateRouteRejected:
                            # A time-dependent route can become infeasible after the
                            # depot shift. In that case the already safe earlier
                            # departure remains available with bounded source waiting.
                            pass
                    if source_waiting_seconds > settings.max_customer_wait_minutes * 60:
                        raise CandidateRouteRejected(
                            UnassignedReasonCode.TIME_WINDOW_CONFLICT,
                            (
                                "Exact route requires more waiting between customer "
                                "stops than the workspace permits"
                            ),
                        )
                    waiting_seconds += source_waiting_seconds
            departure = arrival + timedelta(seconds=target.service_seconds)
            if target.window_end is not None and arrival > target.window_end:
                if target.window_is_hard:
                    raise CandidateRouteRejected(
                        UnassignedReasonCode.TIME_WINDOW_CONFLICT,
                        "Exact truck route violates a hard customer time window",
                    )
                warnings.add(ValidationWarningCode.SOFT_WINDOW_RISK)
            routed_target = replace(
                target,
                planned_arrival=arrival,
                planned_departure=departure,
            )
            snapshot = profile.to_snapshot(
                provider=self._provider_name,
                osm_data_version=self._osm_data_version,
                calculated_at=calculated_at,
            )
            routed_legs.append(
                PlannedLeg(
                    from_stop_sequence=source.sequence,
                    to_stop_sequence=target.sequence,
                    departure_at=leg_departure,
                    arrival_at=arrival,
                    distance_meters=road_leg.distance_meters,
                    travel_seconds=road_leg.travel_seconds,
                    geometry=road_leg.geometry,
                    routing_profile_snapshot=snapshot,
                    routing_provider=self._provider_name,
                    osm_data_version=self._osm_data_version,
                    routed_at=calculated_at,
                )
            )
            if not placements:
                empty_distance_meters += road_leg.distance_meters
            routed_stops.append(routed_target)
            placements = self._placements_after_stop(
                placements,
                routed_target,
                task_by_id,
                trailer_attached=trailer_attached,
            )
            if len(placements) != routed_target.load_after:
                raise CandidateRouteRejected(
                    UnassignedReasonCode.ROUTING_PROFILE_INCOMPLETE,
                    "Cargo placement transition does not match the planned load",
                    missing_fields=(f"route.stop[{target.sequence}].cargo_placements",),
                )

        finish = routed_stops[-1].planned_departure
        overtime_seconds = max(0, round((finish - shift.end_at).total_seconds()))
        if overtime_seconds and not (
            settings.allow_soft_overtime
            and overtime_seconds <= settings.soft_overtime_limit_minutes * 60
        ):
            raise CandidateRouteRejected(
                UnassignedReasonCode.SHIFT_LIMIT_EXCEEDED,
                "Exact truck route finishes after the hard shift limit",
            )
        if overtime_seconds:
            warnings.add(ValidationWarningCode.OVERTIME_WARNING)

        total_travel_seconds = sum(item.travel_seconds for item in routed_legs)
        total_distance_meters = sum(item.distance_meters for item in routed_legs)
        old_empty_cost = self._empty_travel_cost(
            cycle.empty_distance_meters,
            cycle.total_distance_meters,
            cycle.total_travel_seconds,
            settings.empty_travel_weight,
        )
        new_empty_cost = self._empty_travel_cost(
            empty_distance_meters,
            total_distance_meters,
            total_travel_seconds,
            settings.empty_travel_weight,
        )
        score = (
            cycle.score
            - cycle.total_travel_seconds / 60.0
            - old_empty_cost
            - cycle.waiting_seconds / 60.0 * settings.waiting_weight
            + total_travel_seconds / 60.0
            + new_empty_cost
            + waiting_seconds / 60.0 * settings.waiting_weight
        )
        return replace(
            cycle,
            planned_start=routed_stops[0].planned_arrival,
            planned_finish=finish,
            stops=tuple(routed_stops),
            legs=tuple(routed_legs),
            total_distance_meters=total_distance_meters,
            total_travel_seconds=total_travel_seconds,
            waiting_seconds=waiting_seconds,
            empty_distance_meters=empty_distance_meters,
            score=round(score, 6),
            explanation=(
                *cycle.explanation,
                "Каждый участок проверен отдельно для фактической загрузки и состояния прицепа.",
            ),
            warnings=tuple(sorted(warnings, key=lambda item: item.value)),
        )

    @staticmethod
    def _warehouse_delay_before_source(
        routed_stops: list[RouteStop],
        source_wait: timedelta,
    ) -> timedelta:
        """Return wait absorbable at the depot without breaking prior windows."""

        prior_slack = [
            stop.window_end - stop.planned_departure
            for stop in routed_stops
            if stop.task_id is not None and stop.window_end is not None
        ]
        available = min(prior_slack, default=source_wait)
        return min(source_wait, max(timedelta(0), available))

    async def _route_leg(
        self,
        *,
        source: RouteStop,
        target: RouteStop,
        source_ready_at: datetime,
        profile: EffectiveTruckProfile,
    ) -> tuple[RouteGeometry, datetime, datetime]:
        """Route at the actual departure while keeping early-window waits at source.

        Travel duration may change after delaying departure. The bounded adjustment
        therefore asks the provider again with every proposed departure. A final
        conservative request at ``window_start`` guarantees that an unstable
        time-dependent provider can never expose an arrival before the agreed window.
        """

        departure = source_ready_at
        for _ in range(_MAX_WINDOW_DEPARTURE_ADJUSTMENTS):
            route = await self._provider.get_route(
                [source.point, target.point],
                departure,
                profile=profile,
            )
            if len(route.legs) != 1:
                raise RuntimeError("truck provider must return exactly one leg per segment")
            arrival = departure + timedelta(seconds=route.legs[0].travel_seconds)
            if target.window_start is None or arrival >= target.window_start:
                return route, departure, arrival
            departure += target.window_start - arrival

        if target.window_start is not None and departure < target.window_start:
            departure = target.window_start
        route = await self._provider.get_route(
            [source.point, target.point],
            departure,
            profile=profile,
        )
        if len(route.legs) != 1:
            raise RuntimeError("truck provider must return exactly one leg per segment")
        arrival = departure + timedelta(seconds=route.legs[0].travel_seconds)
        return route, departure, arrival

    @staticmethod
    def _initial_delivery_placements(
        stops: tuple[RouteStop, ...],
        task_by_id: dict[str, PlanningTask],
        *,
        trailer_attached: bool,
    ) -> list[CargoPlacement]:
        """Place outbound cargo on truck first and trailer second."""

        placements: list[CargoPlacement] = []
        for stop in stops:
            if stop.stop_type is not StopType.DELIVERY or stop.task_id is None:
                continue
            task = task_by_id.get(stop.task_id)
            if task is None or task.cargo_dimensions is None:
                raise CandidateRouteRejected(
                    UnassignedReasonCode.ROUTING_PROFILE_INCOMPLETE,
                    "Delivery cargo dimensions are missing",
                    missing_fields=(f"task[{stop.task_id}].cargo_dimensions",),
                )
            ExactTruckCycleRouter._append_task_cargo(
                placements,
                task,
                trailer_attached=trailer_attached,
            )
        return placements

    @staticmethod
    def _placements_after_stop(
        placements: list[CargoPlacement],
        stop: RouteStop,
        task_by_id: dict[str, PlanningTask],
        *,
        trailer_attached: bool,
    ) -> list[CargoPlacement]:
        """Apply a customer load transition without implicitly detaching a trailer."""

        if stop.task_id is None:
            return [] if stop.stop_type is StopType.DEPOT_RETURN else list(placements)
        task = task_by_id.get(stop.task_id)
        if task is None:
            raise CandidateRouteRejected(
                UnassignedReasonCode.ROUTING_PROFILE_INCOMPLETE,
                "Route stop references cargo absent from the candidate",
                missing_fields=(f"task[{stop.task_id}]",),
            )
        cargo_ids = {ExactTruckCycleRouter._cargo_id(task, index) for index in range(task.quantity)}
        result = [item for item in placements if str(item.cargo_id) not in cargo_ids]
        if stop.stop_type is StopType.DELIVERY:
            if len(placements) - len(result) != task.quantity:
                raise CandidateRouteRejected(
                    UnassignedReasonCode.ROUTING_PROFILE_INCOMPLETE,
                    "Delivery cargo was not present on the vehicle",
                    missing_fields=(f"task[{task.id}].cargo_placement",),
                )
            return result
        if stop.stop_type is StopType.PICKUP:
            if task.cargo_dimensions is None:
                raise CandidateRouteRejected(
                    UnassignedReasonCode.ROUTING_PROFILE_INCOMPLETE,
                    "Pickup cargo dimensions are missing",
                    missing_fields=(f"task[{task.id}].cargo_dimensions",),
                )
            ExactTruckCycleRouter._append_task_cargo(
                result,
                task,
                trailer_attached=trailer_attached,
            )
        return result

    @staticmethod
    def _append_task_cargo(
        placements: list[CargoPlacement],
        task: PlanningTask,
        *,
        trailer_attached: bool,
    ) -> None:
        """Allocate each unit to the first free supported platform."""

        if task.cargo_dimensions is None:
            raise CandidateRouteRejected(
                UnassignedReasonCode.ROUTING_PROFILE_INCOMPLETE,
                "Cargo dimensions are missing",
                missing_fields=(f"task[{task.id}].cargo_dimensions",),
            )
        occupied = {item.position for item in placements}
        for index in range(task.quantity):
            if CargoPosition.TRUCK_PLATFORM not in occupied:
                position = CargoPosition.TRUCK_PLATFORM
            elif trailer_attached and CargoPosition.TRAILER_PLATFORM not in occupied:
                position = CargoPosition.TRAILER_PLATFORM
            else:
                raise CandidateRouteRejected(
                    UnassignedReasonCode.TRAILER_REQUIRED,
                    "The planned load has no free compatible cargo platform",
                )
            placements.append(
                CargoPlacement(
                    cargo_id=ExactTruckCycleRouter._cargo_id(task, index),
                    position=position,
                    dimensions=task.cargo_dimensions,
                )
            )
            occupied.add(position)

    @staticmethod
    def _cargo_id(task: PlanningTask, index: int) -> str:
        """Build a stable per-unit identity for snapshots and cache keys."""

        return f"{task.id}:cargo:{index + 1}"

    @staticmethod
    def _empty_travel_cost(
        empty_distance_meters: int,
        total_distance_meters: int,
        total_travel_seconds: int,
        weight: float,
    ) -> float:
        """Mirror the heuristic's empty-distance objective component."""

        if total_distance_meters <= 0:
            return 0.0
        return (
            empty_distance_meters / total_distance_meters * (total_travel_seconds / 60.0) * weight
        )
