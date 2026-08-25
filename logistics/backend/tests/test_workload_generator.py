"""PostGIS integration tests for deterministic scenario workload generation."""

from __future__ import annotations

from collections import Counter
from datetime import date
from types import SimpleNamespace
from uuid import uuid4

import pytest
from geoalchemy2.shape import to_shape
from shapely.geometry import Point
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.errors import ApiError
from app.models import LogisticsRequest, RoutePlan, Scenario, UnassignedTask, Zone
from app.models.domain import RequestStatus, RequestType, ZoneClassificationStatus
from app.schemas.domain import (
    GeoJsonGeometry,
    LogisticsRequestCreate,
    RequestDateOptionInput,
    ScenarioCreate,
    ScenarioSettings,
    WarehouseCreate,
    WorkloadGeneratorInput,
    ZoneCreate,
)
from app.services import catalog, scenarios
from app.services.planner_runtime import RuntimePlannerFacade
from app.services.workload_generator import (
    GENERATOR_SOURCE_SYSTEM,
    generate_scenario_workload,
)

pytestmark = pytest.mark.integration


def test_generated_source_identity_uses_business_point_not_display_sequence() -> None:
    """Renumbered legacy generator rows for one point remain one logical source."""

    option = SimpleNamespace(
        date=date(2026, 8, 25),
        priority=100,
        window_start=None,
        window_end=None,
        is_hard=False,
    )
    shared = {
        "source_system": GENERATOR_SOURCE_SYSTEM,
        "notes": "Детерминированная нагрузка, seed=20260822",
        "type": RequestType.PICKUP,
        "latitude": 56.26637711200076,
        "longitude": 36.81960445131736,
        "quantity": 1,
        "service_minutes": 30,
        "date_options": [option],
    }
    first = SimpleNamespace(
        **shared,
        external_id=None,
        name="Вывоз 2026-08-25 №3",
        address_label="Сгенерированная точка 3",
    )
    renumbered = SimpleNamespace(
        **shared,
        external_id=None,
        name="Вывоз 2026-08-25 №7",
        address_label="Сгенерированная точка 3 после переименования",
    )
    renumbered.notes = "Заметка оператора после старой генерации"
    renumbered.quantity = 2
    renumbered.service_minutes = 45
    next_day = SimpleNamespace(**vars(renumbered))
    next_day.date_options = [
        SimpleNamespace(
            date=date(2026, 8, 26),
            priority=100,
            window_start=None,
            window_end=None,
            is_hard=False,
        )
    ]

    assert RuntimePlannerFacade._request_source_key(
        first
    ) == RuntimePlannerFacade._request_source_key(renumbered)
    assert RuntimePlannerFacade._request_source_key(
        first
    ) != RuntimePlannerFacade._request_source_key(next_day)


def test_non_generator_source_identity_keeps_authoritative_external_id() -> None:
    """Distinct real upstream orders are not collapsed merely by sharing an address."""

    first = SimpleNamespace(source_system="RWMS", external_id=uuid4())
    second = SimpleNamespace(source_system="RWMS", external_id=uuid4())

    assert RuntimePlannerFacade._request_source_key(
        first
    ) != RuntimePlannerFacade._request_source_key(second)


def test_current_generator_source_identity_keeps_stable_external_id() -> None:
    """Two intentional generated orders remain distinct even at one coordinate."""

    first = SimpleNamespace(
        source_system=GENERATOR_SOURCE_SYSTEM,
        external_id=uuid4(),
    )
    second = SimpleNamespace(
        source_system=GENERATOR_SOURCE_SYSTEM,
        external_id=uuid4(),
    )

    assert RuntimePlannerFacade._request_source_key(
        first
    ) != RuntimePlannerFacade._request_source_key(second)


def test_generator_source_identity_handles_a_scheduled_legacy_row_without_options() -> None:
    """A legacy generated row cannot crash planning when it only has an assigned date."""

    legacy = SimpleNamespace(
        source_system=GENERATOR_SOURCE_SYSTEM,
        external_id=None,
        type=RequestType.DELIVERY,
        latitude=55.75,
        longitude=37.61,
        scheduled_date=date(2026, 8, 25),
        date_options=[],
    )

    assert RuntimePlannerFacade._request_source_key(legacy) == repr(
        (
            GENERATOR_SOURCE_SYSTEM,
            RequestType.DELIVERY,
            55.75,
            37.61,
            ("2026-08-25",),
        )
    )


