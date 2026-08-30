"""Direct contractor handoff without internal vehicle, shift, or cycle planning."""

from __future__ import annotations

from collections.abc import Sequence
from datetime import date, datetime
from uuid import UUID, uuid5
from zoneinfo import ZoneInfo

from pydantic import ValidationError
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.db import utc_now
from app.errors import ApiError, not_found
from app.integrations.rwms import RwmsPlanningClient
from app.models import (
    LogisticsRequest,
    PlanningTask,
    RouteCycle,
    RoutePlan,
    RouteStop,
    UnassignedTask,
    Warehouse,
)
from app.models.domain import PlanStatus, RequestStatus, RequestType, TaskStatus
from app.schemas.domain import (
    ContractorAssignmentCreate,
    ContractorDispatchCreate,
    ContractorDispatchRead,
    RwmsAssignmentsCommand,
    RwmsDriverIdentity,
    RwmsPlanningAssignment,
    RwmsPlanningRequest,
)
from app.services.catalog import RWMS_SOURCE_SYSTEM

CONTRACTOR_HANDOFF = "CONTRACTOR_HANDOFF"
CONTRACTOR_HANDOFF_ACTOR = "logistics-simulator"
_CONTRACTOR_COMMAND_NAMESPACE = UUID("d7724ad2-e9e7-49db-bc5f-cefd664448c7")
_MUTABLE_PLAN_STATUSES = {
    PlanStatus.DRAFT,
    PlanStatus.GENERATED,
    PlanStatus.VALIDATED,
}


async def dispatch_requests_to_contractor(
    session: AsyncSession,
    warehouse_id: UUID,
    payload: ContractorDispatchCreate,
    client: RwmsPlanningClient,
) -> ContractorDispatchRead:
    """Hand eligible requests for one selected warehouse day to one contractor."""

    warehouse = await session.scalar(
        select(Warehouse).where(Warehouse.id == warehouse_id).with_for_update()
    )
    if warehouse is None:
        raise not_found("warehouse", warehouse_id)
    if warehouse.external_warehouse_id is None:
        raise ApiError(
            422,
            "RWMS_WAREHOUSE_NOT_LINKED",
            "Склад не связан с каталогом RWMS.",  # noqa: RUF001
        )

    latest_plan_id, unassigned_task_ids = await _latest_plan_unassigned_tasks(
        session,
        warehouse_id,
        payload.planning_date,
    )
    request_ids = payload.request_ids
    if payload.mode == "AUTO":
        request_ids = await _automatic_request_ids(
            session,
            warehouse_id,
            payload.planning_date,
            latest_plan_id,
        )
    if not request_ids:
        raise ApiError(
            409,
            "NO_UNASSIGNED_REQUESTS",
            "На выбранную дату нет заявок, которые можно передать наёмному водителю.",  # noqa: RUF001
        )

    requests = await _load_requests_for_handoff(session, request_ids)
    if len(requests) != len(request_ids):
        raise ApiError(
            422,
            "CONTRACTOR_DISPATCH_REQUESTS_INVALID",
            "Одна или несколько выбранных заявок больше недоступны.",
        )
    ordered_requests = [requests[request_id] for request_id in request_ids]
    if payload.mode == "AUTO" and latest_plan_id is not None:
        ordered_requests = [
            request
            for request in ordered_requests
            if request.tasks and all(task.id in unassigned_task_ids for task in request.tasks)
        ]
        if not ordered_requests:
            raise ApiError(
                409,
                "NO_UNASSIGNED_REQUESTS",
                "На выбранную дату нет заявок, которые можно передать наёмному водителю.",  # noqa: RUF001
            )
    replayed: list[LogisticsRequest] = []
    pending: list[LogisticsRequest] = []
    for request in ordered_requests:
        if request.warehouse_id != warehouse_id:
            raise ApiError(
                422,
                "CONTRACTOR_DISPATCH_WAREHOUSE_MISMATCH",
                "Выбранная заявка относится к другому складу.",
            )
        if request.scheduled_date != payload.planning_date:
            raise ApiError(
                422,
                "CONTRACTOR_DISPATCH_DATE_MISMATCH",
                "Выбранная заявка относится к другой дате.",
            )
        if (
            request.assignment_type == CONTRACTOR_HANDOFF
            and request.assigned_contractor_worker_id == payload.contractor_worker_id
            and request.scheduled_date == payload.planning_date
        ):
            replayed.append(request)
            continue
        _validate_dispatch_request(
            request,
            warehouse_id=warehouse_id,
            planning_date=payload.planning_date,
            latest_plan_id=latest_plan_id,
            unassigned_task_ids=unassigned_task_ids,
        )
        pending.append(request)

    if not pending:
        contractor_name = replayed[0].assigned_contractor_name
        assert contractor_name is not None
        return ContractorDispatchRead(
            contractor_worker_id=payload.contractor_worker_id,
            contractor_name=contractor_name,
            planning_date=payload.planning_date,
            mode=payload.mode,
            assigned_request_ids=[request.id for request in replayed],
            assigned_count=len(replayed),
        )

    planned_at = datetime.combine(
        payload.planning_date,
        warehouse.working_day_start,
        tzinfo=ZoneInfo(warehouse.timezone),
    )
    contractor = await _require_contractor(
        client,
        warehouse.external_warehouse_id,
        payload.contractor_worker_id,
        planned_at,
    )
    real_requests = [request for request in pending if request.source_system == RWMS_SOURCE_SYSTEM]
    command: RwmsAssignmentsCommand | None = None
    idempotency_key: UUID | None = None
    if real_requests:
        command, idempotency_key = _rwms_contractor_batch_command(
            real_requests,
            warehouse,
            contractor,
            payload.planning_date,
        )

    await _invalidate_requests_plans(session, pending)
    if command is not None and idempotency_key is not None:
        await _apply_rwms_contractor_command(client, command, idempotency_key)

    for request in pending:
        _persist_contractor_handoff(request, contractor)
    await session.flush()
    assigned = [*replayed, *pending]
    return ContractorDispatchRead(
        contractor_worker_id=contractor.worker_id,
        contractor_name=contractor.display_name,
        planning_date=payload.planning_date,
        mode=payload.mode,
        assigned_request_ids=[request.id for request in assigned],
        assigned_count=len(assigned),
    )


