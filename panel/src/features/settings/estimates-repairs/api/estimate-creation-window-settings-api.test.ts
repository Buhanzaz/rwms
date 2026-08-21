import { beforeEach, describe, expect, it, vi } from "vitest"

const mocks = vi.hoisted(() => ({ bearerRequest: vi.fn() }))

vi.mock("@/lib/api-client", () => ({ bearerRequest: mocks.bearerRequest }))
vi.mock("@/lib/gateway-config", () => ({
  getGatewayRuntimeConfig: () => ({
    maintenanceApiBaseUrl: "/api/maintenance",
  }),
}))

import {
  getEstimateCreationWindow,
  updateEstimateCreationWindow,
} from "@/features/settings/estimates-repairs/api/estimate-creation-window-settings-api"

describe("estimate creation window API", () => {
  beforeEach(() => vi.clearAllMocks())

  it("loads and validates the warehouse-scoped setting", async () => {
    mocks.bearerRequest.mockResolvedValue({
      warehouseId: "warehouse/id",
      version: 0,
      days: 7,
      createdAt: null,
      updatedAt: null,
    })

    await expect(
      getEstimateCreationWindow("token", "warehouse/id")
    ).resolves.toMatchObject({ version: 0, days: 7 })
    expect(mocks.bearerRequest).toHaveBeenCalledWith(
      "token",
      "/api/maintenance/v1/settings/estimate-creation-window/warehouse%2Fid"
    )
  })

  it("sends an optimistic PUT", async () => {
    mocks.bearerRequest.mockResolvedValue({
      warehouseId: "warehouse-1",
      version: 4,
      days: 10,
      createdAt: "2026-08-21T08:00:00Z",
      updatedAt: "2026-08-21T09:00:00Z",
    })

    await updateEstimateCreationWindow("token", "warehouse-1", {
      expectedVersion: 3,
      days: 10,
    })

    expect(mocks.bearerRequest).toHaveBeenCalledWith(
      "token",
      "/api/maintenance/v1/settings/estimate-creation-window/warehouse-1",
      {
        method: "PUT",
        body: JSON.stringify({ expectedVersion: 3, days: 10 }),
      }
    )
  })

  it.each([0, 3651, 1.5])(
    "rejects invalid days %s before transport",
    async (days) => {
      await expect(
        updateEstimateCreationWindow("token", "warehouse-1", {
          expectedVersion: 0,
          days,
        })
      ).rejects.toThrow("от 1 до 3650")
      expect(mocks.bearerRequest).not.toHaveBeenCalled()
    }
  )

  it("rejects a malformed response", async () => {
    mocks.bearerRequest.mockResolvedValue({
      warehouseId: "warehouse-1",
      version: 0,
      days: 0,
      createdAt: null,
      updatedAt: null,
    })

    await expect(
      getEstimateCreationWindow("token", "warehouse-1")
    ).rejects.toThrow("некорректный срок")
  })

  it("rejects another warehouse response", async () => {
    mocks.bearerRequest.mockResolvedValue({
      warehouseId: "warehouse-2",
      version: 0,
      days: 7,
      createdAt: null,
      updatedAt: null,
    })

    await expect(
      getEstimateCreationWindow("token", "warehouse-1")
    ).rejects.toThrow("другого склада")
  })
})
