"""Durable contractor handoff without internal vehicle, shift, or cycle planning."""

from __future__ import annotations

import re
from collections import Counter, defaultdict
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from uuid import UUID, uuid5
from zoneinfo import ZoneInfo

from pydantic import ValidationError
from sqlalchemy import delete, or_, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.db import utc_now
from app.errors import ApiError, not_found
from app.integrations.rwms import RwmsPlanningClient
from app.models import (
    ContractorHandoffCommand,
    LogisticsRequest,
    PlanningTask,
    RouteCycle,
    RoutePlan,
    RouteStop,
    UnassignedTask,
    Warehouse,
)
from app.models.domain import (
    ContractorHandoffStatus,
    CustomerDeliveryPurpose,
    PlanStatus,
    RequestStatus,
    RequestType,
    TaskStatus,
)
from app.schemas.domain import (
    ContractorAssignmentCreate,
    ContractorDispatchCreate,
    ContractorDispatchRead,
    RwmsAppliedAssignment,
    RwmsAssignmentsCommand,
    RwmsDriverIdentity,
    RwmsPlanningAssignment,
    RwmsPlanningRequest,
)
from app.services.catalog import RWMS_SOURCE_SYSTEM
from app.services.request_reschedule_fence import (
    fence_plan_request_reschedules,
    reject_active_request_reschedules,
)

CONTRACTOR_HANDOFF = "CONTRACTOR_HANDOFF"
_CONTRACTOR_COMMAND_NAMESPACE = UUID("d7724ad2-e9e7-49db-bc5f-cefd664448c7")
_SAFE_CODE = re.compile(r"^[A-Z][A-Z0-9_]{0,127}$")
_HANDOFF_LEASE_SECONDS = 60
_MAX_HANDOFF_BACKOFF_SECONDS = 300
_PARTIAL_RESULT_CODE = "RWMS_PARTIAL_ASSIGNMENT_RESULT"
_MUTABLE_PLAN_STATUSES = {
    PlanStatus.DRAFT,
    PlanStatus.GENERATED,
    PlanStatus.VALIDATED,
}


@dataclass(frozen=True, slots=True)
class ClaimedContractorHandoff:
    """Immutable command snapshot safe to carry across the transaction-free HTTP call."""

    command_id: UUID
    payload: RwmsAssignmentsCommand


def _pending_error() -> ApiError:
    """Return the retryable API outcome used while the durable command is unfinished."""

    return ApiError(
        503,
        "CONTRACTOR_HANDOFF_PENDING",
        "Передача сохранена и будет повторена автоматически. Назначение ещё не завершено.",
        headers={"Retry-After": "5"},
    )


