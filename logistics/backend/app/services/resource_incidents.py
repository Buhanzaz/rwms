"""Read warehouse-day resource exclusions consistently without changing fleet ownership."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import LogisticsEvent, LogisticsEventType


@dataclass(frozen=True, slots=True)
class DayResourceRestrictions:
    """Incident exclusions survive catalog reactivation for their recorded planning day."""

    vehicle_ids: frozenset[str] = frozenset()
    shift_ids: frozenset[str] = frozenset()
    trailer_ids: frozenset[str] = frozenset()

    @classmethod
    def from_events(cls, events: Iterable[LogisticsEvent]) -> DayResourceRestrictions:
        """Project only resource incidents; task blocks retain their separate owner semantics."""

        vehicles: set[str] = set()
        shifts: set[str] = set()
        trailers: set[str] = set()
        for event in events:
            match event.event_type:
                case LogisticsEventType.VEHICLE_BREAKDOWN:
                    target, key = vehicles, "vehicle_id"
                case LogisticsEventType.DRIVER_UNAVAILABLE:
                    target, key = shifts, "driver_shift_id"
                case LogisticsEventType.TRAILER_BREAKDOWN:
                    target, key = trailers, "trailer_id"
                case _:
                    continue
            if (value := event.facts.get(key)) is not None:
                target.add(str(value))
        return cls(frozenset(vehicles), frozenset(shifts), frozenset(trailers))

    def allows_shift(self, shift_id: UUID, vehicle_id: UUID) -> bool:
        """Keep a vehicle/driver incident out of both local and borrowed capacity."""

        return str(shift_id) not in self.shift_ids and str(vehicle_id) not in self.vehicle_ids


NO_RESOURCE_RESTRICTIONS = DayResourceRestrictions()


async def load_resource_restrictions(
    session: AsyncSession,
    root_warehouse_id: UUID,
    date_from: date,
    date_to: date,
) -> dict[date, DayResourceRestrictions]:
    """Load a bounded date range once for the owning planning group, not once per shift."""

    events = await session.scalars(
        select(LogisticsEvent).where(
            LogisticsEvent.warehouse_id == root_warehouse_id,
            LogisticsEvent.day.between(date_from, date_to),
            LogisticsEvent.event_type.in_(
                (
                    LogisticsEventType.VEHICLE_BREAKDOWN,
                    LogisticsEventType.TRAILER_BREAKDOWN,
                    LogisticsEventType.DRIVER_UNAVAILABLE,
                )
            ),
        )
    )
    by_day: dict[date, list[LogisticsEvent]] = {}
    for event in events:
        by_day.setdefault(event.day, []).append(event)
    return {day: DayResourceRestrictions.from_events(items) for day, items in by_day.items()}
