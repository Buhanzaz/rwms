"""Durable lease, retry, and completion state for RWMS capacity publication."""

from __future__ import annotations

import re
from datetime import timedelta
from uuid import UUID

from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import utc_now
from app.errors import ApiError
from app.models import Warehouse
from app.models.domain import CapacityPublicationStatus

_SAFE_CODE = re.compile(r"^[A-Z][A-Z0-9_]{0,127}$")
_LEASE_SECONDS = 60
_MAX_BACKOFF_SECONDS = 300
_MAX_ATTEMPTS = 3


def capacity_publication_error_code(exc: Exception) -> str:
    """Return a bounded operational code without persisting upstream response text."""

    if isinstance(exc, ApiError) and _SAFE_CODE.fullmatch(exc.code):
        return exc.code
    return "RWMS_CAPACITY_UNAVAILABLE"


def _permanent_capacity_failure(exc: Exception) -> bool:
    """Keep only stable owner-side validation failures out of automatic recovery."""

    return isinstance(exc, ApiError) and exc.status_code in {400, 403, 404, 405, 410, 415, 422}


async def mark_capacity_publication_pending(
    session: AsyncSession,
    warehouse_id: UUID,
    generation: int,
) -> None:
    """Reserve the initial delivery attempt before committing the immediate publication lease."""

    warehouse = await session.scalar(
        select(Warehouse)
        .where(Warehouse.id == warehouse_id)
        .execution_options(populate_existing=True)
        .with_for_update()
    )
    if warehouse is None or warehouse.capacity_generation != generation:
        raise RuntimeError("capacity generation changed before it was queued")
    warehouse.capacity_publish_status = CapacityPublicationStatus.PENDING
    warehouse.capacity_publish_attempts = 1
    warehouse.capacity_publish_error_code = None
    now = utc_now()
    warehouse.capacity_publish_next_attempt_at = now
    warehouse.capacity_publish_lease_until = now + timedelta(seconds=_LEASE_SECONDS)
    await session.flush()


async def record_capacity_publication_success(
    session: AsyncSession,
    warehouse_id: UUID,
    generation: int,
) -> None:
    """Advance the delivery cursor without hiding a newer pending generation."""

    warehouse = await session.scalar(
        select(Warehouse)
        .where(Warehouse.id == warehouse_id)
        .execution_options(populate_existing=True)
        .with_for_update()
    )
    if warehouse is None:
        return
    warehouse.capacity_published_generation = max(
        warehouse.capacity_published_generation,
        generation,
    )
    if warehouse.capacity_generation == generation:
        warehouse.capacity_publish_status = CapacityPublicationStatus.PUBLISHED
        warehouse.capacity_publish_attempts = 0
        warehouse.capacity_publish_error_code = None
        warehouse.capacity_publish_next_attempt_at = None
        warehouse.capacity_publish_lease_until = None
    await session.flush()


async def record_capacity_publication_failure(
    session: AsyncSession,
    warehouse_id: UUID,
    generation: int,
    exc: Exception,
) -> None:
    """Schedule bounded automatic retry only when the failed generation is still current."""

    warehouse = await session.scalar(
        select(Warehouse)
        .where(Warehouse.id == warehouse_id)
        .execution_options(populate_existing=True)
        .with_for_update()
    )
    if warehouse is None:
        return
    if warehouse.capacity_generation != generation:
        return
    attempts = max(warehouse.capacity_publish_attempts, 1)
    warehouse.capacity_publish_lease_until = None
    warehouse.capacity_publish_attempts = attempts
    warehouse.capacity_publish_error_code = capacity_publication_error_code(exc)
    if _permanent_capacity_failure(exc) or attempts >= _MAX_ATTEMPTS:
        warehouse.capacity_publish_status = CapacityPublicationStatus.REVIEW_REQUIRED
        warehouse.capacity_publish_next_attempt_at = None
        await session.flush()
        return
    delay_seconds = min(_MAX_BACKOFF_SECONDS, 5 * (2 ** min(attempts - 1, 6)))
    warehouse.capacity_publish_status = CapacityPublicationStatus.FAILED
    warehouse.capacity_publish_next_attempt_at = utc_now() + timedelta(seconds=delay_seconds)
    await session.flush()


async def claim_due_capacity_publications(
    session: AsyncSession,
    *,
    limit: int = 10,
) -> tuple[UUID, ...]:
    """Lease due warehouse projections with skip-locked multi-instance fencing."""

    now = utc_now()
    candidates = list(
        await session.scalars(
            select(Warehouse)
            .where(
                Warehouse.capacity_publish_status.in_(
                    (
                        CapacityPublicationStatus.PENDING,
                        CapacityPublicationStatus.FAILED,
                    )
                ),
                Warehouse.capacity_generation > Warehouse.capacity_published_generation,
                or_(
                    Warehouse.capacity_publish_next_attempt_at.is_(None),
                    Warehouse.capacity_publish_next_attempt_at <= now,
                ),
                or_(
                    Warehouse.capacity_publish_lease_until.is_(None),
                    Warehouse.capacity_publish_lease_until <= now,
                ),
            )
            .order_by(Warehouse.capacity_publish_next_attempt_at, Warehouse.id)
            .execution_options(populate_existing=True)
            .with_for_update(skip_locked=True)
            .limit(limit)
        )
    )
    lease_until = now + timedelta(seconds=_LEASE_SECONDS)
    warehouses: list[Warehouse] = []
    for warehouse in candidates:
        if warehouse.capacity_publish_attempts >= _MAX_ATTEMPTS:
            warehouse.capacity_publish_status = CapacityPublicationStatus.REVIEW_REQUIRED
            warehouse.capacity_publish_next_attempt_at = None
            warehouse.capacity_publish_lease_until = None
            continue
        warehouse.capacity_publish_attempts += 1
        warehouse.capacity_publish_status = CapacityPublicationStatus.PENDING
        warehouse.capacity_publish_lease_until = lease_until
        warehouses.append(warehouse)
    await session.flush()
    return tuple(warehouse.id for warehouse in warehouses)
