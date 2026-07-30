import { describe, expect, it } from "vitest"

import { barFillSegments, clampKpiValue } from "@/features/kpi/domain/kpi-bar"

const ranges = [
  { fromPercent: 0, toPercent: 35, color: "#DC2626" },
  { fromPercent: 35, toPercent: 70, color: "#EAB308" },
  { fromPercent: 70, toPercent: 100, color: "#16A34A" },
]

describe("KPI bar", () => {
  it("clamps values to the visible 0–100 track", () => {
    expect(clampKpiValue(-20)).toBe(0)
    expect(clampKpiValue(42.25)).toBe(42.25)
    expect(clampKpiValue(120)).toBe(100)
  })

  it("clips the saturated fill at the exact KPI value", () => {
    expect(barFillSegments(ranges, 52)).toEqual([
      { fromPercent: 0, toPercent: 35, color: "#DC2626" },
      { fromPercent: 35, toPercent: 52, color: "#EAB308" },
    ])
  })
})
