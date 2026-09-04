import { afterEach, describe, expect, it, vi } from "vitest"

import {
  getRepairComplexityColors,
  saveRepairComplexityColors,
} from "@/features/settings/estimates-repairs/api/repair-complexity-colors-api"

vi.mock("@/lib/gateway-config", () => ({
  getGatewayRuntimeConfig: () => ({
    maintenanceApiBaseUrl: "https://panel.example.test/api/maintenance",
  }),
}))

function json(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  })
}

afterEach(() => vi.unstubAllGlobals())

describe("repair complexity colors API", () => {
  it("uses the global same-origin maintenance setting without warehouse context", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      json({
        version: 3,
        lightColor: "#22C55E",
        mediumColor: "#EAB308",
        complexColor: "#F97316",
        capitalColor: "#DC2626",
        updatedAt: "2026-07-30T10:00:00Z",
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await getRepairComplexityColors("token")

    const endpoint = new URL(String(fetchMock.mock.calls[0]?.[0]))
    expect(endpoint.origin).toBe("https://panel.example.test")
    expect(endpoint.pathname).toBe(
      "/api/maintenance/v1/settings/repair-complexity-colors"
    )
    expect(endpoint.searchParams.has("warehouseId")).toBe(false)
  })

  it("sends optimistic version and all four colors", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      json({
        version: 4,
        lightColor: "#16A34A",
        mediumColor: "#EAB308",
        complexColor: "#F97316",
        capitalColor: "#DC2626",
        updatedAt: "2026-07-30T10:05:00Z",
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await saveRepairComplexityColors("token", {
      version: 3,
      lightColor: "#16A34A",
      mediumColor: "#EAB308",
      complexColor: "#F97316",
      capitalColor: "#DC2626",
    })

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(init.method).toBe("PUT")
    expect(JSON.parse(String(init.body))).toEqual({
      expectedVersion: 3,
      lightColor: "#16A34A",
      mediumColor: "#EAB308",
      complexColor: "#F97316",
      capitalColor: "#DC2626",
    })
  })
})
