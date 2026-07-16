import type {
  GroupScheduleDto,
  RestPeriodDto,
  RestPeriodType,
  ScheduleDayDto,
} from "@/features/settings/task-board/model/task-board-settings"

export const SCHEDULE_STEP_MINUTES = 5
export const MIN_PERIOD_MINUTES = 5

export const schedulePeriodLabels: Record<RestPeriodType, string> = {
  SMOKE_BREAK: "Перекур",
  LUNCH: "Обед",
}

export const scheduleDayLabels: Record<ScheduleDayDto["dayOfWeek"], string> = {
  1: "Понедельник",
  2: "Вторник",
  3: "Среда",
  4: "Четверг",
  5: "Пятница",
  6: "Суббота",
  7: "Воскресенье",
}

export function timeToMinutes(value: string) {
  const match = /^(\d{2}):(\d{2})$/.exec(value)
  if (!match) return Number.NaN
  const hours = Number(match[1])
  const minutes = Number(match[2])
  return hours <= 23 && minutes <= 59 ? hours * 60 + minutes : Number.NaN
}

export function minutesToTime(value: number) {
  const bounded = Math.max(0, Math.min(23 * 60 + 59, value))
  return `${String(Math.floor(bounded / 60)).padStart(2, "0")}:${String(
    bounded % 60
  ).padStart(2, "0")}`
}

export function validateScheduleDay(day: ScheduleDayDto) {
  if (!day.enabled) return null
  const shiftStart = timeToMinutes(day.shiftStartsAt)
  const shiftEnd = timeToMinutes(day.shiftEndsAt)
  if (!Number.isFinite(shiftStart) || !Number.isFinite(shiftEnd)) {
    return "Укажите корректное время смены."
  }
  if (shiftEnd <= shiftStart) {
    return "Конец смены должен быть позже начала."
  }
  const ordered = [...day.restPeriods].sort(
    (left, right) =>
      timeToMinutes(left.startsAt) - timeToMinutes(right.startsAt)
  )
  for (let index = 0; index < ordered.length; index += 1) {
    const period = ordered[index]
    const startsAt = timeToMinutes(period.startsAt)
    const endsAt = timeToMinutes(period.endsAt)
    if (!Number.isFinite(startsAt) || !Number.isFinite(endsAt)) {
      return "Укажите корректное время перерыва."
    }
    if (endsAt - startsAt < MIN_PERIOD_MINUTES) {
      return "Перерыв должен длиться не менее 5 минут."
    }
    if (startsAt < shiftStart || endsAt > shiftEnd) {
      return "Перерыв должен находиться внутри смены."
    }
    const previous = ordered[index - 1]
    if (previous && timeToMinutes(previous.endsAt) > startsAt) {
      return "Периоды отдыха не должны пересекаться."
    }
  }
  return null
}

export function replaceSchedulePeriod(
  day: ScheduleDayDto,
  periodId: string,
  change: Partial<RestPeriodDto>
) {
  const next = {
    ...day,
    linkedToTemplate: false,
    restPeriods: day.restPeriods.map((period) =>
      period.id === periodId ? { ...period, ...change } : period
    ),
  }
  return validateScheduleDay(next) ? null : next
}

export function cloneLinkedScheduleDay(
  template: ScheduleDayDto,
  target: ScheduleDayDto,
  copyEnabled = false
): ScheduleDayDto {
  return {
    ...target,
    enabled: copyEnabled ? template.enabled : target.enabled,
    linkedToTemplate: true,
    shiftStartsAt: template.shiftStartsAt,
    shiftEndsAt: template.shiftEndsAt,
    restPeriods: template.restPeriods.map((period, index) => {
      const existing = target.restPeriods[index]
      return {
        ...period,
        id: existing?.id ?? `linked-${target.dayOfWeek}-${period.id}-${index}`,
        version: existing?.version ?? 0,
      }
    }),
  }
}

export function applyScheduleDayChange(
  schedule: GroupScheduleDto,
  changedDay: ScheduleDayDto
): GroupScheduleDto {
  if (changedDay.dayOfWeek !== 1) {
    return {
      ...schedule,
      days: schedule.days.map((day) =>
        day.dayOfWeek === changedDay.dayOfWeek ? changedDay : day
      ),
    }
  }

  const template = { ...changedDay, linkedToTemplate: true }
  return {
    ...schedule,
    days: schedule.days.map((day) => {
      if (day.dayOfWeek === 1) return template
      return day.enabled && day.linkedToTemplate
        ? cloneLinkedScheduleDay(template, day)
        : day
    }),
  }
}

export function isValidIanaTimezone(timezone: string) {
  if (!timezone.trim()) return false
  try {
    new Intl.DateTimeFormat("ru-RU", { timeZone: timezone }).format()
    return true
  } catch {
    return false
  }
}

export function formatSchedulePeriodCount(count: number) {
  const absolute = Math.abs(count)
  const lastTwo = absolute % 100
  const last = absolute % 10
  const suffix =
    lastTwo >= 11 && lastTwo <= 14
      ? "периодов"
      : last === 1
        ? "период"
        : last >= 2 && last <= 4
          ? "периода"
          : "периодов"
  return `${count} ${suffix}`
}
