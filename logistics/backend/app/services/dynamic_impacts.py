"""Structured event impact analysis over existing plans and resources."""

# ruff: noqa: RUF001 -- Russian operator-facing messages are intentional.

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Any
from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.errors import ApiError, not_found
from app.integrations.rwms import RwmsPlanningClient
from app.integrations.rwms_sync import build_plan_status_task_index
from app.models import (
    LogisticsEvent,
    LogisticsEventType,
    LogisticsHumanAction,
    LogisticsNotice,
    LogisticsNoticeStatus,
    LogisticsRequest,
    PlanningTask,
    RecoveryProposal,
    RecoveryProposalStatus,
    RequestDateOption,
    RoutePlan,
    Trailer,
    Vehicle,
    Warehouse,
)
from app.models.domain import RequestStatus, TaskStatus
from app.schemas.operations import (
    LogisticsEventCreate,
    LogisticsEventResult,
)
from app.services import catalog
from app.services import plans as plan_service
from app.services.capacity_generation import advance_warehouse_capacity_generation
from app.services.capacity_publication_state import mark_capacity_publication_pending
from app.services.dynamic_projection import DynamicOperationsProjection
from app.services.dynamic_support import (
    OWNER_TERMINAL_TASK_STATES as _OWNER_TERMINAL_TASK_STATES,
)
from app.services.dynamic_support import (
    OWNER_UNSAFE_SUFFIX_STATES as _OWNER_UNSAFE_SUFFIX_STATES,
)
from app.services.dynamic_support import (
    PLAN_CHANGING_EVENT_TYPES as _PLAN_CHANGING_EVENT_TYPES,
)
from app.services.dynamic_support import (
    event_command_hash as _event_command_hash,
)
from app.services.planning_group import resolve_planning_warehouse_group
from app.services.plans import PlannerFacade


@dataclass(frozen=True, slots=True)
class _OwnerTaskState:
    """One exact owner task revision used to fence impact and later application."""

    task_state: str
    external_task_id: UUID
    task_version: int


