import { describe, expect, it } from "vitest"

import {
  createResyncTracker,
  parseWarehouseInvalidation,
} from "@/hooks/use-warehouse-realtime"

describe("warehouse SSE payloads", () => {
  it("reconciles cached data initially and always reconciles after reconnect", () => {
    const trackerWithEmptyCache = createResyncTracker()
    expect(trackerWithEmptyCache(false)).toBe(false)
    expect(trackerWithEmptyCache(false)).toBe(true)

    const trackerWithCachedData = createResyncTracker()
    expect(trackerWithCachedData(true)).toBe(true)
  })

  it("keeps aggregate and media identity for partial cache updates", () => {
    expect(
      parseWarehouseInvalidation({
        id: "event-1",
        event: "warehouse-invalidation",
        data: JSON.stringify({
          scope: "RENTAL_ITEMS_CHANGED",
          aggregateType: "RENTAL_ITEM",
          aggregateId: "cabin-1",
          changeType: "asset.rental-item.status-changed.v1",
        }),
      })
    ).toEqual({
      scope: "RENTAL_ITEMS_CHANGED",
      aggregateType: "RENTAL_ITEM",
      aggregateId: "cabin-1",
      changeType: "asset.rental-item.status-changed.v1",
      mediaId: undefined,
      ownerType: undefined,
      ownerId: undefined,
    })
  })

  it("accepts older eventType payloads and ignores unrelated SSE events", () => {
    expect(
      parseWarehouseInvalidation({
        id: null,
        event: "warehouse-invalidation",
        data: '{"scope":"MEDIA_CHANGED","eventType":"media.changed.v1"}',
      })?.changeType
    ).toBe("media.changed.v1")
    expect(
      parseWarehouseInvalidation({ id: null, event: "message", data: "{}" })
    ).toBeNull()
  })
})