async def dispatch_requests_to_contractor(
    session: AsyncSession,
    warehouse_id: UUID,
    payload: ContractorDispatchCreate,
    client: RwmsPlanningClient,
    *,
    assigned_by: str,
) -> ContractorDispatchRead:
    """Hand one warehouse-day selection over a durable transaction-free RWMS boundary."""

    active_command_id: UUID | None = None
    if payload.mode == "AUTO":
        active_command_id = await session.scalar(
            select(ContractorHandoffCommand.id)
            .where(
                ContractorHandoffCommand.warehouse_id == warehouse_id,
                ContractorHandoffCommand.planning_date == payload.planning_date,
                ContractorHandoffCommand.contractor_worker_id
                == payload.contractor_worker_id,
                ContractorHandoffCommand.mode == payload.mode,
                ContractorHandoffCommand.status.in_(
                    (
                        ContractorHandoffStatus.PENDING,
                        ContractorHandoffStatus.APPLYING,
                    )
                ),
            )
            .order_by(ContractorHandoffCommand.created_at.desc())
            .limit(1)
        )
    if active_command_id is not None:
        await session.commit()
        await resume_contractor_handoff(
            session,
            active_command_id,
            client,
            force=True,
        )
        return await _dispatch_read_for_command(session, active_command_id)

    warehouse = await session.get(Warehouse, warehouse_id)
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
    request_ids = list(payload.request_ids)
    if payload.mode == "AUTO":
        request_ids = await _automatic_request_ids(
            session,
            warehouse_id,
            payload.planning_date,
            latest_plan_id,
        )
    ordered_requests = await _validated_dispatch_selection(
        session,
        request_ids,
        warehouse_id=warehouse_id,
        planning_date=payload.planning_date,
        latest_plan_id=latest_plan_id,
        unassigned_task_ids=unassigned_task_ids,
        contractor_worker_id=payload.contractor_worker_id,
        auto_mode=payload.mode == "AUTO",
        for_update=False,
    )
    replayed = [request for request in ordered_requests if request.assignment_type is not None]
    pending = [request for request in ordered_requests if request.assignment_type is None]
    if not pending:
        return _dispatch_read(
            payload,
            replayed,
            contractor_name=_assigned_contractor_name(replayed),
        )

    planned_at = datetime.combine(
        payload.planning_date,
        warehouse.working_day_start,
        tzinfo=ZoneInfo(warehouse.timezone),
    )
    external_warehouse_id = warehouse.external_warehouse_id
    await session.commit()
    contractor = await _require_contractor(
        client,
        external_warehouse_id,
        payload.contractor_worker_id,
        planned_at,
    )

    warehouse = await session.scalar(
        select(Warehouse).where(Warehouse.id == warehouse_id).with_for_update()
    )
    if warehouse is None:
        raise not_found("warehouse", warehouse_id)
    latest_plan_id, unassigned_task_ids = await _latest_plan_unassigned_tasks(
        session,
        warehouse_id,
        payload.planning_date,
    )
    ordered_requests = await _validated_dispatch_selection(
        session,
        request_ids,
        warehouse_id=warehouse_id,
        planning_date=payload.planning_date,
        latest_plan_id=latest_plan_id,
        unassigned_task_ids=unassigned_task_ids,
        contractor_worker_id=payload.contractor_worker_id,
        auto_mode=payload.mode == "AUTO",
        for_update=True,
    )
    replayed = [request for request in ordered_requests if request.assignment_type is not None]
    pending = [request for request in ordered_requests if request.assignment_type is None]
    existing_command_ids = {
        request.contractor_handoff_command_id
        for request in pending
        if request.contractor_handoff_command_id is not None
    }
    if existing_command_ids:
        if len(existing_command_ids) != 1:
            raise ApiError(
                409,
                "CONTRACTOR_HANDOFF_IN_PROGRESS",
                "Выбранные заявки уже входят в разные незавершённые передачи.",
            )
        existing_command_id = existing_command_ids.pop()
        assert existing_command_id is not None
        await session.commit()
        await resume_contractor_handoff(session, existing_command_id, client, force=True)
        command_read = await _dispatch_read_for_command(session, existing_command_id)
        combined_ids = list(
            dict.fromkeys(
                [request.id for request in replayed] + command_read.assigned_request_ids
            )
        )
        return command_read.model_copy(
            update={
                "assigned_request_ids": combined_ids,
                "assigned_count": len(combined_ids),
            }
        )
    if not pending:
        return _dispatch_read(
            payload,
            replayed,
            contractor_name=_assigned_contractor_name(replayed),
        )

    real_requests = [
        request for request in pending if request.source_system == RWMS_SOURCE_SYSTEM
    ]
    if not real_requests:
        await _finalize_local_handoff(session, pending, contractor, assigned_by)
        return _dispatch_read(
            payload,
            [*replayed, *pending],
            contractor_name=contractor.display_name,
        )

    command, command_id = _rwms_contractor_batch_command(
        real_requests,
        pending,
        warehouse,
        contractor,
        payload.planning_date,
    )
    claimed = await _stage_contractor_handoff(
        session,
        command_id=command_id,
        command=command,
        warehouse=warehouse,
        planning_date=payload.planning_date,
        mode=payload.mode,
        requests=pending,
        contractor=contractor,
        assigned_by=assigned_by,
    )
    await session.commit()
    await apply_claimed_contractor_handoff(session, claimed, client)
    command_read = await _dispatch_read_for_command(session, command_id)
    combined_ids = list(
        dict.fromkeys([request.id for request in replayed] + command_read.assigned_request_ids)
    )
    return command_read.model_copy(
        update={
            "assigned_request_ids": combined_ids,
            "assigned_count": len(combined_ids),
        }
    )


async def assign_request_to_contractor(
    session: AsyncSession,
    request_id: UUID,
    payload: ContractorAssignmentCreate,
    client: RwmsPlanningClient,
    *,
    assigned_by: str,
) -> LogisticsRequest:
    """Validate one request, durably stage RWMS work, and finalize only after apply."""

    request = await _load_request_for_handoff(session, request_id, for_update=False)
    _validate_rwms_contractor_support(request)
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
    if request.contractor_handoff_command_id is not None:
        command_id = request.contractor_handoff_command_id
        existing_command = await session.get(ContractorHandoffCommand, command_id)
        if (
            existing_command is None
            or existing_command.contractor_worker_id != payload.contractor_worker_id
        ):
            raise ApiError(
                409,
                "CONTRACTOR_HANDOFF_IN_PROGRESS",
                "Заявка уже входит в другую незавершённую передачу.",
            )
        await session.commit()
        await resume_contractor_handoff(session, command_id, client, force=True)
        return await _load_request_for_handoff(session, request_id, for_update=False)
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
    external_warehouse_id = warehouse.external_warehouse_id
    await session.commit()
    contractor = await _require_contractor(
        client,
        external_warehouse_id,
        payload.contractor_worker_id,
        planned_at,
    )
    request = await _load_request_for_handoff(session, request_id, for_update=True)
    _validate_rwms_contractor_support(request)
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
    if request.contractor_handoff_command_id is not None:
        command_id = request.contractor_handoff_command_id
        command_state = await session.get(ContractorHandoffCommand, command_id)
        if command_state is None or command_state.contractor_worker_id != contractor.worker_id:
            raise ApiError(
                409,
                "CONTRACTOR_HANDOFF_IN_PROGRESS",
                "Заявка уже входит в другую незавершённую передачу.",
            )
        await session.commit()
        await resume_contractor_handoff(session, command_id, client, force=True)
        return await _load_request_for_handoff(session, request_id, for_update=False)
    _validate_ready_request(request)
    if request.source_system != RWMS_SOURCE_SYSTEM:
        await _finalize_local_handoff(session, [request], contractor, assigned_by)
        return request

    warehouse = request.warehouse
    command, command_id = _rwms_contractor_command(request, warehouse, contractor)
    assert request.scheduled_date is not None
    claimed = await _stage_contractor_handoff(
        session,
        command_id=command_id,
        command=command,
        warehouse=warehouse,
        planning_date=request.scheduled_date,
        mode="SINGLE",
        requests=[request],
        contractor=contractor,
        assigned_by=assigned_by,
    )
    await session.commit()
    await apply_claimed_contractor_handoff(session, claimed, client)
    return await _load_request_for_handoff(session, request_id, for_update=False)


