"""Focused PostGIS tests for deterministic simulator-capacity publication."""

from __future__ import annotations

from datetime import UTC, date, datetime, time
from unittest.mock import AsyncMock
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.config import Settings
from app.errors import ApiError
from app.integrations.rwms import RwmsPlanningClient
from app.models import LogisticsRequest
from app.models.domain import RequestStatus, RequestType
from app.routing import MockRoutingProvider
from app.schemas.domain import (
    GeoJsonGeometry,
    RwmsCapacitySnapshotResult,
    ScenarioCreate,
    WarehouseCreate,
    WorkloadGeneratorInput,
    ZoneCreate,
)
from app.services import catalog, scenarios
from app.services.capacity_projection import (
    build_capacity_projection,
    publish_scenario_capacity,
)
from app.services.workload_generator import (
    GENERATOR_SOURCE_SYSTEM,
    generate_scenario_workload,
)

pytestmark = pytest.mark.integration


async def _scenario_with_capacity_inputs(
    session: AsyncSession,
) -> tuple[UUID, UUID]:
    """Create one linked depot and broad deterministic generator zone."""

    scenario = await scenarios.create_scenario(
        session,
        ScenarioCreate(name="Capacity projection"),
        Settings(),
    )
    warehouse_id = uuid4()
    await catalog.create_warehouse(
        session,
        scenario.id,
        WarehouseCreate(
            name="RWMS depot",
            external_warehouse_id=warehouse_id,
            latitude=55.75,
            longitude=37.61,
        ),
    )
    await catalog.create_zone(
        session,
        scenario.id,
        ZoneCreate(
            name="Capacity zone",
            code="CAPACITY",
            route_group="CUSTOM",
            geometry=GeoJsonGeometry(
                type="Polygon",
                coordinates=[
                    [
                        (37.0, 55.0),
                        (38.0, 55.0),
                        (38.0, 56.0),
                        (37.0, 56.0),
                        (37.0, 55.0),
                    ]
                ],
            ),
        ),
    )
    return scenario.id, warehouse_id


async def _generate(
    session: AsyncSession,
    scenario_id: UUID,
    *,
    deliveries: int = 4,
    pickups: int = 2,
) -> None:
    """Generate one exact day of delivery and pickup workload."""

    await generate_scenario_workload(
        session,
        scenario_id,
        WorkloadGeneratorInput(
            start_date=date(2026, 9, 1),
            deliveries_per_day=deliveries,
            pickups_per_day=pickups,
            seed=20260901,
        ),
        MockRoutingProvider(),
    )


@pytest.mark.asyncio
async def test_projection_contains_only_active_generated_deliveries_and_is_stable(
    db_session: AsyncSession,
) -> None:
    """Ignore pickups/manual state and derive stable sorted route-window facts."""

    scenario_id, warehouse_id = await _scenario_with_capacity_inputs(db_session)
    await _generate(db_session, scenario_id)

    first = await build_capacity_projection(db_session, scenario_id)
    second = await build_capacity_projection(db_session, scenario_id)

    assert first == second
    assert first.command.warehouse_id == warehouse_id
    assert len(first.command.jobs) == 4
    assert [(job.window_start, job.window_end) for job in first.command.jobs] == [
        (time(9), time(12)),
        (time(9), time(12)),
        (time(12), time(15)),
        (time(15), time(18)),
    ]
    assert len(first.command.source_revision) == 64

    pickup = await db_session.scalar(
        select(LogisticsRequest).where(
            LogisticsRequest.scenario_id == scenario_id,
            LogisticsRequest.source_system == GENERATOR_SOURCE_SYSTEM,
            LogisticsRequest.type == RequestType.PICKUP,
        )
    )
    assert pickup is not None
    pickup.notes = "Pickup-only metadata must not consume delivery capacity"
    await db_session.flush()
    pickup_changed = await build_capacity_projection(db_session, scenario_id)
    assert pickup_changed == first

    delivery = await db_session.scalar(
        select(LogisticsRequest).where(
            LogisticsRequest.scenario_id == scenario_id,
            LogisticsRequest.source_system == GENERATOR_SOURCE_SYSTEM,
            LogisticsRequest.type == RequestType.DELIVERY,
        )
    )
    assert delivery is not None
    delivery.status = RequestStatus.COMPLETED
    await db_session.flush()
    completed = await build_capacity_projection(db_session, scenario_id)
    assert len(completed.command.jobs) == 3
    assert completed.command.source_revision != first.command.source_revision
    assert completed.idempotency_key != first.idempotency_key


