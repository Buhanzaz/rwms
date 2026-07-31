export type EditablePaletteRange = {
  fromPercent: number
  toPercent: number
  color: string | null
}

export type WorkBreak = {
  start: string
  end: string
}

export type EditableWorkSchedule = {
  effectiveFrom: string
  shiftStart: string
  shiftEnd: string
  daysOff: number[]
  breaks: WorkBreak[]
}

export type ValidationResult =
  { valid: true; error: null } | { valid: false; error: string }

export type RepairComplexityBoundaries = {
  lightBoundaryMinutes: number
  mediumBoundaryMinutes: number
  complexBoundaryMinutes: number
}

const RGB_PATTERN = /^#[0-9A-F]{6}$/
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const TIME_PATTERN = /^(?:[01]\d|2[0-3]):[0-5]\d$/

export function normalizeRgb(value: string | null) {
  if (value === null) return null
  const normalized = value.trim().toUpperCase()
  return RGB_PATTERN.test(normalized) ? normalized : null
}

export function hoursAndMinutesToMinutes(hours: string, minutes: string) {
  const parsedHours = Number(hours)
  const parsedMinutes = Number(minutes)
  if (
    !Number.isInteger(parsedHours) ||
    parsedHours < 0 ||
    !Number.isInteger(parsedMinutes) ||
    parsedMinutes < 0 ||
    parsedMinutes > 59
  ) {
    return null
  }
  const total = parsedHours * 60 + parsedMinutes
  return total > 0 ? total : null
}

export function splitMinutes(value: number) {
  return {
    hours: Math.floor(value / 60),
    minutes: value % 60,
  }
}

export function formatRepairDuration(value: number, format: "MINUTES" | "HOURS") {
  if (format === "MINUTES") return `${value} мин`
  const { hours, minutes } = splitMinutes(value)
  if (hours === 0) return `${minutes} мин`
  if (minutes === 0) return `${hours} ч`
  return `${hours} ч ${minutes} мин`
}

export function validateRepairComplexityBoundaries(
  boundaries: RepairComplexityBoundaries
): ValidationResult {
  const values = [
    boundaries.lightBoundaryMinutes,
    boundaries.mediumBoundaryMinutes,
    boundaries.complexBoundaryMinutes,
  ]
  if (values.some((value) => !Number.isInteger(value) || value < 1)) {
    return {
      valid: false,
      error: "Каждая граница должна задавать положительное число минут.",
    }
  }
  if (
    boundaries.lightBoundaryMinutes >= boundaries.mediumBoundaryMinutes ||
    boundaries.mediumBoundaryMinutes >= boundaries.complexBoundaryMinutes
  ) {
    return {
      valid: false,
      error: "Границы должны строго возрастать: лёгкий < средний < тяжёлый.",
    }
  }
  return { valid: true, error: null }
}

export function splitPaletteRange(
  ranges: EditablePaletteRange[],
  atPercent: number
) {
  if (
    !Number.isInteger(atPercent) ||
    atPercent <= 0 ||
    atPercent >= 100 ||
    ranges.length >= 6
  ) {
    return ranges
  }

  const index = ranges.findIndex(
    (range) => atPercent > range.fromPercent && atPercent < range.toPercent
  )
  if (index < 0) return ranges

  const range = ranges[index]!
  return [
    ...ranges.slice(0, index),
    { ...range, toPercent: atPercent },
    { ...range, fromPercent: atPercent },
    ...ranges.slice(index + 1),
  ]
}

export function mergePaletteBoundary(
  ranges: EditablePaletteRange[],
  atPercent: number
) {
  const rightIndex = ranges.findIndex(
    (range) => range.fromPercent === atPercent
  )
  if (rightIndex <= 0) return ranges

  const left = ranges[rightIndex - 1]!
  const right = ranges[rightIndex]!
  return [
    ...ranges.slice(0, rightIndex - 1),
    {
      fromPercent: left.fromPercent,
      toPercent: right.toPercent,
      color: left.color === right.color ? left.color : null,
    },
    ...ranges.slice(rightIndex + 1),
  ]
}

export function movePaletteBoundary(
  ranges: EditablePaletteRange[],
  fromPercent: number,
  toPercent: number
) {
  if (!Number.isInteger(toPercent)) return ranges
  const rightIndex = ranges.findIndex(
    (range) => range.fromPercent === fromPercent
  )
  if (rightIndex <= 0) return ranges

  const left = ranges[rightIndex - 1]!
  const right = ranges[rightIndex]!
  if (
    toPercent <= left.fromPercent ||
    toPercent >= right.toPercent ||
    toPercent === fromPercent
  ) {
    return ranges
  }

  return ranges.map((range, index) => {
    if (index === rightIndex - 1) return { ...range, toPercent }
    if (index === rightIndex) return { ...range, fromPercent: toPercent }
    return range
  })
}

