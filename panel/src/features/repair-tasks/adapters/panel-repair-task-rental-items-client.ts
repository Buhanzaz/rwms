import {
  getAssetRentalItem,
  listAssetRentalItems,
} from "@/features/rental-items/api/asset-rental-items-api"
import { currentMaintenanceAccessToken } from "@/features/repair-estimates/api/maintenance-auth"
import type { RepairTaskRentalItemsClient } from "@/features/repair-tasks/ports/repair-task-rental-items-client"
import type {
  RentalItemDto,
  RentalItemStatus,
} from "@/features/rental-items/model/rental-item"

const DIRECT_REPAIR_EXCLUDED_STATUSES = [
  "AFTER_RENT",
  "WRITTEN_OFF",
  "WAITING_ESTIMATE_CONFIRMATION",
] satisfies RentalItemStatus[]

function toOption(item: RentalItemDto) {
  return {
    id: item.id,
    warehouseId: item.warehouseId,
    number: item.number,
  }
}

async function search({
  warehouseId,
  search: searchValue,
  page: pageNumber,
  size,
}: Parameters<RepairTaskRentalItemsClient["search"]>[0]) {
  const accessToken = await currentMaintenanceAccessToken()
  const page = await listAssetRentalItems({
    accessToken,
    warehouseId,
    search: searchValue,
    page: pageNumber,
    size,
    excludeStatuses: DIRECT_REPAIR_EXCLUDED_STATUSES,
  })

  return {
    items: page.content
      .map(toOption)
      .sort((left, right) => left.number.localeCompare(right.number, "ru")),
    page: page.page,
    size: page.size,
    totalElements: page.totalElements,
    totalPages: page.totalPages,
  }
}

export const panelRepairTaskRentalItemsClient: RepairTaskRentalItemsClient = {
  search,
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
    return toOption(item)
  },
}
