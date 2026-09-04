"""Reproducible offline candidate-selection benchmark; never mutates product data.

Run from the backend with ``python -m scripts.compare_planner_candidates`` after
installing ``.[dev,solver-benchmark]``. Synthetic travel is explicit by default.
Optional Valhalla mode requires an exact road-data identity and never falls back.
The physical vehicle fixture is shared with the exact truck routing regressions.
"""

from __future__ import annotations

import argparse
import asyncio
import json
from dataclasses import asdict, replace
from datetime import timedelta
from hashlib import sha256
from math import ceil
from time import perf_counter, process_time
from typing import Any

import ortools

from app.planner import (
    HeuristicPlanner,
    LogisticsRequest,
    NullProgressPublisher,
    PlanningInput,
    PlanningResult,
    PlanningSettings,
    RequestDateOption,
    RequestStatus,
    RouteCycle,
    TaskType,
    Warehouse,
)
from app.planner.candidate_comparison import RoutedCandidatePool, compare_candidate_selection
from app.routing import GeoPoint, MockRoutingProvider, ValhallaRoutingProvider
from app.services.truck_cycle_router import ExactTruckCycleRouter
from tests.test_truck_cycle_router import CARGO, START, _shift, _vehicle


def benchmark_input(size: int) -> PlanningInput:
    """Create one immutable mixed-day corpus with explicit, non-customer coordinates."""

    if not 1 <= size <= 100:
        raise ValueError("benchmark sizes must be between 1 and 100 tasks")
    depot = Warehouse("benchmark", "Synthetic depot", GeoPoint(30.32, 59.93))
    vehicles = tuple(
        replace(
            _vehicle(),
            id=f"vehicle-{index}",
            routing_spec=replace(
                _vehicle().routing_spec,
                vehicle_id=f"vehicle-{index}",
            ),
            default_trailer=replace(
                _vehicle().default_trailer,
                trailer_id=f"trailer-{index}",
            ),
        )
        for index in range(max(1, ceil(size / 8)))
    )
    shifts = tuple(
        replace(_shift(), id=f"shift-{index}", driver_id=f"driver-{index}", vehicle_id=vehicle.id)
        for index, vehicle in enumerate(vehicles)
    )
    requests = tuple(
        LogisticsRequest(
            id=f"request-{index:03}",
            request_type=TaskType.PICKUP if index % 3 == 2 else TaskType.DELIVERY,
            name=f"Benchmark {index}",
            address_label=f"Synthetic point {index}",
            point=GeoPoint(30.28 + (index * 7 % 13) * 0.012, 59.88 + (index * 11 % 13) * 0.008),
            quantity=1,
            service_minutes=20,
            priority=1 + index % 3,
            status=RequestStatus.READY,
            created_at=START - timedelta(days=1),
            date_options=(
                RequestDateOption(
                    START.date(),
                    priority=1,
                    window_start=START + timedelta(hours=2 if index % 5 == 0 else 0),
                    window_end=START + timedelta(hours=6 if index % 5 == 0 else 11),
                    is_hard=index % 3 != 2,
                ),
            ),
            cargo_dimensions=CARGO,
            trailer_access_allowed=True,
            mandatory=index % 3 != 2,
        )
        for index in range(size)
    )
    return PlanningInput(depot.id, START.date(), depot, requests, shifts, vehicles)


def measurements(
    cycles: tuple[RouteCycle, ...], baseline: PlanningResult
) -> dict[str, int | float]:
    """Report assignment and transport facts without customer payloads or misleading scores."""

    assigned = {task_id for cycle in cycles for task_id in cycle.task_ids}
    return {
        "delivery_cabins": sum(
            task.quantity
            for task in baseline.tasks
            if task.id in assigned and task.task_type is TaskType.DELIVERY
        ),
        "pickup_cabins": sum(
            task.quantity
            for task in baseline.tasks
            if task.id in assigned and task.task_type is TaskType.PICKUP
        ),
        "mandatory_unassigned": sum(
            task.mandatory and task.id not in assigned for task in baseline.tasks
        ),
        "vehicles": len({cycle.vehicle_id for cycle in cycles}),
        "distance_km": round(sum(cycle.total_distance_meters for cycle in cycles) / 1000, 3),
        "empty_km": round(sum(cycle.empty_distance_meters for cycle in cycles) / 1000, 3),
        "travel_seconds": sum(cycle.total_travel_seconds for cycle in cycles),
    }


