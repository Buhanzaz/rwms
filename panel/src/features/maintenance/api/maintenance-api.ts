import { getMaintenanceAccessToken } from "@/features/maintenance/maintenance-runtime"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const MAINTENANCE_API = `${getGatewayRuntimeConfig().maintenanceApiBaseUrl}/v1`

export type MaintenanceActor = {
  actorId: string
  actorType: "USER" | "SERVICE"
}

export type MaintenanceMediaReference = {
  mediaId: string
  generation: number
}

export type MaintenanceRouting = {
  queueId: string
  queueCode: string
  queueKind: string
}

export type MaintenanceCatalogNode = {
  id: string
  catalogVersionId: string
  code: string
  nodeType:
    "CATEGORY" | "SUBCATEGORY" | "WORK" | "MATERIAL" | "LOCATION" | "OPTION"
  name: string
  active: boolean
  parentNodeId: string | null
  unit: string | null
  unitPrice: string | null
  durationMinutes: number
  includeInEstimate: boolean
  commonItem: boolean
  showInMainMenu: boolean
  photoRequired: boolean
  routing: MaintenanceRouting | null
  references: Array<{ referenceId: string; code: string }>
  comment: string | null
  mediaReferences: MaintenanceMediaReference[]
}

export type MaintenanceCatalogLink = {
  id: string
  catalogVersionId: string
  fromNodeId: string
  toNodeId: string
  linkType: "DEPENDENCY" | "FOLLOW_UP"
  sortOrder: number
}

export type MaintenanceCatalogVersion = {
  id: string
  warehouseId: string
  version: number
  lifecycle: "DRAFT" | "ACTIVE" | "SUPERSEDED"
  sourceSha256: string
  counts: { nodes: number; links: number }
  validation: {
    valid: boolean
    errorCount: number
    warningCount: number
    reportSha256: string
  }
  createdAt: string
  activatedAt: string | null
}

export type MaintenanceCatalogPage = {
  items: MaintenanceCatalogVersion[]
  page: number
  size: number
  totalElements: number
}

export type MaintenanceCatalogNodeSnapshot = {
  catalogVersionId: string
  nodeId: string
  code: string
  nodeType: MaintenanceCatalogNode["nodeType"]
  name: string
  unit: string | null
  unitPrice: string | null
  durationMinutes: number
  routing: MaintenanceRouting | null
}

export type MaintenanceEstimateLine = {
  id: string
  catalogSnapshot: MaintenanceCatalogNodeSnapshot | null
  description: string
  quantity: string
  unitPrice: string
  lineTotal: string
  comment: string | null
  mediaReferences: MaintenanceMediaReference[]
}

export type MaintenancePlanStageInput = {
  id: string
  kind: "REPAIR_WORK" | "MOVE_TO_REPAIR" | "MOVE_FROM_REPAIR"
  order: number
  routing: MaintenanceRouting
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
  actor: MaintenanceActor
}

export type MaintenanceEstimatePage = {
  items: MaintenanceEstimate[]
  page: number
  size: number
  totalElements: number
}

export type MaintenanceDelivery = {
  state: "PENDING" | "RETRY_PENDING" | "DELIVERED" | "QUARANTINED"
  attempts: number
  updatedAt: string
}

export type MaintenanceTaskSync = {
  externalTaskId: string
  taskBoardRegistrationVersion: number | null
  generationState: "PENDING_GENERATION" | "GENERATED" | "FAILED"
  delivery: MaintenanceDelivery
}

export type MaintenanceRepairStage = {
  id: string
  kind: MaintenancePlanStageInput["kind"]
  order: number
  state: "PLANNED" | "QUEUED" | "IN_PROGRESS" | "DONE" | "CANCELLED"
  routing: MaintenanceRouting
  taskDeadline: string | null
  taskSync: MaintenanceTaskSync
  completedAt: string | null
}

export type MaintenanceRepair = {
  id: string
  rootRepairId: string
  sourceRepairId: string | null
  estimateId: string | null
  warehouseId: string
  rentalItemId: string
  origin: "ESTIMATE" | "DIRECT_REPAIR"
  kind: "PRIMARY" | "REWORK"
  executionState: "DRAFT" | "QUEUED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED"
  acceptanceState:
    "NOT_READY" | "PENDING" | "IN_REWORK" | "ACCEPTED" | "WRITTEN_OFF"
  version: number
  dispatchDate: string
  sourceParty: string | null
  plan: {
    repairId: string
    repairVersion: number
    stages: MaintenanceRepairStage[]
  }
  lease: {
    leaseId: string
    fencingToken: number
    expiresAt: string
    reconciliationState:
      "NOT_ACQUIRED" | "ACTIVE" | "RELEASED" | "RECONCILIATION_REQUIRED"
  } | null
  mediaReferences: MaintenanceMediaReference[]
  createdAt: string
  updatedAt: string
  actor: MaintenanceActor
}