async def _load_request_for_handoff(
    session: AsyncSession,
    request_id: UUID,
    *,
    for_update: bool,
) -> LogisticsRequest:
    """Load the complete request graph and optionally serialize a command transition."""

    statement = (
        select(LogisticsRequest)
        .where(LogisticsRequest.id == request_id)
        .options(
            selectinload(LogisticsRequest.warehouse),
            selectinload(LogisticsRequest.date_options),
            selectinload(LogisticsRequest.tasks),
        )
    )
    if for_update:
        statement = statement.with_for_update()
    request = await session.scalar(statement)
    if request is None:
        raise not_found("request", request_id)
    return request


async def _load_requests_for_handoff(
    session: AsyncSession,
    request_ids: Sequence[UUID],
    *,
    for_update: bool,
) -> dict[UUID, LogisticsRequest]:
    """Load a bounded request set and optionally lock it in deterministic order."""

    statement = (
        select(LogisticsRequest)
        .where(LogisticsRequest.id.in_(tuple(request_ids)))
        .options(
            selectinload(LogisticsRequest.warehouse),
            selectinload(LogisticsRequest.date_options),
            selectinload(LogisticsRequest.tasks),
        )
        .order_by(LogisticsRequest.id)
    )
    if for_update:
        statement = statement.with_for_update()
    rows = await session.scalars(statement)
    return {request.id: request for request in rows.unique().all()}


async def _validated_dispatch_selection(
    session: AsyncSession,
    request_ids: Sequence[UUID],
    *,
    warehouse_id: UUID,
    planning_date: date,
    latest_plan_id: UUID | None,
    unassigned_task_ids: set[UUID],
    contractor_worker_id: UUID,
    auto_mode: bool,
    for_update: bool,
) -> list[LogisticsRequest]:
    """Load and validate one dispatcher selection without accepting stale client facts."""

    if not request_ids:
        raise ApiError(
            409,
            "NO_UNASSIGNED_REQUESTS",
            "На выбранную дату нет заявок, которые можно передать наёмному водителю.",  # noqa: RUF001
        )
    requests = await _load_requests_for_handoff(
        session,
        request_ids,
        for_update=for_update,
    )
    if len(requests) != len(request_ids):
        raise ApiError(
            422,
            "CONTRACTOR_DISPATCH_REQUESTS_INVALID",
            "Одна или несколько выбранных заявок больше недоступны.",
        )
    ordered = [requests[request_id] for request_id in request_ids]
    if auto_mode and latest_plan_id is not None:
        ordered = [
            request
            for request in ordered
            if request.tasks and all(task.id in unassigned_task_ids for task in request.tasks)
        ]
        if not ordered:
            raise ApiError(
                409,
                "NO_UNASSIGNED_REQUESTS",
                "На выбранную дату нет заявок, которые можно передать наёмному водителю.",  # noqa: RUF001
            )
    for request in ordered:
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
        _validate_rwms_contractor_support(request)
        if request.assignment_type is not None:
            if (
                request.assignment_type == CONTRACTOR_HANDOFF
                and request.assigned_contractor_worker_id == contractor_worker_id
            ):
                continue
            raise ApiError(
                409,
                "REQUEST_ALREADY_ASSIGNED",
                "Заявка уже передана другому исполнителю.",
            )
        if request.contractor_handoff_command_id is not None:
            command = await session.get(
                ContractorHandoffCommand,
                request.contractor_handoff_command_id,
            )
            if command is None or command.contractor_worker_id != contractor_worker_id:
                raise ApiError(
                    409,
                    "CONTRACTOR_HANDOFF_IN_PROGRESS",
                    "Заявка уже входит в другую незавершённую передачу.",
                )
            continue
        _validate_dispatch_request(
            request,
            warehouse_id=warehouse_id,
            planning_date=planning_date,
            latest_plan_id=latest_plan_id,
            unassigned_task_ids=unassigned_task_ids,
        )
    return ordered


def _assigned_contractor_name(requests: Sequence[LogisticsRequest]) -> str:
    """Return the validated display name shared by replayed request assignments."""

    if not requests or requests[0].assigned_contractor_name is None:
        raise RuntimeError("completed contractor handoff is missing its contractor snapshot")
    return requests[0].assigned_contractor_name


def _dispatch_read(
    payload: ContractorDispatchCreate,
    requests: Sequence[LogisticsRequest],
    *,
    contractor_name: str,
) -> ContractorDispatchRead:
    """Serialize only a fully finalized dispatcher handoff."""

    command_id, external_task_ids = _request_handoff_identities(requests)
    return ContractorDispatchRead(
        contractor_worker_id=payload.contractor_worker_id,
        contractor_name=contractor_name,
        planning_date=payload.planning_date,
        mode=payload.mode,
        assigned_request_ids=[request.id for request in requests],
        assigned_count=len(requests),
        contractor_handoff_command_id=command_id,
        external_task_ids=external_task_ids,
    )


