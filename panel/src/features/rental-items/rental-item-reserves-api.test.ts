import { afterEach, describe, expect, it, vi } from "vitest"
import {
  getRentalItemReserves,
  type RentalItemReserves,
} from "./rental-item-reserves-api"

const cabinId = "11111111-1111-4111-8111-111111111111"
const warehouseId = "22222222-2222-4222-8222-222222222222"
function response(): RentalItemReserves {
  return {
    rentalItemId: cabinId,
    warehouseId,
    serverTime: "2026-09-05T12:00:00Z",
    reserves: [
      {
        reservationId: "33333333-3333-4333-8333-333333333333",
        kind: "SELECTION_HOLD",
        source: "CUSTOMER",
        createdAt: "2026-09-05T11:59:00Z",
        expiresAt: "2026-09-05T12:04:00Z",
        clientDisplayName: null,
        managerDisplayName: null,
        orderId: null,
        orderNumber: null,
        orderStatus: null,
        paymentState: null,
        canOpenOrder: false,
      },
    ],
  }
}
afterEach(() => vi.unstubAllGlobals())

describe("cabin reserve gateway read", () => {
  it("reads only the selected cabin and warehouse with authentication and no-store", async () => {
    const body = response()
    const fetch = vi.fn().mockResolvedValue(Response.json(body))
    vi.stubGlobal("fetch", fetch)
    await expect(
      getRentalItemReserves("token", cabinId, warehouseId)
    ).resolves.toEqual(body)
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      `/api/logistics/v1/rental-items/${cabinId}/reserves`
    )
    expect(new URL(url).searchParams.get("warehouseId")).toBe(warehouseId)
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer token")
    expect(init.cache).toBe("no-store")
  })

  it.each([
    "wrong-cabin",
    "wrong-warehouse",
    "missing-metadata",
    "invalid-state",
    "link-without-order",
    "duplicate-id",
  ])(
    "rejects %s instead of silently rendering no reserves",
    async (failure) => {
      const body = response()
      if (failure === "wrong-cabin") body.rentalItemId = warehouseId
      if (failure === "wrong-warehouse") body.warehouseId = cabinId
      if (failure === "missing-metadata")
        delete (body.reserves[0] as Partial<(typeof body.reserves)[0]>)
          .clientDisplayName
      if (failure === "invalid-state")
        Object.assign(body.reserves[0], { paymentState: "PAID" })
      if (failure === "link-without-order") body.reserves[0].canOpenOrder = true
      if (failure === "duplicate-id") body.reserves.push(body.reserves[0])
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(body)))
      await expect(
        getRentalItemReserves("token", cabinId, warehouseId)
      ).rejects.toMatchObject({ code: "INVALID_API_RESPONSE" })
    }
  )

  it("preserves a dependency failure instead of returning an empty list", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          Response.json({ detail: "Резервы недоступны" }, { status: 503 })
        )
    )
    await expect(
      getRentalItemReserves("token", cabinId, warehouseId)
    ).rejects.toMatchObject({ status: 503 })
  })
})
