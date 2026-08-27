"""Monotonic simulator-wide generation for outbound RWMS capacity state."""

from __future__ import annotations

from uuid import UUID

from sqlalchemy import Sequence, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import not_found
from app.models import Scenario

_CAPACITY_GENERATION_SEQUENCE = Sequence("scenario_capacity_generation_seq")


async def advance_scenario_capacity_generation(
    session: AsyncSession,
    scenario_id: UUID,
) -> int:
    """Lock one scenario and assign the next database-global publication generation."""

    scenario = await session.scalar(
        select(Scenario).where(Scenario.id == scenario_id).with_for_update()
    )
    if scenario is None:
        raise not_found("scenario", scenario_id)
    generation = await session.scalar(select(_CAPACITY_GENERATION_SEQUENCE.next_value()))
    if generation is None or generation < 1:
        raise RuntimeError("capacity generation sequence returned an invalid value")
    scenario.capacity_generation = generation
    await session.flush()
    return generation
