"""Anonymous simulator test capacity and isochrone tariffs published to RWMS."""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from decimal import ROUND_HALF_UP, Decimal
from hashlib import sha256
from uuid import UUID, uuid5

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.errors import ApiError, not_found
from app.integrations.rwms import RwmsPlanningClient
from app.models import (
    DriverShift,
    LogisticsRequest,
    PlanningDayClosure,
    Warehouse,
)
from app.models.domain import RequestStatus
from app.schemas.domain import (
    PlanningSettings,
    RwmsCapacitySnapshotCommand,
    RwmsCapacitySnapshotResult,
    RwmsIsochroneTariff,
    RwmsPlanningCapacityJob,
    RwmsPlanningCapacityShift,
)
from app.services.workload_generator import GENERATOR_SOURCE_SYSTEM

_COORDINATE_QUANTUM = Decimal("0.000001")
_CAPACITY_IDEMPOTENCY_NAMESPACE = UUID("3bbef2d9-2f94-45a4-831a-61402ce25b27")
_CAPACITY_SHIFT_NAMESPACE = UUID("48645899-fd60-47ad-95ec-b5cb21b810fe")
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


def _capacity_shift_end(
    delivery_date: date,
    shift_end: time,
    settings: PlanningSettings,
) -> time:
    """Extend published capacity by configured soft overtime within its local day."""

    if not settings.allow_soft_overtime or settings.soft_overtime_limit_minutes == 0:
        return shift_end
    extended = datetime.combine(delivery_date, shift_end) + timedelta(
        minutes=settings.soft_overtime_limit_minutes
    )
    latest = datetime.combine(delivery_date, time.max)
    return min(extended, latest).time()


