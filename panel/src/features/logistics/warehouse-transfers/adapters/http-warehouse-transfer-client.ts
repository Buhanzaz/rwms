import {
  TRANSFER_DOCUMENT_STATES,
  TRANSFER_FURNITURE_READINESS_STATES,
  TRANSFER_FURNITURE_TASK_STATES,
  TRANSFER_LINE_STATES,
  type TransferDocument,
  type TransferArrivalPreflight,
  type TransferDocumentState,
  type TransferFurnitureReadiness,
  type TransferFurnitureReadinessState,
  type TransferFurnitureTaskState,
  type TransferFurnitureTaskStatus,
  type TransferLine,
  type TransferLineState,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import type {
  TransferArrivalCommand,
  TransferCreateCommand,
  TransferLineCommand,
  TransferReconcileCommand,
  TransferVersionedCommand,
  WarehouseTransferClient,
} from "@/features/logistics/warehouse-transfers/ports/warehouse-transfer-client"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

type JsonObject = Record<string, unknown>

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const DOCUMENT_KEYS = [
  "id",
  "version",
  "documentType",
  "state",
  "warehouseId",
  "destinationWarehouseId",
  "partySnapshot",
  "driverSnapshot",
  "clientId",
  "equipmentMovementTaskId",
  "scheduledDate",
  "scheduledAt",
  "rentalOrderId",
  "lines",
  "createdAt",
  "updatedAt",
] as const
const LINE_KEYS = [
  "id",
  "version",
  "lineNumber",
  "assetId",
  "assetVersion",
  "state",
  "tenantSnapshot",
  "rentalOrderId",
] as const
const FURNITURE_READINESS_KEYS = [
  "transferId",
  "transferVersion",
  "state",
  "tasks",
] as const
const FURNITURE_TASK_KEYS = [
  "rentalItemId",
  "unitNumber",
  "taskId",
  "externalTaskId",
  "taskBoardTaskId",
  "taskState",
  "lineCount",
] as const
const ARRIVAL_PREFLIGHT_KEYS = [
  "transferId",
  "lineId",
  "activeRepairId",
  "priorityRequired",
  "movementToShipmentAvailable",
  "missingQueueDefinitionIds",
] as const

function invalidResponse(): never {
  throw new Error("Сервис логистики вернул некорректный ответ перемещения.")
}

function object(value: unknown, keys: readonly string[]): JsonObject {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    invalidResponse()
  }
  const source = value as JsonObject
  const actualKeys = Object.keys(source)
  if (
    actualKeys.length !== keys.length ||
    !keys.every((key) => Object.hasOwn(source, key))
  ) {
    invalidResponse()
  }
  return source
}

function list(value: unknown): unknown[] {
  if (!Array.isArray(value)) invalidResponse()
  return value
}

function text(value: unknown): string {
  if (typeof value !== "string") invalidResponse()
  return value
}

function nonBlankText(value: unknown): string {
  const candidate = text(value)
  if (!candidate.trim()) invalidResponse()
  return candidate
}

function uuid(value: unknown): string {
  const candidate = text(value)
  if (!UUID_PATTERN.test(candidate)) invalidResponse()
  return candidate
}

function nullableText(value: unknown): string | null {
  return value === null ? null : text(value)
}

function nullableUuid(value: unknown): string | null {
  return value === null ? null : uuid(value)
}

function integer(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) invalidResponse()
  return value as number
}

function flag(value: unknown): boolean {
  if (typeof value !== "boolean") invalidResponse()
  return value
}

function timestamp(value: unknown): string {
  const candidate = text(value)
  if (!candidate || !Number.isFinite(Date.parse(candidate))) invalidResponse()
  return candidate
}

function calendarDate(value: unknown): string {
  const candidate = text(value)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(candidate)) invalidResponse()
  const date = new Date(`${candidate}T00:00:00Z`)
  if (
    !Number.isFinite(date.getTime()) ||
    date.toISOString().slice(0, 10) !== candidate
  ) {
    invalidResponse()
  }
  return candidate
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
  const candidate = text(value)
  if (!allowed.includes(candidate as T)) invalidResponse()
  return candidate as T
}

