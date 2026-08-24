"""Planner engine and progress-publication extension points."""

from __future__ import annotations

from typing import Protocol

from .models import PlanningInput, PlanningResult, PlanningSettings, TraceEvent


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
