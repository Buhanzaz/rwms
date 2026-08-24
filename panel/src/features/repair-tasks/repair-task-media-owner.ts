import {
  inventoryFindingMediaOwner,
  maintenanceEstimateMediaOwner,
  maintenanceRepairMediaOwner,
  taskBoardEntryMediaOwner,
  type ServiceMediaOwner,
} from "@/features/media/media-service"
import type {
  RepairTaskDto,
  RepairTaskSubtaskDto,
} from "@/features/repair-tasks/model/repair-task"

type RepairWorkLine = RepairTaskSubtaskDto["workLines"][number]

/**
 * Selects the existing owner proof that can read a repair source reference;
 * it never changes the media object's canonical owner.
 */
export function repairTaskSourceMediaOwner(
  task: RepairTaskDto,
  line?: RepairWorkLine,
  taskBoardEntryId?: string | null
): ServiceMediaOwner {
  if (taskBoardEntryId) {
    return taskBoardEntryMediaOwner(taskBoardEntryId, task.warehouseId)
  }
  if (task.sourceInventoryFindingId) {
    return inventoryFindingMediaOwner(
      task.sourceInventoryFindingId,
      task.warehouseId
    )
  }
  if (task.kind !== "REWORK" && task.sourceEstimateId) {
    return maintenanceEstimateMediaOwner(
      task.sourceEstimateId,
      task.warehouseId
    )
  }
  return maintenanceRepairMediaOwner(
    line?.rework?.sourceRepairId ?? task.id,
    task.warehouseId
  )
}

/** Uses an executable task entry for retained source photos when one exists. */
export function repairTaskGeneralMediaOwner(
  task: RepairTaskDto
): ServiceMediaOwner {
  const entry =
    task.subtasks.find(
      (subtask) =>
        subtask.entryType !== "SHADOW" && Boolean(subtask.taskBoardEntryId)
    ) ?? task.subtasks.find((subtask) => Boolean(subtask.taskBoardEntryId))
  return repairTaskSourceMediaOwner(task, undefined, entry?.taskBoardEntryId)
}