async def _dispatch_read_for_command(
    session: AsyncSession,
    command_id: UUID,
) -> ContractorDispatchRead:
    """Reload one terminal batch command and prove every reserved request was finalized."""

    command = await session.get(ContractorHandoffCommand, command_id)
    if command is None:
        raise RuntimeError("contractor handoff command disappeared")
    if command.status == ContractorHandoffStatus.REJECTED:
        raise _rejected_error(command)
    if command.status != ContractorHandoffStatus.SUCCEEDED:
        raise _pending_error()
    request_ids = [UUID(value) for value in command.request_ids]
    requests = await _load_requests_for_handoff(session, request_ids, for_update=False)
    if len(requests) != len(request_ids) or any(
        requests[request_id].assignment_type != CONTRACTOR_HANDOFF
        for request_id in request_ids
    ):
        raise RuntimeError("completed contractor command has incomplete local assignments")
    if command.mode not in {"AUTO", "MANUAL"}:
        raise RuntimeError("single-request command cannot be serialized as a dispatch")
    command_identity, external_task_ids = _request_handoff_identities(
        [requests[request_id] for request_id in request_ids]
    )
    if command_identity != command.id:
        raise RuntimeError("completed contractor command lost its stable request identity")
    return ContractorDispatchRead(
        contractor_worker_id=command.contractor_worker_id,
        contractor_name=command.contractor_name,
        planning_date=command.planning_date,
        mode=command.mode,
        assigned_request_ids=request_ids,
        assigned_count=len(request_ids),
        contractor_handoff_command_id=command.id,
        external_task_ids=external_task_ids,
    )


def _request_handoff_identities(
    requests: Sequence[LogisticsRequest],
) -> tuple[UUID | None, list[UUID]]:
    """Return stable command and ordered exact task identities from persisted requests."""

    command_ids = [request.contractor_handoff_command_id for request in requests]
    if all(value is None for value in command_ids):
        if any(
            request.contractor_handoff_sequence is not None
            or request.contractor_external_task_ids
            for request in requests
        ):
            raise RuntimeError("local contractor handoff has remote task identity")
        return None, []
    command_id = command_ids[0]
    if command_id is None or any(value != command_id for value in command_ids):
        raise RuntimeError("contractor handoff requests belong to mixed commands")
    sequences = [request.contractor_handoff_sequence for request in requests]
    if any(value is None for value in sequences):
        raise RuntimeError("contractor handoff request sequence is missing")
    exact_sequences = [int(value) for value in sequences if value is not None]
    if len(exact_sequences) != len(set(exact_sequences)):
        raise RuntimeError("contractor handoff request sequence is duplicated")
    if sorted(exact_sequences) != list(range(len(requests))):
        raise RuntimeError("contractor handoff request sequence is not contiguous")
    ordered_requests = [
        request
        for _, request in sorted(
            zip(exact_sequences, requests, strict=True),
            key=lambda item: item[0],
        )
    ]
    task_ids: list[UUID] = []
    for request in ordered_requests:
        try:
            task_ids.extend(UUID(value) for value in request.contractor_external_task_ids)
        except (TypeError, ValueError) as exc:
            raise RuntimeError("persisted contractor external task identity is invalid") from exc
    if len(task_ids) != len(set(task_ids)):
        raise RuntimeError("persisted contractor external task identities are duplicated")
    return command_id, task_ids


async def _finalize_local_handoff(
    session: AsyncSession,
    requests: Sequence[LogisticsRequest],
    contractor: RwmsDriverIdentity,
    assigned_by: str,
) -> None:
    """Atomically apply a generated-only handoff with no remote RWMS effect."""

    await _invalidate_requests_plans(session, requests)
    for request in requests:
        _persist_contractor_handoff(
            request,
            contractor,
            assigned_by,
            external_task_ids=(),
            handoff_sequence=None,
        )
    await session.flush()


async def _stage_contractor_handoff(
    session: AsyncSession,
    *,
    command_id: UUID,
    command: RwmsAssignmentsCommand,
    warehouse: Warehouse,
    planning_date: date,
    mode: str,
    requests: Sequence[LogisticsRequest],
    contractor: RwmsDriverIdentity,
    assigned_by: str,
) -> ClaimedContractorHandoff:
    """Persist an immutable intent and temporary reservation before any RWMS apply call."""

    if not assigned_by.strip():
        raise RuntimeError("authenticated assignment actor must not be blank")
    await reject_active_request_reschedules(
        session,
        tuple(request.id for request in requests),
    )
    await _validate_request_plan_mutability(session, requests)
    ordered_requests = sorted(requests, key=lambda request: str(request.id))
    request_ids = [str(request.id) for request in ordered_requests]
    existing = await session.get(ContractorHandoffCommand, command_id)
    if existing is not None:
        if (
            existing.contractor_worker_id != contractor.worker_id
            or existing.request_ids != request_ids
        ):
            raise ApiError(
                409,
                "CONTRACTOR_HANDOFF_COMMAND_CONFLICT",
                "Состав передачи изменился. Обновите данные и повторите действие.",
            )
        if existing.status == ContractorHandoffStatus.REJECTED:
            raise _rejected_error(existing)
        if existing.status == ContractorHandoffStatus.SUCCEEDED:
            raise RuntimeError("completed contractor command lost its request assignment")
        raise _pending_error()
    now = utc_now()
    entity = ContractorHandoffCommand(
        id=command_id,
        warehouse_id=warehouse.id,
        external_warehouse_id=warehouse.external_warehouse_id,
        planning_date=planning_date,
        mode=mode,
        request_ids=request_ids,
        command_payload=command.model_dump(mode="json", by_alias=True),
        contractor_worker_id=contractor.worker_id,
        contractor_name=contractor.display_name,
        contractor_phone=contractor.phone,
        assigned_by=assigned_by,
        status=ContractorHandoffStatus.APPLYING,
        attempts=1,
        next_attempt_at=None,
        lease_until=now + timedelta(seconds=_HANDOFF_LEASE_SECONDS),
        error_code=None,
        rejection_codes=[],
        completed_at=None,
    )
    session.add(entity)
    for request in ordered_requests:
        request.contractor_handoff_command_id = command_id
        request.status = RequestStatus.DRAFT
    await session.flush()
    return _claimed_snapshot(entity)


