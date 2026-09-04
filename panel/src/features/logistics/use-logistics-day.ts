import { useCallback } from "react"
import { useSearchParams } from "react-router-dom"

import { parseCalendarDate } from "@/components/ui/single-day-picker"
import { calendarDatePartsInTimeZone } from "@/features/kpi/domain/kpi-period"

function isCalendarDate(value: string) {
  return parseCalendarDate(value) !== undefined
}

function currentBusinessDate(timeZone: string) {
  const { year, month, day } = calendarDatePartsInTimeZone(
    new Date(),
    timeZone
  )
  return `${year.toString().padStart(4, "0")}-${month
    .toString()
    .padStart(2, "0")}-${day.toString().padStart(2, "0")}`
}

/** Keeps the selected warehouse-local operation day in the URL across reloads. */
export function useLogisticsDay(timeZone: string | null | undefined) {
  const [searchParams, setSearchParams] = useSearchParams()
  const requestedDate = searchParams.get("date")
  const selectedDate =
    requestedDate && isCalendarDate(requestedDate)
      ? requestedDate
      : timeZone
        ? currentBusinessDate(timeZone)
        : ""

  const setSelectedDate = useCallback(
    (date: string) => {
      if (!isCalendarDate(date)) return
      const next = new URLSearchParams(searchParams)
      next.set("date", date)
      setSearchParams(next, { replace: true })
    },
    [searchParams, setSearchParams]
  )

  return { searchParams, selectedDate, setSelectedDate }
}

export { currentBusinessDate }