async def assign_request_to_contractor(
    session: AsyncSession,
    request_id: UUID,
    payload: ContractorAssignmentCreate,
    client: RwmsPlanningClient,
) -> LogisticsRequest:
    """Validate and persist one idempotent direct handoff under the request row lock."""

    request = await _load_request_for_handoff(session, request_id)
    if request.assignment_type is not None:
        if (
            request.assignment_type == CONTRACTOR_HANDOFF
            and request.assigned_contractor_worker_id == payload.contractor_worker_id
        ):
            return request
        raise ApiError(
            409,
            "REQUEST_ALREADY_ASSIGNED",
            "Заявка уже передана другому исполнителю.",
        )
    _validate_ready_request(request)
    assert request.scheduled_date is not None
    selected_option = next(
        option for option in request.date_options if option.date == request.scheduled_date
    )
    warehouse = request.warehouse
    if warehouse.external_warehouse_id is None:
        raise ApiError(
            422,
            "RWMS_WAREHOUSE_NOT_LINKED",
            "Склад не связан с каталогом RWMS.",  # noqa: RUF001
        )
    planned_time = selected_option.window_start or warehouse.working_day_start
    planned_at = datetime.combine(
        request.scheduled_date,
        planned_time,
        tzinfo=ZoneInfo(warehouse.timezone),
    )
    contractor = await _require_contractor(
        client,
        warehouse.external_warehouse_id,
        payload.contractor_worker_id,
        planned_at,
    )
    command: RwmsAssignmentsCommand | None = None
    idempotency_key: UUID | None = None
    if request.source_system == RWMS_SOURCE_SYSTEM:
        command, idempotency_key = _rwms_contractor_command(request, warehouse, contractor)
    await _invalidate_request_plans(session, request)

    if command is not None and idempotency_key is not None:
        await _apply_rwms_contractor_command(client, command, idempotency_key)

    _persist_contractor_handoff(request, contractor)
    await session.flush()
    return request


