"""Integration tests for durable, retryable RWMS capacity publication."""

from __future__ import annotations

from datetime import timedelta
from unittest.mock import AsyncMock

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.db import utc_now
from app.errors import ApiError
from app.integrations.rwms import RwmsPlanningClient
from app.models.domain import CapacityPublicationStatus
from app.services.capacity_mutations import publish_capacity_after_mutation
from app.services.capacity_publication_state import (
    claim_due_capacity_publications,
    record_capacity_publication_success,
)
from tests.factories import make_warehouse

pytestmark = pytest.mark.integration


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
