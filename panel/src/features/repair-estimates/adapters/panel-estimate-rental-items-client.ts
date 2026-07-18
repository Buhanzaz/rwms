import {
  getAssetRentalItem,
  listAssetRentalItems,
} from "@/features/rental-items/api/asset-rental-items-api"
import { currentMaintenanceAccessToken } from "@/features/repair-estimates/api/maintenance-auth"
import type { EstimateRentalItemsClient } from "@/features/repair-estimates/ports/estimate-rental-items-client"

function toOption(item: { id: string; warehouseId: string; number: string }) {
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
}: Parameters<EstimateRentalItemsClient["search"]>[0]) {
  const accessToken = await currentMaintenanceAccessToken()
  const page = await listAssetRentalItems({
    accessToken,
    warehouseId,
    search: searchValue,
    page: pageNumber,
    size,
    excludeStatuses: ["WRITTEN_OFF", "WAITING_ESTIMATE_CONFIRMATION"],
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

export const panelEstimateRentalItemsClient: EstimateRentalItemsClient = {
  search,
  async resolveById(warehouseId, rentalItemId) {
    const accessToken = await currentMaintenanceAccessToken()
    const item = await getAssetRentalItem(accessToken, rentalItemId)
    return item.warehouseId === warehouseId &&
      item.status !== "WRITTEN_OFF" &&
      item.status !== "WAITING_ESTIMATE_CONFIRMATION"
      ? toOption(item)
      : null
  },
}
