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
  ReturnEstimateCommand,
  ReturnVersionedCommand,
} from "@/features/logistics/returns/ports/return-client"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

type JsonObject = Record<string, unknown>

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

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
    clientId: nullableUuid(source.clientId),
    equipmentMovementTaskId: (() => {
      if (nullableUuid(source.equipmentMovementTaskId) !== null)
        invalidResponse()
      return null
    })(),
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
        lines: input.lines,
      }),
    })
  }

  register(input: ReturnVersionedCommand) {
    return parsedRequest(
      input.accessToken,
      returnsEndpoint(
        `/${encodeURIComponent(input.documentId)}/register?expectedVersion=${input.expectedVersion}`
      ),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
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

  requestEstimate(input: ReturnEstimateCommand) {
    return parsedRequest(
      input.accessToken,
      returnsEndpoint(
        `/${encodeURIComponent(input.documentId)}/request-estimate?expectedVersion=${input.expectedVersion}`
      ),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
        body: JSON.stringify({ lines: input.lines }),
      }
    )
  }
}
