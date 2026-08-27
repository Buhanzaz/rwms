"""Driver-shift workload calculations shared by planning and validation."""

from __future__ import annotations

from collections.abc import Iterable

from .models import DriverShift, PlanningSettings, RouteCycle


def shift_usable_seconds(shift: DriverShift) -> int:
    """Return shift capacity after reserving its configured break."""

    duration = round((shift.end_at - shift.start_at).total_seconds())
    return max(1, duration - shift.break_minutes * 60)


def shift_duty_seconds(cycles: Iterable[RouteCycle], shift: DriverShift) -> int:
    """Return elapsed duty span occupied by one shift's planned cycles."""

    own_cycles = tuple(cycle for cycle in cycles if cycle.driver_shift_id == shift.id)
    if not own_cycles:
        return 0
    duty_start = max(shift.start_at, min(cycle.planned_start for cycle in own_cycles))
    duty_finish = max(cycle.planned_finish for cycle in own_cycles)
    return max(0, round((duty_finish - duty_start).total_seconds()))


def shift_utilization_percent(
    cycles: Iterable[RouteCycle],
    shift: DriverShift,
) -> float:
    """Return driver duty as a percentage of break-adjusted shift capacity."""

    return shift_duty_seconds(cycles, shift) / shift_usable_seconds(shift) * 100.0


def calculate_shift_workload_cost(
    cycles: Iterable[RouteCycle],
    shift: DriverShift,
    settings: PlanningSettings,
) -> float:
    """Penalize only duty above the preferred break-adjusted utilization target."""

    preferred_seconds = (
        shift_usable_seconds(shift) * settings.preferred_shift_utilization_percent / 100.0
    )
    excess_minutes = max(
        0.0,
        (shift_duty_seconds(cycles, shift) - preferred_seconds) / 60.0,
    )
    return excess_minutes * settings.driver_workload_weight


def calculate_driver_workload_cost(
    cycles: Iterable[RouteCycle],
    shifts: Iterable[DriverShift],
    settings: PlanningSettings,
) -> float:
    """Return the total soft overload cost across all driver shifts."""

    cycle_list = tuple(cycles)
    return round(
        sum(calculate_shift_workload_cost(cycle_list, shift, settings) for shift in shifts),
        6,
    )


def incremental_shift_workload_cost(
    existing_cycles: Iterable[RouteCycle],
    candidate: RouteCycle,
    shift: DriverShift,
    settings: PlanningSettings,
) -> float:
    """Return the marginal overload cost of appending one candidate cycle."""

    existing = tuple(existing_cycles)
    before = calculate_shift_workload_cost(existing, shift, settings)
    after = calculate_shift_workload_cost((*existing, candidate), shift, settings)
    return max(0.0, after - before)
