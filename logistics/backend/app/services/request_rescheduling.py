"""Owner-backed slot selection for one existing unassigned RWMS delivery."""

# ruff: noqa: RUF001 -- Russian operator-facing messages are intentional.

from __future__ import annotations

import logging
from copy import deepcopy
from dataclasses import dataclass
from datetime import date, timedelta
from uuid import UUID

from pydantic import ValidationError
from sqlalchemy import or_, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.db import utc_now
from app.errors import ApiError, not_found
from app.integrations.rwms import RwmsPlanningClient
from app.models import (
    LogisticsRequest,
    RecoveryProposal,
    RequestDateOption,
    RequestRescheduleHold,
    RouteCycle,
    RoutePlan,
    RouteStop,
    UnassignedTask,
    Warehouse,
)
from app.models.domain import (
    CatalogCommandReceipt,
    CustomerDeliveryPurpose,
    PlanStatus,
    RequestRescheduleHoldState,
    RequestStatus,
    RequestType,
    TaskStatus,
)
from app.models.operations import RecoveryProposalStatus
from app.schemas.domain import RwmsPlanningRequest
from app.schemas.operations import (
    RequestRescheduleApply,
    RequestRescheduleOptionsQuery,
    RequestRescheduleOptionsRead,
    RequestRescheduleResultRead,
    RequestRescheduleRetry,
    RequestRescheduleSlotRead,
    RwmsRescheduleCommand,
    RwmsRescheduleOption,
    RwmsRescheduleOptions,
    RwmsRescheduleResult,
)
from app.services.catalog import RWMS_SOURCE_SYSTEM
from app.services.catalog_command_idempotency import command_request_hash, execute_idempotent_create
from app.services.plans import MUTABLE_PLAN_STATUSES, archive_mutable_plans_for_dates
from app.services.request_reschedule_fence import reject_active_request_reschedules

_DECISION_REASON = "Клиент согласовал выбранный слот для нераспределённой доставки."
_RECOVERY_LEASE_SECONDS = 60
_MAX_RECOVERY_BACKOFF_SECONDS = 300
_QUARANTINE_RETRY_OPERATION = "REQUEST_RESCHEDULE_QUARANTINE_RETRY"
_QUARANTINE_RETRY_RESOURCE = "request_reschedule_hold"
_APPLY_ALIAS_OPERATION = "REQUEST_RESCHEDULE_APPLY_ALIAS"
_ACTIVE_RECOVERY_PROPOSAL_STATUSES = (
    RecoveryProposalStatus.PROPOSED,
    RecoveryProposalStatus.CUSTOMER_AGREED,
    RecoveryProposalStatus.READY_TO_APPLY,
    RecoveryProposalStatus.FAILED,
)
logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class _RequestIdentity:
    """Stable local-to-owner identity retained across an external-call boundary."""

    request_id: UUID
    request_version: int
    warehouse_id: UUID
    external_warehouse_id: UUID
    order_id: UUID
    order_version: int


@dataclass(frozen=True, slots=True)
class _UnassignedContext:
    """Exact mutable unassigned head that authorizes a new reschedule command."""

    identity: _RequestIdentity
    plan_id: UUID
    plan_version: int
    plan_warehouse_id: UUID
    scheduled_date: date


@dataclass(frozen=True, slots=True)
class _OwnerLineage:
    """Session and booking identity persisted before the owner effect may start."""

    session_id: UUID
    booking_id: UUID


@dataclass(frozen=True, slots=True)
class _PreparedApply:
    """Detached durable command state safe to carry across the owner boundary."""

    hold_id: UUID
    state: RequestRescheduleHoldState
    identity: _RequestIdentity
    context: _UnassignedContext | None
    actor_subject_id: UUID
    owner_idempotency_key: UUID
    owner_lineage: _OwnerLineage | None = None
    selected_slot: RwmsRescheduleOption | None = None
    owner_result: RwmsRescheduleResult | None = None
    public_result: RequestRescheduleResultRead | None = None


