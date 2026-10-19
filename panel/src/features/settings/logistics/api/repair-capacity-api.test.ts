import { afterEach, describe, expect, it, vi } from "vitest"

import {
  getRepairCapacity,
  updateRepairCapacity,
} from "@/features/settings/logistics/api/repair-capacity-api"
import { ApiError } from "@/lib/api-client"

const warehouseId = "warehouse/id"
const setting = {
  warehouseId,
  version: 4,
  repairPlaceCount: 6,
  createdAt: "2026-07-25T09:00:00Z",
  updatedAt: "2026-07-25T10:00:00Z",
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  })
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe("repair capacity API", () => {
  it("loads a warehouse setting through the public maintenance route", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(setting))

    await expect(
      getRepairCapacity("access-token", warehouseId)
    ).resolves.toEqual(setting)

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe(
      "/api/maintenance/v1/settings/repair-capacity/warehouse%2Fid"
    )
    expect(new Headers(init?.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
  })

  it("sends the complete repair-capacity settings in an authenticated PUT", async () => {
    const saved = {
      ...setting,
      version: 5,
      repairPlaceCount: 8,
    }
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(saved))

    await expect(
      updateRepairCapacity("access-token", warehouseId, {
        expectedVersion: 4,
        repairPlaceCount: 8,
      })
    ).resolves.toEqual(saved)

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe(
      "/api/maintenance/v1/settings/repair-capacity/warehouse%2Fid"
    )
    expect(init?.method).toBe("PUT")
    expect(new Headers(init?.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
    expect(JSON.parse(String(init?.body))).toEqual({
      expectedVersion: 4,
      repairPlaceCount: 8,
    })
  })

  it("propagates a 409 as ApiError", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(
        { detail: "Конфликт версий", code: "REPAIR_CAPACITY_CONFLICT" },
        409
      )
    )

    const request = updateRepairCapacity("access-token", warehouseId, {
      expectedVersion: 4,
      repairPlaceCount: 8,
    })

    await expect(request).rejects.toBeInstanceOf(ApiError)
    await expect(request).rejects.toMatchObject({
      status: 409,
      code: "REPAIR_CAPACITY_CONFLICT",
      message: "Конфликт версий",
    })
  })
})
