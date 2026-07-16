import type {
  RentalItemContentsItemDto,
  RentalItemStatus,
} from "@/features/rental-items/model/rental-item"
import type { RepairEstimateMediaRefDto } from "@/features/repair-estimates/model/repair-estimate"

export type ReturnItemTechnicalState =
  | "CONFLICT"
  | "PENDING_INSPECTION"
  | "ACCEPTED"
  | "ESTIMATE_CREATED"
  | "ESTIMATE_LINK_FAILED"
  | "ESTIMATE_IN_PROGRESS"
  | "CANCELLED"

export type ReturnIntakeMode = "NEW_CABIN" | "KNOWN_CABIN" | "OVERRIDE_EXISTING"

export type ReturnExpectedContentsSource =
  "LATEST_SHIPMENT" | "CURRENT_CABIN" | "MANUAL" | "UNKNOWN"

export type ReturnPassportSnapshotDto = {
  type: string
  dimensions: string | null
  finishing: string | null
  category: string | null
  characteristics: string[]
  linoleum: boolean | null
}

export type ReturnIntakeConflictDto = {
  code:
    | "OTHER_WAREHOUSE"
    | "TENANT_MISMATCH"
    | "ACTIVE_PROCESS"
    | "WRITTEN_OFF"
    | "STALE_CONTENTS"
  message: string
  conflictingWarehouseId: string | null
  createdAt: string
}

export type ReturnIntakeAuditDto = {
  id: string
  type:
    | "INTAKE_REGISTERED"
    | "CONFLICT_REGISTERED"
    | "PASSPORT_CHANGED"
    | "EXPECTED_CONTENTS_CORRECTED"
  createdAt: string
  createdBy: string
  reason: string | null
}

export type ReturnConflictResolutionDto = {
  kind: "WAREHOUSE_TRANSFER" | "ACCOUNTING_CORRECTION"
  id: string
  resolvedAt: string
  resolvedBy: string
}

export type ReturnFurnitureDispositionDto = {
  id: string
  name: string
  quantity: number
  remainingQuantity: number
  origin: "PREVIOUS_SNAPSHOT" | "EXTRA_FACTUAL"
  status: "PENDING" | "RESOLVED"
  allocations: Array<{
    id: string
    idempotencyKey: string
    status: "PENDING_TASK" | "PENDING_LEDGER" | "APPLIED"
    action:
      "KEEP_IN_CABIN" | "RETURN_TO_STOCK" | "WRITE_OFF" | "TRANSFER_TO_CABIN"
    quantity: number
    targetRentalItemId: string | null
    reason: string | null
    createdAt: string
    createdBy: string
    taskExternalId: string | null
  }>
  action:
    | "KEEP_IN_CABIN"
    | "RETURN_TO_STOCK"
    | "WRITE_OFF"
    | "TRANSFER_TO_CABIN"
    | null
  targetRentalItemId: string | null
  reason: string | null
  resolvedAt: string | null
  resolvedBy: string | null
}

export type ReturnReceiptItemDto = {
  id: string
  version: number
  rentalItemId: string
  cabinNumber: string
  selectionSource: "CLIENT_LIST" | "MANUAL"
  originalTenant: string | null
  intakeMode: ReturnIntakeMode
  passportSnapshot: ReturnPassportSnapshotDto | null
  previousContents: RentalItemContentsItemDto[]
  expectedContentsSource: ReturnExpectedContentsSource
  expectedContentsEditedReason: string | null
  conflicts: ReturnIntakeConflictDto[]
  conflictResolutions: ReturnConflictResolutionDto[]
  intakeHistory: ReturnIntakeAuditDto[]
  furnitureDispositions: ReturnFurnitureDispositionDto[]
  contentsMode: "FACTUAL" | "LEGACY_QUARANTINE"
  expectedContents: RentalItemContentsItemDto[]
  returnedContents: RentalItemContentsItemDto[]
  technicalState: ReturnItemTechnicalState
  acceptedAt: string | null
  media: RepairEstimateMediaRefDto[]
  sourceEstimateId: string | null
  pendingEstimateId: string | null
  estimateClaimId: string | null
  estimateClaimedAt: string | null
}

export type ReturnReceiptDto = {
  id: string
  version: number
  warehouseId: string
  fromParty: string
  driverId: string | null
  driverName: string | null
  shipmentDate: string | null
  returnDate: string
  receptionMethod: "WAREHOUSE_INSPECTION"
  createdAt: string
  createdBy: string
  receiverName: string | null
  updatedAt: string | null
  updatedBy: string | null
  items: ReturnReceiptItemDto[]
}

/** Compatibility projection used only by the estimate hand-off. */
export type ReturnTaskDto = ReturnReceiptItemDto & {
  receiptId: string
  warehouseId: string
  fromParty: string
  driverId: string | null
  driverName: string | null
  returnDate: string
  createdAt: string
  createdBy: string
  receiverName: string | null
  shipmentDate: string | null
  status: ReturnItemTechnicalState
}

export type ReturnReceiptItemViewDto = ReturnReceiptItemDto & {
  currentStatus: RentalItemStatus
  hasUnresolvedEquipmentDisposition: boolean
  hasEquipmentDispositionHistory: boolean
}

export type ReturnReceiptViewDto = Omit<ReturnReceiptDto, "items"> & {
  items: ReturnReceiptItemViewDto[]
}

export type ShipmentContentsChangeDto = {
  name: string
  beforeQuantity: number
  plannedQuantity: number
  direction: "BRING" | "TAKE"
  quantity: number
  label: string
}

export type ShipmentPreparationTaskDto = {
  id: string
  externalTaskId: string
  title: "Подготовка к отгрузке"
  purpose: "GENERAL_WORKERS"
  createdAt: string
  lines: ShipmentContentsChangeDto[]
  dispatchStatus: "DRAFT" | "PENDING" | "DISPATCHED" | "FAILED"
  boardTaskId: string | null
  queueId: string | null
  queueCode: string | null
  dispatchError: string | null
  dispatchAttemptId: string | null
  dispatchAttemptedAt: string | null
}

export type ShipmentPreparationDispatchDto = {
  externalTaskId: string
  warehouseId: string
  rentalItemId: string
  cabinNumber: string
  status: "PENDING" | "DISPATCHED" | "FAILED"
  boardTaskId: string | null
  queueId: string | null
  queueCode: string | null
  taskText: string
  lastAttemptAt: string
  dispatchAttemptId: string | null
  error: string | null
}

export type ShipmentItemSnapshotDto = {
  rentalItemId: string
  cabinNumber: string
  contentsBefore: RentalItemContentsItemDto[]
  contentsPlanned: RentalItemContentsItemDto[]
  changes: ShipmentContentsChangeDto[]
  preparationTask: ShipmentPreparationTaskDto | null
}

export type ShipmentDto = {
  id: string
  version: number
  warehouseId: string
  company: string
  driverId: string
  driverName: string
  shipmentDate: string
  status: "PREPARING" | "FAILED" | "SHIPPED"
  error: string | null
  createdAt: string
  createdBy: string
  items: ShipmentItemSnapshotDto[]
}

export type LogisticsStateV2 = {
  service: "logistics"
  schemaVersion: 2
  revision: number
  returnReceipts: ReturnReceiptDto[]
  shipments: ShipmentDto[]
  preparationDispatches: ShipmentPreparationDispatchDto[]
}

export type ShipmentCandidateDto = {
  item: {
    id: string
    number: string
    status: RentalItemStatus
    tenant: string | null
    contentsItems: RentalItemContentsItemDto[]
  }
  reservedForCompany: boolean
}
