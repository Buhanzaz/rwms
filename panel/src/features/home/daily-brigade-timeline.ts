import type { KpiWorkerGroup } from "@/features/kpi/api/kpi-api"
import { calendarDatePartsInTimeZone } from "@/features/kpi/domain/kpi-period"
import type {
  KpiPalette,
  KpiWorkSchedule,
} from "@/features/settings/kpi/api/kpi-settings-api"
import { timeToMinutes } from "@/features/settings/kpi/domain/kpi-settings"
import {
  paletteColorForRemainingPercent,
  taskTimerAt,
} from "@/features/task-board/domain/task-board-kpi-presentation"
import type {
  TaskBoardEntryDto,
  TaskBoardSnapshotDto,
} from "@/features/task-board/model/task-board"
import type {
  DailyBrigadeActivityInterval,
  DailyBrigadeActivitySnapshot,
} from "@/features/home/daily-brigade-activity-api"

const HEX_COLOR = /^#[0-9a-f]{6}$/i

/** Warehouse-local bounds and live marker position for the selected workday. */
export type DailyShiftTimeline = {
  shiftStart: string
  shiftEnd: string
  currentTime: string
  currentTimePercent: number
  isDayOff: boolean
}

/** Read-only task interval positioned by authoritative take and finish timestamps. */
export type DailyBrigadeTask = {
  activity: DailyBrigadeActivityInterval
  entry: TaskBoardEntryDto | null
  stage: string
  remainingPercent: number | null
  color: string | null
  startTime: string
  endTime: string
  startPercent: number
  widthPercent: number
}

/** An active workforce group paired with every execution interval from the current day. */
export type DailyBrigadeRow = KpiWorkerGroup & {
  tasks: DailyBrigadeTask[]
}

function localTimeParts(now: Date, timeZone: string) {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone,
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(now)
  const value = (type: Intl.DateTimeFormatPartTypes) =>
    Number(parts.find((part) => part.type === type)?.value)

  return { hour: value("hour"), minute: value("minute") }
}

function localDateKey(now: Date, timeZone: string) {
  const { year, month, day } = calendarDatePartsInTimeZone(now, timeZone)
  return `${String(year).padStart(4, "0")}-${String(month).padStart(
    2,
    "0"
  )}-${String(day).padStart(2, "0")}`
}

function localTimeLabel(now: Date, timeZone: string) {
  const value = localTimeParts(now, timeZone)
  return `${String(value.hour).padStart(2, "0")}:${String(
    value.minute
  ).padStart(2, "0")}`
}

function isoWeekday({
  year,
  month,
  day,
}: {
  year: number
  month: number
  day: number
}) {
  return new Date(Date.UTC(year, month - 1, day)).getUTCDay() || 7
}

function clampPercent(value: number) {
  return Math.max(0, Math.min(100, value))
}

function intervalPercent({
  value,
  localDate,
  timeZone,
  shiftStartMinutes,
  shiftEndMinutes,
}: {
  value: Date
  localDate: string
  timeZone: string
  shiftStartMinutes: number
  shiftEndMinutes: number
}) {
  const valueDate = localDateKey(value, timeZone)
  if (valueDate < localDate) return 0
  if (valueDate > localDate) return 100
  const localTime = localTimeParts(value, timeZone)
  const minutes = localTime.hour * 60 + localTime.minute
  return clampPercent(
    ((minutes - shiftStartMinutes) / (shiftEndMinutes - shiftStartMinutes)) *
      100
  )
}

/**
 * Projects the current instant onto the active warehouse-local shift.
 *
 * The schedule is defined in warehouse-local wall-clock time, so the marker
 * remains correct when a manager's browser is in a different time zone.
 */
