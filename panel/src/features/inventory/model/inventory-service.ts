export type InventorySessionLifecycle = "ACTIVE" | "COMPLETED" | "CANCELLED"
export type InventoryReviewStage = "CABINS" | "FURNITURE"
export type InventoryCabinDispositionReviewPhase =
  "RETURNS" | "SHIPMENTS" | "COMPLETED"
export type InventoryCabinDispositionKind = "LOCAL" | "SHIPMENT" | "WRITE_OFF"
export type InventoryFurnitureReconciliationState =
  | "NOT_REQUIRED"
  | "READY"
  | "PENDING"
  | "SUCCEEDED"
  | "TRANSIENT_FAILED"
  | "BLOCKED"
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
/** Asset-service status selected by the authoritative inventory outcome. */
export type InventoryAssetOutcomeStatus =
  "FREE" | "REPAIR" | "CAPITAL_REPAIR" | "RENTED"

export type InventoryPlanningSettings = {
  warehouseId: string
  settingsRevision: number
  updatedAt: string | null
  holidays: string[]
}

export type UpdateInventoryPlanningSettingsRequest = {
  expectedSettingsRevision: number
  holidays: string[]
}

export type InventoryScheduleMode = "AUTO" | "MANUAL"
export type InventoryFinalPlanState = "DRAFT" | "STALE" | "COMPLETED"
export type InventoryReconciliationStrategy = "CREATE" | "REPLACE" | "MERGE"
export type InventoryReconciliationTargetKind = "ESTIMATE" | "REPAIR"

export type InventoryCollisionPlanSummary = {
  workLineCount: number
  materialLineCount: number
  grandTotalMinor: number
}

export type InventoryCollisionCandidate = {
  targetKind: InventoryReconciliationTargetKind
  targetId: string
  estimateId: string | null
  repairId: string | null
  version: number
  state: string
  started: boolean
  active: boolean
  priority: 1 | 2 | 3 | 4 | 5 | null
  sourceParty: string | null
  planFingerprintSha256: string | null
  planSummary: InventoryCollisionPlanSummary
  forceCapitalRepair: boolean
}

export type InventoryReconciliationDecision = {
  strategy: InventoryReconciliationStrategy
  selectedTargetKind: InventoryReconciliationTargetKind | null
  selectedTargetId: string | null
}

export type InventoryFinalPlanEntry = {
  findingId: string
  findingRevision: number
  planFingerprintSha256: string | null
  targetKind: InventoryReconciliationTargetKind | null
  hasWork: boolean
  order: number
  priority: 1 | 2 | 3 | 4 | 5 | null
  movementToRepair: boolean
  movementScheduledDate: string | null
  repairScheduledDate: string | null
  collisionCandidates: InventoryCollisionCandidate[]
  reconciliationDecision: InventoryReconciliationDecision | null
  forceCapitalRepair: boolean
  dispositionKind: InventoryCabinDispositionKind
  dispositionDetails: Record<string, unknown>
}

export type InventoryFinalPlan = {
  inventoryId: string
  sessionRevision: number
  finalPlanVersion: number
  finalPlanSha256: string
  planningSettingsRevision: number
  state: InventoryFinalPlanState
  movementScheduleMode: InventoryScheduleMode
  repairScheduleMode: InventoryScheduleMode
  entries: InventoryFinalPlanEntry[]
}

export type PrepareInventoryFinalPlanRequest = {
  expectedSessionRevision: number
  expectedSettingsRevision: number
  movementScheduleMode: InventoryScheduleMode
  repairScheduleMode: InventoryScheduleMode
}

export type UpdateInventoryFinalPlanEntryRequest = {
  findingId: string
  expectedFindingRevision: number
  order: number
  priority: 1 | 2 | 3 | 4 | 5 | null
  movementToRepair: boolean
  movementScheduledDate: string | null
  repairScheduledDate: string | null
  reconciliationDecision: InventoryReconciliationDecision | null
}

export type UpdateInventoryFinalPlanRequest = {
  expectedSessionRevision: number
  expectedFinalPlanVersion: number
  movementScheduleMode: InventoryScheduleMode
  repairScheduleMode: InventoryScheduleMode
  entries: UpdateInventoryFinalPlanEntryRequest[]
}

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
  reviewStage: InventoryReviewStage
  furnitureReconciliationState: InventoryFurnitureReconciliationState
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
  routingQueueId: string | null
  routingQueueName: string | null
  routingQueueType: string | null
  description: string
  normalizedDescription: string | null
  unit: string
  quantity: string
  unitPriceMinor: number
  normativeMinutes: string
  groupComment: string | null
  mediaReferences: InventoryMediaReference[]
}

