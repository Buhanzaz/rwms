import { afterEach, describe, expect, it, vi } from "vitest"

import { getWarehouseQueueCapabilities } from "@/features/repair-estimates/api/warehouse-queue-capabilities"

vi.mock("@/lib/gateway-config", () => ({
  getGatewayRuntimeConfig: () => ({
    taskBoardApiBaseUrl: "https://panel.example.test/api/task-board",
  }),
}))

afterEach(() => vi.unstubAllGlobals())

describe("warehouse queue capabilities API", () => {
  it("reads capabilities through the public same-origin task-board route", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          warehouseId: "warehouse-1",
          movementQueueDefinitions: [],
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      getWarehouseQueueCapabilities("token", "warehouse/1")
    ).resolves.toEqual({
      warehouseId: "warehouse-1",
      movementQueueDefinitions: [],
    })

    const endpoint = new URL(String(fetchMock.mock.calls[0]?.[0]))
    expect(endpoint.origin).toBe("https://panel.example.test")
    expect(endpoint.pathname).toBe(
      "/api/task-board/warehouses/warehouse%2F1/queue-capabilities"
    )
  })
})
