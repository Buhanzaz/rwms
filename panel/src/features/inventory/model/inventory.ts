import type {
  MoneyDecimal,
  LogisticsPlanningMode,
  RepairEstimateCompletionMode,
  RepairEstimateLineDto,
  RepairEstimateTaskPlanKind,
  RepairPriority,
} from "@/features/repair-estimates/model/repair-estimate"
import type { RentalItemStatus } from "@/features/rental-items/model/rental-item"
import type {
  InventoryAssetOutcomeStatus,
  InventoryObservation,
} from "@/features/inventory/model/inventory-service"

export type InventoryPermission = "VIEW" | "EDIT" | "MANAGE"
export type InventorySessionStatus = "ACTIVE" | "COMPLETED" | "CANCELLED"
export type InventoryReviewStage = "CABINS" | "FURNITURE"
export type InventoryFurnitureReconciliationState =
  | "NOT_REQUIRED"
  | "READY"
  | "PENDING"
  | "SUCCEEDED"
  | "TRANSIENT_FAILED"
  | "BLOCKED"
export type InventoryFindingOrigin =
  "EXPECTED" | "ADDED_NEW" | "ADDED_USED" | "UNEXPECTED_EXISTING"
export type InventoryInspectionStatus =
  "NOT_INSPECTED" | "READY" | "WORK_STAGED"
export type InventoryReconciliationStatus = "MATCHED" | "MISSING" | "CONFLICT"
export type InventoryConflictCode =
  | "WAREHOUSE_CHANGED"
  | "STATUS_CHANGED"
  | "TENANT_CHANGED"
  | "RENTED"
  | "WRITTEN_OFF"
  | "OTHER_WAREHOUSE"
  | "ADDED_AFTER_START"
  | "RENTAL_ITEM_MISSING"
  | "CROSS_WAREHOUSE_CONFLICT"
  | "EXCLUDED_STATUS_CONFLICT"
  | "ASSET_CHANGED"
  | "NUMBER_CHANGED"
  | "PASSPORT_CHANGED"
  | "CONTENTS_CHANGED"
  | "REPAIRS_CHANGED"
  | "MEDIA_NOT_READY"
  | "PLAN_STALE"
  | "MUTATION_IN_FLIGHT"
  | "SERVER_CONFLICT"
export type InventoryFindingPublicationStatus =
  "NOT_REQUIRED" | "READY" | "PUBLISHING" | "PUBLISHED" | "BLOCKED" | "FAILED"
export type InventoryPublicationStatus =
  "NOT_REQUESTED" | "PENDING" | "PARTIAL" | "PUBLISHED" | "FAILED"

export type InventoryActorSnapshot = {
  id: string
  displayName: string
  permissions: InventoryPermission[]
  authorizedWarehouseIds: string[] | null
}

export type InventoryWarehouseSnapshot = {
  id: string
  name: string
  timeZone: string
}

export type InventoryRentalItemSnapshot = {
  rentalItemId: string
  number: string
  canonicalNumber: string
  warehouseId: string
  status: RentalItemStatus
  tenant: string | null
  passportSnapshot: Record<string, unknown>
  contentsSnapshot: Record<string, unknown> | unknown[]
  repairsSnapshot: InventoryRepairRegistryFact[]
}

export type InventoryRepairRegistryFact = {
  repairId: string
  rootRepairId: string
  origin: string
  kind: string
  executionState: string
  acceptanceState: string
  planFingerprintSha256: string
}

export type InventoryConflictResolutionDto = {
  strategy: "ACCEPT_REGISTRY" | "KEEP_INSPECTION"
  reason: string | null
  resolvedAt: string
}

export type InventoryConflictDto = {
  code: InventoryConflictCode
  message: string
  expected: string | null
  actual: string | null
}

export type InventoryRepairPlanSnapshotDto = {
  id: string
  kind: RepairEstimateTaskPlanKind
  includedLineIds: string[]
  primaryLineId: string | null
  groupComment: string
  queueId: string | null
  routingCatalogNodeId: string | null
  queueName: string | null
  routeQueueKind: "REPAIR" | "MOVEMENT" | "HOLDING" | null
  sortOrder: number
  plannedDurationMinutes: number | null
  photoRequired: boolean
}

