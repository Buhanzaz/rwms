import type { RepairEstimateCatalogRouteQueueKind } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import type {
  RepairTaskDto,
  RepairTaskSubtaskDto,
} from "@/features/repair-tasks/model/repair-task"
import type {
  MockTaskDto,
  TaskInterruptionDto,
} from "@/features/task-board/mock/model"

export type TaskBoardEntryType = "REAL" | "SHADOW"
export type TaskBoardQueueKind =
  RepairEstimateCatalogRouteQueueKind | "UNASSIGNED"

export type TaskBoardQueueDto = {
  key: string
  label: string
  kind: TaskBoardQueueKind
  queueCode: string | null
  routeQueueKind: RepairEstimateCatalogRouteQueueKind | null
  settingsQueueId?: string | null
  settingsCollapsed?: boolean
  entries: TaskBoardEntryDto[]
}

export type TaskBoardEntryDto = {
  id: string
  queueKey: string
  entryType: TaskBoardEntryType
  routeIndex: number
  routeLength: number
  queuePosition: number
  task: RepairTaskDto
  subtask: RepairTaskSubtaskDto
  runtimeTask?: MockTaskDto
  interruption?: TaskInterruptionDto | null
  detailsHref?: string | null
}

export type TaskBoardSnapshotDto = {
  warehouseId: string
  now?: string
  queues: TaskBoardQueueDto[]
  totalEntries: number
  realEntries: number
  shadowEntries: number
}
