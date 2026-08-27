"""PostGIS integration tests for authoritative CRUD and version behavior."""

from datetime import date

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.errors import ApiError
from app.models import RoutePlan, UnassignedTask
from app.schemas.domain import (
    GeoJsonGeometry,
    LogisticsRequestCreate,
    LogisticsRequestUpdate,
    RequestDateOptionInput,
    RequestPlanningDetailsInput,
    ScenarioCreate,
    WarehouseCreate,
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


@pytest.mark.asyncio
async def test_task_regeneration_rejects_an_unassigned_plan_reference(
    db_session: AsyncSession,
) -> None:
    """Return the stable conflict instead of violating the unassigned-task FK."""

    scenario = await scenarios.create_scenario(
        db_session,
        ScenarioCreate(name="Unassigned task reference"),
        Settings(),
    )
    warehouse = await catalog.create_warehouse(
        db_session,
        scenario.id,
        WarehouseCreate(name="Warehouse", latitude=55.75, longitude=37.61),
    )
    request = await catalog.create_request(
        db_session,
        scenario.id,
        LogisticsRequestCreate(
            type="DELIVERY",
            name="Unassigned delivery",
            latitude=55.76,
            longitude=37.62,
            quantity=2,
            date_options=[RequestDateOptionInput(date=date(2026, 8, 27))],
        ),
    )
    plan = RoutePlan(
        scenario_id=scenario.id,
        warehouse_id=warehouse.id,
        date=date(2026, 8, 27),
        name="Plan with unassigned task",
    )
    db_session.add(plan)
    await db_session.flush()
    db_session.add(
        UnassignedTask(
            route_plan_id=plan.id,
            task_id=request.tasks[0].id,
            reason_codes=["NO_SHIFT"],
            descriptions_ru=["Нет доступной смены"],
        )
    )
    await db_session.flush()

    original_task_ids = [task.id for task in request.tasks]
    updated = await catalog.update_request(
        db_session,
        request.id,
        LogisticsRequestUpdate(
            type=request.type,
            name=request.name,
            latitude=request.latitude,
            longitude=request.longitude,
            quantity=request.quantity,
            trailer_access_allowed=request.trailer_access_allowed,
            notes="Operator note only",
            date_options=[
                RequestDateOptionInput(
                    date=option.date,
                    priority=option.priority,
                    window_start=option.window_start,
                    window_end=option.window_end,
                    is_hard=option.is_hard,
                    travel_zone_hours=option.travel_zone_hours,
                )
                for option in request.date_options
            ],
        ),
    )
    assert updated.notes == "Operator note only"
    assert [task.id for task in updated.tasks] == original_task_ids

    with pytest.raises(ApiError) as error:
        await catalog.set_request_planning_details(
            db_session,
            request.id,
            RequestPlanningDetailsInput(
                date=date(2026, 8, 27),
                window_start="09:00",
                window_end="12:00",
                is_hard=True,
                trailer_access_allowed=False,
                contact_name="Контакт",
                contact_phone="+7 900 000-00-00",
            ),
        )

    assert error.value.status_code == 409
    assert error.value.code == "REQUEST_TASKS_ALREADY_PLANNED"
    with pytest.raises(ApiError) as delete_error:
        await catalog.delete_request(db_session, request.id)
    assert delete_error.value.status_code == 409
    assert delete_error.value.code == "REQUEST_ALREADY_PLANNED"
