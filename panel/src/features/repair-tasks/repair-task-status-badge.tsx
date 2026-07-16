import { Badge } from "@/components/ui/badge"
import type { RepairTaskStatus } from "@/features/repair-tasks/model/repair-task"
import { repairTaskStatusLabel } from "@/features/repair-tasks/repair-task-status-labels"

export function RepairTaskStatusBadge({ status }: { status: RepairTaskStatus }) {
  return (
    <Badge
      variant={
        status === "DRAFT" || status === "CANCELLED"
          ? "outline"
          : "secondary"
      }
    >
      {repairTaskStatusLabel(status)}
    </Badge>
  )
}
