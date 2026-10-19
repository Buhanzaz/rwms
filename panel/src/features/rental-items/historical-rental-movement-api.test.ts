import { afterEach, describe, expect, it, vi } from "vitest"

vi.mock("@/lib/gateway-config", () => ({
  getGatewayRuntimeConfig: () => ({
    logisticsApiBaseUrl: "https://gateway.example.test/api/logistics",
  }),
}))

import {
  createHistoricalRentalMovement,
  updateHistoricalRentalShipment,
} from "@/features/rental-items/historical-rental-movement-api"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const RENTAL_ITEM_ID = "22222222-2222-4222-8222-222222222222"
const CLIENT_ID = "33333333-3333-4333-8333-333333333333"
const DOCUMENT_ID = "44444444-4444-4444-8444-444444444444"
const IDEMPOTENCY_KEY = "55555555-5555-4555-8555-555555555555"
const DRIVER_WORKER_ID = "66666666-6666-4666-8666-666666666666"

afterEach(() => vi.unstubAllGlobals())

describe("historical rental movement API", () => {
  it("posts an explicit unknown driver pair without rejecting the shipment", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ id: DOCUMENT_ID, version: 4 }), {
        status: 201,
        headers: { "Content-Type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      createHistoricalRentalMovement({
        accessToken: "access-token",
        idempotencyKey: IDEMPOTENCY_KEY,
        input: {
          warehouseId: WAREHOUSE_ID,
          rentalItemId: RENTAL_ITEM_ID,
          expectedRentalItemVersion: 7,
          clientId: CLIENT_ID,
          driverSnapshot: null,
          driverWorkerId: null,
          kind: "SHIPMENT",
          occurredOn: "2026-08-01",
        },
      })
    ).resolves.toEqual({ id: DOCUMENT_ID, version: 4 })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      "/api/logistics/v1/historical-rental-movements"
    )
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init.body))).toEqual({
      warehouseId: WAREHOUSE_ID,
      rentalItemId: RENTAL_ITEM_ID,
      expectedRentalItemVersion: 7,
      clientId: CLIENT_ID,
      driverSnapshot: null,
      driverWorkerId: null,
      kind: "SHIPMENT",
      occurredOn: "2026-08-01",
    })
  })

  it("fails closed when the accepted document response lacks an identity", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ id: "not-a-uuid", version: 0 }), {
          headers: { "Content-Type": "application/json" },
        })
      )
    )

    await expect(
      createHistoricalRentalMovement({
        accessToken: "access-token",
        idempotencyKey: IDEMPOTENCY_KEY,
        input: {
          warehouseId: WAREHOUSE_ID,
          rentalItemId: RENTAL_ITEM_ID,
          expectedRentalItemVersion: 7,
          clientId: CLIENT_ID,
          driverSnapshot: null,
          driverWorkerId: null,
          kind: "RETURN",
          occurredOn: "2026-08-01",
        },
      })
    ).rejects.toThrow("некорректный документ")
  })

  it("puts a version-fenced correction into the existing historical shipment", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ id: DOCUMENT_ID, version: 6 }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      updateHistoricalRentalShipment({
        accessToken: "access-token",
        documentId: DOCUMENT_ID,
        idempotencyKey: IDEMPOTENCY_KEY,
        input: {
          expectedVersion: 5,
          rentalItemId: RENTAL_ITEM_ID,
          clientId: CLIENT_ID,
          driverSnapshot: "Иванов Иван",
          driverWorkerId: DRIVER_WORKER_ID,
          occurredOn: "2026-07-31",
        },
      })
    ).resolves.toEqual({ id: DOCUMENT_ID, version: 6 })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      `/api/logistics/v1/historical-rental-movements/${DOCUMENT_ID}`
    )
    expect(init.method).toBe("PUT")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init.body))).toEqual({
      expectedVersion: 5,
      rentalItemId: RENTAL_ITEM_ID,
      clientId: CLIENT_ID,
      driverSnapshot: "Иванов Иван",
      driverWorkerId: DRIVER_WORKER_ID,
      occurredOn: "2026-07-31",
    })
  })
})
