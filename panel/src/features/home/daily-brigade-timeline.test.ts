import { describe, expect, it } from "vitest"

import type { KpiWorkerGroup } from "@/features/kpi/api/kpi-api"
import type { KpiPalette } from "@/features/settings/kpi/api/kpi-settings-api"
import {
  dailyBrigadeRows,
  dailyShiftTimelineAt,
  remainingPercentLabel,
} from "@/features/home/daily-brigade-timeline"
import type { DailyBrigadeActivitySnapshot } from "@/features/home/daily-brigade-activity-api"
import type {
  TaskBoardEntryDto,
  TaskBoardSnapshotDto,
} from "@/features/task-board/model/task-board"

const palette: KpiPalette = {
  version: 1,
  ranges: [
    { fromPercent: 0, toPercent: 35, color: "#DC2626" },
    { fromPercent: 35, toPercent: 70, color: "#F59E0B" },
    { fromPercent: 70, toPercent: 100, color: "#16A34A" },
  ],
  overdueColor: "#7F1D1D",
}

const groups: KpiWorkerGroup[] = [
  { id: "group-active", name: "Бригада ремонта", active: true },
  { id: "group-inactive", name: "Старая бригада", active: false },
]

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
    source: { type: "MAINTENANCE_REPAIR", sourceId: "repair-1" },
    taskVersion: 1,
    title: "Ремонт бытовки",
    unitNumber: "БК-001",
    taskStatus: "ACTIVE",
    scheduledDate: "2026-08-24",
    priority: 2,
    pinned: false,
    suspended: false,
    status: "IN_PROGRESS",
    taskText: null,
    plannedDurationMinutes: 60,
    activeStartedAt: "2026-08-24T09:00:00Z",
    pausedAt: null,
    activeWorkSeconds: 1_200,
    timerSnapshot: {
      countedActiveSeconds: 1_200,
      remainingSeconds: 4_800,
      remainingPercent: 80,
      timerState: "WORKING",
      nextTransitionAt: null,
      serverTime: "2026-08-24T09:20:00Z",
    },
    assignments: [
      {
        id: "assignment-1",
        version: 1,
        workerId: "worker-1",
        workerName: "Алексей",
        workerGroupId: "group-active",
        workerGroupName: "Бригада ремонта",
        status: "ACTIVE",
        assignedAt: "2026-08-24T09:00:00Z",
        startedAt: "2026-08-24T09:00:00Z",
        pausedAt: null,
        finishedAt: null,
      },
    ],
    detailsHref: "/repairs?repairId=repair-1",
    ...patch,
  }
}

function board(entries: TaskBoardEntryDto[]): TaskBoardSnapshotDto {
  return {
    warehouseId: "warehouse-1",
    queues: [
      {
        key: "repair",
        version: 1,
        label: "Внутренний ремонт",
        kind: "REPAIR",
        settingsQueueId: "queue-1",
        settingsCollapsed: false,
        workerFeedEnabled: true,
        availableTaskLimit: 6,
        linkedQueueId: null,
        linkedQueueName: null,
        entries,
      },
    ],
    totalEntries: entries.length,
    realEntries: entries.length,
    shadowEntries: 0,
  }
}

function activity(
  intervals: DailyBrigadeActivitySnapshot["intervals"] = [
    {
      workerGroupId: "group-active",
      workerGroupName: "Бригада ремонта",
      taskId: "task-1",
      entryId: "entry-1",
      queueId: "queue-1",
      queueName: "Внутренний ремонт",
      title: "Ремонт бытовки",
      unitNumber: "БК-001",
      taskText: "Замена отделки",
      priority: 2,
      startedAt: "2026-08-24T08:00:00Z",
      finishedAt: null,
      status: "ACTIVE",
      source: { type: "MAINTENANCE_REPAIR", sourceId: "repair-1" },
    },
  ]
): DailyBrigadeActivitySnapshot {
  return {
    warehouseId: "warehouse-1",
    localDate: "2026-08-24",
    serverTime: "2026-08-24T09:20:00Z",
    intervals,
  }
}

