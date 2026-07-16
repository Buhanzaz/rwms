import { afterEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import { listWarehouses } from "@/api/warehouse-api"

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("warehouse HTTP API", () => {
  it("requires a token instead of falling back to a browser mock", async () => {
    await expect(listWarehouses(null)).rejects.toThrow("токен")
  })

  it("uses the gateway warehouse route and Bearer token", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify([
          {
            id: "00000000-0000-0000-0000-000000000001",
            version: 0,
            code: "WH_00000000000000000000000000000001",
            name: "СПБ",
            city: "Санкт-Петербург",
            address: null,
            timeZone: "Europe/Moscow",
            active: true,
            sortOrder: null,
          },
        ]),
        { status: 200, headers: { "content-type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(listWarehouses("access-token")).resolves.toMatchObject([
      { id: "00000000-0000-0000-0000-000000000001", active: true },
    ])
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe(
      `${getGatewayRuntimeConfig().warehouseApiBaseUrl}/v1/warehouses`
    )
    expect(fetchMock.mock.calls[0]?.[1]).toEqual(
      expect.objectContaining({
        headers: expect.any(Headers),
      })
    )
    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(new Headers(request.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
  })

  it("surfaces an upstream conflict without replacing local data", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ detail: "Конфликт версий" }), {
          status: 409,
          headers: { "content-type": "application/problem+json" },
        })
      )
    )

    let failure: unknown
    try {
      await listWarehouses("access-token")
    } catch (error) {
      failure = error
    }
    expect(failure).toBeInstanceOf(ApiError)
    expect((failure as ApiError).status).toBe(409)
  })
})
