import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import { mapAssetRentalItem } from "@/features/rental-items/api/asset-rental-items-api"
import type {
  PageResponse,
  RentalItemDto,
} from "@/features/rental-items/model/rental-item"

export const RENTAL_BOOKING_AVAILABLE_QUERY_KEY = [
  "rental-booking-available",
] as const
export const RENTAL_BOOKING_SELECTED_AVAILABILITY_QUERY_KEY = [
  "rental-booking-selected-availability",
] as const

const INVALID_AVAILABLE_RESPONSE =
  "Сервис имущества вернул некорректный ответ о свободных бытовках."
const INVALID_AVAILABILITY_RESPONSE =
  "Сервис имущества вернул некорректный ответ о доступности бытовок."
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const AVAILABILITY_REASONS = new Set([
  "NOT_FOUND",
  "WAREHOUSE_MISMATCH",
  "STATUS",
  "ORDER_RESERVED",
  "PRESENTATION_HELD",
  "OPERATION_LEASED",
  "AVAILABLE",
] as const)

export type BookingAvailabilityReason =
  | "NOT_FOUND"
  | "WAREHOUSE_MISMATCH"
  | "STATUS"
  | "ORDER_RESERVED"
  | "PRESENTATION_HELD"
  | "OPERATION_LEASED"
  | "AVAILABLE"

export type BookingAvailabilityItem = {
  rentalItemId: string
  available: boolean
  reason: BookingAvailabilityReason
}

export type BookingAvailabilityResponse = {
  warehouseId: string
  items: BookingAvailabilityItem[]
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
}

function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value)
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
}

function parseAvailablePage(value: unknown): PageResponse<RentalItemDto> {
  if (!isRecord(value)) throw new Error(INVALID_AVAILABLE_RESPONSE)

  const { content, page, size, totalElements, totalPages } = value
  if (
    !Array.isArray(content) ||
    !isNonNegativeInteger(page) ||
    !isNonNegativeInteger(size) ||
    !isNonNegativeInteger(totalElements) ||
    !isNonNegativeInteger(totalPages)
  ) {
    throw new Error(INVALID_AVAILABLE_RESPONSE)
  }

  try {
    return {
      content: content.map(mapAssetRentalItem),
      page,
      size,
      totalElements,
      totalPages,
    }
  } catch {
    throw new Error(INVALID_AVAILABLE_RESPONSE)
  }
}

function parseAvailabilityItem(value: unknown): BookingAvailabilityItem {
  if (!isRecord(value)) throw new Error(INVALID_AVAILABILITY_RESPONSE)

  const { rentalItemId, available, reason } = value
  if (
    !isUuid(rentalItemId) ||
    typeof available !== "boolean" ||
    typeof reason !== "string" ||
    !AVAILABILITY_REASONS.has(reason as BookingAvailabilityReason) ||
    (available && reason !== "AVAILABLE") ||
    (!available && reason === "AVAILABLE")
  ) {
    throw new Error(INVALID_AVAILABILITY_RESPONSE)
  }

  return {
    rentalItemId,
    available,
    reason: reason as BookingAvailabilityReason,
  }
}

function parseAvailabilityResponse(
  value: unknown
): BookingAvailabilityResponse {
  if (!isRecord(value)) throw new Error(INVALID_AVAILABILITY_RESPONSE)

  const { warehouseId, items } = value
  if (!isUuid(warehouseId) || !Array.isArray(items) || items.length > 100) {
    throw new Error(INVALID_AVAILABILITY_RESPONSE)
  }

  const parsedItems = items.map(parseAvailabilityItem)
  if (
    new Set(parsedItems.map((item) => item.rentalItemId)).size !==
    parsedItems.length
  ) {
    throw new Error(INVALID_AVAILABILITY_RESPONSE)
  }

  return { warehouseId, items: parsedItems }
}

function availableRentalItemsEndpoint(path = "") {
  return `${getGatewayRuntimeConfig().assetApiBaseUrl}/v1/rental-items${path}`
}

export async function listAvailableRentalItems(params: {
  accessToken: string
  warehouseId: string
  page?: number
  size?: number
  search?: string
}): Promise<PageResponse<RentalItemDto>> {
  const query = new URLSearchParams({
    warehouseId: params.warehouseId,
    page: String(params.page ?? 0),
    size: String(params.size ?? 200),
  })
  const search = params.search?.trim()
  if (search) query.set("search", search)

  const response = await bearerRequest<unknown>(
    params.accessToken,
    `${availableRentalItemsEndpoint("/available")}?${query.toString()}`
  )
  return parseAvailablePage(response)
}

export async function checkRentalItemsAvailability(params: {
  accessToken: string
  warehouseId: string
  rentalItemIds: readonly string[]
}): Promise<BookingAvailabilityResponse> {
  if (
    !isUuid(params.warehouseId) ||
    params.rentalItemIds.length === 0 ||
    params.rentalItemIds.length > 100 ||
    !params.rentalItemIds.every(isUuid)
  ) {
    throw new Error("Для проверки выберите от 1 до 100 бытовок одного склада.")
  }

  const response = await bearerRequest<unknown>(
    params.accessToken,
    availableRentalItemsEndpoint("/availability"),
    {
      method: "POST",
      body: JSON.stringify({
        warehouseId: params.warehouseId,
        rentalItemIds: params.rentalItemIds,
      }),
    }
  )
  const parsed = parseAvailabilityResponse(response)
  if (parsed.warehouseId !== params.warehouseId) {
    throw new Error(INVALID_AVAILABILITY_RESPONSE)
  }
  return parsed
}

export function unavailableRentalItemIds(
  response: BookingAvailabilityResponse,
  requestedIds: readonly string[]
) {
  const availabilityById = new Map(
    response.items.map((item) => [item.rentalItemId, item.available])
  )
  return requestedIds.filter((id) => availabilityById.get(id) !== true)
}
