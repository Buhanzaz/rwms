import type {
  CopyGroupScheduleRequest,
  GroupScheduleRequest,
  GroupScheduleDto,
  WorkerClassDto,
  WorkerDto,
  WorkerGroupDto,
  WorkQueueDto,
} from "@/features/settings/task-board/model/task-board-settings"

export type MockTaskStatus =
  "QUEUED" | "IN_PROGRESS" | "PAUSED" | "RETURNING" | "DONE" | "CANCELLED"

export type MockAssignmentStatus =
  "ACTIVE" | "PAUSED" | "RETURNING" | "DONE" | "CANCELLED"

export type MockPauseReasonType = "MANUAL" | "SCHEDULE" | "INTERRUPTION"

export type MockPauseReasonDto = {
  id: string
  type: MockPauseReasonType
  sourceId: string
  label: string
  createdAt: string
}

export type MockTaskRouteStepDto = {
  id: string
  externalStepId?: string | null
  queueCode: string
  title: string
  position: number
}

export type MockTaskDto = {
  id: string
  version: number
  externalTaskId: string
  warehouseId: string
  title: string
  description: string | null
  cabinNumber: string | null
  queueId: string | null
  queueCode: string | null
  queuePosition: number
  status: MockTaskStatus
  demo: boolean
  route: MockTaskRouteStepDto[]
  activeRouteIndex: number
  assignmentId: string | null
  pauseReasons: MockPauseReasonDto[]
  elapsedSeconds: number
  activeSince: string | null
  createdAt: string
  updatedAt: string
}

export type MockTaskAssignmentDto = {
  id: string
  version: number
  taskId: string
  workerGroupId: string
  workerIds: string[]
  status: MockAssignmentStatus
  startedAt: string
  endedAt: string | null
}

export type TaskInterruptionState =
  "ACTIVE" | "RETURNING" | "RESUMED" | "CANCELLED"

export type TaskInterruptionDto = {
  id: string
  version: number
  interruptedTaskId: string
  interruptingTaskId: string
  workerGroupId: string
  state: TaskInterruptionState
  interruptedAt: string
  returnDeadline: string | null
  resumedAt: string | null
}

export type WorkerNotificationType =
  | "TASK_ASSIGNED"
  | "TASK_INTERRUPTED"
  | "BREAK_WARNING"
  | "BREAK_STARTED"
  | "BREAK_ENDED"
  | "RETURN_STARTED"
  | "TASK_RESUMED"

export type WorkerNotificationDto = {
  id: string
  version: number
  workerId: string
  type: WorkerNotificationType
  message: string
  taskId: string | null
  createdAt: string
  readAt: string | null
  deduplicationKey: string
}

export type MockClockSpeed = 1 | 60 | 300

export type MockClockDto = {
  version: number
  mode: "REAL" | "SIMULATION"
  running: boolean
  speed: MockClockSpeed
  anchorSimulatedAt: string
  anchorRealAt: string
  activeWorkerId: string | null
}

export type SimulationClockSnapshotDto = MockClockDto & { now: string }
export type MockClockSnapshotDto = SimulationClockSnapshotDto

export type UpdateSimulationClockRequest = {
  expectedVersion: number
  mode?: MockClockDto["mode"]
  now?: string
  running?: boolean
  speed?: MockClockSpeed
  activeWorkerId?: string | null
}
export type MockClockUpdateRequest = UpdateSimulationClockRequest