def _revision(
    warehouse_id: UUID,
    capacity_generation: int,
    jobs: list[RwmsPlanningCapacityJob],
    shifts: list[RwmsPlanningCapacityShift],
    isochrone_tariffs: list[RwmsIsochroneTariff],
) -> str:
    """Hash one durable workload generation and its sorted capacity facts."""

    facts = {
        "warehouseId": str(warehouse_id),
        "capacityGeneration": capacity_generation,
        "isochroneTariffs": [
            {
                "travelMinutes": tariff.travel_minutes,
                "priceRubles": tariff.price_rubles,
            }
            for tariff in isochrone_tariffs
        ],
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
                "taskType": job.task_type,
                "trailerAccessAllowed": job.trailer_access_allowed,
                "priority": job.priority,
                "mandatory": job.mandatory,
            }
            for job in jobs
        ],
        "shifts": [
            {
                "sourceShiftId": str(shift.source_shift_id),
                "deliveryDate": shift.delivery_date.isoformat(),
                "shiftStart": shift.shift_start.isoformat(),
                "shiftEnd": shift.shift_end.isoformat(),
                "breakMinutes": shift.break_minutes,
                "cabinCapacity": shift.cabin_capacity,
            }
            for shift in shifts
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
    warehouse_id: UUID,
) -> CapacityProjection:
    """Read generator-owned anonymous test capacity and tariffs for one linked warehouse."""

    warehouse = await session.scalar(
        select(Warehouse).where(Warehouse.id == warehouse_id).with_for_update()
    )
    if warehouse is None:
        raise not_found("warehouse", warehouse_id)
    external_warehouse_id = warehouse.external_warehouse_id
    settings = PlanningSettings.model_validate(warehouse.settings)

    requests = list(
        await session.scalars(
            select(LogisticsRequest)
            .where(
                LogisticsRequest.warehouse_id == warehouse_id,
                LogisticsRequest.source_system == GENERATOR_SOURCE_SYSTEM,
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
            "A capacity snapshot cannot contain more than 1000 generated tasks",
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
                task_type=request.type,
                trailer_access_allowed=request.trailer_access_allowed is not False,
                priority=max(0, request.priority),
                # The canonical capacity aggregate requires every DELIVERY job to be
                # obligatory. Legacy generated rows may still carry false because that
                # flag only controlled local planning; optional PICKUP semantics remain.
                mandatory=(request.type == "DELIVERY" or request.mandatory),
            )
        )
    jobs.sort(
        key=lambda job: (
            job.delivery_date,
            job.window_start,
            job.task_type,
            str(job.source_job_id),
        )
    )
    closed_dates = set(
        await session.scalars(
            select(PlanningDayClosure.date).where(
                PlanningDayClosure.warehouse_id == warehouse_id,
            )
        )
    )
    active_shifts = list(
        await session.scalars(
            select(DriverShift)
            .where(
                DriverShift.warehouse_id == warehouse_id,
                DriverShift.active.is_(True),
            )
            .options(
                selectinload(DriverShift.driver),
                selectinload(DriverShift.vehicle),
            )
            .order_by(DriverShift.date_from, DriverShift.start_time, DriverShift.id)
        )
    )
    shifts: list[RwmsPlanningCapacityShift] = []
    for shift in active_shifts:
        if not shift.driver.active or not shift.vehicle.active:
            continue
        if shift.break_minutes > 720:
            raise ApiError(
                422,
                "RWMS_CAPACITY_SHIFT_BREAK_INVALID",
                "Published shift break time cannot exceed 720 minutes",
            )
        current_date = shift.date_from
        while current_date <= shift.date_to:
            if current_date not in closed_dates:
                shifts.append(
                    RwmsPlanningCapacityShift(
                        source_shift_id=uuid5(
                            _CAPACITY_SHIFT_NAMESPACE,
                            f"{shift.id}:{current_date.isoformat()}",
                        ),
                        delivery_date=current_date,
                        shift_start=shift.start_time,
                        shift_end=_capacity_shift_end(
                            current_date,
                            shift.end_time,
                            settings,
                        ),
                        break_minutes=shift.break_minutes,
                        cabin_capacity=shift.vehicle.capacity,
                    )
                )
            current_date += timedelta(days=1)
    if len(shifts) > 2_000:
        raise ApiError(
            422,
            "RWMS_CAPACITY_TOO_MANY_SHIFTS",
            "A capacity snapshot cannot contain more than 2000 active shifts",
        )
    shifts.sort(
        key=lambda shift: (
            shift.delivery_date,
            shift.shift_start,
            str(shift.source_shift_id),
        )
    )
    isochrone_tariffs = [
        RwmsIsochroneTariff(
            travel_minutes=tariff.travel_minutes,
            price_rubles=tariff.price_rubles,
        )
        for tariff in warehouse.isochrone_tariffs
    ]
    source_revision = _revision(
        external_warehouse_id,
        warehouse.capacity_generation,
        jobs,
        shifts,
        isochrone_tariffs,
    )
    return CapacityProjection(
        command=RwmsCapacitySnapshotCommand(
            source_generation=warehouse.capacity_generation,
            source_revision=source_revision,
            jobs=jobs,
            shifts=shifts,
            isochrone_tariffs=isochrone_tariffs,
        ),
        idempotency_key=uuid5(
            _CAPACITY_IDEMPOTENCY_NAMESPACE,
            source_revision,
        ),
    )


async def publish_warehouse_capacity(
    session: AsyncSession,
    warehouse_id: UUID,
    client: RwmsPlanningClient,
) -> RwmsCapacitySnapshotResult:
    """Release the read transaction before replacing the remote projection."""

    client.ensure_capacity_publish_enabled()
    projection = await build_capacity_projection(session, warehouse_id)
    warehouse = await session.get(Warehouse, warehouse_id)
    if warehouse is None:
        raise not_found("warehouse", warehouse_id)
    await session.commit()
    result = await client.replace_capacity_snapshot(
        warehouse.external_warehouse_id,
        projection.command,
        idempotency_key=projection.idempotency_key,
    )
    if (
        result.warehouse_id != warehouse.external_warehouse_id
        or result.source_generation != projection.command.source_generation
        or result.source_revision != projection.command.source_revision
        or result.job_count != len(projection.command.jobs)
        or result.shift_count != len(projection.command.shifts)
        or result.isochrone_tariff_count != len(projection.command.isochrone_tariffs)
    ):
        raise ApiError(
            502,
            "RWMS_CAPACITY_RESPONSE_MISMATCH",
            "RWMS capacity response does not identify the submitted snapshot",
        )
    return result
