"""PostGIS integration tests for demo data and atomic scenario interchange."""

from copy import deepcopy
from uuid import uuid4

import pytest
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.errors import ApiError
from app.models import Scenario
from app.schemas.domain import RequestScheduleInput, ScenarioCreate
from app.services import catalog, scenarios

pytestmark = pytest.mark.integration


@pytest.mark.asyncio
async def test_demo_export_import_preserves_seed_versions_and_counts(
    db_session: AsyncSession,
) -> None:
    """The deterministic demo round-trips with remapped IDs and preserved snapshots."""

    settings = Settings(planner_default_seed=77)
    source = await scenarios.create_scenario(
        db_session, ScenarioCreate(name="Demo", seed=77), settings
    )
    await scenarios.reset_demo_scenario(db_session, source.id)
    source_requests = await catalog.list_requests(db_session, source.id)
    source_requests[0].source_system = "RWMS"
    source_requests[0].external_id = uuid4()
    source_requests[0].external_version = 7
    source_requests[0].external_payload = {
        "orderNumber": "R-007",
        "nested": {"unitIds": [str(uuid4())]},
    }
    expected_source_metadata = (
        source_requests[0].source_system,
        source_requests[0].external_id,
        source_requests[0].external_version,
        deepcopy(source_requests[0].external_payload),
    )
    await db_session.flush()
    selected_date = source.default_planning_date
    assert selected_date is not None
    await catalog.schedule_request(
        db_session,
        source_requests[0].id,
        RequestScheduleInput(date=selected_date),
    )
    document = await scenarios.export_scenario(db_session, source.id, include_plans=True)
    document.zones[0].data.delivery_price = 12_500
    document.zones[0].data.pickup_price = 9_000
    imported = await scenarios.import_scenario(db_session, document, settings, name="Imported demo")
    imported_document = await scenarios.export_scenario(db_session, imported.id, include_plans=True)

    assert imported.id != source.id
    assert imported.seed == source.seed == 77
    assert [zone.version for zone in imported_document.zones] == [
        zone.version for zone in document.zones
    ]
    assert len(imported_document.warehouses) == 1
    assert len(imported_document.zones) == 4
    assert imported_document.zones[0].data.delivery_price == 12_500
    assert imported_document.zones[0].data.pickup_price == 9_000
    assert len(imported_document.drivers) == 3
    assert len(imported_document.vehicles) == 3
    assert len(imported_document.shifts) == 3
    assert len(imported_document.requests) == 6
    assert [item.scheduled_date for item in imported_document.requests].count(selected_date) == 1
    imported_source = next(
        item
        for item in imported_document.requests
        if item.external_id == expected_source_metadata[1]
    )
    assert (
        imported_source.source_system,
        imported_source.external_id,
        imported_source.external_version,
        imported_source.external_payload,
    ) == expected_source_metadata


@pytest.mark.asyncio
async def test_invalid_import_can_be_rolled_back_without_partial_scenario(
    db_session: AsyncSession,
) -> None:
    """A broken cross-reference aborts every row created in the import transaction."""

    settings = Settings()
    source = await scenarios.create_scenario(db_session, ScenarioCreate(name="Source"), settings)
    await scenarios.reset_demo_scenario(db_session, source.id)
    document = await scenarios.export_scenario(db_session, source.id, include_plans=False)
    document.zone_relations[0].data.from_zone_id = uuid4()
    before = int(await db_session.scalar(select(func.count(Scenario.id))) or 0)

    with pytest.raises(ApiError) as failure:
        async with db_session.begin_nested():
            await scenarios.import_scenario(db_session, document, settings)
    assert failure.value.code == "IMPORT_REFERENCE_INVALID"
    after = int(await db_session.scalar(select(func.count(Scenario.id))) or 0)
    assert after == before