def _claimed_snapshot(command: ContractorHandoffCommand) -> ClaimedContractorHandoff:
    """Validate and detach one immutable persisted command from its transaction."""

    try:
        payload = RwmsAssignmentsCommand.model_validate(command.command_payload)
    except ValidationError as exc:
        raise RuntimeError("persisted contractor command is invalid") from exc
    return ClaimedContractorHandoff(command_id=command.id, payload=payload)


async def claim_due_contractor_handoffs(
    session: AsyncSession,
    *,
    limit: int = 10,
) -> tuple[ClaimedContractorHandoff, ...]:
    """Lease a bounded due batch with skip-locked multi-instance fencing."""

    now = utc_now()
    commands = list(
        await session.scalars(
            select(ContractorHandoffCommand)
            .where(
                or_(
                    (
                        ContractorHandoffCommand.status
                        == ContractorHandoffStatus.PENDING
                    )
                    & or_(
                        ContractorHandoffCommand.next_attempt_at.is_(None),
                        ContractorHandoffCommand.next_attempt_at <= now,
                    ),
                    (
                        ContractorHandoffCommand.status
                        == ContractorHandoffStatus.APPLYING
                    )
                    & (ContractorHandoffCommand.lease_until <= now),
                )
            )
            .order_by(
                ContractorHandoffCommand.next_attempt_at,
                ContractorHandoffCommand.created_at,
                ContractorHandoffCommand.id,
            )
            .with_for_update(skip_locked=True)
            .limit(limit)
        )
    )
    lease_until = now + timedelta(seconds=_HANDOFF_LEASE_SECONDS)
    for command in commands:
        command.status = ContractorHandoffStatus.APPLYING
        command.attempts += 1
        command.next_attempt_at = None
        command.lease_until = lease_until
    await session.flush()
    return tuple(_claimed_snapshot(command) for command in commands)


async def resume_contractor_handoff(
    session: AsyncSession,
    command_id: UUID,
    client: RwmsPlanningClient,
    *,
    force: bool,
) -> None:
    """Claim an existing command when possible and replay its stable upstream effect."""

    command = await session.scalar(
        select(ContractorHandoffCommand)
        .where(ContractorHandoffCommand.id == command_id)
        .with_for_update()
    )
    if command is None:
        raise RuntimeError("contractor handoff command disappeared")
    if command.status == ContractorHandoffStatus.SUCCEEDED:
        await session.commit()
        return
    if command.status == ContractorHandoffStatus.REJECTED:
        error = _rejected_error(command)
        await session.commit()
        raise error
    now = utc_now()
    if (
        command.status == ContractorHandoffStatus.APPLYING
        and command.lease_until is not None
        and command.lease_until > now
    ):
        await session.commit()
        raise _pending_error()
    if (
        not force
        and command.next_attempt_at is not None
        and command.next_attempt_at > now
    ):
        await session.commit()
        raise _pending_error()
    command.status = ContractorHandoffStatus.APPLYING
    command.attempts += 1
    command.next_attempt_at = None
    command.lease_until = now + timedelta(seconds=_HANDOFF_LEASE_SECONDS)
    claimed = _claimed_snapshot(command)
    await session.commit()
    await apply_claimed_contractor_handoff(session, claimed, client)


async def apply_claimed_contractor_handoff(
    session: AsyncSession,
    claimed: ClaimedContractorHandoff,
    client: RwmsPlanningClient,
) -> None:
    """Apply one leased command outside a transaction and persist its exact outcome."""

    try:
        result = await client.apply_assignments(
            claimed.payload,
            idempotency_key=str(claimed.command_id),
        )
        if result.rejected and (
            result.applied
            or await _handoff_has_partial_result(session, claimed.command_id)
        ):
            await _record_partial_handoff_result(
                session,
                claimed.command_id,
                [item.code for item in result.rejected],
            )
            raise _pending_error()
        if result.rejected:
            await _record_handoff_rejection(
                session,
                claimed.command_id,
                [item.code for item in result.rejected],
            )
            command = await session.get(ContractorHandoffCommand, claimed.command_id)
            assert command is not None
            raise _rejected_error(command)
        try:
            _external_task_ids_by_order(claimed.payload, result.applied)
        except ApiError as exc:
            if result.applied:
                await _record_partial_handoff_result(
                    session,
                    claimed.command_id,
                    ["RWMS_APPLY_RESPONSE_MISMATCH"],
                )
                raise _pending_error() from exc
            raise
        await _finalize_handoff_command(
            session,
            claimed.command_id,
            result.applied,
        )
    except ApiError as exc:
        if exc.code in {
            "CONTRACTOR_ASSIGNMENT_REJECTED",
            "CONTRACTOR_HANDOFF_PENDING",
        }:
            raise
        await session.rollback()
        await _record_handoff_failure(session, claimed.command_id, exc)
        raise _pending_error() from exc
    except Exception as exc:
        await session.rollback()
        await _record_handoff_failure(session, claimed.command_id, exc)
        raise _pending_error() from exc