function transferLine(value: unknown): TransferLine {
  const source = object(value, LINE_KEYS)
  const lineNumber = integer(source.lineNumber)
  if (lineNumber < 1) invalidResponse()
  return {
    id: uuid(source.id),
    version: integer(source.version),
    lineNumber,
    assetId: uuid(source.assetId),
    assetVersion: integer(source.assetVersion),
    state: oneOf<TransferLineState>(source.state, TRANSFER_LINE_STATES),
    tenantSnapshot: nullableText(source.tenantSnapshot),
    rentalOrderId: (() => {
      if (nullableUuid(source.rentalOrderId) !== null) invalidResponse()
      return null
    })(),
  }
}

function transferFurnitureTaskStatus(
  value: unknown
): TransferFurnitureTaskStatus {
  const source = object(value, FURNITURE_TASK_KEYS)
  const lineCount = integer(source.lineCount)
  if (lineCount < 1) invalidResponse()
  return {
    rentalItemId: uuid(source.rentalItemId),
    unitNumber: nonBlankText(source.unitNumber),
    taskId: uuid(source.taskId),
    externalTaskId: uuid(source.externalTaskId),
    taskBoardTaskId: nullableUuid(source.taskBoardTaskId),
    taskState: oneOf<TransferFurnitureTaskState>(
      source.taskState,
      TRANSFER_FURNITURE_TASK_STATES
    ),
    lineCount,
  }
}

export function parseTransferFurnitureReadiness(
  value: unknown
): TransferFurnitureReadiness {
  const source = object(value, FURNITURE_READINESS_KEYS)
  return {
    transferId: uuid(source.transferId),
    transferVersion: integer(source.transferVersion),
    state: oneOf<TransferFurnitureReadinessState>(
      source.state,
      TRANSFER_FURNITURE_READINESS_STATES
    ),
    tasks: list(source.tasks).map(transferFurnitureTaskStatus),
  }
}

export function parseTransferArrivalPreflight(
  value: unknown
): TransferArrivalPreflight {
  const source = object(value, ARRIVAL_PREFLIGHT_KEYS)
  const activeRepairId = nullableUuid(source.activeRepairId)
  const priorityRequired = flag(source.priorityRequired)
  const movementToShipmentAvailable = flag(source.movementToShipmentAvailable)
  const missingQueueDefinitionIds = list(source.missingQueueDefinitionIds).map(
    uuid
  )
  if (
    new Set(missingQueueDefinitionIds).size !==
      missingQueueDefinitionIds.length ||
    (activeRepairId === null &&
      (priorityRequired ||
        movementToShipmentAvailable ||
        missingQueueDefinitionIds.length > 0)) ||
    (activeRepairId !== null && !priorityRequired)
  ) {
    invalidResponse()
  }
  return {
    transferId: uuid(source.transferId),
    lineId: uuid(source.lineId),
    activeRepairId,
    priorityRequired,
    movementToShipmentAvailable,
    missingQueueDefinitionIds,
  }
}

export function parseTransferDocument(value: unknown): TransferDocument {
  const source = object(value, DOCUMENT_KEYS)
  const warehouseId = uuid(source.warehouseId)
  const destinationWarehouseId = uuid(source.destinationWarehouseId)
  const lines = list(source.lines).map(transferLine)
  if (
    source.documentType !== "TRANSFER" ||
    source.partySnapshot !== null ||
    nullableUuid(source.clientId) !== null ||
    warehouseId === destinationWarehouseId ||
    lines.length === 0
  ) {
    invalidResponse()
  }

  return {
    id: uuid(source.id),
    version: integer(source.version),
    documentType: "TRANSFER",
    state: oneOf<TransferDocumentState>(source.state, TRANSFER_DOCUMENT_STATES),
    warehouseId,
    destinationWarehouseId,
    partySnapshot: null,
    driverSnapshot: nullableText(source.driverSnapshot),
    clientId: null,
    equipmentMovementTaskId: nullableUuid(source.equipmentMovementTaskId),
    scheduledDate: (() => {
      return calendarDate(source.scheduledDate)
    })(),
    scheduledAt: (() => {
      if (source.scheduledAt !== null) invalidResponse()
      return null
    })(),
    rentalOrderId: (() => {
      if (nullableUuid(source.rentalOrderId) !== null) invalidResponse()
      return null
    })(),
    lines,
    createdAt: timestamp(source.createdAt),
    updatedAt: timestamp(source.updatedAt),
  }
}

