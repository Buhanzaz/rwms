import type {
  RepairEstimateCatalogRouteQueueKind,
  RepairEstimateFurnitureEquipmentReferenceDto,
} from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import type { MediaProcessingStatus } from "@/features/media/model/media"

export type RepairEstimateId = string
export type RepairEstimateLineId = string
export type RepairEstimateTaskPlanId = string
export type RepairEstimateMediaId = string

export type RepairEstimateStatus = "DRAFT" | "COMPLETED"
export type RepairEstimateLineType = "WORK" | "MATERIAL" | "UNSPECIFIED"
export type RepairEstimateCompletionMode = "AUTO" | "MANUAL"
export type RepairEstimateTaskPlanGenerationStatus =
  "PENDING_GENERATION" | "GENERATED" | "FAILED" | "UNKNOWN"
export type RepairEstimateTaskPlanKind =
  "REPAIR_WORK" | "MOVE_TO_REPAIR" | "MOVE_FROM_REPAIR"
export type RepairEstimateMediaRotationDegrees = 0 | 90 | 180 | 270

export type MaintenanceMediaReferenceDto = {
  mediaId: string
  generation: number
}

/** Decimal money value serialized at the service boundary, for example `1250.00`. */
export type MoneyDecimal = string

export type RepairEstimateCatalogLineSnapshotDto = {
  nodeId: string
  code: string
  name: string
  nodeType: "WORK" | "MATERIAL" | "OPTION"
  furnitureEquipment: RepairEstimateFurnitureEquipmentReferenceDto | null
}

export type RepairEstimateLineDto = {
  id: RepairEstimateLineId
  sourceLineKey: string
  lineType: RepairEstimateLineType
  description: string
  lineComment: string
  unit: string
  quantity: number
  unitPrice: MoneyDecimal
  lineTotal: MoneyDecimal
  catalogSnapshot: RepairEstimateCatalogLineSnapshotDto | null
  maintenanceMediaReferences?: MaintenanceMediaReferenceDto[]
}

export type RepairEstimateMediaVariantDto = {
  url: string
  storageRef: string
  mimeType: string
  width: number | null
  height: number | null
}

export type RepairEstimateMediaRefDto = {
  id: RepairEstimateMediaId
  fileName: string
  mimeType: string
  rotationDegrees: RepairEstimateMediaRotationDegrees
  variants: {
    small: RepairEstimateMediaVariantDto
    largeWebp: RepairEstimateMediaVariantDto
    original?: RepairEstimateMediaVariantDto
  }
  processingStatus?: MediaProcessingStatus
  originalAvailable?: boolean
  createdAt: string
}

export type RepairEstimateWorkflowRequestRefDto = {
  id: string
  status: "PENDING_EXTERNAL_DISPATCH"
}

export type RepairEstimateTaskPlanDto = {
  id: RepairEstimateTaskPlanId
  kind: RepairEstimateTaskPlanKind
  includedLineIds: RepairEstimateLineId[]
  primaryLineId: RepairEstimateLineId | null
  groupComment: string
  /** Canonical task-board queue id carried by the maintenance routing snapshot. */
  queueId?: string | null
  queueCode: string | null
  routeQueueKind: RepairEstimateCatalogRouteQueueKind | null
  sortOrder: number
  generationStatus: RepairEstimateTaskPlanGenerationStatus
  workflowRequestRef: RepairEstimateWorkflowRequestRefDto | null
}

/** Client-supplied plan data. Workflow/outbox references are estimate-service owned. */
export type RepairEstimateTaskPlanCommandDto = Omit<
  RepairEstimateTaskPlanDto,
  "workflowRequestRef"
>

export type RepairEstimateDto = {
  id: RepairEstimateId
  version: number
  status: RepairEstimateStatus
  warehouseId: string
  rentalItemId: string
  cabinNumber: string
  authorName: string
  sourceParty: string
  destinationText?: string | null
  /** @deprecated Read compatibility for estimates stored before destinationText. */
  destinationParty?: string | null
  dispatchDate: string | null
  comment: string
  totalAmount: MoneyDecimal
  lines: RepairEstimateLineDto[]
  media: RepairEstimateMediaRefDto[]
  /** Opaque server-owned references; preview URLs are resolved only by media-service. */
  maintenanceMediaReferences?: MaintenanceMediaReferenceDto[]
  repairId?: string | null
  deliveryState?: "PENDING" | "RETRY_PENDING" | "DELIVERED" | "QUARANTINED"
  completionMode: RepairEstimateCompletionMode | null
  movementRequired: boolean | null
  taskPlans: RepairEstimateTaskPlanDto[]
  createdAt: string
  updatedAt: string
}

export type RepairEstimateSummaryDto = Pick<
  RepairEstimateDto,
  | "id"
  | "version"
  | "status"
  | "warehouseId"
  | "rentalItemId"
  | "cabinNumber"
  | "authorName"
  | "sourceParty"
  | "destinationText"
  | "destinationParty"
  | "dispatchDate"
  | "totalAmount"
  | "createdAt"
  | "updatedAt"
>

export type RepairEstimateDraftCommand = {
  estimateId: RepairEstimateId | null
  expectedVersion: number | null
  warehouseId: string
  rentalItemId: string
  sourceParty: string
  destinationText?: string | null
  /** @deprecated Transitional command compatibility; new clients omit this field. */
  destinationParty?: string | null
  dispatchDate: string | null
  comment: string
  lines: RepairEstimateLineDto[]
  media: RepairEstimateMediaRefDto[]
  maintenanceMediaReferences?: MaintenanceMediaReferenceDto[]
}

export type CompleteRepairEstimateCommand = RepairEstimateDraftCommand & {
  completionMode: RepairEstimateCompletionMode
  movementRequired: boolean
  taskPlans: RepairEstimateTaskPlanCommandDto[]
}

export type AmendCompletedRepairEstimateCommand = Omit<
  CompleteRepairEstimateCommand,
  "estimateId" | "expectedVersion"
> & {
  estimateId: RepairEstimateId
  expectedVersion: number
  expectedLinkedRepairVersion: number | null
  reason: string
}

export type RepairEstimateListQuery = {
  warehouseId: string
  status: RepairEstimateStatus
}

export type EstimateRentalItemOptionDto = {
  id: string
  warehouseId: string
  number: string
}

export type EstimateRentalItemSearchQuery = {
  warehouseId: string
  search: string
  page: number
  size: number
}

export type EstimateRentalItemSearchPageDto = {
  items: EstimateRentalItemOptionDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type PendingEstimateMediaUpload = {
  id: string
  file: File
  previewUrl: string
  rotationDegrees: RepairEstimateMediaRotationDegrees
}

export type RepairEstimateEditorDraft = {
  estimateId: RepairEstimateId | null
  expectedVersion: number | null
  rentalItemId: string
  sourceParty: string
  destinationParty: string
  dispatchDate: string | null
  comment: string
  lines: RepairEstimateLineDto[]
  media: RepairEstimateMediaRefDto[]
  maintenanceMediaReferences?: MaintenanceMediaReferenceDto[]
  pendingUploads: PendingEstimateMediaUpload[]
}

export type CompleteRepairEstimateInput = {
  draft: RepairEstimateEditorDraft
  warehouseId: string
  completionMode: RepairEstimateCompletionMode
  movementRequired: boolean
  taskPlans: RepairEstimateTaskPlanDto[]
}

export type AmendCompletedRepairEstimateInput = CompleteRepairEstimateInput & {
  expectedTaskVersion: number | null
  reason: string
}
