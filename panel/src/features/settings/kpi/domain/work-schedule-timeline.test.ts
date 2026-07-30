import { describe, expect, it } from "vitest"

import { scheduleTimeline } from "@/features/settings/kpi/domain/kpi-settings"

describe("work schedule timeline", () => {
  it("keeps the time label for valid breaks and ignores incomplete drafts", () => {
    const timeline = scheduleTimeline("09:00", "18:00", [
      { start: "10:30", end: "10:45" },
      { start: "", end: "" },
      { start: "16:00", end: "15:45" },
    ])

    expect(timeline).toHaveLength(1)
    expect(timeline[0]).toMatchObject({
      start: "10:30",
      end: "10:45",
    })
    expect(timeline[0]?.startPercent).toBeCloseTo(100 / 6)
    expect(timeline[0]?.widthPercent).toBeCloseTo(100 / 36)
  })
})
