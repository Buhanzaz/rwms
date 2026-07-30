import { describe, expect, it } from "vitest"

import {
  buildKpiPeriodQuery,
  clampKpiPeriod,
  daysInMonth,
  resetKpiPeriod,
} from "@/features/kpi/domain/kpi-period"

describe("KPI period filters", () => {
  it("uses the warehouse time zone when resetting to today", () => {
    const now = new Date("2026-12-31T21:30:00Z")

    expect(resetKpiPeriod(now, "Europe/Moscow")).toEqual({
      periodType: "DAY",
      year: 2027,
      month: 1,
      day: 1,
      quarter: 1,
    })
  })

  it("supports leap years and clamps an invalid selected day", () => {
    expect(daysInMonth(2024, 2)).toBe(29)
    expect(daysInMonth(2026, 2)).toBe(28)
    expect(
      clampKpiPeriod({
        periodType: "DAY",
        year: 2026,
        month: 2,
        day: 31,
        quarter: 1,
      }).day
    ).toBe(28)
  })

  it("emits only parameters used by the selected period", () => {
    const base = {
      year: 2026,
      month: 7,
      day: 30,
      quarter: 3,
    }

    expect(buildKpiPeriodQuery({ ...base, periodType: "YEAR" })).toBe(
      "periodType=YEAR&year=2026"
    )
    expect(buildKpiPeriodQuery({ ...base, periodType: "QUARTER" })).toBe(
      "periodType=QUARTER&year=2026&quarter=3"
    )
    expect(buildKpiPeriodQuery({ ...base, periodType: "MONTH" })).toBe(
      "periodType=MONTH&year=2026&month=7"
    )
    expect(buildKpiPeriodQuery({ ...base, periodType: "DAY" })).toBe(
      "periodType=DAY&year=2026&month=7&day=30"
    )
  })
})
