export const RETURN_DOCUMENT_STATES = [
  "DRAFT",
  "REGISTERING",
  "INSPECTION_REQUIRED",
  "ACCEPTING",
  "ACCEPTED",
  "ESTIMATE_PENDING",
  "ESTIMATE_REQUESTED",
  "CONFLICT",
  "RECONCILIATION_REQUIRED",
  "CANCELLED",
] as const

export type ReturnDocumentState = (typeof RETURN_DOCUMENT_STATES)[number]

export const RETURN_LINE_STATES = [
  "PENDING",
  "DEPARTING",
  "DEPARTED",
  "ARRIVING",
  "ARRIVED",
  "CONFLICT",
  "CANCELLED",
] as const

export type ReturnLineState = (typeof RETURN_LINE_STATES)[number]

export type ReturnLine = {
  id: string
  version: number
  lineNumber: number
  assetId: string
  assetVersion: number
  state: ReturnLineState
  tenantSnapshot: string | null
  rentalOrderId: string | null
}

export type ReturnDocument = {
  id: string
  version: number
  documentType: "RETURN"
  state: ReturnDocumentState
  warehouseId: string
  destinationWarehouseId: null
  partySnapshot: string | null
  driverSnapshot: string | null
  clientId: string | null
  equipmentMovementTaskId: null
  lines: ReturnLine[]
  createdAt: string
  updatedAt: string
}

export type CreateReturnLine = {
  assetId: string
  assetVersion: number
  tenantSnapshot: string
  rentalOrderId: string
}

export type MediaReference = {
  mediaId: string
  generation: number
}

export type ReturnMediaLine = {
  lineId: string
  references: MediaReference[]
  additionalEquipment: ReturnAdditionalEquipment[]
}

export type ReturnAdditionalEquipment = {
  equipmentId: string
  quantity: number
}

export type EquipmentShortage = {
  equipmentId: string
  missingQuantity: number
}

export type ReturnShortageLine = {
  lineId: string
  shortages: EquipmentShortage[]
}

export const RETURN_STATE_LABELS: Record<ReturnDocumentState, string> = {
  DRAFT: "Черновик",
  REGISTERING: "Регистрируется",
  INSPECTION_REQUIRED: "Требуется осмотр",
  ACCEPTING: "Принимается",
  ACCEPTED: "Принят",
  ESTIMATE_PENDING: "Формируется смета",
  ESTIMATE_REQUESTED: "Смета запрошена",
  CONFLICT: "Конфликт",
  RECONCILIATION_REQUIRED: "Требуется сверка",
  CANCELLED: "Отменён",
}
