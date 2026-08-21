import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type MaintenanceMediaReference = {
  mediaId: string
  generation: number
}

export type MaintenanceActorSnapshot = {
  actorId: string
  actorType: "USER" | "SERVICE"
}

export type MaintenanceRoutingSnapshot = {
  queueId: string
  queueName: string
  queueType: string
}

export type MaintenanceFurnitureEquipmentSnapshot = {
  equipmentId: string
  equipmentName: string
}

export type MaintenanceCabinCharacteristicSnapshot = {
  characteristicId: string
  characteristicName: string
}

export type MaintenanceCatalogNodeSnapshot = {
  catalogVersionId: string
  nodeId: string
  nodeType:
    "CATEGORY" | "SUBCATEGORY" | "WORK" | "MATERIAL" | "LOCATION" | "OPTION"
  name: string
  unit: string | null
  unitPrice: string | null
  durationMinutes: number
  routing: MaintenanceRoutingSnapshot | null
  furnitureEquipment: MaintenanceFurnitureEquipmentSnapshot | null
  forcesCapitalRepair: boolean
  characteristic: MaintenanceCabinCharacteristicSnapshot | null
}

export type MaintenanceEstimateLineType = "WORK" | "MATERIAL"

export type MaintenanceEstimateLineInput = {
  id: string
  catalogSnapshot: MaintenanceCatalogNodeSnapshot | null
  lineType: MaintenanceEstimateLineType
  description: string
  unit: string | null
  quantity: string
  /** Required for custom work; custom MATERIAL is sent as zero. */
  normativeMinutes?: number
  unitPrice: string
  comment: string | null
  mediaReferences: MaintenanceMediaReference[]
}

export type MaintenanceEstimateLine = Omit<
  MaintenanceEstimateLineInput,
  "normativeMinutes"
> & {
  /** Maintenance always returns the canonical planned duration. */
  normativeMinutes: number
  lineTotal: string
  disposition?: MaintenanceReworkLineDisposition | null
  sourceRepairId?: string | null
  sourceLineId?: string | null
  lineageRootLineId?: string | null
}

export type MaintenanceReworkLineDisposition = "ADDED" | "REPEAT"

export type MaintenanceReworkLineMetadata = {
  disposition: MaintenanceReworkLineDisposition
  sourceRepairId: string | null
  sourceLineId: string | null
  lineageRootLineId: string
}

export type MaintenanceReworkCandidateLine = {
  sourceRepairId: string
  sourceLineId: string
  lineageRootLineId: string
  line: MaintenanceEstimateLine
}

export type MaintenanceReworkCandidates = {
  items: MaintenanceReworkCandidateLine[]
}

export type MaintenanceAddedReworkLineInput = {
  id: string
  disposition: "ADDED"
  line: MaintenanceEstimateLineInput
}

export type MaintenanceRepeatReworkLineInput = {
  id: string
  disposition: "REPEAT"
  sourceRepairId: string
  sourceLineId: string
  quantity: string
  comment: string | null
}

export type MaintenanceReworkLineInput =
  MaintenanceAddedReworkLineInput | MaintenanceRepeatReworkLineInput

export type MaintenanceRepairStageKind = "REPAIR_WORK"

export type MaintenancePlanStageInput = {
  id: string
  kind: MaintenanceRepairStageKind
  order: number
  routing: MaintenanceRoutingSnapshot
  includedLineIds: string[]
  primaryLineId: string | null
  groupComment: string
  taskDeadline: string | null
}

export type MaintenanceEstimateRevision = {
  revision: number
  dispatchDate: string
  sourceParty: string | null
  lines: MaintenanceEstimateLine[]
  plan: MaintenancePlanStageInput[]
  total: string
  reason: string | null
  recordedAt: string
  forceCapitalRepair: boolean
}

