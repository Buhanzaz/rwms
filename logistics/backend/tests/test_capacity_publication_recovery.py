"""Integration tests for durable, retryable RWMS capacity publication."""

from __future__ import annotations

import os
from datetime import timedelta
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from sqlalchemy import delete
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.config import Settings
from app.db import utc_now
from app.errors import ApiError
from app.integrations.rwms import RwmsPlanningClient
from app.models import Warehouse
from app.models.domain import CapacityPublicationStatus
from app.services.capacity_mutations import publish_capacity_after_mutation
from app.services.capacity_publication_state import (
    claim_due_capacity_publications,
    mark_capacity_publication_pending,
    record_capacity_publication_failure,
    record_capacity_publication_success,
)
from tests.factories import make_warehouse

pytestmark = pytest.mark.integration


def _session_factory() -> tuple[async_sessionmaker[AsyncSession], AsyncEngine]:
    """Create independent sessions against the explicitly selected integration database."""

    database_url = os.getenv("TEST_DATABASE_URL")
    if not database_url:
        pytest.skip("TEST_DATABASE_URL is not configured")
    engine = create_async_engine(database_url, pool_pre_ping=True)
    return async_sessionmaker(engine, expire_on_commit=False), engine


def _enabled_settings() -> Settings:
    """Create a valid service-to-service configuration without external I/O."""

    return Settings(
        rwms_sync_enabled=True,
        rwms_capacity_publish_enabled=True,
        rwms_logistics_base_url="https://rwms.internal",
        rwms_token_url="https://auth.internal/oauth2/token",
        rwms_client_id="planner",
        rwms_client_secret="secret",
    )


@pytest.mark.asyncio
async def test_committed_mutation_reports_success_while_failed_projection_is_retried(
    db_session: AsyncSession,
) -> None:
    """An upstream outage never converts an already committed local save into HTTP failure."""

    warehouse = await make_warehouse(db_session)
    client = AsyncMock(spec=RwmsPlanningClient)
    client.replace_capacity_snapshot.side_effect = ApiError(
        503,
        "RWMS_CAPACITY_UNAVAILABLE",
        "Remote projection is unavailable",
    )

    outcome = await publish_capacity_after_mutation(
        db_session,
        warehouse.id,
        _enabled_settings(),
        client,
    )

    await db_session.refresh(warehouse)
    assert outcome.status == "FAILED"
    assert warehouse.capacity_publish_status == CapacityPublicationStatus.FAILED
    assert warehouse.capacity_publish_attempts == 1
    assert warehouse.capacity_publish_error_code == "RWMS_CAPACITY_UNAVAILABLE"
    assert warehouse.capacity_published_generation < warehouse.capacity_generation
    warehouse.capacity_publish_next_attempt_at = utc_now() - timedelta(seconds=1)
    await db_session.flush()

    claimed = await claim_due_capacity_publications(db_session)

    assert claimed == (warehouse.id,)
    assert warehouse.capacity_publish_status == CapacityPublicationStatus.PENDING
    assert warehouse.capacity_publish_lease_until is not None


@pytest.mark.asyncio
async def test_old_success_does_not_hide_a_newer_pending_generation(
    db_session: AsyncSession,
) -> None:
    """Concurrent completion advances the cursor but preserves the newer retry obligation."""

    warehouse = await make_warehouse(db_session)
    warehouse.capacity_generation = 12
    warehouse.capacity_published_generation = 10
    warehouse.capacity_publish_status = CapacityPublicationStatus.PENDING
    warehouse.capacity_publish_next_attempt_at = utc_now()
    await db_session.flush()

    await record_capacity_publication_success(db_session, warehouse.id, 11)

    assert warehouse.capacity_published_generation == 11
    assert warehouse.capacity_publish_status == CapacityPublicationStatus.PENDING
    assert warehouse.capacity_publish_next_attempt_at is not None


