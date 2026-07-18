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
 * `usages` deliberately remains empty: the public API does not expose a
 * per-cabin usage read model, so the panel must not reconstruct one locally.
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
  rentalItemId: string
  rentalItemNumber: string
  rentalItemType: string
  rentalItemStatus: RentalItemStatus
  warehouseId: string
  quantity: number
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

/** The types below are retained only for explicit fail-closed transition stubs. */
export type ReturnEquipmentDispositionStatus =
  "ACTION_REQUIRED" | "PARTIALLY_RESOLVED" | "RESOLVED"

export type ReturnEquipmentDispositionAction =
  "RETURN_TO_STOCK" | "WRITE_OFF" | "TRANSFER_TO_CABIN"

export type ReturnEquipmentDispositionResolutionDto = {
  id: string
  idempotencyKey: string
  action: ReturnEquipmentDispositionAction
  quantity: number
  targetRentalItemId: string | null
  targetCabinNumber: string | null
  reason: string | null
  createdAt: string
  createdBy: string
}

export type ReturnEquipmentDispositionCaseDto = {
  id: string
  version: number
  warehouseId: string
  returnReceiptId: string
  returnItemId: string
  sourceRentalItemId: string
  sourceCabinNumber: string
  equipmentMasterItemId: string | null
  equipmentName: string
  normalizedEquipmentKey: string
  receivedQuantity: number
  remainingQuantity: number
  receivedAt: string
  status: ReturnEquipmentDispositionStatus
  resolutions: ReturnEquipmentDispositionResolutionDto[]
}

export type EquipmentDispositionListItemDto = EquipmentDispositionDto

export type RegisterReturnEquipmentDispositionInput = {
  warehouseId: string
  returnReceiptId: string
  returnItemId: string
  sourceRentalItemId: string
  sourceCabinNumber: string
  receivedAt: string
  contents: Array<{ name: string; quantity: number }>
}

export type ResolveReturnEquipmentDispositionInput = {
  caseId: string
  expectedVersion: number
  idempotencyKey: string
  action: ReturnEquipmentDispositionAction
  quantity: number
  targetRentalItemId?: string | null
  reason?: string | null
  createdBy: string
  confirmCreateMasterItem?: boolean
}

export type UpdateEquipmentUsagePayload = {
  usageId: string
  quantity: number
}