class LogisticsImpactAnalyzer:
    """Structured event impact analysis over existing plans and resources."""

    def __init__(
        self,
        planner: PlannerFacade,
        rwms_client: RwmsPlanningClient | None,
        projection: DynamicOperationsProjection,
    ) -> None:
        """Bind impact analysis to existing routing and authoritative state adapters."""

        self._planner = planner
        self._rwms_client = rwms_client
        self._projection = projection

    async def register_event(
        self,
        session: AsyncSession,
        warehouse_id: UUID,
        planning_date: date,
        payload: LogisticsEventCreate,
        *,
        actor: str,
        idempotency_key: str,
    ) -> LogisticsEventResult:
        """Persist one fact, update resource state, and derive deterministic recovery work."""

        warehouse = await session.scalar(
            select(Warehouse).where(Warehouse.id == warehouse_id).with_for_update()
        )
        if warehouse is None:
            raise not_found("warehouse", warehouse_id)
        replay = await session.scalar(
            select(LogisticsEvent).where(
                LogisticsEvent.warehouse_id == warehouse_id,
                LogisticsEvent.idempotency_key == idempotency_key,
            )
        )
        if replay is not None:
            if replay.facts.get("command_hash") != _event_command_hash(payload):
                raise ApiError(409, "IDEMPOTENCY_KEY_REUSED", "Ключ повтора уже использован.")
            return await self._projection.event_result(session, replay)
        planning_group = await resolve_planning_warehouse_group(
            session,
            self._rwms_client,
            warehouse,
            planning_date=planning_date,
        )
        planning_root_id = planning_group.root.id
        if planning_root_id != warehouse_id:
            raise ApiError(
                422,
                "PLANNING_ROOT_REQUIRED",
                "Операционные события группового дня регистрируются через основной склад.",
                extra={"planning_root_warehouse_id": str(planning_root_id)},
            )
        effective_at = payload.effective_at or payload.occurred_at
        effective_day = effective_at.astimezone(ZoneInfo(planning_group.root.timezone)).date()
        if effective_day != planning_date:
            raise ApiError(
                422,
                "EVENT_EFFECTIVE_DAY_MISMATCH",
                "Время действия события относится к другому операционному дню склада.",
                extra={
                    "planning_date": planning_date.isoformat(),
                    "effective_local_date": effective_day.isoformat(),
                    "warehouse_timezone": planning_group.root.timezone,
                },
            )
        planning_member_ids = frozenset(member.id for member in planning_group.members)
        plan: RoutePlan | None
        if payload.plan_id is not None:
            plan = (
                await plan_service.lock_plan_for_request_mutation(session, payload.plan_id)
                if payload.event_type in _PLAN_CHANGING_EVENT_TYPES
                else await plan_service.get_plan(session, payload.plan_id)
            )
        else:
            plan = await plan_service.get_latest_plan_for_date(
                session,
                planning_root_id,
                planning_date,
            )
            if plan is not None and payload.event_type in _PLAN_CHANGING_EVENT_TYPES:
                plan = await plan_service.lock_plan_for_request_mutation(session, plan.id)
        if plan is not None:
            if plan.warehouse_id != planning_root_id or plan.date != planning_date:
                raise ApiError(
                    422, "EVENT_PLAN_MISMATCH", "План относится к другому складу или дню."
                )
            if (
                payload.event_type in _PLAN_CHANGING_EVENT_TYPES
                and payload.expected_plan_version is None
            ):
                raise ApiError(
                    409,
                    "PLAN_VERSION_REQUIRED",
                    "Для изменения активного плана требуется его актуальная версия.",
                    extra={"actual_plan_id": str(plan.id), "actual_version": plan.version},
                )
            if (
                payload.expected_plan_version is not None
                and plan.version != payload.expected_plan_version
            ):
                raise ApiError(
                    409,
                    "PLAN_VERSION_CONFLICT",
                    "План уже изменён другим пользователем.",
                    extra={
                        "expected_version": payload.expected_plan_version,
                        "actual_version": plan.version,
                    },
                )
            if payload.event_type in _PLAN_CHANGING_EVENT_TYPES:
                active_plan = await plan_service.get_latest_plan_for_date(
                    session, planning_root_id, planning_date
                )
                if active_plan is None or active_plan.id != plan.id:
                    raise ApiError(
                        409,
                        "PLAN_HEAD_CHANGED",
                        "Активная ревизия плана уже изменилась.",
                        extra={
                            "expected_plan_id": str(plan.id),
                            "actual_plan_id": (
                                str(active_plan.id) if active_plan is not None else None
                            ),
                        },
                    )
        facts: dict[str, Any] = {
            **payload.facts,
            "command_hash": _event_command_hash(payload),
            "expected_plan_version": payload.expected_plan_version,
            "reason": payload.reason,
            "vehicle_id": str(payload.vehicle_id) if payload.vehicle_id else None,
            "trailer_id": str(payload.trailer_id) if payload.trailer_id else None,
            "recovery_mode": payload.recovery_mode,
            "driver_shift_id": (str(payload.driver_shift_id) if payload.driver_shift_id else None),
            "effective_at": ((payload.effective_at or payload.occurred_at).isoformat()),
            "delay_minutes": payload.delay_minutes,
        }
        event = LogisticsEvent(
            warehouse_id=warehouse_id,
            day=planning_date,
            plan_id=plan.id if plan is not None else None,
            cycle_id=payload.cycle_id,
            request_id=payload.request_id,
            task_id=payload.task_id,
            event_type=payload.event_type,
            source_event=payload.source_event,
            idempotency_key=idempotency_key,
            occurred_at=payload.occurred_at,
            actor=actor,
            facts=facts,
        )
        session.add(event)
        await session.flush()
        if payload.event_type == LogisticsEventType.VEHICLE_DELAY:
            await self._analyze_delay(
                session,
                event,
                plan,
                payload,
                warehouse,
                planning_member_ids,
            )
        elif payload.event_type in {
            LogisticsEventType.VEHICLE_BREAKDOWN,
            LogisticsEventType.TRAILER_BREAKDOWN,
            LogisticsEventType.DRIVER_UNAVAILABLE,
        }:
            await self._analyze_resource_loss(
                session,
                event,
                plan,
                payload,
                warehouse,
                planning_member_ids,
            )
        elif payload.event_type in {
            LogisticsEventType.DELIVERY_CANCELLED,
            LogisticsEventType.PICKUP_CANCELLED,
            LogisticsEventType.ORDER_CANCELLED,
        }:
            await self._analyze_cancellation(
                session,
                event,
                plan,
                payload,
                warehouse,
                planning_member_ids,
            )
        elif payload.event_type == LogisticsEventType.TASK_BLOCKED:
            await self._analyze_task_blocked(session, event, plan, payload, warehouse)
        else:
            await self._create_generic_notice(session, event, payload.reason)
        await session.flush()
        return await self._projection.event_result(session, event)

    async def _owner_task_states_or_fail_closed(
        self,
        session: AsyncSession,
        event: LogisticsEvent,
        plan: RoutePlan,
        _warehouse: Warehouse,
    ) -> dict[UUID, _OwnerTaskState] | None:
        """Load every member warehouse's authoritative state or retain a safe action."""

        rwms_requests = {
            stop.task.request_id: stop.task.request
            for cycle in plan.cycles
            for stop in cycle.stops
            if stop.task is not None
            and stop.task.request.source_system == catalog.RWMS_SOURCE_SYSTEM
        }
        rwms_requests.update(
            {
                item.task.request_id: item.task.request
                for item in plan.unassigned_tasks
                if item.task.request.source_system == catalog.RWMS_SOURCE_SYSTEM
            }
        )
        if not rwms_requests:
            return {}
        try:
            if self._rwms_client is None:
                raise ApiError(
                    503,
                    "RWMS_ASSIGNMENT_STATUS_UNAVAILABLE",
                    "Состояние заданий владельца заказа временно недоступно.",
                )
            loaded_requests = tuple(
                await session.scalars(
                    select(LogisticsRequest)
                    .where(LogisticsRequest.id.in_(tuple(rwms_requests)))
                    .options(selectinload(LogisticsRequest.tasks))
                )
            )
            request_by_id = {request.id: request for request in loaded_requests}
            service_warehouse_ids = {request.warehouse_id for request in request_by_id.values()}
            service_warehouses = tuple(
                await session.scalars(
                    select(Warehouse).where(Warehouse.id.in_(service_warehouse_ids))
                )
            )
            warehouse_by_id = {item.id: item for item in service_warehouses}
            missing_warehouse_ids = service_warehouse_ids - warehouse_by_id.keys()
            if missing_warehouse_ids or any(
                item.external_warehouse_id is None for item in service_warehouses
            ):
                raise ApiError(
                    409,
                    "RWMS_WAREHOUSE_NOT_LINKED",
                    "Один из складов плана больше не связан с владельцем заданий.",
                    extra={
                        "missing_warehouse_ids": [
                            str(item) for item in sorted(missing_warehouse_ids, key=str)
                        ]
                    },
                )
            task_index = build_plan_status_task_index(plan)
            states: dict[UUID, _OwnerTaskState] = {}
            for service_warehouse in sorted(
                service_warehouses,
                key=lambda item: str(item.external_warehouse_id),
            ):
                external_warehouse_id = service_warehouse.external_warehouse_id
                if external_warehouse_id is None:  # guarded above for type narrowing
                    continue
                feed = await self._rwms_client.get_assignment_statuses(
                    warehouse_id=external_warehouse_id,
                    date=event.day,
                )
                if feed.warehouse_id != external_warehouse_id or feed.date != event.day:
                    raise ApiError(
                        502,
                        "RWMS_ASSIGNMENT_STATUS_SCOPE_MISMATCH",
                        "Владелец заказа вернул состояния другого склада или дня.",
                    )
                for status in feed.assignments:
                    local = task_index.get((status.order_id, tuple(status.unit_ids)))
                    if local is None:
                        continue
                    task, request = local
                    if request.warehouse_id != service_warehouse.id:
                        raise ApiError(
                            502,
                            "RWMS_ASSIGNMENT_STATUS_SCOPE_MISMATCH",
                            "Владелец заказа вернул задание другого склада группы.",
                        )
                    current = _OwnerTaskState(
                        task_state=status.task_state,
                        external_task_id=status.external_task_id,
                        task_version=status.task_version,
                    )
                    previous = states.get(task.id)
                    if previous is not None and previous != current:
                        raise ApiError(
                            502,
                            "RWMS_ASSIGNMENT_STATUS_CONFLICT",
                            "Владелец заказа вернул противоречивые состояния задания.",
                        )
                    states[task.id] = current
            expected_assigned_task_ids = {
                stop.task.id
                for cycle in plan.cycles
                for stop in cycle.stops
                if stop.task is not None
                and stop.task.request.source_system == catalog.RWMS_SOURCE_SYSTEM
                and stop.task.type == "DELIVERY"
            }
            missing_task_ids = expected_assigned_task_ids - states.keys()
            if missing_task_ids:
                raise ApiError(
                    502,
                    "RWMS_ASSIGNMENT_STATUS_INCOMPLETE",
                    "Владелец заказа не вернул состояние всех опубликованных заданий.",
                    extra={
                        "missing_task_ids": [
                            str(item) for item in sorted(missing_task_ids, key=str)
                        ]
                    },
                )
            return states
        except ApiError as exc:
            notice = LogisticsNotice(
                event_id=event.id,
                warehouse_id=event.warehouse_id,
                day=event.day,
                plan_id=plan.id,
                notice_type="AUTHORITATIVE_TASK_STATE_UNAVAILABLE",
                severity="ERROR",
                reason_codes=[exc.code, "RECOVERY_FAIL_CLOSED"],
                facts={"source_plan_id": str(plan.id)},
                message_ru=(
                    "Не удалось проверить фактическое выполнение заданий. "
                    "Автоматическое перепланирование не подготовлено."
                ),
                recommended_action_ru=(
                    "Обновите данные RWMS и повторите анализ; исходный план сохранён."
                ),
                requires_action=True,
                status=LogisticsNoticeStatus.REQUIRES_ACTION,
            )
            session.add(notice)
            await session.flush()
            session.add(
                LogisticsHumanAction(
                    notice_id=notice.id,
                    event_id=event.id,
                    warehouse_id=event.warehouse_id,
                    day=event.day,
                    action_type="REFRESH_AUTHORITATIVE_TASK_STATE",
                    context={"source_plan_id": str(plan.id), "reason_code": exc.code},
                )
            )
            return None

    @staticmethod
    def _execution_state(
        task: PlanningTask,
        owner_states: dict[UUID, _OwnerTaskState],
    ) -> str:
        """Prefer authoritative execution state and otherwise use the local lifecycle."""

        owner = owner_states.get(task.id)
        return owner.task_state if owner is not None else str(task.status)

    @staticmethod
    def _is_terminal_execution_state(state: str) -> bool:
        """Return whether a task is immutable terminal work for recovery purposes."""

        return state in _OWNER_TERMINAL_TASK_STATES or state in {
            TaskStatus.COMPLETED,
            TaskStatus.CANCELLED,
        }

    @staticmethod
    def _is_unsafe_suffix_state(state: str) -> bool:
        """Return whether the current planner cannot safely represent this active suffix."""

        return state in _OWNER_UNSAFE_SUFFIX_STATES or state == TaskStatus.IN_PROGRESS

    async def _create_suffix_fail_closed_work(
        self,
        session: AsyncSession,
        event: LogisticsEvent,
        plan: RoutePlan,
        *,
        task_ids: list[UUID],
        reason_code: str,
    ) -> None:
        """Explain why a partially executing cycle cannot be auto-rebuilt safely."""

        notice = LogisticsNotice(
            event_id=event.id,
            warehouse_id=event.warehouse_id,
            day=event.day,
            plan_id=plan.id,
            notice_type="PARTIAL_REPLAN_REQUIRES_MANUAL_CONTROL",
            severity="CRITICAL",
            reason_codes=[reason_code, "RECOVERY_FAIL_CLOSED"],
            facts={"active_task_ids": [str(item) for item in sorted(task_ids, key=str)]},
            message_ru=(
                "Рейс закреплён вручную за неисправным ресурсом. Автозамена остановлена."
                if reason_code == "FAILED_RESOURCE_CYCLE_LOCKED"
                else "Маршрут уже выполняется. Безопасно отделить выполненную и текущую часть "
                "от остатка автоматически нельзя."
            ),
            recommended_action_ru=(
                "Снимите закрепление рейса и повторите восстановление после проверки груза."
                if reason_code == "FAILED_RESOURCE_CYCLE_LOCKED"
                else "Зафиксируйте фактическое положение груза и исполнителя, "
                "затем измените остаток вручную."
            ),
            requires_action=True,
            status=LogisticsNoticeStatus.REQUIRES_ACTION,
        )
        session.add(notice)
        await session.flush()
        session.add(
            LogisticsHumanAction(
                notice_id=notice.id,
                event_id=event.id,
                warehouse_id=event.warehouse_id,
                day=event.day,
                action_type="RESOLVE_IN_PROGRESS_ROUTE",
                context={
                    "source_plan_id": str(plan.id),
                    "active_task_ids": [str(item) for item in sorted(task_ids, key=str)],
                },
            )
        )

    async def _analyze_delay(
        self,
        session: AsyncSession,
        event: LogisticsEvent,
        plan: RoutePlan | None,
        payload: LogisticsEventCreate,
        warehouse: Warehouse,
        planning_member_ids: frozenset[UUID],
    ) -> None:
        """Classify every unfinished customer stop after a persisted delay fact."""

        if plan is None or payload.vehicle_id is None or payload.delay_minutes is None:
            await self._create_generic_notice(session, event, "Для задержки нет активного плана.")
            return
        affected_cycles = [
            cycle for cycle in plan.cycles if cycle.driver_shift.vehicle_id == payload.vehicle_id
        ]
        if not affected_cycles:
            vehicle = await session.get(Vehicle, payload.vehicle_id)
            if vehicle is None or vehicle.warehouse_id not in planning_member_ids:
                raise not_found("vehicle", payload.vehicle_id)
            await self._create_generic_notice(
                session,
                event,
                "Задержка зарегистрирована; выбранный план не затронут.",
            )
            return
        owner_states = await self._owner_task_states_or_fail_closed(session, event, plan, warehouse)
        if owner_states is None:
            return
        effective_at = payload.effective_at or payload.occurred_at
        shifted_etas = await self._planner.preview_delay_task_etas(
            session,
            plan.id,
            payload.vehicle_id,
            effective_at,
            payload.delay_minutes,
        )
        classifications: list[dict[str, object]] = []
        late_requests: dict[UUID, LogisticsRequest] = {}
        unsafe_task_ids: list[UUID] = []
        risk_count = 0
        late_count = 0
        request_warehouse_ids = {
            stop.task.request.warehouse_id
            for cycle in affected_cycles
            for stop in cycle.stops
            if stop.task is not None
        }
        timezone_by_warehouse_id = {
            warehouse_id: timezone
            for warehouse_id, timezone in await session.execute(
                select(Warehouse.id, Warehouse.timezone).where(
                    Warehouse.id.in_(request_warehouse_ids)
                )
            )
        }
        for cycle in affected_cycles:
            for stop in cycle.stops:
                if stop.task is None:
                    continue
                execution_state = self._execution_state(stop.task, owner_states)
                if self._is_terminal_execution_state(execution_state):
                    continue
                if self._is_unsafe_suffix_state(execution_state):
                    unsafe_task_ids.append(stop.task.id)
                if stop.task.id not in shifted_etas:
                    continue
                request = stop.task.request
                zone = ZoneInfo(timezone_by_warehouse_id[request.warehouse_id])
                option = next(
                    (item for item in request.date_options if item.date == event.day), None
                )
                shifted_eta = shifted_etas[stop.task.id]
                classification = "IN_SLOT"
                window_start = None
                window_end = None
                late_minutes = 0
                if option is not None and option.window_end is not None:
                    window_start = (
                        datetime.combine(event.day, option.window_start, tzinfo=zone)
                        if option.window_start is not None
                        else None
                    )
                    window_end = datetime.combine(event.day, option.window_end, tzinfo=zone)
                    if shifted_eta > window_end:
                        classification = "LATE"
                        late_minutes = max(
                            1, round((shifted_eta - window_end).total_seconds() / 60)
                        )
                        late_count += 1
                        late_requests[request.id] = request
                    elif shifted_eta >= window_end - timedelta(minutes=30):
                        classification = "RISK"
                        risk_count += 1
                classifications.append(
                    {
                        "request_id": str(request.id),
                        "task_id": str(stop.task.id),
                        "execution_state": execution_state,
                        "classification": classification,
                        "previous_eta": stop.planned_arrival.isoformat(),
                        "new_eta": shifted_eta.isoformat(),
                        "window_start": (
                            window_start.isoformat() if window_start is not None else None
                        ),
                        "window_end": window_end.isoformat() if window_end is not None else None,
                        "late_minutes": late_minutes,
                    }
                )
        if unsafe_task_ids and not classifications:
            await self._create_suffix_fail_closed_work(
                session,
                event,
                plan,
                task_ids=unsafe_task_ids,
                reason_code="IN_PROGRESS_SUFFIX_REPLAN_UNSUPPORTED",
            )
            return
        if not classifications:
            await self._create_generic_notice(
                session,
                event,
                "Задержка зарегистрирована; после времени события незавершённых точек нет.",
            )
            return
        message = (
            f"Машина задерживается на {payload.delay_minutes} мин. "
            f"Риск: {risk_count}; гарантированное опоздание: {late_count}."
        )
        notice = LogisticsNotice(
            event_id=event.id,
            warehouse_id=event.warehouse_id,
            day=event.day,
            plan_id=plan.id,
            notice_type="VEHICLE_DELAY_IMPACT",
            severity="ERROR" if late_count else "WARNING" if risk_count else "INFO",
            reason_codes=[
                *(["ETA_LATE"] if late_count else []),
                *(["ETA_RISK"] if risk_count else []),
                *(["ETA_IN_SLOT"] if not late_count and not risk_count else []),
                *(
                    ["IN_PROGRESS_SUFFIX_REPLAN_UNSUPPORTED"]
                    if unsafe_task_ids and late_count
                    else []
                ),
            ],
            facts={
                "delay_minutes": payload.delay_minutes,
                "effective_at": effective_at.isoformat(),
                "tasks": classifications,
            },
            message_ru=message,
            recommended_action_ru=(
                "Предупредите клиентов по заданиям с опозданием."
                if late_count
                else "Контролируйте ETA; клиентские окна пока не нарушены."
            ),
            requires_action=bool(late_count),
            status=(
                LogisticsNoticeStatus.REQUIRES_ACTION
                if late_count
                else LogisticsNoticeStatus.COMPLETED
            ),
        )
        session.add(notice)
        await session.flush()
        contact_actions: list[LogisticsHumanAction] = []
        for request in late_requests.values():
            detail = next(
                item
                for item in classifications
                if item["request_id"] == str(request.id) and item["classification"] == "LATE"
            )
            contact_actions.append(
                LogisticsHumanAction(
                    notice_id=notice.id,
                    event_id=event.id,
                    warehouse_id=event.warehouse_id,
                    day=event.day,
                    request_id=request.id,
                    action_type="CONTACT_CUSTOMER_DELAY",
                    customer_name=request.contact_name or request.name,
                    customer_type=request.client_type,
                    customer_phone=request.contact_phone or None,
                    current_date=event.day,
                    context={
                        "task_id": detail["task_id"],
                        "delay_minutes": payload.delay_minutes,
                        "window_start": detail["window_start"],
                        "window_end": detail["window_end"],
                        "new_eta": detail["new_eta"],
                        "late_minutes": detail["late_minutes"],
                    },
                )
            )
        session.add_all(contact_actions)
        await session.flush()
        if unsafe_task_ids and late_count:
            await self._create_suffix_fail_closed_work(
                session,
                event,
                plan,
                task_ids=unsafe_task_ids,
                reason_code="IN_PROGRESS_SUFFIX_REPLAN_UNSUPPORTED",
            )
            return
        if late_count == 0 or not classifications:
            return
        locked_cycle_ids = [
            str(cycle.id) for cycle in plan.cycles if cycle not in affected_cycles or cycle.locked
        ]
        recovery_task_ids = sorted(
            {
                str(item["task_id"])
                for item in classifications
                if isinstance(item.get("task_id"), str)
            }
        )
        session.add(
            RecoveryProposal(
                event_id=event.id,
                warehouse_id=event.warehouse_id,
                day=event.day,
                source_plan_id=plan.id,
                proposal_type="PARTIAL_REPLAN_DELAY",
                status=RecoveryProposalStatus.PROPOSED,
                summary_ru="Пересчитать только затронутую задержкой часть дня.",
                affected_request_ids=[str(item) for item in late_requests],
                affected_task_ids=[item["task_id"] for item in classifications],
                changes={
                    "driver_shift_ids": [str(item.driver_shift_id) for item in affected_cycles],
                    "delay_minutes": payload.delay_minutes,
                    "effective_at": (payload.effective_at or payload.occurred_at).isoformat(),
                    "locked_cycle_ids": locked_cycle_ids,
                    "recovery_task_ids": recovery_task_ids,
                    "required_contact_action_ids": [str(action.id) for action in contact_actions],
                    "delay_contact_outcomes": {},
                    "preserve_original_window_request_ids": [],
                },
                metrics={
                    "affected": len(classifications),
                    "in_slot": len(classifications) - risk_count - late_count,
                    "risk": risk_count,
                    "late": late_count,
                },
            )
        )

    async def _analyze_resource_loss(
        self,
        session: AsyncSession,
        event: LogisticsEvent,
        plan: RoutePlan | None,
        payload: LogisticsEventCreate,
        warehouse: Warehouse,
        planning_member_ids: frozenset[UUID],
    ) -> None:
        """Disable the failed resource and stage a minimum-change partial replan."""

        if payload.event_type == LogisticsEventType.VEHICLE_BREAKDOWN:
            vehicle = await session.scalar(
                select(Vehicle).where(Vehicle.id == payload.vehicle_id).with_for_update()
            )
            vehicle_is_in_plan = plan is not None and any(
                cycle.driver_shift.vehicle_id == payload.vehicle_id for cycle in plan.cycles
            )
            if vehicle is None or (
                vehicle.warehouse_id not in planning_member_ids and not vehicle_is_in_plan
            ):
                raise not_found("vehicle", payload.vehicle_id)
            vehicle.active = False
            vehicle.version += 1
            resource_home_id = vehicle.warehouse_id
        elif payload.event_type == LogisticsEventType.TRAILER_BREAKDOWN:
            trailer = await session.scalar(
                select(Trailer).where(Trailer.id == payload.trailer_id).with_for_update()
            )
            trailer_is_in_plan = plan is not None and any(
                self._cycle_uses_trailer(cycle, payload.trailer_id) for cycle in plan.cycles
            )
            if trailer is None or (
                trailer.warehouse_id not in planning_member_ids and not trailer_is_in_plan
            ):
                raise not_found("trailer", payload.trailer_id)
            trailer.active = False
            trailer.version += 1
            resource_home_id = trailer.warehouse_id
        else:
            from app.models import DriverShift

            shift = await session.scalar(
                select(DriverShift).where(DriverShift.id == payload.driver_shift_id)
            )
            shift_is_in_plan = plan is not None and any(
                cycle.driver_shift_id == payload.driver_shift_id for cycle in plan.cycles
            )
            if shift is None or (
                shift.warehouse_id not in planning_member_ids and not shift_is_in_plan
            ):
                raise not_found("driver_shift", payload.driver_shift_id)
            resource_home_id = shift.warehouse_id
        for member_id in sorted(planning_member_ids | {resource_home_id}, key=str):
            generation = await advance_warehouse_capacity_generation(session, member_id)
            await mark_capacity_publication_pending(session, member_id, generation)
        if plan is None:
            await self._create_generic_notice(
                session, event, "Ресурс недоступен; активного плана нет."
            )
            return
        affected_cycles = [
            cycle
            for cycle in plan.cycles
            if (
                payload.vehicle_id is not None
                and cycle.driver_shift.vehicle_id == payload.vehicle_id
            )
            or (
                payload.driver_shift_id is not None
                and cycle.driver_shift_id == payload.driver_shift_id
            )
            or (
                payload.trailer_id is not None
                and self._cycle_uses_trailer(cycle, payload.trailer_id)
            )
        ]
        if not affected_cycles:
            await self._create_generic_notice(
                session,
                event,
                "Недоступность ресурса зарегистрирована; выбранный план не затронут.",
            )
            return
        owner_states = await self._owner_task_states_or_fail_closed(session, event, plan, warehouse)
        if owner_states is None:
            return
        affected_tasks = {
            stop.task.id: stop.task
            for cycle in affected_cycles
            for stop in cycle.stops
            if stop.task is not None
            and not self._is_terminal_execution_state(
                self._execution_state(stop.task, owner_states)
            )
        }
        unsafe_task_ids = [
            task.id
            for task in affected_tasks.values()
            if self._is_unsafe_suffix_state(self._execution_state(task, owner_states))
        ]
        completed_count = sum(
            stop.task is not None
            and self._is_terminal_execution_state(self._execution_state(stop.task, owner_states))
            for cycle in affected_cycles
            for stop in cycle.stops
        )
        if not affected_tasks:
            await self._create_generic_notice(
                session,
                event,
                "Недоступность ресурса зарегистрирована; все его задания уже завершены.",
            )
            return
        if unsafe_task_ids:
            await self._create_suffix_fail_closed_work(
                session,
                event,
                plan,
                task_ids=unsafe_task_ids,
                reason_code="IN_PROGRESS_SUFFIX_REPLAN_UNSUPPORTED",
            )
            return
        locked_task_ids = [
            stop.task_id
            for cycle in affected_cycles
            if cycle.locked
            for stop in cycle.stops
            if stop.task_id is not None and stop.task_id in affected_tasks
        ]
        if locked_task_ids:
            await self._create_suffix_fail_closed_work(
                session, event, plan, task_ids=locked_task_ids,
                reason_code="FAILED_RESOURCE_CYCLE_LOCKED",
            )
            return
        locked_cycle_ids = [
            str(cycle.id) for cycle in plan.cycles if cycle not in affected_cycles or cycle.locked
        ]
        notice = LogisticsNotice(
            event_id=event.id,
            warehouse_id=event.warehouse_id,
            day=event.day,
            plan_id=plan.id,
            notice_type="RESOURCE_LOSS_IMPACT",
            severity="CRITICAL",
            reason_codes=[payload.event_type.value, "PARTIAL_REPLAN_REQUIRED"],
            facts={
                "affected_task_count": len(affected_tasks),
                "completed_task_count": completed_count,
                "locked_cycle_ids": locked_cycle_ids,
            },
            message_ru=(
                f"Ресурс выбыл. Затронуто {len(affected_tasks)} невыполненных заданий; "
                f"{completed_count} выполненных точек остаются неизменными."
            ),
            recommended_action_ru="Примените частичное перепланирование оставшейся работы.",
            requires_action=True,
            status=LogisticsNoticeStatus.REQUIRES_ACTION,
        )
        session.add(notice)
        await session.flush()
        action = LogisticsHumanAction(
            notice_id=notice.id,
            event_id=event.id,
            warehouse_id=event.warehouse_id,
            day=event.day,
            action_type="APPLY_PARTIAL_REPLAN",
            context={"affected_task_count": len(affected_tasks)},
        )
        session.add(action)
        await session.flush()
        session.add(
            RecoveryProposal(
                event_id=event.id,
                action_id=action.id,
                warehouse_id=event.warehouse_id,
                day=event.day,
                source_plan_id=plan.id,
                proposal_type="PARTIAL_REPLAN_RESOURCE_LOSS",
                status=RecoveryProposalStatus.READY_TO_APPLY,
                summary_ru="Сохранить выполненную и незатронутую работу, перераспределить остаток.",
                affected_request_ids=sorted(
                    {str(task.request_id) for task in affected_tasks.values()}
                ),
                affected_task_ids=sorted(str(task_id) for task_id in affected_tasks),
                changes={
                    "locked_cycle_ids": locked_cycle_ids,
                    "recovery_task_ids": sorted(str(task_id) for task_id in affected_tasks),
                    "resource_event": payload.event_type.value,
                    "effective_at": event.facts["effective_at"],
                },
                metrics={
                    "affected": len(affected_tasks),
                    "completed_locked": completed_count,
                },
            )
        )

    @staticmethod
    def _cycle_uses_trailer(cycle: Any, trailer_id: UUID | None) -> bool:
        """Use the saved routed configuration, conservatively handling missing proofs."""

        if trailer_id is None:
            return False
        snapshots = [segment.routing_profile_snapshot for segment in cycle.segments]
        if any(
            snapshot is not None and snapshot.get("trailerId") == str(trailer_id)
            for snapshot in snapshots
        ):
            return True
        incomplete = not snapshots or any(
            snapshot is None or "trailerId" not in snapshot for snapshot in snapshots
        )
        return incomplete and (
            cycle.driver_shift.vehicle.default_trailer_id == trailer_id
        )

    async def _analyze_cancellation(
        self,
        session: AsyncSession,
        event: LogisticsEvent,
        plan: RoutePlan | None,
        payload: LogisticsEventCreate,
        warehouse: Warehouse,
        planning_member_ids: frozenset[UUID],
    ) -> None:
        """Stage same-day removal without ever pulling a future delivery into today."""

        request = await self._projection.request(session, payload.request_id)
        if request.warehouse_id not in planning_member_ids:
            raise ApiError(422, "EVENT_REQUEST_MISMATCH", "Задание относится к другому складу.")
        required_type = {
            LogisticsEventType.DELIVERY_CANCELLED: "DELIVERY",
            LogisticsEventType.PICKUP_CANCELLED: "PICKUP",
        }.get(payload.event_type)
        if required_type is not None and request.type != required_type:
            raise ApiError(
                422,
                "CANCELLATION_TYPE_MISMATCH",
                "Тип события отмены не соответствует типу задания.",
            )
        if request.status in {RequestStatus.COMPLETED, RequestStatus.CANCELLED}:
            raise ApiError(
                409,
                "REQUEST_ALREADY_TERMINAL",
                "Задание уже завершено или отменено.",
            )
        request_in_plan = plan is not None and (
            any(
                stop.task is not None and stop.task.request_id == request.id
                for cycle in plan.cycles
                for stop in cycle.stops
            )
            or any(item.task.request_id == request.id for item in plan.unassigned_tasks)
        )
        if request.scheduled_date != event.day and not request_in_plan:
            raise ApiError(
                422,
                "CANCELLATION_REQUEST_NOT_IN_DAY",
                "Задание не относится к выбранному дню или плану.",
            )
        owner_states: dict[UUID, _OwnerTaskState] = {}
        if plan is not None:
            loaded_states = await self._owner_task_states_or_fail_closed(
                session, event, plan, warehouse
            )
            if loaded_states is None:
                return
            owner_states = loaded_states
            request_plan_tasks = [
                stop.task
                for cycle in plan.cycles
                for stop in cycle.stops
                if stop.task is not None and stop.task.request_id == request.id
            ]
            request_execution_states = {
                self._execution_state(task, owner_states) for task in request_plan_tasks
            }
            if "COMPLETED" in request_execution_states:
                raise ApiError(
                    409,
                    "REQUEST_ALREADY_EXECUTED",
                    "Задание уже выполнено владельцем исполнения.",
                )
            if (
                request.source_system == catalog.RWMS_SOURCE_SYSTEM
                and request_plan_tasks
                and request_execution_states != {"CANCELLED"}
            ):
                raise ApiError(
                    409,
                    "RWMS_CANCELLATION_NOT_CONFIRMED",
                    "Владелец заказа ещё не подтвердил отмену всех заданий.",
                )
        affected_cycles = [
            cycle
            for cycle in (plan.cycles if plan is not None else [])
            if any(
                stop.task is not None and stop.task.request_id == request.id for stop in cycle.stops
            )
        ]
        unsafe_task_ids = [
            stop.task.id
            for cycle in affected_cycles
            for stop in cycle.stops
            if stop.task is not None
            and self._is_unsafe_suffix_state(self._execution_state(stop.task, owner_states))
        ]
        if plan is not None and unsafe_task_ids:
            await self._create_suffix_fail_closed_work(
                session,
                event,
                plan,
                task_ids=unsafe_task_ids,
                reason_code="CANCELLATION_IN_PROGRESS_UNSUPPORTED",
            )
            return
        pickup_candidates = tuple(
            await session.scalars(
                select(LogisticsRequest)
                .where(
                    LogisticsRequest.warehouse_id.in_(planning_member_ids),
                    LogisticsRequest.type == "PICKUP",
                    LogisticsRequest.status == RequestStatus.READY,
                    LogisticsRequest.id != request.id,
                    LogisticsRequest.date_options.any(RequestDateOption.date == event.day),
                )
                .order_by(
                    LogisticsRequest.priority.desc(),
                    LogisticsRequest.created_at,
                    LogisticsRequest.id,
                )
                .limit(20)
            )
        )
        feasible_pickup_ids: list[str] = []
        pickup_evaluation_error: str | None = None
        for candidate in pickup_candidates:
            try:
                feasible_dates = await self._planner.preview_feasible_request_dates(
                    session,
                    candidate.id,
                    (event.day,),
                )
                if event.day in feasible_dates:
                    feasible_pickup_ids.append(str(candidate.id))
            except ApiError as exc:
                pickup_evaluation_error = pickup_evaluation_error or exc.code
        return_warehouse_ids = {cycle.driver_shift.warehouse_id for cycle in affected_cycles}
        return_warehouses = tuple(
            await session.scalars(select(Warehouse).where(Warehouse.id.in_(return_warehouse_ids)))
        )
        base_task_groups: list[dict[str, Any]] = []
        if not feasible_pickup_ids and self._rwms_client is not None:
            for return_warehouse in sorted(return_warehouses, key=lambda item: str(item.id)):
                if return_warehouse.external_warehouse_id is None:
                    continue
                try:
                    tasks = [
                        item.model_dump(mode="json", by_alias=True)
                        for item in await self._rwms_client.list_base_tasks(
                            return_warehouse.external_warehouse_id,
                            available_at=payload.effective_at or payload.occurred_at,
                            limit=20,
                        )
                    ]
                except ApiError:
                    tasks = []
                if tasks:
                    base_task_groups.append(
                        {
                            "warehouse_id": str(return_warehouse.id),
                            "external_warehouse_id": str(return_warehouse.external_warehouse_id),
                            "warehouse_name": return_warehouse.name,
                            "tasks": tasks,
                        }
                    )
        locked_cycle_ids = [
            str(cycle.id)
            for cycle in (plan.cycles if plan is not None else [])
            if cycle not in affected_cycles
        ]
        recovery_task_ids = sorted(
            {
                str(stop.task.id)
                for cycle in affected_cycles
                for stop in cycle.stops
                if stop.task is not None
                and stop.task.request_id != request.id
                and not self._is_terminal_execution_state(
                    self._execution_state(stop.task, owner_states)
                )
            }
        )
        notice = LogisticsNotice(
            event_id=event.id,
            warehouse_id=event.warehouse_id,
            day=event.day,
            plan_id=plan.id if plan is not None else None,
            request_id=request.id,
            notice_type="TASK_CANCELLED_IMPACT",
            severity="WARNING",
            reason_codes=[
                payload.event_type.value,
                "NO_FUTURE_DELIVERY_PULL_FORWARD",
                *(["SAME_DAY_PICKUP_FEASIBLE"] if feasible_pickup_ids else []),
                *(
                    ["PICKUP_CANDIDATES_TO_EVALUATE"]
                    if pickup_candidates and not feasible_pickup_ids
                    else []
                ),
                *(["BASE_TASKS_AVAILABLE"] if base_task_groups else []),
            ],
            facts={
                "same_day_pickup_candidate_count": len(pickup_candidates),
                "feasible_same_day_pickup_ids": feasible_pickup_ids,
                "pickup_evaluation_error": pickup_evaluation_error,
                "base_task_groups": base_task_groups,
            },
            message_ru=(
                "Сегодняшнее задание отменено. Завтрашние доставки на сегодня не переносятся."
            ),
            recommended_action_ru=(
                "Пересчитайте остаток дня; сначала рассматриваются подходящие вывозы."
                if feasible_pickup_ids
                else (
                    "Пересчитайте остаток дня: кандидаты на вывоз требуют проверки маршрутом; "
                    "внутренняя работа остаётся резервом."
                    if pickup_candidates
                    else "Верните машину на базу; при наличии можно выполнить внутреннее задание."
                )
            ),
            requires_action=False,
            status=LogisticsNoticeStatus.COMPLETED,
        )
        session.add(notice)
        session.add(
            RecoveryProposal(
                event_id=event.id,
                warehouse_id=event.warehouse_id,
                day=event.day,
                source_plan_id=plan.id if plan is not None else None,
                proposal_type="PARTIAL_REPLAN_CANCELLATION",
                status=RecoveryProposalStatus.READY_TO_APPLY,
                summary_ru="Убрать отменённое задание и пересчитать только остаток дня.",
                affected_request_ids=[str(request.id)],
                affected_task_ids=[str(task.id) for task in request.tasks],
                changes={
                    "cancel_request_id": str(request.id),
                    "cancel_local": request.source_system != catalog.RWMS_SOURCE_SYSTEM,
                    "owner_cancellation_snapshot": {
                        str(task.id): {
                            "external_task_id": str(owner_states[task.id].external_task_id),
                            "task_version": owner_states[task.id].task_version,
                            "task_state": owner_states[task.id].task_state,
                        }
                        for task in request.tasks
                        if task.id in owner_states
                    },
                    "locked_cycle_ids": locked_cycle_ids,
                    "recovery_task_ids": recovery_task_ids,
                    "feasible_same_day_pickup_ids": feasible_pickup_ids,
                    "base_task_groups": base_task_groups,
                },
                metrics={
                    "future_deliveries_pulled": 0,
                    "same_day_pickup_candidate_count": len(pickup_candidates),
                    "feasible_same_day_pickup_count": len(feasible_pickup_ids),
                    "base_task_count": sum(len(group["tasks"]) for group in base_task_groups),
                },
            )
        )

    async def _analyze_task_blocked(
        self,
        session: AsyncSession,
        event: LogisticsEvent,
        plan: RoutePlan | None,
        payload: LogisticsEventCreate,
        warehouse: Warehouse,
    ) -> None:
        """Exclude one exact planned task and stage only a safe remaining-work rebuild."""

        if plan is None or payload.task_id is None:
            raise ApiError(
                409,
                "TASK_BLOCKED_PLAN_REQUIRED",
                "Блокировка задания требует актуальный план дня.",
            )
        assigned_cycles = [
            cycle
            for cycle in plan.cycles
            if any(stop.task_id == payload.task_id for stop in cycle.stops)
        ]
        unassigned = next(
            (item for item in plan.unassigned_tasks if item.task_id == payload.task_id),
            None,
        )
        if not assigned_cycles and unassigned is None:
            raise ApiError(
                422,
                "TASK_NOT_IN_SOURCE_PLAN",
                "Задание не входит в выбранную ревизию плана.",
            )
        if payload.cycle_id is not None and not any(
            cycle.id == payload.cycle_id for cycle in assigned_cycles
        ):
            raise ApiError(
                422,
                "TASK_CYCLE_MISMATCH",
                "Задание не входит в указанный рейс.",
            )
        if assigned_cycles:
            task = next(
                stop.task
                for cycle in assigned_cycles
                for stop in cycle.stops
                if stop.task_id == payload.task_id and stop.task is not None
            )
        else:
            assert unassigned is not None
            task = unassigned.task
        owner_states = await self._owner_task_states_or_fail_closed(session, event, plan, warehouse)
        if owner_states is None:
            return
        execution_state = self._execution_state(task, owner_states)
        if self._is_terminal_execution_state(execution_state):
            await self._create_generic_notice(
                session,
                event,
                "Задание уже завершено или отменено; активный план не изменён.",
            )
            return
        if self._is_unsafe_suffix_state(execution_state):
            await self._create_suffix_fail_closed_work(
                session,
                event,
                plan,
                task_ids=[task.id],
                reason_code="TASK_BLOCKED_IN_PROGRESS",
            )
            return
        notice = LogisticsNotice(
            event_id=event.id,
            warehouse_id=event.warehouse_id,
            day=event.day,
            plan_id=plan.id,
            cycle_id=assigned_cycles[0].id if assigned_cycles else None,
            request_id=task.request_id,
            task_id=task.id,
            notice_type="TASK_BLOCKED_IMPACT",
            severity="ERROR",
            reason_codes=["TASK_BLOCKED", "PARTIAL_REPLAN_REQUIRED"],
            facts={"execution_state": execution_state},
            message_ru="Задание заблокировано и исключено из выполнимой части плана.",
            recommended_action_ru=(
                "Устраните причину блокировки; оставшуюся работу можно перепланировать."
            ),
            requires_action=True,
            status=LogisticsNoticeStatus.REQUIRES_ACTION,
        )
        session.add(notice)
        await session.flush()
        action = LogisticsHumanAction(
            notice_id=notice.id,
            event_id=event.id,
            warehouse_id=event.warehouse_id,
            day=event.day,
            request_id=task.request_id,
            action_type="RESOLVE_BLOCKED_TASK",
            context={"task_id": str(task.id), "source_plan_id": str(plan.id)},
        )
        session.add(action)
        await session.flush()
        if not assigned_cycles:
            return
        locked_cycle_ids = [str(cycle.id) for cycle in plan.cycles if cycle not in assigned_cycles]
        recovery_task_ids = sorted(
            {
                str(stop.task.id)
                for cycle in assigned_cycles
                for stop in cycle.stops
                if stop.task is not None
                and stop.task.id != task.id
                and not self._is_terminal_execution_state(
                    self._execution_state(stop.task, owner_states)
                )
            }
        )
        session.add(
            RecoveryProposal(
                event_id=event.id,
                action_id=action.id,
                warehouse_id=event.warehouse_id,
                day=event.day,
                source_plan_id=plan.id,
                proposal_type="PARTIAL_REPLAN_TASK_BLOCKED",
                status=RecoveryProposalStatus.READY_TO_APPLY,
                summary_ru="Исключить заблокированное задание и пересчитать остаток рейса.",
                affected_request_ids=[str(task.request_id)],
                affected_task_ids=[str(task.id)],
                changes={
                    "blocked_task_id": str(task.id),
                    "locked_cycle_ids": locked_cycle_ids,
                    "recovery_task_ids": recovery_task_ids,
                },
                metrics={"affected": 1, "remaining_to_replan": len(recovery_task_ids)},
            )
        )

    async def _create_generic_notice(
        self,
        session: AsyncSession,
        event: LogisticsEvent,
        message: str,
    ) -> None:
        """Persist an informational comment for a structured event with no special analyzer."""

        session.add(
            LogisticsNotice(
                event_id=event.id,
                warehouse_id=event.warehouse_id,
                day=event.day,
                plan_id=event.plan_id,
                cycle_id=event.cycle_id,
                request_id=event.request_id,
                task_id=event.task_id,
                notice_type=event.event_type,
                severity="INFO",
                reason_codes=[event.event_type],
                facts=event.facts,
                message_ru=message,
                requires_action=False,
                status=LogisticsNoticeStatus.COMPLETED,
            )
        )
