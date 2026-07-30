import type {
  RepairTaskDto,
  RepairTaskAcceptCommand,
  RepairTaskEarlyWriteOffCommand,
  RepairTaskSubtasksCommand,
  RepairTaskWriteOffCommand,
  RepairTaskWriteCommand,
} from "@/features/repair-tasks/model/repair-task"

export class RepairTaskQueueDraftPersistedError extends Error {
  readonly taskId: string
  readonly expectedVersion: number

  constructor(params: {
    taskId: string
    expectedVersion: number
    message: string
    cause?: unknown
  }) {
    super(params.message, { cause: params.cause })
    this.name = "RepairTaskQueueDraftPersistedError"
    this.taskId = params.taskId
    this.expectedVersion = params.expectedVersion
  }
}

export interface RepairTasksClient {
  list(warehouseId: string): Promise<RepairTaskDto[]>
  listPendingAcceptance(warehouseId: string): Promise<RepairTaskDto[]>
  listWriteOffs(warehouseId: string): Promise<RepairTaskDto[]>
  getById(taskId: string, warehouseId: string): Promise<RepairTaskDto | null>
  getBySourceEstimateId(
    sourceEstimateId: string,
    warehouseId: string
  ): Promise<RepairTaskDto | null>
  saveDraft(command: RepairTaskWriteCommand): Promise<RepairTaskDto>
  queue(command: RepairTaskWriteCommand): Promise<RepairTaskDto>
  updateSubtasks(command: RepairTaskSubtasksCommand): Promise<RepairTaskDto>
  accept(command: RepairTaskAcceptCommand): Promise<RepairTaskDto>
  writeOff(command: RepairTaskWriteOffCommand): Promise<RepairTaskDto>
  earlyWriteOff(command: RepairTaskEarlyWriteOffCommand): Promise<RepairTaskDto>
}
