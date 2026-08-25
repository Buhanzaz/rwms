"""Planner engine and progress-publication extension points."""

from __future__ import annotations

from typing import Protocol

from .models import (
    DriverShift,
    PlanningInput,
    PlanningResult,
    PlanningSettings,
    PlanningTask,
    RouteCycle,
    TraceEvent,
    UnassignedReasonCode,
    Vehicle,
)


class CandidateRouteRejected(RuntimeError):
    """Reject one proposed assignment with a stable operator-facing reason."""

    def __init__(
        self,
        reason_code: UnassignedReasonCode,
        detail: str,
        *,
        missing_fields: tuple[str, ...] = (),
    ) -> None:
        super().__init__(detail)
        self.reason_code = reason_code
        self.detail = detail
        self.missing_fields = missing_fields


class CandidateRouteEvaluator(Protocol):
    """Validate and reschedule a candidate using exact per-leg truck routes."""

    async def route_candidate(
        self,
        cycle: RouteCycle,
        *,
        tasks: tuple[PlanningTask, ...],
        vehicle: Vehicle,
        shift: DriverShift,
        settings: PlanningSettings,
    ) -> RouteCycle:
        """Return an exact safe cycle or raise ``CandidateRouteRejected``."""

        ...


class ProgressPublisher(Protocol):
    """Receive bounded planner events for persistence or live SSE delivery."""

    async def publish(self, event: TraceEvent) -> None:
        """Publish one immutable event without changing planner decisions."""

        ...


class PlannerEngine(Protocol):
    """Generate an immutable route plan from a complete input snapshot."""

    async def generate_plan(
        self,
        input_data: PlanningInput,
        settings: PlanningSettings,
        progress: ProgressPublisher,
    ) -> PlanningResult:
        """Generate a deterministic plan and its diagnostics."""

        ...


class NullProgressPublisher:
    """Discard planner events while preserving the same planner behavior."""

    async def publish(self, event: TraceEvent) -> None:
        """Accept an event without side effects."""

        del event


class RecordingProgressPublisher:
    """Collect planner events in memory for tests or synchronous adapters."""

    def __init__(self) -> None:
        self.events: list[TraceEvent] = []

    async def publish(self, event: TraceEvent) -> None:
        """Append one event in emission order."""

        self.events.append(event)
