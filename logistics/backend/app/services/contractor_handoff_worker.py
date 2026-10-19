"""Background recovery for durable RWMS contractor handoff commands."""

from __future__ import annotations

import asyncio
import logging

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.db import async_session_factory
from app.errors import ApiError
from app.integrations.rwms import RwmsPlanningClient
from app.services.contractor_assignment import (
    apply_claimed_contractor_handoff,
    claim_due_contractor_handoffs,
)

logger = logging.getLogger(__name__)


async def process_due_contractor_handoffs(
    client: RwmsPlanningClient,
    *,
    batch_size: int,
    session_factory: async_sessionmaker[AsyncSession] = async_session_factory,
) -> int:
    """Claim one bounded batch, release its locks, and replay each stable command."""

    async with session_factory() as claim_session:
        claimed = await claim_due_contractor_handoffs(
            claim_session,
            limit=batch_size,
        )
        await claim_session.commit()
    for command in claimed:
        async with session_factory() as delivery_session:
            try:
                await apply_claimed_contractor_handoff(
                    delivery_session,
                    command,
                    client,
                )
            except ApiError as exc:
                if exc.code == "CONTRACTOR_ASSIGNMENT_REJECTED":
                    logger.warning(
                        "RWMS permanently rejected contractor handoff",
                        extra={"command_id": str(command.command_id)},
                    )
                else:
                    logger.warning(
                        "RWMS contractor handoff remains pending",
                        extra={
                            "command_id": str(command.command_id),
                            "error_code": exc.code,
                        },
                    )
            except Exception:
                logger.exception(
                    "RWMS contractor handoff reconciliation failed",
                    extra={"command_id": str(command.command_id)},
                )
    return len(claimed)


async def run_contractor_handoff_worker(
    client: RwmsPlanningClient,
    stop: asyncio.Event,
    *,
    interval_seconds: float,
    batch_size: int,
) -> None:
    """Reconcile due contractor commands until graceful application shutdown."""

    while not stop.is_set():
        try:
            processed = await process_due_contractor_handoffs(
                client,
                batch_size=batch_size,
            )
        except asyncio.CancelledError:
            raise
        except Exception:
            processed = 0
            logger.exception("RWMS contractor handoff retry batch failed")
        timeout = 0.1 if processed else interval_seconds
        try:
            await asyncio.wait_for(stop.wait(), timeout=timeout)
        except TimeoutError:
            pass
