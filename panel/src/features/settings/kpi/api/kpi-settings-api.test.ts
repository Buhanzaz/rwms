import { afterEach, describe, expect, it, vi } from "vitest"

import {
  activateKpiSettings,
  deletePendingWorkSchedule,
  getKpiPalette,
  getKpiSettings,
  saveKpiPalette,
  saveWorkSchedule,
} from "@/features/settings/kpi/api/kpi-settings-api"

const settings = {
  status: "DRAFT",
  version: 3,
  dataAvailableFrom: null,
  minimumEffectiveDate: "2026-09-04",
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
  it("loads global settings from the public task-board route", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(settings))

    await expect(getKpiSettings("access-token")).resolves.toEqual(settings)

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe("/api/task-board/kpi-settings")
    expect(new Headers(init?.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
  })

  it("loads and saves the one palette shared by every object", async () => {
    const palette = {
      version: 1,
      ranges: [{ fromPercent: 0, toPercent: 100, color: "#16A34A" }],
      overdueColor: "#7F1D1D",
      problemColor: "#FF3B30",
      completedColor: "#238636",
    }
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        jsonResponse({
          version: 3,
          palette,
        })
      )
      .mockResolvedValueOnce(
        jsonResponse({
          version: 4,
          palette,
        })
      )
    const input = {
      expectedVersion: 3,
      ranges: [
        { fromPercent: 0, toPercent: 35, color: "#DC2626" },
        { fromPercent: 35, toPercent: 100, color: "#16A34A" },
      ],
      overdueColor: "#7F1D1D",
      problemColor: "#FF3B30",
      completedColor: "#238636",
    }

    await expect(getKpiPalette("access-token")).resolves.toEqual({
      version: 3,
      palette,
    })
    await saveKpiPalette("access-token", input)

    expect(
      fetchMock.mock.calls.map(([url]) => new URL(String(url)).pathname)
    ).toEqual(["/api/task-board/kpi-palette", "/api/task-board/kpi-palette"])
    expect(fetchMock.mock.calls[1]![1]?.method).toBe("PUT")
    expect(JSON.parse(String(fetchMock.mock.calls[1]![1]?.body))).toEqual(input)
  })

  it("rejects a malformed additive completed color instead of masking it with the default", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse({
        version: 3,
        palette: {
          version: 1,
          ranges: [{ fromPercent: 0, toPercent: 100, color: "#16A34A" }],
          overdueColor: "#7F1D1D",
          problemColor: "#FF3B30",
          completedColor: null,
        },
      })
    )

    await expect(getKpiPalette("access-token")).rejects.toThrow(
      "некорректную палитру KPI"
    )
  })

  it("saves, removes and activates a scheduled configuration", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse({ ...settings, version: 4 }))
      .mockResolvedValueOnce(jsonResponse(undefined, 204))
      .mockResolvedValueOnce(
        jsonResponse({ ...settings, version: 5, status: "ACTIVE" })
      )

    await saveWorkSchedule("access-token", {
      expectedVersion: 3,
      effectiveFrom: "2026-08-01",
      shiftStart: "08:00",
      shiftEnd: "17:00",
      daysOff: [6, 7],
      breaks: [{ start: "13:00", end: "14:00" }],
    })
    await deletePendingWorkSchedule("access-token", 4)
    await activateKpiSettings(
      "access-token",
      4,
      "00000000-0000-4000-8000-000000000099"
    )

    expect(
      fetchMock.mock.calls.map(([url]) => new URL(String(url)).pathname)
    ).toEqual([
      "/api/task-board/kpi-settings/work-schedule",
      "/api/task-board/kpi-settings/work-schedule/pending",
      "/api/task-board/kpi-settings/activate",
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
