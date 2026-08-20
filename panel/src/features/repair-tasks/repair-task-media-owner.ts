import {
  inventoryFindingMediaOwner,
  maintenanceEstimateMediaOwner,
  maintenanceRepairMediaOwner,
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
  line?: RepairWorkLine
): ServiceMediaOwner {
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