export type MaintenanceRepairPage = {
  items: MaintenanceRepair[]
  page: number
  size: number
  totalElements: number
}

export type MaintenanceEstimateCommandResult = {
  estimate: MaintenanceEstimate
  repair: MaintenanceRepair | null
  delivery: MaintenanceDelivery
}

export type MaintenanceRepairCommandResult = {
  repair: MaintenanceRepair
  affectedSourceRepairs: MaintenanceRepair[]
  delivery: MaintenanceDelivery
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
  actor: MaintenanceActor
}

type JsonRecord = Record<string, unknown>

function invalidResponse(): never {
  throw new Error("Сервис технического обслуживания вернул некорректный ответ.")
}

function object(value: unknown): JsonRecord {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    invalidResponse()
  }
  return value as JsonRecord
}

function list(value: unknown) {
  if (!Array.isArray(value)) invalidResponse()
  return value
}

function text(value: unknown) {
  if (typeof value !== "string") invalidResponse()
  return value
}

function nullableText(value: unknown) {
  return value === null ? null : text(value)
}

function integer(value: unknown) {
  if (!Number.isSafeInteger(value) || (value as number) < 0) invalidResponse()
  return value as number
}

function truth(value: unknown) {
  if (typeof value !== "boolean") invalidResponse()
  return value
}

function oneOf<const T extends readonly string[]>(value: unknown, values: T) {
  const parsed = text(value)
  if (!values.includes(parsed)) invalidResponse()
  return parsed as T[number]
}

const NODE_TYPES = [
  "CATEGORY",
  "SUBCATEGORY",
  "WORK",
  "MATERIAL",
  "LOCATION",
  "OPTION",
] as const
const STAGE_KINDS = [
  "REPAIR_WORK",
  "MOVE_TO_REPAIR",
  "MOVE_FROM_REPAIR",
] as const

function parseActor(value: unknown): MaintenanceActor {
  const source = object(value)
  return {
    actorId: text(source.actorId),
    actorType: oneOf(source.actorType, ["USER", "SERVICE"] as const),
  }
}

function parseMediaReference(value: unknown): MaintenanceMediaReference {
  const source = object(value)
  return {
    mediaId: text(source.mediaId),
    generation: integer(source.generation),
  }
}

function parseRouting(value: unknown): MaintenanceRouting {
  const source = object(value)
  return {
    queueId: text(source.queueId),
    queueCode: text(source.queueCode),
    queueKind: text(source.queueKind),
  }
}

function parseCatalogVersion(value: unknown): MaintenanceCatalogVersion {
  const source = object(value)
  const counts = object(source.counts)
  const validation = object(source.validation)
  return {
    id: text(source.id),
    warehouseId: text(source.warehouseId),
    version: integer(source.version),
    lifecycle: oneOf(source.lifecycle, [
      "DRAFT",
      "ACTIVE",
      "SUPERSEDED",
    ] as const),
    sourceSha256: text(source.sourceSha256),
    counts: { nodes: integer(counts.nodes), links: integer(counts.links) },
    validation: {
      valid: truth(validation.valid),
      errorCount: integer(validation.errorCount),
      warningCount: integer(validation.warningCount),
      reportSha256: text(validation.reportSha256),
    },
    createdAt: text(source.createdAt),
    activatedAt: nullableText(source.activatedAt),
  }
}

function parseCatalogNode(value: unknown): MaintenanceCatalogNode {
  const source = object(value)
  return {
    id: text(source.id),
    catalogVersionId: text(source.catalogVersionId),
    code: text(source.code),
    nodeType: oneOf(source.nodeType, NODE_TYPES),
    name: text(source.name),
    active: truth(source.active),
    parentNodeId: nullableText(source.parentNodeId),
    unit: nullableText(source.unit),
    unitPrice: nullableText(source.unitPrice),
    durationMinutes: integer(source.durationMinutes),
    includeInEstimate: truth(source.includeInEstimate),
    commonItem: truth(source.commonItem),
    showInMainMenu: truth(source.showInMainMenu),
    photoRequired: truth(source.photoRequired),
    routing: source.routing === null ? null : parseRouting(source.routing),
    references: list(source.references).map((item) => {
      const reference = object(item)
      return {
        referenceId: text(reference.referenceId),
        code: text(reference.code),
      }
    }),
    comment: nullableText(source.comment),
    mediaReferences: list(source.mediaReferences).map(parseMediaReference),
  }
}