class ExistingRequestReschedulingService:
    """Delegate rescheduling to RWMS and converge its local planning projection."""

    def __init__(self, rwms_client: RwmsPlanningClient) -> None:
        self._rwms_client = rwms_client

    async def options(
        self,
        session: AsyncSession,
        request_id: UUID,
        payload: RequestRescheduleOptionsQuery,
    ) -> RequestRescheduleOptionsRead:
        """Return every fresh owner slot on the selected date without accepting manual windows."""

        context = await self._load_unassigned_context(
            session,
            request_id,
            expected_request_version=payload.expected_request_version,
            for_update=True,
        )
        await reject_active_request_reschedules(session, (request_id,))
        await session.commit()
        owner = await self._rwms_client.get_reschedule_options(
            context.identity.order_id,
            expected_order_version=context.identity.order_version,
        )
        self._validate_owner_options(context.identity, owner)

        current = await self._load_unassigned_context(
            session,
            request_id,
            expected_request_version=payload.expected_request_version,
            for_update=True,
        )
        await reject_active_request_reschedules(session, (request_id,))
        if (
            current.identity != context.identity
            or current.plan_id != context.plan_id
            or current.plan_version != context.plan_version
            or current.plan_warehouse_id != context.plan_warehouse_id
            or current.scheduled_date != context.scheduled_date
        ):
            raise ApiError(
                409,
                "REQUEST_RESCHEDULE_STATE_CHANGED",
                "Заявка или активный план изменились во время расчёта слотов.",
            )
        await session.commit()

        selected_options = sorted(
            (option for option in owner.options if option.date == payload.date),
            key=_slot_sort_key,
        )
        return RequestRescheduleOptionsRead(
            request_id=request_id,
            request_version=current.identity.request_version,
            source_plan_id=current.plan_id,
            source_plan_version=current.plan_version,
            order_id=owner.order_id,
            order_version=owner.order_version,
            session_id=owner.session_id,
            session_version=owner.session_version,
            current_slot=_slot_read(owner.current_slot),
            options=[_slot_read(option) for option in selected_options],
        )

    async def apply(
        self,
        session: AsyncSession,
        request_id: UUID,
        payload: RequestRescheduleApply,
        *,
        actor_subject_id: UUID,
        idempotency_key: UUID,
    ) -> RequestRescheduleResultRead:
        """Apply one owner slot and converge locally without duplicating the source request."""

        prepared = await self._prepare_apply(
            session,
            request_id,
            payload,
            actor_subject_id=actor_subject_id,
            idempotency_key=idempotency_key,
        )
        if prepared.state == RequestRescheduleHoldState.CLAIMED:
            prepared = await self._advance_claim_to_owner(session, prepared, payload)

        if prepared.state == RequestRescheduleHoldState.COMPLETE:
            if prepared.public_result is None:
                raise RuntimeError("completed request reschedule hold has no public result")
            return prepared.public_result

        if (
            prepared.state != RequestRescheduleHoldState.OWNER_CALLING
            or prepared.owner_lineage is None
            or prepared.selected_slot is None
        ):
            raise RuntimeError("request reschedule hold did not reach the owner phase")

        return await self._call_owner_and_converge(session, prepared, payload)

    async def resume_hold(
        self,
        session: AsyncSession,
        hold_id: UUID,
    ) -> RequestRescheduleResultRead | None:
        """Replay one persisted OWNER_CALLING command after a crash or ambiguous timeout."""

        hold = await session.scalar(
            select(RequestRescheduleHold)
            .where(RequestRescheduleHold.id == hold_id)
            .with_for_update()
        )
        if hold is None or hold.state not in {
            RequestRescheduleHoldState.CLAIMED,
            RequestRescheduleHoldState.OWNER_CALLING,
        }:
            await session.commit()
            return None
        prepared = self._prepared_hold(hold)
        payload = RequestRescheduleApply(
            expected_request_version=hold.expected_request_version,
            source_plan_id=hold.source_plan_id,
            source_plan_version=hold.source_plan_version,
            expected_order_version=hold.expected_order_version,
            expected_session_version=hold.expected_session_version,
            slot_id=hold.slot_id,
            slot_version=hold.slot_version,
        )
        await session.commit()
        if prepared.state == RequestRescheduleHoldState.CLAIMED:
            prepared = await self._advance_claim_to_owner(session, prepared, payload)
        return await self._call_owner_and_converge(session, prepared, payload)

    async def retry_quarantined(
        self,
        session: AsyncSession,
        request_id: UUID,
        payload: RequestRescheduleRetry,
        *,
        requested_by: UUID,
        idempotency_key: UUID,
    ) -> RequestRescheduleResultRead:
        """Explicitly resume one quarantined owner command with its original stable key."""

        await self._load_request(session, request_id, for_update=True)

        async def load_hold(hold_id: UUID) -> RequestRescheduleHold | None:
            entity: RequestRescheduleHold | None = await session.scalar(
                select(RequestRescheduleHold)
                .where(RequestRescheduleHold.id == hold_id)
                .with_for_update()
            )
            return entity

        async def claim_hold() -> RequestRescheduleHold:
            entity = await load_hold(payload.hold_id)
            if entity is None or entity.request_id != request_id:
                raise ApiError(404, "REQUEST_RESCHEDULE_HOLD_NOT_FOUND", "Перенос не найден.")
            self._validate_quarantine_retry(entity, payload)
            if entity.state == RequestRescheduleHoldState.QUARANTINED:
                entity.last_retry_requested_at = utc_now()
                entity.last_retry_requested_by = requested_by
                self._reactivate_quarantined(entity)
                entity.lease_until = utc_now() + timedelta(seconds=_RECOVERY_LEASE_SECONDS)
            return entity

        receipt = await execute_idempotent_create(
            session,
            operation=_QUARANTINE_RETRY_OPERATION,
            actor_id=requested_by,
            idempotency_key=str(idempotency_key),
            payload={
                "request_id": str(request_id),
                **payload.model_dump(mode="json"),
            },
            resource_type=_QUARANTINE_RETRY_RESOURCE,
            create=claim_hold,
            load=load_hold,
        )
        hold = receipt.resource
        if hold.request_id != request_id:
            raise RuntimeError("quarantine retry receipt points to another request")
        self._validate_quarantine_retry(hold, payload)
        if hold.state == RequestRescheduleHoldState.SUPERSEDED:
            raise ApiError(
                409,
                "RWMS_RESCHEDULE_RESULT_SUPERSEDED",
                "Результат переноса уже заменён более новой версией заказа.",
            )
        if hold.state == RequestRescheduleHoldState.COMPLETE:
            prepared = self._prepared_hold(hold)
            await session.commit()
            if prepared.public_result is None:
                raise RuntimeError("completed request reschedule hold has no public result")
            return prepared.public_result
        if hold.state != RequestRescheduleHoldState.OWNER_CALLING:
            raise ApiError(
                409,
                "REQUEST_RESCHEDULE_NOT_QUARANTINED",
                "Для заявки нет переноса, ожидающего ручного повторного запуска.",
            )
        prepared = self._prepared_hold(hold)
        apply_payload = RequestRescheduleApply(
            expected_request_version=hold.expected_request_version,
            source_plan_id=hold.source_plan_id,
            source_plan_version=hold.source_plan_version,
            expected_order_version=hold.expected_order_version,
            expected_session_version=hold.expected_session_version,
            slot_id=hold.slot_id,
            slot_version=hold.slot_version,
        )
        await session.commit()
        logger.warning(
            (
                "RWMS request reschedule quarantine retry requested "
                "hold_id=%s request_id=%s requested_by=%s quarantine_count=%d"
            ),
            str(hold.id),
            str(request_id),
            str(requested_by),
            hold.quarantine_count,
            extra={
                "hold_id": str(hold.id),
                "request_id": str(request_id),
                "requested_by": str(requested_by),
            },
        )
        return await self._call_owner_and_converge(session, prepared, apply_payload)

    async def _advance_claim_to_owner(
        self,
        session: AsyncSession,
        prepared: _PreparedApply,
        payload: RequestRescheduleApply,
    ) -> _PreparedApply:
        """Refresh the selected offer, then persist OWNER_CALLING before any effect."""

        if prepared.context is None:
            raise RuntimeError("claimed request reschedule hold lost its plan lineage")
        try:
            fresh_options = await self._rwms_client.get_reschedule_options(
                prepared.identity.order_id,
                expected_order_version=payload.expected_order_version,
            )
            self._validate_owner_options(prepared.identity, fresh_options)
            if fresh_options.session_version != payload.expected_session_version:
                raise ApiError(
                    409,
                    "RWMS_RESCHEDULE_SESSION_VERSION_CONFLICT",
                    "Набор доступных слотов уже изменился. Выполните расчёт ещё раз.",
                    extra={
                        "expected_version": payload.expected_session_version,
                        "actual_version": fresh_options.session_version,
                    },
                )
            selected_slot = self._select_current_offer(fresh_options, payload)
            return await self._prepare_owner_call(
                session,
                prepared,
                fresh_options,
                selected_slot,
            )
        except BaseException as exc:
            await session.rollback()
            await self._fail_claim(session, prepared.hold_id, exc)
            raise

    async def _call_owner_and_converge(
        self,
        session: AsyncSession,
        prepared: _PreparedApply,
        payload: RequestRescheduleApply,
    ) -> RequestRescheduleResultRead:
        """Execute or replay the owner effect, then atomically complete its local projection."""

        assert prepared.owner_lineage is not None
        assert prepared.selected_slot is not None

        command = RwmsRescheduleCommand(
            expected_order_version=payload.expected_order_version,
            expected_session_version=payload.expected_session_version,
            slot_id=payload.slot_id,
            slot_version=payload.slot_version,
            decision_code="CUSTOMER_AGREED_ALTERNATIVE",
            decision_actor_subject_id=prepared.actor_subject_id,
            decision_reason=_DECISION_REASON,
            published_plan_withdrawal=None,
        )
        result = await self._rwms_client.reschedule_order(
            prepared.identity.order_id,
            command,
            idempotency_key=str(prepared.owner_idempotency_key),
        )
        self._validate_owner_result_envelope(
            prepared.identity,
            payload,
            result,
            owner_lineage=prepared.owner_lineage,
        )
        if self._is_newer_owner_reschedule(payload, result, prepared.selected_slot):
            await self._terminalize_newer_owner_reschedule(session, prepared, result)
            raise ApiError(
                409,
                "RWMS_RESCHEDULE_RESULT_SUPERSEDED",
                "Результат переноса уже заменён более новой версией заказа.",
                extra={
                    "actual_order_version": result.order_version,
                    "actual_session_version": result.session_version,
                },
            )
        self._validate_owner_result(
            prepared.identity,
            payload,
            result,
            owner_lineage=prepared.owner_lineage,
            selected_slot=prepared.selected_slot,
        )
        try:
            response = await self._converge_local_request(
                session,
                prepared.identity.request_id,
                result,
                active_hold_id=prepared.hold_id,
            )
        except ApiError as exc:
            if exc.code != "RWMS_RESCHEDULE_RESULT_SUPERSEDED":
                raise
            await session.rollback()
            await self._supersede_hold(session, prepared.hold_id, result)
            await session.commit()
            raise
        await self._complete_hold(session, prepared.hold_id, result, response)
        await session.commit()
        return response

    async def _terminalize_newer_owner_reschedule(
        self,
        session: AsyncSession,
        prepared: _PreparedApply,
        result: RwmsRescheduleResult,
    ) -> None:
        """Release A when its stable owner replay proves a later commitment B now exists."""

        request = await self._load_request(
            session,
            prepared.identity.request_id,
            for_update=True,
        )
        identity = await self._request_identity(session, request)
        if (
            identity.order_id != result.order_id
            or identity.external_warehouse_id != result.warehouse_id
        ):
            raise ApiError(
                409,
                "RWMS_RESCHEDULE_LOCAL_IDENTITY_CHANGED",
                "Связь локальной заявки с владельцем заказа изменилась.",
            )
        if identity.order_version < result.order_version:
            await self._archive_affected_mutable_plans(
                session,
                request,
                target_date=result.confirmed_slot.date,
                active_hold_id=prepared.hold_id,
            )
        await self._supersede_hold(session, prepared.hold_id, result)
        await session.commit()

    async def _find_existing_hold(
        self,
        session: AsyncSession,
        *,
        request_id: UUID,
        actor_subject_id: UUID,
        idempotency_key: UUID,
        request_hash: str,
    ) -> RequestRescheduleHold | None:
        """Resolve exact replay or same-payload browser reattachment under row locks."""

        exact = await session.scalar(
            select(RequestRescheduleHold)
            .where(
                RequestRescheduleHold.actor_id == actor_subject_id,
                RequestRescheduleHold.idempotency_key == idempotency_key,
            )
            .with_for_update()
        )
        if exact is not None:
            self._validate_hold(exact, request_id=request_id, request_hash=request_hash)
            self._raise_for_nonreplayable_hold(exact)
            return exact

        alias = await session.scalar(
            select(CatalogCommandReceipt)
            .where(
                CatalogCommandReceipt.operation == _APPLY_ALIAS_OPERATION,
                CatalogCommandReceipt.actor_id == actor_subject_id,
                CatalogCommandReceipt.idempotency_key == str(idempotency_key),
            )
            .with_for_update()
        )
        if alias is not None:
            if alias.request_hash != request_hash:
                raise ApiError(
                    409,
                    "IDEMPOTENCY_KEY_CONFLICT",
                    "Idempotency-Key уже использован для другой команды.",
                )
            if alias.resource_type != _QUARANTINE_RETRY_RESOURCE or alias.resource_id is None:
                raise ApiError(
                    409,
                    "IDEMPOTENCY_RESULT_UNAVAILABLE",
                    "Предыдущая команда не оставила доступного результата.",
                )
            aliased = await session.scalar(
                select(RequestRescheduleHold)
                .where(RequestRescheduleHold.id == alias.resource_id)
                .with_for_update()
            )
            if aliased is None:
                raise ApiError(
                    409,
                    "IDEMPOTENCY_RESULT_UNAVAILABLE",
                    "Состояние предыдущей команды больше недоступно.",
                )
            self._validate_hold(
                aliased,
                request_id=request_id,
                request_hash=request_hash,
            )
            self._raise_for_nonreplayable_hold(aliased)
            return aliased
        attached = await session.scalar(
            select(RequestRescheduleHold)
            .where(
                RequestRescheduleHold.request_id == request_id,
                RequestRescheduleHold.actor_id == actor_subject_id,
                RequestRescheduleHold.request_hash == request_hash,
                RequestRescheduleHold.state.in_(
                    (
                        RequestRescheduleHoldState.CLAIMED,
                        RequestRescheduleHoldState.OWNER_CALLING,
                        RequestRescheduleHoldState.QUARANTINED,
                        RequestRescheduleHoldState.COMPLETE,
                    )
                ),
            )
            .order_by(RequestRescheduleHold.created_at.desc())
            .limit(1)
            .with_for_update()
        )
        if attached is not None:
            await self._bind_hold_alias(
                session,
                attached,
                actor_subject_id=actor_subject_id,
                idempotency_key=idempotency_key,
                request_hash=request_hash,
            )
            if attached.state == RequestRescheduleHoldState.QUARANTINED:
                self._raise_for_nonreplayable_hold(attached)
        return attached

    @staticmethod
    async def _bind_hold_alias(
        session: AsyncSession,
        hold: RequestRescheduleHold,
        *,
        actor_subject_id: UUID,
        idempotency_key: UUID,
        request_hash: str,
    ) -> None:
        """Persist every accepted browser key so later reuse cannot change its payload."""

        if hold.actor_id == actor_subject_id and hold.idempotency_key == idempotency_key:
            return
        receipt_id = await session.scalar(
            insert(CatalogCommandReceipt)
            .values(
                operation=_APPLY_ALIAS_OPERATION,
                actor_id=actor_subject_id,
                idempotency_key=str(idempotency_key),
                request_hash=request_hash,
                resource_type=_QUARANTINE_RETRY_RESOURCE,
                resource_id=hold.id,
            )
            .on_conflict_do_nothing(constraint="uq_catalog_command_receipts_operation_actor_key")
            .returning(CatalogCommandReceipt.id)
        )
        if receipt_id is not None:
            return
        receipt = await session.scalar(
            select(CatalogCommandReceipt)
            .where(
                CatalogCommandReceipt.operation == _APPLY_ALIAS_OPERATION,
                CatalogCommandReceipt.actor_id == actor_subject_id,
                CatalogCommandReceipt.idempotency_key == str(idempotency_key),
            )
            .with_for_update()
        )
        if (
            receipt is None
            or receipt.request_hash != request_hash
            or receipt.resource_type != _QUARANTINE_RETRY_RESOURCE
            or receipt.resource_id != hold.id
        ):
            raise ApiError(
                409,
                "IDEMPOTENCY_KEY_CONFLICT",
                "Idempotency-Key уже использован для другой команды.",
            )

    @staticmethod
    def _raise_for_nonreplayable_hold(hold: RequestRescheduleHold) -> None:
        """Expose stable terminal and quarantined outcomes for exact/aliased retries."""

        if hold.state == RequestRescheduleHoldState.SUPERSEDED:
            raise ApiError(
                409,
                "RWMS_RESCHEDULE_RESULT_SUPERSEDED",
                "Результат переноса уже заменён более новой версией заказа.",
            )
        if hold.state == RequestRescheduleHoldState.QUARANTINED:
            raise ApiError(
                409,
                "REQUEST_RESCHEDULE_QUARANTINED",
                "Перенос требует безопасного повторного запуска оператором.",
                extra={
                    "request_id": str(hold.request_id),
                    "hold_id": str(hold.id),
                    "quarantine_count": hold.quarantine_count,
                },
            )
        if hold.state == RequestRescheduleHoldState.FAILED:
            raise ApiError(
                409,
                hold.error_code or "REQUEST_RESCHEDULE_PREPARATION_FAILED",
                "Предыдущая попытка завершилась до изменения заказа; пересчитайте слоты.",
            )

    async def _prepare_apply(
        self,
        session: AsyncSession,
        request_id: UUID,
        payload: RequestRescheduleApply,
        *,
        actor_subject_id: UUID,
        idempotency_key: UUID,
    ) -> _PreparedApply:
        """Fence a new command, while allowing an exact durable replay after owner commit."""

        request_hash = command_request_hash(
            {
                "request_id": str(request_id),
                "payload": payload.model_dump(mode="json"),
            }
        )
        existing = await self._find_existing_hold(
            session,
            request_id=request_id,
            actor_subject_id=actor_subject_id,
            idempotency_key=idempotency_key,
            request_hash=request_hash,
        )
        if existing is not None:
            await session.commit()
            return self._prepared_hold(existing)

        candidate = await self._load_unassigned_context(
            session,
            request_id,
            expected_request_version=payload.expected_request_version,
            for_update=False,
        )
        if (
            candidate.plan_id != payload.source_plan_id
            or candidate.plan_version != payload.source_plan_version
        ):
            raise ApiError(
                409,
                "PLAN_VERSION_CONFLICT",
                "Нераспределённый план уже изменился. Обновите данные и повторите действие.",
                extra={
                    "expected_plan_id": str(payload.source_plan_id),
                    "actual_plan_id": str(candidate.plan_id),
                    "expected_version": payload.source_plan_version,
                    "actual_version": candidate.plan_version,
                },
            )
        await session.commit()

        current = await self._load_unassigned_context(
            session,
            request_id,
            expected_request_version=payload.expected_request_version,
            for_update=True,
        )
        source_plan = await session.scalar(
            select(RoutePlan)
            .where(RoutePlan.id == current.plan_id)
            .execution_options(populate_existing=True)
            .with_for_update()
        )
        await self._reject_active_recovery_proposal(
            session,
            request_id,
            source_plan_id=current.plan_id,
        )
        if (
            current != candidate
            or source_plan is None
            or source_plan.status not in MUTABLE_PLAN_STATUSES
            or source_plan.id != payload.source_plan_id
            or source_plan.version != payload.source_plan_version
            or source_plan.version != current.plan_version
            or source_plan.warehouse_id != current.plan_warehouse_id
            or source_plan.date != current.scheduled_date
        ):
            raise ApiError(
                409,
                "REQUEST_RESCHEDULE_STATE_CHANGED",
                "Актуальный нераспределённый план уже изменился.",
            )
        existing = await self._find_existing_hold(
            session,
            request_id=request_id,
            actor_subject_id=actor_subject_id,
            idempotency_key=idempotency_key,
            request_hash=request_hash,
        )
        if existing is not None:
            await session.commit()
            return self._prepared_hold(existing)
        context = current
        if current.identity.order_version != payload.expected_order_version:
            raise ApiError(
                409,
                "RWMS_ORDER_VERSION_CONFLICT",
                "Версия заказа уже изменилась. Выполните расчёт слотов ещё раз.",
                extra={
                    "expected_version": payload.expected_order_version,
                    "actual_version": current.identity.order_version,
                },
            )
        active = await session.scalar(
            select(RequestRescheduleHold)
            .where(
                or_(
                    RequestRescheduleHold.request_id == request_id,
                    RequestRescheduleHold.source_plan_id == context.plan_id,
                ),
                RequestRescheduleHold.state.in_(
                    (
                        RequestRescheduleHoldState.CLAIMED,
                        RequestRescheduleHoldState.OWNER_CALLING,
                        RequestRescheduleHoldState.QUARANTINED,
                    )
                ),
            )
            .with_for_update()
        )
        if active is not None:
            raise ApiError(
                409,
                "REQUEST_RESCHEDULE_IN_PROGRESS",
                "Для заявки или этого плана уже подтверждается другой клиентский слот.",
                headers={"Retry-After": "3"},
            )
        hold = RequestRescheduleHold(
            request_id=request_id,
            actor_id=actor_subject_id,
            idempotency_key=idempotency_key,
            request_hash=request_hash,
            state=RequestRescheduleHoldState.CLAIMED,
            expected_request_version=payload.expected_request_version,
            local_warehouse_id=context.identity.warehouse_id,
            external_warehouse_id=context.identity.external_warehouse_id,
            order_id=context.identity.order_id,
            expected_order_version=payload.expected_order_version,
            expected_session_version=payload.expected_session_version,
            slot_id=payload.slot_id,
            slot_version=payload.slot_version,
            source_plan_id=context.plan_id,
            source_plan_version=context.plan_version,
            source_plan_warehouse_id=context.plan_warehouse_id,
            source_scheduled_date=context.scheduled_date,
            owner_session_id=None,
            owner_booking_id=None,
            selected_slot=None,
            owner_result=None,
            public_result=None,
            attempt_count=0,
            quarantine_count=0,
            next_attempt_at=None,
            lease_until=utc_now() + timedelta(seconds=_RECOVERY_LEASE_SECONDS),
            quarantined_at=None,
            last_quarantine_error_code=None,
            last_retry_requested_at=None,
            last_retry_requested_by=None,
            error_code=None,
            completed_at=None,
        )
        try:
            async with session.begin_nested():
                session.add(hold)
                await session.flush()
        except IntegrityError:
            concurrent = await session.scalar(
                select(RequestRescheduleHold)
                .where(
                    RequestRescheduleHold.actor_id == actor_subject_id,
                    RequestRescheduleHold.idempotency_key == idempotency_key,
                )
                .with_for_update()
            )
            if concurrent is not None:
                self._validate_hold(
                    concurrent,
                    request_id=request_id,
                    request_hash=request_hash,
                )
                self._raise_for_nonreplayable_hold(concurrent)
                prepared = self._prepared_hold(concurrent)
                await session.commit()
                return prepared
            active = await session.scalar(
                select(RequestRescheduleHold)
                .where(
                    or_(
                        RequestRescheduleHold.request_id == request_id,
                        RequestRescheduleHold.source_plan_id == context.plan_id,
                    ),
                    RequestRescheduleHold.state.in_(
                        (
                            RequestRescheduleHoldState.CLAIMED,
                            RequestRescheduleHoldState.OWNER_CALLING,
                            RequestRescheduleHoldState.QUARANTINED,
                        )
                    ),
                )
                .with_for_update()
            )
            if active is None:
                raise
            self._raise_for_nonreplayable_hold(active)
            raise ApiError(
                409,
                "REQUEST_RESCHEDULE_IN_PROGRESS",
                "Для заявки или этого плана уже подтверждается другой клиентский слот.",
                extra={"request_id": str(active.request_id)},
                headers={"Retry-After": "3"},
            ) from None
        await session.commit()
        return self._prepared_hold(hold)

    async def _prepare_owner_call(
        self,
        session: AsyncSession,
        prepared: _PreparedApply,
        owner: RwmsRescheduleOptions,
        selected_slot: RwmsRescheduleOption,
    ) -> _PreparedApply:
        """Recheck the exact local lineage, then durably mark that owner apply may have started."""

        if prepared.state != RequestRescheduleHoldState.CLAIMED:
            return prepared
        current = await self._load_unassigned_context(
            session,
            prepared.identity.request_id,
            expected_request_version=prepared.identity.request_version,
            for_update=True,
        )
        if current != prepared.context:
            raise ApiError(
                409,
                "REQUEST_RESCHEDULE_STATE_CHANGED",
                "Заявка или активный план изменились после расчёта слотов.",
            )
        hold = await session.scalar(
            select(RequestRescheduleHold)
            .where(RequestRescheduleHold.id == prepared.hold_id)
            .with_for_update()
        )
        if hold is None:
            raise RuntimeError("request reschedule hold disappeared before owner apply")
        if hold.state == RequestRescheduleHoldState.CLAIMED:
            hold.state = RequestRescheduleHoldState.OWNER_CALLING
            hold.owner_session_id = owner.session_id
            hold.owner_booking_id = owner.booking_id
            hold.selected_slot = selected_slot.model_dump(mode="json", by_alias=True)
            hold.lease_until = utc_now() + timedelta(seconds=_RECOVERY_LEASE_SECONDS)
            hold.next_attempt_at = None
        elif hold.state not in {
            RequestRescheduleHoldState.OWNER_CALLING,
            RequestRescheduleHoldState.COMPLETE,
        }:
            raise ApiError(
                409,
                "REQUEST_RESCHEDULE_PREPARATION_FAILED",
                "Подготовка переноса уже завершилась с ошибкой.",
            )
        result = self._prepared_hold(hold)
        await session.commit()
        return result

    async def _load_unassigned_context(
        self,
        session: AsyncSession,
        request_id: UUID,
        *,
        expected_request_version: int,
        for_update: bool,
    ) -> _UnassignedContext:
        """Require one complete request slice in exactly one mutable unassigned plan head."""

        request = await self._load_request(session, request_id, for_update=for_update)
        identity = await self._request_identity(session, request)
        await self._reject_active_recovery_proposal(session, request_id)
        if request.version != expected_request_version:
            raise ApiError(
                409,
                "REQUEST_VERSION_CONFLICT",
                "Заявка уже изменена. Обновите план и повторите действие.",
                extra={
                    "expected_version": expected_request_version,
                    "actual_version": request.version,
                },
            )
        if request.status not in {RequestStatus.READY, RequestStatus.UNASSIGNED}:
            raise ApiError(
                409,
                "REQUEST_NOT_UNASSIGNED",
                "Перенести через выбор слота можно только нераспределённую доставку.",
            )
        if request.assignment_type is not None:
            raise ApiError(
                409,
                "REQUEST_ALREADY_ASSIGNED",
                "Заявка уже передана исполнителю и не может быть перенесена этим способом.",
            )
        if request.scheduled_date is None:
            raise ApiError(
                409,
                "REQUEST_NOT_SCHEDULED",
                "У заявки отсутствует текущая дата доставки.",
            )
        task_ids = {task.id for task in request.tasks}
        if not task_ids or any(
            task.status not in {TaskStatus.READY, TaskStatus.UNASSIGNED} or task.locked
            for task in request.tasks
        ):
            raise ApiError(
                409,
                "REQUEST_TASK_NOT_UNASSIGNED",
                "Часть заявки уже назначена или заблокирована в маршруте.",
            )
        assigned_plan_id = await session.scalar(
            select(RoutePlan.id)
            .join(RouteCycle, RouteCycle.route_plan_id == RoutePlan.id)
            .join(RouteStop, RouteStop.route_cycle_id == RouteCycle.id)
            .where(
                RouteStop.task_id.in_(task_ids),
                RoutePlan.status != PlanStatus.ARCHIVED,
            )
            .limit(1)
        )
        if assigned_plan_id is not None:
            raise ApiError(
                409,
                "REQUEST_ALREADY_ROUTE_ASSIGNED",
                "Заявка уже назначена в маршрут и требует другого процесса перепланирования.",
                extra={"plan_id": str(assigned_plan_id)},
            )
        rows = tuple(
            (
                await session.execute(
                    select(RoutePlan, UnassignedTask.task_id)
                    .join(UnassignedTask, UnassignedTask.route_plan_id == RoutePlan.id)
                    .where(
                        UnassignedTask.task_id.in_(task_ids),
                        RoutePlan.status != PlanStatus.ARCHIVED,
                    )
                    .execution_options(populate_existing=True)
                )
            ).all()
        )
        confirmed = next(
            (plan for plan, _task_id in rows if plan.status == PlanStatus.CONFIRMED),
            None,
        )
        if confirmed is not None:
            raise ApiError(
                409,
                "REQUEST_IN_CONFIRMED_PLAN",
                "Заявка сохранена в подтверждённом плане и требует опубликованного переноса.",
                extra={"plan_id": str(confirmed.id)},
            )
        tasks_by_plan: dict[UUID, set[UUID]] = {}
        plans_by_id: dict[UUID, RoutePlan] = {}
        for plan, task_id in rows:
            if plan.status not in MUTABLE_PLAN_STATUSES:
                continue
            plans_by_id[plan.id] = plan
            tasks_by_plan.setdefault(plan.id, set()).add(task_id)
        complete_plan_ids = [
            plan_id for plan_id, plan_task_ids in tasks_by_plan.items() if plan_task_ids == task_ids
        ]
        if len(complete_plan_ids) != 1 or any(
            plan_task_ids != task_ids for plan_task_ids in tasks_by_plan.values()
        ):
            raise ApiError(
                409,
                "REQUEST_NOT_UNASSIGNED",
                "Заявка не находится целиком в одном актуальном нераспределённом плане.",
            )
        plan = plans_by_id[complete_plan_ids[0]]
        await self._reject_active_recovery_proposal(
            session,
            request_id,
            source_plan_id=plan.id,
        )
        if plan.date != request.scheduled_date:
            raise ApiError(
                409,
                "REQUEST_PLAN_DATE_MISMATCH",
                "Дата заявки не совпадает с датой актуального нераспределённого плана.",
            )
        return _UnassignedContext(
            identity=identity,
            plan_id=plan.id,
            plan_version=plan.version,
            plan_warehouse_id=plan.warehouse_id,
            scheduled_date=request.scheduled_date,
        )

    @staticmethod
    async def _reject_active_recovery_proposal(
        session: AsyncSession,
        request_id: UUID,
        *,
        source_plan_id: UUID | None = None,
    ) -> None:
        """Fence every canonical request or plan lineage used by retryable recovery work."""

        request_key = str(request_id)
        lineage_matches = [
            RecoveryProposal.affected_request_ids.contains([request_key]),
            RecoveryProposal.changes["request_id"].astext == request_key,
            RecoveryProposal.changes["cancel_request_id"].astext == request_key,
        ]
        if source_plan_id is not None:
            lineage_matches.append(RecoveryProposal.source_plan_id == source_plan_id)
        active_recovery_id = await session.scalar(
            select(RecoveryProposal.id)
            .where(
                RecoveryProposal.status.in_(_ACTIVE_RECOVERY_PROPOSAL_STATUSES),
                or_(*lineage_matches),
            )
            .order_by(RecoveryProposal.created_at, RecoveryProposal.id)
            .limit(1)
        )
        if active_recovery_id is not None:
            raise ApiError(
                409,
                "REQUEST_RECOVERY_IN_PROGRESS",
                "Для заявки уже выполняется операционное перепланирование.",
                extra={"proposal_id": str(active_recovery_id)},
            )

    async def _request_identity(
        self,
        session: AsyncSession,
        request: LogisticsRequest,
    ) -> _RequestIdentity:
        """Validate the only supported source type and return its exact owner mapping."""

        if request.source_system != RWMS_SOURCE_SYSTEM:
            raise ApiError(
                422,
                "REQUEST_RESCHEDULE_SOURCE_UNSUPPORTED",
                "Расчёт клиентского слота доступен только для заявок из RWMS.",
            )
        if request.type != RequestType.DELIVERY:
            raise ApiError(
                422,
                "REQUEST_RESCHEDULE_TYPE_UNSUPPORTED",
                "Расчёт клиентского слота доступен только для доставки.",
            )
        if request.customer_delivery_purpose != CustomerDeliveryPurpose.RENTAL_DELIVERY:
            raise ApiError(
                422,
                "REQUEST_RESCHEDULE_PURPOSE_UNSUPPORTED",
                "Расчёт нового клиентского слота доступен только для доставки аренды.",
            )
        if (
            request.external_id is None
            or request.external_version is None
            or request.external_payload is None
        ):
            raise ApiError(
                409,
                "RWMS_REQUEST_MAPPING_MISSING",
                "У заявки отсутствует актуальная связь с владельцем заказа.",
            )
        warehouse = await session.get(Warehouse, request.warehouse_id)
        if warehouse is None:
            raise not_found("warehouse", request.warehouse_id)
        return _RequestIdentity(
            request_id=request.id,
            request_version=request.version,
            warehouse_id=request.warehouse_id,
            external_warehouse_id=warehouse.external_warehouse_id,
            order_id=request.external_id,
            order_version=request.external_version,
        )

    @staticmethod
    async def _load_request(
        session: AsyncSession,
        request_id: UUID,
        *,
        for_update: bool,
    ) -> LogisticsRequest:
        """Reload one request graph, bypassing stale identity-map state after owner calls."""

        statement = (
            select(LogisticsRequest)
            .where(LogisticsRequest.id == request_id)
            .options(
                selectinload(LogisticsRequest.date_options),
                selectinload(LogisticsRequest.tasks),
            )
            .execution_options(populate_existing=True)
        )
        if for_update:
            statement = statement.with_for_update()
        request = await session.scalar(statement)
        if request is None:
            raise not_found("request", request_id)
        return request

    @staticmethod
    def _validate_hold(
        hold: RequestRescheduleHold,
        *,
        request_id: UUID,
        request_hash: str,
    ) -> None:
        """Reject a UUID key reused for any different request or command body."""

        if hold.request_id != request_id or hold.request_hash != request_hash:
            raise ApiError(
                409,
                "IDEMPOTENCY_KEY_CONFLICT",
                "Idempotency-Key уже использован для другой команды.",
            )

    @staticmethod
    def _validate_quarantine_retry(
        hold: RequestRescheduleHold,
        payload: RequestRescheduleRetry,
    ) -> None:
        """Fence an operator retry to the exact quarantined command generation."""

        if hold.quarantine_count != payload.expected_quarantine_count:
            raise ApiError(
                409,
                "REQUEST_RESCHEDULE_QUARANTINE_VERSION_CONFLICT",
                "Состояние незавершённого переноса уже изменилось.",
                extra={
                    "expected_version": payload.expected_quarantine_count,
                    "actual_version": hold.quarantine_count,
                },
            )
        if hold.quarantine_count < 1 or hold.state not in {
            RequestRescheduleHoldState.QUARANTINED,
            RequestRescheduleHoldState.OWNER_CALLING,
            RequestRescheduleHoldState.COMPLETE,
            RequestRescheduleHoldState.SUPERSEDED,
        }:
            raise ApiError(
                409,
                "REQUEST_RESCHEDULE_NOT_QUARANTINED",
                "Для заявки нет переноса, ожидающего ручного повторного запуска.",
            )

    @staticmethod
    def _reactivate_quarantined(hold: RequestRescheduleHold) -> None:
        """Let the same actor explicitly replay one quarantined owner command safely."""

        if hold.state != RequestRescheduleHoldState.QUARANTINED:
            return
        hold.state = RequestRescheduleHoldState.OWNER_CALLING
        hold.attempt_count = 0
        hold.next_attempt_at = None
        hold.lease_until = None
        hold.error_code = None
        hold.completed_at = None

    @staticmethod
    def _prepared_hold(hold: RequestRescheduleHold) -> _PreparedApply:
        """Validate and detach one persisted command phase for transaction-free work."""

        try:
            state = RequestRescheduleHoldState(hold.state)
            selected_slot = (
                RwmsRescheduleOption.model_validate(hold.selected_slot)
                if hold.selected_slot is not None
                else None
            )
            owner_result = (
                RwmsRescheduleResult.model_validate(hold.owner_result)
                if hold.owner_result is not None
                else None
            )
            public_result = (
                RequestRescheduleResultRead.model_validate(hold.public_result)
                if hold.public_result is not None
                else None
            )
        except (ValueError, ValidationError) as exc:
            raise RuntimeError("persisted request reschedule hold is invalid") from exc
        owner_lineage = None
        if hold.owner_session_id is not None and hold.owner_booking_id is not None:
            owner_lineage = _OwnerLineage(
                session_id=hold.owner_session_id,
                booking_id=hold.owner_booking_id,
            )
        identity = _RequestIdentity(
            request_id=hold.request_id,
            request_version=hold.expected_request_version,
            warehouse_id=hold.local_warehouse_id,
            external_warehouse_id=hold.external_warehouse_id,
            order_id=hold.order_id,
            order_version=hold.expected_order_version,
        )
        context = (
            _UnassignedContext(
                identity=identity,
                plan_id=hold.source_plan_id,
                plan_version=hold.source_plan_version,
                plan_warehouse_id=hold.source_plan_warehouse_id,
                scheduled_date=hold.source_scheduled_date,
            )
            if hold.source_plan_id is not None
            else None
        )
        return _PreparedApply(
            hold_id=hold.id,
            state=state,
            identity=identity,
            context=context,
            actor_subject_id=hold.actor_id,
            owner_idempotency_key=hold.idempotency_key,
            owner_lineage=owner_lineage,
            selected_slot=selected_slot,
            owner_result=owner_result,
            public_result=public_result,
        )

    @staticmethod
    async def _fail_claim(
        session: AsyncSession,
        hold_id: UUID,
        exc: BaseException,
    ) -> None:
        """Release a command that provably failed before the owner effect could start."""

        hold = await session.scalar(
            select(RequestRescheduleHold)
            .where(RequestRescheduleHold.id == hold_id)
            .with_for_update()
        )
        if hold is not None and hold.state == RequestRescheduleHoldState.CLAIMED:
            error_code = exc.code if isinstance(exc, ApiError) else "RESCHEDULE_PREPARATION_FAILED"
            hold.state = RequestRescheduleHoldState.FAILED
            hold.next_attempt_at = None
            hold.lease_until = None
            hold.error_code = error_code[:128]
            hold.completed_at = utc_now()
        await session.commit()

    @staticmethod
    async def _complete_hold(
        session: AsyncSession,
        hold_id: UUID,
        result: RwmsRescheduleResult,
        response: RequestRescheduleResultRead,
    ) -> None:
        """Store the owner receipt atomically with forward local convergence."""

        hold = await session.scalar(
            select(RequestRescheduleHold)
            .where(RequestRescheduleHold.id == hold_id)
            .with_for_update()
        )
        if hold is None:
            raise RuntimeError("request reschedule hold disappeared after owner apply")
        result_payload = result.model_dump(mode="json", by_alias=True)
        public_payload = response.model_dump(mode="json")
        if hold.state == RequestRescheduleHoldState.COMPLETE:
            if hold.owner_result != result_payload or hold.public_result != public_payload:
                raise ApiError(
                    409,
                    "RWMS_RESCHEDULE_RECEIPT_CONFLICT",
                    "Владелец вернул другой результат для уже завершённой команды.",
                )
            return
        if hold.state != RequestRescheduleHoldState.OWNER_CALLING:
            raise RuntimeError("request reschedule hold left the owner phase unexpectedly")
        hold.state = RequestRescheduleHoldState.COMPLETE
        hold.owner_result = result_payload
        hold.public_result = public_payload
        hold.next_attempt_at = None
        hold.lease_until = None
        hold.error_code = None
        hold.completed_at = utc_now()

    @staticmethod
    async def _supersede_hold(
        session: AsyncSession,
        hold_id: UUID,
        result: RwmsRescheduleResult,
    ) -> None:
        """Release a valid owner receipt already overtaken by a newer local projection."""

        hold = await session.scalar(
            select(RequestRescheduleHold)
            .where(RequestRescheduleHold.id == hold_id)
            .with_for_update()
        )
        if hold is None:
            raise RuntimeError("request reschedule hold disappeared after owner apply")
        if hold.state == RequestRescheduleHoldState.SUPERSEDED:
            return
        if hold.state != RequestRescheduleHoldState.OWNER_CALLING:
            raise RuntimeError("request reschedule hold left the owner phase unexpectedly")
        hold.state = RequestRescheduleHoldState.SUPERSEDED
        hold.owner_result = result.model_dump(mode="json", by_alias=True)
        hold.public_result = None
        hold.next_attempt_at = None
        hold.lease_until = None
        hold.error_code = "RWMS_RESCHEDULE_RESULT_SUPERSEDED"
        hold.completed_at = utc_now()

    @staticmethod
    def _validate_owner_options(
        identity: _RequestIdentity,
        owner: RwmsRescheduleOptions,
    ) -> None:
        """Reject an upstream result outside the locally authorized order and warehouse."""

        if (
            owner.order_id != identity.order_id
            or owner.order_version != identity.order_version
            or owner.warehouse_id != identity.external_warehouse_id
        ):
            raise ApiError(
                502,
                "RWMS_RESCHEDULE_OPTIONS_RESPONSE_INVALID",
                "Владелец заказа вернул слоты для другой заявки или версии.",
            )
        slot_keys = [(option.slot_id, option.slot_version) for option in owner.options]
        if len(slot_keys) != len(set(slot_keys)):
            raise ApiError(
                502,
                "RWMS_RESCHEDULE_OPTIONS_RESPONSE_INVALID",
                "Владелец заказа вернул неоднозначный набор слотов.",
            )

    @staticmethod
    def _select_current_offer(
        owner: RwmsRescheduleOptions,
        payload: RequestRescheduleApply,
    ) -> RwmsRescheduleOption:
        """Resolve the exact fresh slot and reject stale or expired browser selections."""

        selected = next(
            (
                option
                for option in owner.options
                if option.slot_id == payload.slot_id and option.slot_version == payload.slot_version
            ),
            None,
        )
        if selected is None:
            code = (
                "RWMS_RESCHEDULE_SLOT_VERSION_CONFLICT"
                if any(option.slot_id == payload.slot_id for option in owner.options)
                else "RWMS_RESCHEDULE_SLOT_NOT_AVAILABLE"
            )
            raise ApiError(
                409,
                code,
                "Выбранный слот уже недоступен. Выполните расчёт ещё раз.",
            )
        if selected.expires_at <= utc_now():
            raise ApiError(
                409,
                "RWMS_RESCHEDULE_SLOT_EXPIRED",
                "Срок действия выбранного слота истёк. Выполните расчёт ещё раз.",
            )
        return selected

    @staticmethod
    def _validate_owner_result(
        identity: _RequestIdentity,
        payload: RequestRescheduleApply,
        result: RwmsRescheduleResult,
        *,
        owner_lineage: _OwnerLineage,
        selected_slot: RwmsRescheduleOption | None,
    ) -> None:
        """Validate the durable owner receipt before touching the local projection."""

        ExistingRequestReschedulingService._validate_owner_result_envelope(
            identity,
            payload,
            result,
            owner_lineage=owner_lineage,
        )
        if (
            result.confirmed_slot.slot_id != payload.slot_id
            or result.confirmed_slot.slot_version <= payload.slot_version
            or (
                selected_slot is not None
                and (
                    result.confirmed_slot.date != selected_slot.date
                    or result.confirmed_slot.kind != selected_slot.kind
                    or result.confirmed_slot.window_start != selected_slot.window_start
                    or result.confirmed_slot.window_end != selected_slot.window_end
                    or result.confirmed_slot.delivery_price_rubles
                    != selected_slot.delivery_price_rubles
                )
            )
        ):
            raise ApiError(
                502,
                "RWMS_RESCHEDULE_RESPONSE_INVALID",
                "Владелец заказа вернул несогласованный результат переноса.",
            )

    @staticmethod
    def _validate_owner_result_envelope(
        identity: _RequestIdentity,
        payload: RequestRescheduleApply,
        result: RwmsRescheduleResult,
        *,
        owner_lineage: _OwnerLineage,
    ) -> None:
        """Require the same owner aggregate and a forward version before classification."""

        if (
            result.order_id != identity.order_id
            or result.warehouse_id != identity.external_warehouse_id
            or result.session_id != owner_lineage.session_id
            or result.booking_id != owner_lineage.booking_id
            or result.order_version <= payload.expected_order_version
            or result.session_version <= payload.expected_session_version
            or result.published_plan_withdrawal is not None
        ):
            raise ApiError(
                502,
                "RWMS_RESCHEDULE_RESPONSE_INVALID",
                "Владелец заказа вернул несогласованный результат переноса.",
            )

    @staticmethod
    def _is_newer_owner_reschedule(
        payload: RequestRescheduleApply,
        result: RwmsRescheduleResult,
        selected_slot: RwmsRescheduleOption,
    ) -> bool:
        """Recognize B, not a malformed first response A, from exact Spring version advances."""

        first_order_version = payload.expected_order_version + 1
        first_session_version = payload.expected_session_version + 1
        if (
            result.order_version <= first_order_version
            or result.session_version <= first_session_version
        ):
            return False
        confirmed = result.confirmed_slot
        return (
            confirmed.slot_id != selected_slot.slot_id
            or confirmed.date != selected_slot.date
            or confirmed.kind != selected_slot.kind
            or confirmed.window_start != selected_slot.window_start
            or confirmed.window_end != selected_slot.window_end
            or confirmed.delivery_price_rubles != selected_slot.delivery_price_rubles
        )

    async def _converge_local_request(
        self,
        session: AsyncSession,
        request_id: UUID,
        result: RwmsRescheduleResult,
        *,
        active_hold_id: UUID,
    ) -> RequestRescheduleResultRead:
        """Apply one forward-only local projection revision under the request row lock."""

        request = await self._load_request(session, request_id, for_update=True)
        identity = await self._request_identity(session, request)
        if (
            identity.order_id != result.order_id
            or identity.external_warehouse_id != result.warehouse_id
        ):
            raise ApiError(
                409,
                "RWMS_RESCHEDULE_LOCAL_IDENTITY_CHANGED",
                "Связь локальной заявки с владельцем заказа изменилась.",
            )
        if identity.order_version > result.order_version:
            raise ApiError(
                409,
                "RWMS_RESCHEDULE_RESULT_SUPERSEDED",
                "Результат этого повтора уже заменён более новой версией заказа.",
                extra={"actual_order_version": identity.order_version},
            )
        if identity.order_version < result.order_version:
            await self._archive_affected_mutable_plans(
                session,
                request,
                target_date=result.confirmed_slot.date,
                active_hold_id=active_hold_id,
            )

        target_date = result.confirmed_slot.date
        existing_target = next(
            (option for option in request.date_options if option.date == target_date),
            None,
        )
        travel_zone_hours = (
            existing_target.travel_zone_hours if existing_target is not None else None
        )
        desired_option = RequestDateOption(
            request_id=request.id,
            date=target_date,
            priority=0,
            window_start=result.confirmed_slot.window_start,
            window_end=result.confirmed_slot.window_end,
            is_hard=True,
            travel_zone_hours=travel_zone_hours,
        )
        desired_payload = _owner_payload(request, result, travel_zone_hours=travel_zone_hours)
        options_changed = (
            len(request.date_options) != 1
            or not request.date_options
            or (_date_option_facts(request.date_options[0]) != _date_option_facts(desired_option))
        )
        if identity.order_version == result.order_version:
            if (
                request.external_payload != desired_payload
                or request.scheduled_date != target_date
                or request.delivery_price_rubles != result.confirmed_slot.delivery_price_rubles
                or options_changed
            ):
                raise ApiError(
                    409,
                    "RWMS_RESCHEDULE_LOCAL_PROJECTION_CONFLICT",
                    "Локальная проекция этой версии заказа не совпадает с результатом переноса.",
                )
            return _result_read(request, result)

        task_ids = {task.id for task in request.tasks}
        confirmed_assigned = (
            select(RoutePlan.id)
            .join(RouteCycle, RouteCycle.route_plan_id == RoutePlan.id)
            .join(RouteStop, RouteStop.route_cycle_id == RouteCycle.id)
            .where(
                RoutePlan.status == PlanStatus.CONFIRMED,
                RouteStop.task_id.in_(task_ids),
            )
        )
        confirmed_unassigned = (
            select(RoutePlan.id)
            .join(UnassignedTask, UnassignedTask.route_plan_id == RoutePlan.id)
            .where(
                RoutePlan.status == PlanStatus.CONFIRMED,
                UnassignedTask.task_id.in_(task_ids),
            )
        )
        confirmed_plan_id = await session.scalar(
            confirmed_assigned.union(confirmed_unassigned).limit(1)
        )
        if (
            confirmed_plan_id is not None
            or request.status
            in {
                RequestStatus.PLANNED,
                RequestStatus.IN_PROGRESS,
                RequestStatus.COMPLETED,
                RequestStatus.CANCELLED,
            }
            or any(
                task.status
                in {
                    TaskStatus.PLANNED,
                    TaskStatus.IN_PROGRESS,
                    TaskStatus.COMPLETED,
                    TaskStatus.CANCELLED,
                }
                for task in request.tasks
            )
        ):
            raise ApiError(
                409,
                "RWMS_RESCHEDULE_OWNER_COMMITTED_LOCAL_CONFLICT",
                (
                    "Владелец заказа применил слот, но локальная заявка уже "
                    "опубликована или выполнена."
                ),
            )

        old_date = request.scheduled_date
        request_changed = (
            request.external_version != result.order_version
            or request.external_payload != desired_payload
            or old_date != target_date
            or request.status != RequestStatus.READY
            or request.delivery_price_rubles != result.confirmed_slot.delivery_price_rubles
            or options_changed
            or any(task.status != TaskStatus.READY for task in request.tasks)
        )
        if options_changed:
            request.date_options.clear()
            await session.flush()
            request.date_options.append(desired_option)
        request.external_version = result.order_version
        request.external_payload = desired_payload
        request.scheduled_date = target_date
        request.status = RequestStatus.READY
        request.delivery_price_rubles = result.confirmed_slot.delivery_price_rubles
        for task in request.tasks:
            task.status = TaskStatus.READY
        if request_changed:
            request.version += 1
        await session.flush()
        return _result_read(request, result)

    @staticmethod
    async def _archive_affected_mutable_plans(
        session: AsyncSession,
        request: LogisticsRequest,
        *,
        target_date: date,
        active_hold_id: UUID,
    ) -> None:
        """Archive old and target heads for every plan root that retained this request."""

        task_ids = {task.id for task in request.tasks}
        assigned = (
            select(RoutePlan.warehouse_id, RoutePlan.date)
            .join(RouteCycle, RouteCycle.route_plan_id == RoutePlan.id)
            .join(RouteStop, RouteStop.route_cycle_id == RouteCycle.id)
            .where(RouteStop.task_id.in_(task_ids))
        )
        unassigned = (
            select(RoutePlan.warehouse_id, RoutePlan.date)
            .join(UnassignedTask, UnassignedTask.route_plan_id == RoutePlan.id)
            .where(UnassignedTask.task_id.in_(task_ids))
        )
        referenced = tuple((await session.execute(assigned.union(unassigned))).all())
        dates_by_warehouse: dict[UUID, set[date]] = {}
        for warehouse_id, planning_date in referenced:
            dates_by_warehouse.setdefault(warehouse_id, set()).update({planning_date, target_date})
        dates_by_warehouse.setdefault(request.warehouse_id, set()).add(target_date)
        if request.scheduled_date is not None:
            dates_by_warehouse[request.warehouse_id].add(request.scheduled_date)
        for warehouse_id in sorted(dates_by_warehouse, key=str):
            dates = dates_by_warehouse[warehouse_id]
            await archive_mutable_plans_for_dates(
                session,
                warehouse_id,
                dates,
                exclude_request_reschedule_hold_id=active_hold_id,
            )