export type InventoryFrozenPlanStage = {
  id: string
  order: number
  catalogNodeId: string
  catalogNodeName: string
  kind: "REPAIR_WORK"
  routingQueueId: string
  routingQueueName: string
  routingQueueType: string
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
  movementToRepair: boolean
  forceCapitalRepair: boolean
  logisticsPlanningMode: LogisticsPlanningMode | null
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
  desiredAssetStatus: InventoryAssetOutcomeStatus
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

export type FurnitureReviewCabin = {
  findingId: string
  assetId: string
  cabinNumber: string
  status: string
  currentQuantity: number
  observedQuantity: number
}

export type FurnitureReviewItem = {
  equipmentId: string
  catalogVersion: number
  equipmentName: string
  currentStockQuantity: number
  observedStockQuantity: number
  cabins: FurnitureReviewCabin[]
}

export type FurnitureReviewView = {
  inventoryId: string
  sessionRevision: number
  stage: InventoryReviewStage
  assetSnapshotSha256: string
  reviewSha256: string | null
  confirmed: boolean
  items: FurnitureReviewItem[]
}

export type StartFurnitureReviewRequest = {
  expectedSessionRevision: number
  findingRevisions: InventoryRevisionExpectation[]
}

export type InventoryCabinDispositionCandidate = {
  findingId: string
  findingRevision: number
  assetId: string
  assetVersion: number
  cabinNumber: string
  candidateKind: "RETURN" | "MISSING"
  dispositionKind: InventoryCabinDispositionKind | null
  dispositionDetails: Record<string, unknown> | null
}

export type InventoryCabinDispositionReview = {
  inventoryId: string
  sessionRevision: number
  reviewRevision: number
  phase: InventoryCabinDispositionReviewPhase
  returnCandidates: InventoryCabinDispositionCandidate[]
  missingCandidates: InventoryCabinDispositionCandidate[]
}

export type ConfirmInventoryReturnsRequest = {
  expectedSessionRevision: number
  expectedReviewRevision: number
  returns: Array<{
    findingId: string
    expectedFindingRevision: number
    returnedOn: string
    clientId: string
    clientSnapshot: string
  }>
}

export type ConfirmInventoryShipmentsRequest = {
  expectedSessionRevision: number
  expectedReviewRevision: number
  shipments: Array<{
    findingId: string
    expectedFindingRevision: number
    departedOn: string
    clientId: string
    clientSnapshot: string
    furniture: Array<{
      equipmentId: string
      catalogVersion: number
      quantity: number
    }>
  }>
}

export type RefreshInventorySessionRequest = {
  expectedSessionRevision: number
}

export type SaveFurnitureReviewRequest = {
  expectedSessionRevision: number
  assetSnapshotSha256: string
  items: Array<{
    equipmentId: string
    catalogVersion: number
    observedStockQuantity: number
    cabins: Array<{
      findingId: string
      expectedFindingRevision: number
      observedQuantity: number
    }>
  }>
}

export type InventoryValidatedFinding = {
  findingId: string
  currentSnapshot: InventoryCurrentSnapshot | null
  conflicts: InventoryConflict[]
}

export type InventoryRegistryReview = {
  inventoryId: string
  sessionRevision: number
  findingRevisions: InventoryRevisionExpectation[]
  validatedAt: string
  validatedFindings: InventoryValidatedFinding[]
}

export type InventoryCompletionPreview = {
  inventoryId: string
  sessionRevision: number
  finalPlanVersion: number
  finalPlanSha256: string
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
  validatedFindings: InventoryValidatedFinding[]
}

export type InventoryCatalogPlanLine = {
  aggregationKind: "CATALOG"
  catalogNodeId: string
  routingCatalogNodeId: null
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
  routingCatalogNodeId: string
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
  kind: "REPAIR_WORK"
  order: number
}

export type InventoryPlanSelection =
  | null
  | {
      mode: "AUTO"
      priority: 1 | 2 | 3 | 4 | 5
      coverMediaId: string | null
      movementToRepair: boolean
      forceCapitalRepair?: boolean
      logisticsPlanningMode: LogisticsPlanningMode | null
      logisticsScheduledDate: string | null
      lines: InventoryCatalogPlanLine[]
      stages: InventoryPlanStageSelection[]
    }
  | {
      mode: "MANUAL"
      priority: 1 | 2 | 3 | 4 | 5
      coverMediaId: string | null
      movementToRepair: boolean
      forceCapitalRepair?: boolean
      logisticsPlanningMode: LogisticsPlanningMode | null
      logisticsScheduledDate: string | null
      lines: Array<InventoryCatalogPlanLine | InventoryManualPlanLine>
      stages: InventoryPlanStageSelection[]
    }

export type InventoryPublicationBatch = {
  inventoryId: string
  aggregateState: InventoryAggregatePublicationState
  intents: InventoryPublicationIntent[]
}

export type RecalculateInventoryOutcomeRequest = {
  expectedSessionRevision: number
  finalPlanVersion: number
  finalPlanSha256: string
}

export type OutcomeRecalculation = {
  inventoryId: string
  sessionRevision: number
  finalPlanVersion: number
  finalPlanSha256: string
  furnitureReconciliationState: InventoryFurnitureReconciliationState
  createdPublicationCount: number
  requeuedPublicationCount: number
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
