import { afterEach, describe, expect, it, vi } from "vitest"

import {
  activateKpiSettings,
  deletePendingWorkSchedule,
  getKpiSettings,
  saveKpiPalette,
  saveRepairComplexity,
  saveWorkSchedule,
} from "@/features/settings/kpi/api/kpi-settings-api"

const warehouseId = "warehouse/id"
const settings = {
  warehouseId,
  timeZone: "Europe/Moscow",
  status: "DRAFT",
  version: 3,
  dataAvailableFrom: null,
  repairComplexity: {
    lightBoundaryMinutes: 60,
    mediumBoundaryMinutes: 180,
    complexBoundaryMinutes: 360,
  },
  palette: null,
  activeSchedule: null,
  pendingSchedule: null,
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers:
      status === 204 ? undefined : { "content-type": "application/json" },
  })
}

afterEach(() => vi.restoreAllMocks())

describe("KPI settings API", () => {
  it("loads warehouse settings from the public task-board route", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(settings))

    await expect(getKpiSettings("access-token", warehouseId)).resolves.toEqual(
      settings
    )

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe(
      "/api/task-board/warehouses/warehouse%2Fid/task-board/kpi-settings"
    )
    expect(new Headers(init?.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
  })

  it("saves an atomic palette with the aggregate expectedVersion", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse({ ...settings, version: 4 }))
    const input = {
      expectedVersion: 3,
      ranges: [
        { fromPercent: 0, toPercent: 35, color: "#DC2626" },
        { fromPercent: 35, toPercent: 100, color: "#16A34A" },
      ],
      overdueColor: "#7F1D1D",
    }

    await saveKpiPalette("access-token", warehouseId, input)

    const [url, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(url)).pathname).toBe(
      "/api/task-board/warehouses/warehouse%2Fid/task-board/kpi-settings/palette"
    )
    expect(init?.method).toBe("PUT")
    expect(JSON.parse(String(init?.body))).toEqual(input)
  })

  it("saves repair complexity boundaries in canonical minutes", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse({ ...settings, version: 4 }))
    const input = {
      expectedVersion: 3,
      lightBoundaryMinutes: 60,
      mediumBoundaryMinutes: 180,
      complexBoundaryMinutes: 360,
    }

    await saveRepairComplexity("access-token", warehouseId, input)

    const [url, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(url)).pathname).toBe(
      "/api/task-board/warehouses/warehouse%2Fid/task-board/kpi-settings/repair-complexity"
    )
    expect(init?.method).toBe("PUT")
    expect(JSON.parse(String(init?.body))).toEqual(input)
  })

  it("saves, removes and activates a scheduled configuration", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse({ ...settings, version: 4 }))
      .mockResolvedValueOnce(jsonResponse(undefined, 204))
      .mockResolvedValueOnce(
        jsonResponse({ ...settings, version: 5, status: "ACTIVE" })
      )

    await saveWorkSchedule("access-token", warehouseId, {
      expectedVersion: 3,
      effectiveFrom: "2026-08-01",
      shiftStart: "08:00",
      shiftEnd: "17:00",
      daysOff: [6, 7],
      breaks: [{ start: "13:00", end: "14:00" }],
    })
    await deletePendingWorkSchedule("access-token", warehouseId, 4)
    await activateKpiSettings(
      "access-token",
      warehouseId,
      4,
      "00000000-0000-4000-8000-000000000099"
    )

    expect(
      fetchMock.mock.calls.map(([url]) => new URL(String(url)).pathname)
    ).toEqual([
      "/api/task-board/warehouses/warehouse%2Fid/task-board/kpi-settings/work-schedule",
      "/api/task-board/warehouses/warehouse%2Fid/task-board/kpi-settings/work-schedule/pending",
      "/api/task-board/warehouses/warehouse%2Fid/task-board/kpi-settings/activate",
    ])
    expect(new URL(String(fetchMock.mock.calls[1]![0])).search).toBe(
      "?expectedVersion=4"
    )
    expect(fetchMock.mock.calls[1]![1]?.method).toBe("DELETE")
    expect(JSON.parse(String(fetchMock.mock.calls[2]![1]?.body))).toEqual({
      expectedVersion: 4,
    })
    expect(
      new Headers(fetchMock.mock.calls[2]![1]?.headers).get("Idempotency-Key")
    ).toBe("00000000-0000-4000-8000-000000000099")
  })
})
