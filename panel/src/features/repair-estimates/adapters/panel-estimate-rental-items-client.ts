import {
  getRentalItem,
  getRentalItems,
} from "@/features/rental-items/api/rental-items-api"
import type { EstimateRentalItemsClient } from "@/features/repair-estimates/ports/estimate-rental-items-client"
import { isLinkedReturnEstimate } from "@/features/logistics/api/logistics-api"
import { hasActiveWarehouseTransferLowLevel } from "@/features/logistics/warehouse-transfers/active-transfer-guard"

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
  const page = await getRentalItems({
    warehouseId,
    search: searchValue,
    page: pageNumber,
    size,
    excludeStatuses: ["WRITTEN_OFF", "WAITING_ESTIMATE_CONFIRMATION"],
  })

  return {
    items: page.content
      .filter((item) => !hasActiveWarehouseTransferLowLevel(item.id))
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
    const item = await getRentalItem(rentalItemId)
    return item?.warehouseId === warehouseId &&
      item.status !== "WRITTEN_OFF" &&
      item.status !== "WAITING_ESTIMATE_CONFIRMATION" &&
      !hasActiveWarehouseTransferLowLevel(item.id)
      ? toOption(item)
      : null
  },
  async resolveLinkedReturnEstimate(warehouseId, rentalItemId, estimateId) {
    const item = await getRentalItem(rentalItemId)
    if (
      !item ||
      item.warehouseId !== warehouseId ||
      item.status !== "WAITING_ESTIMATE_CONFIRMATION" ||
      hasActiveWarehouseTransferLowLevel(item.id) ||
      !(await isLinkedReturnEstimate(warehouseId, rentalItemId, estimateId))
    )
      return null
    return toOption(item)
  },
}
