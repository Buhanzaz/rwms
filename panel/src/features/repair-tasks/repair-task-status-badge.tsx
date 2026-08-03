import { Badge } from "@/components/ui/badge"
import type { RepairTaskStatus } from "@/features/repair-tasks/model/repair-task"
import { repairTaskStatusLabel } from "@/features/repair-tasks/repair-task-status-labels"

export function RepairTaskStatusBadge({
  status,
  awaitingMovement = false,
}: {
  status: RepairTaskStatus
  awaitingMovement?: boolean
}) {
  return (
    <Badge
      variant={
        awaitingMovement || status === "DRAFT" || status === "CANCELLED"
          ? "outline"
          : "secondary"
      }
    >
      {awaitingMovement ? "Ожидает перемещения" : repairTaskStatusLabel(status)}
    </Badge>
  )
}