export function dailyShiftTimelineAt({
  schedule,
  timeZone,
  now,
}: {
  schedule: Pick<KpiWorkSchedule, "shiftStart" | "shiftEnd" | "daysOff">
  timeZone: string
  now: Date
}): DailyShiftTimeline | null {
  const shiftStartMinutes = timeToMinutes(schedule.shiftStart)
  const shiftEndMinutes = timeToMinutes(schedule.shiftEnd)
  if (
    shiftStartMinutes === null ||
    shiftEndMinutes === null ||
    shiftStartMinutes >= shiftEndMinutes
  ) {
    return null
  }

  const localDate = calendarDatePartsInTimeZone(now, timeZone)
  const localTime = localTimeParts(now, timeZone)
  const currentMinutes = localTime.hour * 60 + localTime.minute
  const durationMinutes = shiftEndMinutes - shiftStartMinutes

  return {
    shiftStart: schedule.shiftStart,
    shiftEnd: schedule.shiftEnd,
    currentTime: `${String(localTime.hour).padStart(2, "0")}:${String(
      localTime.minute
    ).padStart(2, "0")}`,
    currentTimePercent: clampPercent(
      ((currentMinutes - shiftStartMinutes) / durationMinutes) * 100
    ),
    isDayOff: schedule.daysOff.includes(isoWeekday(localDate)),
  }
}

function isTakenEntry(entry: TaskBoardEntryDto) {
  return entry.status === "IN_PROGRESS" || entry.status === "PAUSED"
}

function isCurrentAssignment(entry: TaskBoardEntryDto, groupId: string) {
  return entry.assignments.some(
    (assignment) =>
      assignment.workerGroupId === groupId &&
      (assignment.status === "ACTIVE" || assignment.status === "PAUSED")
  )
}

function taskColor(
  entry: TaskBoardEntryDto,
  now: number,
  palette: KpiPalette | null
) {
  const color = paletteColorForRemainingPercent(
    taskTimerAt(entry, now).remainingPercent,
    palette
  )
  return color && HEX_COLOR.test(color) ? color : null
}

/**
 * Joins the current task-board read model to active workforce groups without
 * making the browser the owner of assignments or KPI state.
 */
export function dailyBrigadeRows({
  groups,
  board,
  activity,
  timeline,
  timeZone,
  palette,
  now,
}: {
  groups: KpiWorkerGroup[]
  board: TaskBoardSnapshotDto
  activity: DailyBrigadeActivitySnapshot
  timeline: DailyShiftTimeline
  timeZone: string
  palette: KpiPalette | null
  now: number
}): DailyBrigadeRow[] {
  const shiftStartMinutes = timeToMinutes(timeline.shiftStart)
  const shiftEndMinutes = timeToMinutes(timeline.shiftEnd)
  if (
    shiftStartMinutes === null ||
    shiftEndMinutes === null ||
    shiftStartMinutes >= shiftEndMinutes
  ) {
    return []
  }
  const takenEntries = board.queues.flatMap((queue) =>
    queue.entries.filter(isTakenEntry).map((entry) => ({ entry }))
  )

  return groups
    .filter((group) => group.active)
    .map((group) => {
      const tasks = activity.intervals
        .filter((item) => item.workerGroupId === group.id)
        .map((item) => {
          const active = takenEntries.find(
            ({ entry }) =>
              entry.taskId === item.taskId &&
              entry.queueId === item.queueId &&
              isCurrentAssignment(entry, group.id)
          )
          const start = new Date(item.startedAt)
          const end = new Date(item.finishedAt ?? now)
          const startPercent = intervalPercent({
            value: start,
            localDate: activity.localDate,
            timeZone,
            shiftStartMinutes,
            shiftEndMinutes,
          })
          const endPercent = intervalPercent({
            value: end,
            localDate: activity.localDate,
            timeZone,
            shiftStartMinutes,
            shiftEndMinutes,
          })
          const timer = active ? taskTimerAt(active.entry, now) : null
          return {
            activity: item,
            entry: active?.entry ?? null,
            stage: item.queueName,
            remainingPercent: timer?.remainingPercent ?? null,
            color: active ? taskColor(active.entry, now, palette) : null,
            startTime: localTimeLabel(start, timeZone),
            endTime: localTimeLabel(end, timeZone),
            startPercent,
            widthPercent: Math.max(0, endPercent - startPercent),
          }
        })
        .sort(
          (left, right) =>
            Date.parse(left.activity.startedAt) -
              Date.parse(right.activity.startedAt) ||
            left.activity.taskId.localeCompare(right.activity.taskId)
        )
      return {
        ...group,
        tasks,
      }
    })
    .sort((left, right) => left.name.localeCompare(right.name, "ru"))
}

export function remainingPercentLabel(value: number | null) {
  if (value === null) return "Нет расчёта"
  const rounded = Math.round(value)
  return rounded < 0 ? `Просрочено на ${Math.abs(rounded)}%` : `${rounded}%`
}
