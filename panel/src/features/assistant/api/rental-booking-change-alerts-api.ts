import { bearerRequest, invalidApiResponseError } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export const BOOKING_CHANGE_SETTLEMENTS = [
  "POLICY_UNCONFIGURED",
  "PAYMENT_REQUIRED",
  "NOT_REQUIRED",
  "TEST_PAID",
  "WAIVED",
] as const

export type RentalBookingChangeAlert = {
  mutationId: string
  version: number
  bookingId: string
  orderId: string
  warehouseId: string
  canOpenOrder: boolean
  operation: "CANCEL" | "RESCHEDULE"
  occurredAt: string
  previousDeliveryDate: string
  newDeliveryDate: string | null
  deliveryAddress: string
  feeRubles: string | null
  settlement: (typeof BOOKING_CHANGE_SETTLEMENTS)[number] | null
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function calendarDate(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value))
    return false
  const date = new Date(`${value}T00:00:00Z`)
  return (
    Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
  )
}

function parseAlert(value: unknown): RentalBookingChangeAlert {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error("Invalid change alert")
  const alert = value as Record<string, unknown>
  if (
    ["mutationId", "bookingId", "orderId", "warehouseId"].some(
      (key) => typeof alert[key] !== "string" || !UUID.test(alert[key])
    )
  )
    throw new Error("Invalid change alert identity")
  if (
    typeof alert.canOpenOrder !== "boolean" ||
    typeof alert.version !== "number" ||
    !Number.isSafeInteger(alert.version) ||
    alert.version < 0 ||
    (alert.operation !== "CANCEL" && alert.operation !== "RESCHEDULE") ||
    typeof alert.occurredAt !== "string" ||
    !/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(alert.occurredAt) ||
    !Number.isFinite(Date.parse(alert.occurredAt)) ||
    !calendarDate(alert.previousDeliveryDate) ||
    (alert.newDeliveryDate !== null && !calendarDate(alert.newDeliveryDate)) ||
    typeof alert.deliveryAddress !== "string" ||
    !alert.deliveryAddress.trim() ||
    (alert.settlement !== null &&
      !BOOKING_CHANGE_SETTLEMENTS.includes(
        alert.settlement as (typeof BOOKING_CHANGE_SETTLEMENTS)[number]
      )) ||
    (alert.feeRubles !== null &&
      (typeof alert.feeRubles !== "string" ||
        !/^(0|[1-9][0-9]{0,18})$/.test(alert.feeRubles) ||
        BigInt(alert.feeRubles) > 9223372036854775807n))
  ) {
    throw new Error("Invalid change alert details")
  }
  return alert as RentalBookingChangeAlert
}

function endpoint(path = "") {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/rental-booking-change-alerts${path}`
}

export async function getRentalBookingChangeAlerts(
  accessToken: string
): Promise<RentalBookingChangeAlert[]> {
  const response = await bearerRequest<unknown>(accessToken, endpoint())
  try {
    if (!Array.isArray(response)) throw new Error("Invalid change alerts list")
    const alerts = response.map(parseAlert)
    if (new Set(alerts.map((alert) => alert.mutationId)).size !== alerts.length)
      throw new Error("Duplicate change alert")
    return alerts
  } catch (error) {
    throw invalidApiResponseError(error)
  }
}

/** Acknowledges only this notification; never changes the order or its settlement. */
export function acknowledgeRentalBookingChangeAlert(params: {
  accessToken: string
  mutationId: string
  expectedVersion: number
  idempotencyKey: string
}) {
  return bearerRequest<void>(
    params.accessToken,
    endpoint(`/${encodeURIComponent(params.mutationId)}/acknowledgement`),
    {
      method: "POST",
      headers: { "Idempotency-Key": params.idempotencyKey },
      body: JSON.stringify({ expectedVersion: params.expectedVersion }),
    }
  )
}
