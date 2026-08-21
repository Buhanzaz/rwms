export type TaskBoardEntryType = "REAL" | "SHADOW"
export type TaskBoardQueueKind =
  "MOVEMENT" | "REPAIR" | "HOLDING" | "FURNITURE_MOVEMENT"
export type TaskBoardEntryStatus =
  "WAITING" | "IN_PROGRESS" | "PAUSED" | "DONE" | "CANCELLED"
export type TaskBoardTaskStatus = "ACTIVE" | "DONE" | "CANCELLED"
export type TaskBoardAssignmentStatus =
  "ACTIVE" | "PAUSED" | "DONE" | "CANCELLED"
export type TaskBoardTimerState =
  "WORKING" | "BREAK" | "OFF_SHIFT" | "PAUSED" | "DONE"

export type TaskBoardTimerSnapshotDto = {
  countedActiveSeconds: number
  remainingSeconds: number | null
  remainingPercent: number | null
  timerState: TaskBoardTimerState
  nextTransitionAt: string | null
  serverTime: string
}

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
  settingsQueueId: string
  settingsCollapsed: boolean
  availableTaskLimit: number
  entries: TaskBoardEntryDto[]
}

export type TaskBoardSourceDto = {
  type: "MAINTENANCE_REPAIR"
  sourceId: string
}

export type TaskBoardEntryDto = {
  id: string
  version: number
  warehouseId: string
  queueKey: string
  queueId: string
  entryType: TaskBoardEntryType
  routeIndex: number
  routeLength: number
  queuePosition: number
  taskId: string
  externalTaskId: string | null
  source?: TaskBoardSourceDto | null
  taskVersion: number
  title: string
  unitNumber: string | null
  taskStatus: TaskBoardTaskStatus
  scheduledDate: string
  priority: number
  pinned: boolean
  status: TaskBoardEntryStatus
  taskText: string | null
  plannedDurationMinutes: number | null
  activeStartedAt: string | null
  pausedAt: string | null
  activeWorkSeconds: number
  timerSnapshot?: TaskBoardTimerSnapshotDto | null
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
