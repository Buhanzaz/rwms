export const SHIPMENT_DOCUMENT_STATES = [
  "DRAFT",
  "PREPARING",
  "AWAITING_CONFIRMATION",
  "CONFIRMING_PREPARATION",
  "SHIPPED",
  "CANCELLING",
  "CANCELLED",
  "CONFLICT",
  "RECONCILIATION_REQUIRED",
] as const

export type ShipmentDocumentState = (typeof SHIPMENT_DOCUMENT_STATES)[number]

export const SHIPMENT_LINE_STATES = [
  "PENDING",
  "DEPARTING",
  "DEPARTED",
  "ARRIVING",
  "ARRIVED",
  "CONFLICT",
  "CANCELLED",
] as const

export type ShipmentLineState = (typeof SHIPMENT_LINE_STATES)[number]

export type ShipmentLine = {
  id: string
  version: number
  lineNumber: number
  assetId: string
  assetVersion: number
  state: ShipmentLineState
  tenantSnapshot: string | null
}

export type ShipmentDocument = {
  id: string
  version: number
  documentType: "SHIPMENT"
  state: ShipmentDocumentState
  warehouseId: string
  destinationWarehouseId: null
  partySnapshot: string
  driverSnapshot: string
  lines: ShipmentLine[]
  createdAt: string
  updatedAt: string
}

export type ShipmentEquipmentAllocation = {
  equipmentId: string
  quantity: number
  expectedStockVersion: number
}

export type ShipmentPlanLine = {
  assetId: string
  assetVersion: number
  allocations: ShipmentEquipmentAllocation[]
}

export const SHIPMENT_STATE_LABELS: Record<ShipmentDocumentState, string> = {
  DRAFT: "Черновик",
  PREPARING: "Подготовка",
  AWAITING_CONFIRMATION: "Ждёт подтверждения",
  CONFIRMING_PREPARATION: "Подтверждается",
  SHIPPED: "Отгружено",
  CANCELLING: "Отменяется",
  CANCELLED: "Отменено",
  CONFLICT: "Конфликт",
  RECONCILIATION_REQUIRED: "Требуется сверка",
}
