"""Deterministic request generation and safe regeneration for scenario load testing."""

from __future__ import annotations

import random
from datetime import date, timedelta
from uuid import UUID, uuid5

from geoalchemy2.shape import to_shape
from shapely.geometry import MultiPolygon, Point, Polygon
from sqlalchemy import and_, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.errors import ApiError, not_found
from app.models import (
    LogisticsRequest,
    RequestDateOption,
    RouteStop,
    Scenario,
    UnassignedTask,
    Zone,
)
from app.models.domain import RequestStatus, RequestType
from app.schemas.domain import (
    LogisticsRequestCreate,
    RequestDateOptionInput,
    ScenarioSettings,
    WorkloadGenerationDailyCount,
    WorkloadGenerationResult,
    WorkloadGeneratorInput,
)
from app.services import catalog

_POINT_ATTEMPTS = 256
GENERATOR_SOURCE_SYSTEM = "SIMULATOR_GENERATOR"
_LEGACY_GENERATOR_NOTES_PREFIX = "Детерминированная нагрузка, seed="
_GENERATOR_EXTERNAL_ID_NAMESPACE = UUID("64bf4fc6-a798-4a4d-9d4a-75a10c708dbb")


def _stable_zones(zones: list[Zone]) -> list[Zone]:
    """Order zones without using database UUIDs so cloned scenarios reproduce payloads."""

    return sorted(zones, key=lambda zone: (zone.code, zone.name))


def _weighted_component(rng: random.Random, geometry: Polygon | MultiPolygon) -> Polygon:
    """Choose a stable polygon component proportionally to its usable area."""

    components = [geometry] if isinstance(geometry, Polygon) else sorted(
        geometry.geoms,
        key=lambda polygon: (polygon.bounds, polygon.wkb_hex),
    )
    return rng.choices(components, weights=[polygon.area for polygon in components], k=1)[0]


def _point_inside_zone(rng: random.Random, zone: Zone) -> Point:
    """Sample a point strictly inside a polygonal zone, excluding all interior holes."""

    geometry = to_shape(zone.geometry)
    if not isinstance(geometry, (Polygon, MultiPolygon)):
        raise ValueError("zone geometry must be Polygon or MultiPolygon")
    component = _weighted_component(rng, geometry)
    min_x, min_y, max_x, max_y = component.bounds
    for _ in range(_POINT_ATTEMPTS):
        candidate = Point(
            rng.uniform(min_x, max_x),
            rng.uniform(min_y, max_y),
        )
        if component.contains(candidate):
            return candidate
    fallback = component.representative_point()
    if not component.contains(fallback):
        raise ValueError("zone has no strictly interior representative point")
    return fallback


def _date_options(
    rng: random.Random,
    horizon: tuple[date, ...],
    primary_date: date,
    alternative_count: int,
) -> list[RequestDateOptionInput]:
    """Build one preferred date and the requested number of unique lower-priority options."""

    alternatives = rng.sample(
        [candidate for candidate in horizon if candidate != primary_date],
        alternative_count,
    )
    options = [RequestDateOptionInput(date=primary_date, priority=100, is_hard=False)]
    options.extend(
        RequestDateOptionInput(
            date=alternative,
            priority=90 - index * 10,
            is_hard=False,
        )
        for index, alternative in enumerate(sorted(alternatives))
    )
    return options


async def _replace_generated_requests(
    session: AsyncSession,
    scenario_id: UUID,
    horizon: tuple[date, ...],
) -> int:
    """Delete only generator-owned requests whose preferred date is in the horizon."""

    generated_marker = or_(
        LogisticsRequest.source_system == GENERATOR_SOURCE_SYSTEM,
        and_(
            LogisticsRequest.source_system.is_(None),
            LogisticsRequest.external_id.is_(None),
            LogisticsRequest.notes.like(f"{_LEGACY_GENERATOR_NOTES_PREFIX}%"),
        ),
    )
    statement = (
        select(LogisticsRequest)
        .join(RequestDateOption)
        .where(
            LogisticsRequest.scenario_id == scenario_id,
            RequestDateOption.priority == 100,
            RequestDateOption.date.between(horizon[0], horizon[-1]),
            generated_marker,
        )
        .options(selectinload(LogisticsRequest.tasks))
        .with_for_update()
    )
    requests = list((await session.scalars(statement)).unique().all())
    task_ids = [task.id for request in requests for task in request.tasks]
    if task_ids:
        route_stop_count = int(
            await session.scalar(
                select(func.count(RouteStop.id)).where(RouteStop.task_id.in_(task_ids))
            )
            or 0
        )
        unassigned_count = int(
            await session.scalar(
                select(func.count(UnassignedTask.id)).where(
                    UnassignedTask.task_id.in_(task_ids)
                )
            )
            or 0
        )
        if route_stop_count or unassigned_count:
            raise ApiError(
                409,
                "GENERATED_REQUESTS_ALREADY_PLANNED",
                (
                    "Тестовые заявки выбранного периода уже входят в сохранённый план. "
                    "Чтобы сохранить историю плана, выберите другой день или создайте "
                    "новый тестовый сценарий."
                ),
                extra={
                    "route_stop_references": route_stop_count,
                    "unassigned_references": unassigned_count,
                },
            )

    for request in requests:
        await session.delete(request)
    await session.flush()
    return len(requests)