async def _load_request_for_handoff(
    session: AsyncSession,
    request_id: UUID,
) -> LogisticsRequest:
    """Lock the complete request graph used by the contractor command."""

    request = await session.scalar(
        select(LogisticsRequest)
        .where(LogisticsRequest.id == request_id)
        .options(
            selectinload(LogisticsRequest.warehouse),
            selectinload(LogisticsRequest.date_options),
            selectinload(LogisticsRequest.tasks),
        )
        .with_for_update()
    )
    if request is None:
        raise not_found("request", request_id)
    return request


async def _load_requests_for_handoff(
    session: AsyncSession,
    request_ids: Sequence[UUID],
) -> dict[UUID, LogisticsRequest]:
    """Lock a bounded request set and preload every fact required by dispatch validation."""

    rows = await session.scalars(
        select(LogisticsRequest)
        .where(LogisticsRequest.id.in_(tuple(request_ids)))
        .options(
            selectinload(LogisticsRequest.warehouse),
            selectinload(LogisticsRequest.date_options),
            selectinload(LogisticsRequest.tasks),
        )
        .with_for_update()
    )
    return {request.id: request for request in rows.unique().all()}


async def _latest_plan_unassigned_tasks(
    session: AsyncSession,
    warehouse_id: UUID,
    planning_date: date,
) -> tuple[UUID | None, set[UUID]]:
    """Return the latest active plan and the exact tasks it still leaves unassigned."""

    plan_id = await session.scalar(
        select(RoutePlan.id)
        .where(
            RoutePlan.warehouse_id == warehouse_id,
            RoutePlan.date == planning_date,
            RoutePlan.status != PlanStatus.ARCHIVED,
        )
        .order_by(RoutePlan.updated_at.desc(), RoutePlan.id.desc())
        .limit(1)
    )
    if plan_id is None:
        return None, set()
    task_ids = await session.scalars(
        select(UnassignedTask.task_id).where(UnassignedTask.route_plan_id == plan_id)
    )
    return plan_id, set(task_ids.all())


async def _automatic_request_ids(
    session: AsyncSession,
    warehouse_id: UUID,
    planning_date: date,
    latest_plan_id: UUID | None,
) -> list[UUID]:
    """Select eligible requests deterministically without invoking the internal optimizer."""

    statement = select(LogisticsRequest.id).where(
        LogisticsRequest.warehouse_id == warehouse_id,
        LogisticsRequest.scheduled_date == planning_date,
        LogisticsRequest.status == RequestStatus.READY,
        LogisticsRequest.assignment_type.is_(None),
        LogisticsRequest.type.in_((RequestType.DELIVERY, RequestType.PICKUP)),
    )
    if latest_plan_id is not None:
        statement = (
            statement.join(PlanningTask, PlanningTask.request_id == LogisticsRequest.id)
            .join(UnassignedTask, UnassignedTask.task_id == PlanningTask.id)
            .where(UnassignedTask.route_plan_id == latest_plan_id)
        )
    request_ids = await session.scalars(
        statement.order_by(
            LogisticsRequest.priority.desc(),
            LogisticsRequest.created_at,
            LogisticsRequest.id,
        )
    )
    return list(dict.fromkeys(request_ids.all()))


def _validate_ready_request(request: LogisticsRequest) -> None:
    """Validate request lifecycle and selected date before any assignment side effect."""

    if request.type not in (RequestType.DELIVERY, RequestType.PICKUP):
        raise ApiError(
            422,
            "CONTRACTOR_HANDOFF_REQUEST_TYPE_UNSUPPORTED",
            "Наёмному водителю можно передать только доставку или вывоз.",
        )
    if request.status != RequestStatus.READY:
        raise ApiError(
            409,
            "REQUEST_NOT_READY",
            "Передать можно только готовую нераспределённую заявку.",
        )
    if request.scheduled_date is None:
        raise ApiError(
            422,
            "REQUEST_DATE_REQUIRED",
            "Сначала выберите дату заявки.",
        )
    if not any(option.date == request.scheduled_date for option in request.date_options):
        raise ApiError(
            422,
            "REQUEST_DATE_NOT_ALLOWED",
            "Выбранная дата отсутствует среди согласованных дат заявки.",
        )
    if not request.tasks:
        raise ApiError(
            422,
            "REQUEST_TASKS_REQUIRED",
            "У заявки отсутствуют задания для передачи водителю.",  # noqa: RUF001
        )