def _multi_polygon_with_hole() -> GeoJsonGeometry:
    """Build two components where the larger one contains an excluded center hole."""

    return GeoJsonGeometry(
        type="MultiPolygon",
        coordinates=[
            [
                [(37.0, 55.0), (38.0, 55.0), (38.0, 56.0), (37.0, 56.0), (37.0, 55.0)],
                [(37.4, 55.4), (37.6, 55.4), (37.6, 55.6), (37.4, 55.6), (37.4, 55.4)],
            ],
            [
                [(38.2, 55.0), (38.4, 55.0), (38.4, 55.2), (38.2, 55.2), (38.2, 55.0)]
            ],
        ],
    )


async def _scenario_with_zone(
    session: AsyncSession,
    *,
    name: str,
    service_minutes: int = 37,
) -> tuple[Scenario, Zone]:
    """Create one fresh scenario with identical deterministic geometry and metadata."""

    scenario = await scenarios.create_scenario(
        session,
        ScenarioCreate(
            name=name,
            settings=ScenarioSettings(default_service_minutes=service_minutes),
        ),
        Settings(),
    )
    zone = await catalog.create_zone(
        session,
        scenario.id,
        ZoneCreate(
            name="Стабильная зона",
            code="STABLE",
            route_group="CUSTOM",
            geometry=_multi_polygon_with_hole(),
            priority=10,
        ),
    )
    return scenario, zone


def _business_payload(requests: list[LogisticsRequest]) -> list[tuple[object, ...]]:
    """Remove generated database identities before comparing reproducible requests."""

    values = []
    for request in requests:
        values.append(
            (
                request.type,
                request.name,
                request.address_label,
                request.latitude,
                request.longitude,
                request.quantity,
                request.service_minutes,
                request.priority,
                request.status,
                request.scheduled_date,
                tuple(
                    sorted(
                        (
                            option.date,
                            option.priority,
                            option.window_start,
                            option.window_end,
                            option.is_hard,
                        )
                        for option in request.date_options
                    )
                ),
            )
        )
    return sorted(values, key=repr)


@pytest.mark.asyncio
async def test_generation_creates_exact_dated_classified_workload(
    db_session: AsyncSession,
) -> None:
    """Every requested day/type count is exact and every generated point is classifiable."""

    scenario, zone = await _scenario_with_zone(db_session, name="Generated workload")
    payload = WorkloadGeneratorInput(
        start_date=date(2026, 9, 1),
        days=3,
        deliveries_per_day=2,
        pickups_per_day=1,
        alternative_dates_count=2,
        seed=314159,
    )

    result = await generate_scenario_workload(db_session, scenario.id, payload)
    requests = await catalog.list_requests(db_session, scenario.id)

    assert result.created_requests == len(requests) == 9
    assert result.created_deliveries == 6
    assert result.created_pickups == 3
    assert result.end_date == date(2026, 9, 3)
    assert [(item.date, item.deliveries, item.pickups) for item in result.daily_counts] == [
        (date(2026, 9, 1), 2, 1),
        (date(2026, 9, 2), 2, 1),
        (date(2026, 9, 3), 2, 1),
    ]

    horizon = {date(2026, 9, day) for day in range(1, 4)}
    primary_counts: Counter[tuple[date, str]] = Counter()
    geometry = to_shape(zone.geometry)
    for request in requests:
        assert request.status == RequestStatus.READY
        assert request.scheduled_date is None
        assert request.service_minutes == 37
        assert request.quantity in (1, 2)
        assert request.zone_id is not None
        assert request.zone_classification_status == ZoneClassificationStatus.CLASSIFIED
        assert geometry.contains(Point(request.longitude, request.latitude))
        assert sum(task.quantity for task in request.tasks) == request.quantity
        assert all(1 <= task.quantity <= 2 for task in request.tasks)

        option_dates = [option.date for option in request.date_options]
        assert len(option_dates) == len(set(option_dates)) == 3
        assert set(option_dates) <= horizon
        primary = next(option for option in request.date_options if option.priority == 100)
        assert all(
            option.priority < 100
            for option in request.date_options
            if option is not primary
        )
        primary_counts[(primary.date, request.type)] += 1

    expected_primary_counts: Counter[tuple[date, str]] = Counter(
        {
            (day, RequestType.DELIVERY): 2
            for day in sorted(horizon)
        }
    )
    expected_primary_counts.update(
        {(day, RequestType.PICKUP): 1 for day in sorted(horizon)}
    )
    assert primary_counts == expected_primary_counts