export interface TaskBoardMockRuntimeClient {
  subscribe(listener: () => void): () => void
  listSchedules(warehouseId: string): Promise<GroupScheduleDto[]>
  saveSchedule(
    warehouseId: string,
    groupId: string,
    request: GroupScheduleRequest
  ): Promise<GroupScheduleDto>
  copySchedule(
    warehouseId: string,
    request: CopyGroupScheduleRequest
  ): Promise<GroupScheduleDto[]>
  getSimulationClock(): Promise<SimulationClockSnapshotDto>
  updateSimulationClock(
    request: UpdateSimulationClockRequest
  ): Promise<SimulationClockSnapshotDto>
  resetSimulationClock(
    expectedVersion: number
  ): Promise<SimulationClockSnapshotDto>
  getSnapshot(warehouseId: string): Promise<MockTaskBoardSnapshotDto>
  registerTask(command: RegisterMockTaskCommand): Promise<MockTaskDto>
  syncRegisteredTask(command: SyncRegisteredTaskCommand): Promise<MockTaskDto>
  findByExternalTaskId(
    warehouseId: string,
    externalTaskId: string
  ): Promise<MockTaskDto | null>
  moveTask(command: MoveMockTaskCommand): Promise<MockTaskDto>
  takeTask(command: TakeMockTaskCommand): Promise<MockTaskDto>
  pauseTask(command: MockTaskVersionCommand): Promise<MockTaskDto>
  resumeTask(command: MockTaskVersionCommand): Promise<MockTaskDto>
  completeTask(command: MockTaskVersionCommand): Promise<MockTaskDto>
  cancelTask(command: MockTaskVersionCommand): Promise<MockTaskDto>
  confirmGroupReturned(
    command: ConfirmGroupReturnedCommand
  ): Promise<MockTaskDto[]>
  listNotifications(workerId?: string): Promise<WorkerNotificationDto[]>
  markNotificationRead(
    id: string,
    expectedVersion: number
  ): Promise<WorkerNotificationDto>
  markAllNotificationsRead(
    command: MarkAllNotificationsReadCommand
  ): Promise<WorkerNotificationDto[]>
  evaluate(at?: string): Promise<string>
}

export type MockTaskBoardAuditEventDto = {
  id: string
  type: string
  entityId: string
  actor: string
  occurredAt: string
  details: Record<string, string | number | boolean | null>
}

export type MockTaskBoardEnvelope = {
  service: "task-board-browser-mock"
  schemaVersion: 2
  seedVersion: number
  revision: number
  queues: WorkQueueDto[]
  classes: WorkerClassDto[]
  workers: WorkerDto[]
  groups: WorkerGroupDto[]
  schedules: GroupScheduleDto[]
  tasks: MockTaskDto[]
  assignments: MockTaskAssignmentDto[]
  interruptions: TaskInterruptionDto[]
  notifications: WorkerNotificationDto[]
  clock: MockClockDto
  auditEvents: MockTaskBoardAuditEventDto[]
  tombstones: string[]
}

export type MockTaskBoardSnapshotDto = {
  warehouseId: string
  revision: number
  now: string
  queues: WorkQueueDto[]
  tasks: MockTaskDto[]
  assignments: MockTaskAssignmentDto[]
  groups: WorkerGroupDto[]
  workers: WorkerDto[]
  interruptions: TaskInterruptionDto[]
}

export type RegisterMockTaskCommand = {
  externalTaskId: string
  warehouseId: string
  queueCode: string | null
  title: string
  description?: string | null
  cabinNumber?: string | null
  route?: {
    externalStepId?: string | null
    queueCode: string
    title: string
  }[]
  demo?: boolean
}

export type SyncRegisteredTaskCommand = {
  warehouseId: string
  externalTaskId: string
  expectedVersion: number
  title: string
  description?: string | null
  cabinNumber?: string | null
  route: {
    externalStepId: string
    queueCode: string
    title: string
  }[]
  sourceStatus: "ACTIVE" | "CANCELLED" | "COMPLETED"
}

export type TakeMockTaskCommand = {
  taskId: string
  expectedVersion: number
  workerGroupId: string
  workerIds?: string[]
}

export type MockTaskVersionCommand = {
  taskId: string
  expectedVersion: number
}

export type ConfirmGroupReturnedCommand = {
  workerGroupId: string
  interruptions: { id: string; expectedVersion: number }[]
}

export type MarkAllNotificationsReadCommand = {
  workerId?: string
  notifications: { id: string; expectedVersion: number }[]
}

export type MoveMockTaskCommand = MockTaskVersionCommand & {
  targetQueueId: string | null
  queuePosition: number
}

export class TaskBoardMockConflictError extends Error {
  readonly status = 409

  constructor(message = "Настройки доски были изменены в другой вкладке.") {
    super(message)
    this.name = "TaskBoardMockConflictError"
  }
}

export class TaskBoardMockValidationError extends Error {
  readonly status = 400

  constructor(message: string) {
    super(message)
    this.name = "TaskBoardMockValidationError"
  }
}
