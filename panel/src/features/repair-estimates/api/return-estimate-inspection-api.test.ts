import { afterEach, describe, expect, it, vi } from "vitest"

import {
  awaitReturnEstimateInspection,
  getReturnEstimateInspection,
} from "@/features/repair-estimates/api/return-estimate-inspection-api"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const estimateId = "00000000-0000-4000-8000-000000000002"

afterEach(() => vi.unstubAllGlobals())

describe("return estimate inventory inspection", () => {
  it("uses the public inventory route and preserves confirmed evidence", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          state: "CONFIRMED",
          inventoryId: "inventory-1",
          findingId: "finding-1",
          cabinNumber: "CAB-17",
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    const result = await getReturnEstimateInspection(
      "token",
      warehouseId,
      estimateId
    )

    expect(result).toMatchObject({ state: "CONFIRMED", cabinNumber: "CAB-17" })
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(
      `http://localhost:3000/api/inventory/v1/return-estimates/${estimateId}/inspection?warehouseId=${warehouseId}`
    )
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer token")
  })

  it("retries only pending reads within the supplied bounded schedule", async () => {
    const responses = ["PENDING", "PENDING", "CONFIRMED"]
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              state: responses.shift(),
              inventoryId: null,
              findingId: null,
              cabinNumber: responses.length === 0 ? "CAB-17" : null,
            }),
            { status: 200, headers: { "Content-Type": "application/json" } }
          )
        )
      )
    )
    const wait = vi.fn(async (_delayMs: number) => undefined)

    const result = await awaitReturnEstimateInspection(
      "token",
      warehouseId,
      estimateId,
      { delaysMs: [100, 200, 400], wait }
    )

    expect(result.state).toBe("CONFIRMED")
    expect(wait.mock.calls.map(([delayMs]) => delayMs)).toEqual([100, 200])
  })
})
