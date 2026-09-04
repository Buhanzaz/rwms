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
  /** Physical source remains the transfer source even when the document is read at its destination. */
  inventorySourceWarehouseId: string
  /** Inventory-created shipment furniture is never part of a warehouse transfer line. */
  inventoryShipmentFurniture: null
}

export type TransferDocument = {
  id: string
  version: number
  documentType: "TRANSFER"
  /** Interwarehouse work is deliberately separate from customer delivery. */
  customerDeliveryPurpose: null
  state: TransferDocumentState
  warehouseId: string
  destinationWarehouseId: string
  /** Optional reverse leg returned by a transfer-creation response. */
  linkedReturnTransferId: string | null
  partySnapshot: null
  /** Immutable assigned-driver display snapshot; never use the opaque ID in the UI. */
  driverSnapshot: string | null
  driverWorkerId: string | null
  clientId: null
  /** Historical rental imports are only shipment or return facts, never transfers. */
  historicalRentalImport: false
  equipmentMovementTaskId: string | null
  scheduledDate: string
  /** Transfers are warehouse-owned and never belong to a rental order. */
  rentalOrderId: null
  /** Transfers are not created from rental shipments. */
  rentalShipmentId: null
  /** Inventory outcomes create only return or shipment documents, never transfers. */
  inventorySourceId: null
  inventorySourceFindingId: null
  inventorySourceDispositionKind: null
  lines: TransferLine[]
  createdAt: string
  updatedAt: string
}

/** Supported post-arrival intent for a driver or vehicle. */
export type TransferResourceRepositionMode = "NONE" | "TEMPORARY" | "PERMANENT"

/** Independent resource intent stored with a transfer plan. */
export type TransferResourceReposition = {
  resourceId: string | null
  mode: TransferResourceRepositionMode
  until: string | null
}

/** Furniture catalog requirement for one cabin in a typed group. */
export type TransferFurniturePerCabinRequest = {
  furnitureCatalogItemId: string
  quantityPerCabin: number
}

/** Optimistically fenced physical cabin selected for a planned group. */
export type TransferCabinAllocationRequest = {
  assetId: string
  assetVersion: number
}

/** Catalog-backed cabin requirement that may be saved before allocation. */
export type TransferCabinGroupRequest = {
  rentalTypeId: string
  dimensionId: string | null
  finishingId: string | null
  characteristicIds: string[]
  linoleum: boolean | null
  quantity: number
  furniturePerCabin: TransferFurniturePerCabinRequest[]
  allocatedCabins: TransferCabinAllocationRequest[]
}

/** Independent furniture cargo that is not counted as cabin contents. */
export type TransferLooseFurnitureRequest = {
  furnitureCatalogItemId: string
  quantity: number
}

/** Complete replace-on-write draft plan accepted by logistics-service. */
export type TransferPlanRequest = {
  plannedDepartureAt: string | null
  plannedArrivalAt: string | null
  logisticsComment: string | null
  tripDriverId: string | null
  tripVehicleId: string | null
  driverReposition: TransferResourceReposition | null
  vehicleReposition: TransferResourceReposition | null
  cabinGroups: TransferCabinGroupRequest[]
  looseFurniture: TransferLooseFurnitureRequest[]
}

/** Lifecycle of the planning projection independently from document effects. */
export type TransferPlanState = "DRAFT" | "CONFIRMED"

/** Durable asset reservation readiness reported after confirmation. */
export type TransferReservationReadiness = "NOT_RESERVED" | "RESERVED"

/** Calculated per-cabin requirement in the server plan projection. */
export type TransferFurniturePerCabin = TransferFurniturePerCabinRequest & {
  totalQuantity: number
}

/** Server-owned group identity and calculated totals. */
export type TransferCabinGroup = Omit<
  TransferCabinGroupRequest,
  "furniturePerCabin"
> & {
  groupId: string
  position: number
  furniturePerCabin: TransferFurniturePerCabin[]
}

/** Server-calculated furniture totals without double-counting cabin contents. */
export type TransferFurnitureTotal = {
  furnitureCatalogItemId: string
  cabinRequirementQuantity: number
  looseQuantity: number
  totalQuantity: number
}

/** Strict logistics-owned planning projection for one interwarehouse transfer. */
export type TransferPlan = {
  transferId: string
  documentVersion: number
  documentState: TransferDocumentState
  planId: string | null
  planVersion: number | null
  state: TransferPlanState
  reservationReadiness: TransferReservationReadiness
  readinessDetail: string | null
  legacyCompatible: boolean
  scheduledDate: string
  plannedDepartureAt: string | null
  plannedArrivalAt: string | null
  logisticsComment: string | null
  tripDriverId: string | null
  tripVehicleId: string | null
  driverReposition: TransferResourceReposition
  vehicleReposition: TransferResourceReposition
  cabinGroups: TransferCabinGroup[]
  looseFurniture: TransferLooseFurnitureRequest[]
  totalCabinCount: number
  furnitureTotals: TransferFurnitureTotal[]
}

export const TRANSFER_FURNITURE_READINESS_STATES = [
  "NOT_REQUIRED",
  "READY",
  "AWAITING_TASK_COMPLETION",
  "BLOCKED",
] as const

export type TransferFurnitureReadinessState =
  (typeof TRANSFER_FURNITURE_READINESS_STATES)[number]

export const TRANSFER_FURNITURE_TASK_STATES = [
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

export type TransferFurnitureTaskState =
  (typeof TRANSFER_FURNITURE_TASK_STATES)[number]

export type TransferFurnitureTaskStatus = {
  rentalItemId: string
  unitNumber: string
  taskId: string
  externalTaskId: string
  taskBoardTaskId: string | null
  taskState: TransferFurnitureTaskState
  lineCount: number
}

export type TransferFurnitureReadiness = {
  transferId: string
  transferVersion: number
  state: TransferFurnitureReadinessState
  tasks: TransferFurnitureTaskStatus[]
}

export type CreateTransferLine = {
  assetId: string
  assetVersion: number
}

export type CabinFurnitureRequirement = {
  equipmentId: string
  quantity: number
}

export type TransferFurnitureReplacement = {
  assetId: string
  contents: CabinFurnitureRequirement[]
}

export type TransferMediaReference = {
  mediaId: string
  generation: number
}

export type TransferArrivalPreflight = {
  transferId: string
  lineId: string
  activeRepairId: string | null
  priorityRequired: boolean
  missingQueueDefinitionIds: string[]
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
