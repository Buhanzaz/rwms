import { afterEach, describe, expect, it, vi } from "vitest"
import {
  listOrderBookingChangeQuotes,
  waiveOrderBookingChangeQuote,
  listPendingBookingChangeQuotes,
} from "./order-booking-change-quotes-api"

const orderId = "11111111-1111-4111-8111-111111111111"
const quoteId = "22222222-2222-4222-8222-222222222222"
const quote = {
  quoteId,
  version: 4,
  bookingId: "33333333-3333-4333-8333-333333333333",
  bookingVersion: 9,
  operation: "CANCEL",
  oldSlotId: "44444444-4444-4444-8444-444444444444",
  slotId: null,
  slotVersion: null,
  amountRubles: "9223372036854775807",
  settlement: "PAYMENT_REQUIRED",
  applicationState: "OFFERED",
  testPaymentAvailable: false,
  supportPhone: "+79990000000",
  expiresAt: "2026-09-05T11:00:00Z",
  noticeDays: 2,
  deliveryDate: "2026-09-06",
  warehouseTimeZone: "Europe/Moscow",
  targetDeliveryDate: null,
  targetWindowStart: null,
  targetWindowEnd: null,
}
const command = {
  accessToken: "token",
  orderId,
  quoteId,
  expectedVersion: 4,
  reason: "  Подтверждённый форс-мажор  ",
  idempotencyKey: "55555555-5555-4555-8555-555555555555",
}

function response(body: unknown, status = 200) {
  const fetch = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    })
  )
  vi.stubGlobal("fetch", fetch)
  return fetch
}
afterEach(() => vi.unstubAllGlobals())

describe("order booking change quotes API", () => {
  it("loads exact owner quotes through the authenticated public order endpoint", async () => {
    const fetch = response([quote])
    await expect(
      listOrderBookingChangeQuotes("token", orderId)
    ).resolves.toEqual([quote])
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      `/api/logistics/v1/orders/${orderId}/booking-change-quotes`
    )
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer token")
  })

  it.each([null, "0"])(
    "keeps nullable fee %s distinct without calculating one",
    async (amountRubles) => {
      response([{ ...quote, amountRubles }])
      expect(
        (await listOrderBookingChangeQuotes("token", orderId))[0].amountRubles
      ).toBe(amountRubles)
    }
  )

  it("reads a reschedule with exact independent slot and booking fences", async () => {
    const reschedule = {
      ...quote,
      operation: "RESCHEDULE",
      slotId: "66666666-6666-4666-8666-666666666666",
      slotVersion: 12,
      targetDeliveryDate: "2026-09-08",
      targetWindowStart: "10:00:00",
      targetWindowEnd: "12:00:00",
    }
    response([reschedule])
    await expect(
      listOrderBookingChangeQuotes("token", orderId)
    ).resolves.toEqual([reschedule])
  })

  it.each([
    { quoteId: "bad" },
    { bookingVersion: Number.MAX_SAFE_INTEGER + 1 },
    { version: -1 },
    { amountRubles: 1500 },
    { amountRubles: "9223372036854775808" },
    { amountRubles: "2.5" },
    { amountRubles: undefined },
    { operation: "UPDATE" },
    { operation: "RESCHEDULE" },
    { slotVersion: undefined },
    { settlement: "PAID" },
    { applicationState: "COMPLETED" },
    { testPaymentAvailable: undefined },
    { supportPhone: "call support" },
    { deliveryDate: "2026-02-30" },
    { expiresAt: "2026-09-05T10:00:00" },
    { noticeDays: -1 },
    { warehouseTimeZone: "not/a/timezone" },
    { targetDeliveryDate: undefined },
    {
      operation: "RESCHEDULE",
      slotId: orderId,
      slotVersion: 1,
      targetDeliveryDate: "2026-09-08",
      targetWindowStart: "25:00",
      targetWindowEnd: "12:00",
    },
  ])("rejects malformed owner facts %j", async (invalid) => {
    response([{ ...quote, ...invalid }])
    await expect(
      listOrderBookingChangeQuotes("token", orderId)
    ).rejects.toMatchObject({ code: "INVALID_API_RESPONSE" })
  })

  it.each([{}, [quote, quote]])(
    "rejects invalid lists or duplicate quote identities",
    async (invalid) => {
      response(invalid)
      await expect(
        listOrderBookingChangeQuotes("token", orderId)
      ).rejects.toMatchObject({ code: "INVALID_API_RESPONSE" })
    }
  )

  it("posts only the quote fence and trimmed reason with stable caller identity", async () => {
    const waived = {
      ...quote,
      version: 5,
      amountRubles: "0",
      settlement: "WAIVED",
    }
    const fetch = response(waived)
    await expect(waiveOrderBookingChangeQuote(command)).resolves.toEqual(waived)
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      `/api/logistics/v1/orders/${orderId}/booking-change-quotes/${quoteId}/waiver`
    )
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      command.idempotencyKey
    )
    expect(JSON.parse(String(init.body))).toEqual({
      expectedVersion: 4,
      reason: "Подтверждённый форс-мажор",
    })
  })

  it.each([403, 409, 503])(
    "preserves owner HTTP %i and never fabricates a waiver",
    async (status) => {
      response(
        {
          title: "Command rejected",
          status,
          detail: "Owner rejected this command",
        },
        status
      )
      await expect(waiveOrderBookingChangeQuote(command)).rejects.toMatchObject(
        { status }
      )
    }
  )

  it("rejects a response for a different quote", async () => {
    response({ ...quote, quoteId: orderId })
    await expect(waiveOrderBookingChangeQuote(command)).rejects.toMatchObject({
      code: "INVALID_API_RESPONSE",
    })
  })

  it("loads the fee-only staff feed without fetching any full order", async () => {
    const item = {
      orderId,
      warehouseId: "77777777-7777-4777-8777-777777777777",
      quote,
    }
    const fetch = response([item])
    await expect(listPendingBookingChangeQuotes("token")).resolves.toEqual([
      item,
    ])
    expect(new URL(fetch.mock.calls[0][0]).pathname).toBe(
      "/api/logistics/v1/rental-booking-change-quotes"
    )
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it.each([{ orderId: "bad" }, { warehouseId: "bad" }, { quote: null }])(
    "rejects malformed pending envelope %j",
    async (invalid) => {
      response([
        {
          orderId,
          warehouseId: "77777777-7777-4777-8777-777777777777",
          quote,
          ...invalid,
        },
      ])
      await expect(
        listPendingBookingChangeQuotes("token")
      ).rejects.toMatchObject({ code: "INVALID_API_RESPONSE" })
    }
  )
})
