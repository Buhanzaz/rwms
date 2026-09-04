"""Read projections and retention reporting for operational logistics."""



from __future__ import annotations

from datetime import date, timedelta
from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.db import utc_now
from app.errors import ApiError, not_found
from app.models import (
    LegalHold,
    LogisticsEvent,
    LogisticsHumanAction,
    LogisticsHumanDecision,
    LogisticsNotice,
    LogisticsRequest,
    PlanningDayMode,
    PlanningDayPolicy,
    RecoveryProposal,
    RetentionPolicy,
    Warehouse,
)
from app.schemas.operations import (
    LogisticsEventResult,
    PlanningDayOperationsRead,
    RetentionDryRunRead,
)
from app.services.dynamic_support import (
    PENDING_ACTION_STATUSES as _PENDING_ACTION_STATUSES,
)
from app.services.dynamic_support import (
    action_read as _action_read,
)
from app.services.dynamic_support import (
    decision_read as _decision_read,
)
from app.services.dynamic_support import (
    event_read as _event_read,
)
from app.services.dynamic_support import (
    notice_read as _notice_read,
)
from app.services.dynamic_support import (
    proposal_read as _proposal_read,
)


class DynamicOperationsProjection:
    """Read projections and retention reporting for operational logistics."""

    _EVENT_LIMIT = 1000
    _NOTICE_LIMIT = 2000
    _ACTION_LIMIT = 2000
    _DECISION_LIMIT = 4000
    _PROPOSAL_LIMIT = 2000

    async def day_operations(
        self,
        session: AsyncSession,
        warehouse_id: UUID,
        planning_date: date,
    ) -> PlanningDayOperationsRead:
        """Read the latest bounded window and an exact unresolved-action count."""

        if await session.get(Warehouse, warehouse_id) is None:
            raise not_found("warehouse", warehouse_id)
        policy = await self.policy(session, warehouse_id, planning_date)
        raw_events = tuple(
            await session.scalars(
                select(LogisticsEvent)
                .where(
                    LogisticsEvent.warehouse_id == warehouse_id,
                    LogisticsEvent.day == planning_date,
                )
                .order_by(LogisticsEvent.created_at.desc(), LogisticsEvent.id.desc())
                .limit(self._EVENT_LIMIT + 1)
            )
        )
        raw_notices = tuple(
            await session.scalars(
                select(LogisticsNotice)
                .where(
                    LogisticsNotice.warehouse_id == warehouse_id,
                    LogisticsNotice.day == planning_date,
                )
                .order_by(LogisticsNotice.created_at.desc(), LogisticsNotice.id.desc())
                .limit(self._NOTICE_LIMIT + 1)
            )
        )
        raw_actions = tuple(
            await session.scalars(
                select(LogisticsHumanAction)
                .where(
                    LogisticsHumanAction.warehouse_id == warehouse_id,
                    LogisticsHumanAction.day == planning_date,
                )
                .order_by(
                    LogisticsHumanAction.created_at.desc(),
                    LogisticsHumanAction.id.desc(),
                )
                .limit(self._ACTION_LIMIT + 1)
            )
        )
        events = tuple(reversed(raw_events[: self._EVENT_LIMIT]))
        notices = tuple(reversed(raw_notices[: self._NOTICE_LIMIT]))
        actions = tuple(reversed(raw_actions[: self._ACTION_LIMIT]))
        action_ids = [action.id for action in actions]
        raw_decisions = (
            tuple(
                await session.scalars(
                    select(LogisticsHumanDecision)
                    .where(LogisticsHumanDecision.action_id.in_(action_ids))
                    .order_by(
                        LogisticsHumanDecision.created_at.desc(),
                        LogisticsHumanDecision.id.desc(),
                    )
                    .limit(self._DECISION_LIMIT + 1)
                )
            )
            if action_ids
            else ()
        )
        decisions = tuple(reversed(raw_decisions[: self._DECISION_LIMIT]))
        raw_proposals = tuple(
            await session.scalars(
                select(RecoveryProposal)
                .where(
                    RecoveryProposal.warehouse_id == warehouse_id,
                    RecoveryProposal.day == planning_date,
                )
                .order_by(RecoveryProposal.created_at.desc(), RecoveryProposal.id.desc())
                .limit(self._PROPOSAL_LIMIT + 1)
            )
        )
        proposals = tuple(reversed(raw_proposals[: self._PROPOSAL_LIMIT]))
        truncated_collections = [
            name
            for name, truncated in (
                ("EVENTS", len(raw_events) > self._EVENT_LIMIT),
                ("NOTICES", len(raw_notices) > self._NOTICE_LIMIT),
                ("ACTIONS", len(raw_actions) > self._ACTION_LIMIT),
                (
                    "DECISIONS",
                    len(raw_decisions) > self._DECISION_LIMIT
                    or len(raw_actions) > self._ACTION_LIMIT,
                ),
                ("PROPOSALS", len(raw_proposals) > self._PROPOSAL_LIMIT),
            )
            if truncated
        ]
        return PlanningDayOperationsRead(
            warehouse_id=warehouse_id,
            day=planning_date,
            mode=(
                PlanningDayMode(policy.mode)
                if policy is not None
                else PlanningDayMode.DELIVERIES_AND_PICKUPS
            ),
            mode_version=policy.version if policy is not None else 0,
            pending_action_count=await self.pending_action_count(
                session,
                warehouse_id,
                planning_date,
            ),
            truncated_collections=truncated_collections,
            events=[_event_read(item) for item in events],
            notices=[_notice_read(item) for item in notices],
            actions=[_action_read(item) for item in actions],
            decisions=[_decision_read(item) for item in decisions],
            proposals=[_proposal_read(item) for item in proposals],
        )


    async def retention_dry_run(self, session: AsyncSession) -> RetentionDryRunRead:
        """Report retention candidates while leaving destructive purge disabled."""

        policies = tuple(
            await session.scalars(select(RetentionPolicy).order_by(RetentionPolicy.data_class))
        )
        legal_hold_count = int(
            await session.scalar(
                select(func.count(LegalHold.id)).where(LegalHold.released_at.is_(None))
            )
            or 0
        )
        now = utc_now()
        candidate_counts: dict[str, int] = {}
        for policy in policies:
            if policy.data_class == "BUSINESS_AUDIT_PROOF":
                cutoff = now - timedelta(days=policy.online_days)
                candidate_counts[policy.data_class] = int(
                    await session.scalar(
                        select(func.count(LogisticsEvent.id)).where(
                            LogisticsEvent.created_at < cutoff
                        )
                    )
                    or 0
                )
            else:
                candidate_counts[policy.data_class] = 0
        return RetentionDryRunRead(
            generated_at=now,
            policies=[
                {
                    "data_class": policy.data_class,
                    "online_days": policy.online_days,
                    "archive_days": policy.archive_days,
                    "purge_enabled": policy.purge_enabled,
                    "description_ru": policy.description_ru,
                }
                for policy in policies
            ],
            legal_hold_count=legal_hold_count,
            candidate_counts=candidate_counts,
            destructive_purge_enabled=False,
        )


    async def event_result(
        self,
        session: AsyncSession,
        event: LogisticsEvent,
    ) -> LogisticsEventResult:
        """Load one event's bounded derived objects for command/replay responses."""

        notices = tuple(
            await session.scalars(
                select(LogisticsNotice)
                .where(LogisticsNotice.event_id == event.id)
                .order_by(LogisticsNotice.created_at, LogisticsNotice.id)
            )
        )
        actions = tuple(
            await session.scalars(
                select(LogisticsHumanAction)
                .where(LogisticsHumanAction.event_id == event.id)
                .order_by(LogisticsHumanAction.created_at, LogisticsHumanAction.id)
            )
        )
        proposals = tuple(
            await session.scalars(
                select(RecoveryProposal)
                .where(RecoveryProposal.event_id == event.id)
                .order_by(RecoveryProposal.created_at, RecoveryProposal.id)
            )
        )
        return LogisticsEventResult(
            event=_event_read(event),
            notices=[_notice_read(item) for item in notices],
            actions=[_action_read(item) for item in actions],
            proposals=[_proposal_read(item) for item in proposals],
        )


    @staticmethod
    async def policy(
        session: AsyncSession,
        warehouse_id: UUID,
        planning_date: date,
    ) -> PlanningDayPolicy | None:
        """Read the explicit policy; absence means the stable mixed default."""

        result = await session.scalar(
            select(PlanningDayPolicy).where(
                PlanningDayPolicy.warehouse_id == warehouse_id,
                PlanningDayPolicy.date == planning_date,
            )
        )
        return result


    @staticmethod
    async def pending_action_count(
        session: AsyncSession,
        warehouse_id: UUID,
        planning_date: date,
    ) -> int:
        """Count only unresolved dispatcher work for the selected day."""

        return int(
            await session.scalar(
                select(func.count(LogisticsHumanAction.id)).where(
                    LogisticsHumanAction.warehouse_id == warehouse_id,
                    LogisticsHumanAction.day == planning_date,
                    LogisticsHumanAction.status.in_(_PENDING_ACTION_STATUSES),
                )
            )
            or 0
        )


    @staticmethod
    async def request(
        session: AsyncSession,
        request_id: UUID | None,
        *,
        for_update: bool = False,
    ) -> LogisticsRequest:
        """Load one request with dates/tasks and optional optimistic row lock."""

        if request_id is None:
            raise ApiError(422, "REQUEST_REQUIRED", "Для действия не указано задание.")
        statement = (
            select(LogisticsRequest)
            .where(LogisticsRequest.id == request_id)
            .options(
                selectinload(LogisticsRequest.date_options),
                selectinload(LogisticsRequest.tasks),
            )
        )
        if for_update:
            statement = statement.with_for_update()
        request = await session.scalar(statement)
        if request is None:
            raise not_found("logistics_request", request_id)
        return request
