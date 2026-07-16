import type { InventoryInspectionStatus } from "@/features/inventory/model/inventory"
import type { RepairEstimateStatus } from "@/features/repair-estimates/model/repair-estimate"
import type {
  RepairTaskAcceptanceStatus,
  RepairTaskStatus,
} from "@/features/repair-tasks/model/repair-task"
import type {
  RentalItemDto,
  RentalItemStatus,
} from "@/features/rental-items/model/rental-item"

export type CabinActorSnapshot = {
  id: string | null
  displayName: string
}

export type CabinPhotoStage =
  "BEFORE" | "WORK" | "AFTER" | "ACCEPTANCE" | "GENERAL"

export type CabinActivitySourceType =
  | "RENTAL_ITEM"
  | "INVENTORY"
  | "ESTIMATE"
  | "REPAIR"
  | "SHIPMENT"
  | "RETURN_RECEIPT"
  | "WAREHOUSE_TRANSFER"
  | "ACCOUNTING_CORRECTION"
  | "CONTENTS_TRANSFER"
  | "MANUAL"

export type CabinActivityType =
  | "INSPECTION_COMPLETED"
  | "ESTIMATE_CREATED"
  | "ESTIMATE_COMPLETED"
  | "REPAIR_CREATED"
  | "REPAIR_STAGE_COMPLETED"
  | "REPAIR_COMPLETED"
  | "REPAIR_ACCEPTED"
  | "REPAIR_WRITTEN_OFF"
  | "SHIPPED"
  | "RETURNED"
  | "RETURN_INTAKE_REGISTERED"
  | "RETURN_CONFLICT_REGISTERED"
  | "RETURN_PASSPORT_CHANGED"
  | "RETURN_EXPECTED_CONTENTS_CORRECTED"
  | "RETURN_CONFLICT_RESOLVED"
  | "RETURN_FURNITURE_DISPOSITION_RECORDED"
  | "SHIPMENT_CREATED"
  | "SHIPMENT_UPDATED"
  | "SHIPMENT_TASK_DISPATCHED"
  | "SHIPMENT_PREPARED"
  | "SHIPMENT_PREPARATION_CONFLICT"
  | "SHIPMENT_CANCELLED"
  | "WAREHOUSE_TRANSFER_CREATED"
  | "WAREHOUSE_TRANSFER_TASK_REGISTERED"
  | "WAREHOUSE_TRANSFER_DEPARTED"
  | "WAREHOUSE_TRANSFER_ARRIVAL_CONFLICT"
  | "WAREHOUSE_TRANSFER_RECEIVED"
  | "WAREHOUSE_TRANSFER_CANCELLED"
  | "WAREHOUSE_CORRECTION_CREATED"
  | "WAREHOUSE_CORRECTION_APPROVED"
  | "WAREHOUSE_CORRECTION_APPLIED"
  | "WAREHOUSE_CORRECTION_REJECTED"
  | "PHOTO_ADDED"
  | "STATUS_CHANGED"
  | "GENERAL_COMMENT_UPDATED"
  | "COMMENT_ADDED"
  | "CONTENTS_TRANSFERRED"

export type CabinPhotoProcessingStatus =
  "UPLOADING" | "PROCESSING" | "READY" | "FAILED"

export type CabinPhotoVariantDto = {
  url: string
  width: number | null
  height: number | null
  mimeType: string | null
}

export type CabinPhotoDto = {
  id: string
  occurredAt: string | null
  order: number
  stage: CabinPhotoStage
  processingStatus: CabinPhotoProcessingStatus
  variants: {
    thumb: CabinPhotoVariantDto
    preview: CabinPhotoVariantDto
  }
  originalAvailable: boolean
  provenance: {
    sourceType: CabinActivitySourceType
    sourceId: string
    sourceLabel: string
  }
}

export type CabinPhotoGroupDto = {
  id: string
  rentalItemId: string
  activityId: string | null
  occurredAt: string | null
  actor: CabinActorSnapshot | null
  sourceType: CabinActivitySourceType
  sourceId: string
  sourceLabel: string
  stage: CabinPhotoStage
  photos: CabinPhotoDto[]
}

export type CabinActivityLinkDto = {
  kind:
    | "INVENTORY"
    | "ESTIMATE"
    | "REPAIR"
    | "SHIPMENT"
    | "RETURN_RECEIPT"
    | "WAREHOUSE_TRANSFER"
  label: string
  href: string
}

