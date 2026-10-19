"""Planning-day policy commands over the root-group plan."""

from __future__ import annotations

from datetime import date
from hashlib import sha256
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.db import utc_now
from app.errors import ApiError, not_found
from app.integrations.rwms import RwmsPlanningClient
from app.models import (
    LogisticsActionStatus,
    LogisticsEvent,
    LogisticsEventType,
    LogisticsHumanAction,
    LogisticsNotice,
    LogisticsNoticeStatus,
    LogisticsRequest,
    PlanningDayMode,
    PlanningDayPolicy,
    RecoveryProposal,
    RecoveryProposalStatus,
    RoutePlan,
    SlotDayPlan,
    Warehouse,
)
from app.models.domain import RequestStatus
from app.schemas.operations import (
    PlanningDayModeResult,
)
from app.services import plans as plan_service
from app.services.capacity_generation import advance_warehouse_capacity_generation
from app.services.capacity_publication_state import mark_capacity_publication_pending
from app.services.dynamic_projection import DynamicOperationsProjection
from app.services.dynamic_recovery import RecoveryProposalWorkflow
from app.services.dynamic_support import (
    PENDING_ACTION_STATUSES as _PENDING_ACTION_STATUSES,
)
from app.services.dynamic_support import (
    TERMINAL_TASK_STATUSES as _TERMINAL_TASK_STATUSES,
)
from app.services.dynamic_support import (
    mode_allows as _mode_allows,
)
from app.services.planning_group import resolve_planning_warehouse_group


