import { describe, expect, it } from "vitest"

import type { KpiPalette } from "@/features/settings/kpi/api/kpi-settings-api"
import {
  nextTaskTimerTransitionAt,
  paletteColorForRemainingPercent,
  paletteForTaskBoard,
  taskTimerAt,
} from "@/features/task-board/domain/task-board-kpi-presentation"
import type { TaskBoardEntryDto } from "@/features/task-board/model/task-board"

const palette: KpiPalette = {
  version: 3,
  ranges: [
    { fromPercent: 0, toPercent: 35, color: "#DC2626" },
    { fromPercent: 35, toPercent: 70, color: "#EAB308" },
    { fromPercent: 70, toPercent: 100, color: "#16A34A" },
  ],
  overdueColor: "#7F1D1D",
}

function entry(patch: Partial<TaskBoardEntryDto> = {}): TaskBoardEntryDto {
  return {
    id: "entry-1",
    version: 1,
    warehouseId: "warehouse-1",
    queueKey: "repair",
    queueId: "queue-1",
    entryType: "REAL",
    routeIndex: 0,
    routeLength: 1,
    queuePosition: 0,
    taskId: "task-1",
    externalTaskId: null,
    source: null,
    taskVersion: 1,
    title: "Ремонт",
    unitNumber: "БЫТ-001",
    taskStatus: "ACTIVE",
    scheduledDate: "2026-07-30",
    priority: 3,
    pinned: false,
    status: "IN_PROGRESS",
    taskText: null,
    plannedDurationMinutes: 20,
    activeStartedAt: "2026-07-30T07:00:00Z",
    pausedAt: null,
    activeWorkSeconds: 600,
    timerSnapshot: {
      countedActiveSeconds: 600,
      remainingSeconds: 600,
      remainingPercent: 50,
      timerState: "WORKING",
      nextTransitionAt: null,
      serverTime: "2026-07-30T07:10:00Z",
    },
    assignments: [],
    detailsHref: null,
    ...patch,
  }
}

describe("task-board KPI palette", () => {
  it("uses the global palette regardless of the warehouse work-schedule status", () => {
    expect(paletteForTaskBoard({ palette })).toBe(palette)
    expect(paletteForTaskBoard({ palette: null })).toBeNull()
  })

  it("uses the upper segment on a shared boundary and clamps values above 100", () => {
    expect(paletteColorForRemainingPercent(70, palette)).toBe("#16A34A")
    expect(paletteColorForRemainingPercent(125, palette)).toBe("#16A34A")
    expect(paletteColorForRemainingPercent(35, palette)).toBe("#EAB308")
  })

  it("uses the dedicated overdue color below zero", () => {
    expect(paletteColorForRemainingPercent(-0.01, palette)).toBe("#7F1D1D")
    expect(paletteColorForRemainingPercent(0, palette)).toBe("#DC2626")
  })

  it("stays neutral without a configured palette or remaining percentage", () => {
    expect(paletteColorForRemainingPercent(50, null)).toBeNull()
    expect(paletteColorForRemainingPercent(null, palette)).toBeNull()
  })
})

describe("server task timer snapshot", () => {
  it("finds the earliest server schedule transition for a board refresh", () => {
    expect(
      nextTaskTimerTransitionAt([
        entry(),
        entry({
          id: "entry-2",
          timerSnapshot: {
            ...entry().timerSnapshot!,
            nextTransitionAt: "2026-07-30T07:15:00Z",
          },
        }),
        entry({
          id: "entry-3",
          timerSnapshot: {
            ...entry().timerSnapshot!,
            nextTransitionAt: "2026-07-30T07:12:00Z",
          },
        }),
      ])
    ).toBe(Date.parse("2026-07-30T07:12:00Z"))
  })

  it("ticks counted and remaining time only while the server state is working", () => {
    expect(
      taskTimerAt(entry(), Date.parse("2026-07-30T07:11:00Z"))
    ).toMatchObject({
      countedActiveSeconds: 660,
      remainingSeconds: 540,
      remainingPercent: 45,
      timerState: "WORKING",
    })
  })

  it("derives live percentage from the server budget after a task return", () => {
    expect(
      taskTimerAt(
        entry({
          plannedDurationMinutes: 30,
          timerSnapshot: {
            countedActiveSeconds: 0,
            remainingSeconds: 600,
            remainingPercent: 100,
            timerState: "WORKING",
            nextTransitionAt: null,
            serverTime: "2026-07-30T07:10:00Z",
          },
        }),
        Date.parse("2026-07-30T07:11:00Z")
      ).remainingPercent
    ).toBe(90)
  })

  it("stops a working snapshot exactly at the next schedule transition", () => {
    expect(
      taskTimerAt(
        entry({
          timerSnapshot: {
            ...entry().timerSnapshot!,
            nextTransitionAt: "2026-07-30T07:15:00Z",
          },
        }),
        Date.parse("2026-07-30T07:20:00Z")
      )
    ).toMatchObject({
      countedActiveSeconds: 900,
      remainingSeconds: 300,
      remainingPercent: 25,
    })
  })

  it.each(["BREAK", "OFF_SHIFT", "PAUSED", "DONE"] as const)(
    "does not locally decrement a %s snapshot",
    (timerState) => {
      expect(
        taskTimerAt(
          entry({
            timerSnapshot: {
              ...entry().timerSnapshot!,
              timerState,
            },
          }),
          Date.parse("2026-07-30T08:10:00Z")
        )
      ).toMatchObject({
        countedActiveSeconds: 600,
        remainingSeconds: 600,
        remainingPercent: 50,
        timerState,
      })
    }
  )

  it("does not invent a local timer when the server snapshot is absent", () => {
    expect(
      taskTimerAt(
        entry({ timerSnapshot: null }),
        Date.parse("2026-07-30T07:11:00Z")
      )
    ).toMatchObject({
      countedActiveSeconds: 600,
      remainingSeconds: null,
      remainingPercent: null,
      timerState: null,
    })
  })
})
