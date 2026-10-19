import { describe, expect, it } from "vitest"

import {
  formatPaymentCountdown,
  formatReceiptRubles,
  observeOrderPayment,
  parseOrderPayment,
  paymentAllowsFulfillment,
  paymentRefetchInterval,
  paymentRemainingMilliseconds,
} from "@/features/orders/domain/order-payment"
import { orderPaymentFixture } from "@/features/orders/domain/order-payment.fixtures"

describe("initial order payment boundary", () => {
  it("retains immutable amounts, furniture unit-month factors and delivery charged once", () => {
    const source = orderPaymentFixture()
    expect(parseOrderPayment(source, source.orderId)).toEqual(source)
    expect(source.receipt?.lines[1]).toMatchObject({
      quantity: "2",
      unitPriceRubles: "700",
      rentalMonths: 3,
      amountRubles: "4200",
    })
  })

  it("preserves absent historical evidence without pretending it is paid or free", () => {
    const source = orderPaymentFixture({
      state: null,
      startedAt: null,
      expiresAt: null,
      receipt: null,
      canConfirm: false,
    })
    expect(parseOrderPayment(source)).toEqual(source)
    expect(paymentAllowsFulfillment(source)).toBe(true)
    expect(paymentAllowsFulfillment(undefined)).toBe(false)
    expect(paymentAllowsFulfillment({ ...source, orderStatus: "DRAFT" })).toBe(
      false
    )
  })

  it("keeps multiplication beyond int64 exact and renders the full 80-digit amount range", () => {
    const source = orderPaymentFixture()
    const receipt = source.receipt!
    const rentalPrice = "9223372036854775807"
    const lines = [
      {
        ...receipt.lines[0],
        unitPriceRubles: rentalPrice,
        amountRubles: (BigInt(rentalPrice) * 3n).toString(),
      },
      {
        ...receipt.lines[1],
        quantity: rentalPrice,
        unitPriceRubles: rentalPrice,
        amountRubles: (
          BigInt(rentalPrice) *
          BigInt(rentalPrice) *
          3n
        ).toString(),
      },
    ]
    const totalRubles = lines
      .reduce((sum, line) => sum + BigInt(line.amountRubles), 0n)
      .toString()
    const parsed = parseOrderPayment({
      ...source,
      receipt: { ...receipt, deliveryIncluded: false, lines, totalRubles },
    })
    expect(parsed.receipt?.totalRubles).toBe(totalRubles)
    const maximum = "9".repeat(80)
    expect(formatReceiptRubles(maximum).replace(/\s/g, "")).toBe(maximum + "₽")
  })

  it.each([
    { receipt: undefined },
    { state: undefined },
    { serverTime: "2026-09-05T12:00:00" },
    { orderVersion: -1 },
    { expiresAt: "2026-09-05T12:10:00Z" },
    { state: "CONFIRMED" },
    { orderId: "a-different-order" },
  ])("rejects invalid or incomplete evidence: %j", (invalid) => {
    expect(() =>
      parseOrderPayment({ ...orderPaymentFixture(), ...invalid })
    ).toThrow()
  })

  it("rejects a mismatched order and rounded or inconsistent receipt facts", () => {
    const source = orderPaymentFixture()
    expect(() =>
      parseOrderPayment(source, "11111111-1111-4111-8111-111111111111")
    ).toThrow()
    for (const invalid of [
      { totalRubles: 41700 },
      { totalRubles: "0" },
      { deliveryIncluded: false },
      { lines: [{ ...source.receipt!.lines[0], unitPriceRubles: 8500 }] },
      { lines: [{ ...source.receipt!.lines[0], rentalMonths: null }] },
    ]) {
      expect(() =>
        parseOrderPayment({
          ...source,
          receipt: { ...source.receipt, ...invalid },
        })
      ).toThrow()
    }
  })

  it("admits fulfillment only for confirmed or explicitly historical saved orders", () => {
    expect(paymentAllowsFulfillment(orderPaymentFixture())).toBe(false)
    expect(
      paymentAllowsFulfillment(
        orderPaymentFixture({ state: "CONFIRMED", canConfirm: false })
      )
    ).toBe(true)
    for (const state of ["EXPIRING", "EXPIRED", "CANCELLED"] as const) {
      expect(
        paymentAllowsFulfillment(
          orderPaymentFixture({ state, canConfirm: false })
        )
      ).toBe(false)
    }
  })
})

describe("server-anchored countdown", () => {
  it("subtracts monotonic elapsed time without using the device wall clock", () => {
    const observation = observeOrderPayment(
      orderPaymentFixture({ serverTime: "2026-09-05T12:02:30Z" }),
      40_000
    )
    expect(paymentRemainingMilliseconds(observation, 40_000)).toBe(150_000)
    expect(paymentRemainingMilliseconds(observation, 42_500)).toBe(147_500)
    expect(formatPaymentCountdown(147_500)).toBe("02:28")
    expect(paymentRemainingMilliseconds(observation, 240_000)).toBe(0)
  })

  it("a reload and newer server snapshot never restart the five-minute window", () => {
    const observation = observeOrderPayment(
      orderPaymentFixture({ serverTime: "2026-09-05T12:04:59Z" }),
      20
    )
    expect(
      formatPaymentCountdown(paymentRemainingMilliseconds(observation, 20)!)
    ).toBe("00:01")
    expect(
      formatPaymentCountdown(paymentRemainingMilliseconds(observation, 1_020)!)
    ).toBe("00:00")
    expect(paymentRefetchInterval(observation.payment)).toBe(2_000)
    expect(
      paymentRefetchInterval(
        orderPaymentFixture({ state: "CONFIRMED", canConfirm: false })
      )
    ).toBe(false)
  })
})
