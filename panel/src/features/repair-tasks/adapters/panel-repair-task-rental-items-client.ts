import { getAssetRentalItem } from "@/features/rental-items/api/asset-rental-items-api"
import { currentMaintenanceAccessToken } from "@/features/repair-estimates/api/maintenance-auth"
import type { RepairTaskRentalItemsClient } from "@/features/repair-tasks/ports/repair-task-rental-items-client"

export const panelRepairTaskRentalItemsClient: RepairTaskRentalItemsClient = {
  async resolveById(warehouseId, rentalItemId) {
    const accessToken = await currentMaintenanceAccessToken()
    const item = await getAssetRentalItem(accessToken, rentalItemId)
    if (
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
}
