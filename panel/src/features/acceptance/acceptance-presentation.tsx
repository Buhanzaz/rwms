import { Badge } from "@/components/ui/badge"
import type { RepairTaskAcceptanceStatus } from "@/features/repair-tasks/model/repair-task"
import { cn } from "@/lib/utils"

import { acceptanceStatusLabels } from "./acceptance-formatters"

const acceptanceStatusClassName: Record<RepairTaskAcceptanceStatus, string> = {
  NOT_READY: "bg-muted text-muted-foreground",
  PENDING:
    "bg-[var(--acceptance-pending-bg)] text-[var(--acceptance-pending-fg)]",
  IN_REWORK: "bg-status-progress text-foreground",
  ACCEPTED:
    "bg-[var(--acceptance-accepted-bg)] text-[var(--acceptance-accepted-fg)]",
  WRITTEN_OFF:
    "bg-[var(--acceptance-written-off-bg)] text-[var(--acceptance-written-off-fg)]",
}

export function AcceptanceStatusBadge({
  status,
}: {
  status: RepairTaskAcceptanceStatus
}) {
  return (
    <Badge
      variant="secondary"
      className={cn(
        "border-transparent whitespace-nowrap",
        acceptanceStatusClassName[status]
      )}
    >
      {acceptanceStatusLabels[status]}
    </Badge>
  )
}

type TimeState = "SAFE" | "WARNING" | "CRITICAL" | "EXPIRED"

const timeStateClassName: Record<TimeState, string> = {
  SAFE: "bg-[var(--acceptance-time-safe-bg)] text-[var(--acceptance-time-safe-fg)]",
  WARNING:
    "bg-[var(--acceptance-time-warning-bg)] text-[var(--acceptance-time-warning-fg)]",
  CRITICAL:
    "bg-[var(--acceptance-time-critical-bg)] text-[var(--acceptance-time-critical-fg)]",
  EXPIRED:
    "bg-[var(--acceptance-time-expired-bg)] text-[var(--acceptance-time-expired-fg)]",
}

export function RemainingTimeBadge({
  plannedDurationMinutes,
  activeWorkSeconds,
}: {
  plannedDurationMinutes: number | null
  activeWorkSeconds: number
}) {
  if (
    plannedDurationMinutes === null ||
    !Number.isFinite(plannedDurationMinutes) ||
    plannedDurationMinutes <= 0
  ) {
    return <Badge variant="secondary">Нет норматива</Badge>
  }

  const plannedSeconds = plannedDurationMinutes * 60
  const remainingPercent =
    ((plannedSeconds - Math.max(0, activeWorkSeconds)) / plannedSeconds) * 100
  const state: TimeState =
    remainingPercent > 60
      ? "SAFE"
      : remainingPercent > 40
        ? "WARNING"
        : remainingPercent > 20
          ? "CRITICAL"
          : "EXPIRED"
  const label =
    remainingPercent < 0
      ? "Просрочено"
      : `${Math.max(0, Math.round(remainingPercent))}% времени осталось`

  return (
    <Badge
      variant="secondary"
      className={cn("border-transparent", timeStateClassName[state])}
    >
      {label}
    </Badge>
  )
}
