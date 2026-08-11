import { afterEach, describe, expect, it, vi } from "vitest"

import {
  createManualRentalInquiry,
  listRentalInquiriesForOrder,
} from "@/features/booking/api/rental-inquiries-api"

const CLIENT_ID = "11111111-1111-4111-8111-111111111111"
const ORDER_ID = "22222222-2222-4222-8222-222222222222"
const IDEMPOTENCY_KEY = "33333333-3333-4333-8333-333333333333"

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("order rental inquiries API", () => {
  it("lists only inquiries linked to the current rental order", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify([]), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await listRentalInquiriesForOrder({
      accessToken: "access-token",
      rentalOrderId: ORDER_ID,
    })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const endpoint = new URL(url)
    expect(endpoint.pathname).toBe("/api/logistics/v1/rental-inquiries")
    expect(endpoint.searchParams.get("rentalOrderId")).toBe(ORDER_ID)
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
  })

  it("creates a manual inquiry directly for the existing client and order", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ id: "inquiry-1" }), {
        status: 201,
        headers: { "Content-Type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await createManualRentalInquiry({
      accessToken: "access-token",
      clientId: CLIENT_ID,
      rentalOrderId: ORDER_ID,
      idempotencyKey: IDEMPOTENCY_KEY,
    })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe("/api/logistics/v1/rental-inquiries")
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init.body))).toEqual({
      conversationId: null,
      clientId: CLIENT_ID,
      rentalOrderId: ORDER_ID,
    })
  })
})
