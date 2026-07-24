import type {
  MoneyDecimal,
  RepairEstimateCompletionMode,
  RepairEstimateLineDto,
  RepairEstimateTaskPlanKind,
} from "@/features/repair-estimates/model/repair-estimate"
import type { RentalItemStatus } from "@/features/rental-items/model/rental-item"

export type InventoryPermission = "VIEW" | "EDIT" | "MANAGE"
export type InventorySessionStatus = "ACTIVE" | "COMPLETED" | "CANCELLED"
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

export type InventoryRepairPlanSnapshotDto = {
  id: string
  kind: RepairEstimateTaskPlanKind
  includedLineIds: string[]
  primaryLineId: string | null
  groupComment: string
  queueCode: string | null
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
  conflicts: InventoryConflictDto[]
  comment: string
  media: Array<{ mediaId: string; generation: number }>
  lines: RepairEstimateLineDto[]
  repairCompletionMode: RepairEstimateCompletionMode | null
  movementRequired: boolean
  repairPlans: InventoryRepairPlanSnapshotDto[]
  publicationStatus: InventoryFindingPublicationStatus
  publicationOperationKey: string | null
  publishedRepairTaskId: string | null
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
  findingCount: number
  inspectedCount: number
  findings: InventoryFindingDto[]
  statistics: InventoryStatisticsDto | null
  publicationStatus: InventoryPublicationStatus
}

export type InventoryCreateRentalItem = {
  number: string
  type: string
  dimensions: string
  finishing: string
  category: string
  characteristics: string[]
  linoleum: boolean
}
