import { describe, expect, it, vi } from "vitest"

import type { MediaClient, MediaAsset } from "@/features/media/media-service"
import type { RentalItemCreationIntent } from "@/features/rental-items/api/asset-rental-items-api"
import {
  creationPhotoCommandKeys,
  matchRentalItemCreationManifest,
  prepareRentalItemCreationPhotos,
  waitForRentalItemCreationPhotosReady,
} from "@/features/rental-items/rental-item-creation-intent-support"
import type { StagedRentalItemPhoto } from "@/features/rental-items/rental-item-creation-photo-uploader"

const WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const RENTAL_ITEM_ID = "33333333-3333-4333-8333-333333333333"
const INTENT_ID = "44444444-4444-4444-8444-444444444444"
const FOLDER_ID = "55555555-5555-4555-8555-555555555555"
const MEDIA_COMMAND_ID = "66666666-6666-4666-8666-666666666666"
const FIRST_UPLOAD_ID = "77777777-7777-4777-8777-777777777777"
const SECOND_UPLOAD_ID = "88888888-8888-4888-8888-888888888888"
const FIRST_CHECKSUM = "a".repeat(64)
const SECOND_CHECKSUM = "b".repeat(64)

function stagedPhoto(
  id: string,
  file: File,
  title: boolean
): StagedRentalItemPhoto {
  return { id, file, previewUrl: `blob:${id}`, title }
}

function intent(): RentalItemCreationIntent {
  return {
    id: INTENT_ID,
    version: 0,
    rentalItemId: RENTAL_ITEM_ID,
    warehouseId: WAREHOUSE_ID,
    state: "PENDING",
    expectedPhotoCount: 2,
    mediaFolderId: FOLDER_ID,
    mediaCommandId: MEDIA_COMMAND_ID,
    photoManifestSha256: "c".repeat(64),
    photoManifest: [
      {
        photoIndex: 0,
        uploadCommandId: FIRST_UPLOAD_ID,
        checksumSha256: SECOND_CHECKSUM,
        contentType: "image/png",
        contentLength: 6,
      },
      {
        photoIndex: 1,
        uploadCommandId: SECOND_UPLOAD_ID,
        checksumSha256: FIRST_CHECKSUM,
        contentType: "image/jpeg",
        contentLength: 5,
      },
    ],
    coverMediaId: null,
    mediaProofSha256: null,
    createdAt: "2026-08-31T09:00:00Z",
    completedAt: null,
    abandonedAt: null,
  }
}

function mediaAsset(
  id: string,
  status: MediaAsset["status"],
  generation: number
): MediaAsset {
  return {
    id,
    folderId: FOLDER_ID,
    fileName: `${id}.jpg`,
    contentType: "image/jpeg",
    kind: "IMAGE",
    status,
    version: 1,
    generation,
    rotationDegrees: 0,
    sortOrder: 0,
    sizeBytes: 5,
    createdAt: "2026-08-31T09:00:00Z",
    variants: [],
  }
}

describe("rental item creation intent support", () => {
  it("places the title first and computes the exact ordered manifest", async () => {
    const first = new File(["first"], "first.jpg", { type: "image/jpeg" })
    const second = new File(["second"], "second.png", { type: "image/png" })
    const calculateChecksumSha256 = vi.fn(async (file: Blob) =>
      file === second ? SECOND_CHECKSUM : FIRST_CHECKSUM
    )
    const mediaClient = { calculateChecksumSha256 } as unknown as MediaClient

    const prepared = await prepareRentalItemCreationPhotos(mediaClient, [
      stagedPhoto("first", first, false),
      stagedPhoto("second", second, true),
    ])

    expect(prepared.photos.map((photo) => photo.file.name)).toEqual([
      "second.png",
      "first.jpg",
    ])
    expect(prepared.manifest).toEqual([
      {
        photoIndex: 0,
        checksumSha256: SECOND_CHECKSUM,
        contentType: "image/png",
        contentLength: 6,
      },
      {
        photoIndex: 1,
        checksumSha256: FIRST_CHECKSUM,
        contentType: "image/jpeg",
        contentLength: 5,
      },
    ])
    expect(matchRentalItemCreationManifest(prepared, intent())).toEqual({
      matches: true,
    })
  })

  it("rejects reselected bytes that do not match the durable manifest", async () => {
    const mediaClient = {
      calculateChecksumSha256: vi.fn(async () => "d".repeat(64)),
    } as unknown as MediaClient
    const prepared = await prepareRentalItemCreationPhotos(mediaClient, [
      stagedPhoto(
        "first",
        new File(["wrong"], "first.jpg", { type: "image/jpeg" }),
        true
      ),
      stagedPhoto(
        "second",
        new File(["second"], "second.png", { type: "image/png" }),
        false
      ),
    ])

    expect(matchRentalItemCreationManifest(prepared, intent())).toMatchObject({
      matches: false,
      message: expect.stringContaining("не совпадают"),
    })
  })

  it("uses the exact server photo identity for every media command replay", () => {
    expect(creationPhotoCommandKeys(intent().photoManifest[0]!)).toEqual({
      createSession: FIRST_UPLOAD_ID,
      uploadAndFinalize: FIRST_UPLOAD_ID,
    })
  })

  it("waits for the exact uploaded assets to become READY", async () => {
    const first = mediaAsset(FIRST_UPLOAD_ID, "PROCESSING", 0)
    const second = mediaAsset(SECOND_UPLOAD_ID, "READY", 1)
    const listOwnerMedia = vi.fn().mockResolvedValue({
      items: [mediaAsset(FIRST_UPLOAD_ID, "READY", 1), second],
      next: null,
    })
    const delay = vi.fn(async () => undefined)
    const mediaClient = { listOwnerMedia } as unknown as MediaClient

    await expect(
      waitForRentalItemCreationPhotosReady({
        mediaClient,
        accessToken: "access-token",
        owner: {
          ownerType: "CABIN",
          ownerId: RENTAL_ITEM_ID,
          warehouseId: WAREHOUSE_ID,
          context: "WAREHOUSE",
        },
        folderId: FOLDER_ID,
        uploadedAssets: [first, second],
        delay,
        attempts: 2,
      })
    ).resolves.toBeUndefined()
    expect(listOwnerMedia).toHaveBeenCalledOnce()
    expect(delay).not.toHaveBeenCalled()
  })
})