async def claim_due_request_reschedule_holds(
    session: AsyncSession,
    *,
    limit: int,
) -> tuple[UUID, ...]:
    """Lease one bounded due batch with skip-locked multi-instance fencing."""

    if limit < 1:
        raise ValueError("limit must be positive")
    now = utc_now()
    holds = list(
        await session.scalars(
            select(RequestRescheduleHold)
            .where(
                RequestRescheduleHold.state.in_(
                    (
                        RequestRescheduleHoldState.CLAIMED,
                        RequestRescheduleHoldState.OWNER_CALLING,
                    )
                ),
                or_(
                    RequestRescheduleHold.next_attempt_at.is_(None),
                    RequestRescheduleHold.next_attempt_at <= now,
                ),
                or_(
                    RequestRescheduleHold.lease_until.is_(None),
                    RequestRescheduleHold.lease_until <= now,
                ),
            )
            .order_by(
                RequestRescheduleHold.next_attempt_at,
                RequestRescheduleHold.updated_at,
                RequestRescheduleHold.id,
            )
            .with_for_update(skip_locked=True)
            .limit(limit)
        )
    )
    lease_until = now + timedelta(seconds=_RECOVERY_LEASE_SECONDS)
    for hold in holds:
        hold.attempt_count += 1
        hold.next_attempt_at = None
        hold.lease_until = lease_until
    await session.flush()
    return tuple(hold.id for hold in holds)


