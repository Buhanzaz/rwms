import type { OrderPayment } from "@/features/orders/domain/order-payment"

/** Canonical response fixture shared only by payment boundary and component tests. */
export function orderPaymentFixture(
  overrides: Partial<OrderPayment> = {}
): OrderPayment {
  const orderId = "33333333-3333-4333-8333-333333333333"
  const cabinId = "55555555-5555-4555-8555-555555555555"
  return {
    orderId,
    orderVersion: 4,
    orderStatus: "SAVED",
    state: "PENDING",
    startedAt: "2026-09-05T12:00:00Z",
    expiresAt: "2026-09-05T12:05:00Z",
    resolvedAt: null,
    source: null,
    serverTime: "2026-09-05T12:00:00Z",
    canConfirm: true,
    receipt: {
      schemaVersion: 1,
      orderId,
      orderNumber: "ORD-000042",
      issuedAt: "2026-09-05T12:00:00Z",
      currency: "RUB",
      deliveryIncluded: true,
      lines: [
        {
          kind: "CABIN",
          rentalItemId: cabinId,
          equipmentId: null,
          label: "Бытовка № БЫТ-001",
          quantity: "1",
          rentalMonths: 3,
          unitPriceRubles: "8500",
          amountRubles: "25500",
          pricingVersion: 4,
        },
        {
          kind: "FURNITURE",
          rentalItemId: cabinId,
          equipmentId: "77777777-7777-4777-8777-777777777777",
          label: "Кровать",
          quantity: "2",
          rentalMonths: 3,
          unitPriceRubles: "700",
          amountRubles: "4200",
          pricingVersion: 6,
        },
        {
          kind: "DELIVERY",
          rentalItemId: null,
          equipmentId: null,
          label: "Доставка",
          quantity: "1",
          rentalMonths: null,
          unitPriceRubles: "12000",
          amountRubles: "12000",
          pricingVersion: null,
        },
      ],
      totalRubles: "41700",
    },
    ...overrides,
  }
}
