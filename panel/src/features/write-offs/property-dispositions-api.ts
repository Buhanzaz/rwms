import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type PropertyDispositionAssetKind = "CABIN" | "EQUIPMENT"
export type PropertyDispositionKind = "WRITE_OFF" | "LOSS"
export type PropertyDispositionSource =
  "MANUAL" | "REPAIR" | "ESTIMATE" | "INVENTORY" | "UNACCOUNTED"
export type PropertyDispositionState =
  | "PENDING_APPROVAL"
  | "APPROVED"
  | "MOVEMENT_PENDING"
  | "EFFECT_PENDING"
  | "EFFECTIVE"
  | "REJECTED"
  | "QUARANTINED"
export type PropertyDispositionAssetEffectState =
  "NOT_STARTED" | "PENDING" | "APPLIED" | "NOT_REQUIRED" | "QUARANTINED"

export type CabinContentsDispositionMode =
  "MOVE_SELECTED_TO_STOCK" | "DISPOSE_WITH_CABIN"

export type CabinContentsDispositionLineInput = {
  equipmentId: string
  expectedBalanceVersion: number
  moveToStockQuantity: number
}

export type CabinContentsDispositionPlanInput = {
  mode: CabinContentsDispositionMode
  lines: CabinContentsDispositionLineInput[]
}

export type CabinContentsDispositionLine = CabinContentsDispositionLineInput & {
  equipmentName: string
  equipmentFormat: string | null
  currentQuantity: number
  disposeQuantity: number
}

export type CabinContentsDispositionPlan = {
  mode: CabinContentsDispositionMode
  lines: CabinContentsDispositionLine[]
}

export type PropertyDispositionActor = {
  actorId: string
  actorType: "USER" | "SERVICE"
}

export type PropertyDispositionRepairChainEntry = {
  repairId: string
  repairVersion: number
}

export type PropertyDispositionDecision = {
  id: string
  version: number
  recoveryVersion: number
  warehouseId: string
  assetKind: PropertyDispositionAssetKind
  assetId: string
  assetDisplayName: string
  disposition: PropertyDispositionKind
  source: PropertyDispositionSource
  state: PropertyDispositionState
  reason: string
  evidenceLink: string | null
  quantity: number | null
  expectedAssetVersion: number | null
  expectedSourceBalanceVersion: number | null
  contentsPlan: CabinContentsDispositionPlan | null
  rootRepairId: string | null
  repairChain: PropertyDispositionRepairChainEntry[]
  inventorySessionId: string | null
  findingId: string | null
  assetEffectState: PropertyDispositionAssetEffectState
  movementTaskId: string | null
  failureCode: string | null
  failureDetail: string | null
  requestedBy: PropertyDispositionActor
  requestedAt: string
  reviewedBy: PropertyDispositionActor | null
  reviewedAt: string | null
  reviewComment: string | null
  effectiveAt: string | null
  updatedAt: string
}

export type PropertyDispositionPage = {
  items: PropertyDispositionDecision[]
  page: number
  size: number
  totalElements: number
}

export type CreateCabinPropertyDisposition = {
  warehouseId: string
  assetKind: "CABIN"
  assetId: string
  expectedAssetVersion: number
  disposition: PropertyDispositionKind
  reason: string
  evidenceLink: string | null
  contentsPlan: CabinContentsDispositionPlanInput | null
}

export type CreateEquipmentPropertyDisposition = {
  warehouseId: string
  assetKind: "EQUIPMENT"
  assetId: string
  expectedAssetVersion: number
  expectedSourceBalanceVersion: number
  quantity: number
  disposition: PropertyDispositionKind
  reason: string
  evidenceLink: string | null
}

export type CreatePropertyDisposition =
  CreateCabinPropertyDisposition | CreateEquipmentPropertyDisposition

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const ASSET_KINDS = ["CABIN", "EQUIPMENT"] as const
const DISPOSITION_KINDS = ["WRITE_OFF", "LOSS"] as const
const SOURCES = [
  "MANUAL",
  "REPAIR",
  "ESTIMATE",
  "INVENTORY",
  "UNACCOUNTED",
] as const
const STATES = [
  "PENDING_APPROVAL",
  "APPROVED",
  "MOVEMENT_PENDING",
  "EFFECT_PENDING",
  "EFFECTIVE",
  "REJECTED",
  "QUARANTINED",
] as const
const EFFECT_STATES = [
  "NOT_STARTED",
  "PENDING",
  "APPLIED",
  "NOT_REQUIRED",
  "QUARANTINED",
] as const
const CONTENTS_MODES = ["MOVE_SELECTED_TO_STOCK", "DISPOSE_WITH_CABIN"] as const

type JsonRecord = Record<string, unknown>

function maintenanceApiBaseUrl() {
  return `${getGatewayRuntimeConfig().maintenanceApiBaseUrl}/v1`
}

function requireAccessToken(accessToken: string | null) {
  if (!accessToken?.trim()) {
    throw new Error("Не получен токен доступа к сервису ремонта.")
  }
  return accessToken
}