export type MaintenanceEstimate = {
  id: string
  warehouseId: string
  rentalItemId: string
  version: number
  lifecycle: "DRAFT" | "COMPLETED"
  currentRevision: number
  revisions: MaintenanceEstimateRevision[]
  repairId: string | null
  mediaReferences: MaintenanceMediaReference[]
  coverMediaId?: string | null
  createdAt: string
  completedAt: string | null
  actor: MaintenanceActorSnapshot
  forceCapitalRepair: boolean
}

/** A maintenance-owned draft that was created for one return-document line. */
export type MaintenanceReturnEstimateSource = {
  returnId: string
  lineId: string
  warehouseId: string
  rentalItemId: string
  estimateId: string
}

export type MaintenancePage<T> = {
  items: T[]
  page: number
  size: number
  totalElements: number
}

export type MaintenanceEstimateWrite = {
  dispatchDate: string
  sourceParty: string | null
  lines: MaintenanceEstimateLineInput[]
  plan: MaintenancePlanStageInput[]
  mediaReferences: MaintenanceMediaReference[]
  coverMediaId?: string | null
  forceCapitalRepair?: boolean
}

export type MaintenanceDeliverySnapshot = {
  state: "PENDING" | "RETRY_PENDING" | "DELIVERED" | "QUARANTINED"
  attempts: number
  updatedAt: string
}

export type MaintenanceLeaseSnapshot = {
  leaseId: string
  fencingToken: number
  expiresAt: string
  reconciliationState:
    "NOT_ACQUIRED" | "ACTIVE" | "RELEASED" | "RECONCILIATION_REQUIRED"
}

export type MaintenanceTaskSyncSnapshot = {
  externalTaskId: string
  taskBoardEntryId: string | null
  taskBoardRegistrationVersion: number | null
  generationState: "PENDING_GENERATION" | "GENERATED" | "FAILED"
  delivery: MaintenanceDeliverySnapshot
}

export type MaintenanceTaskEvidence = {
  evidenceId: string
  entryId: string
  workerId: string
  workerGroupId: string | null
  mediaId: string
  mediaGeneration: number
  capturedAt: string
  recordedAt: string
  state: "READY" | "REVIEW_REQUIRED"
}

export type MaintenanceRepairStage = {
  id: string
  kind: MaintenanceRepairStageKind
  order: number
  state: "PLANNED" | "QUEUED" | "IN_PROGRESS" | "DONE" | "CANCELLED"
  routing: MaintenanceRoutingSnapshot
  workLines: MaintenanceEstimateLine[]
  materialLines: MaintenanceEstimateLine[]
  primaryLineId: string | null
  groupComment: string
  evidence: MaintenanceTaskEvidence[]
  taskDeadline: string | null
  taskSync: MaintenanceTaskSyncSnapshot
  completedAt: string | null
}

export type MaintenanceRepairPlan = {
  repairId: string
  repairVersion: number
  stages: MaintenanceRepairStage[]
}

export type MaintenanceInventorySource = {
  inventoryId: string
  findingId: string
  sourceRevision: number
  planFingerprint: string
  sourceFingerprint: string
}

export type MaintenanceRepair = {
  id: string
  rootRepairId: string
  sourceRepairId: string | null
  estimateId: string | null
  warehouseId: string
  rentalItemId: string
  origin: "ESTIMATE" | "DIRECT_REPAIR" | "INVENTORY"
  kind: "PRIMARY" | "REWORK"
  executionState: "DRAFT" | "QUEUED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED"
  acceptanceState:
    "NOT_READY" | "PENDING" | "IN_REWORK" | "ACCEPTED" | "WRITTEN_OFF"
  version: number
  dispatchDate: string
  priority: number
  sourceParty: string | null
  plan: MaintenanceRepairPlan
  inventorySource: MaintenanceInventorySource | null
  lease: MaintenanceLeaseSnapshot | null
  mediaReferences: MaintenanceMediaReference[]
  coverMediaId?: string | null
  complexity: {
    type: "LIGHT" | "MEDIUM" | "COMPLEX" | "CAPITAL"
    name:
      | "Лёгкий ремонт"
      | "Средний ремонт"
      | "Тяжёлый ремонт"
      | "Капитальный ремонт"
    color: string
    plannedMinutes: string
    forcedCapital: boolean
  }
  movementToRepair: boolean
  forceCapitalRepair: boolean
  logisticsPlanningMode: "AUTO" | "FIXED_DATE"
  logisticsScheduledDate: string | null
  createdAt: string
  updatedAt: string
  actor: MaintenanceActorSnapshot
}