async def record_request_reschedule_recovery_failure(
    session: AsyncSession,
    hold_id: UUID,
    exc: BaseException,
    *,
    max_attempts: int,
) -> RequestRescheduleHoldState | None:
    """Back off a leased retry or quarantine an owner-started command at its cap."""

    if max_attempts < 1:
        raise ValueError("max_attempts must be positive")
    hold = await session.scalar(
        select(RequestRescheduleHold).where(RequestRescheduleHold.id == hold_id).with_for_update()
    )
    if hold is None:
        await session.commit()
        return None
    state = RequestRescheduleHoldState(hold.state)
    if state not in {
        RequestRescheduleHoldState.CLAIMED,
        RequestRescheduleHoldState.OWNER_CALLING,
    }:
        await session.commit()
        return state
    error_code = (exc.code if isinstance(exc, ApiError) else "REQUEST_RESCHEDULE_RECOVERY_FAILED")[
        :128
    ]
    if hold.attempt_count >= max_attempts:
        terminal_at = utc_now()
        hold.next_attempt_at = None
        hold.lease_until = None
        hold.error_code = error_code
        hold.completed_at = terminal_at
        hold.state = (
            RequestRescheduleHoldState.FAILED
            if state == RequestRescheduleHoldState.CLAIMED
            else RequestRescheduleHoldState.QUARANTINED
        )
        if state == RequestRescheduleHoldState.OWNER_CALLING:
            hold.quarantine_count += 1
            hold.quarantined_at = terminal_at
            hold.last_quarantine_error_code = error_code
    else:
        delay_seconds = min(
            _MAX_RECOVERY_BACKOFF_SECONDS,
            5 * (2 ** min(max(hold.attempt_count - 1, 0), 6)),
        )
        hold.next_attempt_at = utc_now() + timedelta(seconds=delay_seconds)
        hold.lease_until = None
    result_state = RequestRescheduleHoldState(hold.state)
    quarantine_log = (
        str(hold.id),
        str(hold.request_id),
        hold.attempt_count,
        hold.quarantine_count,
        error_code,
    )
    await session.commit()
    if result_state == RequestRescheduleHoldState.QUARANTINED:
        logger.error(
            (
                "RWMS request reschedule recovery quarantined "
                "hold_id=%s request_id=%s attempts=%d quarantine_count=%d error_code=%s"
            ),
            *quarantine_log,
        )
    return result_state


