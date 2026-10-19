import { bearerRequest, invalidApiResponseError } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const SETTLEMENTS = [
  "POLICY_UNCONFIGURED",
  "PAYMENT_REQUIRED",
  "NOT_REQUIRED",
  "TEST_PAID",
  "WAIVED",
] as const
const APPLICATION_STATES = ["OFFERED", "APPLYING", "APPLIED"] as const

/** Exact owner quote; order and booking versions are separate concurrency fences. */
export type OrderBookingChangeQuote = {
  quoteId: string
  version: number
  bookingId: string
  bookingVersion: number
  operation: "CANCEL" | "RESCHEDULE"
  oldSlotId: string
  slotId: string | null
  slotVersion: number | null
  amountRubles: string | null
  settlement: (typeof SETTLEMENTS)[number]
  applicationState: (typeof APPLICATION_STATES)[number]
  testPaymentAvailable: boolean
  supportPhone: string | null
  expiresAt: string
  noticeDays: number
  deliveryDate: string
  warehouseTimeZone: string
  targetDeliveryDate: string | null
  targetWindowStart: string | null
  targetWindowEnd: string | null
}

export type PendingBookingChangeQuote = {
  orderId: string
  warehouseId: string
  quote: OrderBookingChangeQuote
}
export const PENDING_BOOKING_CHANGE_QUOTES_QUERY_KEY = [
  "pending-booking-change-quotes",
] as const

export const ORDER_BOOKING_CHANGE_QUOTES_QUERY_KEY = [
  "order-booking-change-quotes",
] as const
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const version = (value: unknown): value is number =>
  typeof value === "number" && Number.isSafeInteger(value) && value >= 0
const uuid = (value: unknown): value is string =>
  typeof value === "string" && UUID.test(value)
function calendarDate(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value))
    return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return (
    Number.isFinite(parsed.getTime()) &&
    parsed.toISOString().slice(0, 10) === value
  )
}
const localTime = (value: unknown): value is string =>
  typeof value === "string" &&
  /^(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:\.[0-9]{1,9})?)?$/.test(
    value
  )

function parseQuote(value: unknown): OrderBookingChangeQuote {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error("Invalid change quote")
  const quote = value as Record<string, unknown>
  if (
    !uuid(quote.quoteId) ||
    !uuid(quote.bookingId) ||
    !uuid(quote.oldSlotId) ||
    !version(quote.version) ||
    !version(quote.bookingVersion) ||
    (quote.operation !== "CANCEL" && quote.operation !== "RESCHEDULE") ||
    (quote.slotId !== null && !uuid(quote.slotId)) ||
    (quote.slotVersion !== null && !version(quote.slotVersion)) ||
    (quote.operation === "CANCEL" &&
      (quote.slotId !== null || quote.slotVersion !== null)) ||
    (quote.operation === "RESCHEDULE" &&
      (quote.slotId === null || quote.slotVersion === null)) ||
    (quote.amountRubles !== null &&
      (typeof quote.amountRubles !== "string" ||
        !/^(0|[1-9][0-9]{0,18})$/.test(quote.amountRubles) ||
        BigInt(quote.amountRubles) > 9223372036854775807n)) ||
    !SETTLEMENTS.includes(
      quote.settlement as OrderBookingChangeQuote["settlement"]
    ) ||
    !APPLICATION_STATES.includes(
      quote.applicationState as OrderBookingChangeQuote["applicationState"]
    ) ||
    typeof quote.testPaymentAvailable !== "boolean" ||
    (quote.supportPhone !== null &&
      (typeof quote.supportPhone !== "string" ||
        !/^\+[1-9][0-9]{7,14}$/.test(quote.supportPhone))) ||
    typeof quote.expiresAt !== "string" ||
    !/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(quote.expiresAt) ||
    !Number.isFinite(Date.parse(quote.expiresAt)) ||
    !version(quote.noticeDays) ||
    quote.noticeDays > 2147483647 ||
    typeof quote.deliveryDate !== "string" ||
    !/^\d{4}-\d{2}-\d{2}$/.test(quote.deliveryDate) ||
    typeof quote.warehouseTimeZone !== "string" ||
    !quote.warehouseTimeZone.trim() ||
    (quote.targetDeliveryDate !== null &&
      !calendarDate(quote.targetDeliveryDate)) ||
    (quote.targetWindowStart !== null && !localTime(quote.targetWindowStart)) ||
    (quote.targetWindowEnd !== null && !localTime(quote.targetWindowEnd)) ||
    (quote.operation === "RESCHEDULE" &&
      (quote.targetDeliveryDate === null ||
        quote.targetWindowStart === null ||
        quote.targetWindowEnd === null)) ||
    (quote.operation === "CANCEL" &&
      (quote.targetDeliveryDate !== null ||
        quote.targetWindowStart !== null ||
        quote.targetWindowEnd !== null))
  )
    throw new Error("Invalid change quote facts")
  const date = new Date(`${quote.deliveryDate}T00:00:00Z`)
  if (
    !Number.isFinite(date.getTime()) ||
    date.toISOString().slice(0, 10) !== quote.deliveryDate
  )
    throw new Error("Invalid delivery date")
  new Intl.DateTimeFormat("ru-RU", { timeZone: quote.warehouseTimeZone })
  return quote as OrderBookingChangeQuote
}

