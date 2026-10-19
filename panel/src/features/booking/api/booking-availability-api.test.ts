import { afterEach, describe, expect, it, vi } from "vitest"

vi.mock("@/lib/gateway-config", () => ({
  getGatewayRuntimeConfig: () => ({
    assetApiBaseUrl: "https://gateway.example.test/api/asset",
  }),
}))

import {
  checkRentalItemsAvailability,
  listAvailableRentalItems,
  unavailableRentalItemIds,
} from "@/features/booking/api/booking-availability-api"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const CABIN_ID = "22222222-2222-4222-8222-222222222222"
const TYPE_ID = "33333333-3333-4333-8333-333333333333"
const DIMENSION_ID = "44444444-4444-4444-8444-444444444444"
const FINISHING_ID = "55555555-5555-4555-8555-555555555555"
const CHARACTERISTIC_ID = "66666666-6666-4666-8666-666666666666"

function rentalItemResponse() {
  return {
    id: CABIN_ID,
    version: 3,
    warehouseId: WAREHOUSE_ID,
    number: "БЫТ-042",
    status: "FREE",
    rentalTypeId: TYPE_ID,
    rentalType: "БК-1",
    dimensionId: DIMENSION_ID,
    dimensions: "2.4x6",
    finishingId: FINISHING_ID,
    finishing: "ДВП",
    category: "Новая",
    characteristics: [{ id: CHARACTERISTIC_ID, name: "Окно" }],
    linoleum: true,
    generalComment: null,
    passport: {},
    tags: [],
    contents: [],
    activeOrderReservation: null,
    createdAt: "2026-08-03T08:00:00Z",
    updatedAt: "2026-08-03T09:00:00Z",
  }
}

function jsonResponse(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("booking availability API", () => {
  it("loads only the authoritative available page with number search", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        content: [rentalItemResponse()],
        page: 2,
        size: 200,
        totalElements: 401,
        totalPages: 3,
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    const page = await listAvailableRentalItems({
      accessToken: "access-token",
      warehouseId: WAREHOUSE_ID,
      page: 2,
      size: 200,
      search: "  БЫТ-042  ",
    })

    const [input, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const url = new URL(input)
    expect(url.pathname).toBe("/api/asset/v1/rental-items/available")
    expect(Object.fromEntries(url.searchParams)).toEqual({
      warehouseId: WAREHOUSE_ID,
      page: "2",
      size: "200",
      search: "БЫТ-042",
    })
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
    expect(page.content).toHaveLength(1)
    expect(page.content[0]).toMatchObject({
      id: CABIN_ID,
      number: "БЫТ-042",
      status: "FREE",
    })
  })

  it("posts exact selected ids and reports missing entries as unavailable", async () => {
    const missingId = "77777777-7777-4777-8777-777777777777"
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        warehouseId: WAREHOUSE_ID,
        items: [
          { rentalItemId: CABIN_ID, available: true, reason: "AVAILABLE" },
        ],
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    const response = await checkRentalItemsAvailability({
      accessToken: "access-token",
      warehouseId: WAREHOUSE_ID,
      rentalItemIds: [CABIN_ID, missingId],
    })

    const [input, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(input).pathname).toBe(
      "/api/asset/v1/rental-items/availability"
    )
    expect(init.method).toBe("POST")
    expect(JSON.parse(String(init.body))).toEqual({
      warehouseId: WAREHOUSE_ID,
      rentalItemIds: [CABIN_ID, missingId],
    })
    expect(unavailableRentalItemIds(response, [CABIN_ID, missingId])).toEqual([
      missingId,
    ])
  })

  it("rejects contradictory availability instead of trusting malformed data", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse({
          warehouseId: WAREHOUSE_ID,
          items: [
            {
              rentalItemId: CABIN_ID,
              available: false,
              reason: "AVAILABLE",
            },
          ],
        })
      )
    )

    await expect(
      checkRentalItemsAvailability({
        accessToken: "access-token",
        warehouseId: WAREHOUSE_ID,
        rentalItemIds: [CABIN_ID],
      })
    ).rejects.toThrow("некорректный ответ")
  })
})
