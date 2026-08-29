"""Transaction boundary for retryable warehouse-capacity mutations."""

from __future__ import annotations

from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.integrations.rwms import RwmsPlanningClient
from app.services.capacity_generation import advance_warehouse_capacity_generation
from app.services.capacity_projection import publish_warehouse_capacity


async def publish_capacity_after_mutation(
    session: AsyncSession,
    warehouse_id: UUID,
    settings: Settings,
    client: RwmsPlanningClient,
) -> None:
    """Fence a capacity mutation and publish it after commit when enabled.

    A failed remote replacement deliberately leaves the committed generation available for
    the explicit warehouse capacity retry endpoint.
    """

    await advance_warehouse_capacity_generation(session, warehouse_id)
    if settings.rwms_capacity_publish_enabled:
        await session.commit()
        await publish_warehouse_capacity(session, warehouse_id, client)
