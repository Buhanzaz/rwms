import {
  getRentalItem,
  RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES,
  updateRentalItemStatusForRepairWorkflow,
} from "@/features/rental-items/api/rental-items-api"
import type { RepairTaskRentalItemsClient } from "@/features/repair-tasks/ports/repair-task-rental-items-client"

export const panelRepairTaskRentalItemsClient: RepairTaskRentalItemsClient = {
  async resolveById(warehouseId, rentalItemId) {
    const item = await getRentalItem(rentalItemId)
    if (
      !item ||
      item.warehouseId !== warehouseId ||
      item.status === "WRITTEN_OFF" ||
      item.status === "WAITING_ESTIMATE_CONFIRMATION"
    ) {
      return null
    }
    return {
      id: item.id,
      warehouseId: item.warehouseId,
      number: item.number,
    }
  },
  async updateStatus(params) {
    await updateRentalItemStatusForRepairWorkflow({
      warehouseId: params.warehouseId,
      rentalItemId: params.rentalItemId,
      status: params.status,
      allowedSourceStatuses:
        params.status === "REPAIR"
          ? [
              ...RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES,
              ...(params.allowWaitingEstimateConfirmation
                ? (["WAITING_ESTIMATE_CONFIRMATION"] as const)
                : []),
            ]
          : params.status === "FREE"
            ? RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES
            : params.status === "WAITING_REPAIR_CHECK"
              ? ["REPAIR", "WAITING_REPAIR_CHECK"]
              : undefined,
    })
  },
}
