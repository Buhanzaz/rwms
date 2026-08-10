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
  driverWorkerId: string | null
  clientId: string | null
  equipmentMovementTaskId: string | null
  scheduledDate: string | null
  rentalOrderId: string | null
  /** Shipment batch that produced this return document. */
  rentalShipmentId?: string | null
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
  equipmentConfirmed: true
  additionalEquipment: ReturnAdditionalEquipment[]
}

export type ReturnAdditionalEquipment = {
  equipmentId: string
  quantity: number
}

/** Proof that one return line can start its own maintenance estimate. */
export type ReturnEstimateLine = {
  lineId: string
  references: MediaReference[]
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
