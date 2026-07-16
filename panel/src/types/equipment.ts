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

export type EquipmentWriteOffSummaryDto = {
  id: string
  warehouseId: string
  name: string
  writtenOffQuantity: number
}

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

export type EquipmentDispositionListItemDto =
  | ({ kind: "RETURN_DISPOSITION" } & ReturnEquipmentDispositionCaseDto)
  | ({ kind: "HISTORICAL_WRITE_OFF" } & EquipmentWriteOffSummaryDto)

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
