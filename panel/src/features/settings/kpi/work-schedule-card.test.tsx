import { cleanup, render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { KpiSettingsResponse } from "@/features/settings/kpi/api/kpi-settings-api"
import { WorkScheduleCard } from "@/features/settings/kpi/work-schedule-card"

afterEach(cleanup)

function settingsWithBreaks(
  breaks: { start: string; end: string }[]
): KpiSettingsResponse {
  return {
    status: "DRAFT",
    version: 3,
    dataAvailableFrom: null,
    minimumEffectiveDate: "2026-07-30",
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
  it("allows saving a schedule effective today", async () => {
    const onSave = vi.fn()
    render(
      <WorkScheduleCard
        settings={{
          ...settingsWithBreaks([]),
          activeSchedule: null,
          pendingSchedule: null,
        }}
        today="2026-07-30"
        saving={false}
        deleting={false}
        blocked={false}
        actionError={null}
        onSave={onSave}
        onDeletePending={vi.fn()}
      />
    )

    const effectiveDate = screen.getByLabelText(
      "Дата вступления графика"
    ) as HTMLInputElement
    expect(effectiveDate.min).toBe("2026-07-30")
    expect(effectiveDate.value).toBe("2026-07-30")

    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: "Сохранить график" }))

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({ effectiveFrom: "2026-07-30" })
    )
  })

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
