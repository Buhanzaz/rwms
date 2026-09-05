"""Human decisions and recoverable proposal application."""

# ruff: noqa: RUF001 -- Russian operator-facing messages are intentional.

from __future__ import annotations

from datetime import date
from uuid import NAMESPACE_URL, UUID, uuid4, uuid5

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import utc_now
from app.errors import ApiError, not_found
from app.integrations.rwms import RwmsPlanningClient
from app.integrations.rwms_sync import (
    build_assignment_replacement_command,
    build_plan_status_task_index,
    build_replacement_membership,
)
from app.models import (
    LogisticsActionStatus,
    LogisticsDecisionType,
    LogisticsEvent,
    LogisticsHumanAction,
    LogisticsHumanDecision,
    LogisticsNotice,
    LogisticsNoticeStatus,
    LogisticsRequest,
    RecoveryProposal,
    RecoveryProposalStatus,
    RoutePlan,
    Warehouse,
)
from app.models.domain import RequestStatus, TaskStatus
from app.schemas.domain import (
    ManualChangeCommand,
    RwmsPlanningAssignmentStatus,
    RwmsReplacePlanningAssignmentsCommand,
)
from app.schemas.operations import (
    LogisticsHumanDecisionCreate,
    LogisticsHumanDecisionRead,
    RecoveryProposalRead,
    RwmsPublishedAssignmentRemoval,
    RwmsPublishedAssignmentWithdrawal,
    RwmsPublishedAssignmentWithdrawalResult,
    RwmsRescheduleCommand,
)
from app.services import catalog
from app.services import plans as plan_service
from app.services.dynamic_projection import DynamicOperationsProjection
from app.services.dynamic_support import (
    PENDING_ACTION_STATUSES as _PENDING_ACTION_STATUSES,
)
from app.services.dynamic_support import (
    decision_command_hash as _decision_command_hash,
)
from app.services.dynamic_support import (
    decision_read as _decision_read,
)
from app.services.dynamic_support import (
    proposal_read as _proposal_read,
)
from app.services.plans import PlannerFacade
from app.services.request_reschedule_fence import reject_active_request_reschedules