export type InventoryFindingDto = {
  id: string
  version: number
  rentalItemId: string | null
  canonicalNumber: string
  cabinNumber: string
  origin: InventoryFindingOrigin
  inspectionStatus: InventoryInspectionStatus
  reconciliationStatus: InventoryReconciliationStatus
  expectedSnapshot: InventoryRentalItemSnapshot | null
  currentSnapshot: InventoryRentalItemSnapshot | null
  inspectionBaseline: InventoryRentalItemSnapshot | null
  conflictResolution: InventoryConflictResolutionDto | null
  conflicts: InventoryConflictDto[]
  comment: string
  /** Evidence recorded during the inventory inspection; it does not mutate the asset passport. */
  passportObservation: InventoryObservation
  /** Furniture observed during the inspection; reconciled only after the cabin review is complete. */
  equipmentObservation: InventoryObservation
  media: Array<{ mediaId: string; generation: number }>
  coverMediaId: string | null
  inspectionSource: "INVENTORY" | null
  lines: RepairEstimateLineDto[]
  repairCompletionMode: RepairEstimateCompletionMode | null
  repairPriority: RepairPriority
  movementToRepair: boolean
  /** Always set by the current adapter; optional only for legacy panel fixtures. */
  forceCapitalRepair?: boolean
  logisticsPlanningMode: LogisticsPlanningMode
  logisticsScheduledDate: string | null
  repairPlans: InventoryRepairPlanSnapshotDto[]
  publicationStatus: InventoryFindingPublicationStatus
  publicationOperationKey: string | null
  publishedRepairTaskId: string | null
  /** Owner-issued outcome status; null only when this finding has no outcome operation. */
  desiredAssetStatus: InventoryAssetOutcomeStatus | null
  publicationError: string | null
}

export type InventoryAggregateLineDto = {
  key: string
  lineType: "WORK" | "MATERIAL"
  description: string
  catalogNodeId: string | null
  unit: string
  unitPrice: MoneyDecimal
  quantity: number
  total: MoneyDecimal
}

export type InventoryMembershipMovementDto = {
  id: string
  type: "DEPARTED" | "TRANSFERRED" | "ARRIVED"
  assetId: string
  displayCanonicalNumber: string
  origin: InventoryFindingOrigin
  fromWarehouseId: string | null
  toWarehouseId: string | null
  status: RentalItemStatus | null
  tenantSnapshot: string | null
  occurredAt: string
}

export type InventoryStatisticsDto = {
  durationSeconds: number
  expectedCount: number
  inspectedCount: number
  missingCount: number
  readyCount: number
  withWorkCount: number
  addedCount: number
  conflictCount: number
  workLineCount: number
  materialLineCount: number
  plannedDurationMinutes: number
  workTotal: MoneyDecimal
  materialTotal: MoneyDecimal
  grandTotal: MoneyDecimal
  aggregates: InventoryAggregateLineDto[]
}

export type InventorySessionDto = {
  id: string
  version: number
  warehouseId: string
  status: InventorySessionStatus
  warehouse: InventoryWarehouseSnapshot
  author: InventoryActorSnapshot
  businessDate: string
  startedAt: string
  completedAt: string | null
  cancellation: { reason: string; cancelledAt: string } | null
  findingCount: number
  inspectedCount: number
  findings: InventoryFindingDto[]
  membershipMovements: InventoryMembershipMovementDto[]
  statistics: InventoryStatisticsDto | null
  publicationStatus: InventoryPublicationStatus
  reviewStage: InventoryReviewStage
  furnitureReconciliationState: InventoryFurnitureReconciliationState
}

export type InventoryFurnitureReviewCabinDto = {
  findingId: string
  assetId: string
  cabinNumber: string
  status: string
  currentQuantity: number
  observedQuantity: number
}

export type InventoryFurnitureReviewItemDto = {
  equipmentId: string
  catalogVersion: number
  equipmentName: string
  currentStockQuantity: number
  observedStockQuantity: number
  cabins: InventoryFurnitureReviewCabinDto[]
}

export type InventoryFurnitureReviewDto = {
  inventoryId: string
  sessionRevision: number
  stage: InventoryReviewStage
  assetSnapshotSha256: string
  reviewSha256: string | null
  confirmed: boolean
  items: InventoryFurnitureReviewItemDto[]
}

export type InventoryCreateRentalItem = {
  number: string
  rentalTypeId: string
  dimensionId: string
  finishingId: string
  category: string
  characteristicIds: string[]
  linoleum: boolean
}