def _slot_read(slot: RwmsRescheduleOption) -> RequestRescheduleSlotRead:
    """Map the strict camel-case owner model into the frozen snake-case public contract."""

    return RequestRescheduleSlotRead(
        slot_id=slot.slot_id,
        slot_version=slot.slot_version,
        date=slot.date,
        kind=slot.kind,
        window_start=slot.window_start,
        window_end=slot.window_end,
        delivery_price_rubles=slot.delivery_price_rubles,
        expires_at=slot.expires_at,
    )


def _slot_sort_key(slot: RwmsRescheduleOption) -> tuple[date, str, str, str]:
    """Keep all same-day owner options in deterministic display order."""

    return (
        slot.date,
        slot.window_start.isoformat() if slot.window_start is not None else "",
        slot.window_end.isoformat() if slot.window_end is not None else "",
        str(slot.slot_id),
    )


def _date_option_facts(option: RequestDateOption) -> tuple[object, ...]:
    """Compare the complete local date commitment without relying on row identity."""

    return (
        option.date,
        option.priority,
        option.window_start,
        option.window_end,
        option.is_hard,
        option.travel_zone_hours,
    )


def _owner_payload(
    request: LogisticsRequest,
    result: RwmsRescheduleResult,
    *,
    travel_zone_hours: int | None,
) -> dict[str, object]:
    """Advance only owner fields represented by the strict stored planning snapshot."""

    raw = deepcopy(request.external_payload)
    if not isinstance(raw, dict):
        raise ApiError(
            409,
            "RWMS_REQUEST_MAPPING_MISSING",
            "У заявки отсутствуют исходные данные владельца заказа.",
        )
    raw["orderVersion"] = result.order_version
    raw["dateOptions"] = [
        {
            "date": result.confirmed_slot.date.isoformat(),
            "priority": 0,
            "isHard": True,
            "windowStart": (
                result.confirmed_slot.window_start.isoformat()
                if result.confirmed_slot.window_start is not None
                else None
            ),
            "windowEnd": (
                result.confirmed_slot.window_end.isoformat()
                if result.confirmed_slot.window_end is not None
                else None
            ),
            "travelZoneHours": travel_zone_hours,
        }
    ]
    raw["deliveryPriceRubles"] = result.confirmed_slot.delivery_price_rubles
    try:
        normalized = RwmsPlanningRequest.model_validate(raw)
    except ValidationError as exc:
        raise ApiError(
            409,
            "RWMS_REQUEST_MAPPING_INVALID",
            "Исходные данные заявки не позволяют сохранить подтверждённый слот.",
        ) from exc
    return normalized.model_dump(mode="json", by_alias=True)


def _result_read(
    request: LogisticsRequest,
    result: RwmsRescheduleResult,
) -> RequestRescheduleResultRead:
    """Return the owner receipt with the current non-regressed local request fence."""

    return RequestRescheduleResultRead(
        request_id=request.id,
        request_version=request.version,
        order_id=result.order_id,
        order_version=result.order_version,
        session_id=result.session_id,
        session_version=result.session_version,
        scheduled_date=result.confirmed_slot.date,
        confirmed_slot=_slot_read(result.confirmed_slot),
    )
