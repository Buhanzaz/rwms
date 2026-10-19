import { afterEach, describe, expect, it, vi } from "vitest"

import { getGroupKpi, listKpiWorkerGroups } from "@/features/kpi/api/kpi-api"

const warehouseId = "warehouse/id"

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
  })
}

afterEach(() => vi.restoreAllMocks())

describe("KPI read API", () => {
  it("loads analytics with selected period parameters", async () => {
    const response = {
      warehouseId,
      periodType: "QUARTER",
      periodStart: "2026-07-01",
      periodEnd: "2026-09-30",
      coverageStart: "2026-07-15",
      coverageEnd: "2026-09-30",
      status: "PARTIAL",
      dataAvailableFrom: "2026-07-15",
      formulaVersion: "v1",
      asOf: "2026-07-30T12:00:00Z",
      groups: [],
    }
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(response))

    await expect(
      getGroupKpi("access-token", warehouseId, {
        periodType: "QUARTER",
        year: 2026,
        month: 7,
        day: 30,
        quarter: 3,
      })
    ).resolves.toEqual(response)

    const url = new URL(String(fetchMock.mock.calls[0]![0]))
    expect(url.pathname).toBe(
      "/api/analytics/v1/warehouses/warehouse%2Fid/group-kpi"
    )
    expect(url.search).toBe("?periodType=QUARTER&year=2026&quarter=3")
  })

  it("loads the current group directory through task-board", async () => {
    const groups = [
      {
        id: "group-1",
        name: "Слесари",
        active: true,
      },
    ]
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(groups))

    await expect(
      listKpiWorkerGroups("access-token", warehouseId)
    ).resolves.toEqual(groups)
    expect(new URL(String(fetchMock.mock.calls[0]![0])).pathname).toBe(
      "/api/task-board/warehouses/warehouse%2Fid/worker-groups"
    )
  })
})
