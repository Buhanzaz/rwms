import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export const MANUAL_BOOKING_DRAFT_HOLD_QUERY_KEY = [
  "manual-booking-draft-hold",
] as const

const INVALID_RESPONSE_MESSAGE =
  "Сервис логистики вернул некорректный ответ о резерве бронирования."
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export type ManualBookingDraftHold = {
  draftId: string
  warehouseId: string
  expiresAt: string | null
  rentalItemIds: string[]
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
}

function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value)
}

function isIsoDateTime(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.trim() !== "" &&
    Number.isFinite(Date.parse(value))
  )
}

function parseManualBookingDraftHold(value: unknown): ManualBookingDraftHold {
  if (!isRecord(value)) throw new Error(INVALID_RESPONSE_MESSAGE)

  const { draftId, warehouseId, expiresAt, rentalItemIds } = value
  if (
    !isUuid(draftId) ||
    !isUuid(warehouseId) ||
    (expiresAt !== null && !isIsoDateTime(expiresAt)) ||
    !Array.isArray(rentalItemIds) ||
    rentalItemIds.length > 100 ||
    !rentalItemIds.every(isUuid) ||
    new Set(rentalItemIds).size !== rentalItemIds.length
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return { draftId, warehouseId, expiresAt, rentalItemIds: [...rentalItemIds] }
}

function manualBookingDraftHoldEndpoint(draftId: string) {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/manual-booking-drafts/${encodeURIComponent(draftId)}/holds`
}

function assertInput(
  draftId: string,
  warehouseId: string,
  rentalItemIds?: readonly string[]
) {
  if (
    !isUuid(draftId) ||
    !isUuid(warehouseId) ||
    (rentalItemIds !== undefined &&
      (rentalItemIds.length === 0 ||
        rentalItemIds.length > 100 ||
        !rentalItemIds.every(isUuid) ||
        new Set(rentalItemIds).size !== rentalItemIds.length))
  ) {
    throw new Error("Для резерва выберите от 1 до 100 бытовок одного склада.")
  }
}

function assertMatchingResponse(
  response: ManualBookingDraftHold,
  draftId: string,
  warehouseId: string
) {
  if (response.draftId !== draftId || response.warehouseId !== warehouseId) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }
  return response
}

export async function putManualBookingDraftHold(params: {
  accessToken: string
  draftId: string
  idempotencyKey: string
  warehouseId: string
  rentalItemIds: readonly string[]
}) {
  assertInput(params.draftId, params.warehouseId, params.rentalItemIds)
  const response = await bearerRequest<unknown>(
    params.accessToken,
    manualBookingDraftHoldEndpoint(params.draftId),
    {
      method: "PUT",
      headers: { "Idempotency-Key": params.idempotencyKey },
      body: JSON.stringify({
        warehouseId: params.warehouseId,
        rentalItemIds: params.rentalItemIds,
      }),
    }
  )
  const parsed = assertMatchingResponse(
    parseManualBookingDraftHold(response),
    params.draftId,
    params.warehouseId
  )
  if (
    parsed.expiresAt === null ||
    parsed.rentalItemIds.length !== params.rentalItemIds.length ||
    !params.rentalItemIds.every((id) => parsed.rentalItemIds.includes(id))
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }
  return parsed
}

export async function getManualBookingDraftHold(params: {
  accessToken: string
  draftId: string
  warehouseId: string
}) {
  assertInput(params.draftId, params.warehouseId)
  const query = new URLSearchParams({ warehouseId: params.warehouseId })
  const response = await bearerRequest<unknown>(
    params.accessToken,
    `${manualBookingDraftHoldEndpoint(params.draftId)}?${query.toString()}`
  )
  return assertMatchingResponse(
    parseManualBookingDraftHold(response),
    params.draftId,
    params.warehouseId
  )
}
