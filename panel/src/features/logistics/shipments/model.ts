import type {
  RentalItemContentsItemDto,
  RentalItemDto,
} from "@/features/rental-items/model/rental-item"

export type ShipmentContentsChange = {
  name: string
  beforeQuantity: number
  plannedQuantity: number
  direction: "BRING" | "TAKE"
  quantity: number
  label: string
}

export type ShipmentSourceAllocation = {
  sourceType: "WAREHOUSE" | "CABIN"
  sourceRentalItemId: string | null
  sourceCabinNumber: string | null
  expectedSourceVersion: number | null
  items: RentalItemContentsItemDto[]
}

export type ShipmentPreparationTask = {
  id: string
  externalTaskId: string
  createdAt: string
  lines: ShipmentContentsChange[]
  dispatchStatus: "DRAFT" | "PENDING" | "DISPATCHED" | "FAILED" | "CANCELLED"
  boardTaskId: string | null
  boardTaskVersion: number | null
  queueId: string | null
  queueCode: string | null
  dispatchError: string | null
  dispatchAttemptId: string | null
  dispatchAttemptedAt: string | null
}

export type ShipmentItem = {
  rentalItemId: string
  cabinNumber: string
  expectedTargetVersion: number | null
  contentsBefore: RentalItemContentsItemDto[]
  contentsPlanned: RentalItemContentsItemDto[]
  changes: ShipmentContentsChange[]
  sourceAllocations: ShipmentSourceAllocation[]
  preparationState: "PLANNED" | "TASK_SENT" | "READY" | "CONFLICT"
  preparationTask: ShipmentPreparationTask | null
  conflict: string | null
}

export type ShipmentAuditEvent = {
  id: string
  type:
    | "DRAFT_SAVED"
    | "STOCK_RESERVED"
    | "TASK_DISPATCHED"
    | "PREPARATION_CONFIRMED"
    | "PREPARATION_CONFLICT"
    | "SHIPMENT_FINALIZED"
    | "SHIPMENT_CANCELLED"
  occurredAt: string
  actor: string
  details: string
}

export type Shipment = {
  id: string
  version: number
  warehouseId: string
  company: string
  driverId: string
  driverName: string
  shipmentDate: string
  status:
    | "PREPARING"
    | "FAILED"
    | "AWAITING_CONFIRMATION"
    | "APPLYING"
    | "FINALIZING"
    | "READY_TO_SHIP"
    | "CONFLICT"
    | "SHIPPED"
    | "CANCELLED"
    | "LEGACY_QUARANTINE"
  error: string | null
  createdAt: string
  createdBy: string
  items: ShipmentItem[]
  audit: ShipmentAuditEvent[]
  evidence: "PROVEN" | "LEGACY_UNPROVEN"
  applicationAttempt: ShipmentApplicationAttempt | null
  finalizationAttempt: ShipmentFinalizationAttempt | null
}

export type ShipmentApplicationAttempt = {
  id: string
  phase: "PREPARED" | "RENTALS_WRITTEN" | "EQUIPMENT_WRITTEN"
  createdAt: string
  actor: string
  rentalMutations: Array<{
    id: string
    before: RentalItemDto
    after: RentalItemDto
  }>
  equipmentMutations: Array<{
    id: string
    before: ShipmentEquipmentSnapshot | null
    after: ShipmentEquipmentSnapshot
  }>
}

export type ShipmentEquipmentSnapshot = {
  id: string
  warehouseId: string
  name: string
  stockQuantity: number
  writtenOffQuantity: number
  lostQuantity: number
}

export type ShipmentFinalizationAttempt = {
  id: string
  phase: "PREPARED" | "RENTALS_WRITTEN"
  createdAt: string
  actor: string
  rentalMutations: Array<{
    id: string
    before: RentalItemDto
    after: RentalItemDto
  }>
}

export type ShipmentCandidate = {
  item: {
    id: string
    version: number
    number: string
    status: string
    tenant: string | null
    contentsItems: RentalItemContentsItemDto[]
  }
  reservedForCompany: boolean
}

export type ShipmentSourceCandidate = {
  id: string
  version: number
  number: string
  contentsItems: RentalItemContentsItemDto[]
}

export type ShipmentDraftInput = {
  shipmentId?: string | null
  expectedVersion?: number
  warehouseId: string
  company: string
  driverId: string
  driverName: string
  shipmentDate: string
  items: Array<{
    rentalItemId: string
    expectedTargetVersion: number | null
    contentsBefore: RentalItemContentsItemDto[]
    contentsPlanned: RentalItemContentsItemDto[]
    sourceAllocations: ShipmentSourceAllocation[]
    preparationTask: ShipmentPreparationTask | null
  }>
  createdBy: string
}

export type ShipmentEnvelope = {
  service: "browser-logistics-shipments"
  schemaVersion: 1
  revision: number
  shipments: Shipment[]
}