class PlanningDayModeWorkflow:
    """Planning-day policy commands over the root-group plan."""

    def __init__(
        self,
        rwms_client: RwmsPlanningClient | None,
        recovery: RecoveryProposalWorkflow,
        projection: DynamicOperationsProjection,
    ) -> None:
        """Bind root-group resolution to recovery and read collaborators."""

        self._rwms_client = rwms_client
        self._recovery = recovery
        self._projection = projection

    async def set_day_mode(
        self,
        session: AsyncSession,
        warehouse_id: UUID,
        planning_date: date,
        *,
        expected_version: int,
        plan_id: UUID | None,
        expected_plan_version: int | None,
        mode: PlanningDayMode,
        actor: str,
        idempotency_key: str,
    ) -> PlanningDayModeResult:
        """Persist a mode and create action items only for tasks already in a real plan."""

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
            if (
                replay.event_type != LogisticsEventType.PLANNING_MODE_CHANGED
                or replay.day != planning_date
                or replay.facts.get("new_mode") != mode.value
                or replay.facts.get("previous_version") != expected_version
                or replay.facts.get("expected_plan_id")
                != (str(plan_id) if plan_id is not None else None)
                or replay.facts.get("expected_plan_version") != expected_plan_version
            ):
                raise ApiError(409, "IDEMPOTENCY_KEY_REUSED", "Ключ повтора уже использован.")
            policy = await self._projection.policy(session, warehouse_id, planning_date)
            return PlanningDayModeResult(
                warehouse_id=warehouse_id,
                day=planning_date,
                mode=PlanningDayMode(policy.mode) if policy is not None else mode,
                version=policy.version if policy is not None else 0,
                event_id=replay.id,
                conflict_count=int(replay.facts.get("conflict_count", 0)),
                pending_action_count=await self._projection.pending_action_count(
                    session, warehouse_id, planning_date
                ),
            )
        policy = await session.scalar(
            select(PlanningDayPolicy)
            .where(
                PlanningDayPolicy.warehouse_id == warehouse_id,
                PlanningDayPolicy.date == planning_date,
            )
            .with_for_update()
        )
        actual_version = policy.version if policy is not None else 0
        if actual_version != expected_version:
            raise ApiError(
                409,
                "PLANNING_DAY_MODE_VERSION_CONFLICT",
                "Режим дня уже изменён другим пользователем.",
                extra={"expected_version": expected_version, "actual_version": actual_version},
            )
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
                "Операции группового дня выполняются через основной склад.",
                extra={"planning_root_warehouse_id": str(planning_root_id)},
            )
        active_plan = await plan_service.get_latest_plan_for_date(
            session,
            planning_root_id,
            planning_date,
        )
        if active_plan is not None:
            active_plan = await plan_service.lock_plan_for_request_mutation(
                session,
                active_plan.id,
            )
        if active_plan is None:
            if plan_id is not None or expected_plan_version is not None:
                raise ApiError(
                    409,
                    "PLANNING_DAY_PLAN_HEAD_CHANGED",
                    "Активный план дня уже изменился.",
                    extra={"actual_plan_id": None},
                )
        elif (
            plan_id is None
            or expected_plan_version is None
            or active_plan.id != plan_id
            or active_plan.version != expected_plan_version
        ):
            raise ApiError(
                409,
                "PLANNING_DAY_PLAN_VERSION_CONFLICT",
                "План дня уже изменён другим пользователем.",
                extra={
                    "expected_plan_id": str(plan_id) if plan_id is not None else None,
                    "actual_plan_id": str(active_plan.id),
                    "expected_plan_version": expected_plan_version,
                    "actual_plan_version": active_plan.version,
                },
            )
        previous_mode = (
            PlanningDayMode(policy.mode)
            if policy is not None
            else PlanningDayMode.DELIVERIES_AND_PICKUPS
        )
        await self._obsolete_prior_mode_work(session, warehouse_id, planning_date)
        if policy is None:
            policy = PlanningDayPolicy(
                warehouse_id=warehouse_id,
                date=planning_date,
                mode=mode,
                version=1,
                changed_by=actor,
            )
            session.add(policy)
        else:
            policy.mode = mode
            policy.version += 1
            policy.changed_by = actor
        await session.flush()
        for member in sorted(planning_group.members, key=lambda item: str(item.id)):
            generation = await advance_warehouse_capacity_generation(session, member.id)
            await mark_capacity_publication_pending(session, member.id, generation)
            day_fence = await session.scalar(
                select(SlotDayPlan)
                .where(
                    SlotDayPlan.warehouse_id == member.id,
                    SlotDayPlan.date == planning_date,
                )
                .with_for_update()
            )
            if day_fence is not None:
                day_fence.version += 1
                day_fence.source_revision = sha256(
                    (
                        f"{day_fence.source_revision}:planning-day-policy:"
                        f"{warehouse_id}:{policy.version}:{mode.value}"
                    ).encode()
                ).hexdigest()
        await session.flush()

        plan = active_plan
        conflicting_requests = await self._conflicting_requests(
            session,
            tuple(member.id for member in planning_group.members),
            planning_date,
            plan,
            mode,
        )
        event = LogisticsEvent(
            warehouse_id=warehouse_id,
            day=planning_date,
            plan_id=plan.id if plan is not None else None,
            event_type=LogisticsEventType.PLANNING_MODE_CHANGED,
            idempotency_key=idempotency_key,
            occurred_at=utc_now(),
            actor=actor,
            facts={
                "previous_mode": previous_mode.value,
                "new_mode": mode.value,
                "previous_version": actual_version,
                "new_version": policy.version,
                "expected_plan_id": str(plan_id) if plan_id is not None else None,
                "expected_plan_version": expected_plan_version,
                "conflict_count": len(conflicting_requests),
                "conflicting_request_ids": [str(item.id) for item in conflicting_requests],
            },
        )
        session.add(event)
        await session.flush()
        for request in conflicting_requests:
            await self._recovery.create_reschedule_work(
                session,
                event,
                request,
                source_plan=plan,
                cause_ru=(
                    f"{planning_date.strftime('%d.%m.%Y')} переведён в режим "
                    f"«{self._mode_ru(mode)}»."
                ),
            )
        await session.flush()
        return PlanningDayModeResult(
            warehouse_id=warehouse_id,
            day=planning_date,
            mode=mode,
            version=policy.version,
            event_id=event.id,
            conflict_count=len(conflicting_requests),
            pending_action_count=await self._projection.pending_action_count(
                session, warehouse_id, planning_date
            ),
        )

    @staticmethod
    async def _obsolete_prior_mode_work(
        session: AsyncSession,
        warehouse_id: UUID,
        planning_date: date,
    ) -> None:
        """Close only unresolved work derived from superseded day-mode events."""

        event_ids = tuple(
            await session.scalars(
                select(LogisticsEvent.id).where(
                    LogisticsEvent.warehouse_id == warehouse_id,
                    LogisticsEvent.day == planning_date,
                    LogisticsEvent.event_type == LogisticsEventType.PLANNING_MODE_CHANGED,
                )
            )
        )
        if not event_ids:
            return
        actions = tuple(
            await session.scalars(
                select(LogisticsHumanAction)
                .where(
                    LogisticsHumanAction.event_id.in_(event_ids),
                    LogisticsHumanAction.status.in_(_PENDING_ACTION_STATUSES),
                )
                .with_for_update()
            )
        )
        for action in actions:
            action.status = LogisticsActionStatus.OBSOLETE
            action.resolved_at = utc_now()
            action.version += 1
        notices = tuple(
            await session.scalars(
                select(LogisticsNotice)
                .where(
                    LogisticsNotice.event_id.in_(event_ids),
                    LogisticsNotice.status == LogisticsNoticeStatus.REQUIRES_ACTION,
                )
                .with_for_update()
            )
        )
        for notice in notices:
            notice.status = LogisticsNoticeStatus.OBSOLETE
            notice.resolved_at = utc_now()
        action_ids = tuple(action.id for action in actions)
        if action_ids:
            proposals = tuple(
                await session.scalars(
                    select(RecoveryProposal)
                    .where(
                        RecoveryProposal.action_id.in_(action_ids),
                        RecoveryProposal.status.in_(
                            (
                                RecoveryProposalStatus.PROPOSED,
                                RecoveryProposalStatus.FAILED,
                            )
                        ),
                    )
                    .with_for_update()
                )
            )
            for proposal in proposals:
                proposal.status = RecoveryProposalStatus.REJECTED
                proposal.failure_code = "SUPERSEDED_DAY_MODE"
                proposal.version += 1

    @staticmethod
    async def _conflicting_requests(
        session: AsyncSession,
        planning_member_ids: tuple[UUID, ...],
        planning_date: date,
        plan: RoutePlan | None,
        mode: PlanningDayMode,
    ) -> tuple[LogisticsRequest, ...]:
        """Return real dated commitments, including unassigned positions in a plan."""

        by_id: dict[UUID, LogisticsRequest] = {}
        if plan is not None:
            plan_tasks = [
                stop.task for cycle in plan.cycles for stop in cycle.stops if stop.task is not None
            ]
            plan_tasks.extend(item.task for item in plan.unassigned_tasks)
            for task in plan_tasks:
                if task.status in _TERMINAL_TASK_STATUSES:
                    continue
                request = task.request
                if not _mode_allows(mode, request.type):
                    by_id[request.id] = request
        committed = tuple(
            await session.scalars(
                select(LogisticsRequest)
                .where(
                    LogisticsRequest.warehouse_id.in_(planning_member_ids),
                    LogisticsRequest.scheduled_date == planning_date,
                    LogisticsRequest.status.in_(
                        (
                            RequestStatus.READY,
                            RequestStatus.PLANNED,
                            RequestStatus.IN_PROGRESS,
                            RequestStatus.UNASSIGNED,
                        )
                    ),
                )
                .options(
                    selectinload(LogisticsRequest.tasks),
                    selectinload(LogisticsRequest.date_options),
                )
            )
        )
        for request in committed:
            if not _mode_allows(mode, request.type):
                by_id[request.id] = request
        return tuple(by_id[key] for key in sorted(by_id, key=str))

    @staticmethod
    def _mode_ru(mode: PlanningDayMode) -> str:
        """Render the finite mode catalog without making text the business source."""

        return {
            PlanningDayMode.DELIVERIES_AND_PICKUPS: "Доставки + вывозы",
            PlanningDayMode.DELIVERIES_ONLY: "Только доставки",
            PlanningDayMode.PICKUPS_ONLY: "Только вывозы",
        }[mode]
