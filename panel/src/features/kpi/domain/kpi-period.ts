export type KpiPeriodType = "YEAR" | "QUARTER" | "MONTH" | "DAY"

export type KpiPeriod = {
  periodType: KpiPeriodType
  year: number
  month: number
  day: number
  quarter: number
}

export function daysInMonth(year: number, month: number) {
  return new Date(Date.UTC(year, month, 0)).getUTCDate()
}

export function clampKpiPeriod(period: KpiPeriod): KpiPeriod {
  const year = Math.min(9999, Math.max(1, Math.trunc(period.year)))
  const month = Math.min(12, Math.max(1, Math.trunc(period.month)))
  const quarter = Math.min(4, Math.max(1, Math.trunc(period.quarter)))
  const day = Math.min(
    daysInMonth(year, month),
    Math.max(1, Math.trunc(period.day))
  )

  return { ...period, year, month, day, quarter }
}

export function calendarDatePartsInTimeZone(date: Date, timeZone: string) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date)
  const value = (type: Intl.DateTimeFormatPartTypes) =>
    Number(parts.find((part) => part.type === type)?.value)

  return {
    year: value("year"),
    month: value("month"),
    day: value("day"),
  }
}

export function resetKpiPeriod(date: Date, timeZone: string): KpiPeriod {
  const { year, month, day } = calendarDatePartsInTimeZone(date, timeZone)

  return {
    periodType: "DAY",
    year,
    month,
    day,
    quarter: Math.ceil(month / 3),
  }
}

export function buildKpiPeriodQuery(period: KpiPeriod) {
  const normalized = clampKpiPeriod(period)
  const params = new URLSearchParams({
    periodType: normalized.periodType,
    year: String(normalized.year),
  })

  if (normalized.periodType === "QUARTER") {
    params.set("quarter", String(normalized.quarter))
  }
  if (normalized.periodType === "MONTH" || normalized.periodType === "DAY") {
    params.set("month", String(normalized.month))
  }
  if (normalized.periodType === "DAY") {
    params.set("day", String(normalized.day))
  }

  return params.toString()
}

export function periodLabel(period: KpiPeriod) {
  const normalized = clampKpiPeriod(period)
  if (normalized.periodType === "YEAR") return `${normalized.year} год`
  if (normalized.periodType === "QUARTER") {
    return `${normalized.quarter}-й квартал ${normalized.year}`
  }
  const monthName = new Intl.DateTimeFormat("ru-RU", {
    month: "long",
  }).format(new Date(Date.UTC(normalized.year, normalized.month - 1, 1)))
  if (normalized.periodType === "MONTH") {
    return `${monthName} ${normalized.year}`
  }
  return `${normalized.day} ${monthName} ${normalized.year}`
}
