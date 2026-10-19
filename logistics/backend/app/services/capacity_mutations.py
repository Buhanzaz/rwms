"""Transaction boundary for retryable warehouse-capacity mutations."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.integrations.rwms import RwmsPlanningClient
from app.services.capacity_generation import advance_warehouse_capacity_generation
from app.services.capacity_projection import publish_warehouse_capacity
from app.services.capacity_publication_state import mark_capacity_publication_pending

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class CapacityPublicationOutcome:
    """Non-ambiguous local commit and remote delivery result for one mutation."""

    generation: int
    status: str


async def publish_capacity_after_mutation(
    session: AsyncSession,
    warehouse_id: UUID,
    settings: Settings,
    client: RwmsPlanningClient,
) -> CapacityPublicationOutcome:
    """Fence a capacity mutation and publish it after commit when enabled.

    A failed remote replacement deliberately leaves the committed generation available for
    the explicit warehouse capacity retry endpoint.
    """

    generation = await advance_warehouse_capacity_generation(session, warehouse_id)
    if not settings.rwms_capacity_publish_enabled:
        return CapacityPublicationOutcome(generation, "NOT_REQUESTED")
    await mark_capacity_publication_pending(session, warehouse_id, generation)
    await session.commit()
    try:
        await publish_warehouse_capacity(session, warehouse_id, client)
    except Exception:
        logger.exception(
            "RWMS capacity publication deferred after committed local mutation",
            extra={"warehouse_id": str(warehouse_id), "generation": generation},
        )
        return CapacityPublicationOutcome(generation, "FAILED")
    return CapacityPublicationOutcome(generation, "PUBLISHED")