/** Append-only proven event. Browser adapters must never manufacture events. */
export type CabinActivityDto = {
  id: string
  rentalItemId: string
  occurredAt: string
  actor: CabinActorSnapshot | null
  type: CabinActivityType
  sourceType: CabinActivitySourceType
  sourceId: string
  sourceLabel: string
  comment: string | null
  statusTransition: {
    from: RentalItemStatus
    to: RentalItemStatus
    reason: string
  } | null
  parentActivityId: string | null
  photoGroupId: string | null
  links: CabinActivityLinkDto[]
}

export type CabinInspectionDto = {
  id: string
  sourceType: "INVENTORY" | "RETURN_RECEIPT"
  inventoryId: string | null
  returnReceiptId: string | null
  findingId: string
  occurredAt: string
  actor: CabinActorSnapshot | null
  status: InventoryInspectionStatus
  comment: string | null
  photoGroupId: string | null
  publishedRepairTaskId: string | null
  links: CabinActivityLinkDto[]
}

export type CabinEstimateDto = {
  id: string
  version: number
  status: RepairEstimateStatus
  occurredAt: string
  updatedAt: string
  actor: CabinActorSnapshot | null
  comment: string | null
  totalAmount: string
  photoGroupId: string | null
  link: CabinActivityLinkDto
}

export type CabinRepairDto = {
  id: string
  version: number
  status: RepairTaskStatus
  acceptanceStatus: RepairTaskAcceptanceStatus
  occurredAt: string
  completedAt: string | null
  actor: CabinActorSnapshot | null
  reason: string
  comment: string | null
  beforePhotoGroupId: string | null
  resultPhotoGroupIds: string[]
  link: CabinActivityLinkDto
}

export type CabinRentalMovementDto = {
  id: string
  direction: "OUTBOUND" | "INBOUND"
  occurredAt: string
  party: string
  driverName: string | null
  actor: CabinActorSnapshot | null
  contents: Array<{ name: string; quantity: number }>
  photoGroupId: string | null
  link: CabinActivityLinkDto
}

export type CabinCommentDto = {
  id: string
  occurredAt: string
  actor: CabinActorSnapshot | null
  text: string
  sourceType: CabinActivitySourceType
  sourceId: string
  sourceLabel: string
  link: CabinActivityLinkDto | null
  editable: false
}

export type RentalItemRepairAction =
  | { type: "CREATE_ESTIMATE"; disabledReason: null }
  | { type: "CREATE_REPAIR"; disabledReason: null }
  | { type: "NONE"; disabledReason: string }

export type RentalItemDossierDto = {
  rentalItem: RentalItemDto
  overview: {
    lastInspectionAt: string | null
    activeRepairCount: number
    latestEstimateId: string | null
    latestRepairId: string | null
  }
  repairAction: RentalItemRepairAction
  activities: CabinActivityDto[]
  photoGroups: CabinPhotoGroupDto[]
  inspections: CabinInspectionDto[]
  estimates: CabinEstimateDto[]
  repairs: CabinRepairDto[]
  shipments: CabinRentalMovementDto[]
  comments: CabinCommentDto[]
  reservations: []
  returns: []
}

export type CabinOriginalPhotoContext = {
  kind: "ESTIMATE" | "INVENTORY" | "REPAIR"
  warehouseId: string
  documentId: string
  stageId?: string
}

export type AddCabinPhotoGroupCommand = {
  rentalItemId: string
  warehouseId: string
  expectedVersion: number
  actor: CabinActorSnapshot
  uploads: Array<{
    file: File
    rotationDegrees: 0 | 90 | 180 | 270
  }>
  comment?: string
}

export type AddCabinCommentCommand = {
  rentalItemId: string
  warehouseId: string
  actor: CabinActorSnapshot
  text: string
}

export type UpdateCabinStatusCommand = {
  rentalItemId: string
  warehouseId: string
  expectedVersion: number
  actor: CabinActorSnapshot
  status: "FREE" | "WAREHOUSE" | "OWN_NEEDS" | "RESERVED" | "USED_SALE"
  reason: string
}

export type UpdateCabinGeneralCommentCommand = {
  rentalItemId: string
  warehouseId: string
  expectedVersion: number
  actor: CabinActorSnapshot
  comment: string
}

export type RentalItemEstimateNavigationSeed = {
  type: "rental-item-estimate-seed-v1"
  warehouseId: string
  rentalItemId: string
  cabinNumber: string
}

export type RentalItemRepairNavigationSeed = {
  type: "rental-item-repair-seed-v1"
  warehouseId: string
  rentalItemId: string
  cabinNumber: string
}

export type RentalItemDossierNavigationState = {
  rentalItemSeed?:
    RentalItemEstimateNavigationSeed | RentalItemRepairNavigationSeed
}
