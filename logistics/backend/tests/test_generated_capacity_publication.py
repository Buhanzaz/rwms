"""Tests for non-fatal anonymous test-capacity publication outcomes."""

from __future__ import annotations

from datetime import date
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest

import app.api.catalog as catalog_api
from app.config import Settings
from app.errors import ApiError
from app.schemas.domain import (
    WorkloadGenerationDailyCount,
    WorkloadGenerationResult,
    WorkloadGeneratorInput,
)


@pytest.mark.asyncio
async def test_generation_succeeds_locally_when_test_capacity_publication_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A remote projection outage is reported without converting local success into an error."""

    warehouse_id = uuid4()
    start_date = date(2026, 8, 30)
    generated = WorkloadGenerationResult(
        warehouse_id=warehouse_id,
        seed=17,
        start_date=start_date,
        end_date=start_date,
        created_requests=2,
        created_deliveries=1,
        created_pickups=1,
        daily_counts=[
            WorkloadGenerationDailyCount(
                date=start_date,
                deliveries=1,
                pickups=1,
            )
        ],
    )
    generate = AsyncMock(return_value=generated)
    auto_plan = AsyncMock(return_value=())
    publish = AsyncMock(
        side_effect=ApiError(
            503,
            "RWMS_CAPACITY_UNAVAILABLE",
            "Remote test projection is unavailable",
        )
    )
    monkeypatch.setattr(catalog_api, "generate_warehouse_workload", generate)
    monkeypatch.setattr(catalog_api, "generate_missing_draft_plans", auto_plan)
    monkeypatch.setattr(catalog_api, "publish_warehouse_capacity", publish)
    session = AsyncMock()
    settings = Settings(
        rwms_sync_enabled=True,
        rwms_capacity_publish_enabled=True,
        rwms_logistics_base_url="https://rwms.internal",
        rwms_token_url="https://auth.internal/oauth2/token",
        rwms_client_id="planner",
        rwms_client_secret="secret",
    )

    result = await catalog_api.generate_workload(
        warehouse_id,
        WorkloadGeneratorInput(
            start_date=start_date,
            days=1,
            deliveries_per_day=1,
            pickups_per_day=1,
            alternative_dates_count=0,
            seed=17,
        ),
        session,
        AsyncMock(),
        AsyncMock(),
        settings,
        AsyncMock(),
    )

    assert result.created_requests == 2
    assert result.capacity_projection_status == "FAILED"
    assert result.capacity_projection_warning is not None
    assert "анонимная проекция" in result.capacity_projection_warning
    session.commit.assert_awaited_once()
    publish.assert_awaited_once()
