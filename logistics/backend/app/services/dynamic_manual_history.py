"""Bridge validated manual plan edits into the operational event timeline."""

from __future__ import annotations

from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession

from app.models import (
    LogisticsEvent,
    LogisticsEventType,
    LogisticsNotice,
    LogisticsNoticeStatus,
    ManualChangeAudit,
    RoutePlan,
)
from app.schemas.domain import ManualChangeCommand


def _optional_uuid(value: object) -> UUID | None:
    """Parse one optional command reference without accepting ambiguous values."""

    try:
        return UUID(str(value)) if value is not None else None
    except (TypeError, ValueError):
        return None


async def append_manual_change_history(
    session: AsyncSession,
    plan: RoutePlan,
    audit: ManualChangeAudit,
    command: ManualChangeCommand,
) -> None:
    """Append one idempotently identified event/comment after a validated manual edit."""

    cycle_id = _optional_uuid(
        command.payload.get("cycle_id") or command.payload.get("target_cycle_id")
    )
    task_id = _optional_uuid(command.payload.get("task_id"))
    event = LogisticsEvent(
        warehouse_id=plan.warehouse_id,
        day=plan.date,
        plan_id=plan.id,
        cycle_id=cycle_id,
        task_id=task_id,
        event_type=LogisticsEventType.MANUAL_PLAN_CHANGE,
        source_event="manual_change_audit",
        idempotency_key=f"manual-change:{audit.id}",
        occurred_at=audit.changed_at,
        actor=command.changed_by,
        facts={
            "manual_change_id": str(audit.id),
            "change_type": command.change_type,
            "plan_version_before": audit.plan_version_before,
            "plan_version_after": audit.plan_version_after,
            "cycle_id": str(cycle_id) if cycle_id is not None else None,
            "task_id": str(task_id) if task_id is not None else None,
            "reason": command.reason,
        },
    )
    session.add(event)
    await session.flush()
    session.add(
        LogisticsNotice(
            event_id=event.id,
            warehouse_id=plan.warehouse_id,
            day=plan.date,
            plan_id=plan.id,
            cycle_id=cycle_id,
            task_id=task_id,
            notice_type="MANUAL_PLAN_CHANGE_APPLIED",
            severity="INFO",
            reason_codes=["MANUAL_PLAN_CHANGE", command.change_type],
            facts={
                "manual_change_id": str(audit.id),
                "plan_version_after": audit.plan_version_after,
            },
            message_ru=(
                f"Ручное изменение «{command.change_type}» применено к плану дня."
            ),
            recommended_action_ru=None,
            requires_action=False,
            status=LogisticsNoticeStatus.COMPLETED,
        )
    )
