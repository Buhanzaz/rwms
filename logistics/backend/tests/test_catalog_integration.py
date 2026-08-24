"""PostGIS integration tests for authoritative CRUD and version behavior."""

from datetime import date

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.schemas.domain import (
    GeoJsonGeometry,
    LogisticsRequestCreate,
    RequestDateOptionInput,
    ScenarioCreate,
    ZoneCreate,
    ZoneRelationCreate,
    ZoneUpdate,
)
from app.services import catalog, scenarios

pytestmark = pytest.mark.integration


def _polygon(west: float, south: float, east: float, north: float) -> GeoJsonGeometry:
    """Build a rectangular GeoJSON polygon."""

    return GeoJsonGeometry(
        type="Polygon",
        coordinates=[[(west, south), (east, south), (east, north), (west, north), (west, south)]],
    )


@pytest.mark.asyncio
async def test_relation_uuid_binding_and_request_zone_version_flow(
    db_session: AsyncSession,
) -> None:
    """Create relations with UUID binds and preserve classification until explicit refresh."""

    settings = Settings()
    scenario = await scenarios.create_scenario(
        db_session, ScenarioCreate(name="Integration"), settings
    )
    broad = await catalog.create_zone(
        db_session,
        scenario.id,
        ZoneCreate(
            name="Broad",
            code="BROAD",
            route_group="CUSTOM",
            geometry=_polygon(37.0, 55.0, 38.0, 56.0),
            priority=1,
        ),
    )
    specific = await catalog.create_zone(
        db_session,
        scenario.id,
        ZoneCreate(
            name="Specific",
            code="SPECIFIC",
            route_group="CITY",
            geometry=_polygon(37.5, 55.5, 37.7, 55.8),
            priority=1,
        ),
    )
    relation = await catalog.create_zone_relation(
        db_session,
        scenario.id,
        ZoneRelationCreate(from_zone_id=broad.id, to_zone_id=specific.id),
    )
    assert relation.from_zone_id == broad.id
    assert relation.to_zone_id == specific.id

    request = await catalog.create_request(
        db_session,
        scenario.id,
        LogisticsRequestCreate(
            type="DELIVERY",
            name="Five cabins",
            latitude=55.6,
            longitude=37.6,
            quantity=5,
            date_options=[RequestDateOptionInput(date=date(2026, 8, 25))],
        ),
    )
    assert request.zone_id == specific.id
    assert request.zone_version == 1
    assert [task.quantity for task in request.tasks] == [2, 2, 1]

    await catalog.update_zone(
        db_session,
        specific.id,
        ZoneUpdate(geometry=_polygon(37.5, 55.5, 37.75, 55.85)),
    )
    assert request.zone_version == 1
    assert await catalog.count_stale_requests(db_session, specific) == 1

    updated, outside, unchanged = await catalog.reclassify_requests(db_session, scenario.id)
    assert updated == 1
    assert outside == 0
    assert unchanged == 0
    assert request.zone_version == 2
    assert all(task.zone_version == 2 for task in request.tasks)
