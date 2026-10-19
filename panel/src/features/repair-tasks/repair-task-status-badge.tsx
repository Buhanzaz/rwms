import { Badge } from "@/components/ui/badge"
import type { RepairTaskStatus } from "@/features/repair-tasks/model/repair-task"
import { repairTaskStatusLabel } from "@/features/repair-tasks/repair-task-status-labels"

const repairStatusVariant = {
  DRAFT: "muted",
  QUEUED: "warning",
  IN_PROGRESS: "progress",
  COMPLETED: "success",
  CANCELLED: "muted",
} as const satisfies Record<RepairTaskStatus, string>

export function RepairTaskStatusBadge({
  status,
  awaitingMovement = false,
}: {
  status: RepairTaskStatus
  awaitingMovement?: boolean
}) {
  return (
    <Badge variant={awaitingMovement ? "info" : repairStatusVariant[status]}>
      {awaitingMovement ? "Ожидает перемещения" : repairTaskStatusLabel(status)}
    </Badge>
  )
}
