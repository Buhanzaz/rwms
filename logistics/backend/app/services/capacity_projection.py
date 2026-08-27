"""Deterministic simulator-capacity projection published to RWMS logistics-service."""

from __future__ import annotations

import json
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from hashlib import sha256
from uuid import UUID, uuid5

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.errors import ApiError, not_found
from app.integrations.rwms import RwmsPlanningClient
from app.models import LogisticsRequest, Scenario, Warehouse
from app.models.domain import RequestStatus, RequestType
from app.schemas.domain import (
    RwmsCapacitySnapshotCommand,
    RwmsCapacitySnapshotResult,
    RwmsPlanningCapacityJob,
)
from app.services.workload_generator import GENERATOR_SOURCE_SYSTEM

_COORDINATE_QUANTUM = Decimal("0.000001")
_CAPACITY_IDEMPOTENCY_NAMESPACE = UUID("3bbef2d9-2f94-45a4-831a-61402ce25b27")
_EXCLUDED_STATUSES = (
    RequestStatus.DRAFT,
    RequestStatus.COMPLETED,
    RequestStatus.CANCELLED,
)


@dataclass(frozen=True, slots=True)
class CapacityProjection:
    """Immutable outbound snapshot plus the UUIDv5 command identity derived from it."""

    command: RwmsCapacitySnapshotCommand
    idempotency_key: UUID


def _coordinate(value: float) -> Decimal:
    """Round persisted floating coordinates into RWMS's six-decimal contract."""

    return Decimal(str(value)).quantize(_COORDINATE_QUANTUM, rounding=ROUND_HALF_UP)


def _revision(
    scenario_id: UUID,
    warehouse_id: UUID,
    capacity_generation: int,
    jobs: list[RwmsPlanningCapacityJob],
) -> str:
    """Hash one durable workload generation and its sorted capacity facts."""

    facts = {
        "sourceScenarioId": str(scenario_id),
        "warehouseId": str(warehouse_id),
        "capacityGeneration": capacity_generation,
        "jobs": [
            {
                "sourceJobId": str(job.source_job_id),
                "deliveryDate": job.delivery_date.isoformat(),
                "latitude": format(job.latitude, "f"),
                "longitude": format(job.longitude, "f"),
                "cabinCount": job.cabin_count,
                "windowStart": job.window_start.isoformat(),
                "windowEnd": job.window_end.isoformat(),
                "serviceMinutes": job.service_minutes,
            }
            for job in jobs
        ],
    }
    serialized = json.dumps(
        facts,
        ensure_ascii=True,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return sha256(serialized).hexdigest()


async def build_capacity_projection(
    session: AsyncSession,
    scenario_id: UUID,
) -> CapacityProjection:
    """Read one complete generated-delivery snapshot for a uniquely linked warehouse."""

    scenario = await session.scalar(
        select(Scenario).where(Scenario.id == scenario_id).with_for_update()
    )
    if scenario is None:
        raise not_found("scenario", scenario_id)
    warehouses = list(
        await session.scalars(
            select(Warehouse)
            .where(
                Warehouse.scenario_id == scenario_id,
                Warehouse.external_warehouse_id.is_not(None),
            )
            .order_by(Warehouse.id)
        )
    )
    if not warehouses:
        raise ApiError(
            422,
            "RWMS_CAPACITY_WAREHOUSE_NOT_LINKED",
            "Capacity publication requires one warehouse linked to an RWMS warehouse",
        )
    if len(warehouses) != 1:
        raise ApiError(
            422,
            "RWMS_CAPACITY_WAREHOUSE_AMBIGUOUS",
            "Capacity publication requires exactly one linked RWMS warehouse",
        )
    warehouse_id = warehouses[0].external_warehouse_id
    assert warehouse_id is not None

    requests = list(
        await session.scalars(
            select(LogisticsRequest)
            .where(
                LogisticsRequest.scenario_id == scenario_id,
                LogisticsRequest.source_system == GENERATOR_SOURCE_SYSTEM,
                LogisticsRequest.type == RequestType.DELIVERY,
                LogisticsRequest.scheduled_date.is_not(None),
                LogisticsRequest.status.not_in(_EXCLUDED_STATUSES),
            )
            .options(selectinload(LogisticsRequest.date_options))
            .order_by(LogisticsRequest.scheduled_date, LogisticsRequest.external_id)
        )
    )
    if len(requests) > 1_000:
        raise ApiError(
            422,
            "RWMS_CAPACITY_TOO_MANY_JOBS",
            "A capacity snapshot cannot contain more than 1000 generated deliveries",
        )

    jobs: list[RwmsPlanningCapacityJob] = []
    for request in requests:
        if request.external_id is None:
            raise ApiError(
                422,
                "RWMS_CAPACITY_SOURCE_JOB_ID_REQUIRED",
                "Every generated delivery requires a stable external source identity",
            )
        scheduled_date = request.scheduled_date
        assert scheduled_date is not None
        selected = next(
            (option for option in request.date_options if option.date == scheduled_date),
            None,
        )
        if (
            selected is None
            or selected.window_start is None
            or selected.window_end is None
            or not selected.is_hard
        ):
            raise ApiError(
                422,
                "RWMS_CAPACITY_WINDOW_REQUIRED",
                "Every published generated delivery requires a complete hard window",
            )
        if request.service_minutes < 1:
            raise ApiError(
                422,
                "RWMS_CAPACITY_SERVICE_TIME_REQUIRED",
                "Every published generated delivery requires positive service time",
            )
        jobs.append(
            RwmsPlanningCapacityJob(
                source_job_id=request.external_id,
                delivery_date=scheduled_date,
                latitude=_coordinate(request.latitude),
                longitude=_coordinate(request.longitude),
                cabin_count=request.quantity,
                window_start=selected.window_start,
                window_end=selected.window_end,
                service_minutes=request.service_minutes,
            )
        )
    jobs.sort(
        key=lambda job: (
            job.delivery_date,
            job.window_start,
            str(job.source_job_id),
        )
    )
    source_revision = _revision(
        scenario_id,
        warehouse_id,
        scenario.capacity_generation,
        jobs,
    )
    return CapacityProjection(
        command=RwmsCapacitySnapshotCommand(
            warehouse_id=warehouse_id,
            source_generation=scenario.capacity_generation,
            source_revision=source_revision,
            jobs=jobs,
        ),
        idempotency_key=uuid5(
            _CAPACITY_IDEMPOTENCY_NAMESPACE,
            source_revision,
        ),
    )


async def publish_scenario_capacity(
    session: AsyncSession,
    scenario_id: UUID,
    client: RwmsPlanningClient,
) -> RwmsCapacitySnapshotResult:
    """Release the read transaction before replacing the remote projection."""

    client.ensure_capacity_publish_enabled()
    projection = await build_capacity_projection(session, scenario_id)
    await session.commit()
    result = await client.replace_capacity_snapshot(
        scenario_id,
        projection.command,
        idempotency_key=projection.idempotency_key,
    )
    if (
        result.warehouse_id != projection.command.warehouse_id
        or result.source_scenario_id != scenario_id
        or result.source_generation != projection.command.source_generation
        or result.source_revision != projection.command.source_revision
        or result.job_count != len(projection.command.jobs)
    ):
        raise ApiError(
            502,
            "RWMS_CAPACITY_RESPONSE_MISMATCH",
            "RWMS capacity response does not identify the submitted snapshot",
        )
    return result
