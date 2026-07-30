import { cleanup, render, screen, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { WarehouseKpiSettings } from "@/features/settings/kpi/api/kpi-settings-api"
import { WorkScheduleCard } from "@/features/settings/kpi/work-schedule-card"

afterEach(cleanup)

function settingsWithBreaks(
  breaks: { start: string; end: string }[]
): WarehouseKpiSettings {
  return {
    warehouseId: "00000000-0000-4000-8000-000000000001",
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
    activeSchedule: {
      id: "00000000-0000-4000-8000-000000000002",
      version: 1,
      effectiveFrom: "2026-07-31",
      shiftStart: "09:00",
      shiftEnd: "18:00",
      daysOff: [6, 7],
      breaks,
    },
    pendingSchedule: null,
  }
}

function renderCard(breaks: { start: string; end: string }[]) {
  render(
    <WorkScheduleCard
      settings={settingsWithBreaks(breaks)}
      today="2026-07-30"
      saving={false}
      deleting={false}
      blocked={false}
      actionError={null}
      onSave={vi.fn()}
      onDeletePending={vi.fn()}
    />
  )
}

describe("WorkScheduleCard workday preview", () => {
  it("shows every valid break as an accessible block with its time above the shift bar", () => {
    renderCard([
      { start: "10:30", end: "10:45" },
      { start: "13:00", end: "14:00" },
    ])

    const timeline = screen.getByRole("list", {
      name: "Шкала рабочей смены и перерывов",
    })
    const firstBreak = within(timeline).getByRole("listitem", {
      name: "Перерыв 1: 10:30–10:45",
    })

    expect(within(timeline).getByText("10:30–10:45")).toBeTruthy()
    expect(within(timeline).getByText("13:00–14:00")).toBeTruthy()
    expect(
      within(timeline).getByRole("listitem", {
        name: "Перерыв 2: 13:00–14:00",
      })
    ).toBeTruthy()
    expect(firstBreak.getAttribute("style")).toContain("left: 16.666")
    expect(firstBreak.getAttribute("style")).toContain("width: 2.777")
  })

  it("does not render invalid draft breaks or throw while time fields are incomplete", () => {
    renderCard([
      { start: "", end: "" },
      { start: "16:00", end: "15:45" },
      { start: "10:30", end: "10:45" },
    ])

    const timeline = screen.getByRole("list", {
      name: "Шкала рабочей смены и перерывов",
    })

    expect(
      within(timeline).getByRole("listitem", {
        name: "Перерыв 1: 10:30–10:45",
      })
    ).toBeTruthy()
    expect(within(timeline).queryAllByRole("listitem")).toHaveLength(1)
    expect(within(timeline).queryByText("–")).toBeNull()
  })
})
