"""Add dynamic logistics operations, customer facts, and safe retention metadata.

Revision ID: 20260901_0031
Revises: 20260901_0030
Create Date: 2026-09-01
"""

# ruff: noqa: E501 -- Alembic table declarations keep each column invariant together.

from collections.abc import Sequence
from uuid import UUID

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260901_0031"
down_revision: str | None = "20260901_0030"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Create append-oriented operational state without deleting existing rows."""

    op.add_column(
        "logistics_requests",
        sa.Column("client_type", sa.String(length=32), nullable=True),
    )
    op.create_check_constraint(
        op.f("ck_logistics_requests_valid_client_type"),
        "logistics_requests",
        "client_type IS NULL OR client_type IN ('INDIVIDUAL', 'SOLE_PROPRIETOR', 'LEGAL_ENTITY')",
    )

    op.create_table(
        "planning_day_policies",
        sa.Column("warehouse_id", sa.Uuid(), nullable=False),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column(
            "mode",
            sa.String(length=40),
            server_default="DELIVERIES_AND_PICKUPS",
            nullable=False,
        ),
        sa.Column("version", sa.Integer(), server_default="1", nullable=False),
        sa.Column("changed_by", sa.String(length=200), nullable=False),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint(
            "mode IN ('DELIVERIES_AND_PICKUPS', 'DELIVERIES_ONLY', 'PICKUPS_ONLY')",
            name=op.f("ck_planning_day_policies_valid_mode"),
        ),
        sa.CheckConstraint("version >= 1", name=op.f("ck_planning_day_policies_positive_version")),
        sa.ForeignKeyConstraint(["warehouse_id"], ["warehouses.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("warehouse_id", "date", name="uq_planning_day_policies_warehouse_date"),
    )
    op.create_index(
        "ix_planning_day_policies_warehouse_date",
        "planning_day_policies",
        ["warehouse_id", "date"],
    )

    op.create_table(
        "logistics_events",
        sa.Column("warehouse_id", sa.Uuid(), nullable=False),
        sa.Column("day", sa.Date(), nullable=False),
        sa.Column("plan_id", sa.Uuid(), nullable=True),
        sa.Column("cycle_id", sa.Uuid(), nullable=True),
        sa.Column("request_id", sa.Uuid(), nullable=True),
        sa.Column("task_id", sa.Uuid(), nullable=True),
        sa.Column("event_type", sa.String(length=64), nullable=False),
        sa.Column("source_event", sa.String(length=200), nullable=True),
        sa.Column("idempotency_key", sa.String(length=200), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("actor", sa.String(length=200), nullable=False),
        sa.Column(
            "facts",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint(
            "length(idempotency_key) > 0", name=op.f("ck_logistics_events_nonempty_idempotency_key")
        ),
        sa.CheckConstraint(
            "jsonb_typeof(facts) = 'object'", name=op.f("ck_logistics_events_facts_object")
        ),
        sa.ForeignKeyConstraint(["cycle_id"], ["route_cycles.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["plan_id"], ["route_plans.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["request_id"], ["logistics_requests.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["task_id"], ["planning_tasks.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["warehouse_id"], ["warehouses.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "warehouse_id", "idempotency_key", name="uq_logistics_events_warehouse_key"
        ),
    )
    op.create_index(
        "ix_logistics_events_warehouse_day",
        "logistics_events",
        ["warehouse_id", "day", "created_at"],
    )
    op.create_index("ix_logistics_events_plan_id", "logistics_events", ["plan_id"])

    op.create_table(
        "logistics_notices",
        sa.Column("event_id", sa.Uuid(), nullable=False),
        sa.Column("warehouse_id", sa.Uuid(), nullable=False),
        sa.Column("day", sa.Date(), nullable=False),
        sa.Column("plan_id", sa.Uuid(), nullable=True),
        sa.Column("cycle_id", sa.Uuid(), nullable=True),
        sa.Column("request_id", sa.Uuid(), nullable=True),
        sa.Column("task_id", sa.Uuid(), nullable=True),
        sa.Column("notice_type", sa.String(length=64), nullable=False),
        sa.Column("severity", sa.String(length=16), nullable=False),
        sa.Column(
            "reason_codes",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "facts",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("message_ru", sa.Text(), nullable=False),
        sa.Column("recommended_action_ru", sa.Text(), nullable=True),
        sa.Column("requires_action", sa.Boolean(), server_default=sa.false(), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "severity IN ('INFO', 'WARNING', 'ERROR', 'CRITICAL')",
            name=op.f("ck_logistics_notices_valid_severity"),
        ),
        sa.CheckConstraint(
            "status IN ('REQUIRES_ACTION', 'ACCEPTED', 'REJECTED', 'COMPLETED', 'OBSOLETE')",
            name=op.f("ck_logistics_notices_valid_status"),
        ),
        sa.CheckConstraint(
            "jsonb_typeof(reason_codes) = 'array'",
            name=op.f("ck_logistics_notices_reason_codes_array"),
        ),
        sa.CheckConstraint(
            "jsonb_typeof(facts) = 'object'", name=op.f("ck_logistics_notices_facts_object")
        ),
        sa.ForeignKeyConstraint(["cycle_id"], ["route_cycles.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["event_id"], ["logistics_events.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["plan_id"], ["route_plans.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["request_id"], ["logistics_requests.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["task_id"], ["planning_tasks.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["warehouse_id"], ["warehouses.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_logistics_notices_day_status", "logistics_notices", ["warehouse_id", "day", "status"]
    )
    op.create_index("ix_logistics_notices_event_id", "logistics_notices", ["event_id"])

    op.create_table(
        "logistics_human_actions",
        sa.Column("notice_id", sa.Uuid(), nullable=False),
        sa.Column("event_id", sa.Uuid(), nullable=False),
        sa.Column("warehouse_id", sa.Uuid(), nullable=False),
        sa.Column("day", sa.Date(), nullable=False),
        sa.Column("request_id", sa.Uuid(), nullable=True),
        sa.Column("action_type", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("version", sa.Integer(), server_default="1", nullable=False),
        sa.Column("customer_name", sa.String(length=200), nullable=True),
        sa.Column("customer_type", sa.String(length=32), nullable=True),
        sa.Column("customer_phone", sa.String(length=64), nullable=True),
        sa.Column("current_date", sa.Date(), nullable=True),
        sa.Column("recommended_date", sa.Date(), nullable=True),
        sa.Column(
            "alternative_dates",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "context",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint(
            "version >= 1", name=op.f("ck_logistics_human_actions_positive_version")
        ),
        sa.CheckConstraint(
            "status IN ('PENDING', 'IN_PROGRESS', 'RESOLVED', 'OBSOLETE')",
            name=op.f("ck_logistics_human_actions_valid_status"),
        ),
        sa.CheckConstraint(
            "jsonb_typeof(alternative_dates) = 'array'",
            name=op.f("ck_logistics_human_actions_dates_array"),
        ),
        sa.CheckConstraint(
            "jsonb_typeof(context) = 'object'",
            name=op.f("ck_logistics_human_actions_context_object"),
        ),
        sa.ForeignKeyConstraint(["event_id"], ["logistics_events.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["notice_id"], ["logistics_notices.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["request_id"], ["logistics_requests.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["warehouse_id"], ["warehouses.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_logistics_human_actions_day_status",
        "logistics_human_actions",
        ["warehouse_id", "day", "status"],
    )
    op.create_index(
        "ix_logistics_human_actions_notice_id", "logistics_human_actions", ["notice_id"]
    )

    op.create_table(
        "logistics_human_decisions",
        sa.Column("action_id", sa.Uuid(), nullable=False),
        sa.Column("event_id", sa.Uuid(), nullable=False),
        sa.Column("decision_type", sa.String(length=40), nullable=False),
        sa.Column("selected_date", sa.Date(), nullable=True),
        sa.Column("actor", sa.String(length=200), nullable=False),
        sa.Column("comment", sa.Text(), nullable=True),
        sa.Column("idempotency_key", sa.String(length=200), nullable=False),
        sa.Column(
            "constraint_data",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint(
            "length(idempotency_key) > 0",
            name=op.f("ck_logistics_human_decisions_nonempty_idempotency_key"),
        ),
        sa.CheckConstraint(
            "jsonb_typeof(constraint_data) = 'object'",
            name=op.f("ck_logistics_human_decisions_constraint_object"),
        ),
        sa.ForeignKeyConstraint(["action_id"], ["logistics_human_actions.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["event_id"], ["logistics_events.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "action_id", "idempotency_key", name="uq_logistics_human_decisions_action_key"
        ),
    )
    op.create_index(
        "ix_logistics_human_decisions_action_id",
        "logistics_human_decisions",
        ["action_id", "created_at"],
    )

    op.create_table(
        "recovery_proposals",
        sa.Column("event_id", sa.Uuid(), nullable=False),
        sa.Column("action_id", sa.Uuid(), nullable=True),
        sa.Column("warehouse_id", sa.Uuid(), nullable=False),
        sa.Column("day", sa.Date(), nullable=False),
        sa.Column("source_plan_id", sa.Uuid(), nullable=True),
        sa.Column("result_plan_id", sa.Uuid(), nullable=True),
        sa.Column("proposal_type", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("version", sa.Integer(), server_default="1", nullable=False),
        sa.Column("summary_ru", sa.Text(), nullable=False),
        sa.Column(
            "affected_request_ids",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "affected_task_ids",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "changes",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "metrics",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("failure_code", sa.String(length=128), nullable=True),
        sa.Column("applied_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint("version >= 1", name=op.f("ck_recovery_proposals_positive_version")),
        sa.CheckConstraint(
            "status IN ('PROPOSED', 'CUSTOMER_AGREED', 'READY_TO_APPLY', 'APPLIED', 'REJECTED', 'FAILED')",
            name=op.f("ck_recovery_proposals_valid_status"),
        ),
        sa.CheckConstraint(
            "jsonb_typeof(affected_request_ids) = 'array'",
            name=op.f("ck_recovery_proposals_request_ids_array"),
        ),
        sa.CheckConstraint(
            "jsonb_typeof(affected_task_ids) = 'array'",
            name=op.f("ck_recovery_proposals_task_ids_array"),
        ),
        sa.CheckConstraint(
            "jsonb_typeof(changes) = 'object'", name=op.f("ck_recovery_proposals_changes_object")
        ),
        sa.CheckConstraint(
            "jsonb_typeof(metrics) = 'object'", name=op.f("ck_recovery_proposals_metrics_object")
        ),
        sa.ForeignKeyConstraint(["action_id"], ["logistics_human_actions.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["event_id"], ["logistics_events.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["result_plan_id"], ["route_plans.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["source_plan_id"], ["route_plans.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["warehouse_id"], ["warehouses.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_recovery_proposals_day_status", "recovery_proposals", ["warehouse_id", "day", "status"]
    )
    op.create_index("ix_recovery_proposals_event_id", "recovery_proposals", ["event_id"])

    op.create_table(
        "retention_policies",
        sa.Column("data_class", sa.String(length=64), nullable=False),
        sa.Column("online_days", sa.Integer(), nullable=False),
        sa.Column("archive_days", sa.Integer(), nullable=False),
        sa.Column("purge_enabled", sa.Boolean(), server_default=sa.false(), nullable=False),
        sa.Column("description_ru", sa.Text(), nullable=False),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint(
            "online_days >= 0", name=op.f("ck_retention_policies_nonnegative_online_days")
        ),
        sa.CheckConstraint(
            "archive_days >= 0", name=op.f("ck_retention_policies_nonnegative_archive_days")
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("data_class", name="uq_retention_policies_data_class"),
    )
    policies = sa.table(
        "retention_policies",
        sa.column("id", sa.Uuid()),
        sa.column("data_class", sa.String()),
        sa.column("online_days", sa.Integer()),
        sa.column("archive_days", sa.Integer()),
        sa.column("purge_enabled", sa.Boolean()),
        sa.column("description_ru", sa.Text()),
    )
    op.bulk_insert(
        policies,
        [
            {
                "id": UUID("00000000-0000-0000-0000-000000000501"),
                "data_class": "BUSINESS_AUDIT_PROOF",
                "online_days": 1825,
                "archive_days": 0,
                "purge_enabled": False,
                "description_ru": "Бизнес-факты, аудит и доказательства: 5 лет после закрытия.",
            },
            {
                "id": UUID("00000000-0000-0000-0000-000000000502"),
                "data_class": "TERMINAL_INBOX_OUTBOX",
                "online_days": 90,
                "archive_days": 365,
                "purge_enabled": False,
                "description_ru": "Терминальные inbox/outbox: 90 дней онлайн и 1 год в архиве.",
            },
            {
                "id": UUID("00000000-0000-0000-0000-000000000503"),
                "data_class": "GPS",
                "online_days": 30,
                "archive_days": 0,
                "purge_enabled": False,
                "description_ru": "GPS: 30 дней; источник GPS пока не подключён.",
            },
        ],
    )

    op.create_table(
        "legal_holds",
        sa.Column("data_class", sa.String(length=64), nullable=False),
        sa.Column("subject_key", sa.String(length=200), nullable=False),
        sa.Column("reason", sa.Text(), nullable=False),
        sa.Column("placed_by", sa.String(length=200), nullable=False),
        sa.Column("released_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("released_by", sa.String(length=200), nullable=True),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint(
            "length(subject_key) > 0", name=op.f("ck_legal_holds_nonempty_subject_key")
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_legal_holds_subject", "legal_holds", ["data_class", "subject_key", "released_at"]
    )

    op.create_table(
        "archive_manifests",
        sa.Column("manifest_key", sa.String(length=200), nullable=False),
        sa.Column("data_class", sa.String(length=64), nullable=False),
        sa.Column(
            "scope",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("record_count", sa.BigInteger(), server_default="0", nullable=False),
        sa.Column("checksum_sha256", sa.String(length=64), nullable=False),
        sa.Column("archive_uri", sa.String(length=1000), nullable=True),
        sa.Column("dry_run", sa.Boolean(), server_default=sa.true(), nullable=False),
        sa.Column("created_by", sa.String(length=200), nullable=False),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint(
            "length(checksum_sha256) = 64", name=op.f("ck_archive_manifests_valid_checksum")
        ),
        sa.CheckConstraint(
            "record_count >= 0", name=op.f("ck_archive_manifests_nonnegative_record_count")
        ),
        sa.CheckConstraint(
            "jsonb_typeof(scope) = 'object'", name=op.f("ck_archive_manifests_scope_object")
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("manifest_key", name="uq_archive_manifests_manifest_key"),
    )


def downgrade() -> None:
    """Remove only planner-owned operational structures and the client-type projection."""

    op.drop_table("archive_manifests")
    op.drop_index("ix_legal_holds_subject", table_name="legal_holds")
    op.drop_table("legal_holds")
    op.drop_table("retention_policies")
    op.drop_index("ix_recovery_proposals_event_id", table_name="recovery_proposals")
    op.drop_index("ix_recovery_proposals_day_status", table_name="recovery_proposals")
    op.drop_table("recovery_proposals")
    op.drop_index("ix_logistics_human_decisions_action_id", table_name="logistics_human_decisions")
    op.drop_table("logistics_human_decisions")
    op.drop_index("ix_logistics_human_actions_notice_id", table_name="logistics_human_actions")
    op.drop_index("ix_logistics_human_actions_day_status", table_name="logistics_human_actions")
    op.drop_table("logistics_human_actions")
    op.drop_index("ix_logistics_notices_event_id", table_name="logistics_notices")
    op.drop_index("ix_logistics_notices_day_status", table_name="logistics_notices")
    op.drop_table("logistics_notices")
    op.drop_index("ix_logistics_events_plan_id", table_name="logistics_events")
    op.drop_index("ix_logistics_events_warehouse_day", table_name="logistics_events")
    op.drop_table("logistics_events")
    op.drop_index("ix_planning_day_policies_warehouse_date", table_name="planning_day_policies")
    op.drop_table("planning_day_policies")
    op.drop_constraint(
        op.f("ck_logistics_requests_valid_client_type"), "logistics_requests", type_="check"
    )
    op.drop_column("logistics_requests", "client_type")
