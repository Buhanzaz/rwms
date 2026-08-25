"""Focused tests for bounded cycle-level road-geometry enrichment."""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta
from itertools import pairwise
from zoneinfo import ZoneInfo

import pytest

from app.planner import (
    PlanMetrics,
    PlannedLeg,
    PlanningResult,
    RouteCycle,
    RouteStop,
    StopType,
)
from app.routing import GeoPoint, RouteGeometry, RouteLeg, TravelMatrix
from app.services.planner_runtime import RuntimePlannerFacade

MOSCOW = ZoneInfo("Europe/Moscow")
START = datetime(2026, 8, 24, 8, tzinfo=MOSCOW)


class _RecordingRoutingProvider:
    """Return endpoint-identifiable leg geometry while recording request concurrency."""

    def __init__(self, *, delay_seconds: float = 0.0, omit_last_leg: bool = False) -> None:
        self.delay_seconds = delay_seconds
        self.omit_last_leg = omit_last_leg
        self.calls: list[tuple[tuple[GeoPoint, ...], datetime | None]] = []
        self.active_calls = 0
        self.max_active_calls = 0

    async def get_matrix(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
    ) -> TravelMatrix:
        """Reject unused matrix requests so the test only observes geometry enrichment."""

        del points, departure_at
        raise AssertionError("geometry enrichment must not request a matrix")

    async def get_route(
        self,
        points: list[GeoPoint],
        departure_at: datetime | None,
    ) -> RouteGeometry:
        """Build one route leg per adjacent point and track concurrent calls."""

        immutable_points = tuple(points)
        self.calls.append((immutable_points, departure_at))
        self.active_calls += 1
        self.max_active_calls = max(self.max_active_calls, self.active_calls)
        try:
            if self.delay_seconds:
                await asyncio.sleep(self.delay_seconds)
            legs = tuple(
                RouteLeg(
                    from_index=index,
                    to_index=index + 1,
                    distance_meters=90_000 + index,
                    travel_seconds=9_000 + index,
                    geometry=_line(first, second),
                )
                for index, (first, second) in enumerate(pairwise(immutable_points))
            )
            if self.omit_last_leg:
                legs = legs[:-1]
            return RouteGeometry(
                geometry={
                    "type": "LineString",
                    "coordinates": [list(point.coordinates) for point in immutable_points],
                },
                legs=legs,
                total_distance_meters=sum(leg.distance_meters for leg in legs),
                total_travel_seconds=sum(leg.travel_seconds for leg in legs),
            )
        finally:
            self.active_calls -= 1


def _line(first: GeoPoint, second: GeoPoint) -> dict[str, object]:
    return {
        "type": "LineString",
        "coordinates": [list(first.coordinates), list(second.coordinates)],
    }


def _cycle(cycle_index: int, customer_count: int = 3) -> RouteCycle:
    points = (
        GeoPoint(37.60, 55.70),
        *(GeoPoint(37.60 + cycle_index / 100 + index / 1000, 55.70 + index / 1000)
          for index in range(1, customer_count + 1)),
        GeoPoint(37.60, 55.70),
    )
    stops = tuple(
        RouteStop(
            sequence=index,
            stop_type=(
                StopType.DEPOT_LOAD
                if index == 0
                else StopType.DEPOT_RETURN
                if index == len(points) - 1
                else StopType.DELIVERY
            ),
            point=point,
            planned_arrival=START + timedelta(minutes=10 * index),
            planned_departure=START + timedelta(minutes=10 * index),
            service_seconds=0,
            quantity_delta=0,
            load_before=0,
            load_after=0,
        )
        for index, point in enumerate(points)
    )
    legs = tuple(
        PlannedLeg(
            from_stop_sequence=index,
            to_stop_sequence=index + 1,
            departure_at=START + timedelta(minutes=10 * index),
            arrival_at=START + timedelta(minutes=10 * (index + 1)),
            distance_meters=1_000 + cycle_index * 10 + index,
            travel_seconds=600 + index,
            geometry=_line(points[index], points[index + 1]),
        )
        for index in range(len(points) - 1)
    )
    return RouteCycle(
        id=f"cycle-{cycle_index}",
        driver_shift_id=f"shift-{cycle_index}",
        driver_id=f"driver-{cycle_index}",
        vehicle_id=f"vehicle-{cycle_index}",
        sequence=cycle_index + 1,
        planned_start=START,
        planned_finish=START + timedelta(minutes=10 * (len(points) - 1)),
        stops=stops,
        legs=legs,
        total_distance_meters=sum(leg.distance_meters for leg in legs),
        total_travel_seconds=sum(leg.travel_seconds for leg in legs),
        total_service_seconds=0,
        waiting_seconds=0,
        empty_distance_meters=0,
        detour_seconds=0,
        score=float(cycle_index),
    )


