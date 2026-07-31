export type InventorySessionLifecycle = "ACTIVE" | "COMPLETED" | "CANCELLED"
export type InventoryFindingOrigin =
  "EXPECTED" | "ADDED_NEW" | "ADDED_USED" | "UNEXPECTED_EXISTING"
export type InventoryInspectionState = "NOT_INSPECTED" | "READY" | "WORK_STAGED"
export type InventoryReconciliationState = "MATCHED" | "MISSING" | "CONFLICT"
export type InventoryPublicationState =
  | "NOT_REQUIRED"
  | "READY"
  | "PENDING"
  | "SUCCEEDED"
  | "TRANSIENT_FAILED"
  | "BLOCKED"
  | "CLOSED_BLOCKED"
export type InventoryAggregatePublicationState =
  "NOT_REQUESTED" | "PENDING" | "PARTIAL" | "SUCCEEDED" | "BLOCKED"

export type InventoryPageMetadata = {
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type InventorySessionSummary = {
  id: string
  sessionRevision: number
  warehouseId: string
  warehouseVersion: number
  warehouseTimeZone: string
  author: {
    id: string
    displayName: string
  }
  businessDate: string
  lifecycle: InventorySessionLifecycle
  expectedCount: number
  findingCount: number
  inspectedCount: number
  startedAt: string
  terminalAt: string | null
  publicationState: InventoryAggregatePublicationState
}

export type InventoryObservation =
  | { presence: "ABSENT"; value: null }
  | { presence: "EXPLICIT_EMPTY"; value: Record<string, never> | [] }
  | { presence: "PRESENT"; value: Record<string, unknown> | unknown[] }

export type InventoryMediaReference = {
  mediaId: string
  generation: number
}

export type InventoryExpectedSnapshot = {
  assetId: string
  assetVersion: number
  warehouseId: string
  status: string
  displayCanonicalNumber: string
  tenantSnapshot: string | null
  passportSnapshot: Record<string, unknown>
  contentsSnapshot: Record<string, unknown> | unknown[]
}

export type InventoryFrozenPlanLine = {
  id: string
  sourceKind: "CATALOG" | "MANUAL"
  lineType: "WORK" | "MATERIAL"
  catalogVersionId: string | null
  catalogNodeId: string | null
  description: string
  normalizedDescription: string | null
  unit: string
  quantity: string
  unitPriceMinor: number
  normativeMinutes: string
  groupComment: string | null
}

export type InventoryFrozenPlanStage = {
  id: string
  order: number
  catalogNodeId: string
  catalogNodeName: string
  kind: "REPAIR_WORK" | "MOVE_TO_REPAIR" | "MOVE_FROM_REPAIR"
  routingQueueId: string
  routingQueueName: string
  routingQueueType: string
  movementRequired: boolean
  photoRequired: boolean
  normativeDurationMinutes: number
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

export type InventoryCurrentSnapshot = {
  assetId: string
  assetVersion: number
  warehouseId: string
  status: string
  displayCanonicalNumber: string
  tenantSnapshot: string | null
  passportSnapshot: Record<string, unknown>
  contentsSnapshot: Record<string, unknown> | unknown[]
  repairsSnapshot: InventoryRepairRegistryFact[]
}

export type InventoryMembershipMovement = {
  id: string
  type: "DEPARTED" | "TRANSFERRED" | "ARRIVED"
  assetId: string
  displayCanonicalNumber: string
  origin: InventoryFindingOrigin
  fromWarehouseId: string | null
  toWarehouseId: string | null
  status: string | null
  tenantSnapshot: string | null
  occurredAt: string
}

export type InventoryConflict = {
  code:
    | "WAREHOUSE_CHANGED"
    | "STATUS_CHANGED"
    | "TENANT_CHANGED"
    | "RENTED"
    | "WRITTEN_OFF"
    | "OTHER_WAREHOUSE"
    | "ADDED_AFTER_START"
    | "RENTAL_ITEM_MISSING"
    | "ASSET_CHANGED"
    | "NUMBER_CHANGED"
    | "PASSPORT_CHANGED"
    | "CONTENTS_CHANGED"
    | "REPAIRS_CHANGED"
  message: string
  expected: string | null
  actual: string | null
}

export type InventoryConflictResolution = {
  strategy: "ACCEPT_REGISTRY" | "KEEP_INSPECTION"
  reason: string | null
  resolvedAt: string
}

export type InventoryFrozenPlan = {
  mode: "AUTO" | "MANUAL"
  priority?: 1 | 2 | 3 | 4 | 5
  coverMediaId?: string | null
  logisticsPlanningMode: LogisticsPlanningMode
  logisticsScheduledDate: string | null
  catalogVersionId: string
  fingerprintSha256: string
  lines: InventoryFrozenPlanLine[]
  stages: InventoryFrozenPlanStage[]
}

export type InventoryPublicationIntent = {
  id: string
  inventoryId: string
  findingId: string
  publicationRevision: number
  state: InventoryPublicationState
  sourceRevision: number
  attemptCount: number
  maintenanceRepairId: string | null
  failureCode: string | null
}

export type InventoryFinding = {
  id: string
  inventoryId: string
  findingRevision: number
  origin: InventoryFindingOrigin
  inspection: InventoryInspectionState
  reconciliation: InventoryReconciliationState
  assetId: string | null
  assetVersion: number | null
  displayCanonicalNumber: string
  identityMatchKey: string
  passportObservation: InventoryObservation
  equipmentObservation: InventoryObservation
  mutationState:
    "IDLE" | "SOURCE_CREATE_PENDING" | "SOURCE_CREATED" | "PLAN_RESOLVE_PENDING"
  planFingerprintSha256: string | null
  comment: string
  expectedSnapshot: InventoryExpectedSnapshot | null
  currentSnapshot: InventoryCurrentSnapshot | null
  inspectionBaseline: InventoryCurrentSnapshot | null
  conflictResolution: InventoryConflictResolution | null
  conflicts: InventoryConflict[]
  frozenPlan: InventoryFrozenPlan | null
  media: InventoryMediaReference[]
  coverMediaId?: string | null
  inspectionSource?: "INVENTORY" | null
  publication: InventoryPublicationIntent | null
}

export type InventoryStatisticsLine = {
  aggregationKind: "CATALOG" | "MANUAL"
  catalogVersionId: string | null
  catalogNodeId: string | null
  normalizedDescription: string | null
  type: "WORK" | "MATERIAL"
  unit: string
  unitPriceMinor: number
  quantity: string
  rowTotalMinor: number
}

export type InventoryFrozenStatistics = {
  expectedCount: number
  inspectedCount: number
  missingCount: number
  readyCount: number
  withWorkCount: number
  addedCount: number
  unexpectedExistingCount: number
  conflictCount: number
  workLineCount: number
  materialLineCount: number
  workTotalMinor: number
  materialTotalMinor: number
  grandTotalMinor: number
  roundingAdjustmentMinor: number
  normativeMinutes: string
  durationSeconds: number
  aggregateLines: InventoryStatisticsLine[]
}

export type InventorySessionDetail = InventorySessionSummary & {
  statistics: InventoryFrozenStatistics | null
  cancellation: { reason: string; cancelledAt: string } | null
  membershipMovements: InventoryMembershipMovement[]
}

export type InventorySessionView = InventorySessionDetail & {
  findings: InventoryFinding[]
}

export type InventorySessionPage = {
  content: InventorySessionSummary[]
  page: InventoryPageMetadata
}

export type InventoryFindingPage = {
  content: InventoryFinding[]
  page: InventoryPageMetadata
}

export type InventoryNumberResolution = {
  displayCanonicalNumber: string
  identityMatchKey: string
  outcome:
    | "MATCHED"
    | "CROSS_WAREHOUSE_CONFLICT"
    | "EXCLUDED_STATUS_CONFLICT"
    | "MISSING_CONFLICT"
    | "NOT_FOUND"
  finding: InventoryFinding | null
}

export type InventoryRevisionExpectation = {
  findingId: string
  expectedFindingRevision: number
}

export type InventoryCompletionPreview = {
  inventoryId: string
  sessionRevision: number
  findingRevisions: InventoryRevisionExpectation[]
  validationSha256: string
  validatedAt: string
  acknowledgementSha256: string
  statistics: InventoryFrozenStatistics
  risks: Array<{
    findingId: string
    code:
      | "NOT_INSPECTED"
      | "MISSING"
      | "CONFLICT"
      | "ASSET_CHANGED"
      | "MEDIA_NOT_READY"
      | "PLAN_STALE"
      | "MUTATION_IN_FLIGHT"
  }>
  validatedFindings: Array<{
    findingId: string
    currentSnapshot: InventoryCurrentSnapshot | null
    conflicts: InventoryConflict[]
  }>
}

export type InventoryCatalogPlanLine = {
  aggregationKind: "CATALOG"
  catalogNodeId: string
  description: null
  type: null
  unit: null
  quantity: string
  unitPriceMinor: null
  normativeMinutes: null
  groupComment: string | null
  mediaReferences: InventoryMediaReference[]
}

export type InventoryManualPlanLine = {
  aggregationKind: "MANUAL"
  catalogNodeId: null
  description: string
  type: "WORK" | "MATERIAL"
  unit: string
  quantity: string
  unitPriceMinor: number
  normativeMinutes: string
  groupComment: string | null
  mediaReferences: InventoryMediaReference[]
}

export type InventoryPlanStageSelection = {
  catalogNodeId: string
  kind: "REPAIR_WORK" | "MOVE_TO_REPAIR" | "MOVE_FROM_REPAIR"
  order: number
}

export type InventoryPlanSelection =
  | null
  | {
      mode: "AUTO"
      priority: 1 | 2 | 3 | 4 | 5
      coverMediaId: string | null
      logisticsPlanningMode: LogisticsPlanningMode
      logisticsScheduledDate: string | null
      lines: InventoryCatalogPlanLine[]
      stages: InventoryPlanStageSelection[]
    }
  | {
      mode: "MANUAL"
      priority: 1 | 2 | 3 | 4 | 5
      coverMediaId: string | null
      logisticsPlanningMode: LogisticsPlanningMode
      logisticsScheduledDate: string | null
      lines: Array<InventoryCatalogPlanLine | InventoryManualPlanLine>
      stages: InventoryPlanStageSelection[]
    }

export type InventoryPublicationBatch = {
  inventoryId: string
  aggregateState: InventoryAggregatePublicationState
  intents: InventoryPublicationIntent[]
}

export type InventoryCreateIntent = {
  findingId: string
  idempotencyKey: string
  number: string
}

export type InventoryPublicationReconcileEvidence = {
  reconcileReason: string
  currentPreconditionSha256: string
}

export type InventoryCommandIdentity = {
  idempotencyKey: string
}

export type InventoryStatisticsSummary = {
  sessionCount: number
  statistics: InventoryFrozenStatistics
}

export type InventorySessionStatistics = {
  inventoryId: string
  warehouseId: string
  businessDate: string
  startedAt: string
  completedAt: string
  statistics: InventoryFrozenStatistics
}

export type InventoryStatisticsPage = {
  content: InventorySessionStatistics[]
  page: InventoryPageMetadata
}

export type InventoryMediaAsset = {
  id: string
  fileName: string
  contentType: string
  kind: "IMAGE" | "VIDEO"
  status: "UPLOADING" | "PROCESSING" | "READY" | "FAILED" | "DELETED"
  version: number
  generation: number
  rotationDegrees: 0 | 90 | 180 | 270
  sortOrder: number
  sizeBytes: number | null
  createdAt: string
  variants: Array<{
    kind: "SMALL" | "MEDIUM" | "LARGE"
    contentType: string
    url: string
    width: number | null
    height: number | null
  }>
}

export type InventoryMediaScope = {
  ownerId: string
  warehouseId: string
}
import type { LogisticsPlanningMode } from "@/features/repair-estimates/model/repair-estimate"
