import type {
  RepairTaskDto,
  RepairTaskAcceptCommand,
  RepairTaskCompleteEntryCommand,
  RepairTaskFromEstimateCommand,
  RepairTaskFromInventoryFindingCommand,
  RepairTaskEntryRefCommand,
  RepairTaskEarlyWriteOffCommand,
  RepairTaskMoveEntryCommand,
  RepairTaskSyncFromEstimateCommand,
  RepairTaskSubtasksCommand,
  RepairTaskTakeEntryCommand,
  RepairTaskWriteOffCommand,
  RepairTaskWriteCommand,
} from "@/features/repair-tasks/model/repair-task"

export interface RepairTasksClient {
  list(warehouseId: string): Promise<RepairTaskDto[]>
  listPendingAcceptance(warehouseId: string): Promise<RepairTaskDto[]>
  listWriteOffs(warehouseId: string): Promise<RepairTaskDto[]>
  getById(taskId: string, warehouseId: string): Promise<RepairTaskDto | null>
  getBySourceEstimateId(
    sourceEstimateId: string,
    warehouseId: string
  ): Promise<RepairTaskDto | null>
  getByInventoryFinding(
    sourceInventoryId: string,
    sourceInventoryFindingId: string,
    warehouseId: string
  ): Promise<RepairTaskDto | null>
  saveDraft(command: RepairTaskWriteCommand): Promise<RepairTaskDto>
  queue(command: RepairTaskWriteCommand): Promise<RepairTaskDto>
  updateSubtasks(command: RepairTaskSubtasksCommand): Promise<RepairTaskDto>
  moveEntry(command: RepairTaskMoveEntryCommand): Promise<RepairTaskDto>
  takeEntry(command: RepairTaskTakeEntryCommand): Promise<RepairTaskDto>
  pauseEntry(command: RepairTaskEntryRefCommand): Promise<RepairTaskDto>
  resumeEntry(command: RepairTaskEntryRefCommand): Promise<RepairTaskDto>
  completeEntry(command: RepairTaskCompleteEntryCommand): Promise<RepairTaskDto>
  accept(command: RepairTaskAcceptCommand): Promise<RepairTaskDto>
  writeOff(command: RepairTaskWriteOffCommand): Promise<RepairTaskDto>
  earlyWriteOff(command: RepairTaskEarlyWriteOffCommand): Promise<RepairTaskDto>
  upsertFromEstimate(
    command: RepairTaskFromEstimateCommand
  ): Promise<RepairTaskDto>
  upsertByInventoryFinding(
    command: RepairTaskFromInventoryFindingCommand
  ): Promise<RepairTaskDto>
  syncFromEstimate(
    command: RepairTaskSyncFromEstimateCommand
  ): Promise<RepairTaskDto>
}
