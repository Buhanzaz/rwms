"""Transport contracts for dynamic planning, incidents, recovery, and retention."""

from __future__ import annotations

import json
from datetime import date, time
from typing import Literal
from uuid import UUID

from pydantic import AwareDatetime, Field, field_validator, model_validator

from app.models.operations import (
    LogisticsDecisionType,
    LogisticsEventType,
    PlanningDayMode,
)
from app.schemas.domain import (
    ApiModel,
    RwmsApiModel,
    RwmsDriverShiftPlan,
    RwmsPlanningAssignmentReplacement,
)


class PlanningDayModeUpdate(ApiModel):
    """Version-fenced command that changes the optimizer mode for one day."""

    expected_version: int = Field(ge=0)
    plan_id: UUID | None = None
    expected_plan_version: int | None = Field(default=None, ge=1)
    mode: PlanningDayMode

    @model_validator(mode="after")
    def validate_plan_fence_pair(self) -> PlanningDayModeUpdate:
        """Require both active-plan fence fields together, or neither for an empty day."""

        if (self.plan_id is None) != (self.expected_plan_version is None):
            raise ValueError("plan_id and expected_plan_version must be provided together")
        return self


class LogisticsEventCreate(ApiModel):
    """Structured incident or plan-change fact submitted for impact analysis."""

    event_type: LogisticsEventType
    plan_id: UUID | None = None
    expected_plan_version: int | None = Field(default=None, ge=1)
    cycle_id: UUID | None = None
    request_id: UUID | None = None
    task_id: UUID | None = None
    vehicle_id: UUID | None = None
    trailer_id: UUID | None = None
    recovery_mode: Literal["MANUAL", "AUTO"] = "MANUAL"
    driver_shift_id: UUID | None = None
    occurred_at: AwareDatetime
    effective_at: AwareDatetime | None = None
    delay_minutes: int | None = Field(default=None, ge=1, le=1440)
    reason: str = Field(min_length=1, max_length=1000)
    source_event: str | None = Field(default=None, max_length=200)
    facts: dict[str, object] = Field(default_factory=dict)

    @field_validator("facts")
    @classmethod
    def validate_bounded_untrusted_facts(cls, value: dict[str, object]) -> dict[str, object]:
        """Bound operator metadata and reserve keys derived by the owning service."""

        reserved = {
            "command_hash",
            "expected_plan_version",
            "reason",
            "vehicle_id",
            "trailer_id",
            "recovery_mode",
            "driver_shift_id",
            "effective_at",
            "delay_minutes",
            "authoritative_task_states",
        }
        overlap = sorted(reserved.intersection(value))
        if overlap:
            raise ValueError(f"facts contains service-owned keys: {', '.join(overlap)}")
        if len(value) > 64:
            raise ValueError("facts cannot contain more than 64 keys")
        encoded = json.dumps(
            value,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        if len(encoded) > 16 * 1024:
            raise ValueError("facts cannot exceed 16384 UTF-8 bytes")
        return value

    @model_validator(mode="after")
    def validate_event_specific_fields(self) -> LogisticsEventCreate:
        """Require the resource or request identity needed by each event family."""

        if (
            self.event_type
            in {
                LogisticsEventType.VEHICLE_BREAKDOWN,
                LogisticsEventType.VEHICLE_DELAY,
            }
            and self.vehicle_id is None
        ):
            raise ValueError("vehicle_id is required for a vehicle incident")
        if self.event_type == LogisticsEventType.VEHICLE_DELAY and self.delay_minutes is None:
            raise ValueError("delay_minutes is required for VEHICLE_DELAY")
        if self.event_type == LogisticsEventType.TRAILER_BREAKDOWN and self.trailer_id is None:
            raise ValueError("trailer_id is required for TRAILER_BREAKDOWN")
        if self.event_type == LogisticsEventType.TRAILER_BREAKDOWN and (
            self.vehicle_id is not None or self.driver_shift_id is not None
        ):
            raise ValueError("a trailer incident targets only trailer_id")
        if self.trailer_id is not None and self.event_type != LogisticsEventType.TRAILER_BREAKDOWN:
            raise ValueError("trailer_id is only supported for TRAILER_BREAKDOWN")
        if self.recovery_mode == "AUTO" and self.event_type not in {
            LogisticsEventType.VEHICLE_BREAKDOWN,
            LogisticsEventType.TRAILER_BREAKDOWN,
        }:
            raise ValueError("AUTO recovery is only supported for vehicle or trailer breakdown")
        if (
            self.event_type == LogisticsEventType.DRIVER_UNAVAILABLE
            and self.driver_shift_id is None
        ):
            raise ValueError("driver_shift_id is required for DRIVER_UNAVAILABLE")
        if (
            self.event_type
            in {
                LogisticsEventType.DELIVERY_CANCELLED,
                LogisticsEventType.PICKUP_CANCELLED,
                LogisticsEventType.ORDER_CANCELLED,
            }
            and self.request_id is None
        ):
            raise ValueError("request_id is required for a cancellation")
        if self.event_type == LogisticsEventType.TASK_BLOCKED and self.task_id is None:
            raise ValueError("task_id is required for TASK_BLOCKED")
        return self


class LogisticsEventRead(ApiModel):
    """Immutable operational event returned in the day history."""

    id: UUID
    warehouse_id: UUID
    day: date
    plan_id: UUID | None
    cycle_id: UUID | None
    request_id: UUID | None
    task_id: UUID | None
    event_type: str
    source_event: str | None
    occurred_at: AwareDatetime
    actor: str
    facts: dict[str, object]
    created_at: AwareDatetime


class LogisticsNoticeRead(ApiModel):
    """Structured, deterministic system comment visible to a dispatcher."""

    id: UUID
    event_id: UUID
    warehouse_id: UUID
    day: date
    plan_id: UUID | None
    cycle_id: UUID | None
    request_id: UUID | None
    task_id: UUID | None
    notice_type: str
    severity: str
    reason_codes: list[str]
    facts: dict[str, object]
    message_ru: str
    recommended_action_ru: str | None
    requires_action: bool
    status: str
    created_at: AwareDatetime
    resolved_at: AwareDatetime | None


class LogisticsHumanActionRead(ApiModel):
    """One unresolved or historical dispatcher action with contact details."""

    id: UUID
    notice_id: UUID
    event_id: UUID
    warehouse_id: UUID
    day: date
    request_id: UUID | None
    action_type: str
    status: str
    version: int
    customer_name: str | None
    customer_type: str | None
    customer_phone: str | None
    current_date: date | None
    recommended_date: date | None
    alternative_dates: list[str]
    context: dict[str, object]
    created_at: AwareDatetime
    updated_at: AwareDatetime
    resolved_at: AwareDatetime | None


class LogisticsHumanDecisionCreate(ApiModel):
    """Version-fenced dispatcher outcome that becomes a future solve constraint."""

    expected_version: int = Field(ge=1)
    expected_proposal_version: int | None = Field(default=None, ge=1)
    decision_type: LogisticsDecisionType
    selected_date: date | None = None
    comment: str | None = Field(default=None, max_length=2000)

    @model_validator(mode="after")
    def validate_selected_date(self) -> LogisticsHumanDecisionCreate:
        """Require an explicit date only for the decision that chooses another day."""

        if (self.decision_type == LogisticsDecisionType.ACCEPT_OTHER_DATE) != (
            self.selected_date is not None
        ):
            raise ValueError(
                "ACCEPT_OTHER_DATE requires selected_date and other decisions forbid it"
            )
        if self.decision_type == LogisticsDecisionType.ACKNOWLEDGE_RESOLVED and (
            self.comment is None or not self.comment.strip()
        ):
            raise ValueError("ACKNOWLEDGE_RESOLVED requires an operator comment")
        return self


class LogisticsHumanDecisionRead(ApiModel):
    """Append-only record of the dispatcher response to an action."""

    id: UUID
    action_id: UUID
    event_id: UUID
    decision_type: str
    selected_date: date | None
    actor: str
    comment: str | None
    constraint_data: dict[str, object]
    created_at: AwareDatetime


class RecoveryProposalRead(ApiModel):
    """Recovery candidate with explicit customer-agreement/application state."""

    id: UUID
    event_id: UUID
    action_id: UUID | None
    warehouse_id: UUID
    day: date
    source_plan_id: UUID | None
    result_plan_id: UUID | None
    proposal_type: str
    status: str
    version: int
    summary_ru: str
    affected_request_ids: list[str]
    affected_task_ids: list[str]
    changes: dict[str, object]
    metrics: dict[str, object]
    failure_code: str | None
    created_at: AwareDatetime
    updated_at: AwareDatetime
    applied_at: AwareDatetime | None


class RecoveryProposalApply(ApiModel):
    """Optimistic command that applies only a ready recovery proposal."""

    expected_version: int = Field(ge=1)


class RequestRescheduleOptionsQuery(ApiModel):
    """Version-fenced date selected before loading authoritative delivery slots."""

    expected_request_version: int = Field(ge=1)
    date: date


class RequestRescheduleApply(ApiModel):
    """Existing-request reschedule command containing only authoritative owner fences."""

    expected_request_version: int = Field(ge=1)
    source_plan_id: UUID
    source_plan_version: int = Field(ge=1)
    expected_order_version: int = Field(ge=0)
    expected_session_version: int = Field(ge=0)
    slot_id: UUID
    slot_version: int = Field(ge=0)


class RequestRescheduleRetry(ApiModel):
    """Exact quarantined command identity used for an operator-authorized replay."""

    hold_id: UUID
    expected_quarantine_count: int = Field(ge=1)


class PlanningDayOperationsRead(ApiModel):
    """Latest bounded operational window with explicit truncation metadata."""

    warehouse_id: UUID
    day: date
    mode: PlanningDayMode
    mode_version: int
    pending_action_count: int
    truncated_collections: list[
        Literal["EVENTS", "NOTICES", "ACTIONS", "DECISIONS", "PROPOSALS"]
    ] = Field(default_factory=list)
    events: list[LogisticsEventRead]
    notices: list[LogisticsNoticeRead]
    actions: list[LogisticsHumanActionRead]
    decisions: list[LogisticsHumanDecisionRead]
    proposals: list[RecoveryProposalRead]


class PlanningDayModeResult(ApiModel):
    """Mode-change result including whether real conflicts required human action."""

    warehouse_id: UUID
    day: date
    mode: PlanningDayMode
    version: int
    event_id: UUID
    conflict_count: int
    pending_action_count: int


class LogisticsEventResult(ApiModel):
    """Impact analysis result for one idempotently accepted event."""

    event: LogisticsEventRead
    notices: list[LogisticsNoticeRead]
    actions: list[LogisticsHumanActionRead]
    proposals: list[RecoveryProposalRead]


class RwmsPlanningBaseTask(RwmsApiModel):
    """Existing low-priority base work exposed by the owning Spring service."""

    task_id: UUID = Field(alias="taskId")
    external_task_id: UUID = Field(alias="externalTaskId")
    kind: Literal[
        "GENERAL_MOVEMENT",
        "DELIVER_TO_REPAIR",
        "REMOVE_FROM_REPAIR",
        "CAPITAL_TO_PRODUCTION",
        "TRANSFER",
    ]
    unit_number: str = Field(alias="unitNumber", min_length=1, max_length=64)
    summary: str
    scheduled_date: date = Field(alias="scheduledDate")
    priority: int = Field(ge=1, le=5)
    state: Literal["SCHEDULED"]


class RwmsRescheduleOption(RwmsApiModel):
    """Owner-validated slot option for one RWMS order reschedule."""

    slot_id: UUID = Field(alias="slotId")
    slot_version: int = Field(alias="slotVersion", ge=0)
    date: date
    kind: Literal["FIXED_WINDOW", "DURING_DAY"]
    window_start: time | None = Field(alias="windowStart")
    window_end: time | None = Field(alias="windowEnd")
    delivery_price_rubles: int | None = Field(alias="deliveryPriceRubles", ge=0)
    expires_at: AwareDatetime = Field(alias="expiresAt")

    @model_validator(mode="after")
    def validate_window(self) -> RwmsRescheduleOption:
        """Keep fixed-window and during-day offers structurally unambiguous."""

        fixed = self.kind == "FIXED_WINDOW"
        if fixed != (self.window_start is not None and self.window_end is not None):
            raise ValueError("FIXED_WINDOW requires both bounds; DURING_DAY forbids them")
        if (
            self.window_start is not None
            and self.window_end is not None
            and self.window_start >= self.window_end
        ):
            raise ValueError("windowStart must precede windowEnd")
        return self


class RwmsPlanningCustomerContact(RwmsApiModel):
    """Normalized customer contact returned only for dispatcher coordination."""

    client_type: Literal["INDIVIDUAL", "SOLE_PROPRIETOR", "LEGAL_ENTITY"] = Field(
        alias="clientType"
    )
    client_name: str = Field(alias="clientName", min_length=1, max_length=512)
    contact_name: str | None = Field(alias="contactName", max_length=512)
    contact_phone: str | None = Field(alias="contactPhone", max_length=32)


class RwmsRescheduleOptions(RwmsApiModel):
    """Current order-version-fenced choices returned by the authoritative owner."""

    order_id: UUID = Field(alias="orderId")
    order_version: int = Field(alias="orderVersion", ge=0)
    session_id: UUID = Field(alias="sessionId")
    session_version: int = Field(alias="sessionVersion", ge=0)
    booking_id: UUID = Field(alias="bookingId")
    warehouse_id: UUID = Field(alias="warehouseId")
    customer: RwmsPlanningCustomerContact
    current_slot: RwmsRescheduleOption = Field(alias="currentSlot")
    options: list[RwmsRescheduleOption]


class RwmsPublishedAssignmentRemoval(RwmsApiModel):
    """Exact published task removed from its old day only by the owner saga."""

    document_id: UUID = Field(alias="documentId")
    external_task_id: UUID = Field(alias="externalTaskId")
    expected_task_version: int = Field(alias="expectedTaskVersion", ge=0)
    service_warehouse_id: UUID = Field(alias="serviceWarehouseId")
    scheduled_date: date = Field(alias="scheduledDate")
    unit_ids: list[UUID] = Field(alias="unitIds", min_length=1, max_length=2)

    @field_validator("unit_ids")
    @classmethod
    def validate_unique_units(cls, value: list[UUID]) -> list[UUID]:
        """Reject an ambiguous duplicate cabin identity in the removed slice."""

        if len(value) != len(set(value)):
            raise ValueError("unitIds must be unique")
        return value


class RwmsPublishedAssignmentWithdrawal(RwmsApiModel):
    """Complete old-day lineage revision used by the durable Spring recovery saga."""

    source_plan_id: UUID = Field(alias="sourcePlanId")
    expected_source_plan_version: int = Field(alias="expectedSourcePlanVersion", ge=1)
    replacement_plan_version: int = Field(alias="replacementPlanVersion", ge=2)
    warehouse_id: UUID = Field(alias="warehouseId")
    date: date
    removed_assignment: RwmsPublishedAssignmentRemoval = Field(alias="removedAssignment")
    remaining_assignments: list[RwmsPlanningAssignmentReplacement] = Field(
        alias="remainingAssignments", max_length=500
    )
    driver_shift_plans: list[RwmsDriverShiftPlan] = Field(alias="driverShiftPlans", max_length=500)

    @model_validator(mode="after")
    def validate_old_day_revision(self) -> RwmsPublishedAssignmentWithdrawal:
        """Fence the removed member and every retained task to one newer old-day revision."""

        if self.replacement_plan_version <= self.expected_source_plan_version:
            raise ValueError("replacementPlanVersion must be strictly newer")
        if self.removed_assignment.scheduled_date != self.date:
            raise ValueError("removed assignment must belong to the withdrawal date")
        if any(item.scheduled_date != self.date for item in self.remaining_assignments):
            raise ValueError("remaining assignments must belong to the withdrawal date")
        task_ids = [item.external_task_id for item in self.remaining_assignments]
        if len(task_ids) != len(set(task_ids)):
            raise ValueError("remainingAssignments must contain unique externalTaskId values")
        if self.removed_assignment.external_task_id in set(task_ids):
            raise ValueError("removedAssignment cannot remain in the replacement membership")
        return self


class RwmsRescheduleCommand(RwmsApiModel):
    """Selected authoritative slot and fences submitted after customer agreement."""

    expected_order_version: int = Field(alias="expectedOrderVersion", ge=0)
    expected_session_version: int = Field(alias="expectedSessionVersion", ge=0)
    slot_id: UUID = Field(alias="slotId")
    slot_version: int = Field(alias="slotVersion", ge=0)
    decision_code: Literal["CUSTOMER_AGREED_RECOMMENDED", "CUSTOMER_AGREED_ALTERNATIVE"] = Field(
        alias="decisionCode"
    )
    decision_actor_subject_id: UUID = Field(alias="decisionActorSubjectId")
    decision_reason: str = Field(alias="decisionReason", min_length=1, max_length=2000)
    published_plan_withdrawal: RwmsPublishedAssignmentWithdrawal | None = Field(
        default=None, alias="publishedPlanWithdrawal"
    )


class RwmsPublishedAssignmentWithdrawalResult(RwmsApiModel):
    """Durable old-day tombstone returned only after the owner saga converges."""

    source_plan_id: UUID = Field(alias="sourcePlanId")
    source_plan_version: int = Field(alias="sourcePlanVersion", ge=2)
    removed_external_task_id: UUID = Field(alias="removedExternalTaskId")
    removed_task_version: int = Field(alias="removedTaskVersion", ge=0)
    state: Literal["COMPLETE"]


class RwmsRescheduleResult(RwmsApiModel):
    """Authoritative order version and selected service promise after rescheduling."""

    order_id: UUID = Field(alias="orderId")
    order_version: int = Field(alias="orderVersion", ge=0)
    session_id: UUID = Field(alias="sessionId")
    session_version: int = Field(alias="sessionVersion", ge=0)
    booking_id: UUID = Field(alias="bookingId")
    warehouse_id: UUID = Field(alias="warehouseId")
    confirmed_slot: RwmsRescheduleOption = Field(alias="confirmedSlot")
    published_plan_withdrawal: RwmsPublishedAssignmentWithdrawalResult | None = Field(
        alias="publishedPlanWithdrawal"
    )


class RequestRescheduleSlotRead(ApiModel):
    """One owner-calculated slot exposed without browser-authored timing fields."""

    slot_id: UUID
    slot_version: int = Field(ge=0)
    date: date
    kind: Literal["FIXED_WINDOW", "DURING_DAY"]
    window_start: time | None
    window_end: time | None
    delivery_price_rubles: int | None = Field(ge=0)
    expires_at: AwareDatetime


class RequestRescheduleOptionsRead(ApiModel):
    """All owner-calculated slots for the dispatcher-selected date and current fences."""

    request_id: UUID
    request_version: int = Field(ge=1)
    source_plan_id: UUID
    source_plan_version: int = Field(ge=1)
    order_id: UUID
    order_version: int = Field(ge=0)
    session_id: UUID
    session_version: int = Field(ge=0)
    current_slot: RequestRescheduleSlotRead
    options: list[RequestRescheduleSlotRead]


class RequestRescheduleResultRead(ApiModel):
    """Converged local request plus the authoritative owner receipt returned to the panel."""

    request_id: UUID
    request_version: int = Field(ge=1)
    order_id: UUID
    order_version: int = Field(ge=0)
    session_id: UUID
    session_version: int = Field(ge=0)
    scheduled_date: date
    confirmed_slot: RequestRescheduleSlotRead


class RetentionDryRunRead(ApiModel):
    """Safe retention report that never deletes or archives rows by itself."""

    generated_at: AwareDatetime
    policies: list[dict[str, object]]
    legal_hold_count: int
    candidate_counts: dict[str, int]
    destructive_purge_enabled: bool = False