function parseCatalogLink(value: unknown): MaintenanceCatalogLink {
  const source = object(value)
  return {
    id: text(source.id),
    catalogVersionId: text(source.catalogVersionId),
    fromNodeId: text(source.fromNodeId),
    toNodeId: text(source.toNodeId),
    linkType: oneOf(source.linkType, ["DEPENDENCY", "FOLLOW_UP"] as const),
    sortOrder: integer(source.sortOrder),
  }
}

function parseEstimateLine(value: unknown): MaintenanceEstimateLine {
  const source = object(value)
  const catalog =
    source.catalogSnapshot === null ? null : object(source.catalogSnapshot)
  return {
    id: text(source.id),
    catalogSnapshot: catalog
      ? {
          catalogVersionId: text(catalog.catalogVersionId),
          nodeId: text(catalog.nodeId),
          code: text(catalog.code),
          nodeType: oneOf(catalog.nodeType, NODE_TYPES),
          name: text(catalog.name),
          unit: nullableText(catalog.unit),
          unitPrice: nullableText(catalog.unitPrice),
          durationMinutes: integer(catalog.durationMinutes),
          routing:
            catalog.routing === null ? null : parseRouting(catalog.routing),
        }
      : null,
    description: text(source.description),
    quantity: text(source.quantity),
    unitPrice: text(source.unitPrice),
    lineTotal: text(source.lineTotal),
    comment: nullableText(source.comment),
    mediaReferences: list(source.mediaReferences).map(parseMediaReference),
  }
}

function parsePlanStage(value: unknown): MaintenancePlanStageInput {
  const source = object(value)
  return {
    id: text(source.id),
    kind: oneOf(source.kind, STAGE_KINDS),
    order: integer(source.order),
    routing: parseRouting(source.routing),
    taskDeadline: nullableText(source.taskDeadline),
  }
}

function parseEstimate(value: unknown): MaintenanceEstimate {
  const source = object(value)
  return {
    id: text(source.id),
    warehouseId: text(source.warehouseId),
    rentalItemId: text(source.rentalItemId),
    version: integer(source.version),
    lifecycle: oneOf(source.lifecycle, ["DRAFT", "COMPLETED"] as const),
    currentRevision: integer(source.currentRevision),
    revisions: list(source.revisions).map((item) => {
      const revision = object(item)
      return {
        revision: integer(revision.revision),
        dispatchDate: text(revision.dispatchDate),
        sourceParty: nullableText(revision.sourceParty),
        lines: list(revision.lines).map(parseEstimateLine),
        plan: list(revision.plan).map(parsePlanStage),
        total: text(revision.total),
        reason: nullableText(revision.reason),
        recordedAt: text(revision.recordedAt),
      }
    }),
    repairId: nullableText(source.repairId),
    mediaReferences: list(source.mediaReferences).map(parseMediaReference),
    createdAt: text(source.createdAt),
    completedAt: nullableText(source.completedAt),
    actor: parseActor(source.actor),
  }
}

function parseDelivery(value: unknown): MaintenanceDelivery {
  const source = object(value)
  return {
    state: oneOf(source.state, [
      "PENDING",
      "RETRY_PENDING",
      "DELIVERED",
      "QUARANTINED",
    ] as const),
    attempts: integer(source.attempts),
    updatedAt: text(source.updatedAt),
  }
}

function parseTaskSync(value: unknown): MaintenanceTaskSync {
  const source = object(value)
  return {
    externalTaskId: text(source.externalTaskId),
    taskBoardRegistrationVersion:
      source.taskBoardRegistrationVersion === null
        ? null
        : integer(source.taskBoardRegistrationVersion),
    generationState: oneOf(source.generationState, [
      "PENDING_GENERATION",
      "GENERATED",
      "FAILED",
    ] as const),
    delivery: parseDelivery(source.delivery),
  }
}

