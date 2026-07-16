import { useState } from "react"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type {
  GroupScheduleDto,
  ScheduleDayDto,
} from "@/features/settings/task-board/model/task-board-settings"
import { ScheduleDayTimeline } from "@/features/settings/task-board/schedule-timeline"
import {
  applyScheduleDayChange,
  formatSchedulePeriodCount,
  validateScheduleDay,
} from "@/features/settings/task-board/schedule-timeline-utils"

const monday: ScheduleDayDto = {
  dayOfWeek: 1,
  enabled: true,
  linkedToTemplate: true,
  shiftStartsAt: "09:00",
  shiftEndsAt: "18:00",
  restPeriods: [
    {
      id: "smoke-1",
      version: 0,
      type: "SMOKE_BREAK",
      startsAt: "11:00",
      endsAt: "11:10",
      warningMinutes: 5,
      autoPause: true,
    },
    {
      id: "lunch-1",
      version: 0,
      type: "LUNCH",
      startsAt: "13:00",
      endsAt: "14:00",
      warningMinutes: 5,
      autoPause: true,
    },
  ],
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

function TimelineHarness({
  initial = monday,
  template = monday,
}: {
  initial?: ScheduleDayDto
  template?: ScheduleDayDto
}) {
  const [day, setDay] = useState(initial)
  return (
    <ScheduleDayTimeline
      day={day}
      templateDay={template}
      onChange={setDay}
      onCopy={vi.fn()}
    />
  )
}

describe("ScheduleDayTimeline", () => {
  it("moves a rest period by five minutes with the keyboard", () => {
    render(<TimelineHarness />)

    const period = screen.getByRole("button", {
      name: /Перекур 11:00–11:10/,
    })
    fireEvent.keyDown(period, { key: "ArrowRight" })

    expect(
      screen.getByRole("button", { name: /Перекур 11:05–11:15/ })
    ).toBeTruthy()
  })

  it("opens exact fields without losing the timeline interaction", () => {
    render(<TimelineHarness />)

    fireEvent.click(screen.getByRole("button", { name: /Перекур 11:00–11:10/ }))

    expect(screen.getByRole("dialog", { name: "Период отдыха" })).toBeTruthy()
    expect(screen.getByLabelText("Начало")).toHaveProperty("value", "11:00")
    expect(screen.getByLabelText("Окончание")).toHaveProperty("value", "11:10")
    expect(
      screen
        .getByLabelText("Автоматически ставить задания на паузу")
        .getAttribute("data-state")
    ).toBe("checked")
  })

  it("keeps resize hit areas at least 24px without covering the center control", () => {
    render(<TimelineHarness />)

    const start = screen.getByRole("button", {
      name: "Изменить начало: Перекур",
    })
    const end = screen.getByRole("button", {
      name: "Изменить окончание: Перекур",
    })
    expect(start.className.split(" ")).toContain("size-6")
    expect(start.className.split(" ")).toContain("-bottom-3")
    expect(end.className.split(" ")).toContain("size-6")
    expect(end.className.split(" ")).toContain("-top-3")

    fireEvent.click(screen.getByRole("button", { name: /Перекур 11:00–11:10/ }))
    expect(screen.getByRole("dialog", { name: "Период отдыха" })).toBeTruthy()
  })

  it("rejects overlap and periods outside the shift", () => {
    expect(
      validateScheduleDay({
        ...monday,
        restPeriods: [
          ...monday.restPeriods,
          {
            ...monday.restPeriods[0],
            id: "overlap",
            startsAt: "11:05",
            endsAt: "11:20",
          },
        ],
      })
    ).toBe("Периоды отдыха не должны пересекаться.")

    expect(
      validateScheduleDay({
        ...monday,
        restPeriods: [
          {
            ...monday.restPeriods[0],
            startsAt: "08:55",
            endsAt: "09:05",
          },
        ],
      })
    ).toBe("Перерыв должен находиться внутри смены.")
  })

  it("unlinks a directly edited day and restores it from the template", () => {
    const tuesday: ScheduleDayDto = {
      ...monday,
      dayOfWeek: 2,
      restPeriods: monday.restPeriods.map((period) => ({
        ...period,
        id: `${period.id}-tuesday`,
      })),
    }
    render(<TimelineHarness initial={tuesday} template={monday} />)

    fireEvent.change(document.querySelector("#shift-start-2")!, {
      target: { value: "08:00" },
    })
    const linked = screen.getByLabelText("Следовать шаблону понедельника")
    expect(linked.getAttribute("data-state")).toBe("unchecked")

    fireEvent.click(linked)
    expect(
      (document.querySelector("#shift-start-2") as HTMLInputElement).value
    ).toBe("09:00")
    expect(linked.getAttribute("data-state")).toBe("checked")
  })

  it("propagates template edits only to enabled linked days", () => {
    const tuesday: ScheduleDayDto = {
      ...monday,
      dayOfWeek: 2,
      restPeriods: monday.restPeriods.map((period) => ({
        ...period,
        id: `${period.id}-stable-tuesday`,
      })),
    }
    const wednesday = {
      ...tuesday,
      dayOfWeek: 3 as const,
      linkedToTemplate: false,
    }
    const saturday = { ...tuesday, dayOfWeek: 6 as const, enabled: false }
    const schedule: GroupScheduleDto = {
      id: "schedule",
      version: 0,
      warehouseId: "warehouse",
      workerGroupId: "group",
      timezone: "Europe/Moscow",
      returnGraceMinutes: 3,
      days: [monday, tuesday, wednesday, saturday],
    }

    const changed = applyScheduleDayChange(schedule, {
      ...monday,
      shiftStartsAt: "08:30",
      restPeriods: [monday.restPeriods[0]],
    })

    expect(changed.days[1].shiftStartsAt).toBe("08:30")
    expect(changed.days[1].restPeriods[0].id).toBe("smoke-1-stable-tuesday")
    expect(changed.days[2].shiftStartsAt).toBe("09:00")
    expect(changed.days[3].shiftStartsAt).toBe("09:00")
  })

  it("uses Russian plural forms for the period count", () => {
    expect(formatSchedulePeriodCount(1)).toBe("1 период")
    expect(formatSchedulePeriodCount(2)).toBe("2 периода")
    expect(formatSchedulePeriodCount(5)).toBe("5 периодов")
    expect(formatSchedulePeriodCount(11)).toBe("11 периодов")
    expect(formatSchedulePeriodCount(21)).toBe("21 период")
  })
})
