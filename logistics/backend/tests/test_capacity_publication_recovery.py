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
                current.capacity_publish_attempts = 7
                current.capacity_publish_error_code = "NEW_GENERATION_PENDING"
                current.capacity_publish_next_attempt_at = next_attempt_at
                current.capacity_publish_lease_until = lease_until
                await newer_generation.commit()

            await record_capacity_publication_success(
                late_success, warehouse_id, sent_generation
            )
            await late_success.commit()

        async with sessions() as verification:
            current = await verification.get(Warehouse, warehouse_id)
            assert current is not None
            assert current.capacity_generation == 12
            assert current.capacity_published_generation == 11
            assert current.capacity_publish_status == CapacityPublicationStatus.PENDING
            assert current.capacity_publish_attempts == 7
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
