import { describe, expect, it } from "vitest"

import { buildManualBookingPresentationGroups } from "@/features/booking/booking-presentation"

function id(index: number) {
  return `00000000-0000-4000-8000-${String(index).padStart(12, "0")}`
}

describe("manual booking presentation groups", () => {
  it("splits 100 selected cabins into deterministic contract-sized groups", () => {
    const groups = buildManualBookingPresentationGroups(
      Array.from({ length: 100 }, (_, index) => id(index + 1))
    )

    expect(groups.map((group) => group.key)).toEqual([
      "manual-booking-1",
      "manual-booking-2",
      "manual-booking-3",
      "manual-booking-4",
    ])
    expect(groups.map((group) => group.rentalItemIds.length)).toEqual([
      30, 30, 30, 10,
    ])
    expect(groups.flatMap((group) => group.rentalItemIds)).toEqual(
      Array.from({ length: 100 }, (_, index) => id(index + 1))
    )
  })

  it("rejects empty and oversized selections", () => {
    expect(() => buildManualBookingPresentationGroups([])).toThrow()
    expect(() =>
      buildManualBookingPresentationGroups(
        Array.from({ length: 101 }, (_, index) => id(index + 1))
      )
    ).toThrow()
  })
})