function record(value: unknown): JsonRecord {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("Сервис ремонта вернул некорректное решение по имуществу.")
  }
  return value as JsonRecord
}

function string(value: unknown) {
  if (typeof value !== "string" || !value.trim()) {
    throw new Error("Сервис ремонта вернул некорректное решение по имуществу.")
  }
  return value
}

function uuid(value: unknown) {
  const parsed = string(value)
  if (!UUID_PATTERN.test(parsed)) {
    throw new Error("Сервис ремонта вернул некорректный идентификатор решения.")
  }
  return parsed
}

function integer(value: unknown, minimum = 0) {
  if (!Number.isSafeInteger(value) || (value as number) < minimum) {
    throw new Error("Сервис ремонта вернул некорректную версию решения.")
  }
  return value as number
}

function nullable<T>(value: unknown, parse: (candidate: unknown) => T) {
  return value === null ? null : parse(value)
}

function enumValue<T extends string>(value: unknown, allowed: readonly T[]) {
  const parsed = string(value)
  if (!allowed.includes(parsed as T)) {
    throw new Error("Сервис ремонта вернул неизвестное состояние решения.")
  }
  return parsed as T
}

function dateTime(value: unknown) {
  const parsed = string(value)
  if (!Number.isFinite(Date.parse(parsed))) {
    throw new Error("Сервис ремонта вернул некорректную дату решения.")
  }
  return parsed
}

function nullableString(value: unknown) {
  return value === null ? null : string(value)
}

function optionalNullableString(value: unknown) {
  return value === undefined || value === null ? null : string(value)
}

function actor(value: unknown): PropertyDispositionActor {
  const source = record(value)
  return {
    actorId: string(source.actorId),
    actorType: enumValue(source.actorType, ["USER", "SERVICE"] as const),
  }
}

function contentLine(value: unknown): CabinContentsDispositionLine {
  const source = record(value)
  return {
    equipmentId: uuid(source.equipmentId),
    equipmentName: string(source.equipmentName),
    equipmentFormat: nullableString(source.equipmentFormat),
    currentQuantity: integer(source.currentQuantity, 1),
    moveToStockQuantity: integer(source.moveToStockQuantity),
    disposeQuantity: integer(source.disposeQuantity),
    expectedBalanceVersion: integer(source.expectedBalanceVersion),
  }
}

function contentsPlan(value: unknown): CabinContentsDispositionPlan {
  const source = record(value)
  if (!Array.isArray(source.lines) || source.lines.length === 0) {
    throw new Error("Сервис ремонта вернул неполный план содержимого бытовки.")
  }
  return {
    mode: enumValue(source.mode, CONTENTS_MODES),
    lines: source.lines.map(contentLine),
  }
}

function repairChainEntry(value: unknown): PropertyDispositionRepairChainEntry {
  const source = record(value)
  return {
    repairId: uuid(source.repairId),
    repairVersion: integer(source.repairVersion),
  }
}

export function parsePropertyDispositionDecision(
  value: unknown
): PropertyDispositionDecision {
  const source = record(value)
  if (!Array.isArray(source.repairChain)) {
    throw new Error("Сервис ремонта вернул неполную цепочку решения.")
  }
  return {
    id: uuid(source.id),
    version: integer(source.version),
    recoveryVersion: integer(source.recoveryVersion),
    warehouseId: uuid(source.warehouseId),
    assetKind: enumValue(source.assetKind, ASSET_KINDS),
    assetId: uuid(source.assetId),
    assetDisplayName: string(source.assetDisplayName),
    disposition: enumValue(source.disposition, DISPOSITION_KINDS),
    source: enumValue(source.source, SOURCES),
    state: enumValue(source.state, STATES),
    reason: string(source.reason),
    evidenceLink: optionalNullableString(source.evidenceLink),
    quantity: nullable(source.quantity, (candidate) => integer(candidate, 1)),
    expectedAssetVersion: nullable(source.expectedAssetVersion, integer),
    expectedSourceBalanceVersion: nullable(
      source.expectedSourceBalanceVersion,
      integer
    ),
    contentsPlan: nullable(source.contentsPlan, contentsPlan),
    rootRepairId: nullable(source.rootRepairId, uuid),
    repairChain: source.repairChain.map(repairChainEntry),
    inventorySessionId: nullable(source.inventorySessionId, uuid),
    findingId: nullable(source.findingId, uuid),
    assetEffectState: enumValue(source.assetEffectState, EFFECT_STATES),
    movementTaskId: nullable(source.movementTaskId, uuid),
    failureCode: nullableString(source.failureCode),
    failureDetail: nullableString(source.failureDetail),
    requestedBy: actor(source.requestedBy),
    requestedAt: dateTime(source.requestedAt),
    reviewedBy: nullable(source.reviewedBy, actor),
    reviewedAt: nullable(source.reviewedAt, dateTime),
    reviewComment: nullableString(source.reviewComment),
    effectiveAt: nullable(source.effectiveAt, dateTime),
    updatedAt: dateTime(source.updatedAt),
  }
}

