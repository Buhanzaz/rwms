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
  queueCode: string
  queueKind: string
}

export type MaintenanceFurnitureEquipmentSnapshot = {
  equipmentId: string
  equipmentCode: string
  equipmentName: string
}

export type MaintenanceCatalogNodeSnapshot = {
  catalogVersionId: string
  nodeId: string
  code: string
  nodeType:
    "CATEGORY" | "SUBCATEGORY" | "WORK" | "MATERIAL" | "LOCATION" | "OPTION"
  name: string
  unit: string | null
  unitPrice: string | null
  durationMinutes: number
  routing: MaintenanceRoutingSnapshot | null
  furnitureEquipment: MaintenanceFurnitureEquipmentSnapshot | null
}

export type MaintenanceEstimateLineInput = {
  id: string
  catalogSnapshot: MaintenanceCatalogNodeSnapshot | null
  description: string
  quantity: string
  unitPrice: string
  comment: string | null
  mediaReferences: MaintenanceMediaReference[]
}

export type MaintenanceEstimateLine = MaintenanceEstimateLineInput & {
  lineTotal: string
}

export type MaintenanceRepairStageKind =
  "REPAIR_WORK" | "MOVE_TO_REPAIR" | "MOVE_FROM_REPAIR"

export type MaintenancePlanStageInput = {
  id: string
  kind: MaintenanceRepairStageKind
  order: number
  routing: MaintenanceRoutingSnapshot
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
  createdAt: string
  completedAt: string | null
  actor: MaintenanceActorSnapshot
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
  taskBoardRegistrationVersion: number | null
  generationState: "PENDING_GENERATION" | "GENERATED" | "FAILED"
  delivery: MaintenanceDeliverySnapshot
}

export type MaintenanceRepairStage = {
  id: string
  kind: MaintenanceRepairStageKind
  order: number
  state: "PLANNED" | "QUEUED" | "IN_PROGRESS" | "DONE" | "CANCELLED"
  routing: MaintenanceRoutingSnapshot
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
  sourceParty: string | null
  plan: MaintenanceRepairPlan
  inventorySource: MaintenanceInventorySource | null
  lease: MaintenanceLeaseSnapshot | null
  mediaReferences: MaintenanceMediaReference[]
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

export type MaintenanceWriteOffProjection = {
  repairId: string
  rootRepairId: string
  warehouseId: string
  rentalItemId: string
  repairVersion: number
  writtenOffAt: string
  actor: MaintenanceActorSnapshot
}

const MAINTENANCE_API = `${getGatewayRuntimeConfig().maintenanceApiBaseUrl}/v1`

function json(
  method: string,
  body: unknown,
  headers?: HeadersInit
): RequestInit {
  return { method, headers, body: JSON.stringify(body) }
}

function collectionEndpoint(
  resource: "estimates" | "repairs" | "acceptance" | "write-offs",
  warehouseId: string,
  filters: Record<string, string | undefined> = {}
) {
  const endpoint = new URL(`${MAINTENANCE_API}/${resource}`)
  endpoint.searchParams.set("warehouseId", warehouseId)
  endpoint.searchParams.set("page", "0")
  endpoint.searchParams.set("size", "200")
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined) endpoint.searchParams.set(key, value)
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
    throw new Error("Браузер не поддерживает безопасные UUID команд.")
  }
  return crypto.randomUUID()
}

export function listMaintenanceEstimates(
  accessToken: string,
  warehouseId: string,
  lifecycle?: MaintenanceEstimate["lifecycle"]
) {
  return bearerRequest<MaintenancePage<MaintenanceEstimate>>(
    accessToken,
    collectionEndpoint("estimates", warehouseId, { lifecycle })
  )
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
  idempotencyKey: string
) {
  return bearerRequest<MaintenanceEstimateCommandResult>(
    accessToken,
    itemEndpoint("estimates", warehouseId, estimateId, "/complete"),
    json("POST", { expectedVersion }, { "Idempotency-Key": idempotencyKey })
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

export function createDirectMaintenanceRepair(
  accessToken: string,
  idempotencyKey: string,
  request: {
    warehouseId: string
    rentalItemId: string
    dispatchDate: string
    sourceParty: string | null
    plan: MaintenancePlanStageInput[]
    mediaReferences: MaintenanceMediaReference[]
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
  stages: MaintenancePlanStageInput[],
  mediaReferences: MaintenanceMediaReference[]
) {
  return bearerRequest<MaintenanceRepair>(
    accessToken,
    itemEndpoint("repairs", warehouseId, repairId, "/plan"),
    json("PUT", { expectedVersion, stages, mediaReferences })
  )
}

export function queueMaintenanceRepair(
  accessToken: string,
  warehouseId: string,
  repairId: string,
  expectedVersion: number,
  idempotencyKey: string
) {
  return bearerRequest<MaintenanceRepairCommandResult>(
    accessToken,
    itemEndpoint("repairs", warehouseId, repairId, "/plan"),
    json("POST", { expectedVersion }, { "Idempotency-Key": idempotencyKey })
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
    plan: MaintenancePlanStageInput[]
    mediaReferences: MaintenanceMediaReference[]
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

export function writeOffMaintenanceRepair(
  accessToken: string,
  warehouseId: string,
  repairId: string,
  expectedVersion: number,
  reason: string,
  comment: string | null,
  idempotencyKey: string
) {
  return bearerRequest<MaintenanceRepairCommandResult>(
    accessToken,
    itemEndpoint("repairs", warehouseId, repairId, "/write-off"),
    json(
      "POST",
      { expectedVersion, reason, comment },
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

export function listMaintenanceWriteOffs(
  accessToken: string,
  warehouseId: string
) {
  return bearerRequest<MaintenancePage<MaintenanceWriteOffProjection>>(
    accessToken,
    collectionEndpoint("write-offs", warehouseId)
  )
}