@pytest.mark.asyncio
async def test_projection_distinguishes_intentional_a_b_a_regeneration(
    db_session: AsyncSession,
) -> None:
    """Do not mistake a newly regenerated prior shape for a delayed old retry."""

    scenario_id, _ = await _scenario_with_capacity_inputs(db_session)
    await _generate(db_session, scenario_id, deliveries=1, pickups=0)
    first_a = await build_capacity_projection(db_session, scenario_id)

    await _generate(db_session, scenario_id, deliveries=2, pickups=0)
    state_b = await build_capacity_projection(db_session, scenario_id)

    await _generate(db_session, scenario_id, deliveries=1, pickups=0)
    second_a = await build_capacity_projection(db_session, scenario_id)

    assert len(first_a.command.jobs) == len(second_a.command.jobs) == 1
    assert first_a.command.jobs == second_a.command.jobs
    assert state_b.command.source_revision != first_a.command.source_revision
    assert second_a.command.source_revision != first_a.command.source_revision
    assert second_a.idempotency_key != first_a.idempotency_key


@pytest.mark.asyncio
async def test_projection_rejects_incomplete_or_soft_generated_delivery_window(
    db_session: AsyncSession,
) -> None:
    """Do not silently widen legacy generated deliveries into fake slot occupancy."""

    scenario_id, _ = await _scenario_with_capacity_inputs(db_session)
    await _generate(db_session, scenario_id, deliveries=1, pickups=0)
    delivery = await db_session.scalar(
        select(LogisticsRequest)
        .where(
            LogisticsRequest.scenario_id == scenario_id,
            LogisticsRequest.type == RequestType.DELIVERY,
        )
        .options(selectinload(LogisticsRequest.date_options))
    )
    assert delivery is not None
    delivery.date_options[0].is_hard = False
    await db_session.flush()

    with pytest.raises(ApiError) as error:
        await build_capacity_projection(db_session, scenario_id)

    assert error.value.code == "RWMS_CAPACITY_WINDOW_REQUIRED"


@pytest.mark.asyncio
async def test_publication_releases_read_transaction_before_remote_io(
    db_session: AsyncSession,
) -> None:
    """Never keep simulator database resources locked during authenticated HTTP."""

    scenario_id, warehouse_id = await _scenario_with_capacity_inputs(db_session)
    await _generate(db_session, scenario_id, deliveries=1, pickups=1)
    client = AsyncMock(spec=RwmsPlanningClient)

    async def replace_remote(
        remote_scenario_id: UUID,
        command: object,
        *,
        idempotency_key: UUID,
    ) -> RwmsCapacitySnapshotResult:
        assert remote_scenario_id == scenario_id
        assert not db_session.in_transaction()
        assert idempotency_key.version == 5
        return RwmsCapacitySnapshotResult(
            warehouse_id=warehouse_id,
            source_scenario_id=scenario_id,
            source_generation=command.source_generation,  # type: ignore[attr-defined]
            version=1,
            source_revision=command.source_revision,  # type: ignore[attr-defined]
            job_count=len(command.jobs),  # type: ignore[attr-defined]
            replayed=False,
            updated_at=datetime(2026, 9, 1, 8, tzinfo=UTC),
        )

    client.replace_capacity_snapshot.side_effect = replace_remote

    result = await publish_scenario_capacity(db_session, scenario_id, client)

    assert result.warehouse_id == warehouse_id
    client.ensure_capacity_publish_enabled.assert_called_once_with()
    client.replace_capacity_snapshot.assert_awaited_once()