def _result(cycles: tuple[RouteCycle, ...]) -> PlanningResult:
    return PlanningResult(
        cycles=cycles,
        unassigned=(),
        tasks=(),
        score=0,
        metrics=PlanMetrics(
            total_tasks=0,
            assigned_tasks=0,
            unassigned_tasks=0,
            assignment_percent=100,
            cycle_count=len(cycles),
            active_shift_count=len(cycles),
            total_distance_meters=0,
            empty_distance_meters=0,
            empty_distance_percent=0,
            total_travel_seconds=0,
            total_service_seconds=0,
            total_waiting_seconds=0,
            total_detour_seconds=0,
            paired_delivery_count=0,
            paired_pickup_count=0,
            average_vehicle_load=0,
            shift_utilization_percent=0,
            overtime_seconds=0,
            minimum_buffer_seconds=0,
            score=0,
        ),
        trace_events=(),
        seed=17,
    )


async def test_enrichment_requests_one_route_per_cycle_and_maps_each_leg_geometry() -> None:
    cycles = (_cycle(0, 3), _cycle(1, 2), _cycle(2, 1))
    provider = _RecordingRoutingProvider()

    enriched = await RuntimePlannerFacade._attach_road_geometries(
        _result(cycles), provider
    )

    assert len(provider.calls) == len(cycles)
    assert [cycle.id for cycle in enriched.cycles] == [cycle.id for cycle in cycles]
    for original, actual in zip(cycles, enriched.cycles, strict=True):
        assert actual.total_distance_meters == original.total_distance_meters
        assert actual.total_travel_seconds == original.total_travel_seconds
        assert [leg.distance_meters for leg in actual.legs] == [
            leg.distance_meters for leg in original.legs
        ]
        assert [leg.travel_seconds for leg in actual.legs] == [
            leg.travel_seconds for leg in original.legs
        ]
        assert [leg.geometry for leg in actual.legs] == [
            _line(first.point, second.point)
            for first, second in pairwise(actual.stops)
        ]

    assert sorted(len(points) for points, _departure_at in provider.calls) == sorted(
        len(cycle.stops) for cycle in cycles
    )


async def test_enrichment_limits_route_requests_to_four_concurrent_cycles() -> None:
    cycles = tuple(_cycle(index, 1) for index in range(9))
    provider = _RecordingRoutingProvider(delay_seconds=0.01)

    enriched = await RuntimePlannerFacade._attach_road_geometries(
        _result(cycles), provider
    )

    assert [cycle.id for cycle in enriched.cycles] == [cycle.id for cycle in cycles]
    assert provider.max_active_calls == 4


async def test_enrichment_rejects_a_route_with_the_wrong_leg_count() -> None:
    provider = _RecordingRoutingProvider(omit_last_leg=True)

    with pytest.raises(RuntimeError, match="route leg count"):
        await RuntimePlannerFacade._attach_road_geometries(
            _result((_cycle(0, 2),)), provider
        )
