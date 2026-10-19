import { invalidApiResponseError } from "@/lib/api-client"
import {
  ORDER_STATUSES,
  type OrderStatus,
} from "@/features/orders/domain/orders"

export const ORDER_PAYMENT_STATES = [
  "PENDING",
  "CONFIRMED",
  "EXPIRING",
  "EXPIRED",
  "CANCELLED",
] as const

export type OrderPaymentState = (typeof ORDER_PAYMENT_STATES)[number]
export type OrderPaymentSource =
  "CUSTOMER_TEST" | "PRESENTATION_TEST" | "MANAGER_CONFIRMATION"

/** Frozen initial bill; amounts remain exact decimal strings, including beyond int64. */
export type OrderPaymentReceiptLine = {
  kind: "CABIN" | "FURNITURE" | "DELIVERY"
  rentalItemId: string | null
  equipmentId: string | null
  label: string
  quantity: string
  rentalMonths: number | null
  unitPriceRubles: string
  amountRubles: string
  pricingVersion: number | null
}

export type OrderPaymentReceipt = {
  schemaVersion: 1
  orderId: string
  orderNumber: string
  issuedAt: string
  currency: "RUB"
  deliveryIncluded: boolean
  lines: OrderPaymentReceiptLine[]
  totalRubles: string
}

/** Payment evidence is independent of booking completion and order status. */
export type OrderPayment = {
  orderId: string
  orderVersion: number
  orderStatus: OrderStatus
  state: OrderPaymentState | null
  startedAt: string | null
  expiresAt: string | null
  resolvedAt: string | null
  source: OrderPaymentSource | null
  serverTime: string
  canConfirm: boolean
  receipt: OrderPaymentReceipt | null
}

/** An in-memory clock anchor, never persisted or sent back as domain evidence. */
export type ObservedOrderPayment = {
  payment: OrderPayment
  observedAt: number
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const WHOLE_RUBLES = /^(0|[1-9][0-9]{0,79})$/
const INT64 = 9223372036854775807n

function invalid(): never {
  throw invalidApiResponseError(
    "Сервис вернул некорректный чек или состояние оплаты."
  )
}

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) invalid()
  return value as Record<string, unknown>
}

function uuid(value: unknown): value is string {
  return typeof value === "string" && UUID.test(value)
}

function version(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
}

function timestamp(value: unknown): value is string {
  return (
    typeof value === "string" &&
    /^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
    Number.isFinite(Date.parse(value))
  )
}

function amount(value: unknown): value is string {
  return typeof value === "string" && WHOLE_RUBLES.test(value)
}

function int64Amount(value: unknown): value is string {
  return amount(value) && value.length <= 19 && BigInt(value) <= INT64
}

function parseReceipt(value: unknown, orderId: string): OrderPaymentReceipt {
  const receipt = record(value)
  if (
    receipt.schemaVersion !== 1 ||
    receipt.orderId !== orderId ||
    typeof receipt.orderNumber !== "string" ||
    !receipt.orderNumber.trim() ||
    !timestamp(receipt.issuedAt) ||
    receipt.currency !== "RUB" ||
    typeof receipt.deliveryIncluded !== "boolean" ||
    !Array.isArray(receipt.lines) ||
    receipt.lines.length === 0 ||
    !amount(receipt.totalRubles)
  )
    invalid()

  const lines = receipt.lines.map((value) => {
    const line = record(value)
    if (
      !["CABIN", "FURNITURE", "DELIVERY"].includes(String(line.kind)) ||
      (line.rentalItemId !== null && !uuid(line.rentalItemId)) ||
      (line.equipmentId !== null && !uuid(line.equipmentId)) ||
      typeof line.label !== "string" ||
      !line.label.trim() ||
      line.label.length > 600 ||
      !int64Amount(line.quantity) ||
      line.quantity === "0" ||
      !int64Amount(line.unitPriceRubles) ||
      !amount(line.amountRubles) ||
      (line.pricingVersion !== null && !version(line.pricingVersion))
    )
      invalid()
    if (line.kind === "DELIVERY") {
      if (
        line.quantity !== "1" ||
        line.rentalItemId !== null ||
        line.equipmentId !== null ||
        line.rentalMonths !== null ||
        line.pricingVersion !== null ||
        line.unitPriceRubles !== line.amountRubles
      )
        invalid()
    } else {
      if (
        !uuid(line.rentalItemId) ||
        !version(line.rentalMonths) ||
        line.rentalMonths < 1 ||
        line.rentalMonths > 120 ||
        line.pricingVersion === null ||
        (line.kind === "CABIN" &&
          (line.quantity !== "1" || line.equipmentId !== null)) ||
        (line.kind === "FURNITURE" && !uuid(line.equipmentId)) ||
        BigInt(line.quantity) *
          BigInt(line.unitPriceRubles) *
          BigInt(line.rentalMonths) !==
          BigInt(line.amountRubles)
      )
        invalid()
    }
    return line as OrderPaymentReceiptLine
  })
  const deliveryLines = lines.filter((line) => line.kind === "DELIVERY")
  const cabinIds = new Set(
    lines
      .filter((line) => line.kind === "CABIN")
      .map((line) => line.rentalItemId)
  )
  if (
    cabinIds.size === 0 ||
    deliveryLines.length > 1 ||
    receipt.deliveryIncluded !== (deliveryLines.length === 1) ||
    lines.some(
      (line) => line.kind === "FURNITURE" && !cabinIds.has(line.rentalItemId)
    ) ||
    lines.reduce((total, line) => total + BigInt(line.amountRubles), 0n) !==
      BigInt(receipt.totalRubles)
  )
    invalid()
  return { ...(receipt as OrderPaymentReceipt), lines }
}

