import type { RepairEstimateCatalogRouteQueueKind } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import type {
  MoneyDecimal,
  RepairEstimateCompletionMode,
  RepairEstimateLineDto,
  RepairEstimateMediaRefDto,
  RepairEstimateTaskPlanKind,
} from "@/features/repair-estimates/model/repair-estimate"
import type { RentalItemStatus } from "@/features/rental-items/model/rental-item"

export type InventoryPermission = "VIEW" | "EDIT" | "MANAGE"
export type InventorySessionStatus = "ACTIVE" | "COMPLETED"
export type InventoryFindingOrigin =
  "EXPECTED" | "ADDED_NEW" | "ADDED_USED" | "UNEXPECTED_EXISTING"
export type InventoryInspectionStatus =
  "NOT_INSPECTED" | "READY" | "WORK_STAGED"
export type InventoryReconciliationStatus = "MATCHED" | "MISSING"
export type InventoryConflictCode =
  | "WAREHOUSE_CHANGED"
  | "STATUS_CHANGED"
  | "TENANT_CHANGED"
  | "RENTED"
  | "WRITTEN_OFF"
  | "OTHER_WAREHOUSE"
  | "ADDED_AFTER_START"
  | "RENTAL_ITEM_MISSING"
export type InventoryFindingPublicationStatus =
  "NOT_REQUIRED" | "READY" | "PUBLISHING" | "PUBLISHED" | "BLOCKED" | "FAILED"
export type InventoryPublicationStatus =
  "NOT_REQUESTED" | "PENDING" | "PARTIAL" | "PUBLISHED" | "FAILED"

export type InventoryActorSnapshot = {
  id: string
  displayName: string
  permissions: InventoryPermission[]
  /** null means global warehouse access. */
  authorizedWarehouseIds: string[] | null
}

export type InventoryWarehouseSnapshot = {
  id: string
  code: string
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
}

export type InventoryConflictDto = {
  code: InventoryConflictCode
  message: string
  expected: string | null
  actual: string | null
}

/** Immutable routing snapshot. It is used for late publication without catalog recalculation. */
export type InventoryRepairPlanSnapshotDto = {
  id: string
  kind: RepairEstimateTaskPlanKind
  includedLineIds: string[]
  primaryLineId: string | null
  groupComment: string
  queueCode: string | null
  routeQueueKind: RepairEstimateCatalogRouteQueueKind | null
  sortOrder: number
  plannedDurationMinutes: number | null
  photoRequired: boolean
}

export type InventoryFindingDto = {
  id: string
  rentalItemId: string | null
  canonicalNumber: string
  cabinNumber: string
  origin: InventoryFindingOrigin
  inspectionStatus: InventoryInspectionStatus
  reconciliationStatus: InventoryReconciliationStatus
  expectedSnapshot: InventoryRentalItemSnapshot | null
  currentSnapshot: InventoryRentalItemSnapshot | null
  conflicts: InventoryConflictDto[]
  comment: string
  media: RepairEstimateMediaRefDto[]
  lines: RepairEstimateLineDto[]
  /** Immutable workflow choice made while saving the inspection. */
  repairCompletionMode: RepairEstimateCompletionMode | null
  /** Immutable decision to add both movement stages to the published repair. */
  movementRequired: boolean
  repairPlans: InventoryRepairPlanSnapshotDto[]
  publicationStatus: InventoryFindingPublicationStatus
  publicationOperationKey: string | null
  publishedRepairTaskId: string | null
  publicationError: string | null
  inspectedAt: string | null
  inspectedBy: InventoryActorSnapshot | null
}

export type InventoryAggregateLineDto = {
  key: string
  lineType: RepairEstimateLineDto["lineType"]
  description: string
  catalogNodeId: string | null
  unit: string
  unitPrice: MoneyDecimal
  quantity: number
  total: MoneyDecimal
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
  expectedItems: InventoryRentalItemSnapshot[]
  findings: InventoryFindingDto[]
  statistics: InventoryStatisticsDto | null
  publicationStatus: InventoryPublicationStatus
}

export type InventoryStartCommand = {
  warehouse: InventoryWarehouseSnapshot
  actor: InventoryActorSnapshot
  businessDate: string
  expectedItems: InventoryRentalItemSnapshot[]
}

export type InventoryFindingInspectionCommand = {
  inventoryId: string
  expectedVersion: number
  actor: InventoryActorSnapshot
  findingId: string
  comment: string
  media: RepairEstimateMediaRefDto[]
  lines: RepairEstimateLineDto[]
  repairCompletionMode?: RepairEstimateCompletionMode | null
  movementRequired?: boolean
  repairPlans: InventoryRepairPlanSnapshotDto[]
  currentSnapshot: InventoryRentalItemSnapshot | null
  conflicts: InventoryConflictDto[]
}

export type InventoryCompleteCommand = {
  inventoryId: string
  expectedVersion: number
  actor: InventoryActorSnapshot
  findings: InventoryFindingDto[]
  statistics: InventoryStatisticsDto
}

export type InventoryFindingPublicationResult = {
  inventoryId: string
  inventoryVersion: number
  findingId: string
  status: InventoryFindingPublicationStatus
  repairTaskId: string | null
  error: string | null
}
