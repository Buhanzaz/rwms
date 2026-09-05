"""Durable operational logistics events, recovery work, and retention metadata."""

from __future__ import annotations

from datetime import date, datetime
from enum import StrEnum
from typing import Any
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
    Uuid,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.mutable import MutableDict, MutableList
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, TimestampMixin, UuidPrimaryKeyMixin


class PlanningDayMode(StrEnum):
    """Task directions permitted by the optimizer for one warehouse-local day."""

    DELIVERIES_AND_PICKUPS = "DELIVERIES_AND_PICKUPS"
    DELIVERIES_ONLY = "DELIVERIES_ONLY"
    PICKUPS_ONLY = "PICKUPS_ONLY"


class LogisticsEventType(StrEnum):
    """Facts that can invalidate or constrain the current operational plan."""

    VEHICLE_BREAKDOWN = "VEHICLE_BREAKDOWN"
    TRAILER_BREAKDOWN = "TRAILER_BREAKDOWN"
    VEHICLE_DELAY = "VEHICLE_DELAY"
    DRIVER_UNAVAILABLE = "DRIVER_UNAVAILABLE"
    DELIVERY_CANCELLED = "DELIVERY_CANCELLED"
    PICKUP_CANCELLED = "PICKUP_CANCELLED"
    ORDER_CANCELLED = "ORDER_CANCELLED"
    TASK_BLOCKED = "TASK_BLOCKED"
    MANUAL_PLAN_CHANGE = "MANUAL_PLAN_CHANGE"
    PLANNING_MODE_CHANGED = "PLANNING_MODE_CHANGED"


class LogisticsNoticeStatus(StrEnum):
    """Operator-visible lifecycle of a structured system comment."""

    REQUIRES_ACTION = "REQUIRES_ACTION"
    ACCEPTED = "ACCEPTED"
    REJECTED = "REJECTED"
    COMPLETED = "COMPLETED"
    OBSOLETE = "OBSOLETE"


class LogisticsActionStatus(StrEnum):
    """Mutable queue state of a human logistics action."""

    PENDING = "PENDING"
    IN_PROGRESS = "IN_PROGRESS"
    RESOLVED = "RESOLVED"
    OBSOLETE = "OBSOLETE"


class LogisticsDecisionType(StrEnum):
    """Immutable dispatcher outcome for a pending customer or operational action."""

    ACCEPT_RECOMMENDATION = "ACCEPT_RECOMMENDATION"
    ACCEPT_OTHER_DATE = "ACCEPT_OTHER_DATE"
    REJECT = "REJECT"
    UNREACHABLE = "UNREACHABLE"
    ACCEPT_DELAY = "ACCEPT_DELAY"
    REJECT_DELAY = "REJECT_DELAY"
    ACKNOWLEDGE_RESOLVED = "ACKNOWLEDGE_RESOLVED"


class RecoveryProposalStatus(StrEnum):
    """Separation between computed advice, agreement, readiness, and application."""

    PROPOSED = "PROPOSED"
    CUSTOMER_AGREED = "CUSTOMER_AGREED"
    READY_TO_APPLY = "READY_TO_APPLY"
    APPLIED = "APPLIED"
    REJECTED = "REJECTED"
    FAILED = "FAILED"


class PlanningDayPolicy(UuidPrimaryKeyMixin, TimestampMixin, Base):
    """Version-fenced optimizer constraints for one warehouse-local planning date."""

    __tablename__ = "planning_day_policies"
    __table_args__ = (
        UniqueConstraint("warehouse_id", "date", name="uq_planning_day_policies_warehouse_date"),
        CheckConstraint("version >= 1", name="positive_version"),
        CheckConstraint(
            "mode IN ('DELIVERIES_AND_PICKUPS', 'DELIVERIES_ONLY', 'PICKUPS_ONLY')",
            name="valid_mode",
        ),
        Index("ix_planning_day_policies_warehouse_date", "warehouse_id", "date"),
    )

    warehouse_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("warehouses.id", ondelete="CASCADE"), nullable=False
    )
    date: Mapped[date] = mapped_column(Date, nullable=False)
    mode: Mapped[str] = mapped_column(
        String(40), nullable=False, default=PlanningDayMode.DELIVERIES_AND_PICKUPS
    )
    version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    changed_by: Mapped[str] = mapped_column(String(200), nullable=False)