function parseRepair(value: unknown): MaintenanceRepair {
  const source = object(value)
  const plan = object(source.plan)
  return {
    id: text(source.id),
    rootRepairId: text(source.rootRepairId),
    sourceRepairId: nullableText(source.sourceRepairId),
    estimateId: nullableText(source.estimateId),
    warehouseId: text(source.warehouseId),
    rentalItemId: text(source.rentalItemId),
    origin: oneOf(source.origin, ["ESTIMATE", "DIRECT_REPAIR"] as const),
    kind: oneOf(source.kind, ["PRIMARY", "REWORK"] as const),
    executionState: oneOf(source.executionState, [
      "DRAFT",
      "QUEUED",
      "IN_PROGRESS",
      "COMPLETED",
      "CANCELLED",
    ] as const),
    acceptanceState: oneOf(source.acceptanceState, [
      "NOT_READY",
      "PENDING",
      "IN_REWORK",
      "ACCEPTED",
      "WRITTEN_OFF",
    ] as const),
    version: integer(source.version),
    dispatchDate: text(source.dispatchDate),
    sourceParty: nullableText(source.sourceParty),
    plan: {
      repairId: text(plan.repairId),
      repairVersion: integer(plan.repairVersion),
      stages: list(plan.stages).map((item) => {
        const stage = object(item)
        return {
          id: text(stage.id),
          kind: oneOf(stage.kind, STAGE_KINDS),
          order: integer(stage.order),
          state: oneOf(stage.state, [
            "PLANNED",
            "QUEUED",
            "IN_PROGRESS",
            "DONE",
            "CANCELLED",
          ] as const),
          routing: parseRouting(stage.routing),
          taskDeadline: nullableText(stage.taskDeadline),
          taskSync: parseTaskSync(stage.taskSync),
          completedAt: nullableText(stage.completedAt),
        }
      }),
    },
    lease:
      source.lease === null
        ? null
        : (() => {
            const lease = object(source.lease)
            return {
              leaseId: text(lease.leaseId),
              fencingToken: integer(lease.fencingToken),
              expiresAt: text(lease.expiresAt),
              reconciliationState: oneOf(lease.reconciliationState, [
                "NOT_ACQUIRED",
                "ACTIVE",
                "RELEASED",
                "RECONCILIATION_REQUIRED",
              ] as const),
            }
          })(),
    mediaReferences: list(source.mediaReferences).map(parseMediaReference),
    createdAt: text(source.createdAt),
    updatedAt: text(source.updatedAt),
    actor: parseActor(source.actor),
  }
}

async function request<T>(
  path: string,
  parser: (value: unknown) => T,
  init?: RequestInit
) {
  const token = await getMaintenanceAccessToken()
  return parser(
    await bearerRequest<unknown>(token, `${MAINTENANCE_API}${path}`, init)
  )
}

function queryPath(
  path: string,
  values: Record<string, string | number | undefined>
) {
  const endpoint = new URL(`${MAINTENANCE_API}${path}`)
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined) endpoint.searchParams.set(key, String(value))
  })
  return endpoint
}

export async function listMaintenanceCatalogVersions(
  warehouseId: string,
  lifecycle?: MaintenanceCatalogVersion["lifecycle"]
) {
  const token = await getMaintenanceAccessToken()
  const value = await bearerRequest<unknown>(
    token,
    queryPath("/catalog/versions", {
      warehouseId,
      lifecycle,
      page: 0,
      size: 200,
    })
  )
  const source = object(value)
  return {
    items: list(source.items).map(parseCatalogVersion),
    page: integer(source.page),
    size: integer(source.size),
    totalElements: integer(source.totalElements),
  } satisfies MaintenanceCatalogPage
}

export function listMaintenanceCatalogNodes(
  warehouseId: string,
  versionId: string
) {
  return request(
    `/catalog/versions/${encodeURIComponent(versionId)}/nodes?warehouseId=${encodeURIComponent(warehouseId)}`,
    (value) => list(value).map(parseCatalogNode)
  )
}

export function listMaintenanceCatalogLinks(
  warehouseId: string,
  versionId: string
) {
  return request(
    `/catalog/versions/${encodeURIComponent(versionId)}/links?warehouseId=${encodeURIComponent(warehouseId)}`,
    (value) => list(value).map(parseCatalogLink)
  )
}

