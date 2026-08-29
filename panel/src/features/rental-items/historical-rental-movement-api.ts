import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** Direction of a past rental fact entered directly from a cabin card. */
export type HistoricalRentalMovementKind = "SHIPMENT" | "RETURN"

/** Version-fenced create with an optional complete shipment-driver audit pair. */
export type CreateHistoricalRentalMovementInput = {
  warehouseId: string
  rentalItemId: string
  expectedRentalItemVersion: number
  clientId: string
  driverSnapshot: string | null
  driverWorkerId: string | null
  kind: HistoricalRentalMovementKind
  occurredOn: string
}

/** Version-fenced client, driver-audit and date correction of one historical shipment. */
export type UpdateHistoricalRentalShipmentInput = {
  expectedVersion: number
  rentalItemId: string
  clientId: string
  driverSnapshot: string | null
  driverWorkerId: string | null
  occurredOn: string
}

/** Minimal verified document identity returned after a historical fact is accepted. */
export type HistoricalRentalMovementDocument = {
  id: string
  version: number
}

function invalidResponse(): never {
  throw new Error("Сервис логистики вернул некорректный документ аренды.")
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    invalidResponse()
  }
  return value as Record<string, unknown>
}

function parseDocument(value: unknown): HistoricalRentalMovementDocument {
  const source = record(value)
  if (
    typeof source.id !== "string" ||
    !UUID_PATTERN.test(source.id) ||
    typeof source.version !== "number" ||
    !Number.isSafeInteger(source.version) ||
    source.version < 0
  ) {
    invalidResponse()
  }

  return { id: source.id, version: source.version }
}

function endpoint() {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/historical-rental-movements`
}

/** Sends a fenced, idempotent historical rental movement through the public gateway. */
export async function createHistoricalRentalMovement(params: {
  accessToken: string
  idempotencyKey: string
  input: CreateHistoricalRentalMovementInput
}): Promise<HistoricalRentalMovementDocument> {
  return parseDocument(
    await bearerRequest<unknown>(params.accessToken, endpoint(), {
      method: "POST",
      headers: { "Idempotency-Key": params.idempotencyKey },
      body: JSON.stringify(params.input),
    })
  )
}

/** Corrects one historical shipment document without replaying its physical effects. */
export async function updateHistoricalRentalShipment(params: {
  accessToken: string
  documentId: string
  idempotencyKey: string
  input: UpdateHistoricalRentalShipmentInput
}): Promise<HistoricalRentalMovementDocument> {
  return parseDocument(
    await bearerRequest<unknown>(
      params.accessToken,
      `${endpoint()}/${encodeURIComponent(params.documentId)}`,
      {
        method: "PUT",
        headers: { "Idempotency-Key": params.idempotencyKey },
        body: JSON.stringify(params.input),
      }
    )
  )
}

/** Produces a browser-side key so an ambiguous historical import can be retried safely. */
export function createHistoricalRentalMovementIdempotencyKey() {
  if (
    typeof crypto === "undefined" ||
    typeof crypto.randomUUID !== "function"
  ) {
    throw new Error("Браузер не поддерживает ключи идемпотентности.")
  }

  return crypto.randomUUID()
}