async def _reject_repeated_seed_without_regeneration(
    session: AsyncSession,
    scenario_id: UUID,
    horizon: tuple[date, ...],
    seed: int,
    deliveries_per_day: int,
    pickups_per_day: int,
) -> None:
    """Prevent an identical generator run from creating duplicate customer stops."""

    expected_external_ids = tuple(
        _generated_external_id(seed, primary_date, request_type, sequence)
        for primary_date in horizon
        for request_type, count in (
            (RequestType.DELIVERY, deliveries_per_day),
            (RequestType.PICKUP, pickups_per_day),
        )
        for sequence in range(1, count + 1)
    )
    existing_count = int(
        await session.scalar(
            select(func.count(func.distinct(LogisticsRequest.id)))
            .join(RequestDateOption)
            .where(
                LogisticsRequest.scenario_id == scenario_id,
                LogisticsRequest.source_system == GENERATOR_SOURCE_SYSTEM,
                or_(
                    LogisticsRequest.external_id.in_(expected_external_ids),
                    LogisticsRequest.notes
                    == f"{_LEGACY_GENERATOR_NOTES_PREFIX}{seed}",
                ),
                RequestDateOption.priority == 100,
                RequestDateOption.date.between(horizon[0], horizon[-1]),
            )
        )
        or 0
    )
    if existing_count:
        raise ApiError(
            409,
            "GENERATED_WORKLOAD_ALREADY_EXISTS",
            (
                "Нагрузка для того же seed уже существует в выбранном периоде. "
                "Включите «Перегенерировать выбранный период», чтобы не создавать "
                "повторные поездки в те же точки."
            ),
            extra={
                "existing_requests": existing_count,
                "seed": seed,
                "start_date": horizon[0].isoformat(),
                "end_date": horizon[-1].isoformat(),
            },
        )


def _generated_external_id(
    seed: int,
    primary_date: date,
    request_type: str,
    sequence: int,
) -> UUID:
    """Return a stable identity for one deterministic generated source request."""

    return uuid5(
        _GENERATOR_EXTERNAL_ID_NAMESPACE,
        f"{seed}:{primary_date.isoformat()}:{request_type}:{sequence}",
    )


async def generate_scenario_workload(
    session: AsyncSession,
    scenario_id: UUID,
    payload: WorkloadGeneratorInput,
) -> WorkloadGenerationResult:
    """Generate or safely replace a deterministic workload through canonical creation."""

    # The scenario row is the command fence. Without it, two concurrent runs can
    # both pass duplicate detection before either inserts its stable source IDs.
    scenario = await session.scalar(
        select(Scenario).where(Scenario.id == scenario_id).with_for_update()
    )
    if scenario is None:
        raise not_found("scenario", scenario_id)
    zones = _stable_zones(
        list(
            await session.scalars(
                select(Zone).where(Zone.scenario_id == scenario_id)
            )
        )
    )
    if not zones:
        raise ApiError(
            422,
            "NO_ZONES",
            "Для генерации нагрузки требуется хотя бы одна логистическая зона.",
        )

    rng = random.Random(payload.seed)
    settings = ScenarioSettings.model_validate(scenario.settings)
    horizon = tuple(payload.start_date + timedelta(days=offset) for offset in range(payload.days))
    replaced_requests = 0
    if payload.replace_existing_generated:
        replaced_requests = await _replace_generated_requests(
            session,
            scenario_id,
            horizon,
        )
    elif payload.deliveries_per_day or payload.pickups_per_day:
        await _reject_repeated_seed_without_regeneration(
            session,
            scenario_id,
            horizon,
            payload.seed,
            payload.deliveries_per_day,
            payload.pickups_per_day,
        )
    created_deliveries = 0
    created_pickups = 0
    daily_counts: list[WorkloadGenerationDailyCount] = []

    for primary_date in horizon:
        daily_counts.append(
            WorkloadGenerationDailyCount(
                date=primary_date,
                deliveries=payload.deliveries_per_day,
                pickups=payload.pickups_per_day,
            )
        )
        for request_type, count in (
            (RequestType.DELIVERY, payload.deliveries_per_day),
            (RequestType.PICKUP, payload.pickups_per_day),
        ):
            for sequence in range(1, count + 1):
                zone = rng.choice(zones)
                point = _point_inside_zone(rng, zone)
                request_label = (
                    "Доставка" if request_type == RequestType.DELIVERY else "Вывоз"
                )
                generated_request = await catalog.create_request(
                    session,
                    scenario_id,
                    LogisticsRequestCreate(
                        type=request_type,
                        name=f"{request_label} {primary_date.isoformat()} №{sequence}",
                        address_label=(
                            f"Сгенерированная точка {zone.code}: "
                            f"{point.y:.6f}, {point.x:.6f}"
                        ),
                        latitude=point.y,
                        longitude=point.x,
                        quantity=rng.choice((1, 2)),
                        service_minutes=settings.default_service_minutes,
                        priority=0,
                        status=RequestStatus.READY,
                        split_allowed=True,
                        notes=f"Детерминированная нагрузка, seed={payload.seed}",
                        date_options=_date_options(
                            rng,
                            horizon,
                            primary_date,
                            payload.alternative_dates_count,
                        ),
                    ),
                )
                generated_request.source_system = GENERATOR_SOURCE_SYSTEM
                generated_request.external_id = _generated_external_id(
                    payload.seed,
                    primary_date,
                    request_type,
                    sequence,
                )
                if request_type == RequestType.DELIVERY:
                    created_deliveries += 1
                else:
                    created_pickups += 1

    created_requests = created_deliveries + created_pickups
    return WorkloadGenerationResult(
        scenario_id=scenario_id,
        seed=payload.seed,
        start_date=payload.start_date,
        end_date=horizon[-1],
        created_requests=created_requests,
        created_deliveries=created_deliveries,
        created_pickups=created_pickups,
        replaced_requests=replaced_requests,
        daily_counts=daily_counts,
    )