function transfersEndpoint(path = "") {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/transfers${path}`
}

function commandHeaders(idempotencyKey: string) {
  return { "Idempotency-Key": idempotencyKey }
}

async function parsedRequest(
  accessToken: string,
  input: string,
  init?: RequestInit
) {
  return parseTransferDocument(
    await bearerRequest<unknown>(accessToken, input, init)
  )
}

function lineCommandPath(
  input: Pick<
    TransferLineCommand,
    "documentId" | "lineId" | "expectedVersion" | "expectedLineVersion"
  >,
  action: string
) {
  const documentId = encodeURIComponent(input.documentId)
  const lineId = encodeURIComponent(input.lineId)
  return `/${documentId}/lines/${lineId}/${action}?expectedVersion=${input.expectedVersion}&expectedLineVersion=${input.expectedLineVersion}`
}

export class HttpWarehouseTransferClient implements WarehouseTransferClient {
  async list(accessToken: string, warehouseId: string) {
    const response = await bearerRequest<unknown>(
      accessToken,
      transfersEndpoint(`?warehouseId=${encodeURIComponent(warehouseId)}`)
    )
    return list(response).map(parseTransferDocument)
  }

  get(accessToken: string, documentId: string) {
    return parsedRequest(
      accessToken,
      transfersEndpoint(`/${encodeURIComponent(documentId)}`)
    )
  }

  async getFurnitureReadiness(accessToken: string, documentId: string) {
    const readiness = parseTransferFurnitureReadiness(
      await bearerRequest<unknown>(
        accessToken,
        transfersEndpoint(
          `/${encodeURIComponent(documentId)}/furniture-readiness`
        )
      )
    )
    if (readiness.transferId !== documentId) invalidResponse()
    return readiness
  }

  async getArrivalPreflight(
    input: Omit<TransferLineCommand, "idempotencyKey">
  ) {
    const preflight = parseTransferArrivalPreflight(
      await bearerRequest<unknown>(
        input.accessToken,
        transfersEndpoint(lineCommandPath(input, "arrival-preflight"))
      )
    )
    if (
      preflight.transferId !== input.documentId ||
      preflight.lineId !== input.lineId
    ) {
      invalidResponse()
    }
    return preflight
  }

  create(input: TransferCreateCommand) {
    return parsedRequest(input.accessToken, transfersEndpoint(), {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify({
        warehouseId: input.warehouseId,
        destinationWarehouseId: input.destinationWarehouseId,
        driverSnapshot: input.driverSnapshot,
        scheduledDate: input.scheduledDate,
        lines: input.lines,
        furnitureReplacements: input.furnitureReplacements,
      }),
    })
  }

  depart(input: TransferLineCommand) {
    return parsedRequest(
      input.accessToken,
      transfersEndpoint(lineCommandPath(input, "depart")),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
      }
    )
  }

  arrive(input: TransferArrivalCommand) {
    return parsedRequest(
      input.accessToken,
      transfersEndpoint(lineCommandPath(input, "arrive")),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
        body: JSON.stringify({
          references: input.references,
          priority: input.priority,
          movementToShipment: input.movementToShipment,
        }),
      }
    )
  }

  cancel(input: TransferVersionedCommand) {
    return parsedRequest(
      input.accessToken,
      transfersEndpoint(
        `/${encodeURIComponent(input.documentId)}/cancel?expectedVersion=${input.expectedVersion}`
      ),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
      }
    )
  }

  reconcile(input: TransferReconcileCommand) {
    return parsedRequest(
      input.accessToken,
      transfersEndpoint(
        `/${encodeURIComponent(input.documentId)}/reconcile?expectedVersion=${input.expectedVersion}`
      ),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
        body: JSON.stringify({ reason: input.reason }),
      }
    )
  }
}
