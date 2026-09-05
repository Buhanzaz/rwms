"""Shared bounded value helpers for dynamic logistics workflows."""

from __future__ import annotations

from hashlib import sha256

from app.models import (
    LogisticsActionStatus,
    LogisticsEvent,
    LogisticsEventType,
    LogisticsHumanAction,
    LogisticsHumanDecision,
    LogisticsNotice,
    PlanningDayMode,
    RecoveryProposal,
)
from app.models.domain import TaskStatus
from app.schemas.operations import (
    LogisticsEventCreate,
    LogisticsEventRead,
    LogisticsHumanActionRead,
    LogisticsHumanDecisionCreate,
    LogisticsHumanDecisionRead,
    LogisticsNoticeRead,
    RecoveryProposalRead,
)

PENDING_ACTION_STATUSES = (
    LogisticsActionStatus.PENDING,
    LogisticsActionStatus.IN_PROGRESS,
)
TERMINAL_TASK_STATUSES = (TaskStatus.COMPLETED, TaskStatus.CANCELLED)
PLAN_CHANGING_EVENT_TYPES = frozenset(
    {
        LogisticsEventType.VEHICLE_BREAKDOWN,
        LogisticsEventType.TRAILER_BREAKDOWN,
        LogisticsEventType.VEHICLE_DELAY,
        LogisticsEventType.DRIVER_UNAVAILABLE,
        LogisticsEventType.DELIVERY_CANCELLED,
        LogisticsEventType.PICKUP_CANCELLED,
        LogisticsEventType.ORDER_CANCELLED,
        LogisticsEventType.TASK_BLOCKED,
        LogisticsEventType.MANUAL_PLAN_CHANGE,
        LogisticsEventType.PLANNING_MODE_CHANGED,
    }
)
OWNER_TERMINAL_TASK_STATES = frozenset({"COMPLETED", "CANCELLED"})
OWNER_UNSAFE_SUFFIX_STATES = frozenset(
    {"REGISTERING", "CURRENT", "FINALIZING", "RECONCILIATION_REQUIRED"}
)


def event_read(event: LogisticsEvent) -> LogisticsEventRead:
    """Serialize one immutable event without lazy relationship access."""

    return LogisticsEventRead.model_validate(event)


def notice_read(notice: LogisticsNotice) -> LogisticsNoticeRead:
    """Serialize one deterministic system comment."""

    return LogisticsNoticeRead.model_validate(notice)


def action_read(action: LogisticsHumanAction) -> LogisticsHumanActionRead:
    """Serialize one dispatcher queue item."""

    return LogisticsHumanActionRead.model_validate(action)


def decision_read(decision: LogisticsHumanDecision) -> LogisticsHumanDecisionRead:
    """Serialize one immutable dispatcher outcome."""

    return LogisticsHumanDecisionRead.model_validate(decision)


def proposal_read(proposal: RecoveryProposal) -> RecoveryProposalRead:
    """Serialize one recovery proposal and its separated lifecycle state."""

    return RecoveryProposalRead.model_validate(proposal)


def event_command_hash(payload: LogisticsEventCreate) -> str:
    """Build a stable replay fingerprint without retaining authorization material."""

    unchanged_defaults = set()
    if payload.recovery_mode == "MANUAL":
        unchanged_defaults.add("recovery_mode")
    if payload.trailer_id is None:
        unchanged_defaults.add("trailer_id")
    # Preserve replay of incidents recorded before the optional recovery controls existed.
    return sha256(
        payload.model_dump_json(exclude_none=False, exclude=unchanged_defaults).encode()
    ).hexdigest()


def decision_command_hash(payload: LogisticsHumanDecisionCreate) -> str:
    """Fingerprint one decision so an idempotency key cannot change its meaning."""

    return sha256(payload.model_dump_json(exclude_none=False).encode()).hexdigest()


def mode_allows(mode: PlanningDayMode, request_type: str) -> bool:
    """Return whether one request direction can be planned under a day mode."""

    return (
        mode == PlanningDayMode.DELIVERIES_AND_PICKUPS
        or (mode == PlanningDayMode.DELIVERIES_ONLY and request_type == "DELIVERY")
        or (mode == PlanningDayMode.PICKUPS_ONLY and request_type == "PICKUP")
    )