const timeline = dailyShiftTimelineAt({
  schedule: { shiftStart: "09:00", shiftEnd: "18:00", daysOff: [] },
  timeZone: "Europe/Moscow",
  now: new Date("2026-08-24T09:20:00Z"),
})!

describe("daily shift timeline", () => {
  it("uses warehouse-local time instead of the browser time zone", () => {
    const timeline = dailyShiftTimelineAt({
      schedule: { shiftStart: "09:00", shiftEnd: "18:00", daysOff: [] },
      timeZone: "Europe/Moscow",
      now: new Date("2026-08-24T09:30:00Z"),
    })

    expect(timeline).toMatchObject({
      shiftStart: "09:00",
      shiftEnd: "18:00",
      currentTime: "12:30",
      currentTimePercent: (3.5 / 9) * 100,
      isDayOff: false,
    })
  })

  it("clamps the marker outside a shift and marks configured days off", () => {
    expect(
      dailyShiftTimelineAt({
        schedule: { shiftStart: "09:00", shiftEnd: "18:00", daysOff: [] },
        timeZone: "Europe/Moscow",
        now: new Date("2026-08-24T04:00:00Z"),
      })?.currentTimePercent
    ).toBe(0)
    expect(
      dailyShiftTimelineAt({
        schedule: { shiftStart: "09:00", shiftEnd: "18:00", daysOff: [7] },
        timeZone: "Europe/Moscow",
        now: new Date("2026-08-23T09:00:00Z"),
      })?.isDayOff
    ).toBe(true)
  })
})

describe("daily brigade rows", () => {
  it("shows one current task for each active brigade with the configured KPI color", () => {
    const rows = dailyBrigadeRows({
      groups,
      board: board([entry()]),
      activity: activity(),
      timeline,
      timeZone: "Europe/Moscow",
      palette,
      now: Date.parse("2026-08-24T09:20:00Z"),
    })

    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({
      id: "group-active",
      tasks: [
        {
          stage: "Внутренний ремонт",
          remainingPercent: 80,
          color: "#16A34A",
          startTime: "11:00",
          endTime: "12:20",
          startPercent: (2 / 9) * 100,
        },
      ],
    })
    expect(rows[0]?.tasks[0]?.widthPercent).toBeCloseTo((80 / 540) * 100)
  })

  it("keeps finished history visible and neutral after it leaves the board", () => {
    const finished = activity([
      {
        ...activity().intervals[0]!,
        startedAt: "2026-08-24T06:34:00Z",
        finishedAt: "2026-08-24T08:06:00Z",
        status: "DONE",
      },
    ])
    const rows = dailyBrigadeRows({
      groups,
      board: board([]),
      activity: finished,
      timeline,
      timeZone: "Europe/Moscow",
      palette,
      now: Date.parse("2026-08-24T09:20:00Z"),
    })

    expect(rows[0]?.tasks[0]).toMatchObject({
      entry: null,
      color: null,
      startTime: "09:34",
      endTime: "11:06",
      startPercent: (34 / 540) * 100,
      widthPercent: (92 / 540) * 100,
    })
  })

  it("retains a paused assignment and reports overdue percentages clearly", () => {
    const paused = entry({
      status: "PAUSED",
      timerSnapshot: {
        ...entry().timerSnapshot!,
        remainingSeconds: -60,
        remainingPercent: -1,
        timerState: "PAUSED",
      },
      assignments: [{ ...entry().assignments[0]!, status: "PAUSED" }],
    })
    const rows = dailyBrigadeRows({
      groups,
      board: board([paused]),
      activity: activity([{ ...activity().intervals[0]!, status: "PAUSED" }]),
      timeline,
      timeZone: "Europe/Moscow",
      palette,
      now: Date.parse("2026-08-24T09:20:00Z"),
    })

    expect(rows[0]?.tasks[0]?.color).toBe("#7F1D1D")
    expect(remainingPercentLabel(-1)).toBe("Просрочено на 1%")
  })
})