async def _record_handoff_failure(
    session: AsyncSession,
    command_id: UUID,
    exc: Exception,
) -> None:
    """Release a failed lease and schedule capped exponential retry without raw text."""

    command = await session.scalar(
        select(ContractorHandoffCommand)
        .where(ContractorHandoffCommand.id == command_id)
        .with_for_update()
    )
    if command is None or command.status in {
        ContractorHandoffStatus.SUCCEEDED,
        ContractorHandoffStatus.REJECTED,
    }:
        await session.commit()
        return
    delay_seconds = min(
        _MAX_HANDOFF_BACKOFF_SECONDS,
        5 * (2 ** min(max(command.attempts - 1, 0), 6)),
    )
    command.status = ContractorHandoffStatus.PENDING
    command.lease_until = None
    command.next_attempt_at = utc_now() + timedelta(seconds=delay_seconds)
    if command.error_code != _PARTIAL_RESULT_CODE:
        command.error_code = _handoff_error_code(exc)
    await session.commit()


async def _record_handoff_rejection(
    session: AsyncSession,
    command_id: UUID,
    rejection_codes: Sequence[str],
) -> None:
    """Persist a typed terminal rejection and release every local reservation."""

    command = await session.scalar(
        select(ContractorHandoffCommand)
        .where(ContractorHandoffCommand.id == command_id)
        .with_for_update()
    )
    if command is None:
        raise RuntimeError("contractor handoff command disappeared")
    if command.status == ContractorHandoffStatus.SUCCEEDED:
        await session.commit()
        return
    safe_codes = _safe_rejection_codes(rejection_codes)
    requests = await _command_requests(session, command_id, for_update=True)
    for request in requests:
        if request.assignment_type is None:
            request.contractor_handoff_command_id = None
            if request.status == RequestStatus.DRAFT:
                request.status = RequestStatus.READY
    command.status = ContractorHandoffStatus.REJECTED
    command.lease_until = None
    command.next_attempt_at = None
    command.error_code = "CONTRACTOR_ASSIGNMENT_REJECTED"
    command.rejection_codes = safe_codes
    command.completed_at = utc_now()
    await session.commit()


async def _record_partial_handoff_result(
    session: AsyncSession,
    command_id: UUID,
    rejection_codes: Sequence[str],
) -> None:
    """Keep a mixed upstream result retryable without undoing possibly applied effects."""

    command = await session.scalar(
        select(ContractorHandoffCommand)
        .where(ContractorHandoffCommand.id == command_id)
        .with_for_update()
    )
    if command is None:
        raise RuntimeError("contractor handoff command disappeared")
    if command.status in {
        ContractorHandoffStatus.SUCCEEDED,
        ContractorHandoffStatus.REJECTED,
    }:
        await session.commit()
        return
    delay_seconds = min(
        _MAX_HANDOFF_BACKOFF_SECONDS,
        5 * (2 ** min(max(command.attempts - 1, 0), 6)),
    )
    command.status = ContractorHandoffStatus.PENDING
    command.lease_until = None
    command.next_attempt_at = utc_now() + timedelta(seconds=delay_seconds)
    command.error_code = _PARTIAL_RESULT_CODE
    command.rejection_codes = _safe_rejection_codes(rejection_codes)
    await session.commit()


def _safe_rejection_codes(rejection_codes: Sequence[str]) -> list[str]:
    """Sanitize per-assignment rejection diagnostics without persisting raw messages."""

    return [
        code if _SAFE_CODE.fullmatch(code) else "RWMS_ASSIGNMENT_REJECTED"
        for code in rejection_codes
    ]


async def _handoff_has_partial_result(session: AsyncSession, command_id: UUID) -> bool:
    """Remember that an earlier response confirmed an irreversible partial effect."""

    error_code = await session.scalar(
        select(ContractorHandoffCommand.error_code).where(
            ContractorHandoffCommand.id == command_id
        )
    )
    return error_code == _PARTIAL_RESULT_CODE


