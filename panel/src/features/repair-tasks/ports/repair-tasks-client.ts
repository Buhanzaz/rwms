import type {
  RepairTaskDto,
  RepairTaskAcceptCommand,
  RepairTaskEarlyWriteOffCommand,
  RepairTaskSubtasksCommand,
  RepairTaskWriteOffCommand,
  RepairTaskWriteCommand,
} from "@/features/repair-tasks/model/repair-task"
import type { PropertyDispositionDecision } from "@/features/write-offs/property-dispositions-api"

export class RepairTaskQueueDraftPersistedError extends Error {
  readonly taskId: string
  readonly expectedVersion: number
  readonly code: string | null

  constructor(params: {
    taskId: string
    expectedVersion: number
    message: string
    code?: string | null
    cause?: unknown
  }) {
    super(params.message, { cause: params.cause })
    this.name = "RepairTaskQueueDraftPersistedError"
    this.taskId = params.taskId
    this.expectedVersion = params.expectedVersion
    this.code = params.code ?? null
  }
}

export interface RepairTasksClient {
  listPendingAcceptance(warehouseId: string): Promise<RepairTaskDto[]>
  getById(taskId: string, warehouseId: string): Promise<RepairTaskDto | null>
  getBySourceEstimateId(
    sourceEstimateId: string,
    warehouseId: string
  ): Promise<RepairTaskDto | null>
  saveDraft(command: RepairTaskWriteCommand): Promise<RepairTaskDto>
  queue(command: RepairTaskWriteCommand): Promise<RepairTaskDto>
  updateSubtasks(command: RepairTaskSubtasksCommand): Promise<RepairTaskDto>
  accept(command: RepairTaskAcceptCommand): Promise<RepairTaskDto>
  writeOff(
    command: RepairTaskWriteOffCommand
  ): Promise<PropertyDispositionDecision>
  earlyWriteOff(
    command: RepairTaskEarlyWriteOffCommand
  ): Promise<PropertyDispositionDecision>
}