@pytest.mark.asyncio
async def test_equal_seed_and_geometry_reproduce_business_payload(
    db_session: AsyncSession,
) -> None:
    """Fresh scenarios with different UUIDs receive identical generated business data."""

    first, _ = await _scenario_with_zone(db_session, name="First")
    second, _ = await _scenario_with_zone(db_session, name="Second")
    payload = WorkloadGeneratorInput(
        start_date=date(2026, 10, 10),
        days=3,
        deliveries_per_day=2,
        pickups_per_day=2,
        alternative_dates_count=1,
        seed=271828,
    )

    first_result = await generate_scenario_workload(db_session, first.id, payload)
    second_result = await generate_scenario_workload(db_session, second.id, payload)

    assert first_result.model_dump(exclude={"scenario_id"}) == second_result.model_dump(
        exclude={"scenario_id"}
    )
    first_requests = await catalog.list_requests(db_session, first.id)
    second_requests = await catalog.list_requests(db_session, second.id)
    assert _business_payload(first_requests) == _business_payload(second_requests)
    assert all(request.external_id is not None for request in first_requests)
    assert len({request.external_id for request in first_requests}) == len(first_requests)


@pytest.mark.asyncio
async def test_repeated_seed_requires_explicit_regeneration_without_adding_duplicates(
    db_session: AsyncSession,
) -> None:
    """A repeated deterministic run cannot masquerade as distinct customer work."""

    scenario, _ = await _scenario_with_zone(db_session, name="Repeated seed")
    payload = WorkloadGeneratorInput(
        start_date=date(2026, 10, 20),
        days=1,
        deliveries_per_day=2,
        pickups_per_day=2,
        alternative_dates_count=0,
        seed=20260822,
    )

    await generate_scenario_workload(db_session, scenario.id, payload)
    original = await catalog.list_requests(db_session, scenario.id)
    original[0].notes = "Отредактированная оператором заметка"
    await db_session.flush()

    with pytest.raises(ApiError) as failure:
        await generate_scenario_workload(db_session, scenario.id, payload)

    assert failure.value.status_code == 409
    assert failure.value.code == "GENERATED_WORKLOAD_ALREADY_EXISTS"
    assert failure.value.extra == {
        "existing_requests": 4,
        "seed": 20260822,
        "start_date": "2026-10-20",
        "end_date": "2026-10-20",
    }
    remaining = await catalog.list_requests(db_session, scenario.id)
    assert [request.id for request in remaining] == [request.id for request in original]


@pytest.mark.asyncio
async def test_generation_requires_zone_without_creating_partial_requests(
    db_session: AsyncSession,
) -> None:
    """A missing zone is an explicit domain error and leaves the scenario unchanged."""

    scenario = await scenarios.create_scenario(
        db_session,
        ScenarioCreate(name="No zones"),
        Settings(),
    )
    payload = WorkloadGeneratorInput(
        start_date=date(2026, 11, 1),
        days=2,
        deliveries_per_day=1,
        pickups_per_day=1,
        alternative_dates_count=1,
        seed=1,
    )

    with pytest.raises(ApiError) as failure:
        await generate_scenario_workload(db_session, scenario.id, payload)

    assert failure.value.status_code == 422
    assert failure.value.code == "NO_ZONES"
    assert await catalog.list_requests(db_session, scenario.id) == []


@pytest.mark.asyncio
async def test_zero_counts_are_valid_noop_with_daily_zero_metrics(
    db_session: AsyncSession,
) -> None:
    """A zero-delivery, zero-pickup command reports the horizon without adding data."""

    scenario, _ = await _scenario_with_zone(db_session, name="Zero workload")
    payload = WorkloadGeneratorInput(
        start_date=date(2026, 12, 1),
        days=2,
        deliveries_per_day=0,
        pickups_per_day=0,
        alternative_dates_count=1,
        seed=99,
    )

    result = await generate_scenario_workload(db_session, scenario.id, payload)

    assert result.created_requests == 0
    assert result.created_deliveries == 0
    assert result.created_pickups == 0
    assert [(item.deliveries, item.pickups) for item in result.daily_counts] == [(0, 0), (0, 0)]
    assert await catalog.list_requests(db_session, scenario.id) == []