async def benchmark(size: int, args: argparse.Namespace) -> dict[str, Any]:
    """Run baseline plus native selection using exactly the same successful road evaluations."""

    input_data = benchmark_input(size)
    settings = PlanningSettings(seed=41, max_optimization_seconds=args.heuristic_seconds)
    provider = (
        ValhallaRoutingProvider(args.valhalla_url, osm_data_version=args.osm_data_version)
        if args.valhalla_url
        else MockRoutingProvider()
    )
    routing_name = "valhalla" if args.valhalla_url else "synthetic"
    router = ExactTruckCycleRouter(
        provider,
        provider_name=routing_name,
        osm_data_version=args.osm_data_version if args.valhalla_url else "synthetic-grid-v1",
        now=lambda: START,
    )
    pool = RoutedCandidatePool(router, limit=args.pool_limit)
    started, cpu_started = perf_counter(), process_time()
    try:
        baseline = await HeuristicPlanner(MockRoutingProvider(), pool).generate_plan(
            input_data,
            settings,
            NullProgressPublisher(),
        )
        generation_seconds = perf_counter() - started
        generation_cpu_seconds = process_time() - cpu_started
        tasks = {task.id: task for task in baseline.tasks}
        vehicles = {vehicle.id: vehicle for vehicle in input_data.vehicles}

        def verify(cycle: RouteCycle) -> None:
            router.assert_current_route(
                cycle,
                tasks=tuple(tasks[task_id] for task_id in cycle.task_ids),
                vehicle=vehicles[cycle.vehicle_id],
            )

        comparison = await asyncio.to_thread(
            compare_candidate_selection,
            input_data,
            settings,
            baseline,
            pool,
            verify_route=verify,
            max_seconds=args.native_seconds,
        )
        return {
            "corpus_sha256": sha256(
                json.dumps(asdict(input_data), sort_keys=True, default=str).encode()
            ).hexdigest(),
            "size": len(baseline.tasks),
            "routing": routing_name,
            "ortools": ortools.__version__,
            "generation_seconds": round(generation_seconds, 4),
            "generation_cpu_seconds": round(generation_cpu_seconds, 4),
            "heuristic_timed_out": baseline.timed_out,
            "baseline": measurements(baseline.cycles, baseline),
            "native": measurements(comparison.cycles, baseline)
            if comparison.cycles is not None
            else None,
            "native_status": comparison.status,
            "native_seconds": round(comparison.elapsed_seconds, 4),
            "optimal_stages": comparison.optimal_stages,
            "total_stages": comparison.total_stages,
            "candidates": comparison.candidate_count,
            "discarded_candidates": comparison.discarded_candidates,
            "native_additional_road_calls": 0,
        }
    finally:
        if isinstance(provider, ValhallaRoutingProvider):
            await provider.aclose()


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sizes", type=int, nargs="+", default=[5, 10, 20, 50, 100])
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--heuristic-seconds", type=float, default=3)
    parser.add_argument("--native-seconds", type=float, default=3)
    parser.add_argument("--pool-limit", type=int, default=512)
    parser.add_argument("--valhalla-url")
    parser.add_argument("--osm-data-version")
    args = parser.parse_args()
    if args.valhalla_url and not args.osm_data_version:
        parser.error("Valhalla comparison requires --osm-data-version; no synthetic fallback")
    if not 1 <= args.repeat <= 10:
        parser.error("repeat must be between 1 and 10")
    for repeat in range(args.repeat):
        for size in args.sizes:
            print(
                json.dumps({"repeat": repeat + 1, **await benchmark(size, args)}, sort_keys=True),
                flush=True,
            )


if __name__ == "__main__":
    asyncio.run(main())
