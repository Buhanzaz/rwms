export const TRANSFER_DOCUMENT_STATES = [
  "DRAFT",
  "CANCELLING",
  "DEPARTING",
  "IN_TRANSIT",
  "ARRIVING",
  "COMPLETED",
  "CANCELLED",
  "CONFLICT",
  "RECONCILIATION_REQUIRED",
] as const

export type TransferDocumentState = (typeof TRANSFER_DOCUMENT_STATES)[number]

export const TRANSFER_LINE_STATES = [
  "PENDING",
  "DEPARTING",
  "DEPARTED",
  "ARRIVING",
  "ARRIVED",
  "CONFLICT",
  "CANCELLED",
] as const

export type TransferLineState = (typeof TRANSFER_LINE_STATES)[number]

export type TransferLine = {
  id: string
  version: number
  lineNumber: number
  assetId: string
  assetVersion: number
  state: TransferLineState
  tenantSnapshot: string | null
  rentalOrderId: null
}

export type TransferDocument = {
  id: string
  version: number
  documentType: "TRANSFER"
  state: TransferDocumentState
  warehouseId: string
  destinationWarehouseId: string
  partySnapshot: null
  driverSnapshot: string | null
  clientId: null
  equipmentMovementTaskId: string | null
  lines: TransferLine[]
  createdAt: string
  updatedAt: string
}

export type CreateTransferLine = {
  assetId: string
  assetVersion: number
}

export type CreateTransferEquipmentLine = {
  equipmentId: string
  expectedSourceBalanceVersion: number
  quantity: number
}

export type TransferMediaReference = {
  mediaId: string
  generation: number
}

export const TRANSFER_STATE_LABELS: Record<TransferDocumentState, string> = {
  DRAFT: "Черновик",
  CANCELLING: "Отменяется",
  DEPARTING: "Отправляется",
  IN_TRANSIT: "В пути",
  ARRIVING: "Принимается",
  COMPLETED: "Завершено",
  CANCELLED: "Отменено",
  CONFLICT: "Конфликт",
  RECONCILIATION_REQUIRED: "Требуется сверка",
}

export const TRANSFER_LINE_STATE_LABELS: Record<TransferLineState, string> = {
  PENDING: "Ожидает отправки",
  DEPARTING: "Отправляется",
  DEPARTED: "В пути",
  ARRIVING: "Принимается",
  ARRIVED: "Принята",
  CONFLICT: "Конфликт",
  CANCELLED: "Отменена",
}
