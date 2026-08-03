import type {
  RepairEstimateCabinCharacteristicReferenceDto,
  RepairEstimateCatalogRouteQueueKind,
  RepairEstimateFurnitureEquipmentReferenceDto,
} from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import type { MediaProcessingStatus } from "@/features/media/model/media"

export type RepairEstimateId = string
export type RepairEstimateLineId = string
export type RepairEstimateTaskPlanId = string
export type RepairEstimateMediaId = string

export type RepairEstimateStatus = "DRAFT" | "COMPLETED"
export type RepairEstimateLineType = "WORK" | "MATERIAL"
export type RepairEstimateCompletionMode = "AUTO" | "MANUAL"
export type LogisticsPlanningMode = "AUTO" | "FIXED_DATE"
export type RepairPriority = 1 | 2 | 3 | 4 | 5
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
  name: string
  nodeType: "WORK" | "MATERIAL" | "OPTION"
  furnitureEquipment: RepairEstimateFurnitureEquipmentReferenceDto | null
  characteristic: RepairEstimateCabinCharacteristicReferenceDto | null
}

/**
 * Panel-only stage selection for a custom line. The maintenance contract
 * persists this through the containing repair-work stage rather than the line
 * itself.
 */
export type RepairEstimateCustomQueueBindingDto = {
  queueId: string
  queueName: string
  queueKind: "REPAIR" | "HOLDING"
}

export type RepairEstimateLineDto = {
  id: RepairEstimateLineId
  sourceLineKey: string
  lineType: RepairEstimateLineType
  description: string
  lineComment: string
  /** An absent catalog unit is normalized for the panel; custom lines always have one. */
  unit: string
  quantity: number
  /**
   * Planned execution time in whole minutes. New custom WORK lines must set a
   * positive value; catalog lines retain the duration returned by maintenance.
   */
  normativeMinutes?: number
  unitPrice: MoneyDecimal
  lineTotal: MoneyDecimal
  catalogSnapshot: RepairEstimateCatalogLineSnapshotDto | null
  /**
   * Never serialized as a line field. Kept nullable for older panel callers
   * that do not own custom repair work; adapters reconstruct it from stages.
   */
  customQueueBinding?: RepairEstimateCustomQueueBindingDto | null
  maintenanceMediaReferences?: MaintenanceMediaReferenceDto[]
  rework?: {
    disposition: "ADDED" | "REPEAT"
    sourceRepairId: string | null
    sourceLineId: string | null
    lineageRootLineId: string
  } | null
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
  queueName: string | null
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
  dispatchDate: string | null
  comment: string
  totalAmount: MoneyDecimal
  lines: RepairEstimateLineDto[]
  media: RepairEstimateMediaRefDto[]
  /** Opaque server-owned references; preview URLs are resolved only by media-service. */
  maintenanceMediaReferences?: MaintenanceMediaReferenceDto[]
  coverMediaId?: string | null
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
  dispatchDate: string | null
  comment: string
  lines: RepairEstimateLineDto[]
  /** Explicit routing stages used to persist custom work-line queue bindings. */
  taskPlans?: RepairEstimateTaskPlanCommandDto[]
  media: RepairEstimateMediaRefDto[]
  maintenanceMediaReferences?: MaintenanceMediaReferenceDto[]
  coverMediaId?: string | null
}

export type CompleteRepairEstimateCommand = RepairEstimateDraftCommand & {
  completionMode: RepairEstimateCompletionMode
  movementRequired: boolean
  logisticsPlanningMode: LogisticsPlanningMode
  logisticsScheduledDate: string | null
  taskPlans: RepairEstimateTaskPlanCommandDto[]
  priority: RepairPriority
}

export type AmendCompletedRepairEstimateCommand = Omit<
  CompleteRepairEstimateCommand,
  "estimateId" | "expectedVersion" | "priority"
> & {
  estimateId: RepairEstimateId
  expectedVersion: number
  expectedLinkedRepairVersion: number | null
  reason: string
}

export type RepairEstimateListQuery = {
  warehouseId: string
  status?: RepairEstimateStatus
}

export type EstimateRentalItemOptionDto = {
  id: string
  warehouseId: string
  number: string
  /** Filled from the public return document for an item returned from rent. */
  counterparty?: string | null
  /** Local calendar date of the public return document. */
  arrivalDate?: string | null
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
  dispatchDate: string | null
  comment: string
  lines: RepairEstimateLineDto[]
  media: RepairEstimateMediaRefDto[]
  maintenanceMediaReferences?: MaintenanceMediaReferenceDto[]
  coverMediaId?: string | null
  pendingUploads: PendingEstimateMediaUpload[]
}

export type CompleteRepairEstimateInput = {
  draft: RepairEstimateEditorDraft
  warehouseId: string
  completionMode: RepairEstimateCompletionMode
  movementRequired: boolean
  logisticsPlanningMode: LogisticsPlanningMode
  logisticsScheduledDate: string | null
  taskPlans: RepairEstimateTaskPlanDto[]
  priority: RepairPriority
}

export type AmendCompletedRepairEstimateInput = Omit<
  CompleteRepairEstimateInput,
  "priority"
> & {
  expectedTaskVersion: number | null
  reason: string
}