export function replaceMaintenanceCatalogNodes(
  warehouseId: string,
  versionId: string,
  expectedVersion: number,
  nodes: unknown[]
) {
  return request(
    `/catalog/versions/${encodeURIComponent(versionId)}/nodes?warehouseId=${encodeURIComponent(warehouseId)}`,
    parseCatalogVersion,
    {
      method: "PUT",
      body: JSON.stringify({ expectedVersion, nodes }),
    }
  )
}

export function replaceMaintenanceCatalogLinks(
  warehouseId: string,
  versionId: string,
  expectedVersion: number,
  links: unknown[]
) {
  return request(
    `/catalog/versions/${encodeURIComponent(versionId)}/links?warehouseId=${encodeURIComponent(warehouseId)}`,
    parseCatalogVersion,
    {
      method: "PUT",
      body: JSON.stringify({ expectedVersion, links }),
    }
  )
}

export async function listMaintenanceEstimates(
  warehouseId: string,
  lifecycle: MaintenanceEstimate["lifecycle"]
) {
  const token = await getMaintenanceAccessToken()
  const value = await bearerRequest<unknown>(
    token,
    queryPath("/estimates", { warehouseId, lifecycle, page: 0, size: 200 })
  )
  const source = object(value)
  return {
    items: list(source.items).map(parseEstimate),
    page: integer(source.page),
    size: integer(source.size),
    totalElements: integer(source.totalElements),
  } satisfies MaintenanceEstimatePage
}

export function getMaintenanceEstimate(
  warehouseId: string,
  estimateId: string
) {
  return request(
    `/estimates/${encodeURIComponent(estimateId)}?warehouseId=${encodeURIComponent(warehouseId)}`,
    parseEstimate
  )
}

function jsonCommand(body: unknown, idempotencyKey?: string): RequestInit {
  return {
    method: "POST",
    headers: idempotencyKey ? { "Idempotency-Key": idempotencyKey } : undefined,
    body: JSON.stringify(body),
  }
}

export function createMaintenanceEstimate(
  idempotencyKey: string,
  body: unknown
) {
  return request("/estimates", parseEstimate, jsonCommand(body, idempotencyKey))
}

export function replaceMaintenanceEstimate(
  warehouseId: string,
  estimateId: string,
  body: unknown
) {
  return request(
    `/estimates/${encodeURIComponent(estimateId)}?warehouseId=${encodeURIComponent(warehouseId)}`,
    parseEstimate,
    { method: "PUT", body: JSON.stringify(body) }
  )
}

export function completeMaintenanceEstimate(
  warehouseId: string,
  estimateId: string,
  idempotencyKey: string,
  expectedVersion: number
) {
  return request(
    `/estimates/${encodeURIComponent(estimateId)}/complete?warehouseId=${encodeURIComponent(warehouseId)}`,
    (value) => {
      const source = object(value)
      return {
        estimate: parseEstimate(source.estimate),
        repair: source.repair === null ? null : parseRepair(source.repair),
        delivery: parseDelivery(source.delivery),
      } satisfies MaintenanceEstimateCommandResult
    },
    jsonCommand({ expectedVersion }, idempotencyKey)
  )
}

export function amendMaintenanceEstimate(
  warehouseId: string,
  estimateId: string,
  idempotencyKey: string,
  body: unknown
) {
  return request(
    `/estimates/${encodeURIComponent(estimateId)}/amendments?warehouseId=${encodeURIComponent(warehouseId)}`,
    (value) => {
      const source = object(value)
      return {
        estimate: parseEstimate(source.estimate),
        repair: source.repair === null ? null : parseRepair(source.repair),
        delivery: parseDelivery(source.delivery),
      } satisfies MaintenanceEstimateCommandResult
    },
    jsonCommand(body, idempotencyKey)
  )
}

export async function listMaintenanceRepairs(
  warehouseId: string,
  filters: {
    executionState?: MaintenanceRepair["executionState"]
    acceptanceState?: MaintenanceRepair["acceptanceState"]
  } = {}
) {
  const token = await getMaintenanceAccessToken()
  const value = await bearerRequest<unknown>(
    token,
    queryPath("/repairs", { warehouseId, ...filters, page: 0, size: 200 })
  )
  const source = object(value)
  return {
    items: list(source.items).map(parseRepair),
    page: integer(source.page),
    size: integer(source.size),
    totalElements: integer(source.totalElements),
  } satisfies MaintenanceRepairPage
}

export function getMaintenanceRepair(warehouseId: string, repairId: string) {
  return request(
    `/repairs/${encodeURIComponent(repairId)}?warehouseId=${encodeURIComponent(warehouseId)}`,
    parseRepair
  )
}

