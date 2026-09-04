"""Native selection cannot trade away delivery commitments or immutable route proofs."""

from dataclasses import replace
from datetime import timedelta

import pytest

pytest.importorskip("ortools", reason="optional solver-benchmark extra is not installed")

from app.planner import (
    CandidateRouteRejected,
    PlanningInput,
    PlanningResult,
    PlanningSettings,
    Warehouse,
)
from app.planner.candidate_comparison import (
    RoutedCandidatePool,
    _conflict,
    candidate_fingerprint,
    compare_candidate_selection,
)
from app.planner.validation import calculate_plan_metrics
from app.services.truck_cycle_router import ExactTruckCycleRouter
from tests.test_truck_cycle_router import (
    DEPOT,
    FIRST,
    SECOND,
    START,
    RecordingTruckProvider,
    _cycle,
    _shift,
    _task,
    _vehicle,
)


@pytest.fixture
async def comparison_case():
    """One expensive combination versus two safe solo trips on the same truck."""

    router = ExactTruckCycleRouter(
        RecordingTruckProvider(),
        provider_name="test",
        osm_data_version="fixture-v1",
        now=lambda: START,
    )
    settings = PlanningSettings(seed=41)
    vehicle, shift = _vehicle(), _shift()
    tasks = (_task("first", FIRST, 1), _task("second", SECOND, 1))
    combined = await router.route_candidate(
        _cycle(tasks),
        tasks=tasks,
        vehicle=vehicle,
        shift=shift,
        settings=settings,
    )
    pool = RoutedCandidatePool(router)
    for index, task in enumerate(tasks):
        source = replace(
            _cycle((task,)),
            id=f"solo-{index}",
            planned_start=START + timedelta(hours=index * 3),
            planned_finish=_cycle((task,)).planned_finish + timedelta(hours=index * 3),
        )
        await pool.route_candidate(
            source,
            tasks=(task,),
            vehicle=vehicle,
            shift=shift,
            settings=settings,
        )
    input_data = PlanningInput(
        "warehouse",
        START.date(),
        Warehouse("warehouse", "Depot", DEPOT),
        (),
        (shift,),
        (vehicle,),
    )
    baseline = PlanningResult(
        (combined,),
        (),
        tasks,
        combined.score,
        calculate_plan_metrics(
            (combined,), total_tasks=2, unassigned_tasks=0, score=combined.score, shifts=(shift,)
        ),
        (),
        41,
    )

    def verify(cycle):
        router.assert_current_route(
            cycle,
            tasks=tuple(task for task in tasks if task.id in cycle.task_ids),
            vehicle=vehicle,
        )

    return input_data, settings, baseline, pool, verify


def test_native_selection_keeps_deliveries_and_uses_shorter_verified_trips(comparison_case):
    input_data, settings, baseline, pool, verify = comparison_case
    original = baseline.cycles
    comparison = compare_candidate_selection(
        input_data,
        settings,
        baseline,
        pool,
        verify_route=verify,
    )
    assert comparison.status == "OPTIMAL"
    assert comparison.optimal_stages == comparison.total_stages
    assert comparison.cycles is not None
    assert {task_id for cycle in comparison.cycles for task_id in cycle.task_ids} == {
        task.id for task in baseline.tasks
    }
    assert sum(cycle.total_distance_meters for cycle in comparison.cycles) == 40_000
    assert baseline.cycles == original


def test_locked_cycle_is_unchanged_even_when_more_expensive(comparison_case):
    input_data, settings, baseline, pool, verify = comparison_case
    locked = replace(baseline.cycles[0], locked=True)
    comparison = compare_candidate_selection(
        replace(input_data, locked_cycles=(locked,)),
        settings,
        replace(baseline, cycles=(locked,)),
        pool,
        verify_route=verify,
    )
    assert comparison.cycles == (locked,)


def test_same_id_at_a_different_departure_has_a_distinct_fingerprint(comparison_case):
    _, _, baseline, _, _ = comparison_case
    cycle = baseline.cycles[0]
    shifted = replace(cycle, planned_start=cycle.planned_start + timedelta(minutes=1))
    assert shifted.id == cycle.id
    assert candidate_fingerprint(cycle) != candidate_fingerprint(shifted)


def test_stale_candidate_proof_is_an_error_not_a_silent_fallback(comparison_case):
    input_data, settings, baseline, pool, verify = comparison_case
    stale = replace(baseline.cycles[0], legs=())
    pool.cycles[candidate_fingerprint(stale)] = stale
    with pytest.raises(CandidateRouteRejected, match="complete exact truck proof"):
        compare_candidate_selection(input_data, settings, baseline, pool, verify_route=verify)


def test_turnaround_conflict_cannot_create_a_shorter_but_impossible_day(comparison_case):
    input_data, settings, baseline, pool, verify = comparison_case
    # A four-hour depot turn makes the two solo trips incompatible, although their
    # occupied route intervals themselves do not overlap.
    input_data = replace(
        input_data, warehouse=replace(input_data.warehouse, turnaround_minutes=240)
    )
    comparison = compare_candidate_selection(
        input_data, settings, baseline, pool, verify_route=verify
    )
    assert comparison.cycles == baseline.cycles


def test_turnaround_conflict_is_conservative_regardless_of_candidate_order(comparison_case):
    input_data, settings, baseline, _, _ = comparison_case
    source = baseline.cycles[0]
    early_depot = replace(input_data.warehouse, id="early", turnaround_minutes=180)
    late_depot = replace(input_data.warehouse, id="late", turnaround_minutes=0)
    early_shift = replace(
        input_data.shifts[0],
        id="early-shift",
        driver_id="early-driver",
        resource_option_id="early-option",
        route_depot=early_depot,
    )
    late_shift = replace(
        input_data.shifts[0],
        id="late-shift",
        driver_id="late-driver",
        resource_option_id="late-option",
        route_depot=late_depot,
    )
    early = replace(
        source,
        id="early-cycle",
        driver_shift_id=early_shift.id,
        driver_id=early_shift.driver_id,
        resource_option_id=early_shift.resource_option_id,
        planned_start=START,
        planned_finish=START + timedelta(hours=1),
    )
    late = replace(
        source,
        id="late-cycle",
        driver_shift_id=late_shift.id,
        driver_id=late_shift.driver_id,
        resource_option_id=late_shift.resource_option_id,
        planned_start=START + timedelta(hours=3),
        planned_finish=START + timedelta(hours=4),
    )
    shifts = {
        early_shift.resource_option_id: early_shift,
        late_shift.resource_option_id: late_shift,
    }

    assert _conflict(early, late, shifts, input_data, settings)
    assert _conflict(late, early, shifts, input_data, settings)