async def _finalize_handoff_command(
    session: AsyncSession,
    command_id: UUID,
    applied: Sequence[RwmsAppliedAssignment],
) -> None:
    """Finalize local assignment and plan invalidation after confirmed upstream apply."""

    command = await session.scalar(
        select(ContractorHandoffCommand)
        .where(ContractorHandoffCommand.id == command_id)
        .with_for_update()
    )
    if command is None:
        raise RuntimeError("contractor handoff command disappeared")
    if command.status == ContractorHandoffStatus.SUCCEEDED:
        await session.commit()
        return
    if command.status == ContractorHandoffStatus.REJECTED:
        error = _rejected_error(command)
        await session.commit()
        raise error
    try:
        command_payload = RwmsAssignmentsCommand.model_validate(command.command_payload)
    except ValidationError as exc:
        raise RuntimeError("persisted contractor command is invalid") from exc
    external_task_ids_by_order = _external_task_ids_by_order(command_payload, applied)
    requests = await _command_requests(session, command_id, for_update=True)
    expected_ids = [UUID(value) for value in command.request_ids]
    if [request.id for request in requests] != expected_ids:
        raise RuntimeError("contractor handoff request reservation is incomplete")
    await _invalidate_requests_plans(session, requests)
    contractor = RwmsDriverIdentity(
        worker_id=command.contractor_worker_id,
        display_name=command.contractor_name,
        employment_type="CONTRACTOR",
        phone=command.contractor_phone,
        operational_warehouse_id=command.external_warehouse_id,
        available_from=None,
        available_until=None,
        availability_kind="HOME",
    )
    rwms_request_ids = {
        request.external_id
        for request in requests
        if request.source_system == RWMS_SOURCE_SYSTEM
    }
    if None in rwms_request_ids or rwms_request_ids != set(external_task_ids_by_order):
        raise RuntimeError("contractor handoff response does not map to reserved RWMS requests")
    for handoff_sequence, request in enumerate(requests):
        if request.contractor_handoff_command_id != command_id:
            raise RuntimeError("contractor handoff request reservation changed")
        request_external_task_ids = (
            external_task_ids_by_order[request.external_id]
            if request.source_system == RWMS_SOURCE_SYSTEM and request.external_id is not None
            else ()
        )
        if request.assignment_type is not None:
            if (
                request.assignment_type == CONTRACTOR_HANDOFF
                and request.assigned_contractor_worker_id == command.contractor_worker_id
            ):
                expected_task_ids = [
                    str(value)
                    for value in request_external_task_ids
                ]
                if request.contractor_external_task_ids != expected_task_ids:
                    raise RuntimeError("completed contractor task identities changed on replay")
                if request.contractor_handoff_sequence != handoff_sequence:
                    raise RuntimeError("completed contractor request sequence changed on replay")
                continue
            raise RuntimeError("contractor handoff request acquired another assignment")
        _persist_contractor_handoff(
            request,
            contractor,
            command.assigned_by,
            external_task_ids=request_external_task_ids,
            handoff_sequence=handoff_sequence,
        )
    command.status = ContractorHandoffStatus.SUCCEEDED
    command.lease_until = None
    command.next_attempt_at = None
    command.error_code = None
    command.rejection_codes = []
    command.completed_at = utc_now()
    await session.commit()


def _external_task_ids_by_order(
    command: RwmsAssignmentsCommand,
    applied: Sequence[RwmsAppliedAssignment],
) -> dict[UUID, tuple[UUID, ...]]:
    """Validate a complete upstream bijection and group exact task IDs deterministically.

    The canonical transport correlates applied rows only by order, not by the
    submitted assignment slice. Multiple tasks for one order therefore use UUID
    ordering so retries and reloads remain stable even when upstream reorders rows.
    """

    expected_orders = Counter(assignment.order_id for assignment in command.assignments)
    actual_orders = Counter(item.order_id for item in applied)
    task_ids = [item.external_task_id for item in applied]
    document_ids = [item.document_id for item in applied]
    if (
        expected_orders != actual_orders
        or len(applied) != len(command.assignments)
        or len(task_ids) != len(set(task_ids))
        or len(document_ids) != len(set(document_ids))
    ):
        raise ApiError(
            502,
            "RWMS_APPLY_RESPONSE_MISMATCH",
            "RWMS вернул несогласованный результат передачи заявок.",
        )
    grouped: defaultdict[UUID, list[UUID]] = defaultdict(list)
    for item in applied:
        grouped[item.order_id].append(item.external_task_id)
    return {
        order_id: tuple(sorted(grouped[order_id], key=str))
        for order_id in expected_orders
    }


async def _command_requests(
    session: AsyncSession,
    command_id: UUID,
    *,
    for_update: bool,
) -> list[LogisticsRequest]:
    """Load command requests in the immutable snapshot order."""

    command = await session.get(ContractorHandoffCommand, command_id)
    if command is None:
        raise RuntimeError("contractor handoff command disappeared")
    request_ids = [UUID(value) for value in command.request_ids]
    requests = await _load_requests_for_handoff(session, request_ids, for_update=for_update)
    return [requests[request_id] for request_id in request_ids if request_id in requests]


def _handoff_error_code(exc: Exception) -> str:
    """Reduce transient failures to a bounded operational code."""

    if isinstance(exc, ApiError) and _SAFE_CODE.fullmatch(exc.code):
        return exc.code
    return "RWMS_CONTRACTOR_HANDOFF_UNAVAILABLE"


def _rejected_error(command: ContractorHandoffCommand) -> ApiError:
    """Expose typed permanent rejection codes without leaking upstream messages."""

    return ApiError(
        409,
        "CONTRACTOR_ASSIGNMENT_REJECTED",
        "RWMS не принял передачу заявок наёмному водителю.",
        extra={"rejection_codes": list(command.rejection_codes)},
    )


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
        or_(
            LogisticsRequest.type == RequestType.DELIVERY,
            LogisticsRequest.source_system.is_(None),
            LogisticsRequest.source_system != RWMS_SOURCE_SYSTEM,
        ),
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


def _validate_rwms_contractor_support(request: LogisticsRequest) -> None:
    """Reject real pickups before replay, plan invalidation, or upstream effects."""

    if (
        request.source_system == RWMS_SOURCE_SYSTEM
        and request.type == RequestType.PICKUP
    ):
        raise ApiError(
            422,
            "RWMS_CONTRACTOR_PICKUP_UNSUPPORTED",
            "Вывоз RWMS пока нельзя передать наёмному водителю: назначьте внутренний маршрут.",
        )


