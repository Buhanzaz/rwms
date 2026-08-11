import {
  RETURN_DOCUMENT_STATES,
  RETURN_LINE_STATES,
  type ReturnDocument,
  type ReturnDocumentState,
  type ReturnLine,
  type ReturnLineState,
} from "@/features/logistics/returns/model"
import type {
  ReturnAcceptUndamagedCommand,
  ReturnClient,
  ReturnCreateCommand,
  ReturnPickupCommand,
  StartReturnEstimatesCommand,
} from "@/features/logistics/returns/ports/return-client"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

type JsonObject = Record<string, unknown>

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const LOCAL_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/
function invalidResponse(): never {
  throw new Error("Сервис логистики вернул некорректный ответ.")
}

function object(value: unknown): JsonObject {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    invalidResponse()
  }
  return value as JsonObject
}

function list(value: unknown): unknown[] {
  if (!Array.isArray(value)) invalidResponse()
  return value
}

function text(value: unknown): string {
  if (typeof value !== "string") invalidResponse()
  return value
}

function uuid(value: unknown): string {
  const candidate = text(value)
  if (!UUID_PATTERN.test(candidate)) invalidResponse()
  return candidate
}

function nullableUuid(value: unknown): string | null {
  return value === null ? null : uuid(value)
}

function nullableText(value: unknown): string | null {
  return value === null ? null : text(value)
}

function integer(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) invalidResponse()
  return value as number
}

function timestamp(value: unknown): string {
  const candidate = text(value)
  if (!candidate || !Number.isFinite(Date.parse(candidate))) invalidResponse()
  return candidate
}

function localDate(value: unknown): string {
  const candidate = text(value)
  if (!LOCAL_DATE_PATTERN.test(candidate)) invalidResponse()
  const date = new Date(`${candidate}T00:00:00.000Z`)
  if (date.toISOString().slice(0, 10) !== candidate) invalidResponse()
  return candidate
}

function nullableLocalDate(value: unknown): string | null {
  return value === null ? null : localDate(value)
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
  const candidate = text(value)
  if (!allowed.includes(candidate as T)) invalidResponse()
  return candidate as T
}

function returnLine(value: unknown): ReturnLine {
  const source = object(value)
  const lineNumber = integer(source.lineNumber)
  if (lineNumber < 1) invalidResponse()
  return {
    id: uuid(source.id),
    version: integer(source.version),
    lineNumber,
    assetId: uuid(source.assetId),
    assetVersion: integer(source.assetVersion),
    state: oneOf<ReturnLineState>(source.state, RETURN_LINE_STATES),
    tenantSnapshot: nullableText(source.tenantSnapshot),
    rentalOrderId: nullableUuid(source.rentalOrderId),
  }
}

export function parseReturnDocument(value: unknown): ReturnDocument {
  const source = object(value)
  const lines = list(source.lines).map(returnLine)
  if (lines.length === 0) invalidResponse()
  if (source.documentType !== "RETURN") invalidResponse()
  if (nullableUuid(source.destinationWarehouseId) !== null) invalidResponse()

  return {
    id: uuid(source.id),
    version: integer(source.version),
    documentType: "RETURN",
    state: oneOf<ReturnDocumentState>(source.state, RETURN_DOCUMENT_STATES),
    warehouseId: uuid(source.warehouseId),
    destinationWarehouseId: null,
    partySnapshot: nullableText(source.partySnapshot),
    driverSnapshot: nullableText(source.driverSnapshot),
    driverWorkerId:
      source.driverWorkerId === undefined
        ? null
        : nullableUuid(source.driverWorkerId),
    clientId: nullableUuid(source.clientId),
    equipmentMovementTaskId: nullableUuid(source.equipmentMovementTaskId),
    scheduledDate: nullableLocalDate(source.scheduledDate),
    rentalOrderId: nullableUuid(source.rentalOrderId),
    ...(source.rentalShipmentId === undefined
      ? {}
      : { rentalShipmentId: nullableUuid(source.rentalShipmentId) }),
    lines,
    createdAt: timestamp(source.createdAt),
    updatedAt: timestamp(source.updatedAt),
  }
}

function returnsEndpoint(path = "") {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/returns${path}`
}

function commandHeaders(idempotencyKey: string) {
  return { "Idempotency-Key": idempotencyKey }
}

async function parsedRequest(
  accessToken: string,
  input: string,
  init?: RequestInit
) {
  return parseReturnDocument(
    await bearerRequest<unknown>(accessToken, input, init)
  )
}

export class HttpReturnClient implements ReturnClient {
  async list(accessToken: string, warehouseId: string) {
    const response = await bearerRequest<unknown>(
      accessToken,
      returnsEndpoint(`?warehouseId=${encodeURIComponent(warehouseId)}`)
    )
    return list(response).map(parseReturnDocument)
  }

  get(accessToken: string, documentId: string) {
    return parsedRequest(
      accessToken,
      returnsEndpoint(`/${encodeURIComponent(documentId)}`)
    )
  }

  create(input: ReturnCreateCommand) {
    return parsedRequest(input.accessToken, returnsEndpoint(), {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify({
        warehouseId: input.warehouseId,
        clientId: input.clientId,
        driverSnapshot: input.driverSnapshot,
        driverWorkerId: input.driverWorkerId,
        lines: input.lines,
      }),
    })
  }

  register(input: ReturnPickupCommand) {
    return parsedRequest(
      input.accessToken,
      returnsEndpoint(
        `/${encodeURIComponent(input.documentId)}/register?expectedVersion=${input.expectedVersion}`
      ),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
        body: JSON.stringify({
          driverSnapshot: input.driverSnapshot,
          driverWorkerId: input.driverWorkerId,
          scheduledDate: input.scheduledDate,
        }),
      }
    )
  }

  acceptUndamaged(input: ReturnAcceptUndamagedCommand) {
    return parsedRequest(
      input.accessToken,
      returnsEndpoint(
        `/${encodeURIComponent(input.documentId)}/accept-undamaged?expectedVersion=${input.expectedVersion}`
      ),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
        body: JSON.stringify({ lines: input.lines }),
      }
    )
  }

  startEstimates(input: StartReturnEstimatesCommand) {
    return parsedRequest(
      input.accessToken,
      returnsEndpoint(
        `/${encodeURIComponent(input.documentId)}/start-estimates?expectedVersion=${input.expectedVersion}`
      ),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
        body: JSON.stringify({ lines: input.lines }),
      }
    )
  }
}