/** Rejects missing evidence, floating-point money and a bill belonging to another order. */
export function parseOrderPayment(
  value: unknown,
  expectedOrderId?: string
): OrderPayment {
  const payment = record(value)
  if (
    !uuid(payment.orderId) ||
    (expectedOrderId !== undefined && payment.orderId !== expectedOrderId) ||
    !version(payment.orderVersion) ||
    !ORDER_STATUSES.includes(payment.orderStatus as OrderStatus) ||
    (payment.state !== null &&
      !ORDER_PAYMENT_STATES.includes(payment.state as OrderPaymentState)) ||
    (payment.startedAt !== null && !timestamp(payment.startedAt)) ||
    (payment.expiresAt !== null && !timestamp(payment.expiresAt)) ||
    (payment.resolvedAt !== null && !timestamp(payment.resolvedAt)) ||
    (payment.source !== null &&
      !["CUSTOMER_TEST", "PRESENTATION_TEST", "MANAGER_CONFIRMATION"].includes(
        String(payment.source)
      )) ||
    !timestamp(payment.serverTime) ||
    typeof payment.canConfirm !== "boolean"
  )
    invalid()
  if (
    payment.state !== null &&
    (!timestamp(payment.startedAt) ||
      !timestamp(payment.expiresAt) ||
      Date.parse(payment.expiresAt) - Date.parse(payment.startedAt) !== 300_000)
  )
    invalid()
  const receipt =
    payment.receipt === null
      ? null
      : parseReceipt(payment.receipt, payment.orderId)
  if (
    payment.canConfirm &&
    (payment.state !== "PENDING" ||
      payment.orderStatus !== "SAVED" ||
      receipt === null ||
      !timestamp(payment.expiresAt) ||
      Date.parse(payment.serverTime) >= Date.parse(payment.expiresAt))
  )
    invalid()
  return { ...(payment as OrderPayment), receipt }
}

export function observeOrderPayment(
  payment: OrderPayment,
  observedAt = performance.now()
): ObservedOrderPayment {
  return { payment, observedAt }
}

/** Counts down from server time, unaffected by a wrong or changed wall clock on the device. */
export function paymentRemainingMilliseconds(
  observation: ObservedOrderPayment,
  monotonicNow: number
): number | null {
  if (observation.payment.expiresAt === null) return null
  return Math.max(
    0,
    Date.parse(observation.payment.expiresAt) -
      Date.parse(observation.payment.serverTime) -
      Math.max(0, monotonicNow - observation.observedAt)
  )
}

export function formatPaymentCountdown(remainingMilliseconds: number): string {
  const seconds = Math.ceil(Math.max(0, remainingMilliseconds) / 1_000)
  return (
    String(Math.floor(seconds / 60)).padStart(2, "0") +
    ":" +
    String(seconds % 60).padStart(2, "0")
  )
}

export function formatReceiptRubles(value: string): string {
  if (!amount(value)) invalid()
  return new Intl.NumberFormat("ru-RU").format(BigInt(value)) + " ₽"
}

/** Only the owner response admits a new shipment; explicit historical null is not a paid badge. */
export function paymentAllowsFulfillment(
  payment: OrderPayment | undefined
): boolean {
  return (
    payment !== undefined &&
    payment.orderStatus !== "DRAFT" &&
    payment.orderStatus !== "CANCELLED" &&
    (payment.state === null || payment.state === "CONFIRMED")
  )
}

export function paymentRefetchInterval(
  payment: OrderPayment | undefined
): number | false {
  return payment === undefined ||
    payment.orderStatus === "DRAFT" ||
    payment.state === "PENDING" ||
    payment.state === "EXPIRING"
    ? 2_000
    : false
}
