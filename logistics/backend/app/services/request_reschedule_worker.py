"""Bounded automatic recovery for owner-backed request reschedule holds."""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.db import async_session_factory
from app.errors import ApiError
from app.integrations.rwms import RwmsPlanningClient
from app.models.domain import RequestRescheduleHoldState
from app.services.request_rescheduling import (
    ExistingRequestReschedulingService,
    claim_due_request_reschedule_holds,
    record_request_reschedule_recovery_failure,
)

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class RequestRescheduleRecoveryBatch:
    """Structured worker outcome suitable for logs and operational metrics scraping."""

    claimed: int
    succeeded: int
    failed: int
    quarantined: int


async def process_pending_request_reschedules(
    client: RwmsPlanningClient,
    *,
    batch_size: int,
    max_attempts: int,
    session_factory: async_sessionmaker[AsyncSession] = async_session_factory,
) -> RequestRescheduleRecoveryBatch:
    """Replay one bounded owner-phase batch using each persisted idempotency key."""

    claimed = 0
    succeeded = 0
    failed = 0
    quarantined = 0
    for _index in range(batch_size):
        async with session_factory() as scan_session:
            hold_ids = await claim_due_request_reschedule_holds(
                scan_session,
                limit=1,
            )
            await scan_session.commit()
        if not hold_ids:
            break
        hold_id = hold_ids[0]
        claimed += 1
        async with session_factory() as recovery_session:
            try:
                await ExistingRequestReschedulingService(client).resume_hold(
                    recovery_session,
                    hold_id,
                )
            except ApiError as exc:
                await recovery_session.rollback()
                state = await record_request_reschedule_recovery_failure(
                    recovery_session,
                    hold_id,
                    exc,
                    max_attempts=max_attempts,
                )
                if state == RequestRescheduleHoldState.COMPLETE:
                    succeeded += 1
                    continue
                failed += 1
                if state == RequestRescheduleHoldState.QUARANTINED:
                    quarantined += 1
                logger.warning(
                    (
                        "RWMS request reschedule recovery attempt failed "
                        "hold_id=%s error_code=%s state=%s"
                    ),
                    str(hold_id),
                    exc.code,
                    state.value if state is not None else None,
                    extra={
                        "hold_id": str(hold_id),
                        "error_code": exc.code,
                        "state": state.value if state is not None else None,
                    },
                )
            except Exception as exc:
                await recovery_session.rollback()
                state = await record_request_reschedule_recovery_failure(
                    recovery_session,
                    hold_id,
                    exc,
                    max_attempts=max_attempts,
                )
                if state == RequestRescheduleHoldState.COMPLETE:
                    succeeded += 1
                    continue
                failed += 1
                if state == RequestRescheduleHoldState.QUARANTINED:
                    quarantined += 1
                logger.exception(
                    "RWMS request reschedule recovery failed hold_id=%s state=%s",
                    str(hold_id),
                    state.value if state is not None else None,
                    extra={
                        "hold_id": str(hold_id),
                        "state": state.value if state is not None else None,
                    },
                )
            else:
                succeeded += 1
    batch = RequestRescheduleRecoveryBatch(
        claimed=claimed,
        succeeded=succeeded,
        failed=failed,
        quarantined=quarantined,
    )
    if batch.claimed:
        logger.info(
            (
                "RWMS request reschedule recovery batch completed "
                "claimed=%d succeeded=%d failed=%d quarantined=%d"
            ),
            batch.claimed,
            batch.succeeded,
            batch.failed,
            batch.quarantined,
            extra={
                "claimed": batch.claimed,
                "succeeded": batch.succeeded,
                "failed": batch.failed,
                "quarantined": batch.quarantined,
            },
        )
    return batch


async def run_request_reschedule_worker(
    client: RwmsPlanningClient,
    stop: asyncio.Event,
    *,
    interval_seconds: float,
    batch_size: int,
    max_attempts: int,
) -> None:
    """Recover owner-phase commands until graceful application shutdown."""

    while not stop.is_set():
        try:
            await process_pending_request_reschedules(
                client,
                batch_size=batch_size,
                max_attempts=max_attempts,
            )
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("RWMS request reschedule recovery batch failed")
        try:
            await asyncio.wait_for(stop.wait(), timeout=interval_seconds)
        except TimeoutError:
            pass
