import {
  RETURN_DOCUMENT_STATES,
  type ReturnDocumentState,
} from "@/features/logistics/returns/model"
import {
  SHIPMENT_DOCUMENT_STATES,
  type ShipmentDocumentState,
  type InventoryShipmentFurniture,
} from "@/features/logistics/shipments/model"
import { bearerRequest, invalidApiResponseError } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type LogisticsHistoryReference = {
  documentId: string
  warehouseId: string
  documentType: "RETURN" | "SHIPMENT"
}
export type HistoryEquipment = { equipmentId: string; quantity: number }
export type LogisticsHistoryLine = {
  lineId: string
  assetId: string
  contentsBeforeOperation: { contents: HistoryEquipment[] } | null
  contentsAfterRegistration: { contents: HistoryEquipment[] } | null
  returnAcceptance: {
    equipmentConfirmed: true
    additionalEquipment: HistoryEquipment[]
  } | null
  inventoryShipmentFurniture: InventoryShipmentFurniture[] | null
}
export type LogisticsHistoryEvent = {
  eventId: string
  aggregateVersion: number
  eventType: string
  occurredAt: string | null
  recordedAt: string
  baseline: boolean
  recordedActor: { subjectId: string; principalType: string } | null
  state: ReturnDocumentState | ShipmentDocumentState
  resultCode: string | null
}
export type LogisticsDocumentHistory = LogisticsHistoryReference & {
  documentVersion: number
  lines: LogisticsHistoryLine[]
  events: LogisticsHistoryEvent[]
  nextAfterVersion: number | null
}

const uuidPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
function invalid(): never {
  throw invalidApiResponseError("Сервис вернул некорректную историю документа.")
}
function record(value: unknown) {
  if (!value || typeof value !== "object" || Array.isArray(value)) invalid()
  return value as Record<string, unknown>
}
function list(value: unknown): unknown[] {
  if (!Array.isArray(value)) invalid()
  return value
}
function string(value: unknown): string {
  if (typeof value !== "string") invalid()
  return value
}
function uuid(value: unknown) {
  const result = string(value)
  if (!uuidPattern.test(result)) invalid()
  return result
}
function integer(value: unknown, minimum = 0): number {
  if (
    typeof value !== "number" ||
    !Number.isSafeInteger(value) ||
    value < minimum
  )
    invalid()
  return value
}
function instant(value: unknown) {
  const result = string(value)
  if (!Number.isFinite(Date.parse(result))) invalid()
  return result
}
function equipment(value: unknown, minimum = 0): HistoryEquipment {
  const row = record(value)
  return {
    equipmentId: uuid(row.equipmentId),
    quantity: integer(row.quantity, minimum),
  }
}
function contents(value: unknown) {
  return value === null
    ? null
    : { contents: list(record(value).contents).map((item) => equipment(item)) }
}
function line(value: unknown): LogisticsHistoryLine {
  const row = record(value)
  const acceptance =
    row.returnAcceptance === null ? null : record(row.returnAcceptance)
  if (acceptance && acceptance.equipmentConfirmed !== true) invalid()
  return {
    lineId: uuid(row.lineId),
    assetId: uuid(row.assetId),
    contentsBeforeOperation: contents(row.contentsBeforeOperation),
    contentsAfterRegistration: contents(row.contentsAfterRegistration),
    returnAcceptance:
      acceptance === null
        ? null
        : {
            equipmentConfirmed: true,
            additionalEquipment: list(acceptance.additionalEquipment).map(
              (item) => equipment(item, 1)
            ),
          },
    inventoryShipmentFurniture:
      row.inventoryShipmentFurniture === null
        ? null
        : list(row.inventoryShipmentFurniture).map((item) => ({
            ...equipment(item, 1),
            catalogVersion: integer(record(item).catalogVersion),
          })),
  }
}
function event(
  value: unknown,
  kind: LogisticsHistoryReference["documentType"]
): LogisticsHistoryEvent {
  const row = record(value)
  const actor = row.recordedActor === null ? null : record(row.recordedActor)
  const state = string(row.state)
  const allowed: readonly string[] =
    kind === "RETURN" ? RETURN_DOCUMENT_STATES : SHIPMENT_DOCUMENT_STATES
  const eventType = string(row.eventType)
  if (
    !allowed.includes(state) ||
    !eventType.startsWith(`logistics.${kind.toLowerCase()}.`) ||
    typeof row.baseline !== "boolean"
  )
    invalid()
  const occurredAt = row.occurredAt === null ? null : instant(row.occurredAt)
  if (row.baseline && occurredAt !== null) invalid()
  return {
    eventId: uuid(row.eventId),
    aggregateVersion: integer(row.aggregateVersion),
    eventType,
    occurredAt,
    recordedAt: instant(row.recordedAt),
    baseline: row.baseline,
    recordedActor:
      actor === null
        ? null
        : {
            subjectId: uuid(actor.subjectId),
            principalType: string(actor.principalType),
          },
    state: state as LogisticsHistoryEvent["state"],
    resultCode: row.resultCode === null ? null : string(row.resultCode),
  }
}

/** Owner snapshots are stage-specific evidence; missing data is never turned into an empty manifest. */
export async function getLogisticsDocumentHistory(
  accessToken: string,
  reference: LogisticsHistoryReference,
  afterVersion = -1,
  signal?: AbortSignal
): Promise<LogisticsDocumentHistory> {
  const url = new URL(
    `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/${reference.documentType === "RETURN" ? "returns" : "shipments"}/${encodeURIComponent(reference.documentId)}/history`
  )
  url.searchParams.set("afterVersion", String(afterVersion))
  url.searchParams.set("size", "50")
  const body = record(
    await bearerRequest<unknown>(accessToken, url, { signal })
  )
  if (
    body.documentId !== reference.documentId ||
    body.warehouseId !== reference.warehouseId ||
    body.documentType !== reference.documentType
  )
    invalid()
  const documentVersion = integer(body.documentVersion)
  const lines = list(body.lines).map(line)
  const events = list(body.events).map((value) =>
    event(value, reference.documentType)
  )
  const nextAfterVersion =
    body.nextAfterVersion === null ? null : integer(body.nextAfterVersion)
  if (
    lines.length > 100 ||
    events.length > 50 ||
    new Set(lines.map((row) => row.lineId)).size !== lines.length ||
    new Set(events.map((row) => row.eventId)).size !== events.length
  )
    invalid()
  let previous = afterVersion
  for (const entry of events) {
    if (
      entry.aggregateVersion <= previous ||
      entry.aggregateVersion > documentVersion
    )
      invalid()
    previous = entry.aggregateVersion
  }
  if (
    nextAfterVersion !== null &&
    (events.length === 0 || nextAfterVersion !== previous)
  )
    invalid()
  return { ...reference, documentVersion, lines, events, nextAfterVersion }
}
