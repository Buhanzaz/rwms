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
  rentalOrderId: string | null
}

export type ShipmentDocument = {
  id: string
  version: number
  documentType: "SHIPMENT"
  state: ShipmentDocumentState
  warehouseId: string
  destinationWarehouseId: null
  partySnapshot: string
  driverSnapshot: string | null
  clientId: string | null
  equipmentMovementTaskId: string | null
  scheduledDate: string | null
  rentalOrderId: string | null
  lines: ShipmentLine[]
  createdAt: string
  updatedAt: string
}

export type ShipmentEquipmentAllocation = {
  equipmentId: string
  quantity: number
  expectedStockVersion: number
}

export type ShipmentFurnitureTask = {
  rentalItemId: string
  unitNumber: string
  taskId: string | null
  lineCount: number
}

export type ShipmentFurnitureTaskResult = {
  shipmentId: string
  shipmentVersion: number
  tasks: ShipmentFurnitureTask[]
}

export const SHIPMENT_FURNITURE_READINESS_STATES = [
  "NOT_REQUIRED",
  "READY",
  "REQUIRES_TASK_CREATION",
  "AWAITING_TASK_COMPLETION",
  "BLOCKED",
] as const

export type ShipmentFurnitureReadinessState =
  (typeof SHIPMENT_FURNITURE_READINESS_STATES)[number]

export const SHIPMENT_FURNITURE_TASK_STATES = [
  "RESERVING",
  "REGISTERING_TASK",
  "AWAITING_WORKER",
  "EXECUTING",
  "CANCELLING",
  "COMPLETED",
  "CANCELLED",
  "EXPIRED",
  "CONFLICT",
  "RECONCILIATION_REQUIRED",
] as const

export type ShipmentFurnitureTaskState =
  (typeof SHIPMENT_FURNITURE_TASK_STATES)[number]

export type ShipmentFurnitureTaskStatus = {
  rentalItemId: string
  unitNumber: string
  taskId: string
  externalTaskId: string
  taskBoardTaskId: string | null
  taskState: ShipmentFurnitureTaskState
  lineCount: number
}

export type ShipmentFurnitureReadiness = {
  shipmentId: string
  shipmentVersion: number
  state: ShipmentFurnitureReadinessState
  tasks: ShipmentFurnitureTaskStatus[]
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
