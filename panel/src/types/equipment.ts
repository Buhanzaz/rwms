import type { RentalItemStatus } from "@/features/rental-items/model/rental-item"

export type EquipmentCategory = "FURNITURE" | "ELECTRICAL" | "OTHER"

export type EquipmentBalanceLocationKind =
  "STOCK" | "CABIN_NON_RENTED" | "CABIN_RENTED" | "WRITTEN_OFF" | "LOST"

export type EquipmentBalanceDto = {
  id: string
  version: number
  equipmentId: string
  warehouseId: string
  rentalItemId: string | null
  locationKind: EquipmentBalanceLocationKind
  quantity: number
  activeHeldQuantity: number
  availableStock: number
}

/**
 * Asset-service equipment and its immutable-ledger totals for one warehouse.
 * Feature read models may populate `usages` by joining these canonical balance
 * UUIDs with the public asset-service rental-item projection.
 */
export type EquipmentItemDto = {
  id: string
  version: number
  warehouseId: string
  category: EquipmentCategory
  code: string
  name: string
  active: boolean
  comment: string | null

  totalQuantity: number
  stockQuantity: number
  cabinStockQuantity: number
  rentedQuantity: number
  writtenOffQuantity: number
  lostQuantity: number
  activeHeldQuantity: number
  availableStock: number
  balances: EquipmentBalanceDto[]

  usages: EquipmentRentalUsageDto[]
}

export type EquipmentRentalUsageDto = {
  id: string
  balanceVersion: number
  rentalItemId: string
  rentalItemNumber: string
  rentalItemType: string
  rentalItemStatus: RentalItemStatus
  warehouseId: string
  locationKind: Extract<
    EquipmentBalanceLocationKind,
    "CABIN_NON_RENTED" | "CABIN_RENTED"
  >
  quantity: number
  availableQuantity: number
}

export type EquipmentItemsQueryParams = {
  warehouseId: string
  search?: string
}

export type EquipmentWriteOffSummaryDto = {
  id: string
  warehouseId: string
  name: string
  writtenOffQuantity: number
}

export type EquipmentMovementDto = {
  id: string
  version: number
  equipmentId: string
  sourceBalanceId: string
  targetBalanceId: string
  quantity: number
  kind: string
  occurredAt: string
}

export type EquipmentDispositionDto = EquipmentMovementDto & {
  equipmentCode: string
  equipmentName: string
}

export type DisposeEquipmentInput = {
  equipmentId: string
  warehouseId: string
  sourceRentalItemId: string | null
  sourceLocationKind: EquipmentBalanceLocationKind
  sourceExpectedVersion: number
  quantity: number
  disposition: "WRITE_OFF" | "LOSS"
}

export type TransferEquipmentInput = {
  equipmentId: string
  sourceWarehouseId: string
  sourceRentalItemId: string | null
  sourceLocationKind: EquipmentBalanceLocationKind
  sourceExpectedVersion: number
  targetWarehouseId: string
  targetRentalItemId: string | null
  targetLocationKind: EquipmentBalanceLocationKind
  targetExpectedVersion: number
  quantity: number
}

export type EquipmentDispositionListItemDto = EquipmentDispositionDto