def _validate_dispatch_request(
    request: LogisticsRequest,
    *,
    warehouse_id: UUID,
    planning_date: date,
    latest_plan_id: UUID | None,
    unassigned_task_ids: set[UUID],
) -> None:
    """Validate one request against the command warehouse, date, and latest plan."""

    if request.warehouse_id != warehouse_id:
        raise ApiError(
            422,
            "CONTRACTOR_DISPATCH_WAREHOUSE_MISMATCH",
            "Выбранная заявка относится к другому складу.",
        )
    if request.scheduled_date != planning_date:
        raise ApiError(
            422,
            "CONTRACTOR_DISPATCH_DATE_MISMATCH",
            "Выбранная заявка относится к другой дате.",
        )
    if request.assignment_type is not None:
        raise ApiError(
            409,
            "REQUEST_ALREADY_ASSIGNED",
            "Заявка уже передана другому исполнителю.",
        )
    _validate_ready_request(request)
    if latest_plan_id is not None and any(
        task.id not in unassigned_task_ids for task in request.tasks
    ):
        raise ApiError(
            409,
            "REQUEST_NOT_UNASSIGNED",
            "Заявка уже включена в маршрут штатного водителя или изменилась после расчёта.",
        )


async def _require_contractor(
    client: RwmsPlanningClient,
    warehouse_id: UUID,
    worker_id: UUID,
    planned_at: datetime,
) -> RwmsDriverIdentity:
    """Resolve one active canonical contractor owned by the selected warehouse."""

    identities = await client.list_drivers(
        warehouse_id,
        at=planned_at,
        include_incoming=False,
    )
    contractor = next((item for item in identities if item.worker_id == worker_id), None)
    if (
        contractor is None
        or contractor.employment_type != "CONTRACTOR"
        or contractor.operational_warehouse_id != warehouse_id
        or contractor.phone is None
    ):
        raise ApiError(
            422,
            "CONTRACTOR_UNAVAILABLE",
            "Наёмный водитель неактивен или относится к другому складу.",
        )
    return contractor


async def _invalidate_request_plans(
    session: AsyncSession,
    request: LogisticsRequest,
) -> None:
    """Preserve the single-request command through the shared plan invalidation boundary."""

    await _invalidate_requests_plans(session, [request])


async def _invalidate_requests_plans(
    session: AsyncSession,
    requests: Sequence[LogisticsRequest],
) -> None:
    """Remove affected mutable plans together and protect every immutable plan."""

    task_ids = tuple(task.id for request in requests for task in request.tasks)
    if not task_ids:
        raise ApiError(
            422,
            "REQUEST_TASKS_REQUIRED",
            "У доставки отсутствуют задания для передачи водителю.",  # noqa: RUF001
        )
    assigned_rows = await session.execute(
        select(RoutePlan.id, RoutePlan.status)
        .join(RouteCycle, RouteCycle.route_plan_id == RoutePlan.id)
        .join(RouteStop, RouteStop.route_cycle_id == RouteCycle.id)
        .where(
            RouteStop.task_id.in_(task_ids),
            RoutePlan.status != PlanStatus.ARCHIVED,
        )
    )
    unassigned_rows = await session.execute(
        select(RoutePlan.id, RoutePlan.status)
        .join(UnassignedTask, UnassignedTask.route_plan_id == RoutePlan.id)
        .where(
            UnassignedTask.task_id.in_(task_ids),
            RoutePlan.status != PlanStatus.ARCHIVED,
        )
    )
    plans = {
        plan_id: PlanStatus(status)
        for plan_id, status in (*assigned_rows.all(), *unassigned_rows.all())
    }
    if any(status not in _MUTABLE_PLAN_STATUSES for status in plans.values()):
        raise ApiError(
            409,
            "REQUEST_PLAN_IMMUTABLE",
            "Сначала отмените утверждённый план, в который входит эта доставка.",
        )
    request_dates = {
        option_date
        for request in requests
        for option_date in (
            request.scheduled_date,
            *(option.date for option in request.date_options),
        )
        if option_date is not None
    }
    warehouse_ids = {request.warehouse_id for request in requests}
    dated_mutable_rows = await session.execute(
        select(RoutePlan.id, RoutePlan.status).where(
            RoutePlan.warehouse_id.in_(warehouse_ids),
            RoutePlan.date.in_(request_dates),
            RoutePlan.status.in_(tuple(_MUTABLE_PLAN_STATUSES)),
        )
    )
    plans.update(
        (plan_id, PlanStatus(status)) for plan_id, status in dated_mutable_rows.all()
    )
    if plans:
        await session.execute(delete(RoutePlan).where(RoutePlan.id.in_(tuple(plans))))
        await session.flush()


