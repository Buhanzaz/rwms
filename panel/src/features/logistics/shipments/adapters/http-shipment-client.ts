import {
  SHIPMENT_DOCUMENT_STATES,
  SHIPMENT_LINE_STATES,
  type ShipmentDocument,
  type ShipmentDocumentState,
  type ShipmentLine,
  type ShipmentLineState,
} from "@/features/logistics/shipments/model"
import type {
  ShipmentClient,
  ShipmentCreateCommand,
  ShipmentPlanCommand,
  ShipmentVersionedCommand,
} from "@/features/logistics/shipments/ports/shipment-client"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

type JsonObject = Record<string, unknown>

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function invalidResponse(): never {
  throw new Error("Сервис логистики вернул некорректную отгрузку.")
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

function shipmentLine(value: unknown): ShipmentLine {
  const source = object(value)
  const lineNumber = integer(source.lineNumber)
  if (lineNumber < 1) invalidResponse()
  return {
    id: uuid(source.id),
    version: integer(source.version),
    lineNumber,
    assetId: uuid(source.assetId),
    assetVersion: integer(source.assetVersion),
    state: oneOf<ShipmentLineState>(source.state, SHIPMENT_LINE_STATES),
    tenantSnapshot: nullableText(source.tenantSnapshot),
    rentalOrderId: nullableUuid(source.rentalOrderId),
  }
}

export function parseShipmentDocument(value: unknown): ShipmentDocument {
  const source = object(value)
  const lines = list(source.lines).map(shipmentLine)
  if (lines.length === 0) invalidResponse()
  if (source.documentType !== "SHIPMENT") invalidResponse()
  if (nullableUuid(source.destinationWarehouseId) !== null) invalidResponse()

  return {
    id: uuid(source.id),
    version: integer(source.version),
    documentType: "SHIPMENT",
    state: oneOf<ShipmentDocumentState>(source.state, SHIPMENT_DOCUMENT_STATES),
    warehouseId: uuid(source.warehouseId),
    destinationWarehouseId: null,
    partySnapshot: nonBlankText(source.partySnapshot),
    driverSnapshot: nonBlankText(source.driverSnapshot),
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

function shipmentsEndpoint(path = "") {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/shipments${path}`
}

function commandHeaders(idempotencyKey: string) {
  return { "Idempotency-Key": idempotencyKey }
}

async function parsedRequest(
  accessToken: string,
  input: string,
  init?: RequestInit
) {
  return parseShipmentDocument(
    await bearerRequest<unknown>(accessToken, input, init)
  )
}

export class HttpShipmentClient implements ShipmentClient {
  async list(accessToken: string, warehouseId: string) {
    const response = await bearerRequest<unknown>(
      accessToken,
      shipmentsEndpoint(`?warehouseId=${encodeURIComponent(warehouseId)}`)
    )
    return list(response).map(parseShipmentDocument)
  }

  get(accessToken: string, documentId: string) {
    return parsedRequest(
      accessToken,
      shipmentsEndpoint(`/${encodeURIComponent(documentId)}`)
    )
  }

  create(input: ShipmentCreateCommand) {
    return parsedRequest(input.accessToken, shipmentsEndpoint(), {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify({
        warehouseId: input.warehouseId,
        clientId: input.clientId,
        rentalOrderId: input.rentalOrderId,
        partySnapshot: input.partySnapshot,
        driverSnapshot: input.driverSnapshot,
        lines: input.lines,
      }),
    })
  }

  replacePlan(input: ShipmentPlanCommand) {
    return parsedRequest(
      input.accessToken,
      shipmentsEndpoint(
        `/${encodeURIComponent(input.documentId)}/plan?expectedVersion=${input.expectedVersion}`
      ),
      {
        method: "PUT",
        headers: commandHeaders(input.idempotencyKey),
        body: JSON.stringify({
          partySnapshot: input.partySnapshot,
          driverSnapshot: input.driverSnapshot,
          lines: input.lines,
        }),
      }
    )
  }

  confirmPreparation(input: ShipmentVersionedCommand) {
    return parsedRequest(
      input.accessToken,
      shipmentsEndpoint(
        `/${encodeURIComponent(input.documentId)}/confirm-preparation?expectedVersion=${input.expectedVersion}`
      ),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
      }
    )
  }

  cancel(input: ShipmentVersionedCommand) {
    return parsedRequest(
      input.accessToken,
      shipmentsEndpoint(
        `/${encodeURIComponent(input.documentId)}/cancel?expectedVersion=${input.expectedVersion}`
      ),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
      }
    )
  }
}
