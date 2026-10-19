import { beforeEach, describe, expect, it, vi } from "vitest"

import type { DossierActivity } from "@/features/rental-items/dossier/model/dossier-service"

const dossierApi = vi.hoisted(() => ({
  getPage: vi.fn(),
}))

vi.mock("@/features/rental-items/dossier/api/rental-item-dossier-api", () => ({
  getRentalItemDossierPage: dossierApi.getPage,
}))

import { loadRentalItemPhotoActivities } from "@/features/rental-items/dossier/api/load-rental-item-photo-activities"

const CABIN_ID = "11111111-1111-4111-8111-111111111111"
const WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"

function activity(id: string): DossierActivity {
  return {
    activityId: id,
    cabinId: CABIN_ID,
    warehouseId: WAREHOUSE_ID,
    activityCode: "INVENTORY_INSPECTION_SAVED",
    occurredAt: "2026-08-21T01:00:00Z",
    recordedAt: "2026-08-21T01:00:01Z",
    actorRef: null,
    sourceRef: {
      producer: "inventory-service",
      aggregateType: "FINDING",
      aggregateId: id,
    },
    media: [],
    taskEvidencePhotos: [],
  }
}

beforeEach(() => {
  dossierApi.getPage.mockReset()
})

describe("loadRentalItemPhotoActivities", () => {
  it("loads every inventory/media page independently of interactive filters", async () => {
    const first = activity("33333333-3333-4333-8333-333333333333")
    const second = activity("44444444-4444-4444-8444-444444444444")
    dossierApi.getPage
      .mockResolvedValueOnce({ activities: [first], nextCursor: "page-2" })
      .mockResolvedValueOnce({ activities: [second], nextCursor: null })

    await expect(
      loadRentalItemPhotoActivities("photo-token", CABIN_ID)
    ).resolves.toEqual([first, second])
    expect(dossierApi.getPage).toHaveBeenNthCalledWith(
      1,
      "photo-token",
      CABIN_ID,
      { limit: 100, sourceTypes: ["INVENTORY", "MEDIA"] }
    )
    expect(dossierApi.getPage).toHaveBeenNthCalledWith(
      2,
      "photo-token",
      CABIN_ID,
      {
        limit: 100,
        sourceTypes: ["INVENTORY", "MEDIA"],
        after: "page-2",
      }
    )
  })

  it("rejects a repeated cursor instead of looping forever", async () => {
    dossierApi.getPage.mockResolvedValue({
      activities: [],
      nextCursor: "repeated",
    })

    await expect(
      loadRentalItemPhotoActivities("photo-token", CABIN_ID)
    ).rejects.toThrow("Dossier repeated the photo archive cursor")
    expect(dossierApi.getPage).toHaveBeenCalledTimes(2)
  })
})