function parsePage(value: unknown): PropertyDispositionPage {
  const source = record(value)
  if (!Array.isArray(source.items)) {
    throw new Error("Сервис ремонта вернул некорректную страницу решений.")
  }
  return {
    items: source.items.map(parsePropertyDispositionDecision),
    page: integer(source.page),
    size: integer(source.size, 1),
    totalElements: integer(source.totalElements),
  }
}

export function propertyDispositionListQueryKey(params: {
  warehouseId: string
  disposition: PropertyDispositionKind
  page: number
  size: number
  state: PropertyDispositionState | null
}) {
  return [
    "property-dispositions",
    params.warehouseId,
    params.disposition,
    params.page,
    params.size,
    params.state,
  ] as const
}

export async function listPropertyDispositions(params: {
  accessToken: string | null
  warehouseId: string
  disposition: PropertyDispositionKind
  page: number
  size: number
  state: PropertyDispositionState | null
}) {
  const resource = params.disposition === "WRITE_OFF" ? "write-offs" : "losses"
  const endpoint = new URL(`${maintenanceApiBaseUrl()}/${resource}`)
  endpoint.searchParams.set("warehouseId", params.warehouseId)
  endpoint.searchParams.set("page", String(params.page))
  endpoint.searchParams.set("size", String(params.size))
  if (params.state) endpoint.searchParams.set("state", params.state)
  return parsePage(
    await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      endpoint
    )
  )
}

export async function getPropertyDisposition(params: {
  accessToken: string | null
  warehouseId: string
  decisionId: string
}) {
  const endpoint = new URL(
    `${maintenanceApiBaseUrl()}/dispositions/${encodeURIComponent(params.decisionId)}`
  )
  endpoint.searchParams.set("warehouseId", params.warehouseId)
  return parsePropertyDispositionDecision(
    await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      endpoint
    )
  )
}

export async function createPropertyDisposition(params: {
  accessToken: string | null
  idempotencyKey: string
  request: CreatePropertyDisposition
}) {
  if (!UUID_PATTERN.test(params.idempotencyKey)) {
    throw new Error("Не удалось подготовить безопасный ключ команды решения.")
  }
  return parsePropertyDispositionDecision(
    await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      `${maintenanceApiBaseUrl()}/dispositions`,
      {
        method: "POST",
        headers: { "Idempotency-Key": params.idempotencyKey },
        body: JSON.stringify(params.request),
      }
    )
  )
}

export async function writeOffRepairDisposition(params: {
  accessToken: string
  warehouseId: string
  repairId: string
  expectedVersion: number
  reason: string
  comment: string | null
  contentsPlan: CabinContentsDispositionPlanInput | null
  idempotencyKey: string
}) {
  const endpoint = new URL(
    `${maintenanceApiBaseUrl()}/repairs/${encodeURIComponent(params.repairId)}/write-off`
  )
  endpoint.searchParams.set("warehouseId", params.warehouseId)
  return parsePropertyDispositionDecision(
    await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      endpoint,
      {
        method: "POST",
        headers: { "Idempotency-Key": params.idempotencyKey },
        body: JSON.stringify({
          expectedVersion: params.expectedVersion,
          reason: params.reason,
          comment: params.comment,
          contentsPlan: params.contentsPlan,
        }),
      }
    )
  )
}

async function decisionCommand(params: {
  accessToken: string | null
  warehouseId: string
  decisionId: string
  action: "approve" | "reject" | "recovery"
  body: unknown
}) {
  const endpoint = new URL(
    `${maintenanceApiBaseUrl()}/dispositions/${encodeURIComponent(params.decisionId)}/${params.action}`
  )
  endpoint.searchParams.set("warehouseId", params.warehouseId)
  return parsePropertyDispositionDecision(
    await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      endpoint,
      {
        method: "POST",
        body: JSON.stringify(params.body),
      }
    )
  )
}

export function approvePropertyDisposition(params: {
  accessToken: string | null
  warehouseId: string
  decisionId: string
  expectedVersion: number
  comment: string | null
}) {
  return decisionCommand({
    ...params,
    action: "approve",
    body: { expectedVersion: params.expectedVersion, comment: params.comment },
  })
}

export function rejectPropertyDisposition(params: {
  accessToken: string | null
  warehouseId: string
  decisionId: string
  expectedVersion: number
  reason: string
}) {
  return decisionCommand({
    ...params,
    action: "reject",
    body: { expectedVersion: params.expectedVersion, reason: params.reason },
  })
}

export function recoverPropertyDisposition(params: {
  accessToken: string | null
  warehouseId: string
  decisionId: string
  expectedVersion: number
  expectedRecoveryVersion: number
  reason: string
}) {
  return decisionCommand({
    ...params,
    action: "recovery",
    body: {
      expectedVersion: params.expectedVersion,
      expectedRecoveryVersion: params.expectedRecoveryVersion,
      reason: params.reason,
    },
  })
}

export function createPropertyDispositionIdempotencyKey() {
  if (
    typeof crypto === "undefined" ||
    typeof crypto.randomUUID !== "function"
  ) {
    throw new Error("Браузер не поддерживает безопасные ключи команд.")
  }
  return crypto.randomUUID()
}