export function setPaletteRangeColor(
  ranges: EditablePaletteRange[],
  fromPercent: number,
  value: string
) {
  return ranges.map((range) =>
    range.fromPercent === fromPercent ? { ...range, color: value } : range
  )
}

export function validatePalette(
  ranges: EditablePaletteRange[],
  overdueColor: string | null
): ValidationResult {
  if (ranges.length < 1 || ranges.length > 6) {
    return { valid: false, error: "Настройте от одного до шести диапазонов." }
  }
  if (ranges[0]?.fromPercent !== 0 || ranges.at(-1)?.toPercent !== 100) {
    return {
      valid: false,
      error: "Шкала должна покрывать значения от 0 до 100%.",
    }
  }

  for (const [index, range] of ranges.entries()) {
    if (
      !Number.isInteger(range.fromPercent) ||
      !Number.isInteger(range.toPercent) ||
      range.fromPercent < 0 ||
      range.toPercent > 100 ||
      range.fromPercent >= range.toPercent ||
      (index > 0 && ranges[index - 1]?.toPercent !== range.fromPercent)
    ) {
      return {
        valid: false,
        error: "Диапазоны должны быть целыми, непрерывными и не пересекаться.",
      }
    }
    if (normalizeRgb(range.color) === null) {
      return { valid: false, error: "Назначьте RGB-цвет каждому диапазону." }
    }
  }

  if (normalizeRgb(overdueColor) === null) {
    return { valid: false, error: "Назначьте отдельный цвет просрочки." }
  }

  return { valid: true, error: null }
}

export function timeToMinutes(value: string) {
  if (!TIME_PATTERN.test(value)) return null
  const [hours, minutes] = value.split(":").map(Number)
  return hours! * 60 + minutes!
}

export function validateWorkSchedule(
  schedule: EditableWorkSchedule,
  today: string
): ValidationResult {
  if (
    !DATE_PATTERN.test(schedule.effectiveFrom) ||
    schedule.effectiveFrom <= today
  ) {
    return {
      valid: false,
      error: "Дата вступления должна быть не раньше следующего дня.",
    }
  }

  const shiftStart = timeToMinutes(schedule.shiftStart)
  const shiftEnd = timeToMinutes(schedule.shiftEnd)
  if (shiftStart === null || shiftEnd === null || shiftStart >= shiftEnd) {
    return {
      valid: false,
      error: "Смена должна начинаться раньше окончания в пределах одних суток.",
    }
  }
  if (
    new Set(schedule.daysOff).size !== schedule.daysOff.length ||
    schedule.daysOff.some((day) => !Number.isInteger(day) || day < 1 || day > 7)
  ) {
    return { valid: false, error: "Выходные дни настроены некорректно." }
  }

  const normalizedBreaks = schedule.breaks
    .map((entry) => ({
      start: timeToMinutes(entry.start),
      end: timeToMinutes(entry.end),
    }))
    .sort((left, right) => (left.start ?? -1) - (right.start ?? -1))

  for (const [index, entry] of normalizedBreaks.entries()) {
    if (
      entry.start === null ||
      entry.end === null ||
      entry.start >= entry.end ||
      entry.start < shiftStart ||
      entry.end > shiftEnd
    ) {
      return {
        valid: false,
        error: "Каждый перерыв должен находиться внутри рабочей смены.",
      }
    }
    if (index > 0 && normalizedBreaks[index - 1]!.end! > entry.start) {
      return { valid: false, error: "Интервалы отдыха не должны пересекаться." }
    }
  }

  return { valid: true, error: null }
}

export function scheduleTimeline(
  shiftStartValue: string,
  shiftEndValue: string,
  breaks: WorkBreak[]
) {
  const shiftStart = timeToMinutes(shiftStartValue)
  const shiftEnd = timeToMinutes(shiftEndValue)
  if (shiftStart === null || shiftEnd === null || shiftStart >= shiftEnd) {
    return []
  }
  const duration = shiftEnd - shiftStart

  return breaks.flatMap((entry) => {
    const start = timeToMinutes(entry.start)
    const end = timeToMinutes(entry.end)
    if (
      start === null ||
      end === null ||
      start < shiftStart ||
      end > shiftEnd ||
      start >= end
    ) {
      return []
    }
    return [
      {
        start: entry.start,
        end: entry.end,
        startPercent: ((start - shiftStart) / duration) * 100,
        widthPercent: ((end - start) / duration) * 100,
      },
    ]
  })
}
