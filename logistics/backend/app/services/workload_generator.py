"""Deterministic request generation and safe regeneration for warehouse load testing."""

from __future__ import annotations

import random
from datetime import date, time, timedelta
from uuid import UUID, uuid5

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ApiError, not_found
from app.models import (
    LogisticsRequest,
    RequestDateOption,
    RoutePlan,
    Warehouse,
)
from app.models.domain import RequestStatus, RequestType
from app.routing import GeoPoint, RoadSnapNotFoundError, RoadSnapper, SnappedPoint
from app.schemas.domain import (
    LogisticsRequestCreate,
    PlanningSettings,
    RequestDateOptionInput,
    WorkloadDeletionResult,
    WorkloadGenerationDailyCount,
    WorkloadGenerationResult,
    WorkloadGeneratorInput,
)
from app.services import catalog
from app.services.capacity_generation import advance_warehouse_capacity_generation

_ROAD_SNAP_ATTEMPTS = 24
GENERATOR_SOURCE_SYSTEM = "WAREHOUSE_WORKLOAD_GENERATOR"
_GENERATOR_EXTERNAL_ID_NAMESPACE = UUID("64bf4fc6-a798-4a4d-9d4a-75a10c708dbb")
_DELIVERY_WINDOWS = (
    (time(9), time(12)),
    (time(12), time(15)),
    (time(15), time(18)),
)
_PICKUP_WINDOW = (time(9), time(18))


async def _routable_point_near_warehouse(
    rng: random.Random,
    warehouse: Warehouse,
    snapper: RoadSnapper,
) -> SnappedPoint:
    """Sample and snap bounded deterministic candidates around the warehouse point."""

    for _ in range(_ROAD_SNAP_ATTEMPTS):
        try:
            return await snapper.snap_point(
                GeoPoint(
                    lon=warehouse.longitude + rng.uniform(-0.25, 0.25),
                    lat=warehouse.latitude + rng.uniform(-0.18, 0.18),
                    is_city=True,
                )
            )
        except RoadSnapNotFoundError:
            continue
    raise ApiError(
        422,
        "NO_ROUTABLE_POINT_NEAR_WAREHOUSE",
        "Не удалось найти доступную для автомобиля точку рядом со складом.",  # noqa: RUF001
    )


def _date_options(
    rng: random.Random,
    horizon: tuple[date, ...],
    primary_date: date,
    alternative_count: int,
    request_type: RequestType,
    sequence: int,
    pickup_window: tuple[time, time] = _PICKUP_WINDOW,
) -> list[RequestDateOptionInput]:
    """Build complete hard windows, using the depot day for generated pickups."""

    alternatives = rng.sample(
        [candidate for candidate in horizon if candidate != primary_date],
        alternative_count,
    )
    window_start, window_end = (
        _DELIVERY_WINDOWS[(sequence - 1) % len(_DELIVERY_WINDOWS)]
        if request_type == RequestType.DELIVERY
        else pickup_window
    )
    options = [
        RequestDateOptionInput(
            date=primary_date,
            priority=100,
            window_start=window_start,
            window_end=window_end,
            is_hard=True,
        )
    ]
    options.extend(
        RequestDateOptionInput(
            date=alternative,
            priority=90 - index * 10,
            window_start=window_start,
            window_end=window_end,
            is_hard=True,
        )
        for index, alternative in enumerate(sorted(alternatives))
    )
    return options


async def _replace_generated_requests(
    session: AsyncSession,
    warehouse_id: UUID,
    horizon: tuple[date, ...],
) -> int:
    """Delete only generator-owned requests whose preferred date is in the horizon."""

    statement = (
        select(LogisticsRequest)
        .join(RequestDateOption)
        .where(
            LogisticsRequest.warehouse_id == warehouse_id,
            RequestDateOption.priority == 100,
            RequestDateOption.date.between(horizon[0], horizon[-1]),
            LogisticsRequest.source_system == GENERATOR_SOURCE_SYSTEM,
        )
        .with_for_update()
    )
    requests = list((await session.scalars(statement)).unique().all())
    for request in requests:
        await session.delete(request)
    await session.flush()
    return len(requests)


async def _delete_route_plans(
    session: AsyncSession,
    warehouse_id: UUID,
    horizon: tuple[date, ...],
) -> int:
    """Delete every saved plan derived from workload inside the affected horizon."""

    result = await session.execute(
        delete(RoutePlan)
        .where(
            RoutePlan.warehouse_id == warehouse_id,
            RoutePlan.date.between(horizon[0], horizon[-1]),
        )
        .returning(RoutePlan.id)
    )
    deleted_plan_ids = list(result.scalars().all())
    await session.flush()
    return len(deleted_plan_ids)


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