@pytest.mark.asyncio
async def test_one_day_regeneration_replaces_only_generated_requests(
    db_session: AsyncSession,
) -> None:
    """One-day reruns replace prior generated rows while preserving manual scenario data."""

    scenario, _ = await _scenario_with_zone(db_session, name="One-day regeneration")
    first_result = await generate_scenario_workload(
        db_session,
        scenario.id,
        WorkloadGeneratorInput(
            start_date=date(2026, 12, 24),
            days=1,
            deliveries_per_day=2,
            pickups_per_day=1,
            alternative_dates_count=0,
            seed=2401,
        ),
    )
    assert first_result.created_requests == 3
    assert first_result.replaced_requests == 0

    first_generated = await catalog.list_requests(db_session, scenario.id)
    first_generated[0].source_system = None
    first_generated[0].external_id = None
    await db_session.flush()
    manual = await catalog.create_request(
        db_session,
        scenario.id,
        LogisticsRequestCreate(
            type=RequestType.DELIVERY,
            name="Ручная заявка",
            address_label="Ручная точка",
            latitude=55.2,
            longitude=37.2,
            quantity=1,
            status=RequestStatus.READY,
            date_options=[
                RequestDateOptionInput(date=date(2026, 12, 24), priority=100)
            ],
        ),
    )

    result = await generate_scenario_workload(
        db_session,
        scenario.id,
        WorkloadGeneratorInput(
            start_date=date(2026, 12, 24),
            days=1,
            deliveries_per_day=1,
            pickups_per_day=2,
            alternative_dates_count=0,
            replace_existing_generated=True,
            seed=2402,
        ),
    )
    requests = await catalog.list_requests(db_session, scenario.id)
    generated = [
        request
        for request in requests
        if request.source_system == GENERATOR_SOURCE_SYSTEM
    ]

    assert result.replaced_requests == 3
    assert result.created_requests == 3
    assert len(requests) == 4
    assert len(generated) == 3
    assert {request.type for request in generated} == {
        RequestType.DELIVERY,
        RequestType.PICKUP,
    }
    assert all(len(request.date_options) == 1 for request in generated)
    assert any(request.id == manual.id for request in requests)


@pytest.mark.asyncio
async def test_regeneration_refuses_to_remove_generated_request_saved_in_plan(
    db_session: AsyncSession,
) -> None:
    """A saved plan reference fences regeneration before any generated row is removed."""

    scenario, _ = await _scenario_with_zone(db_session, name="Planned regeneration")
    await generate_scenario_workload(
        db_session,
        scenario.id,
        WorkloadGeneratorInput(
            start_date=date(2026, 12, 25),
            days=1,
            deliveries_per_day=1,
            pickups_per_day=0,
            seed=2501,
        ),
    )
    request = (await catalog.list_requests(db_session, scenario.id))[0]
    warehouse = await catalog.create_warehouse(
        db_session,
        scenario.id,
        WarehouseCreate(
            name="Склад",
            latitude=55.1,
            longitude=37.1,
        ),
    )
    plan = RoutePlan(
        scenario_id=scenario.id,
        warehouse_id=warehouse.id,
        date=date(2026, 12, 25),
        name="Сохранённый план",
    )
    db_session.add(plan)
    await db_session.flush()
    db_session.add(
        UnassignedTask(
            route_plan_id=plan.id,
            task_id=request.tasks[0].id,
            reason_codes=["NO_SHIFT_CAPACITY"],
            descriptions_ru=["Нет свободной смены"],
        )
    )
    await db_session.flush()

    with pytest.raises(ApiError) as failure:
        await generate_scenario_workload(
            db_session,
            scenario.id,
            WorkloadGeneratorInput(
                start_date=date(2026, 12, 25),
                days=1,
                deliveries_per_day=2,
                pickups_per_day=2,
                replace_existing_generated=True,
                seed=2502,
            ),
        )

    assert failure.value.status_code == 409
    assert failure.value.code == "GENERATED_REQUESTS_ALREADY_PLANNED"
    assert failure.value.extra == {
        "route_stop_references": 0,
        "unassigned_references": 1,
    }
    remaining = await catalog.list_requests(db_session, scenario.id)
    assert [item.id for item in remaining] == [request.id]
