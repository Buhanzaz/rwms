export type TaskBoardEntryType = "REAL" | "SHADOW"
export type TaskBoardQueueKind =
  "MOVEMENT" | "REPAIR" | "HOLDING" | "UNASSIGNED"
export type TaskBoardEntryStatus =
  "WAITING" | "IN_PROGRESS" | "PAUSED" | "DONE" | "CANCELLED"
export type TaskBoardTaskStatus = "ACTIVE" | "DONE" | "CANCELLED"
export type TaskBoardAssignmentStatus =
  "ACTIVE" | "PAUSED" | "DONE" | "CANCELLED"

export type TaskBoardAssignmentDto = {
  id: string
  version: number
  workerId: string | null
  workerName: string | null
  workerGroupId: string | null
  workerGroupName: string | null
  status: TaskBoardAssignmentStatus
  assignedAt: string
  startedAt: string | null
  pausedAt: string | null
  finishedAt: string | null
}

export type TaskBoardQueueDto = {
  key: string
  label: string
  kind: TaskBoardQueueKind
  queueCode: string
  settingsQueueId: string | null
  settingsCollapsed: boolean
  entries: TaskBoardEntryDto[]
}

export type TaskBoardEntryDto = {
  id: string
  version: number
  warehouseId: string
  queueKey: string
  queueId: string | null
  queueCode: string
  entryType: TaskBoardEntryType
  routeIndex: number
  routeLength: number
  queuePosition: number
  taskId: string
  externalTaskId: string | null
  taskVersion: number
  title: string
  unitNumber: string | null
  taskStatus: TaskBoardTaskStatus
  status: TaskBoardEntryStatus
  taskText: string | null
  plannedDurationMinutes: number | null
  activeStartedAt: string | null
  pausedAt: string | null
  activeWorkSeconds: number
  assignments: TaskBoardAssignmentDto[]
  detailsHref: string | null
}

export type TaskBoardSnapshotDto = {
  warehouseId: string
  queues: TaskBoardQueueDto[]
  totalEntries: number
  realEntries: number
  shadowEntries: number
}