def _validate_ready_request(request: LogisticsRequest) -> None:
    """Validate request lifecycle and selected date before any assignment side effect."""

    if request.type not in (RequestType.DELIVERY, RequestType.PICKUP):
        raise ApiError(
            422,
            "CONTRACTOR_HANDOFF_REQUEST_TYPE_UNSUPPORTED",
            "Наёмному водителю можно передать только доставку или вывоз.",
        )
    _validate_rwms_contractor_support(request)
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


async def _request_plan_states(
    session: AsyncSession,
    requests: Sequence[LogisticsRequest],
) -> dict[UUID, PlanStatus]:
    """Return active directly referenced plans after enforcing immutable-plan fencing."""

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
    plans: dict[UUID, PlanStatus] = {
        plan_id: PlanStatus(status)
        for plan_id, status in (*assigned_rows.all(), *unassigned_rows.all())
    }
    if any(status not in _MUTABLE_PLAN_STATUSES for status in plans.values()):
        raise ApiError(
            409,
            "REQUEST_PLAN_IMMUTABLE",
            "Сначала отмените утверждённый план, в который входит эта доставка.",
        )
    return plans


async def _validate_request_plan_mutability(
    session: AsyncSession,
    requests: Sequence[LogisticsRequest],
) -> None:
    """Reject immutable plan ownership before a contractor command can leave the process."""

    await _request_plan_states(session, requests)


async def _invalidate_requests_plans(
    session: AsyncSession,
    requests: Sequence[LogisticsRequest],
) -> None:
    """Remove affected mutable plans together after confirmed contractor assignment."""

    plans = await _request_plan_states(session, requests)
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
        plan_ids = tuple(sorted(plans, key=str))
        locked_plan_ids = tuple(
            await session.scalars(
                select(RoutePlan.id)
                .where(RoutePlan.id.in_(plan_ids))
                .order_by(RoutePlan.id)
                .with_for_update()
            )
        )
        for plan_id in locked_plan_ids:
            await fence_plan_request_reschedules(session, plan_id)
        await session.execute(delete(RoutePlan).where(RoutePlan.id.in_(plan_ids)))
        await session.flush()


def _persist_contractor_handoff(
    request: LogisticsRequest,
    contractor: RwmsDriverIdentity,
    assigned_by: str,
    *,
    external_task_ids: Sequence[UUID],
    handoff_sequence: int | None,
) -> None:
    """Record the vehicle-free assignment and exact canonical task identities."""

    request.assignment_type = CONTRACTOR_HANDOFF
    request.assigned_contractor_worker_id = contractor.worker_id
    request.assigned_contractor_name = contractor.display_name
    request.assigned_contractor_phone = contractor.phone
    request.assigned_at = utc_now()
    request.assigned_by = assigned_by
    request.contractor_handoff_sequence = handoff_sequence
    request.contractor_external_task_ids = [str(value) for value in external_task_ids]
    request.status = RequestStatus.PLANNED
    for task in request.tasks:
        task.status = TaskStatus.PLANNED


def _rwms_contractor_command(
    request: LogisticsRequest,
    warehouse: Warehouse,
    contractor: RwmsDriverIdentity,
) -> tuple[RwmsAssignmentsCommand, UUID]:
    """Build stable vehicle-free RWMS assignments for every persisted cabin slice."""

    assignments, source_version, source_revision = _rwms_contractor_assignments(
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
            f"source-revision:{source_revision}:"
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
    real_requests: Sequence[LogisticsRequest],
    reserved_requests: Sequence[LogisticsRequest],
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
    for request in sorted(real_requests, key=lambda item: str(item.id)):
        request_assignments, source_version, source_revision = _rwms_contractor_assignments(
            request,
            warehouse,
            contractor,
        )
        assignments.extend(request_assignments)
        identities.append(f"rwms:{request.id}:{source_version}:{source_revision}")
    real_request_ids = {request.id for request in real_requests}
    identities.extend(
        f"local:{request.id}:{request.version}"
        for request in sorted(reserved_requests, key=lambda item: str(item.id))
        if request.id not in real_request_ids
    )
    identities.sort()
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
) -> tuple[list[RwmsPlanningAssignment], int, str]:
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
        or source.customer_delivery_purpose != request.customer_delivery_purpose
        or request.scheduled_date not in {option.date for option in source.date_options}
    ):
        raise ApiError(
            422,
            "RWMS_REQUEST_MAPPING_INVALID",
            "Сохранённые идентификаторы или дата доставки не совпадают с RWMS.",  # noqa: RUF001
        )
    if source.customer_delivery_purpose != CustomerDeliveryPurpose.RENTAL_DELIVERY:
        raise ApiError(
            422,
            "RWMS_DELIVERY_PURPOSE_UNSUPPORTED",
            "Этот тип клиентской доставки пока нельзя передать через арендный контур RWMS.",
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
        inventory_sources = source.inventory_sources_for(unit_ids)
        if len(inventory_sources) != 1:
            raise ApiError(
                422,
                "RWMS_MIXED_INVENTORY_SOURCE",
                "Одно задание наёмному водителю не может содержать бытовки с разных складов.",  # noqa: RUF001
            )
        assignments.append(
            RwmsPlanningAssignment(
                order_id=source.order_id,
                service_warehouse_id=warehouse.external_warehouse_id,
                inventory_source_warehouse_id=next(iter(inventory_sources)),
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
    return assignments, source.order_version, source.source_revision
