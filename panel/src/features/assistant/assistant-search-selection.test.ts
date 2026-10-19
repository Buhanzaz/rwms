import { afterEach, describe, expect, it, vi } from "vitest"

import {
  isCabinSearchResultActive,
  type AvailableCabin,
  type CabinSearchResult,
  type CabinSelection,
} from "@/features/assistant/api/assistant-api"
import { reconcileSearchResultSelection } from "@/features/assistant/assistant-search-selection"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"

function cabin(id: string, number: string): AvailableCabin {
  return {
    id,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    number,
    status: "FREE",
    rentalType: "БК-1",
    dimensions: "6x2.4",
    finishing: "ЛДСП",
    category: "Обычная",
    characteristics: null,
    linoleum: true,
    passport: {},
    tags: [],
    updatedAt: "2026-08-09T10:00:00Z",
  }
}

afterEach(() => vi.useRealTimers())

describe("assistant selection reconciliation", () => {
  it("uses the renewed authoritative TTL after a partial removal", () => {
    vi.useFakeTimers()
    vi.setSystemTime("2026-08-09T10:02:00Z")
    const removed = cabin("22222222-2222-4222-8222-222222222222", "БЫТ-001")
    const retained = cabin("33333333-3333-4333-8333-333333333333", "БЫТ-002")
    const result: CabinSearchResult = {
      warehouseId: WAREHOUSE_ID,
      expiresAt: "2026-08-09T10:01:00Z",
      groups: [
        {
          group: {
            cabinType: "БК-1",
            finish: "ЛДСП",
            dimensions: "6x2.4",
            quantity: 2,
          },
          cabins: [removed, retained],
        },
      ],
    }
    const selection: CabinSelection = {
      inquiryId: "44444444-4444-4444-8444-444444444444",
      warehouseId: WAREHOUSE_ID,
      expiresAt: "2026-08-09T10:10:00Z",
      rentalItemIds: [retained.id],
      items: [retained],
    }

    const reconciled = reconcileSearchResultSelection(
      result,
      selection,
      new Set([removed.id])
    )

    expect(reconciled.expiresAt).toBe("2026-08-09T10:10:00Z")
    expect(reconciled.groups[0].cabins.map((item) => item.id)).toEqual([
      retained.id,
    ])
    expect(isCabinSearchResultActive(reconciled, Date.now())).toBe(true)
  })
})
