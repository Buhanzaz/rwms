import { afterEach, describe, expect, it, vi } from "vitest"

import { createAssetRentalItem, listAssetRentalItems } from "@/api/asset-api"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const warehouseId = "00000000-0000-0000-0000-000000000001"
const rentalItem = {
  id: "00000000-0000-0000-0000-000000000101",
  version: 0,
  warehouseId,
  number: "CABIN-101",
  status: "FREE",
  rentalType: null,
  dimensions: null,
  finishing: null,
  category: null,
  characteristics: null,
  linoleum: null,
  generalComment: null,
  passport: {},
  tags: [],
  contents: [],
  createdAt: "2026-07-16T00:00:00Z",
  updatedAt: "2026-07-16T00:00:00Z",
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("asset HTTP API", () => {
  it("requires a token instead of using a browser fallback", async () => {
    await expect(listAssetRentalItems(null, { warehouseId })).rejects.toThrow(
      "токен"
    )
  })

  it("uses the asset gateway route and parses the canonical page", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          content: [rentalItem],
          page: 0,
          size: 50,
          totalElements: 1,
          totalPages: 1,
        }),
        { status: 200, headers: { "content-type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      listAssetRentalItems("access-token", { warehouseId })
    ).resolves.toMatchObject({ content: [{ id: rentalItem.id }] })

    const endpoint = new URL(String(fetchMock.mock.calls[0]?.[0]))
    expect(`${endpoint.origin}${endpoint.pathname}`).toBe(
      `${getGatewayRuntimeConfig().assetApiBaseUrl}/v1/rental-items`
    )
    expect(endpoint.searchParams.get("warehouseId")).toBe(warehouseId)
    expect(endpoint.searchParams.get("page")).toBe("0")
    expect(endpoint.searchParams.get("size")).toBe("50")
    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(new Headers(request.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
  })

  it("sends the idempotency key for a new rental item", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(rentalItem), {
        status: 201,
        headers: { "content-type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await createAssetRentalItem(
      "access-token",
      "00000000-0000-0000-0000-000000000901",
      {
        warehouseId,
        number: "CABIN-101",
        rentalType: null,
        dimensions: null,
        finishing: null,
        category: null,
        characteristics: null,
        linoleum: null,
        passport: {},
        tags: [],
      }
    )

    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(new Headers(request.headers).get("Idempotency-Key")).toBe(
      "00000000-0000-0000-0000-000000000901"
    )
    expect(JSON.parse(String(request.body))).toMatchObject({
      warehouseId,
      number: "CABIN-101",
    })
  })
})