class LogisticsEvent(UuidPrimaryKeyMixin, Base):
    """Immutable operational fact that starts impact analysis and recovery planning."""

    __tablename__ = "logistics_events"
    __table_args__ = (
        UniqueConstraint(
            "warehouse_id", "idempotency_key", name="uq_logistics_events_warehouse_key"
        ),
        CheckConstraint("length(idempotency_key) > 0", name="nonempty_idempotency_key"),
        CheckConstraint("jsonb_typeof(facts) = 'object'", name="facts_object"),
        Index("ix_logistics_events_warehouse_day", "warehouse_id", "day", "created_at"),
        Index("ix_logistics_events_plan_id", "plan_id"),
    )

    warehouse_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("warehouses.id", ondelete="RESTRICT"), nullable=False
    )
    day: Mapped[date] = mapped_column(Date, nullable=False)
    plan_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_plans.id", ondelete="SET NULL")
    )
    cycle_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_cycles.id", ondelete="SET NULL")
    )
    request_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("logistics_requests.id", ondelete="SET NULL")
    )
    task_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("planning_tasks.id", ondelete="SET NULL")
    )
    event_type: Mapped[str] = mapped_column(String(64), nullable=False)
    source_event: Mapped[str | None] = mapped_column(String(200))
    idempotency_key: Mapped[str] = mapped_column(String(200), nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    actor: Mapped[str] = mapped_column(String(200), nullable=False)
    facts: Mapped[dict[str, Any]] = mapped_column(
        MutableDict.as_mutable(JSONB), nullable=False, default=dict
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class LogisticsNotice(UuidPrimaryKeyMixin, Base):
    """Durable deterministic Russian system comment backed by structured facts."""

    __tablename__ = "logistics_notices"
    __table_args__ = (
        CheckConstraint(
            "severity IN ('INFO', 'WARNING', 'ERROR', 'CRITICAL')", name="valid_severity"
        ),
        CheckConstraint(
            "status IN ('REQUIRES_ACTION', 'ACCEPTED', 'REJECTED', 'COMPLETED', 'OBSOLETE')",
            name="valid_status",
        ),
        CheckConstraint("jsonb_typeof(reason_codes) = 'array'", name="reason_codes_array"),
        CheckConstraint("jsonb_typeof(facts) = 'object'", name="facts_object"),
        Index("ix_logistics_notices_day_status", "warehouse_id", "day", "status"),
        Index("ix_logistics_notices_event_id", "event_id"),
    )

    event_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("logistics_events.id", ondelete="CASCADE"), nullable=False
    )
    warehouse_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("warehouses.id", ondelete="RESTRICT"), nullable=False
    )
    day: Mapped[date] = mapped_column(Date, nullable=False)
    plan_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_plans.id", ondelete="SET NULL")
    )
    cycle_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_cycles.id", ondelete="SET NULL")
    )
    request_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("logistics_requests.id", ondelete="SET NULL")
    )
    task_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("planning_tasks.id", ondelete="SET NULL")
    )
    notice_type: Mapped[str] = mapped_column(String(64), nullable=False)
    severity: Mapped[str] = mapped_column(String(16), nullable=False)
    reason_codes: Mapped[list[str]] = mapped_column(
        MutableList.as_mutable(JSONB), nullable=False, default=list
    )
    facts: Mapped[dict[str, Any]] = mapped_column(
        MutableDict.as_mutable(JSONB), nullable=False, default=dict
    )
    message_ru: Mapped[str] = mapped_column(Text, nullable=False)
    recommended_action_ru: Mapped[str | None] = mapped_column(Text)
    requires_action: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    status: Mapped[str] = mapped_column(
        String(32), nullable=False, default=LogisticsNoticeStatus.COMPLETED
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class LogisticsHumanAction(UuidPrimaryKeyMixin, TimestampMixin, Base):
    """Versioned action queue item containing the minimum customer contact snapshot."""

    __tablename__ = "logistics_human_actions"
    __table_args__ = (
        CheckConstraint("version >= 1", name="positive_version"),
        CheckConstraint(
            "status IN ('PENDING', 'IN_PROGRESS', 'RESOLVED', 'OBSOLETE')",
            name="valid_status",
        ),
        CheckConstraint("jsonb_typeof(alternative_dates) = 'array'", name="dates_array"),
        CheckConstraint("jsonb_typeof(context) = 'object'", name="context_object"),
        Index("ix_logistics_human_actions_day_status", "warehouse_id", "day", "status"),
        Index("ix_logistics_human_actions_notice_id", "notice_id"),
    )

    notice_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("logistics_notices.id", ondelete="CASCADE"), nullable=False
    )
    event_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("logistics_events.id", ondelete="CASCADE"), nullable=False
    )
    warehouse_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("warehouses.id", ondelete="RESTRICT"), nullable=False
    )
    day: Mapped[date] = mapped_column(Date, nullable=False)
    request_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("logistics_requests.id", ondelete="SET NULL")
    )
    action_type: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(
        String(32), nullable=False, default=LogisticsActionStatus.PENDING
    )
    version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    customer_name: Mapped[str | None] = mapped_column(String(200))
    customer_type: Mapped[str | None] = mapped_column(String(32))
    customer_phone: Mapped[str | None] = mapped_column(String(64))
    current_date: Mapped[date | None] = mapped_column(Date)
    recommended_date: Mapped[date | None] = mapped_column(Date)
    alternative_dates: Mapped[list[str]] = mapped_column(
        MutableList.as_mutable(JSONB), nullable=False, default=list
    )
    context: Mapped[dict[str, Any]] = mapped_column(
        MutableDict.as_mutable(JSONB), nullable=False, default=dict
    )
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class LogisticsHumanDecision(UuidPrimaryKeyMixin, Base):
    """Append-only operator decision that becomes a constraint for later solves."""

    __tablename__ = "logistics_human_decisions"
    __table_args__ = (
        UniqueConstraint(
            "action_id", "idempotency_key", name="uq_logistics_human_decisions_action_key"
        ),
        CheckConstraint("length(idempotency_key) > 0", name="nonempty_idempotency_key"),
        CheckConstraint("jsonb_typeof(constraint_data) = 'object'", name="constraint_object"),
        Index("ix_logistics_human_decisions_action_id", "action_id", "created_at"),
    )

    action_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("logistics_human_actions.id", ondelete="RESTRICT"),
        nullable=False,
    )
    event_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("logistics_events.id", ondelete="RESTRICT"), nullable=False
    )
    decision_type: Mapped[str] = mapped_column(String(40), nullable=False)
    selected_date: Mapped[date | None] = mapped_column(Date)
    actor: Mapped[str] = mapped_column(String(200), nullable=False)
    comment: Mapped[str | None] = mapped_column(Text)
    idempotency_key: Mapped[str] = mapped_column(String(200), nullable=False)
    constraint_data: Mapped[dict[str, Any]] = mapped_column(
        MutableDict.as_mutable(JSONB), nullable=False, default=dict
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class RecoveryProposal(UuidPrimaryKeyMixin, TimestampMixin, Base):
    """Versioned recovery candidate whose application is fenced from customer agreement."""

    __tablename__ = "recovery_proposals"
    __table_args__ = (
        CheckConstraint("version >= 1", name="positive_version"),
        CheckConstraint(
            "status IN ('PROPOSED', 'CUSTOMER_AGREED', 'READY_TO_APPLY', 'APPLIED', "
            "'REJECTED', 'FAILED')",
            name="valid_status",
        ),
        CheckConstraint("jsonb_typeof(affected_request_ids) = 'array'", name="request_ids_array"),
        CheckConstraint("jsonb_typeof(affected_task_ids) = 'array'", name="task_ids_array"),
        CheckConstraint("jsonb_typeof(changes) = 'object'", name="changes_object"),
        CheckConstraint("jsonb_typeof(metrics) = 'object'", name="metrics_object"),
        Index("ix_recovery_proposals_day_status", "warehouse_id", "day", "status"),
        Index("ix_recovery_proposals_event_id", "event_id"),
    )

    event_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("logistics_events.id", ondelete="CASCADE"), nullable=False
    )
    action_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("logistics_human_actions.id", ondelete="SET NULL")
    )
    warehouse_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("warehouses.id", ondelete="RESTRICT"), nullable=False
    )
    day: Mapped[date] = mapped_column(Date, nullable=False)
    source_plan_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_plans.id", ondelete="SET NULL")
    )
    result_plan_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("route_plans.id", ondelete="SET NULL")
    )
    proposal_type: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(
        String(32), nullable=False, default=RecoveryProposalStatus.PROPOSED
    )
    version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    summary_ru: Mapped[str] = mapped_column(Text, nullable=False)
    affected_request_ids: Mapped[list[str]] = mapped_column(
        MutableList.as_mutable(JSONB), nullable=False, default=list
    )
    affected_task_ids: Mapped[list[str]] = mapped_column(
        MutableList.as_mutable(JSONB), nullable=False, default=list
    )
    changes: Mapped[dict[str, Any]] = mapped_column(
        MutableDict.as_mutable(JSONB), nullable=False, default=dict
    )
    metrics: Mapped[dict[str, Any]] = mapped_column(
        MutableDict.as_mutable(JSONB), nullable=False, default=dict
    )
    failure_code: Mapped[str | None] = mapped_column(String(128))
    applied_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class RetentionPolicy(UuidPrimaryKeyMixin, TimestampMixin, Base):
    """Non-destructive metadata describing an approved online/archive retention class."""

    __tablename__ = "retention_policies"
    __table_args__ = (
        UniqueConstraint("data_class", name="uq_retention_policies_data_class"),
        CheckConstraint("online_days >= 0", name="nonnegative_online_days"),
        CheckConstraint("archive_days >= 0", name="nonnegative_archive_days"),
    )

    data_class: Mapped[str] = mapped_column(String(64), nullable=False)
    online_days: Mapped[int] = mapped_column(Integer, nullable=False)
    archive_days: Mapped[int] = mapped_column(Integer, nullable=False)
    purge_enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    description_ru: Mapped[str] = mapped_column(Text, nullable=False)


