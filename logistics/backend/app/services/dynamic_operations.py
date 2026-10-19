"""Public facade for dynamic logistics operational workflows."""

from __future__ import annotations

from datetime import date, datetime, timedelta
from uuid import UUID, uuid5

from sqlalchemy.ext.asyncio import AsyncSession

from app.db import utc_now
from app.errors import ApiError
from app.integrations.rwms import RwmsPlanningClient
from app.models import LogisticsEvent, PlanningDayMode, RecoveryProposalStatus
from app.schemas.operations import (
    LogisticsEventCreate,
    LogisticsEventResult,
    LogisticsHumanDecisionCreate,
    LogisticsHumanDecisionRead,
    PlanningDayModeResult,
    PlanningDayOperationsRead,
    RecoveryProposalRead,
    RetentionDryRunRead,
)
from app.services.dynamic_day_modes import PlanningDayModeWorkflow
from app.services.dynamic_impacts import LogisticsImpactAnalyzer
from app.services.dynamic_projection import DynamicOperationsProjection
from app.services.dynamic_recovery import RecoveryProposalWorkflow
from app.services.plans import PlannerFacade


class DynamicLogisticsService:
    """Delegate each operational use case to one cohesive backend collaborator."""

    def __init__(
        self,
        planner: PlannerFacade,
        rwms_client: RwmsPlanningClient | None,
    ) -> None:
        projection = DynamicOperationsProjection()
        recovery = RecoveryProposalWorkflow(planner, rwms_client, projection)
        self._projection = projection
        self._day_modes = PlanningDayModeWorkflow(rwms_client, recovery, projection)
        self._impacts = LogisticsImpactAnalyzer(planner, rwms_client, projection)
        self._recovery = recovery

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
        """Delegate one version-fenced root planning-day mode command."""

        return await self._day_modes.set_day_mode(
            session,
            warehouse_id,
            planning_date,
            expected_version=expected_version,
            plan_id=plan_id,
            expected_plan_version=expected_plan_version,
            mode=mode,
            actor=actor,
            idempotency_key=idempotency_key,
        )

    async def day_operations(
        self,
        session: AsyncSession,
        warehouse_id: UUID,
        planning_date: date,
    ) -> PlanningDayOperationsRead:
        """Delegate the bounded operational day projection."""

        return await self._projection.day_operations(session, warehouse_id, planning_date)

    async def register_event(
        self,
        session: AsyncSession,
        warehouse_id: UUID,
        planning_date: date,
        payload: LogisticsEventCreate,
        *,
        actor: str,
        idempotency_key: str,
        actor_subject_id: UUID | None = None,
    ) -> LogisticsEventResult:
        """Persist the incident before optionally applying one fenced recovery attempt.

        AUTO reuses the manual saga, including owner receipts and exact road checks.
        Replaying the event resumes an interrupted attempt, never a failed one; the
        latter remains visible for an explicit dispatcher retry after fixing resources.
        """

        if payload.recovery_mode == "AUTO" and actor_subject_id is None:
            raise ApiError(403, "RECOVERY_ACTOR_REQUIRED", "Verified recovery actor is required")
        result = await self._impacts.register_event(
            session,
            warehouse_id,
            planning_date,
            payload,
            actor=actor,
            idempotency_key=idempotency_key,
        )
        if payload.recovery_mode != "AUTO":
            return result
        assert actor_subject_id is not None
        event_id = result.event.id
        # Resource loss must survive a solver outage or a lost HTTP response.
        await session.commit()
        for proposal in result.proposals:
            if proposal.status != RecoveryProposalStatus.READY_TO_APPLY:
                continue
            active_apply = proposal.changes.get("active_apply")
            expected_version = proposal.version
            if isinstance(active_apply, dict) and isinstance(
                proposal.changes.get("prepared_recovery"), dict
            ):
                expected_version = int(active_apply["expected_version"])
            elif isinstance(active_apply, dict):
                # Repeated HTTP requests must not steal a live solve. A stale,
                # fenced claim may be resumed after a bounded five-minute lease.
                started_at = datetime.fromisoformat(str(active_apply["started_at"]))
                if utc_now() - started_at < timedelta(minutes=5):
                    continue
            await self._recovery.apply_proposal(
                session,
                proposal.id,
                expected_version=expected_version,
                actor=actor,
                actor_subject_id=actor_subject_id,
                idempotency_key=str(uuid5(event_id, "automatic-resource-recovery")),
            )
            await session.commit()
        event = await session.get(LogisticsEvent, event_id)
        assert event is not None
        return await self._projection.event_result(session, event)

    async def decide_action(
        self,
        session: AsyncSession,
        action_id: UUID,
        payload: LogisticsHumanDecisionCreate,
        *,
        actor: str,
        idempotency_key: str,
    ) -> LogisticsHumanDecisionRead:
        """Delegate one immutable, version-fenced dispatcher decision."""

        return await self._recovery.decide_action(
            session,
            action_id,
            payload,
            actor=actor,
            idempotency_key=idempotency_key,
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
        """Delegate a recoverable proposal application attempt."""

        return await self._recovery.apply_proposal(
            session,
            proposal_id,
            expected_version=expected_version,
            actor=actor,
            actor_subject_id=actor_subject_id,
            idempotency_key=idempotency_key,
        )

    async def retention_dry_run(self, session: AsyncSession) -> RetentionDryRunRead:
        """Delegate the non-destructive retention foundation report."""

        return await self._projection.retention_dry_run(session)