export function createDirectMaintenanceRepair(
  idempotencyKey: string,
  body: unknown
) {
  return request(
    "/repairs/direct",
    parseRepair,
    jsonCommand(body, idempotencyKey)
  )
}

export function replaceMaintenanceRepairPlan(
  warehouseId: string,
  repairId: string,
  body: unknown
) {
  return request(
    `/repairs/${encodeURIComponent(repairId)}/plan?warehouseId=${encodeURIComponent(warehouseId)}`,
    parseRepair,
    { method: "PUT", body: JSON.stringify(body) }
  )
}

function parseRepairCommandResult(
  value: unknown
): MaintenanceRepairCommandResult {
  const source = object(value)
  return {
    repair: parseRepair(source.repair),
    affectedSourceRepairs: list(source.affectedSourceRepairs).map(parseRepair),
    delivery: parseDelivery(source.delivery),
  }
}

export function queueMaintenanceRepair(
  warehouseId: string,
  repairId: string,
  idempotencyKey: string,
  expectedVersion: number
) {
  return request(
    `/repairs/${encodeURIComponent(repairId)}/plan?warehouseId=${encodeURIComponent(warehouseId)}`,
    parseRepairCommandResult,
    jsonCommand({ expectedVersion }, idempotencyKey)
  )
}

export function createMaintenanceRework(
  warehouseId: string,
  repairId: string,
  idempotencyKey: string,
  body: unknown
) {
  return request(
    `/repairs/${encodeURIComponent(repairId)}/reworks?warehouseId=${encodeURIComponent(warehouseId)}`,
    parseRepair,
    jsonCommand(body, idempotencyKey)
  )
}

export function acceptMaintenanceRepair(
  warehouseId: string,
  repairId: string,
  idempotencyKey: string,
  body: unknown
) {
  return request(
    `/repairs/${encodeURIComponent(repairId)}/accept?warehouseId=${encodeURIComponent(warehouseId)}`,
    parseRepairCommandResult,
    jsonCommand(body, idempotencyKey)
  )
}

export function writeOffMaintenanceRepair(
  warehouseId: string,
  repairId: string,
  idempotencyKey: string,
  body: unknown
) {
  return request(
    `/repairs/${encodeURIComponent(repairId)}/write-off?warehouseId=${encodeURIComponent(warehouseId)}`,
    parseRepairCommandResult,
    jsonCommand(body, idempotencyKey)
  )
}

export async function listMaintenanceAcceptanceProjection(
  warehouseId: string,
  state: MaintenanceRepair["acceptanceState"] = "PENDING"
) {
  const token = await getMaintenanceAccessToken()
  const value = await bearerRequest<unknown>(
    token,
    queryPath("/acceptance", { warehouseId, state, page: 0, size: 200 })
  )
  const source = object(value)
  return list(source.items).map((item): MaintenanceAcceptanceProjection => {
    const projection = object(item)
    return {
      repairId: text(projection.repairId),
      rootRepairId: text(projection.rootRepairId),
      warehouseId: text(projection.warehouseId),
      rentalItemId: text(projection.rentalItemId),
      executionState: oneOf(projection.executionState, [
        "DRAFT",
        "QUEUED",
        "IN_PROGRESS",
        "COMPLETED",
        "CANCELLED",
      ] as const),
      acceptanceState: oneOf(projection.acceptanceState, [
        "NOT_READY",
        "PENDING",
        "IN_REWORK",
        "ACCEPTED",
        "WRITTEN_OFF",
      ] as const),
      repairVersion: integer(projection.repairVersion),
      readyAt: text(projection.readyAt),
    }
  })
}

export async function listMaintenanceWriteOffProjection(warehouseId: string) {
  const token = await getMaintenanceAccessToken()
  const value = await bearerRequest<unknown>(
    token,
    queryPath("/write-offs", { warehouseId, page: 0, size: 200 })
  )
  const source = object(value)
  return list(source.items).map((item): MaintenanceWriteOffProjection => {
    const projection = object(item)
    return {
      repairId: text(projection.repairId),
      rootRepairId: text(projection.rootRepairId),
      warehouseId: text(projection.warehouseId),
      rentalItemId: text(projection.rentalItemId),
      repairVersion: integer(projection.repairVersion),
      writtenOffAt: text(projection.writtenOffAt),
      actor: parseActor(projection.actor),
    }
  })
}