@pytest.mark.asyncio
@pytest.mark.parametrize("upstream_status", [408, 503])
async def test_capacity_exhaustion_and_permanent_validation_require_new_generation(
    db_session: AsyncSession,
    upstream_status: int,
) -> None:
    """Automatic recovery consumes durable claims, then leaves review to the operator."""

    warehouse = await make_warehouse(db_session)
    warehouse.capacity_generation = 1
    warehouse.capacity_published_generation = 0
    warehouse.capacity_publish_status = CapacityPublicationStatus.PENDING
    await mark_capacity_publication_pending(db_session, warehouse.id, 1)
    await record_capacity_publication_failure(
        db_session, warehouse.id, 1, ApiError(upstream_status, "RWMS_CAPACITY_UNAVAILABLE", "down")
    )
    assert warehouse.capacity_publish_status == CapacityPublicationStatus.FAILED
    for attempt in (2, 3):
        warehouse.capacity_publish_next_attempt_at = utc_now() - timedelta(seconds=1)
        assert await claim_due_capacity_publications(db_session) == (warehouse.id,)
        assert warehouse.capacity_publish_attempts == attempt
        await record_capacity_publication_failure(
            db_session,
            warehouse.id,
            1,
            ApiError(upstream_status, "RWMS_CAPACITY_UNAVAILABLE", "down"),
        )
    assert warehouse.capacity_publish_status == CapacityPublicationStatus.REVIEW_REQUIRED
    assert warehouse.capacity_publish_lease_until is None
    assert warehouse.capacity_publish_next_attempt_at is None
    assert await claim_due_capacity_publications(db_session) == ()

    warehouse.capacity_generation = 2
    await mark_capacity_publication_pending(db_session, warehouse.id, 2)
    assert warehouse.capacity_publish_status == CapacityPublicationStatus.PENDING
    assert warehouse.capacity_publish_attempts == 1
    await record_capacity_publication_failure(
        db_session, warehouse.id, 2, ApiError(422, "RWMS_CAPACITY_INVALID", "invalid")
    )
    assert warehouse.capacity_publish_status == CapacityPublicationStatus.REVIEW_REQUIRED
    assert await claim_due_capacity_publications(db_session) == ()


@pytest.mark.asyncio
async def test_expired_third_capacity_lease_requires_review_without_a_fourth_claim(
    db_session: AsyncSession,
) -> None:
    """An expired third lease consumes the finite budget before any fourth send can start."""

    warehouse = await make_warehouse(db_session)
    warehouse.capacity_generation = 1
    warehouse.capacity_published_generation = 0
    await mark_capacity_publication_pending(db_session, warehouse.id, 1)
    assert warehouse.capacity_publish_attempts == 1
    for attempt in (2, 3):
        warehouse.capacity_publish_lease_until = utc_now() - timedelta(seconds=1)
        await db_session.flush()
        assert await claim_due_capacity_publications(db_session) == (warehouse.id,)
        assert warehouse.capacity_publish_attempts == attempt
        assert warehouse.capacity_publish_status == CapacityPublicationStatus.PENDING
        assert warehouse.capacity_publish_lease_until is not None

    warehouse.capacity_publish_lease_until = utc_now() - timedelta(seconds=1)
    await db_session.flush()
    assert await claim_due_capacity_publications(db_session) == ()
    assert warehouse.capacity_publish_status == CapacityPublicationStatus.REVIEW_REQUIRED
    assert warehouse.capacity_publish_attempts == 3
    assert warehouse.capacity_publish_lease_until is None
    assert warehouse.capacity_publish_next_attempt_at is None
    assert await claim_due_capacity_publications(db_session) == ()