export type MaintenanceEstimateCommandResult = {
  estimate: MaintenanceEstimate
  repair: MaintenanceRepair | null
  delivery: MaintenanceDeliverySnapshot
}

export type MaintenanceRepairCommandResult = {
  repair: MaintenanceRepair
  affectedSourceRepairs: MaintenanceRepair[]
  delivery: MaintenanceDeliverySnapshot
}

export type MaintenanceAcceptanceProjection = {
  repairId: string
  rootRepairId: string
  warehouseId: string
  rentalItemId: string
  executionState: MaintenanceRepair["executionState"]
  acceptanceState: MaintenanceRepair["acceptanceState"]
  repairVersion: number
  readyAt: string
}

const MAINTENANCE_API = `${getGatewayRuntimeConfig().maintenanceApiBaseUrl}/v1`

function json(
  method: string,
  body: unknown,
  headers?: HeadersInit
): RequestInit {
  return { method, headers, body: JSON.stringify(body) }
}

function validateLogisticsPlanning(
  movementToRepair: boolean,
  logisticsPlanningMode: "AUTO" | "FIXED_DATE",
  logisticsScheduledDate: string | null
) {
  if (!movementToRepair) {
    if (logisticsPlanningMode !== "AUTO" || logisticsScheduledDate !== null) {
      throw new Error(
        "Планирование логистики доступно только для перемещения на ремонт."
      )
    }
    return { logisticsPlanningMode: null, logisticsScheduledDate: null }
  }
  if (
    (logisticsPlanningMode === "AUTO" && logisticsScheduledDate !== null) ||
    (logisticsPlanningMode === "FIXED_DATE" && !logisticsScheduledDate)
  ) {
    throw new Error(
      "Дата логистического задания должна быть задана только для режима выбора конкретной даты."
    )
  }
  return { logisticsPlanningMode, logisticsScheduledDate }
}

function collectionEndpoint(
  resource: "estimates" | "repairs" | "acceptance",
  warehouseId: string,
  filters: Record<string, string | number | readonly string[] | undefined> = {}
) {
  const endpoint = new URL(`${MAINTENANCE_API}/${resource}`)
  endpoint.searchParams.set("warehouseId", warehouseId)
  endpoint.searchParams.set("page", "0")
  endpoint.searchParams.set("size", "200")
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined) {
      endpoint.searchParams.set(
        key,
        Array.isArray(value) ? value.join(",") : String(value)
      )
    }
  })
  return endpoint
}

function itemEndpoint(
  resource: "estimates" | "repairs",
  warehouseId: string,
  id: string,
  suffix = ""
) {
  const endpoint = new URL(
    `${MAINTENANCE_API}/${resource}/${encodeURIComponent(id)}${suffix}`
  )
  endpoint.searchParams.set("warehouseId", warehouseId)
  return endpoint
}

export function createMaintenanceIdempotencyKey() {
  if (typeof crypto === "undefined" || !("randomUUID" in crypto)) {
    throw new Error(
      "Браузер не поддерживает создание безопасного ключа команды."
    )
  }
  return crypto.randomUUID()
}

export function listMaintenanceEstimates(
  accessToken: string,
  warehouseId: string,
  lifecycle?: MaintenanceEstimate["lifecycle"],
  rentalItemId?: string
) {
  return bearerRequest<MaintenancePage<MaintenanceEstimate>>(
    accessToken,
    collectionEndpoint("estimates", warehouseId, {
      lifecycle,
      rentalItemId,
    })
  )
}

