import { afterEach, describe, expect, it, vi } from "vitest"

vi.mock("@/lib/gateway-config", () => ({
  getGatewayRuntimeConfig: () => ({
    logisticsApiBaseUrl: "https://gateway.example.test/api/logistics",
  }),
}))

import {
  getManualBookingDraftHold,
  putManualBookingDraftHold,
} from "@/features/booking/api/manual-booking-drafts-api"

const DRAFT_ID = "11111111-1111-4111-8111-111111111111"
const WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const CABIN_ID = "33333333-3333-4333-8333-333333333333"
const IDEMPOTENCY_KEY = "44444444-4444-4444-8444-444444444444"

function response() {
  return new Response(
    JSON.stringify({
      draftId: DRAFT_ID,
      warehouseId: WAREHOUSE_ID,
      expiresAt: "2026-08-04T12:00:00Z",
      rentalItemIds: [CABIN_ID],
    }),
    { status: 200, headers: { "Content-Type": "application/json" } }
  )
}

afterEach(() => vi.unstubAllGlobals())

describe("manual booking draft holds API", () => {
  it("creates the draft hold with the exact idempotent PUT payload", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response())
    vi.stubGlobal("fetch", fetchMock)

    await putManualBookingDraftHold({
      accessToken: "access-token",
      draftId: DRAFT_ID,
      idempotencyKey: IDEMPOTENCY_KEY,
      warehouseId: WAREHOUSE_ID,
      rentalItemIds: [CABIN_ID],
    })

    const [input, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(input).pathname).toBe(
      `/api/logistics/v1/manual-booking-drafts/${DRAFT_ID}/holds`
    )
    expect(init.method).toBe("PUT")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init.body))).toEqual({
      warehouseId: WAREHOUSE_ID,
      rentalItemIds: [CABIN_ID],
    })
  })

  it("refreshes the same hold through the warehouse-scoped GET", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response())
    vi.stubGlobal("fetch", fetchMock)

    await getManualBookingDraftHold({
      accessToken: "access-token",
      draftId: DRAFT_ID,
      warehouseId: WAREHOUSE_ID,
    })

    const [input, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const url = new URL(input)
    expect(url.pathname).toBe(
      `/api/logistics/v1/manual-booking-drafts/${DRAFT_ID}/holds`
    )
    expect(url.searchParams.get("warehouseId")).toBe(WAREHOUSE_ID)
    expect(init.method).toBeUndefined()
  })

  it("rejects a response that belongs to another draft", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            draftId: "99999999-9999-4999-8999-999999999999",
            warehouseId: WAREHOUSE_ID,
            expiresAt: "2026-08-04T12:00:00Z",
            rentalItemIds: [CABIN_ID],
          }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        )
      )
    )

    await expect(
      getManualBookingDraftHold({
        accessToken: "access-token",
        draftId: DRAFT_ID,
        warehouseId: WAREHOUSE_ID,
      })
    ).rejects.toThrow("некорректный ответ")
  })

  it("accepts an expired GET projection with no remaining holds", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            draftId: DRAFT_ID,
            warehouseId: WAREHOUSE_ID,
            expiresAt: null,
            rentalItemIds: [],
          }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        )
      )
    )

    await expect(
      getManualBookingDraftHold({
        accessToken: "access-token",
        draftId: DRAFT_ID,
        warehouseId: WAREHOUSE_ID,
      })
    ).resolves.toEqual({
      draftId: DRAFT_ID,
      warehouseId: WAREHOUSE_ID,
      expiresAt: null,
      rentalItemIds: [],
    })
  })
})
