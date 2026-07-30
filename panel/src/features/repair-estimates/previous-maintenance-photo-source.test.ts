import { describe, expect, it } from "vitest"

import type { MediaAsset } from "@/features/media/media-service"
import type {
  MaintenanceEstimate,
  MaintenanceRepair,
} from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import { selectLatestPreviousMaintenancePhotoSource } from "@/features/repair-estimates/previous-maintenance-photo-source"

const warehouseId = "11111111-1111-4111-8111-111111111111"
const rentalItemId = "22222222-2222-4222-8222-222222222222"

function estimate(
  id: string,
  recordedAt: string,
  mediaIds: string[]
): MaintenanceEstimate {
  return {
    id,
    warehouseId,
    rentalItemId,
    version: 1,
    lifecycle: "COMPLETED",
    currentRevision: 1,
    revisions: [
      {
        revision: 1,
        dispatchDate: "2026-07-20",
        sourceParty: null,
        lines: [],
        plan: [],
        total: "0.00",
        reason: null,
        recordedAt,
      },
    ],
    repairId: null,
    mediaReferences: mediaIds.map((mediaId) => ({
      mediaId,
      generation: 1,
    })),
    createdAt: recordedAt,
    completedAt: recordedAt,
    actor: {
      actorId: "33333333-3333-4333-8333-333333333333",
      actorType: "USER",
    },
  }
}

function repair(
  id: string,
  updatedAt: string,
  mediaIds: string[]
): MaintenanceRepair {
  return {
    id,
    rootRepairId: id,
    sourceRepairId: null,
    estimateId: null,
    warehouseId,
    rentalItemId,
    origin: "DIRECT_REPAIR",
    kind: "PRIMARY",
    executionState: "COMPLETED",
    acceptanceState: "ACCEPTED",
    version: 1,
    dispatchDate: "2026-07-20",
    priority: 3,
    sourceParty: null,
    plan: {
      repairId: id,
      repairVersion: 1,
      stages: [],
    },
    inventorySource: null,
    lease: null,
    mediaReferences: mediaIds.map((mediaId) => ({
      mediaId,
      generation: 1,
    })),
    complexity: {
      type: "LIGHT",
      name: "Лёгкий ремонт",
      color: "#22C55E",
      plannedMinutes: "30",
      forcedCapital: false,
    },
    movementToShipment: false,
    createdAt: updatedAt,
    updatedAt,
    actor: {
      actorId: "33333333-3333-4333-8333-333333333333",
      actorType: "USER",
    },
  }
}

function cabinAsset({
  id,
  folderId,
  createdAt,
  sortOrder,
}: {
  id: string
  folderId: string
  createdAt: string
  sortOrder: number
}): MediaAsset {
  return {
    id,
    folderId,
    fileName: `${id}.jpg`,
    contentType: "image/jpeg",
    kind: "IMAGE",
    status: "READY",
    version: 1,
    generation: 1,
    rotationDegrees: 0,
    sortOrder,
    sizeBytes: 100,
    createdAt,
    variants: [],
  }
}

describe("selectLatestPreviousMaintenancePhotoSource", () => {
  it("selects only the newest upload folder from the latest previous source", () => {
    const currentEstimateId = "40000000-0000-4000-8000-000000000001"
    const olderFolderId = "50000000-0000-4000-8000-000000000001"
    const latestFolderId = "50000000-0000-4000-8000-000000000002"
    const latestFirstId = "60000000-0000-4000-8000-000000000001"
    const latestSecondId = "60000000-0000-4000-8000-000000000002"

    const source = selectLatestPreviousMaintenancePhotoSource({
      warehouseId,
      rentalItemId,
      estimates: [
        estimate(currentEstimateId, "2026-07-27T10:00:00Z", [
          "70000000-0000-4000-8000-000000000001",
        ]),
        estimate(
          "40000000-0000-4000-8000-000000000002",
          "2026-07-24T10:00:00Z",
          ["70000000-0000-4000-8000-000000000002"]
        ),
      ],
      repairs: [
        repair("80000000-0000-4000-8000-000000000001", "2026-07-25T10:00:00Z", [
          "90000000-0000-4000-8000-000000000001",
        ]),
      ],
      cabinAssets: [
        cabinAsset({
          id: "60000000-0000-4000-8000-000000000003",
          folderId: olderFolderId,
          createdAt: "2026-07-23T10:00:00Z",
          sortOrder: 0,
        }),
        cabinAsset({
          id: latestSecondId,
          folderId: latestFolderId,
          createdAt: "2026-07-26T10:00:01Z",
          sortOrder: 1,
        }),
        cabinAsset({
          id: latestFirstId,
          folderId: latestFolderId,
          createdAt: "2026-07-26T10:00:00Z",
          sortOrder: 0,
        }),
      ],
      currentOwner: {
        ownerType: "MAINTENANCE_ESTIMATE",
        ownerId: currentEstimateId,
      },
      currentCreatedAt: "2026-07-27T10:00:00Z",
    })

    expect(source).toMatchObject({
      owner: {
        ownerType: "CABIN",
        ownerId: rentalItemId,
      },
      label: "Фото бытовки",
      mediaIds: [latestFirstId, latestSecondId],
    })
  })

  it("uses the source estimate for a repair when it is the latest prior shoot", () => {
    const sourceEstimateId = "40000000-0000-4000-8000-000000000010"
    const sourceMediaId = "70000000-0000-4000-8000-000000000010"
    const currentRepairId = "80000000-0000-4000-8000-000000000010"

    const source = selectLatestPreviousMaintenancePhotoSource({
      warehouseId,
      rentalItemId,
      estimates: [
        estimate(sourceEstimateId, "2026-07-27T09:59:59Z", [sourceMediaId]),
      ],
      repairs: [
        repair(currentRepairId, "2026-07-27T10:00:00Z", [
          "90000000-0000-4000-8000-000000000010",
        ]),
      ],
      cabinAssets: [],
      currentOwner: {
        ownerType: "MAINTENANCE_REPAIR",
        ownerId: currentRepairId,
      },
      currentCreatedAt: "2026-07-27T10:00:00Z",
    })

    expect(source).toMatchObject({
      owner: {
        ownerType: "MAINTENANCE_ESTIMATE",
        ownerId: sourceEstimateId,
      },
      label: "Смета",
      mediaIds: [sourceMediaId],
    })
  })

  it("does not treat a later shoot as photos before", () => {
    const source = selectLatestPreviousMaintenancePhotoSource({
      warehouseId,
      rentalItemId,
      estimates: [
        estimate(
          "40000000-0000-4000-8000-000000000020",
          "2026-07-28T10:00:00Z",
          ["70000000-0000-4000-8000-000000000020"]
        ),
      ],
      repairs: [],
      cabinAssets: [],
      currentOwner: null,
      currentCreatedAt: "2026-07-27T10:00:00Z",
    })

    expect(source).toBeNull()
  })
})
