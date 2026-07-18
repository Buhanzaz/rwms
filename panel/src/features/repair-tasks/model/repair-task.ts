import type { RepairEstimateCatalogRouteQueueKind } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import type {
  PendingEstimateMediaUpload,
  RepairEstimateLineDto,
  RepairEstimateMediaRefDto,
} from "@/features/repair-estimates/model/repair-estimate"

export type RepairTaskStatus =
  "DRAFT" | "QUEUED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED"
export type RepairTaskKind = "REPAIR" | "REWORK"
export type RepairTaskOrigin = "ESTIMATE" | "DIRECT_REPAIR" | "INVENTORY"
export type RepairTaskAcceptanceStatus =
  "NOT_READY" | "PENDING" | "IN_REWORK" | "ACCEPTED" | "WRITTEN_OFF"
export type RepairTaskSubtaskKind =
  "REPAIR_WORK" | "MOVE_TO_REPAIR" | "MOVE_FROM_REPAIR"
export type RepairTaskSubtaskStatus =
  "WAITING" | "IN_PROGRESS" | "PAUSED" | "DONE" | "CANCELLED"
export type RepairTaskAssignmentStatus =
  "ACTIVE" | "PAUSED" | "DONE" | "CANCELLED"

export type RepairTaskWorkerSnapshotDto = {
  id: string
  name: string
}

export type RepairTaskWorkerGroupSnapshotDto = {
  id: string
  name: string
}

export type RepairTaskAssignmentDto = {
  id: string
  worker: RepairTaskWorkerSnapshotDto | null
  assignedAt: string
  startedAt: string | null
  pausedAt: string | null
  finishedAt: string | null
  activeStartedAt: string | null
  status: RepairTaskAssignmentStatus
}

export type RepairTaskSubtaskDto = {
  id: string
  externalTaskId?: string | null
  kind: RepairTaskSubtaskKind
  status: RepairTaskSubtaskStatus
  workLines: RepairEstimateLineDto[]
  materialLines: RepairEstimateLineDto[]
  groupComment: string
  queueCode: string | null
  queueId?: string | null
  routeQueueKind: RepairEstimateCatalogRouteQueueKind | null
  sortOrder: number
  queuePosition: number
  plannedDurationMinutes: number | null
  startedAt: string | null
  completedAt: string | null
  activeStartedAt: string | null
  activeWorkSeconds: number
  workerGroup: RepairTaskWorkerGroupSnapshotDto | null
  assignments: RepairTaskAssignmentDto[]
}

export type RepairTaskDto = {
  id: string
  version: number
  status: RepairTaskStatus
  kind: RepairTaskKind
  origin: RepairTaskOrigin
  acceptanceStatus: RepairTaskAcceptanceStatus
  startedAt: string | null
  completedAt: string | null
  warehouseId: string
  rentalItemId: string
  cabinNumber: string
  actorId: string
  sourceParty?: string | null
  dispatchDate: string | null
  maintenanceMediaReferences?: Array<{ mediaId: string; generation: number }>
  subtasks: RepairTaskSubtaskDto[]
  sourceEstimateId: string | null
  sourceEstimateVersion: number | null
  sourceInventoryId: string | null
  sourceInventoryFindingId: string | null
  sourceRepairTaskId: string | null
  sourceRepairTaskVersion: number | null
  readyAt?: string | null
  writtenOffAt?: string | null
  decisionActorId?: string | null
  taskBoardAvailable?: boolean
  createdAt: string
  updatedAt: string
}

export type RepairTaskEditorDraft = {
  taskId: string | null
  expectedVersion: number | null
  kind: RepairTaskKind
  origin: RepairTaskOrigin
  sourceRepairTaskId: string | null
  sourceRepairTaskVersion: number | null
  sourceEstimateId: string | null
  sourceEstimateVersion: number | null
  rentalItemId: string
  reason: string
  dispatchDate: string | null
  comment: string
  lines: RepairEstimateLineDto[]
  media: RepairEstimateMediaRefDto[]
  pendingUploads: PendingEstimateMediaUpload[]
}

export type RepairTaskWriteCommand = {
  taskId: string | null
  expectedVersion: number | null
  kind: RepairTaskKind
  origin: RepairTaskOrigin
  sourceRepairTaskId: string | null
  sourceRepairTaskVersion: number | null
  sourceEstimateId: string | null
  sourceEstimateVersion: number | null
  warehouseId: string
  rentalItemId: string
  reason: string
  dispatchDate: string | null
  comment: string
  media: RepairEstimateMediaRefDto[]
  subtasks: RepairTaskSubtaskDto[]
}

export type RepairTaskReworkSeed = {
  type: "repair-rework-seed-v1"
  warehouseId: string
  sourceRepairTaskId: string
  sourceRepairTaskVersion: number
  sourceOrigin: RepairTaskOrigin
  sourceEstimateId: string | null
  sourceEstimateVersion: number | null
  rentalItemId: string
  lines: RepairEstimateLineDto[]
}

export type RepairsLocationState = {
  workspaceEntry?: true
  reworkSeed?: RepairTaskReworkSeed
}

export type RepairTaskSubtasksCommand = {
  taskId: string
  expectedVersion: number
  warehouseId: string
  orderedSubtaskIds: string[]
}

export type RepairTaskAcceptCommand = {
  taskId: string
  expectedVersion: number
  warehouseId: string
  comment: string
}

export type RepairTaskWriteOffCommand = {
  taskId: string
  expectedVersion: number
  warehouseId: string
  reason: string
}

export type RepairTaskEarlyWriteOffCommand = RepairTaskWriteCommand & {
  origin: RepairTaskOrigin
  sourceEstimateId: string | null
  sourceEstimateVersion: number | null
  writeOffReason: string
}
