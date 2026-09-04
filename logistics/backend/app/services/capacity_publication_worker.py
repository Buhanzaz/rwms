"""Automatic recovery worker for durable RWMS capacity publications."""

from __future__ import annotations

import asyncio
import logging

from app.db import async_session_factory
from app.integrations.rwms import RwmsPlanningClient
from app.services.capacity_projection import publish_warehouse_capacity
from app.services.capacity_publication_state import claim_due_capacity_publications

logger = logging.getLogger(__name__)


async def _publish_due_batch(client: RwmsPlanningClient) -> int:
    """Claim one bounded batch and attempt each idempotent current projection."""

    async with async_session_factory() as claim_session:
        warehouse_ids = await claim_due_capacity_publications(claim_session)
        await claim_session.commit()
    for warehouse_id in warehouse_ids:
        async with async_session_factory() as publish_session:
            try:
                await publish_warehouse_capacity(publish_session, warehouse_id, client)
            except Exception:
                logger.exception(
                    "Automatic RWMS capacity publication failed",
                    extra={"warehouse_id": str(warehouse_id)},
                )
    return len(warehouse_ids)


async def run_capacity_publication_worker(
    client: RwmsPlanningClient,
    stop: asyncio.Event,
    *,
    interval_seconds: float,
) -> None:
    """Retry due projections until graceful application shutdown."""

    while not stop.is_set():
        try:
            processed = await _publish_due_batch(client)
        except asyncio.CancelledError:
            raise
        except Exception:
            processed = 0
            logger.exception("RWMS capacity retry batch failed")
        timeout = 0.1 if processed else interval_seconds
        try:
            await asyncio.wait_for(stop.wait(), timeout=timeout)
        except TimeoutError:
            pass
