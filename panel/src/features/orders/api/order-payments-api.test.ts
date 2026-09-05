import { afterEach, describe, expect, it, vi } from "vitest"

import { confirmOrderPayment, getOrderPayment } from "./order-payments-api"
import { orderPaymentFixture } from "@/features/orders/domain/order-payment.fixtures"

afterEach(() => vi.unstubAllGlobals())

describe("manager initial payment API", () => {
  it("reads the exact immutable bill through the authenticated public gateway", async () => {
    const payment = orderPaymentFixture()
    const fetch = vi.fn().mockResolvedValue(Response.json(payment))
    vi.stubGlobal("fetch", fetch)
    await expect(getOrderPayment("token", payment.orderId)).resolves.toEqual(
      payment
    )
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      "/api/logistics/v1/orders/" + payment.orderId + "/payment"
    )
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer token")
  })

  it("submits only the version fence and stable key, never a client-selected source or amount", async () => {
    const payment = orderPaymentFixture({
      state: "CONFIRMED",
      canConfirm: false,
      source: "MANAGER_CONFIRMATION",
    })
    const fetch = vi.fn().mockResolvedValue(Response.json(payment))
    vi.stubGlobal("fetch", fetch)
    await expect(
      confirmOrderPayment({
        accessToken: "token",
        orderId: payment.orderId,
        expectedVersion: 4,
        idempotencyKey: "11111111-1111-4111-8111-111111111111",
      })
    ).resolves.toEqual(payment)
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      "/api/logistics/v1/orders/" + payment.orderId + "/payment/confirm"
    )
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer token")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      "11111111-1111-4111-8111-111111111111"
    )
    expect(JSON.parse(String(init.body))).toEqual({ expectedVersion: 4 })
  })

  it("preserves expiry conflicts and rejects another order's response", async () => {
    const payment = orderPaymentFixture()
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          Response.json(
            { code: "ORDER_PAYMENT_NOT_PENDING", detail: "Срок оплаты истёк" },
            { status: 409 }
          )
        )
    )
    await expect(
      confirmOrderPayment({
        accessToken: "token",
        orderId: payment.orderId,
        expectedVersion: 4,
        idempotencyKey: "11111111-1111-4111-8111-111111111111",
      })
    ).rejects.toMatchObject({ status: 409, code: "ORDER_PAYMENT_NOT_PENDING" })
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(payment)))
    await expect(
      getOrderPayment("token", "22222222-2222-4222-8222-222222222222")
    ).rejects.toMatchObject({ code: "INVALID_API_RESPONSE" })
  })
})