function endpoint(orderId: string) {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/orders/${encodeURIComponent(orderId)}/booking-change-quotes`
}

/** Fee-only staff feed; it carries no full-order read permission or customer contact data. */
export async function listPendingBookingChangeQuotes(
  accessToken: string
): Promise<PendingBookingChangeQuote[]> {
  const response = await bearerRequest<unknown>(
    accessToken,
    `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/rental-booking-change-quotes`
  )
  try {
    if (!Array.isArray(response)) throw new Error("Invalid pending quotes list")
    const quotes = response.map((value: unknown) => {
      if (!value || typeof value !== "object" || Array.isArray(value))
        throw new Error("Invalid pending quote")
      const item = value as Record<string, unknown>
      if (!uuid(item.orderId) || !uuid(item.warehouseId))
        throw new Error("Invalid pending quote identity")
      return {
        orderId: item.orderId,
        warehouseId: item.warehouseId,
        quote: parseQuote(item.quote),
      }
    })
    if (
      new Set(quotes.map((item) => item.quote.quoteId)).size !== quotes.length
    )
      throw new Error("Duplicate pending quote")
    return quotes
  } catch (error) {
    throw invalidApiResponseError(error)
  }
}

/** The owner returns only current, unexpired OFFERED quotes, not change history. */
export async function listOrderBookingChangeQuotes(
  accessToken: string,
  orderId: string
): Promise<OrderBookingChangeQuote[]> {
  const response = await bearerRequest<unknown>(accessToken, endpoint(orderId))
  try {
    if (!Array.isArray(response)) throw new Error("Invalid quotes list")
    const quotes = response.map(parseQuote)
    if (new Set(quotes.map((quote) => quote.quoteId)).size !== quotes.length)
      throw new Error("Duplicate quote")
    return quotes
  } catch (error) {
    throw invalidApiResponseError(error)
  }
}

/** Waives one quoted fee only; does not cancel/reschedule the booking or extend quote expiry. */
export async function waiveOrderBookingChangeQuote(params: {
  accessToken: string
  orderId: string
  quoteId: string
  expectedVersion: number
  reason: string
  idempotencyKey: string
}): Promise<OrderBookingChangeQuote> {
  const response = await bearerRequest<unknown>(
    params.accessToken,
    `${endpoint(params.orderId)}/${encodeURIComponent(params.quoteId)}/waiver`,
    {
      method: "POST",
      headers: { "Idempotency-Key": params.idempotencyKey },
      body: JSON.stringify({
        expectedVersion: params.expectedVersion,
        reason: params.reason.trim(),
      }),
    }
  )
  try {
    const quote = parseQuote(response)
    if (quote.quoteId !== params.quoteId)
      throw new Error("Unexpected quote identity")
    return quote
  } catch (error) {
    throw invalidApiResponseError(error)
  }
}