export function listMaintenanceReturnEstimateSources(
  accessToken: string,
  warehouseId: string,
  returnId: string
) {
  const endpoint = new URL(`${MAINTENANCE_API}/estimates/return-sources`)
  endpoint.searchParams.set("warehouseId", warehouseId)
  endpoint.searchParams.set("returnId", returnId)
  return bearerRequest<MaintenanceReturnEstimateSource[]>(accessToken, endpoint)
}

export function getMaintenanceEstimate(
  accessToken: string,
  warehouseId: string,
  estimateId: string
) {
  return bearerRequest<MaintenanceEstimate>(
    accessToken,
    itemEndpoint("estimates", warehouseId, estimateId)
  )
}

export function createMaintenanceEstimate(
  accessToken: string,
  idempotencyKey: string,
  request: MaintenanceEstimateWrite & {
    warehouseId: string
    rentalItemId: string
  }
) {
  return bearerRequest<MaintenanceEstimate>(
    accessToken,
    `${MAINTENANCE_API}/estimates`,
    json("POST", request, { "Idempotency-Key": idempotencyKey })
  )
}

export function replaceMaintenanceEstimate(
  accessToken: string,
  warehouseId: string,
  estimateId: string,
  expectedVersion: number,
  request: MaintenanceEstimateWrite
) {
  return bearerRequest<MaintenanceEstimate>(
    accessToken,
    itemEndpoint("estimates", warehouseId, estimateId),
    json("PUT", { expectedVersion, ...request })
  )
}

export function completeMaintenanceEstimate(
  accessToken: string,
  warehouseId: string,
  estimateId: string,
  expectedVersion: number,
  priority: number,
  idempotencyKey: string,
  movementToRepair: boolean,
  logisticsPlanningMode: "AUTO" | "FIXED_DATE",
  logisticsScheduledDate: string | null,
  allowUnaccountedFurniture = false
) {
  const logisticsPlanning = validateLogisticsPlanning(
    movementToRepair,
    logisticsPlanningMode,
    logisticsScheduledDate
  )
  return bearerRequest<MaintenanceEstimateCommandResult>(
    accessToken,
    itemEndpoint("estimates", warehouseId, estimateId, "/complete"),
    json(
      "POST",
      {
        expectedVersion,
        priority,
        movementToRepair,
        allowUnaccountedFurniture,
        ...logisticsPlanning,
      },
      { "Idempotency-Key": idempotencyKey }
    )
  )
}

export function amendMaintenanceEstimate(
  accessToken: string,
  warehouseId: string,
  estimateId: string,
  idempotencyKey: string,
  request: MaintenanceEstimateWrite & {
    expectedVersion: number
    expectedLinkedRepairVersion: number | null
    reason: string
  }
) {
  return bearerRequest<MaintenanceEstimateCommandResult>(
    accessToken,
    itemEndpoint("estimates", warehouseId, estimateId, "/amendments"),
    json("POST", request, { "Idempotency-Key": idempotencyKey })
  )
}

export function listMaintenanceRepairs(
  accessToken: string,
  warehouseId: string,
  filters: {
    executionState?: MaintenanceRepair["executionState"]
    acceptanceState?: MaintenanceRepair["acceptanceState"]
    rentalItemId?: string
    repairIds?: readonly string[]
    page?: number
    size?: number
  } = {}
) {
  return bearerRequest<MaintenancePage<MaintenanceRepair>>(
    accessToken,
    collectionEndpoint("repairs", warehouseId, filters)
  )
}

export function getMaintenanceRepair(
  accessToken: string,
  warehouseId: string,
  repairId: string
) {
  return bearerRequest<MaintenanceRepair>(
    accessToken,
    itemEndpoint("repairs", warehouseId, repairId)
  )
}

