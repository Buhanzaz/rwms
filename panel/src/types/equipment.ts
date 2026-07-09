import type { RentalItemStatus } from "@/features/rental-items/model/rental-item"

export type EquipmentCategory = "FURNITURE" | "ELECTRICAL"

export type EquipmentRentalUsageDto = {
  id: string
  rentalItemId: string
  rentalItemNumber: string
  rentalItemType: string
  rentalItemStatus: RentalItemStatus
  warehouseId: string
  quantity: number
}

export type EquipmentItemDto = {
  id: string
  warehouseId: string
  category: EquipmentCategory

  name: string

  totalQuantity: number
  stockQuantity: number
  cabinStockQuantity: number
  rentedQuantity: number
  writtenOffQuantity: number
  lostQuantity: number

  usages: EquipmentRentalUsageDto[]
}

export type EquipmentItemsQueryParams = {
  warehouseId: string
  search?: string
}

export type UpdateEquipmentUsagePayload = {
  usageId: string
  quantity: number
}
