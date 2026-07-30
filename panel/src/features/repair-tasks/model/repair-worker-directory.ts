import type { RepairEstimateCatalogRouteQueueKind } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import type { RepairTaskWorkerSnapshotDto } from "@/features/repair-tasks/model/repair-task"

export type RepairWorkerDirectoryGroupDto = {
  id: string
  warehouseId: string
  name: string
  active: boolean
  queueIds: string[]
  routeQueueKinds: RepairEstimateCatalogRouteQueueKind[]
  members: RepairTaskWorkerSnapshotDto[]
}

export type RepairWorkerGroupsQuery = {
  warehouseId: string
  queueId: string | null
  routeQueueKind: RepairEstimateCatalogRouteQueueKind | null
  purpose?: "TASK_ASSIGNMENT" | "DRIVER_DIRECTORY"
}
