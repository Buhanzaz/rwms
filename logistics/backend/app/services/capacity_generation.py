"""Monotonic warehouse generation for outbound RWMS capacity state."""

from __future__ import annotations

from uuid import UUID

from sqlalchemy import Sequence, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import not_found
from app.models import Warehouse

_CAPACITY_GENERATION_SEQUENCE = Sequence("warehouse_capacity_generation_seq")


async def advance_warehouse_capacity_generation(
    session: AsyncSession,
    warehouse_id: UUID,
) -> int:
    """Lock one warehouse and assign the next database-global publication generation."""

    warehouse = await session.scalar(
        select(Warehouse).where(Warehouse.id == warehouse_id).with_for_update()
    )
    if warehouse is None:
        raise not_found("warehouse", warehouse_id)
    generation = await session.scalar(select(_CAPACITY_GENERATION_SEQUENCE.next_value()))
    if generation is None or generation < 1:
        raise RuntimeError("capacity generation sequence returned an invalid value")
    warehouse.capacity_generation = generation
    await session.flush()
    return generation