@pytest.mark.asyncio
async def test_late_success_keeps_newer_generation_due_for_claim() -> None:
    """A stale publishing session cannot clear a newer generation's persisted obligation."""

    sessions, database_engine = _session_factory()
    warehouse_id = None
    try:
        async with sessions() as setup:
            warehouse = await make_warehouse(setup, name=f"Capacity late success {uuid4()}")
            warehouse.capacity_generation = 11
            warehouse.capacity_published_generation = 10
            await setup.commit()
            warehouse_id = warehouse.id

        async with sessions() as late_success:
            stale = await late_success.get(Warehouse, warehouse_id)
            assert stale is not None
            sent_generation = stale.capacity_generation

            next_attempt_at = utc_now() - timedelta(seconds=2)
            lease_until = utc_now() - timedelta(seconds=1)
            async with sessions() as newer_generation:
                current = await newer_generation.get(Warehouse, warehouse_id)
                assert current is not None
                current.capacity_generation = sent_generation + 1
                current.capacity_publish_status = CapacityPublicationStatus.PENDING
                # This remains a newer pending generation; it must stay below the
                # finite automatic budget introduced by R091.
                current.capacity_publish_attempts = 2
                current.capacity_publish_error_code = "NEW_GENERATION_PENDING"
                current.capacity_publish_next_attempt_at = next_attempt_at
                current.capacity_publish_lease_until = lease_until
                await newer_generation.commit()

            await record_capacity_publication_success(late_success, warehouse_id, sent_generation)
            await late_success.commit()

        async with sessions() as verification:
            current = await verification.get(Warehouse, warehouse_id)
            assert current is not None
            assert current.capacity_generation == 12
            assert current.capacity_published_generation == 11
            assert current.capacity_publish_status == CapacityPublicationStatus.PENDING
            assert current.capacity_publish_attempts == 2
            assert current.capacity_publish_error_code == "NEW_GENERATION_PENDING"
            assert current.capacity_publish_next_attempt_at == next_attempt_at
            assert current.capacity_publish_lease_until == lease_until
            assert await claim_due_capacity_publications(verification) == (warehouse_id,)
            await verification.commit()
    finally:
        if warehouse_id is not None:
            async with sessions() as cleanup:
                await cleanup.execute(delete(Warehouse).where(Warehouse.id == warehouse_id))
                await cleanup.commit()
        await database_engine.dispose()


@pytest.mark.asyncio
async def test_late_failure_refreshes_newer_generation_before_mutating() -> None:
    """An old publishing session cannot clear or reschedule a newer generation's lease."""

    sessions, database_engine = _session_factory()
    warehouse_id = None
    try:
        async with sessions() as setup:
            warehouse = await make_warehouse(setup, name=f"Capacity late failure {uuid4()}")
            warehouse.capacity_generation = 11
            warehouse.capacity_published_generation = 10
            await setup.commit()
            warehouse_id = warehouse.id
        async with sessions() as late_failure:
            stale = await late_failure.get(Warehouse, warehouse_id)
            assert stale is not None
            next_attempt_at = utc_now() + timedelta(minutes=3)
            lease_until = utc_now() + timedelta(minutes=2)
            async with sessions() as newer_generation:
                current = await newer_generation.get(Warehouse, warehouse_id)
                assert current is not None
                current.capacity_generation = 12
                current.capacity_publish_status = CapacityPublicationStatus.PENDING
                current.capacity_publish_attempts = 2
                current.capacity_publish_error_code = "NEW_GENERATION_PENDING"
                current.capacity_publish_next_attempt_at = next_attempt_at
                current.capacity_publish_lease_until = lease_until
                await newer_generation.commit()
            await record_capacity_publication_failure(
                late_failure,
                warehouse_id,
                stale.capacity_generation,
                ApiError(503, "RWMS_CAPACITY_UNAVAILABLE", "late"),
            )
            await late_failure.commit()
        async with sessions() as verification:
            current = await verification.get(Warehouse, warehouse_id)
            assert current is not None
            assert current.capacity_generation == 12
            assert current.capacity_publish_status == CapacityPublicationStatus.PENDING
            assert current.capacity_publish_attempts == 2
            assert current.capacity_publish_error_code == "NEW_GENERATION_PENDING"
            assert current.capacity_publish_next_attempt_at == next_attempt_at
            assert current.capacity_publish_lease_until == lease_until
    finally:
        if warehouse_id is not None:
            async with sessions() as cleanup:
                await cleanup.execute(delete(Warehouse).where(Warehouse.id == warehouse_id))
                await cleanup.commit()
        await database_engine.dispose()
