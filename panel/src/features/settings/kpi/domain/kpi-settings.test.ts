import { describe, expect, it } from "vitest"

import {
  formatRepairDuration,
  hoursAndMinutesToMinutes,
  mergePaletteBoundary,
  splitMinutes,
  movePaletteBoundary,
  splitPaletteRange,
  validatePalette,
  validateRepairComplexityBoundaries,
  validateWorkSchedule,
  type EditablePaletteRange,
} from "@/features/settings/kpi/domain/kpi-settings"

describe("repair complexity boundaries", () => {
  it("converts hours and minutes without changing canonical minutes", () => {
    expect(hoursAndMinutesToMinutes("1", "30")).toBe(90)
    expect(splitMinutes(500)).toEqual({ hours: 8, minutes: 20 })
    expect(formatRepairDuration(500, "MINUTES")).toBe("500 мин")
    expect(formatRepairDuration(500, "HOURS")).toBe("8 ч 20 мин")
  })

  it("requires three positive strictly increasing boundaries", () => {
    expect(
      validateRepairComplexityBoundaries({
        lightBoundaryMinutes: 60,
        mediumBoundaryMinutes: 180,
        complexBoundaryMinutes: 360,
      })
    ).toEqual({ valid: true, error: null })
    expect(
      validateRepairComplexityBoundaries({
        lightBoundaryMinutes: 60,
        mediumBoundaryMinutes: 60,
        complexBoundaryMinutes: 360,
      }).valid
    ).toBe(false)
  })
})

describe("KPI palette ranges", () => {
  it("splits a range and inherits its color", () => {
    expect(
      splitPaletteRange(
        [{ fromPercent: 0, toPercent: 100, color: "#123456" }],
        35
      )
    ).toEqual([
      { fromPercent: 0, toPercent: 35, color: "#123456" },
      { fromPercent: 35, toPercent: 100, color: "#123456" },
    ])
  })

  it("merges equal colors and clears a conflicting color", () => {
    const same: EditablePaletteRange[] = [
      { fromPercent: 0, toPercent: 35, color: "#111111" },
      { fromPercent: 35, toPercent: 100, color: "#111111" },
    ]
    const different: EditablePaletteRange[] = [
      same[0]!,
      { ...same[1]!, color: "#222222" },
    ]

    expect(mergePaletteBoundary(same, 35)).toEqual([
      { fromPercent: 0, toPercent: 100, color: "#111111" },
    ])
    expect(mergePaletteBoundary(different, 35)).toEqual([
      { fromPercent: 0, toPercent: 100, color: null },
    ])
  })

  it("moves only an inner boundary and keeps one percent ranges valid", () => {
    const ranges: EditablePaletteRange[] = [
      { fromPercent: 0, toPercent: 35, color: "#111111" },
      { fromPercent: 35, toPercent: 70, color: "#222222" },
      { fromPercent: 70, toPercent: 100, color: "#333333" },
    ]

    expect(movePaletteBoundary(ranges, 35, 69)).toEqual([
      { fromPercent: 0, toPercent: 69, color: "#111111" },
      { fromPercent: 69, toPercent: 70, color: "#222222" },
      { fromPercent: 70, toPercent: 100, color: "#333333" },
    ])
  })

  it("requires 1–6 contiguous, colored integer ranges and overdue color", () => {
    expect(
      validatePalette(
        [
          { fromPercent: 0, toPercent: 35, color: "#DC2626" },
          { fromPercent: 35, toPercent: 100, color: "#16A34A" },
        ],
        "#7F1D1D"
      )
    ).toEqual({ valid: true, error: null })

    expect(
      validatePalette(
        [
          { fromPercent: 0, toPercent: 35, color: "#DC2626" },
          { fromPercent: 36, toPercent: 100, color: null },
        ],
        ""
      ).valid
    ).toBe(false)
  })
})

describe("warehouse work schedule", () => {
  it("accepts ordered breaks inside a future single-day shift", () => {
    expect(
      validateWorkSchedule(
        {
          effectiveFrom: "2026-08-01",
          shiftStart: "08:00",
          shiftEnd: "17:00",
          daysOff: [6, 7],
          breaks: [
            { start: "10:00", end: "10:15" },
            { start: "13:00", end: "14:00" },
          ],
        },
        "2026-07-31"
      )
    ).toEqual({ valid: true, error: null })
  })

  it("accepts activation today and still rejects past or invalid shifts", () => {
    expect(
      validateWorkSchedule(
        {
          effectiveFrom: "2026-07-30",
          shiftStart: "08:00",
          shiftEnd: "17:00",
          daysOff: [7],
          breaks: [],
        },
        "2026-07-30"
      )
    ).toEqual({ valid: true, error: null })

    expect(
      validateWorkSchedule(
        {
          effectiveFrom: "2026-07-29",
          shiftStart: "22:00",
          shiftEnd: "06:00",
          daysOff: [7],
          breaks: [
            { start: "23:00", end: "23:30" },
            { start: "23:20", end: "23:40" },
          ],
        },
        "2026-07-30"
      ).valid
    ).toBe(false)
  })
})
