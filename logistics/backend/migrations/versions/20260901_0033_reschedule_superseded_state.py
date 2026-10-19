"""Make terminal superseded request reschedules upgrade-safe.

Revision ID: 20260901_0033
Revises: 20260901_0032
Create Date: 2026-09-01
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260901_0033"
down_revision: str | None = "20260901_0032"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_PHASE_CONSISTENCY_WITH_SUPERSEDED = (
    "(state = 'CLAIMED' AND owner_session_id IS NULL AND "
    "owner_booking_id IS NULL AND selected_slot IS NULL AND owner_result IS NULL "
    "AND public_result IS NULL AND completed_at IS NULL AND error_code IS NULL) OR "
    "(state = 'OWNER_CALLING' AND owner_session_id IS NOT NULL AND "
    "owner_booking_id IS NOT NULL AND selected_slot IS NOT NULL "
    "AND owner_result IS NULL AND public_result IS NULL "
    "AND completed_at IS NULL AND error_code IS NULL) OR "
    "(state = 'COMPLETE' AND owner_session_id IS NOT NULL AND "
    "owner_booking_id IS NOT NULL AND selected_slot IS NOT NULL "
    "AND owner_result IS NOT NULL AND completed_at IS NOT NULL "
    "AND public_result IS NOT NULL AND error_code IS NULL "
    "AND next_attempt_at IS NULL AND lease_until IS NULL) OR "
    "(state = 'SUPERSEDED' AND owner_session_id IS NOT NULL AND "
    "owner_booking_id IS NOT NULL AND selected_slot IS NOT NULL "
    "AND owner_result IS NOT NULL AND public_result IS NULL "
    "AND completed_at IS NOT NULL "
    "AND error_code = 'RWMS_RESCHEDULE_RESULT_SUPERSEDED' "
    "AND next_attempt_at IS NULL AND lease_until IS NULL) OR "
    "(state = 'QUARANTINED' AND owner_session_id IS NOT NULL AND "
    "owner_booking_id IS NOT NULL AND selected_slot IS NOT NULL "
    "AND owner_result IS NULL AND public_result IS NULL AND completed_at IS NOT NULL "
    "AND error_code IS NOT NULL AND next_attempt_at IS NULL "
    "AND lease_until IS NULL AND quarantined_at IS NOT NULL "
    "AND quarantine_count >= 1) OR "
    "(state = 'FAILED' AND owner_result IS NULL AND public_result IS NULL "
    "AND completed_at IS NOT NULL "
    "AND error_code IS NOT NULL AND next_attempt_at IS NULL "
    "AND lease_until IS NULL AND ((owner_session_id IS NULL AND "
    "owner_booking_id IS NULL AND selected_slot IS NULL) OR "
    "(owner_session_id IS NOT NULL AND owner_booking_id IS NOT NULL "
    "AND selected_slot IS NOT NULL)))"
)

_PHASE_CONSISTENCY_WITHOUT_SUPERSEDED = (
    "(state = 'CLAIMED' AND owner_session_id IS NULL AND "
    "owner_booking_id IS NULL AND selected_slot IS NULL AND owner_result IS NULL "
    "AND public_result IS NULL AND completed_at IS NULL AND error_code IS NULL) OR "
    "(state = 'OWNER_CALLING' AND owner_session_id IS NOT NULL AND "
    "owner_booking_id IS NOT NULL AND selected_slot IS NOT NULL "
    "AND owner_result IS NULL AND public_result IS NULL "
    "AND completed_at IS NULL AND error_code IS NULL) OR "
    "(state = 'COMPLETE' AND owner_session_id IS NOT NULL AND "
    "owner_booking_id IS NOT NULL AND selected_slot IS NOT NULL "
    "AND owner_result IS NOT NULL AND completed_at IS NOT NULL "
    "AND public_result IS NOT NULL AND error_code IS NULL "
    "AND next_attempt_at IS NULL AND lease_until IS NULL) OR "
    "(state = 'QUARANTINED' AND owner_session_id IS NOT NULL AND "
    "owner_booking_id IS NOT NULL AND selected_slot IS NOT NULL "
    "AND owner_result IS NULL AND public_result IS NULL AND completed_at IS NOT NULL "
    "AND error_code IS NOT NULL AND next_attempt_at IS NULL "
    "AND lease_until IS NULL AND quarantined_at IS NOT NULL "
    "AND quarantine_count >= 1) OR "
    "(state = 'FAILED' AND owner_result IS NULL AND public_result IS NULL "
    "AND completed_at IS NOT NULL "
    "AND error_code IS NOT NULL AND next_attempt_at IS NULL "
    "AND lease_until IS NULL AND ((owner_session_id IS NULL AND "
    "owner_booking_id IS NULL AND selected_slot IS NULL) OR "
    "(owner_session_id IS NOT NULL AND owner_booking_id IS NOT NULL "
    "AND selected_slot IS NOT NULL)))"
)


def _replace_constraints(*, phase_consistency: str, valid_states: str) -> None:
    op.drop_constraint(
        op.f("ck_request_reschedule_holds_phase_consistency"),
        "request_reschedule_holds",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_request_reschedule_holds_valid_state"),
        "request_reschedule_holds",
        type_="check",
    )
    op.create_check_constraint(
        op.f("ck_request_reschedule_holds_phase_consistency"),
        "request_reschedule_holds",
        sa.text(phase_consistency),
    )
    op.create_check_constraint(
        op.f("ck_request_reschedule_holds_valid_state"),
        "request_reschedule_holds",
        sa.text(valid_states),
    )


def upgrade() -> None:
    """Allow the terminal SUPERSEDED receipt on databases already at 0032."""

    _replace_constraints(
        phase_consistency=_PHASE_CONSISTENCY_WITH_SUPERSEDED,
        valid_states=(
            "state IN ('CLAIMED', 'OWNER_CALLING', 'QUARANTINED', 'COMPLETE', "
            "'SUPERSEDED', 'FAILED')"
        ),
    )


def downgrade() -> None:
    """Restore the pre-SUPERSEDED state shape."""

    _replace_constraints(
        phase_consistency=_PHASE_CONSISTENCY_WITHOUT_SUPERSEDED,
        valid_states=(
            "state IN ('CLAIMED', 'OWNER_CALLING', 'QUARANTINED', 'COMPLETE', 'FAILED')"
        ),
    )
