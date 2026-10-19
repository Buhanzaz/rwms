import { QueryClient } from "@tanstack/react-query"
import { describe, expect, it } from "vitest"

import type { CabinCoverProjection } from "@/features/media/media-service"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import {
  patchCabinCoverCaches,
  patchRentalItemCaches,
} from "@/hooks/warehouse-realtime-cache"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const OTHER_WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const SUBJECT = "operator-1"

function item(
  id: string,
  number: string,
  version = 0,
  warehouseId = WAREHOUSE_ID
): RentalItemDto {
  return {
    id,
    version,
    warehouseId,
    number,
    rentalTypeId: "type",
    dimensionId: "dimension",
    finishingId: "finishing",
    type: "Бытовка",
    dimensions: "6x2.5",
    finishing: "Внешняя",
    category: "Новая",
    characteristics: [],
    linoleum: false,
    status: "FREE",
    comment: null,
    mediaAvailability: "AVAILABLE",
    contents: null,
    contentsItems: [],
    shipmentDate: null,
    tenant: null,
    price: null,
    activeOrderReservation: null,
    passport: {},
    tags: [],
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
  }
}

function page(items: RentalItemDto[], totalElements = items.length) {
  return {
    pages: [
      {
        content: items,
        page: 0,
        size: 200,
        totalElements,
        totalPages: totalElements === 0 ? 0 : 1,
      },
    ],
    pageParams: [0],
  }
}

function cover(cabinId: string, mediaId: string): CabinCoverProjection {
  return {
    cabinId,
    photoCount: 1,
    cover: {
      mediaId,
      generation: 1,
      kind: "SMALL",
      contentType: "image/webp",
      contentPath: `/${mediaId}`,
      width: 320,
      height: 240,
    },
    previews: [],
  }
}

function newClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  })
}

describe("warehouse realtime cache patches", () => {
  it("replaces one changed cabin without invalidating the list", () => {
    const queryClient = newClient()
    const key = ["rental-items", SUBJECT, WAREHOUSE_ID, ""] as const
    const oldItem = item("cabin-1", "A-001", 1)
    queryClient.setQueryData(key, page([oldItem]))

    patchRentalItemCaches({
      queryClient,
      subject: SUBJECT,
      warehouseId: WAREHOUSE_ID,
      item: { ...oldItem, version: 2, status: "RENTED" },
      changeType: "asset.rental-item.status-changed.v1",
    })

    const data = queryClient.getQueryData<ReturnType<typeof page>>(key)
    expect(data?.pages[0]?.content[0]?.status).toBe("RENTED")
    expect(queryClient.getQueryState(key)?.isInvalidated).toBe(false)
  })

  it("inserts a newly created cabin and adjusts page totals", () => {
    const queryClient = newClient()
    const key = ["rental-items", SUBJECT, WAREHOUSE_ID, ""] as const
    queryClient.setQueryData(key, page([item("cabin-2", "A-002")]))

    patchRentalItemCaches({
      queryClient,
      subject: SUBJECT,
      warehouseId: WAREHOUSE_ID,
      item: item("cabin-1", "A-001"),
      changeType: "asset.rental-item.created.v1",
    })

    const data = queryClient.getQueryData<ReturnType<typeof page>>(key)
    expect(data?.pages[0]?.content.map((entry) => entry.number)).toEqual([
      "A-001",
      "A-002",
    ])
    expect(data?.pages[0]?.totalElements).toBe(2)
  })

  it("removes a cabin from the old warehouse on a move event", () => {
    const queryClient = newClient()
    const key = ["rental-items", SUBJECT, WAREHOUSE_ID, ""] as const
    const legacyDetailKey = ["rental-item", "cabin-1"] as const
    const oldItem = item("cabin-1", "A-001", 3)
    queryClient.setQueryData(key, page([oldItem]))
    queryClient.setQueryData(legacyDetailKey, oldItem)

    patchRentalItemCaches({
      queryClient,
      subject: SUBJECT,
      warehouseId: WAREHOUSE_ID,
      item: item("cabin-1", "A-001", 4, OTHER_WAREHOUSE_ID),
      changeType: "asset.rental-item.warehouse-changed.v1",
    })

    const data = queryClient.getQueryData<ReturnType<typeof page>>(key)
    expect(data?.pages[0]?.content).toEqual([])
    expect(data?.pages[0]?.totalElements).toBe(0)
    expect(queryClient.getQueryData(legacyDetailKey)).toBeNull()
  })

  it("updates only the requested cabin cover projection", () => {
    const queryClient = newClient()
    const key = [
      "rental-item-media-covers",
      SUBJECT,
      WAREHOUSE_ID,
      ["cabin-1", "cabin-2"],
    ] as const
    queryClient.setQueryData(key, {
      items: [cover("cabin-1", "old-1"), cover("cabin-2", "old-2")],
    })

    patchCabinCoverCaches(
      queryClient,
      WAREHOUSE_ID,
      "cabin-1",
      cover("cabin-1", "new-1")
    )

    const data = queryClient.getQueryData<{ items: CabinCoverProjection[] }>(
      key
    )
    expect(data?.items.map((entry) => entry.cover?.mediaId)).toEqual([
      "new-1",
      "old-2",
    ])
  })
})