export function getMaintenanceReworkCandidates(
  accessToken: string,
  warehouseId: string,
  repairId: string
) {
  return bearerRequest<MaintenanceReworkCandidates>(
    accessToken,
    itemEndpoint("repairs", warehouseId, repairId, "/rework-candidates")
  )
}

export function createDirectMaintenanceRepair(
  accessToken: string,
  idempotencyKey: string,
  request: {
    warehouseId: string
    rentalItemId: string
    dispatchDate: string
    sourceParty: string | null
    lines: MaintenanceEstimateLineInput[]
    plan: MaintenancePlanStageInput[]
    mediaReferences: MaintenanceMediaReference[]
    coverMediaId: string | null
    forceCapitalRepair?: boolean
  }
) {
  return bearerRequest<MaintenanceRepair>(
    accessToken,
    `${MAINTENANCE_API}/repairs/direct`,
    json("POST", request, { "Idempotency-Key": idempotencyKey })
  )
}

export function replaceMaintenanceRepairPlan(
  accessToken: string,
  warehouseId: string,
  repairId: string,
  expectedVersion: number,
  lines: MaintenanceEstimateLineInput[],
  stages: MaintenancePlanStageInput[],
  mediaReferences: MaintenanceMediaReference[],
  coverMediaId: string | null,
  forceCapitalRepair = false
) {
  return bearerRequest<MaintenanceRepair>(
    accessToken,
    itemEndpoint("repairs", warehouseId, repairId, "/plan"),
    json("PUT", {
      expectedVersion,
      lines,
      stages,
      mediaReferences,
      coverMediaId,
      forceCapitalRepair,
    })
  )
}

export function queueMaintenanceRepair(
  accessToken: string,
  warehouseId: string,
  repairId: string,
  expectedVersion: number,
  priority: number,
  idempotencyKey: string,
  movementToRepair: boolean,
  logisticsPlanningMode: "AUTO" | "FIXED_DATE",
  logisticsScheduledDate: string | null
) {
  const logisticsPlanning = validateLogisticsPlanning(
    movementToRepair,
    logisticsPlanningMode,
    logisticsScheduledDate
  )
  return bearerRequest<MaintenanceRepairCommandResult>(
    accessToken,
    itemEndpoint("repairs", warehouseId, repairId, "/plan"),
    json(
      "POST",
      {
        expectedVersion,
        priority,
        movementToRepair,
        ...logisticsPlanning,
      },
      { "Idempotency-Key": idempotencyKey }
    )
  )
}

export function createMaintenanceRework(
  accessToken: string,
  warehouseId: string,
  sourceRepairId: string,
  idempotencyKey: string,
  request: {
    expectedVersion: number
    reason: string
    lines: MaintenanceReworkLineInput[]
    plan: MaintenancePlanStageInput[]
    mediaReferences: MaintenanceMediaReference[]
    coverMediaId: string | null
  }
) {
  return bearerRequest<MaintenanceRepair>(
    accessToken,
    itemEndpoint("repairs", warehouseId, sourceRepairId, "/reworks"),
    json("POST", request, { "Idempotency-Key": idempotencyKey })
  )
}

export function acceptMaintenanceRepair(
  accessToken: string,
  warehouseId: string,
  repairId: string,
  expectedVersion: number,
  comment: string | null,
  mediaReferences: MaintenanceMediaReference[],
  idempotencyKey: string
) {
  return bearerRequest<MaintenanceRepairCommandResult>(
    accessToken,
    itemEndpoint("repairs", warehouseId, repairId, "/accept"),
    json(
      "POST",
      { expectedVersion, comment, mediaReferences },
      { "Idempotency-Key": idempotencyKey }
    )
  )
}

export function listMaintenanceAcceptance(
  accessToken: string,
  warehouseId: string,
  state: MaintenanceRepair["acceptanceState"] = "PENDING"
) {
  return bearerRequest<MaintenancePage<MaintenanceAcceptanceProjection>>(
    accessToken,
    collectionEndpoint("acceptance", warehouseId, { state })
  )
}