class LegalHold(UuidPrimaryKeyMixin, TimestampMixin, Base):
    """Explicit legal hold that blocks archival deletion for a bounded data subject."""

    __tablename__ = "legal_holds"
    __table_args__ = (
        CheckConstraint("length(subject_key) > 0", name="nonempty_subject_key"),
        Index("ix_legal_holds_subject", "data_class", "subject_key", "released_at"),
    )

    data_class: Mapped[str] = mapped_column(String(64), nullable=False)
    subject_key: Mapped[str] = mapped_column(String(200), nullable=False)
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    placed_by: Mapped[str] = mapped_column(String(200), nullable=False)
    released_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    released_by: Mapped[str | None] = mapped_column(String(200))


class ArchiveManifest(UuidPrimaryKeyMixin, TimestampMixin, Base):
    """Checksum-addressed dry-run or completed archive manifest without row deletion."""

    __tablename__ = "archive_manifests"
    __table_args__ = (
        UniqueConstraint("manifest_key", name="uq_archive_manifests_manifest_key"),
        CheckConstraint("length(checksum_sha256) = 64", name="valid_checksum"),
        CheckConstraint("record_count >= 0", name="nonnegative_record_count"),
        CheckConstraint("jsonb_typeof(scope) = 'object'", name="scope_object"),
    )

    manifest_key: Mapped[str] = mapped_column(String(200), nullable=False)
    data_class: Mapped[str] = mapped_column(String(64), nullable=False)
    scope: Mapped[dict[str, Any]] = mapped_column(
        MutableDict.as_mutable(JSONB), nullable=False, default=dict
    )
    record_count: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    checksum_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    archive_uri: Mapped[str | None] = mapped_column(String(1000))
    dry_run: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    created_by: Mapped[str] = mapped_column(String(200), nullable=False)