async def _apply_rwms_contractor_command(
    client: RwmsPlanningClient,
    command: RwmsAssignmentsCommand,
    idempotency_key: UUID,
) -> None:
    """Apply one idempotent RWMS command and expose only safe Russian domain errors."""

    result = await client.apply_assignments(
        command,
        idempotency_key=str(idempotency_key),
    )
    if result.rejected:
        raise ApiError(
            409,
            "CONTRACTOR_ASSIGNMENT_REJECTED",
            "RWMS не принял передачу заявок наёмному водителю.",
            extra={
                "rejections": [
                    {"order_id": str(item.order_id), "code": item.code}
                    for item in result.rejected
                ]
            },
        )
    if len(result.applied) != len(command.assignments):
        raise ApiError(
            502,
            "RWMS_APPLY_RESPONSE_MISMATCH",
            "RWMS вернул неполный результат передачи заявок.",
        )


def _persist_contractor_handoff(
    request: LogisticsRequest,
    contractor: RwmsDriverIdentity,
) -> None:
    """Record the vehicle-free contractor assignment on one request aggregate."""

    request.assignment_type = CONTRACTOR_HANDOFF
    request.assigned_contractor_worker_id = contractor.worker_id
    request.assigned_contractor_name = contractor.display_name
    request.assigned_contractor_phone = contractor.phone
    request.assigned_at = utc_now()
    request.assigned_by = CONTRACTOR_HANDOFF_ACTOR
    request.status = RequestStatus.PLANNED
    for task in request.tasks:
        task.status = TaskStatus.PLANNED


def _rwms_contractor_command(
    request: LogisticsRequest,
    warehouse: Warehouse,
    contractor: RwmsDriverIdentity,
) -> tuple[RwmsAssignmentsCommand, UUID]:
    """Build stable vehicle-free RWMS assignments for every persisted cabin slice."""

    assignments, source_version = _rwms_contractor_assignments(
        request,
        warehouse,
        contractor,
    )
    assert request.scheduled_date is not None
    assert warehouse.external_warehouse_id is not None
    command_identity = uuid5(
        _CONTRACTOR_COMMAND_NAMESPACE,
        (
            f"request:{request.id}:source-version:{source_version}:"
            f"date:{request.scheduled_date}:contractor:{contractor.worker_id}"
        ),
    )
    return (
        RwmsAssignmentsCommand(
            warehouse_id=warehouse.external_warehouse_id,
            plan_id=command_identity,
            plan_version=1,
            assignments=assignments,
            driver_shift_plans=[],
        ),
        command_identity,
    )