async def generate_warehouse_workload(
    session: AsyncSession,
    warehouse_id: UUID,
    payload: WorkloadGeneratorInput,
    snapper: RoadSnapper,
) -> WorkloadGenerationResult:
    """Delete affected plans and replace deterministic workload through canonical creation."""

    # The warehouse row serializes replacement and insertion so concurrent runs
    # cannot interleave deletion with another command's stable source identities.
    warehouse = await session.scalar(
        select(Warehouse).where(Warehouse.id == warehouse_id).with_for_update()
    )
    if warehouse is None:
        raise not_found("warehouse", warehouse_id)
    pickup_window = (_PICKUP_WINDOW[0], warehouse.working_day_end)
    if pickup_window[1] <= pickup_window[0]:
        raise ApiError(
            422,
            "GENERATED_PICKUP_WINDOW_INVALID",
            "Рабочий день склада должен заканчиваться после 09:00.",
        )

    rng = random.Random(payload.seed)
    settings = PlanningSettings.model_validate(warehouse.settings)
    horizon = tuple(payload.start_date + timedelta(days=offset) for offset in range(payload.days))
    deleted_plans = await _delete_route_plans(session, warehouse_id, horizon)
    replaced_requests = await _replace_generated_requests(
        session,
        warehouse_id,
        horizon,
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
                snapped = await _routable_point_near_warehouse(rng, warehouse, snapper)
                point = snapped.point
                road_label = f", дорога: {snapped.name}" if snapped.name else ""
                generated_request = await catalog.create_request(
                    session,
                    warehouse_id,
                    LogisticsRequestCreate(
                        type=request_type,
                        name=f"№{sequence}",
                        address_label=(
                            f"Сгенерированная дорожная точка {warehouse.name}{road_label}: "
                            f"{point.lat:.6f}, {point.lon:.6f}"
                        ),
                        latitude=point.lat,
                        longitude=point.lon,
                        quantity=rng.choice((1, 2)),
                        cargo_length_mm=payload.cargo_length_mm,
                        cargo_width_mm=payload.cargo_width_mm,
                        cargo_height_mm=payload.cargo_height_mm,
                        cargo_weight_kg=payload.cargo_weight_kg,
                        service_minutes=settings.default_service_minutes,
                        priority=0,
                        status=RequestStatus.READY,
                        split_allowed=True,
                        trailer_access_allowed=True,
                        notes=f"Детерминированная нагрузка, seed={payload.seed}",
                        date_options=_date_options(
                            rng,
                            horizon,
                            primary_date,
                            payload.alternative_dates_count,
                            request_type,
                            sequence,
                            pickup_window,
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
                generated_request.scheduled_date = primary_date
                if request_type == RequestType.DELIVERY:
                    created_deliveries += 1
                else:
                    created_pickups += 1

    # The monotonic generation separates an intentional A -> B -> A workload
    # replacement from a delayed network retry of the first A publication.
    await advance_warehouse_capacity_generation(session, warehouse_id)
    created_requests = created_deliveries + created_pickups
    return WorkloadGenerationResult(
        warehouse_id=warehouse_id,
        seed=payload.seed,
        start_date=payload.start_date,
        end_date=horizon[-1],
        created_requests=created_requests,
        created_deliveries=created_deliveries,
        created_pickups=created_pickups,
        replaced_requests=replaced_requests,
        deleted_plans=deleted_plans,
        daily_counts=daily_counts,
    )


async def delete_generated_workload(
    session: AsyncSession,
    warehouse_id: UUID,
    target_date: date,
) -> WorkloadDeletionResult:
    """Atomically delete generated requests and saved plans for one exact date."""

    warehouse = await session.scalar(
        select(Warehouse).where(Warehouse.id == warehouse_id).with_for_update()
    )
    if warehouse is None:
        raise not_found("warehouse", warehouse_id)
    horizon = (target_date,)
    deleted_plans = await _delete_route_plans(session, warehouse_id, horizon)
    deleted_requests = await _replace_generated_requests(
        session,
        warehouse_id,
        horizon,
    )
    if deleted_requests > 0:
        await advance_warehouse_capacity_generation(session, warehouse_id)
    return WorkloadDeletionResult(
        warehouse_id=warehouse_id,
        date=target_date,
        deleted_requests=deleted_requests,
        deleted_plans=deleted_plans,
    )