class RecoveryProposalWorkflow:
    """Human decisions and recoverable proposal application."""

    def __init__(
        self,
        planner: PlannerFacade,
        rwms_client: RwmsPlanningClient | None,
        projection: DynamicOperationsProjection,
    ) -> None:
        """Bind the existing planner and authoritative owner adapter."""

        self._planner = planner
        self._rwms_client = rwms_client
        self._projection = projection

    async def decide_action(
        self,
        session: AsyncSession,
        action_id: UUID,
        payload: LogisticsHumanDecisionCreate,
        *,
        actor: str,
        idempotency_key: str,
    ) -> LogisticsHumanDecisionRead:
        """Record an immutable answer and keep agreement separate from plan application."""

        action = await session.scalar(
            select(LogisticsHumanAction)
            .where(LogisticsHumanAction.id == action_id)
            .with_for_update()
        )
        if action is None:
            raise not_found("logistics_human_action", action_id)
        replay = await session.scalar(
            select(LogisticsHumanDecision).where(
                LogisticsHumanDecision.action_id == action_id,
                LogisticsHumanDecision.idempotency_key == idempotency_key,
            )
        )
        if replay is not None:
            if replay.constraint_data.get("command_hash") != _decision_command_hash(payload):
                raise ApiError(409, "IDEMPOTENCY_KEY_REUSED", "Ключ повтора уже использован.")
            return _decision_read(replay)
        if action.version != payload.expected_version:
            raise ApiError(
                409,
                "LOGISTICS_ACTION_VERSION_CONFLICT",
                "Действие уже изменено другим пользователем.",
                extra={
                    "expected_version": payload.expected_version,
                    "actual_version": action.version,
                },
            )
        if action.status not in _PENDING_ACTION_STATUSES:
            raise ApiError(409, "LOGISTICS_ACTION_RESOLVED", "Действие уже завершено.")
        proposals = tuple(
            await session.scalars(
                select(RecoveryProposal)
                .where(
                    RecoveryProposal.action_id == action.id,
                    RecoveryProposal.status.in_(
                        (
                            RecoveryProposalStatus.PROPOSED,
                            RecoveryProposalStatus.CUSTOMER_AGREED,
                        )
                    ),
                )
                .order_by(RecoveryProposal.created_at.desc(), RecoveryProposal.id.desc())
                .with_for_update()
            )
        )
        active_proposal = proposals[0] if proposals else None
        if active_proposal is not None:
            if payload.expected_proposal_version != active_proposal.version:
                raise ApiError(
                    409,
                    "RECOVERY_PROPOSAL_VERSION_CONFLICT",
                    "Предложение уже изменено другим пользователем.",
                    extra={
                        "expected_version": payload.expected_proposal_version,
                        "actual_version": active_proposal.version,
                    },
                )
        elif (
            payload.expected_proposal_version is not None
            and action.action_type != "CONTACT_CUSTOMER_DELAY"
        ):
            raise ApiError(
                409,
                "RECOVERY_PROPOSAL_VERSION_CONFLICT",
                "Связанное предложение больше не ожидает решения.",
            )
        if action.action_type == "CONTACT_CUSTOMER_DELAY":
            if payload.decision_type not in {
                LogisticsDecisionType.ACCEPT_DELAY,
                LogisticsDecisionType.REJECT_DELAY,
                LogisticsDecisionType.UNREACHABLE,
            }:
                raise ApiError(
                    422,
                    "DECISION_NOT_ALLOWED_FOR_ACTION",
                    "Для уведомления об опоздании выберите результат разговора с клиентом.",
                )
            return await self._decide_delay_action(
                session,
                action,
                payload,
                actor=actor,
                idempotency_key=idempotency_key,
            )
        if action.action_type in {
            "REFRESH_AUTHORITATIVE_TASK_STATE",
            "RESOLVE_IN_PROGRESS_ROUTE",
        }:
            if payload.decision_type != LogisticsDecisionType.ACKNOWLEDGE_RESOLVED:
                raise ApiError(
                    422,
                    "DECISION_NOT_ALLOWED_FOR_ACTION",
                    "Операционное действие можно завершить только явным подтверждением.",
                )
            return await self._acknowledge_operational_action(
                session,
                action,
                payload,
                actor=actor,
                idempotency_key=idempotency_key,
            )
        if action.action_type != "AGREE_RESCHEDULE":
            raise ApiError(
                422,
                "DECISION_NOT_ALLOWED_FOR_ACTION",
                "Это действие завершается применением связанного предложения.",
            )
        if payload.decision_type not in {
            LogisticsDecisionType.ACCEPT_RECOMMENDATION,
            LogisticsDecisionType.ACCEPT_OTHER_DATE,
            LogisticsDecisionType.REJECT,
            LogisticsDecisionType.UNREACHABLE,
        }:
            raise ApiError(
                422,
                "DECISION_NOT_ALLOWED_FOR_ACTION",
                "Для согласования даты выберите результат разговора о переносе.",
            )
        rejected_dates = {
            value for value in action.context.get("rejected_dates", []) if isinstance(value, str)
        }
        if (
            payload.decision_type == LogisticsDecisionType.REJECT
            and action.recommended_date is None
        ):
            raise ApiError(
                409,
                "NO_RECOMMENDATION_TO_REJECT",
                "Новой рекомендуемой даты пока нет; отклонять нечего.",
            )
        if payload.decision_type == LogisticsDecisionType.ACCEPT_OTHER_DATE:
            assert payload.selected_date is not None
            allowed_dates = {value for value in action.alternative_dates if isinstance(value, str)}
            if action.recommended_date is not None:
                allowed_dates.add(action.recommended_date.isoformat())
            if (
                payload.selected_date == action.current_date
                or payload.selected_date.isoformat() in rejected_dates
                or payload.selected_date.isoformat() not in allowed_dates
            ):
                raise ApiError(
                    422,
                    "RESCHEDULE_DATE_NOT_OFFERED",
                    "Выберите одну из актуальных допустимых дат переноса.",
                )
        selected_date = (
            action.recommended_date
            if payload.decision_type
            in {
                LogisticsDecisionType.ACCEPT_RECOMMENDATION,
                LogisticsDecisionType.REJECT,
            }
            else payload.selected_date
        )
        constraint = {
            "decision": payload.decision_type.value,
            "selected_date": selected_date.isoformat() if selected_date else None,
            "command_hash": _decision_command_hash(payload),
        }
        decision = LogisticsHumanDecision(
            action_id=action.id,
            event_id=action.event_id,
            decision_type=payload.decision_type,
            selected_date=selected_date,
            actor=actor,
            comment=payload.comment,
            idempotency_key=idempotency_key,
            constraint_data=constraint,
        )
        session.add(decision)
        await session.flush()
        notice = await session.get(LogisticsNotice, action.notice_id)
        if payload.decision_type in {
            LogisticsDecisionType.ACCEPT_RECOMMENDATION,
            LogisticsDecisionType.ACCEPT_OTHER_DATE,
        }:
            if selected_date is None:
                raise ApiError(
                    422,
                    "NO_FEASIBLE_RECOMMENDATION",
                    "Система пока не нашла выполнимую дату для согласования.",
                )
            request = await self._projection.request(session, action.request_id)
            feasible = await self._planner.preview_feasible_request_dates(
                session, request.id, (selected_date,)
            )
            if selected_date not in feasible:
                raise ApiError(
                    422,
                    "RESCHEDULE_DATE_NOT_FEASIBLE",
                    "Выбранную дату невозможно выполнить текущими ресурсами.",
                )
            if active_proposal is None:
                raise ApiError(
                    409,
                    "RECOVERY_PROPOSAL_MISSING",
                    "Предложение для этого решения больше не актуально.",
                )
            slot_by_date = action.context.get("slot_by_date", {})
            selected_slot = (
                slot_by_date.get(selected_date.isoformat())
                if isinstance(slot_by_date, dict)
                else None
            )
            if request.source_system == catalog.RWMS_SOURCE_SYSTEM and not isinstance(
                selected_slot, dict
            ):
                raise ApiError(
                    409,
                    "RWMS_RESCHEDULE_OFFER_EXPIRED",
                    "Предложенный слот больше не доступен; обновите варианты переноса.",
                )
            active_proposal.status = RecoveryProposalStatus.CUSTOMER_AGREED
            active_proposal.changes = {
                **active_proposal.changes,
                "selected_date": selected_date.isoformat(),
                "selected_slot": selected_slot,
                "reschedule_owner": action.context.get("reschedule_owner", {}),
                "decision_code": (
                    "CUSTOMER_AGREED_RECOMMENDED"
                    if payload.decision_type == LogisticsDecisionType.ACCEPT_RECOMMENDATION
                    else "CUSTOMER_AGREED_ALTERNATIVE"
                ),
                "decision_reason": payload.comment or "Дата согласована с клиентом.",
            }
            active_proposal.version += 1
            action.status = LogisticsActionStatus.RESOLVED
            action.resolved_at = utc_now()
            if notice is not None:
                notice.status = LogisticsNoticeStatus.ACCEPTED
                notice.resolved_at = utc_now()
        elif payload.decision_type == LogisticsDecisionType.REJECT:
            rejected = set(rejected_dates)
            assert selected_date is not None
            rejected.add(selected_date.isoformat())
            action.context = {**action.context, "rejected_dates": sorted(rejected)}
            request = await self._projection.request(session, action.request_id)
            stored_slots = action.context.get("slot_by_date", {})
            stored_dates = (
                tuple(date.fromisoformat(value) for value in stored_slots)
                if isinstance(stored_slots, dict)
                else ()
            )
            alternatives = tuple(
                candidate
                for candidate in (
                    stored_dates or tuple(option.date for option in request.date_options)
                )
                if candidate.isoformat() not in rejected and candidate != action.current_date
            )
            feasible = await self._planner.preview_feasible_request_dates(
                session, request.id, alternatives
            )
            action.recommended_date = feasible[0] if feasible else None
            action.alternative_dates = [item.isoformat() for item in feasible[1:]]
            if notice is not None:
                notice.status = LogisticsNoticeStatus.REJECTED
                notice.resolved_at = utc_now()
            for proposal in proposals:
                proposal.status = RecoveryProposalStatus.REJECTED
                proposal.version += 1
            followup = LogisticsNotice(
                event_id=action.event_id,
                warehouse_id=action.warehouse_id,
                day=action.day,
                request_id=action.request_id,
                notice_type="CUSTOMER_REJECTED_DATE",
                severity="WARNING",
                reason_codes=["CUSTOMER_REJECTED_DATE"],
                facts={
                    "rejected_dates": sorted(rejected),
                    "next_date": (
                        action.recommended_date.isoformat()
                        if action.recommended_date is not None
                        else None
                    ),
                },
                message_ru="Клиент отказался от предложенной даты. Ограничение сохранено.",
                recommended_action_ru=(
                    "Следующий выполнимый вариант — "
                    f"{action.recommended_date.strftime('%d.%m.%Y')}."
                    if action.recommended_date is not None
                    else "Выполнимых дат пока не найдено; измените ресурсы или ограничения."
                ),
                requires_action=True,
                status=LogisticsNoticeStatus.REQUIRES_ACTION,
            )
            session.add(followup)
            await session.flush()
            action.notice_id = followup.id
            session.add(
                RecoveryProposal(
                    event_id=action.event_id,
                    action_id=action.id,
                    warehouse_id=action.warehouse_id,
                    day=action.day,
                    proposal_type="RESCHEDULE_REQUEST",
                    status=RecoveryProposalStatus.PROPOSED,
                    summary_ru="Повторно согласовать выполнимую дату с клиентом.",
                    affected_request_ids=(
                        [str(action.request_id)] if action.request_id is not None else []
                    ),
                    changes={
                        "request_id": str(action.request_id),
                        "from_date": (
                            action.current_date.isoformat()
                            if action.current_date is not None
                            else None
                        ),
                        "recommended_date": (
                            action.recommended_date.isoformat()
                            if action.recommended_date is not None
                            else None
                        ),
                        "rejected_dates": sorted(rejected),
                    },
                    metrics={"customer_commitment_changes": 1},
                )
            )
        else:
            action.status = LogisticsActionStatus.PENDING
            action.resolved_at = None
            for proposal in proposals:
                proposal.changes = {
                    **proposal.changes,
                    "customer_unreachable_at": utc_now().isoformat(),
                }
                proposal.version += 1
            if notice is not None:
                notice.status = LogisticsNoticeStatus.COMPLETED
                notice.resolved_at = utc_now()
            followup = LogisticsNotice(
                event_id=action.event_id,
                warehouse_id=action.warehouse_id,
                day=action.day,
                request_id=action.request_id,
                notice_type="CUSTOMER_UNREACHABLE",
                severity="WARNING",
                reason_codes=["CUSTOMER_UNREACHABLE"],
                facts={"decision_idempotency_key": idempotency_key},
                message_ru="Связаться с клиентом не удалось. Изменение не применено.",
                recommended_action_ru="Повторите попытку связи; исходный план сохранён.",
                requires_action=True,
                status=LogisticsNoticeStatus.REQUIRES_ACTION,
            )
            session.add(followup)
            await session.flush()
            action.notice_id = followup.id
        action.version += 1
        await session.flush()
        return _decision_read(decision)

    async def _decide_delay_action(
        self,
        session: AsyncSession,
        action: LogisticsHumanAction,
        payload: LogisticsHumanDecisionCreate,
        *,
        actor: str,
        idempotency_key: str,
    ) -> LogisticsHumanDecisionRead:
        """Aggregate every customer response into one version-fenced delay proposal."""

        proposal = await session.scalar(
            select(RecoveryProposal)
            .where(
                RecoveryProposal.event_id == action.event_id,
                RecoveryProposal.proposal_type == "PARTIAL_REPLAN_DELAY",
                RecoveryProposal.status.in_(
                    (
                        RecoveryProposalStatus.PROPOSED,
                        RecoveryProposalStatus.READY_TO_APPLY,
                        RecoveryProposalStatus.FAILED,
                    )
                ),
            )
            .order_by(RecoveryProposal.created_at.desc(), RecoveryProposal.id.desc())
            .with_for_update()
        )
        if proposal is None:
            raise ApiError(
                409,
                "DELAY_RECOVERY_PROPOSAL_MISSING",
                "Предложение перепланирования задержки больше не актуально.",
            )
        if payload.expected_proposal_version != proposal.version:
            raise ApiError(
                409,
                "RECOVERY_PROPOSAL_VERSION_CONFLICT",
                "Предложение уже изменено другим пользователем.",
                extra={
                    "expected_version": payload.expected_proposal_version,
                    "actual_version": proposal.version,
                },
            )
        required_action_ids = {
            value
            for value in proposal.changes.get("required_contact_action_ids", [])
            if isinstance(value, str)
        }
        if str(action.id) not in required_action_ids:
            raise ApiError(
                409,
                "DELAY_ACTION_PROPOSAL_MISMATCH",
                "Действие не относится к актуальному анализу задержки.",
            )
        constraint = {
            "decision": payload.decision_type.value,
            "preserve_original_window": (
                payload.decision_type == LogisticsDecisionType.REJECT_DELAY
            ),
            "window_start": action.context.get("window_start"),
            "window_end": action.context.get("window_end"),
            "new_eta": action.context.get("new_eta"),
            "late_minutes": action.context.get("late_minutes"),
            "command_hash": _decision_command_hash(payload),
        }
        decision = LogisticsHumanDecision(
            action_id=action.id,
            event_id=action.event_id,
            decision_type=payload.decision_type,
            selected_date=None,
            actor=actor,
            comment=payload.comment,
            idempotency_key=idempotency_key,
            constraint_data=constraint,
        )
        session.add(decision)
        await session.flush()
        outcomes = {
            key: value
            for key, value in proposal.changes.get("delay_contact_outcomes", {}).items()
            if isinstance(key, str) and isinstance(value, dict)
        }
        outcomes[str(action.id)] = {
            "decision": payload.decision_type.value,
            "request_id": str(action.request_id) if action.request_id is not None else None,
            "decision_id": str(decision.id),
        }
        preserve_ids = {
            value
            for value in proposal.changes.get("preserve_original_window_request_ids", [])
            if isinstance(value, str)
        }
        if (
            payload.decision_type == LogisticsDecisionType.REJECT_DELAY
            and action.request_id is not None
        ):
            preserve_ids.add(str(action.request_id))
        proposal.changes = {
            **proposal.changes,
            "delay_contact_outcomes": outcomes,
            "preserve_original_window": bool(preserve_ids),
            "preserve_original_window_request_ids": sorted(preserve_ids),
        }
        proposal.version += 1
        notice = await session.get(LogisticsNotice, action.notice_id)
        now = utc_now()
        if payload.decision_type == LogisticsDecisionType.UNREACHABLE:
            action.status = LogisticsActionStatus.PENDING
            action.resolved_at = None
            followup = LogisticsNotice(
                event_id=action.event_id,
                warehouse_id=action.warehouse_id,
                day=action.day,
                request_id=action.request_id,
                notice_type="DELAY_CUSTOMER_UNREACHABLE",
                severity="WARNING",
                reason_codes=["CUSTOMER_UNREACHABLE", "DELAY_NOT_ACCEPTED"],
                facts={
                    "new_eta": action.context.get("new_eta"),
                    "window_end": action.context.get("window_end"),
                },
                message_ru="Предупредить клиента об опоздании пока не удалось.",
                recommended_action_ru="Повторите попытку связи; слот клиента не изменён.",
                requires_action=True,
                status=LogisticsNoticeStatus.REQUIRES_ACTION,
            )
            session.add(followup)
            await session.flush()
            action.notice_id = followup.id
        else:
            action.status = LogisticsActionStatus.RESOLVED
            action.resolved_at = now
        action.version += 1
        await session.flush()
        pending_contacts = int(
            await session.scalar(
                select(func.count(LogisticsHumanAction.id)).where(
                    LogisticsHumanAction.event_id == action.event_id,
                    LogisticsHumanAction.action_type == "CONTACT_CUSTOMER_DELAY",
                    LogisticsHumanAction.status.in_(_PENDING_ACTION_STATUSES),
                )
            )
            or 0
        )
        if pending_contacts == 0:
            proposal.status = RecoveryProposalStatus.READY_TO_APPLY
            summary_notice = LogisticsNotice(
                event_id=action.event_id,
                warehouse_id=action.warehouse_id,
                day=action.day,
                plan_id=proposal.source_plan_id,
                notice_type="DELAY_CONTACTS_COMPLETED",
                severity="ERROR" if preserve_ids else "WARNING",
                reason_codes=[
                    "DELAY_CONTACTS_COMPLETED",
                    *(["PRESERVE_ORIGINAL_WINDOW"] if preserve_ids else []),
                ],
                facts={
                    "outcomes": outcomes,
                    "preserve_original_window_request_ids": sorted(preserve_ids),
                },
                message_ru="Результаты связи с клиентами сохранены как ограничения плана.",
                recommended_action_ru="Примените единое частичное перепланирование.",
                requires_action=True,
                status=LogisticsNoticeStatus.REQUIRES_ACTION,
            )
            session.add(summary_notice)
            await session.flush()
            apply_action = LogisticsHumanAction(
                notice_id=summary_notice.id,
                event_id=action.event_id,
                warehouse_id=action.warehouse_id,
                day=action.day,
                action_type="APPLY_PARTIAL_REPLAN",
                context={
                    "proposal_id": str(proposal.id),
                    "preserve_original_window_request_ids": sorted(preserve_ids),
                },
            )
            session.add(apply_action)
            await session.flush()
            proposal.action_id = apply_action.id
            if notice is not None:
                notice.status = (
                    LogisticsNoticeStatus.REJECTED
                    if preserve_ids
                    else LogisticsNoticeStatus.ACCEPTED
                )
                notice.resolved_at = now
        else:
            proposal.status = RecoveryProposalStatus.PROPOSED
        await session.flush()
        return _decision_read(decision)

    async def _acknowledge_operational_action(
        self,
        session: AsyncSession,
        action: LogisticsHumanAction,
        payload: LogisticsHumanDecisionCreate,
        *,
        actor: str,
        idempotency_key: str,
    ) -> LogisticsHumanDecisionRead:
        """Resolve proven operator work without fabricating a route or resource mutation."""

        linked_proposal_count = int(
            await session.scalar(
                select(func.count(RecoveryProposal.id)).where(
                    RecoveryProposal.action_id == action.id
                )
            )
            or 0
        )
        if linked_proposal_count:
            raise ApiError(
                409,
                "OPERATIONAL_ACTION_HAS_PROPOSAL",
                "Это действие завершается применением связанного предложения.",
            )
        if action.action_type == "REFRESH_AUTHORITATIVE_TASK_STATE":
            await self._assert_authoritative_status_available(session, action)
        decision = LogisticsHumanDecision(
            action_id=action.id,
            event_id=action.event_id,
            decision_type=payload.decision_type,
            selected_date=None,
            actor=actor,
            comment=payload.comment,
            idempotency_key=idempotency_key,
            constraint_data={
                "decision": payload.decision_type.value,
                "operator_comment": payload.comment,
                "authoritative_status_rechecked": (
                    action.action_type == "REFRESH_AUTHORITATIVE_TASK_STATE"
                ),
                "command_hash": _decision_command_hash(payload),
            },
        )
        session.add(decision)
        now = utc_now()
        action.status = LogisticsActionStatus.RESOLVED
        action.resolved_at = now
        action.version += 1
        notice = await session.get(LogisticsNotice, action.notice_id)
        if notice is not None:
            notice.status = LogisticsNoticeStatus.COMPLETED
            notice.resolved_at = now
        await session.flush()
        return _decision_read(decision)

    async def _assert_authoritative_status_available(
        self,
        session: AsyncSession,
        action: LogisticsHumanAction,
    ) -> None:
        """Re-read a complete owner status snapshot before resolving a refresh action."""

        event = await session.get(LogisticsEvent, action.event_id)
        if event is None or event.plan_id is None or self._rwms_client is None:
            raise ApiError(
                503,
                "RWMS_ASSIGNMENT_STATUS_UNAVAILABLE",
                "Фактическое состояние заданий всё ещё недоступно.",
            )
        plan = await plan_service.get_plan(session, event.plan_id)
        task_index = build_plan_status_task_index(plan)
        expected = {
            stop.task.id
            for cycle in plan.cycles
            for stop in cycle.stops
            if stop.task is not None
            and stop.task.request.source_system == catalog.RWMS_SOURCE_SYSTEM
            and stop.task.type == "DELIVERY"
        }
        service_warehouse_ids = {request.warehouse_id for _, request in task_index.values()}
        warehouses = tuple(
            await session.scalars(select(Warehouse).where(Warehouse.id.in_(service_warehouse_ids)))
        )
        observed: set[UUID] = set()
        for warehouse in warehouses:
            if warehouse.external_warehouse_id is None:
                continue
            feed = await self._rwms_client.get_assignment_statuses(
                warehouse_id=warehouse.external_warehouse_id,
                date=action.day,
            )
            if feed.warehouse_id != warehouse.external_warehouse_id or feed.date != action.day:
                raise ApiError(
                    502,
                    "RWMS_ASSIGNMENT_STATUS_SCOPE_MISMATCH",
                    "Владелец заказа вернул состояния другого склада или дня.",
                )
            for status in feed.assignments:
                local = task_index.get((status.order_id, tuple(status.unit_ids)))
                if local is not None and local[1].warehouse_id == warehouse.id:
                    observed.add(local[0].id)
        if observed != expected:
            raise ApiError(
                503,
                "RWMS_ASSIGNMENT_STATUS_INCOMPLETE",
                "Фактическое состояние не всех заданий удалось обновить.",
            )

    async def apply_proposal(
        self,
        session: AsyncSession,
        proposal_id: UUID,
        *,
        expected_version: int,
        actor: str,
        actor_subject_id: UUID,
        idempotency_key: str,
    ) -> RecoveryProposalRead:
        """Apply a ready recovery revision without rewriting its historical source plan."""

        proposal = await self._lock_proposal(session, proposal_id)
        apply_attempts = proposal.changes.get("apply_attempts", [])
        matching_attempt = next(
            (
                item
                for item in apply_attempts
                if isinstance(item, dict) and item.get("key") == idempotency_key
            ),
            None,
        )
        if matching_attempt is not None:
            if matching_attempt.get("expected_version") != expected_version:
                raise ApiError(409, "IDEMPOTENCY_KEY_REUSED", "Ключ повтора уже использован.")
            if matching_attempt.get("result_version") == proposal.version:
                return _proposal_read(proposal)
            raise ApiError(409, "IDEMPOTENCY_KEY_REUSED", "Ключ повтора уже использован.")
        if proposal.status == RecoveryProposalStatus.APPLIED:
            raise ApiError(409, "RECOVERY_ALREADY_APPLIED", "Предложение уже применено.")
        customer_change = proposal.proposal_type == "RESCHEDULE_REQUEST"
        required_statuses = (
            (
                RecoveryProposalStatus.CUSTOMER_AGREED,
                RecoveryProposalStatus.FAILED,
            )
            if customer_change
            else (
                RecoveryProposalStatus.READY_TO_APPLY,
                RecoveryProposalStatus.FAILED,
            )
        )
        active_apply = proposal.changes.get("active_apply")
        prepared = proposal.changes.get("prepared_recovery")
        operation_token: str
        if isinstance(active_apply, dict):
            active_key = active_apply.get("key")
            active_expected = active_apply.get("expected_version")
            active_token = active_apply.get("token")
            if (
                active_key == idempotency_key
                and active_expected == expected_version
                and isinstance(active_token, str)
                and isinstance(prepared, dict)
            ):
                operation_token = active_token
            elif proposal.version == expected_version:
                operation_token = str(uuid4())
                proposal.status = RecoveryProposalStatus.READY_TO_APPLY
                proposal.changes = {
                    **proposal.changes,
                    "active_apply": {
                        "token": operation_token,
                        "key": idempotency_key,
                        "expected_version": expected_version,
                        "started_at": utc_now().isoformat(),
                    },
                }
                proposal.version += 1
            else:
                raise ApiError(
                    409,
                    "RECOVERY_APPLY_IN_PROGRESS",
                    "Применение уже выполняется; обновите состояние предложения.",
                    extra={"actual_version": proposal.version},
                )
        else:
            if proposal.version != expected_version:
                raise ApiError(
                    409,
                    "RECOVERY_PROPOSAL_VERSION_CONFLICT",
                    "Предложение уже изменено другим пользователем.",
                    extra={
                        "expected_version": expected_version,
                        "actual_version": proposal.version,
                    },
                )
            if proposal.status not in required_statuses:
                raise ApiError(
                    409,
                    "RECOVERY_NOT_READY",
                    "Предложение ещё не готово к применению.",
                )
            operation_token = str(uuid4())
            proposal.status = RecoveryProposalStatus.READY_TO_APPLY
            proposal.version += 1
            proposal.changes = {
                **proposal.changes,
                "active_apply": {
                    "token": operation_token,
                    "key": idempotency_key,
                    "expected_version": expected_version,
                    "started_at": utc_now().isoformat(),
                },
            }
        try:
            result_plan_id = await self._apply_plan_recovery(
                session,
                proposal,
                actor,
                actor_subject_id,
                operation_token,
            )
        except ApiError as exc:
            if exc.code == "RECOVERY_APPLY_SUPERSEDED":
                raise
            proposal = await self._require_active_apply(
                session,
                proposal.id,
                operation_token,
            )
            if customer_change and not isinstance(
                proposal.changes.get("owner_reschedule_receipt"), dict
            ):
                await self._restore_local_reschedule(session, proposal)
            proposal.status = RecoveryProposalStatus.FAILED
            proposal.failure_code = exc.code
            if exc.code == "RECOVERY_RESOURCE_CAPACITY_INSUFFICIENT":
                proposal.changes = {
                    **proposal.changes,
                    "unassigned_task_ids": exc.extra["unassigned_task_ids"],
                }
            proposal.version += 1
            self._record_apply_attempt(
                proposal,
                key=idempotency_key,
                expected_version=expected_version,
                outcome="FAILED",
            )
            linked_action = (
                await session.get(LogisticsHumanAction, proposal.action_id)
                if proposal.action_id is not None
                else None
            )
            if linked_action is not None:
                linked_action.status = LogisticsActionStatus.PENDING
                linked_action.resolved_at = None
                linked_action.version += 1
            session.add(
                LogisticsNotice(
                    event_id=proposal.event_id,
                    warehouse_id=proposal.warehouse_id,
                    day=proposal.day,
                    plan_id=proposal.source_plan_id,
                    request_id=(
                        UUID(str(proposal.changes["request_id"]))
                        if isinstance(proposal.changes.get("request_id"), str)
                        else None
                    ),
                    notice_type="RECOVERY_APPLY_FAILED",
                    severity="ERROR",
                    reason_codes=[exc.code],
                    facts={
                        "proposal_id": str(proposal.id),
                        "unassigned_task_ids": proposal.changes.get("unassigned_task_ids", []),
                    },
                    message_ru=(
                        "Договорённость с клиентом обновлена, но новый план ещё не опубликован."
                        if isinstance(proposal.changes.get("owner_reschedule_receipt"), dict)
                        else "Изменение пока не применено; исходный план сохранён."
                    ),
                    recommended_action_ru=(
                        "Нет безопасной замены для части рейсов: откройте затронутые заявки, "
                        "согласуйте перенос с клиентом либо выберите наёмного водителя. "
                        "При поломке неустойка клиента не применяется."
                        if exc.code == "RECOVERY_RESOURCE_CAPACITY_INSUFFICIENT"
                        else "Проверьте актуальность предложения и повторите применение."
                    ),
                    requires_action=True,
                    status=LogisticsNoticeStatus.REQUIRES_ACTION,
                )
            )
            await session.flush()
            await session.refresh(proposal)
            return _proposal_read(proposal)
        proposal = await self._lock_proposal(session, proposal.id)
        if proposal.status == RecoveryProposalStatus.APPLIED:
            return _proposal_read(proposal)
        proposal = await self._require_active_apply(
            session,
            proposal.id,
            operation_token,
        )
        proposal.result_plan_id = result_plan_id
        proposal.status = RecoveryProposalStatus.APPLIED
        proposal.applied_at = utc_now()
        proposal.failure_code = None
        proposal.version += 1
        proposal.changes = {**proposal.changes, "active_apply": None}
        self._record_apply_attempt(
            proposal,
            key=idempotency_key,
            expected_version=expected_version,
            outcome="APPLIED",
        )
        linked_action = (
            await session.get(LogisticsHumanAction, proposal.action_id)
            if proposal.action_id is not None
            else None
        )
        if linked_action is not None:
            linked_action.status = LogisticsActionStatus.RESOLVED
            linked_action.resolved_at = utc_now()
            linked_action.version += 1
        session.add(
            LogisticsNotice(
                event_id=proposal.event_id,
                warehouse_id=proposal.warehouse_id,
                day=proposal.day,
                plan_id=result_plan_id,
                notice_type="RECOVERY_APPLIED",
                severity="INFO",
                reason_codes=["RECOVERY_APPLIED"],
                facts={"proposal_id": str(proposal.id), "applied_by": actor},
                message_ru="Изменение применено. Затронутая логистика пересчитана.",
                requires_action=False,
                status=LogisticsNoticeStatus.COMPLETED,
            )
        )
        await session.flush()
        await session.refresh(proposal)
        return _proposal_read(proposal)

    @staticmethod
    async def _lock_proposal(
        session: AsyncSession,
        proposal_id: UUID,
    ) -> RecoveryProposal:
        """Reload and lock one proposal after a transaction or external-call boundary."""

        proposal = await session.scalar(
            select(RecoveryProposal).where(RecoveryProposal.id == proposal_id).with_for_update()
        )
        if proposal is None:
            raise not_found("recovery_proposal", proposal_id)
        return proposal

    async def _require_active_apply(
        self,
        session: AsyncSession,
        proposal_id: UUID,
        operation_token: str,
    ) -> RecoveryProposal:
        """Fence a resumed phase to the exact durable apply attempt that prepared it."""

        proposal = await self._lock_proposal(session, proposal_id)
        active_apply = proposal.changes.get("active_apply")
        if not isinstance(active_apply, dict) or active_apply.get("token") != operation_token:
            raise ApiError(
                409,
                "RECOVERY_APPLY_SUPERSEDED",
                "Другой повтор применения уже владеет этим предложением.",
            )
        return proposal

    async def _restore_local_reschedule(
        self,
        session: AsyncSession,
        proposal: RecoveryProposal,
    ) -> None:
        """Undo a local-only staged commitment when recovery evaluation fails."""

        before = proposal.changes.get("local_reschedule_before")
        if not isinstance(before, dict) or not isinstance(before.get("request_id"), str):
            return
        request = await self._projection.request(
            session,
            UUID(before["request_id"]),
            for_update=True,
        )
        scheduled_date = before.get("scheduled_date")
        request.scheduled_date = (
            date.fromisoformat(scheduled_date) if isinstance(scheduled_date, str) else None
        )
        request_status = before.get("request_status")
        request_version = before.get("request_version")
        if isinstance(request_status, str):
            request.status = request_status
        if isinstance(request_version, int):
            request.version = request_version
        task_statuses = before.get("task_statuses")
        if isinstance(task_statuses, dict):
            for task in request.tasks:
                status = task_statuses.get(str(task.id))
                if isinstance(status, str):
                    task.status = status

    @staticmethod
    def _record_apply_attempt(
        proposal: RecoveryProposal,
        *,
        key: str,
        expected_version: int,
        outcome: str,
    ) -> None:
        """Keep bounded exact-command receipts without binding every future retry to one key."""

        attempts = [
            item
            for item in proposal.changes.get("apply_attempts", [])
            if isinstance(item, dict) and item.get("key") != key
        ]
        attempts.append(
            {
                "key": key,
                "expected_version": expected_version,
                "result_version": proposal.version,
                "outcome": outcome,
            }
        )
        proposal.changes = {**proposal.changes, "apply_attempts": attempts[-16:]}

    async def create_reschedule_work(
        self,
        session: AsyncSession,
        event: LogisticsEvent,
        request: LogisticsRequest,
        *,
        source_plan: RoutePlan | None,
        cause_ru: str,
    ) -> None:
        """Create one notice/action/proposal from feasible owner and planner dates."""

        request = await self._projection.request(session, request.id)

        rejected_dates = {
            value.isoformat()
            for value in await session.scalars(
                select(LogisticsHumanDecision.selected_date)
                .join(
                    LogisticsHumanAction,
                    LogisticsHumanAction.id == LogisticsHumanDecision.action_id,
                )
                .where(
                    LogisticsHumanAction.request_id == request.id,
                    LogisticsHumanDecision.decision_type == LogisticsDecisionType.REJECT,
                    LogisticsHumanDecision.selected_date.is_not(None),
                )
            )
            if value is not None
        }
        candidate_dates, upstream_error, slot_by_date, owner_context = await self._candidate_dates(
            request
        )
        candidate_dates = tuple(
            item
            for item in candidate_dates
            if item != event.day and item.isoformat() not in rejected_dates
        )
        feasible_dates: tuple[date, ...] = ()
        if candidate_dates:
            try:
                feasible_dates = await self._planner.preview_feasible_request_dates(
                    session,
                    request.id,
                    candidate_dates,
                )
            except ApiError as exc:
                upstream_error = upstream_error or exc.code
        recommendation = feasible_dates[0] if feasible_dates else None
        alternatives = feasible_dates[1:]
        reason_codes = ["DAY_MODE_CONFLICT"]
        if recommendation is None:
            reason_codes.append("NO_FEASIBLE_ALTERNATIVE_DATE")
        if upstream_error is not None:
            reason_codes.append("UPSTREAM_RESCHEDULE_UNAVAILABLE")
        notice = LogisticsNotice(
            event_id=event.id,
            warehouse_id=event.warehouse_id,
            day=event.day,
            plan_id=source_plan.id if source_plan is not None else None,
            request_id=request.id,
            notice_type="CLIENT_COMMITMENT_CONFLICT",
            severity="ERROR" if request.type == "DELIVERY" else "WARNING",
            reason_codes=reason_codes,
            facts={
                "request_type": request.type,
                "current_date": event.day.isoformat(),
                "recommended_date": recommendation.isoformat() if recommendation else None,
                "alternative_dates": [item.isoformat() for item in alternatives],
                "upstream_error": upstream_error,
            },
            message_ru=(
                f"{cause_ru} Задание «{request.name}» несовместимо с новым режимом "
                "и не изменено автоматически."
            ),
            recommended_action_ru=(
                f"Согласуйте с клиентом перенос на {recommendation.strftime('%d.%m.%Y')}."
                if recommendation is not None
                else "Измените ресурсы или ограничения: выполнимую дату пока найти не удалось."
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
            request_id=request.id,
            action_type="AGREE_RESCHEDULE",
            status=LogisticsActionStatus.PENDING,
            version=1,
            customer_name=request.contact_name or request.name,
            customer_type=request.client_type,
            customer_phone=request.contact_phone or None,
            current_date=event.day,
            recommended_date=recommendation,
            alternative_dates=[item.isoformat() for item in alternatives],
            context={
                "cause": cause_ru,
                "slot_by_date": slot_by_date,
                "reschedule_owner": owner_context,
                "upstream_error": upstream_error,
                "rejected_dates": sorted(rejected_dates),
            },
        )
        session.add(action)
        await session.flush()
        task_ids = [str(task.id) for task in request.tasks]
        session.add(
            RecoveryProposal(
                event_id=event.id,
                action_id=action.id,
                warehouse_id=event.warehouse_id,
                day=event.day,
                source_plan_id=source_plan.id if source_plan is not None else None,
                proposal_type="RESCHEDULE_REQUEST",
                status=RecoveryProposalStatus.PROPOSED,
                version=1,
                summary_ru=f"Перенести «{request.name}» после согласования с клиентом.",
                affected_request_ids=[str(request.id)],
                affected_task_ids=task_ids,
                changes={
                    "request_id": str(request.id),
                    "from_date": event.day.isoformat(),
                    "recommended_date": recommendation.isoformat() if recommendation else None,
                    "rejected_dates": sorted(rejected_dates),
                },
                metrics={"customer_commitment_changes": 1},
            )
        )

    async def _candidate_dates(
        self,
        request: LogisticsRequest,
    ) -> tuple[
        tuple[date, ...],
        str | None,
        dict[str, dict[str, object]],
        dict[str, object],
    ]:
        """Load owner-approved alternatives, retaining a safe error when unavailable."""

        upstream_error: str | None = None
        slot_by_date: dict[str, dict[str, object]] = {}
        owner_context: dict[str, object] = {}
        if (
            request.source_system == catalog.RWMS_SOURCE_SYSTEM
            and request.external_id is not None
            and request.external_version is not None
            and self._rwms_client is not None
        ):
            try:
                response = await self._rwms_client.get_reschedule_options(
                    request.external_id,
                    expected_order_version=request.external_version,
                )
                for option in sorted(
                    response.options,
                    key=lambda item: (
                        item.date,
                        item.window_start.isoformat() if item.window_start else "",
                        item.window_end.isoformat() if item.window_end else "",
                        str(item.slot_id),
                    ),
                ):
                    slot_by_date.setdefault(
                        option.date.isoformat(),
                        option.model_dump(mode="json", by_alias=True),
                    )
                owner_context = {
                    "order_id": str(response.order_id),
                    "order_version": response.order_version,
                    "session_id": str(response.session_id),
                    "session_version": response.session_version,
                    "booking_id": str(response.booking_id),
                    "warehouse_id": str(response.warehouse_id),
                    "current_slot": response.current_slot.model_dump(mode="json", by_alias=True),
                }
                candidates = tuple(sorted({option.date for option in response.options}))
                return candidates, None, slot_by_date, owner_context
            except ApiError as exc:
                upstream_error = exc.code
        candidates = tuple(
            sorted(
                {
                    option.date
                    for option in request.date_options
                    if option.date != request.scheduled_date
                }
            )
        )
        return candidates, upstream_error, slot_by_date, owner_context

    async def _apply_reschedule(
        self,
        session: AsyncSession,
        proposal: RecoveryProposal,
        actor_subject_id: UUID,
        *,
        published_withdrawal: RwmsPublishedAssignmentWithdrawal | None,
        operation_token: str,
    ) -> None:
        """Resume the owner-reschedule saga, then refresh the local projection."""

        request_id = UUID(str(proposal.changes["request_id"]))
        request = await self._projection.request(session, request_id, for_update=True)
        await reject_active_request_reschedules(session, (request.id,))
        selected_date_value = proposal.changes.get("selected_date")
        if not isinstance(selected_date_value, str):
            raise ApiError(409, "CUSTOMER_AGREEMENT_MISSING", "Новая дата ещё не согласована.")
        selected_date = date.fromisoformat(selected_date_value)
        if request.source_system == catalog.RWMS_SOURCE_SYSTEM:
            if (
                self._rwms_client is None
                or request.external_id is None
                or request.external_version is None
            ):
                raise ApiError(
                    503,
                    "RWMS_RESCHEDULE_UNAVAILABLE",
                    "Не удалось связаться с владельцем заказа; решение сохранено и не потеряно.",
                )
            selected_slot = proposal.changes.get("selected_slot")
            owner = proposal.changes.get("reschedule_owner")
            if not isinstance(selected_slot, dict) or not isinstance(owner, dict):
                raise ApiError(
                    409,
                    "RWMS_RESCHEDULE_OFFER_EXPIRED",
                    "Предложение владельца заказа больше не актуально; обновите варианты.",
                )
            slot_id = selected_slot.get("slotId")
            slot_version = selected_slot.get("slotVersion")
            session_version = owner.get("session_version")
            decision_code = proposal.changes.get("decision_code")
            decision_reason = proposal.changes.get("decision_reason")
            if (
                not isinstance(slot_id, str)
                or not isinstance(slot_version, int)
                or not isinstance(session_version, int)
                or decision_code
                not in {
                    "CUSTOMER_AGREED_RECOMMENDED",
                    "CUSTOMER_AGREED_ALTERNATIVE",
                }
                or not isinstance(decision_reason, str)
            ):
                raise ApiError(
                    409,
                    "RWMS_RESCHEDULE_FENCE_MISSING",
                    "Данные согласованного слота устарели; получите новые варианты.",
                )
            receipt = proposal.changes.get("owner_reschedule_receipt")
            if isinstance(receipt, dict):
                stored_withdrawal = receipt.get("published_plan_withdrawal")
                if (
                    receipt.get("order_id") != str(request.external_id)
                    or receipt.get("selected_date") != selected_date.isoformat()
                    or not isinstance(receipt.get("order_version"), int)
                    or (published_withdrawal is None and stored_withdrawal is not None)
                    or (
                        published_withdrawal is not None
                        and (
                            not isinstance(stored_withdrawal, dict)
                            or stored_withdrawal.get("sourcePlanId")
                            != str(published_withdrawal.source_plan_id)
                            or stored_withdrawal.get("sourcePlanVersion")
                            != published_withdrawal.replacement_plan_version
                            or stored_withdrawal.get("removedExternalTaskId")
                            != str(published_withdrawal.removed_assignment.external_task_id)
                        )
                    )
                ):
                    raise ApiError(
                        409,
                        "RWMS_RESCHEDULE_RECEIPT_CONFLICT",
                        "Сохранённый результат согласования относится к другой версии заказа.",
                    )
                owner_order_version = int(receipt["order_version"])
            else:
                prepared = proposal.changes.get("prepared_recovery")
                prepared_key = (
                    prepared.get("owner_idempotency_key") if isinstance(prepared, dict) else None
                )
                owner_idempotency_key = (
                    prepared_key
                    if isinstance(prepared_key, str)
                    else str(uuid5(NAMESPACE_URL, f"rwms-reschedule:{proposal.id}"))
                )
                command = RwmsRescheduleCommand(
                    expected_order_version=request.external_version,
                    expected_session_version=session_version,
                    slot_id=UUID(slot_id),
                    slot_version=slot_version,
                    decision_code=decision_code,
                    decision_actor_subject_id=actor_subject_id,
                    decision_reason=decision_reason,
                    published_plan_withdrawal=published_withdrawal,
                )
                request_version_before = request.version
                request_status_before = request.status
                order_id = request.external_id
                await session.commit()
                result = await self._rwms_client.reschedule_order(
                    order_id,
                    command,
                    idempotency_key=owner_idempotency_key,
                )
                if result.confirmed_slot.date != selected_date:
                    raise ApiError(
                        502,
                        "RWMS_RESCHEDULE_RESPONSE_INVALID",
                        "Владелец заказа подтвердил другую дату; локальный план не изменён.",
                    )
                if published_withdrawal is not None:
                    withdrawal_result = result.published_plan_withdrawal
                    if (
                        withdrawal_result is None
                        or withdrawal_result.source_plan_id != published_withdrawal.source_plan_id
                        or withdrawal_result.source_plan_version
                        != published_withdrawal.replacement_plan_version
                        or withdrawal_result.removed_external_task_id
                        != published_withdrawal.removed_assignment.external_task_id
                        or withdrawal_result.state != "COMPLETE"
                    ):
                        raise ApiError(
                            502,
                            "RWMS_RESCHEDULE_RESPONSE_INVALID",
                            "Владелец заказа не подтвердил удаление старого задания.",
                        )
                elif result.published_plan_withdrawal is not None:
                    raise ApiError(
                        502,
                        "RWMS_RESCHEDULE_RESPONSE_INVALID",
                        "Владелец заказа вернул неподходящую ревизию старого плана.",
                    )
                owner_order_version = result.order_version
                proposal = await self._require_active_apply(
                    session,
                    proposal.id,
                    operation_token,
                )
                if isinstance(prepared, dict):
                    self._assert_same_prepared_recovery(proposal, prepared)
                proposal.changes = {
                    **proposal.changes,
                    "owner_reschedule_receipt": {
                        "owner_idempotency_key": owner_idempotency_key,
                        "order_id": str(result.order_id),
                        "order_version": result.order_version,
                        "session_id": str(result.session_id),
                        "session_version": result.session_version,
                        "booking_id": str(result.booking_id),
                        "warehouse_id": str(result.warehouse_id),
                        "selected_date": result.confirmed_slot.date.isoformat(),
                        "confirmed_slot": result.confirmed_slot.model_dump(
                            mode="json", by_alias=True
                        ),
                        "published_plan_withdrawal": (
                            result.published_plan_withdrawal.model_dump(mode="json", by_alias=True)
                            if result.published_plan_withdrawal is not None
                            else None
                        ),
                        "local_request_version_before": request_version_before,
                        "local_request_status_before": str(request_status_before),
                    },
                }
                await session.commit()
                request = await self._projection.request(
                    session,
                    request_id,
                    for_update=True,
                )
                if (
                    request.version != request_version_before
                    or request.status != request_status_before
                ):
                    raise ApiError(
                        409,
                        "RWMS_RESCHEDULE_LOCAL_VERSION_CHANGED",
                        "Владелец заказа применил перенос, но локальная заявка изменилась; "
                        "требуется безопасный повтор синхронизации.",
                    )
            warehouse = await session.get(Warehouse, request.warehouse_id)
            if warehouse is None or warehouse.external_warehouse_id is None:
                raise ApiError(
                    409,
                    "RWMS_WAREHOUSE_NOT_LINKED",
                    "Склад заказа больше не связан с RWMS; решение сохранено и не потеряно.",
                )
            external_warehouse_id = warehouse.external_warehouse_id
            local_warehouse_id = request.warehouse_id
            if request.external_id is None:
                raise ApiError(
                    409,
                    "RWMS_RESCHEDULE_LOCAL_IDENTITY_CHANGED",
                    "Локальная заявка потеряла идентификатор владельца после переноса.",
                )
            order_id = request.external_id
            refresh_request_version = request.version
            refresh_request_status = request.status
            await session.commit()
            feed = await self._rwms_client.get_planning_requests(
                warehouse_id=external_warehouse_id,
                date_from=selected_date,
                date_to=selected_date,
            )
            proposal = await self._require_active_apply(
                session,
                proposal.id,
                operation_token,
            )
            request = await self._projection.request(
                session,
                request_id,
                for_update=True,
            )
            warehouse = await session.get(Warehouse, request.warehouse_id)
            if (
                request.version != refresh_request_version
                or request.status != refresh_request_status
                or request.warehouse_id != local_warehouse_id
                or warehouse is None
                or warehouse.external_warehouse_id != external_warehouse_id
            ):
                raise ApiError(
                    409,
                    "RWMS_RESCHEDULE_LOCAL_VERSION_CHANGED",
                    "Локальная заявка изменилась во время обновления после переноса.",
                )
            source = next(
                (item for item in feed.requests if item.order_id == order_id),
                None,
            )
            if source is None or source.order_version != owner_order_version:
                raise ApiError(
                    502,
                    "RWMS_RESCHEDULE_REFRESH_MISSING",
                    "Заказ изменён, но обновлённые данные пока не получены; повторите позже.",
                )
            await catalog.upsert_rwms_request(session, local_warehouse_id, source)
            return
        option = next((item for item in request.date_options if item.date == selected_date), None)
        if option is None:
            raise ApiError(
                422, "RESCHEDULE_DATE_NOT_ALLOWED", "Дата отсутствует в допустимых вариантах."
            )
        proposal.changes = {
            **proposal.changes,
            "local_reschedule_before": {
                "request_id": str(request.id),
                "scheduled_date": (
                    request.scheduled_date.isoformat()
                    if request.scheduled_date is not None
                    else None
                ),
                "request_status": str(request.status),
                "request_version": request.version,
                "task_statuses": {str(task.id): str(task.status) for task in request.tasks},
            },
        }
        request.scheduled_date = selected_date
        request.status = RequestStatus.READY
        request.version += 1
        for task in request.tasks:
            task.status = TaskStatus.READY

    async def _apply_plan_recovery(
        self,
        session: AsyncSession,
        proposal: RecoveryProposal,
        actor: str,
        actor_subject_id: UUID,
        operation_token: str,
    ) -> UUID | None:
        """Prepare one hidden revision, converge its owner effect, then expose it locally."""

        if proposal.source_plan_id is None:
            if proposal.proposal_type == "RESCHEDULE_REQUEST":
                await self._apply_reschedule(
                    session,
                    proposal,
                    actor_subject_id,
                    published_withdrawal=None,
                    operation_token=operation_token,
                )
            if proposal.proposal_type == "PARTIAL_REPLAN_CANCELLATION":
                cancelled_request = await self._verify_authoritative_cancellation(
                    session,
                    proposal,
                    operation_token=operation_token,
                )
                self._mark_request_cancelled(cancelled_request)
            return None
        prepared = proposal.changes.get("prepared_recovery")
        if not isinstance(prepared, dict):
            prepared = await self._prepare_plan_recovery(
                session,
                proposal,
                actor,
                operation_token,
            )
            proposal = await self._require_active_apply(
                session,
                proposal.id,
                operation_token,
            )
            proposal.changes = {**proposal.changes, "prepared_recovery": prepared}
            await session.commit()
        prepared = self._validate_prepared_recovery(proposal, prepared)

        owner_operation = prepared["owner_operation"]
        if owner_operation == "REPLACE":
            if self._rwms_client is None:
                raise ApiError(
                    503,
                    "RWMS_ASSIGNMENT_STATUS_UNAVAILABLE",
                    "Не удалось применить опубликованную ревизию у владельца заданий.",
                )
            command_payload = prepared.get("replacement_command")
            if not isinstance(command_payload, dict):
                raise ApiError(
                    409,
                    "RECOVERY_PREPARED_COMMAND_INVALID",
                    "Подготовленная команда замены повреждена; требуется повторный анализ.",
                )
            replacement = RwmsReplacePlanningAssignmentsCommand.model_validate(command_payload)
            receipt = proposal.changes.get("owner_replacement_receipt")
            if not isinstance(receipt, dict):
                await session.commit()
                replaced = await self._rwms_client.replace_assignments(
                    UUID(str(prepared["source_plan_id"])),
                    replacement,
                    idempotency_key=UUID(str(prepared["owner_idempotency_key"])),
                )
                proposal = await self._require_active_apply(
                    session,
                    proposal.id,
                    operation_token,
                )
                self._assert_same_prepared_recovery(proposal, prepared)
                proposal.changes = {
                    **proposal.changes,
                    "owner_replacement_receipt": replaced.model_dump(mode="json", by_alias=True),
                }
                await session.commit()
        elif owner_operation == "WITHDRAW_CANCELLATION":
            if self._rwms_client is None:
                raise ApiError(
                    503,
                    "RWMS_CANCELLATION_STATUS_UNAVAILABLE",
                    "Не удалось применить отмену в опубликованном плане владельца.",
                )
            withdrawal_payload = prepared.get("published_withdrawal")
            if not isinstance(withdrawal_payload, dict):
                raise ApiError(
                    409,
                    "RECOVERY_PREPARED_COMMAND_INVALID",
                    "Подготовленная команда отмены повреждена; требуется повторный анализ.",
                )
            withdrawal = RwmsPublishedAssignmentWithdrawal.model_validate(withdrawal_payload)
            receipt_payload = proposal.changes.get("owner_cancellation_withdrawal_receipt")
            if isinstance(receipt_payload, dict):
                self._require_cancellation_withdrawal_receipt(
                    receipt_payload,
                    withdrawal,
                )
            else:
                try:
                    owner_idempotency_key = UUID(str(prepared["owner_idempotency_key"]))
                except (KeyError, TypeError, ValueError) as exc:
                    raise ApiError(
                        409,
                        "RECOVERY_PREPARED_COMMAND_INVALID",
                        "Подготовленная команда отмены не содержит ключ повтора.",
                    ) from exc
                await session.commit()
                withdrawn = await self._rwms_client.withdraw_cancelled_assignment(
                    UUID(str(prepared["source_plan_id"])),
                    withdrawal,
                    idempotency_key=owner_idempotency_key,
                )
                self._require_cancellation_withdrawal_receipt(
                    withdrawn.model_dump(mode="json", by_alias=True),
                    withdrawal,
                )
                proposal = await self._require_active_apply(
                    session,
                    proposal.id,
                    operation_token,
                )
                self._assert_same_prepared_recovery(proposal, prepared)
                proposal.changes = {
                    **proposal.changes,
                    "owner_cancellation_withdrawal_receipt": withdrawn.model_dump(
                        mode="json", by_alias=True
                    ),
                }
                await session.commit()
        elif owner_operation in {"RESCHEDULE", "LOCAL_RESCHEDULE"}:
            withdrawal_payload = prepared.get("published_withdrawal")
            reschedule_withdrawal = (
                RwmsPublishedAssignmentWithdrawal.model_validate(withdrawal_payload)
                if isinstance(withdrawal_payload, dict)
                else None
            )
            await self._apply_reschedule(
                session,
                proposal,
                actor_subject_id,
                published_withdrawal=reschedule_withdrawal,
                operation_token=operation_token,
            )
        elif owner_operation != "NONE":
            raise ApiError(
                409,
                "RECOVERY_PREPARED_COMMAND_INVALID",
                "Подготовленное действие владельца имеет неизвестный тип.",
            )

        proposal = await self._require_active_apply(
            session,
            proposal.id,
            operation_token,
        )
        self._assert_same_prepared_recovery(proposal, prepared)
        activated = await plan_service.activate_staged_recovery_plan(
            session,
            source_plan_id=UUID(str(prepared["source_plan_id"])),
            expected_source_version=self._prepared_positive_int(
                prepared,
                "source_plan_version",
            ),
            result_plan_id=UUID(str(prepared["result_plan_id"])),
            expected_result_version=self._prepared_positive_int(
                prepared,
                "result_plan_version",
            ),
        )
        cancelled_request_id = prepared.get("cancelled_request_id")
        if isinstance(cancelled_request_id, str):
            cancelled_request = await self._projection.request(
                session,
                UUID(cancelled_request_id),
                for_update=True,
            )
            await reject_active_request_reschedules(session, (cancelled_request.id,))
            expected_request_version = prepared.get("cancelled_request_version")
            if (
                not isinstance(expected_request_version, int)
                or cancelled_request.version != expected_request_version
            ):
                raise ApiError(
                    409,
                    "RWMS_CANCELLATION_STATE_CHANGED",
                    "Локальная версия отменённого задания изменилась до применения плана.",
                )
            self._mark_request_cancelled(cancelled_request)
        return activated.id

    async def _prepare_plan_recovery(
        self,
        session: AsyncSession,
        proposal: RecoveryProposal,
        actor: str,
        operation_token: str,
    ) -> dict[str, object]:
        """Solve and freeze one hidden revision before any owner-side mutation."""

        cancelled_request: LogisticsRequest | None = None
        if proposal.proposal_type == "PARTIAL_REPLAN_CANCELLATION":
            cancelled_request = await self._verify_authoritative_cancellation(
                session,
                proposal,
                operation_token=operation_token,
            )
            proposal = await self._require_active_apply(
                session,
                proposal.id,
                operation_token,
            )
        assert proposal.source_plan_id is not None
        source = await plan_service.get_plan(
            session,
            proposal.source_plan_id,
            for_update=True,
        )
        source_version = source.version
        owner_membership, owner_source_version = await self._owner_membership(
            session,
            source,
            proposal_id=proposal.id,
            operation_token=operation_token,
        )
        proposal = await self._require_active_apply(
            session,
            proposal.id,
            operation_token,
        )
        source = await plan_service.get_plan(session, source.id, for_update=True)
        if source.version != source_version:
            raise ApiError(
                409,
                "PLAN_VERSION_CONFLICT",
                "Исходный план изменился во время подготовки восстановления.",
            )
        request_date_overrides: dict[str, str] = {}
        if proposal.proposal_type == "RESCHEDULE_REQUEST":
            request_id = proposal.changes.get("request_id")
            selected_date = proposal.changes.get("selected_date")
            if not isinstance(request_id, str) or not isinstance(selected_date, str):
                raise ApiError(
                    409,
                    "CUSTOMER_AGREEMENT_MISSING",
                    "Новая дата ещё не согласована.",
                )
            request_date_overrides[request_id] = selected_date
        run = await self._planner.reoptimize_recovery(
            session,
            source.id,
            ManualChangeCommand(
                expected_version=source.version,
                change_type="DYNAMIC_RECOVERY",
                payload={
                    "locked_cycle_ids": proposal.changes.get("locked_cycle_ids", []),
                    "recovery_task_ids": proposal.changes.get("recovery_task_ids", []),
                    "driver_shift_ids": proposal.changes.get("driver_shift_ids", []),
                    "delay_minutes": proposal.changes.get("delay_minutes"),
                    "effective_at": proposal.changes.get("effective_at"),
                    "request_date_overrides": request_date_overrides,
                },
                reason=proposal.summary_ru,
                changed_by=actor,
            ),
        )
        if run.plan_id is None or run.status not in {"COMPLETED", "TIMED_OUT"}:
            raise ApiError(
                422,
                "RECOVERY_PLAN_FAILED",
                "Не удалось построить выполнимый вариант восстановления.",
                extra={"optimizer_status": str(run.status)},
            )
        proposal = await self._require_active_apply(
            session,
            proposal.id,
            operation_token,
        )
        result_plan = await plan_service.get_plan(session, run.plan_id, for_update=True)
        if proposal.proposal_type == "PARTIAL_REPLAN_RESOURCE_LOSS":
            restored_ids = {
                str(stop.task_id)
                for cycle in result_plan.cycles
                for stop in cycle.stops
                if stop.task_id is not None
            }
            missing_ids = sorted(set(proposal.affected_task_ids) - restored_ids)
            if missing_ids:
                raise ApiError(
                    422,
                    "RECOVERY_RESOURCE_CAPACITY_INSUFFICIENT",
                    "Для части рейсов нет безопасной замены. Согласуйте перенос или наёмника.",
                    extra={"unassigned_task_ids": missing_ids},
                )
        prepared: dict[str, object] = {
            "source_plan_id": str(source.id),
            "source_plan_version": source_version,
            "result_plan_id": str(result_plan.id),
            "result_plan_version": result_plan.version,
            "owner_operation": "NONE",
            "owner_idempotency_key": None,
            "replacement_command": None,
            "published_withdrawal": None,
            "cancelled_request_id": (
                str(cancelled_request.id) if cancelled_request is not None else None
            ),
            "cancelled_request_version": (
                cancelled_request.version if cancelled_request is not None else None
            ),
        }
        if proposal.proposal_type == "RESCHEDULE_REQUEST":
            withdrawal: RwmsPublishedAssignmentWithdrawal | None = None
            if owner_membership:
                if owner_source_version is None or self._rwms_client is None:
                    raise ApiError(
                        503,
                        "RWMS_ASSIGNMENT_STATUS_UNAVAILABLE",
                        "Не удалось зафиксировать опубликованный состав плана.",
                    )
                request_id = UUID(str(proposal.changes["request_id"]))
                source_index = build_plan_status_task_index(source)
                removed = [
                    status
                    for status in owner_membership
                    if source_index[(status.order_id, tuple(status.unit_ids))][1].id == request_id
                ]
                if len(removed) != 1:
                    raise ApiError(
                        409,
                        "RWMS_PUBLISHED_RESCHEDULE_MEMBERSHIP_INVALID",
                        "Перенос требует ровно одного опубликованного задания заказа.",
                    )
                removed_status = removed[0]
                replacement_version = owner_source_version + 1
                remaining, shifts = build_replacement_membership(
                    result_plan,
                    owner_membership,
                    source_plan_id=source.id,
                    expected_source_plan_version=owner_source_version,
                    replacement_plan_version=replacement_version,
                    excluded_external_task_ids=frozenset({removed_status.external_task_id}),
                )
                local_request = source_index[
                    (removed_status.order_id, tuple(removed_status.unit_ids))
                ][1]
                service_warehouse = await session.get(
                    Warehouse,
                    local_request.warehouse_id,
                )
                root_warehouse_id = source.warehouse.external_warehouse_id
                if (
                    service_warehouse is None
                    or service_warehouse.external_warehouse_id is None
                    or root_warehouse_id is None
                ):
                    raise ApiError(
                        409,
                        "RWMS_WAREHOUSE_NOT_LINKED",
                        "Склад переносимого задания больше не связан с владельцем.",
                    )
                withdrawal = RwmsPublishedAssignmentWithdrawal(
                    source_plan_id=source.id,
                    expected_source_plan_version=owner_source_version,
                    replacement_plan_version=replacement_version,
                    warehouse_id=root_warehouse_id,
                    date=source.date,
                    removed_assignment=RwmsPublishedAssignmentRemoval(
                        document_id=removed_status.document_id,
                        external_task_id=removed_status.external_task_id,
                        expected_task_version=removed_status.task_version,
                        service_warehouse_id=service_warehouse.external_warehouse_id,
                        scheduled_date=source.date,
                        unit_ids=list(removed_status.unit_ids),
                    ),
                    remaining_assignments=remaining,
                    driver_shift_plans=shifts,
                )
            request = await self._projection.request(
                session,
                UUID(str(proposal.changes["request_id"])),
            )
            prepared["owner_operation"] = (
                "RESCHEDULE"
                if request.source_system == catalog.RWMS_SOURCE_SYSTEM
                else "LOCAL_RESCHEDULE"
            )
            prepared["owner_idempotency_key"] = str(
                uuid5(NAMESPACE_URL, f"rwms-reschedule:{proposal.id}")
            )
            prepared["published_withdrawal"] = (
                withdrawal.model_dump(mode="json", by_alias=True)
                if withdrawal is not None
                else None
            )
        elif proposal.proposal_type == "PARTIAL_REPLAN_CANCELLATION" and owner_membership:
            if (
                owner_source_version is None
                or self._rwms_client is None
                or cancelled_request is None
            ):
                raise ApiError(
                    503,
                    "RWMS_ASSIGNMENT_STATUS_UNAVAILABLE",
                    "Не удалось зафиксировать опубликованный состав отменяемого плана.",
                )
            source_index = build_plan_status_task_index(source)
            removed = [
                status
                for status in owner_membership
                if source_index[(status.order_id, tuple(status.unit_ids))][1].id
                == cancelled_request.id
            ]
            if len(removed) != 1 or removed[0].task_state != "CANCELLED":
                raise ApiError(
                    409,
                    "RWMS_PUBLISHED_CANCELLATION_MEMBERSHIP_INVALID",
                    "Отмена требует ровно одного подтверждённого владельцем задания.",
                )
            removed_status = removed[0]
            removed_local_task = source_index[
                (removed_status.order_id, tuple(removed_status.unit_ids))
            ][0]
            cancellation_snapshot = proposal.changes.get("owner_cancellation_snapshot")
            expected_removed = (
                cancellation_snapshot.get(str(removed_local_task.id))
                if isinstance(cancellation_snapshot, dict)
                else None
            )
            if (
                not isinstance(expected_removed, dict)
                or expected_removed.get("external_task_id") != str(removed_status.external_task_id)
                or expected_removed.get("task_version") != removed_status.task_version
                or expected_removed.get("task_state") != "CANCELLED"
            ):
                raise ApiError(
                    409,
                    "RWMS_CANCELLATION_STATE_CHANGED",
                    "Версия отменённого задания изменилась во время подготовки.",
                )
            replacement_version = owner_source_version + 1
            remaining, shifts = build_replacement_membership(
                result_plan,
                owner_membership,
                source_plan_id=source.id,
                expected_source_plan_version=owner_source_version,
                replacement_plan_version=replacement_version,
                excluded_external_task_ids=frozenset({removed_status.external_task_id}),
            )
            service_warehouse = await session.get(
                Warehouse,
                cancelled_request.warehouse_id,
            )
            root_warehouse_id = source.warehouse.external_warehouse_id
            if (
                service_warehouse is None
                or service_warehouse.external_warehouse_id is None
                or root_warehouse_id is None
            ):
                raise ApiError(
                    409,
                    "RWMS_WAREHOUSE_NOT_LINKED",
                    "Склад отменённого задания больше не связан с владельцем.",
                )
            withdrawal = RwmsPublishedAssignmentWithdrawal(
                source_plan_id=source.id,
                expected_source_plan_version=owner_source_version,
                replacement_plan_version=replacement_version,
                warehouse_id=root_warehouse_id,
                date=source.date,
                removed_assignment=RwmsPublishedAssignmentRemoval(
                    document_id=removed_status.document_id,
                    external_task_id=removed_status.external_task_id,
                    expected_task_version=removed_status.task_version,
                    service_warehouse_id=service_warehouse.external_warehouse_id,
                    scheduled_date=source.date,
                    unit_ids=list(removed_status.unit_ids),
                ),
                remaining_assignments=remaining,
                driver_shift_plans=shifts,
            )
            prepared["owner_operation"] = "WITHDRAW_CANCELLATION"
            prepared["owner_idempotency_key"] = str(
                uuid5(
                    NAMESPACE_URL,
                    f"rwms-cancellation-withdrawal:{proposal.id}:{source.id}:v{replacement_version}",
                )
            )
            prepared["published_withdrawal"] = withdrawal.model_dump(
                mode="json",
                by_alias=True,
            )
        elif owner_membership:
            if owner_source_version is None or self._rwms_client is None:
                raise ApiError(
                    503,
                    "RWMS_ASSIGNMENT_STATUS_UNAVAILABLE",
                    "Не удалось зафиксировать опубликованный состав плана.",
                )
            replacement_version = owner_source_version + 1
            replacement = build_assignment_replacement_command(
                result_plan,
                owner_membership,
                source_plan_id=source.id,
                expected_source_plan_version=owner_source_version,
                replacement_plan_version=replacement_version,
            )
            prepared["owner_operation"] = "REPLACE"
            prepared["owner_idempotency_key"] = str(
                uuid5(
                    NAMESPACE_URL,
                    f"rwms-recovery-replacement:{proposal.id}:{source.id}:v{replacement_version}",
                )
            )
            prepared["replacement_command"] = replacement.model_dump(
                mode="json",
                by_alias=True,
            )
        return prepared

    @staticmethod
    def _validate_prepared_recovery(
        proposal: RecoveryProposal,
        prepared: dict[str, object],
    ) -> dict[str, object]:
        """Reject a partial or cross-proposal prepared recovery receipt."""

        try:
            source_plan_id = UUID(str(prepared["source_plan_id"]))
            UUID(str(prepared["result_plan_id"]))
        except (KeyError, TypeError, ValueError) as exc:
            raise ApiError(
                409,
                "RECOVERY_PREPARED_COMMAND_INVALID",
                "Подготовленная ревизия повреждена; требуется повторный анализ.",
            ) from exc
        source_version = RecoveryProposalWorkflow._prepared_positive_int(
            prepared,
            "source_plan_version",
        )
        result_version = RecoveryProposalWorkflow._prepared_positive_int(
            prepared,
            "result_plan_version",
        )
        if (
            proposal.source_plan_id != source_plan_id
            or source_version < 1
            or result_version < 1
            or prepared.get("owner_operation")
            not in {
                "NONE",
                "REPLACE",
                "RESCHEDULE",
                "LOCAL_RESCHEDULE",
                "WITHDRAW_CANCELLATION",
            }
        ):
            raise ApiError(
                409,
                "RECOVERY_PREPARED_COMMAND_INVALID",
                "Подготовленная ревизия не соответствует предложению.",
            )
        return prepared

    @staticmethod
    def _require_cancellation_withdrawal_receipt(
        payload: dict[str, object],
        withdrawal: RwmsPublishedAssignmentWithdrawal,
    ) -> RwmsPublishedAssignmentWithdrawalResult:
        """Validate a durable owner receipt before exposing the hidden local revision."""

        try:
            result = RwmsPublishedAssignmentWithdrawalResult.model_validate(payload)
        except ValueError as exc:
            raise ApiError(
                409,
                "RWMS_CANCELLATION_WITHDRAWAL_RECEIPT_CONFLICT",
                "Сохранённый результат отмены повреждён; локальный план не изменён.",
            ) from exc
        if (
            result.source_plan_id != withdrawal.source_plan_id
            or result.source_plan_version != withdrawal.replacement_plan_version
            or result.removed_external_task_id != withdrawal.removed_assignment.external_task_id
            or result.state != "COMPLETE"
        ):
            raise ApiError(
                409,
                "RWMS_CANCELLATION_WITHDRAWAL_RECEIPT_CONFLICT",
                "Сохранённый результат отмены относится к другой ревизии плана.",
            )
        return result

    @staticmethod
    def _prepared_positive_int(
        prepared: dict[str, object],
        field: str,
    ) -> int:
        """Read one exact positive integer from a durable prepared payload."""

        value = prepared.get(field)
        if isinstance(value, bool) or not isinstance(value, int) or value < 1:
            raise ApiError(
                409,
                "RECOVERY_PREPARED_COMMAND_INVALID",
                "Подготовленная ревизия содержит недопустимую версию.",
            )
        return value

    @staticmethod
    def _assert_same_prepared_recovery(
        proposal: RecoveryProposal,
        prepared: dict[str, object],
    ) -> None:
        """Recheck the immutable prepared payload after every remote response."""

        if proposal.changes.get("prepared_recovery") != prepared:
            raise ApiError(
                409,
                "RECOVERY_PREPARED_COMMAND_CHANGED",
                "Подготовленная ревизия была заменена во время внешнего вызова.",
            )

    async def _verify_authoritative_cancellation(
        self,
        session: AsyncSession,
        proposal: RecoveryProposal,
        *,
        operation_token: str,
    ) -> LogisticsRequest:
        """Re-read and fence owner cancellation before changing the local projection."""

        raw_request_id = proposal.changes.get("cancel_request_id")
        if not isinstance(raw_request_id, str):
            raise ApiError(
                409,
                "CANCELLATION_PROPOSAL_INVALID",
                "Предложение отмены не содержит точного задания.",
            )
        request = await self._projection.request(
            session,
            UUID(raw_request_id),
            for_update=True,
        )
        await reject_active_request_reschedules(session, (request.id,))
        if request.source_system != catalog.RWMS_SOURCE_SYSTEM:
            return request
        if self._rwms_client is None or proposal.source_plan_id is None:
            raise ApiError(
                503,
                "RWMS_CANCELLATION_STATUS_UNAVAILABLE",
                "Не удалось повторно проверить отмену у владельца заказа.",
            )
        source = await plan_service.get_plan(session, proposal.source_plan_id)
        service_warehouse = await session.get(Warehouse, request.warehouse_id)
        if service_warehouse is None or service_warehouse.external_warehouse_id is None:
            raise ApiError(
                409,
                "RWMS_WAREHOUSE_NOT_LINKED",
                "Склад заказа больше не связан с владельцем задания.",
            )
        expected_request_version = request.version
        expected_request_status = request.status
        expected_source_version = source.version
        expected_source_status = source.status
        external_warehouse_id = service_warehouse.external_warehouse_id
        await session.commit()
        feed = await self._rwms_client.get_assignment_statuses(
            warehouse_id=external_warehouse_id,
            date=proposal.day,
        )
        if feed.warehouse_id != external_warehouse_id or feed.date != proposal.day:
            raise ApiError(
                502,
                "RWMS_ASSIGNMENT_STATUS_SCOPE_MISMATCH",
                "Владелец заказа вернул состояния другого склада или дня.",
            )
        await self._require_active_apply(
            session,
            proposal.id,
            operation_token,
        )
        request = await self._projection.request(
            session,
            UUID(raw_request_id),
            for_update=True,
        )
        await reject_active_request_reschedules(session, (request.id,))
        current_source = await plan_service.get_plan(
            session,
            proposal.source_plan_id,
            for_update=True,
        )
        if (
            request.version != expected_request_version
            or request.status != expected_request_status
            or current_source.version != expected_source_version
            or current_source.status != expected_source_status
        ):
            raise ApiError(
                409,
                "RWMS_CANCELLATION_STATE_CHANGED",
                "План или локальная заявка изменились во время проверки отмены.",
            )
        task_index = build_plan_status_task_index(source)
        current_by_task_id = {}
        for status in feed.assignments:
            local = task_index.get((status.order_id, tuple(status.unit_ids)))
            if local is None or local[1].id != request.id:
                continue
            current_by_task_id[local[0].id] = status
        expected = proposal.changes.get("owner_cancellation_snapshot")
        if not isinstance(expected, dict) or set(expected) != {
            str(task.id) for task in request.tasks
        }:
            raise ApiError(
                409,
                "RWMS_CANCELLATION_FENCE_MISSING",
                "Не сохранена точная ревизия подтверждённой отмены.",
            )
        for task in request.tasks:
            current = current_by_task_id.get(task.id)
            snapshot = expected.get(str(task.id))
            if current is None or not isinstance(snapshot, dict):
                raise ApiError(
                    409,
                    "RWMS_CANCELLATION_STATE_CHANGED",
                    "Состояние отменённого задания изменилось; выполните анализ заново.",
                )
            if current.task_state == "COMPLETED":
                raise ApiError(
                    409,
                    "REQUEST_ALREADY_EXECUTED",
                    "Задание уже выполнено владельцем исполнения.",
                )
            if (
                current.task_state != "CANCELLED"
                or str(current.external_task_id) != snapshot.get("external_task_id")
                or current.task_version != snapshot.get("task_version")
            ):
                raise ApiError(
                    409,
                    "RWMS_CANCELLATION_STATE_CHANGED",
                    "Состояние отменённого задания изменилось; выполните анализ заново.",
                )
        return request

    @staticmethod
    def _mark_request_cancelled(request: LogisticsRequest) -> None:
        """Advance the local projection only after owner verification and successful recovery."""

        if request.status != RequestStatus.CANCELLED:
            request.status = RequestStatus.CANCELLED
            request.version += 1
        for task in request.tasks:
            task.status = TaskStatus.CANCELLED

    async def _owner_membership(
        self,
        session: AsyncSession,
        source: RoutePlan,
        *,
        proposal_id: UUID,
        operation_token: str,
    ) -> tuple[tuple[RwmsPlanningAssignmentStatus, ...], int | None]:
        """Read the exact published lineage across every service warehouse in the source plan."""

        task_index = build_plan_status_task_index(source)
        if not task_index:
            return (), None
        if self._rwms_client is None:
            raise ApiError(
                503,
                "RWMS_ASSIGNMENT_STATUS_UNAVAILABLE",
                "Не удалось проверить опубликованный состав плана у владельца заданий.",
            )
        service_warehouse_ids = {request.warehouse_id for _, request in task_index.values()}
        warehouses = tuple(
            await session.scalars(select(Warehouse).where(Warehouse.id.in_(service_warehouse_ids)))
        )
        warehouse_by_id = {warehouse.id: warehouse for warehouse in warehouses}
        if service_warehouse_ids != set(warehouse_by_id) or any(
            warehouse.external_warehouse_id is None for warehouse in warehouses
        ):
            raise ApiError(
                409,
                "RWMS_WAREHOUSE_NOT_LINKED",
                "Один из складов плана больше не связан с владельцем заданий.",
            )
        expected_source_version = source.version
        expected_source_status = source.status
        await session.commit()
        matching: dict[tuple[UUID, tuple[UUID, ...]], RwmsPlanningAssignmentStatus] = {}
        for warehouse in sorted(warehouses, key=lambda item: str(item.id)):
            external_warehouse_id = warehouse.external_warehouse_id
            if external_warehouse_id is None:  # guarded above for type narrowing
                continue
            feed = await self._rwms_client.get_assignment_statuses(
                warehouse_id=external_warehouse_id,
                date=source.date,
            )
            if feed.warehouse_id != external_warehouse_id or feed.date != source.date:
                raise ApiError(
                    502,
                    "RWMS_ASSIGNMENT_STATUS_SCOPE_MISMATCH",
                    "Владелец заказа вернул состояния другого склада или дня.",
                )
            for status in feed.assignments:
                key = (status.order_id, tuple(status.unit_ids))
                local = task_index.get(key)
                belongs_to_source = status.source_plan_id == source.id
                if local is None:
                    if belongs_to_source:
                        raise ApiError(
                            409,
                            "RWMS_REPLACEMENT_MEMBERSHIP_CHANGED",
                            "Опубликованный состав плана не совпадает с локальной ревизией.",
                        )
                    continue
                if local[1].warehouse_id != warehouse.id:
                    raise ApiError(
                        502,
                        "RWMS_ASSIGNMENT_STATUS_SCOPE_MISMATCH",
                        "Владелец заказа вернул задание другого склада группы.",
                    )
                if not belongs_to_source:
                    if status.source_plan_id is not None:
                        raise ApiError(
                            409,
                            "RWMS_REPLACEMENT_SOURCE_CHANGED",
                            "Задание уже принадлежит другой опубликованной ревизии.",
                        )
                    continue
                if key in matching:
                    raise ApiError(
                        502,
                        "RWMS_ASSIGNMENT_STATUS_DUPLICATE",
                        "Владелец заказа вернул дублирующийся состав плана.",
                    )
                matching[key] = status
        await self._require_active_apply(
            session,
            proposal_id,
            operation_token,
        )
        current_source = await plan_service.get_plan(session, source.id, for_update=True)
        if (
            current_source.version != expected_source_version
            or current_source.status != expected_source_status
        ):
            raise ApiError(
                409,
                "PLAN_VERSION_CONFLICT",
                "Исходный план изменился, пока владелец заданий формировал состояние.",
            )
        if not matching:
            return (), None
        versions = {status.source_plan_version for status in matching.values()}
        if None in versions or len(versions) != 1:
            raise ApiError(
                409,
                "RWMS_REPLACEMENT_SOURCE_CHANGED",
                "Опубликованные задания относятся к разным версиям плана.",
            )
        owner_version = next(iter(versions))
        assert owner_version is not None
        return tuple(matching[key] for key in sorted(matching, key=str)), owner_version