def _rwms_contractor_batch_command(
    requests: Sequence[LogisticsRequest],
    warehouse: Warehouse,
    contractor: RwmsDriverIdentity,
    planning_date: date,
) -> tuple[RwmsAssignmentsCommand, UUID]:
    """Build one stable RWMS command for a validated warehouse-day request set."""

    if warehouse.external_warehouse_id is None:
        raise ApiError(
            422,
            "RWMS_WAREHOUSE_NOT_LINKED",
            "Склад не связан с каталогом RWMS.",  # noqa: RUF001
        )
    assignments: list[RwmsPlanningAssignment] = []
    identities: list[str] = []
    for request in sorted(requests, key=lambda item: str(item.id)):
        request_assignments, source_version = _rwms_contractor_assignments(
            request,
            warehouse,
            contractor,
        )
        assignments.extend(request_assignments)
        identities.append(f"{request.id}:{source_version}")
    command_identity = uuid5(
        _CONTRACTOR_COMMAND_NAMESPACE,
        (
            f"warehouse:{warehouse.id}:date:{planning_date}:"
            f"contractor:{contractor.worker_id}:requests:{','.join(identities)}"
        ),
    )
    return (
        RwmsAssignmentsCommand(
            warehouse_id=warehouse.external_warehouse_id,
            plan_id=command_identity,
            plan_version=1,
            assignments=assignments,
            driver_shift_plans=[],
        ),
        command_identity,
    )


def _rwms_contractor_assignments(
    request: LogisticsRequest,
    warehouse: Warehouse,
    contractor: RwmsDriverIdentity,
) -> tuple[list[RwmsPlanningAssignment], int]:
    """Map one real delivery to concrete vehicle-free RWMS assignment slices."""

    if (
        request.external_id is None
        or request.external_version is None
        or request.external_payload is None
        or request.scheduled_date is None
        or warehouse.external_warehouse_id is None
    ):
        raise ApiError(
            422,
            "RWMS_REQUEST_MAPPING_MISSING",
            "У заявки отсутствуют данные, необходимые для передачи в RWMS.",  # noqa: RUF001
        )
    if request.type != RequestType.DELIVERY:
        raise ApiError(
            422,
            "RWMS_CONTRACTOR_PICKUP_UNSUPPORTED",
            "Этот вывоз пока нельзя передать наёмному водителю через RWMS.",
        )
    try:
        source = RwmsPlanningRequest.model_validate(request.external_payload)
    except ValidationError as exc:
        raise ApiError(
            422,
            "RWMS_REQUEST_MAPPING_INVALID",
            "Сохранённые данные доставки не соответствуют контракту RWMS.",
        ) from exc
    if (
        source.order_id != request.external_id
        or source.order_version != request.external_version
        or request.scheduled_date not in {option.date for option in source.date_options}
    ):
        raise ApiError(
            422,
            "RWMS_REQUEST_MAPPING_INVALID",
            "Сохранённые идентификаторы или дата доставки не совпадают с RWMS.",  # noqa: RUF001
        )

    assignments: list[RwmsPlanningAssignment] = []
    offset = 0
    for task in sorted(request.tasks, key=lambda item: (item.part_number, str(item.id))):
        next_offset = offset + task.quantity
        unit_ids = source.unit_ids[offset:next_offset]
        if task.type != RequestType.DELIVERY or len(unit_ids) != task.quantity:
            raise ApiError(
                422,
                "RWMS_UNIT_MAPPING_INVALID",
                "Состав заданий не совпадает с конкретными бытовками заказа RWMS.",  # noqa: RUF001
            )
        assignments.append(
            RwmsPlanningAssignment(
                order_id=source.order_id,
                service_warehouse_id=warehouse.external_warehouse_id,
                expected_order_version=source.order_version,
                scheduled_date=request.scheduled_date,
                assignment_type=CONTRACTOR_HANDOFF,
                driver_audience_mode="ASSIGNED_DRIVER",
                driver_worker_id=contractor.worker_id,
                driver_name=contractor.display_name,
                unit_ids=unit_ids,
            )
        )
        offset = next_offset
    if offset != len(source.unit_ids):
        raise ApiError(
            422,
            "RWMS_UNIT_MAPPING_INVALID",
            "Задания доставки покрывают не все бытовки заказа RWMS.",
        )
    return assignments, source.order_version
