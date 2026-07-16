import type { RepairEstimateCatalogRouteQueueKind } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import type {
  MediaKind,
  MediaProcessingStatus,
  MediaProvenanceDto,
} from "@/features/media/model/media"

export type RepairEstimateId = string
export type RepairEstimateLineId = string
export type RepairEstimateTaskPlanId = string
export type RepairEstimateMediaId = string

export type RepairEstimateStatus = "DRAFT" | "COMPLETED"
export type RepairEstimateLineType = "WORK" | "MATERIAL"
export type RepairEstimateCompletionMode = "AUTO" | "MANUAL"
export type RepairEstimateTaskPlanGenerationStatus =
  "PENDING_GENERATION" | "GENERATED" | "FAILED"
export type RepairEstimateTaskPlanKind =
  "REPAIR_WORK" | "MOVE_TO_REPAIR" | "MOVE_FROM_REPAIR"
export type RepairEstimateMediaRotationDegrees = 0 | 90 | 180 | 270

/** Decimal money value serialized at the service boundary, for example `1250.00`. */
export type MoneyDecimal = string

export type RepairEstimateCatalogLineSnapshotDto = {
  nodeId: string
  code: string
  name: string
  nodeType: "WORK" | "MATERIAL" | "OPTION"
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
  /** Optional while legacy browser fixtures are being migrated to media-service. */
  kind?: MediaKind
  rotationDegrees: RepairEstimateMediaRotationDegrees
  variants: {
    small: RepairEstimateMediaVariantDto
    largeWebp: RepairEstimateMediaVariantDto
    original?: RepairEstimateMediaVariantDto
  }
  processingStatus?: MediaProcessingStatus
  originalAvailable?: boolean
  provenance?: MediaProvenanceDto
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
  pendingUploads: PendingEstimateMediaUpload[]
}

/** Typed navigation seed from the target-only rental return inspection flow. */
export type LogisticsEstimateSeed = {
  type: "logistics-return-estimate-seed-v1"
  returnTaskId: string
  returnTaskVersion: number
  rentalItemId: string
  cabinNumber: string
  sourceParty: string
  dispatchDate: string
  media: RepairEstimateMediaRefDto[]
  pendingUploads: PendingEstimateMediaUpload[]
  sourceMediaIds: string[]
  /** Deterministic browser-mock lines for expected furniture missing on return. */
  replacementLines: RepairEstimateLineDto[]
  replacementWarnings: string[]
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
}
