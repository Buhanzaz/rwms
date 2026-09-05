import {
  ORDER_STATUSES,
  type OrderStatus,
} from "@/features/orders/domain/orders"
import {
  ORDER_PAYMENT_STATES,
  type OrderPaymentState,
} from "@/features/orders/domain/order-payment"
import { bearerRequest, invalidApiResponseError } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

/** Current asset occupancy enriched with only metadata the server permits this viewer to see. */
export type RentalItemReserve = {
  reservationId: string
  kind: "SELECTION_HOLD" | "ORDER_RESERVATION"
  source: "CUSTOMER" | "MANAGER"
  createdAt: string
  expiresAt: string | null
  clientDisplayName: string | null
  managerDisplayName: string | null
  orderId: string | null
  orderNumber: string | null
  orderStatus: OrderStatus | null
  paymentState: OrderPaymentState | null
  canOpenOrder: boolean
}

export type RentalItemReserves = {
  rentalItemId: string
  warehouseId: string
  serverTime: string
  reserves: RentalItemReserve[]
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
function invalid(): never {
  throw invalidApiResponseError(
    "Сервис вернул некорректный список резервов бытовки."
  )
}
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) invalid()
  return value as Record<string, unknown>
}
function timestamp(value: unknown): value is string {
  return (
    typeof value === "string" &&
    /^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
    Number.isFinite(Date.parse(value))
  )
}
function nullableString(value: unknown) {
  return value === null || typeof value === "string"
}
function parseReserve(value: unknown): RentalItemReserve {
  const row = record(value)
  if (
    typeof row.reservationId !== "string" ||
    !UUID.test(row.reservationId) ||
    (row.kind !== "SELECTION_HOLD" && row.kind !== "ORDER_RESERVATION") ||
    (row.source !== "CUSTOMER" && row.source !== "MANAGER") ||
    !timestamp(row.createdAt) ||
    (row.expiresAt !== null && !timestamp(row.expiresAt)) ||
    !nullableString(row.clientDisplayName) ||
    !nullableString(row.managerDisplayName) ||
    !nullableString(row.orderNumber) ||
    (row.orderId !== null &&
      (typeof row.orderId !== "string" || !UUID.test(row.orderId))) ||
    (row.orderStatus !== null &&
      !ORDER_STATUSES.includes(row.orderStatus as OrderStatus)) ||
    (row.paymentState !== null &&
      !ORDER_PAYMENT_STATES.includes(row.paymentState as OrderPaymentState)) ||
    typeof row.canOpenOrder !== "boolean" ||
    (row.canOpenOrder && !row.orderId)
  )
    invalid()
  return row as RentalItemReserve
}

/** Live gateway read: an unavailable source is an error, never an empty reserve list. */
export async function getRentalItemReserves(
  accessToken: string,
  rentalItemId: string,
  warehouseId: string
): Promise<RentalItemReserves> {
  const url = `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/rental-items/${encodeURIComponent(rentalItemId)}/reserves?warehouseId=${encodeURIComponent(warehouseId)}`
  const value = record(
    await bearerRequest<unknown>(accessToken, url, { cache: "no-store" })
  )
  if (
    value.rentalItemId !== rentalItemId ||
    value.warehouseId !== warehouseId ||
    !timestamp(value.serverTime) ||
    !Array.isArray(value.reserves)
  )
    invalid()
  const reserves = value.reserves.map(parseReserve)
  if (
    new Set(reserves.map((row) => row.reservationId)).size !== reserves.length
  )
    invalid()
  return { rentalItemId, warehouseId, serverTime: value.serverTime, reserves }
}
