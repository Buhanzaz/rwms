import { describe, expect, it } from "vitest"

import {
  createEmptyWarehouseSupportLinkDraft,
  parseWarehouseSupportLinkDrafts,
} from "@/features/settings/warehouses/warehouse-support-links-form"

const SERVED_WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const SUPPORT_WAREHOUSE_ID = "00000000-0000-4000-8000-000000000002"

describe("warehouse support links form", () => {
  it("normalizes calendar dates, weekdays and a daily interval", () => {
    const draft = {
      ...createEmptyWarehouseSupportLinkDraft("new-1", 1),
      supportWarehouseId: SUPPORT_WAREHOUSE_ID,
      allowedWeekdays: ["TUESDAY", "THURSDAY"] as const,
      allowedDates: "2026-09-21, 2026-09-14; 2026-09-14",
      excludedDates: "2026-09-15",
      serviceStart: "08:00",
      serviceEnd: "18:00",
    }

    expect(
      parseWarehouseSupportLinkDrafts(SERVED_WAREHOUSE_ID, [
        { ...draft, allowedWeekdays: [...draft.allowedWeekdays] },
      ])
    ).toEqual({
      links: [
        expect.objectContaining({
          supportWarehouseId: SUPPORT_WAREHOUSE_ID,
          priority: 1,
          allowedWeekdays: ["TUESDAY", "THURSDAY"],
          allowedDates: ["2026-09-14", "2026-09-21"],
          excludedDates: ["2026-09-15"],
          serviceStart: "08:00",
          serviceEnd: "18:00",
        }),
      ],
      error: null,
    })
  })

  it("rejects self-links, duplicates and incomplete service intervals", () => {
    const first = {
      ...createEmptyWarehouseSupportLinkDraft("new-1", 1),
      supportWarehouseId: SUPPORT_WAREHOUSE_ID,
    }

    expect(
      parseWarehouseSupportLinkDrafts(SERVED_WAREHOUSE_ID, [
        { ...first, supportWarehouseId: SERVED_WAREHOUSE_ID },
      ]).error
    ).toContain("сам себя")
    expect(
      parseWarehouseSupportLinkDrafts(SERVED_WAREHOUSE_ID, [
        first,
        { ...first, key: "new-2", priority: "2" },
      ]).error
    ).toContain("дважды")
    expect(
      parseWarehouseSupportLinkDrafts(SERVED_WAREHOUSE_ID, [
        { ...first, serviceStart: "08:00" },
      ]).error
    ).toContain("начало")
  })
})
