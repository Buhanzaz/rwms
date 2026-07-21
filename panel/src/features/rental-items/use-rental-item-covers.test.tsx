import { cleanup, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type {
  CabinCoverProjection,
  MediaVariant,
} from "@/features/media/media-service"

const media = vi.hoisted(() => ({
  createVariantObjectUrl: vi.fn(),
  listOwnerMedia: vi.fn(),
}))

vi.mock("@/features/media/media-service", () => ({
  cabinMediaOwner: (ownerId: string, warehouseId: string) => ({
    ownerType: "CABIN",
    ownerId,
    warehouseId,
    context: "WAREHOUSE",
  }),
  createHttpMediaClient: () => media,
}))

import { useRentalItemCardPhotos } from "@/features/rental-items/use-rental-item-covers"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const CABIN_ID = "22222222-2222-4222-8222-222222222222"
const COVER_ID = "33333333-3333-4333-8333-333333333333"
const SECOND_ID = "44444444-4444-4444-8444-444444444444"

function variant(kind: "SMALL" | "MEDIUM" | "LARGE", contentPath: string) {
  return {
    kind,
    contentType: "image/webp",
    contentPath,
    width: 320,
    height: 240,
  } satisfies MediaVariant
}

const projection: CabinCoverProjection = {
  cabinId: CABIN_ID,
  photoCount: 2,
  cover: {
    mediaId: COVER_ID,
    generation: 1,
    ...variant("SMALL", "/cover-small"),
  },
  previews: [
    {
      mediaId: COVER_ID,
      generation: 1,
      ...variant("SMALL", "/cover-small"),
    },
    {
      mediaId: SECOND_ID,
      generation: 1,
      ...variant("SMALL", "/second-small"),
    },
  ],
}

function Harness() {
  const result = useRentalItemCardPhotos({
    accessToken: "read-token",
    cabinId: CABIN_ID,
    warehouseId: WAREHOUSE_ID,
    projection,
    coverAvailability: "available",
  })
  return (
    <div>
      <span data-testid="availability">{result.availability}</span>
      <span data-testid="photo-ids">
        {result.photos.map((photo) => photo.id).join(",")}
      </span>
    </div>
  )
}

afterEach(() => {
  cleanup()
  media.createVariantObjectUrl.mockReset()
  media.listOwnerMedia.mockReset()
})

describe("useRentalItemCardPhotos", () => {
  it("loads the ordered batch previews once and requests SMALL only", async () => {
    media.createVariantObjectUrl.mockImplementation(
      async (_token: string, _owner: unknown, requested: MediaVariant) => ({
        url: `blob:${requested.contentPath}`,
        dispose: vi.fn(),
      })
    )

    render(<Harness />)

    await waitFor(() =>
      expect(screen.getByTestId("photo-ids").textContent).toBe(
        `${COVER_ID},${SECOND_ID}`
      )
    )
    expect(screen.getByTestId("availability").textContent).toBe("available")
    expect(media.createVariantObjectUrl).toHaveBeenCalledTimes(2)
    expect(
      media.createVariantObjectUrl.mock.calls.map(
        (call) => (call[2] as MediaVariant).kind
      )
    ).toEqual(["SMALL", "SMALL"])
    expect(
      media.createVariantObjectUrl.mock.calls.map(
        (call) => (call[2] as MediaVariant).contentPath
      )
    ).toEqual(["/cover-small", "/second-small"])
    expect(media.listOwnerMedia).not.toHaveBeenCalled()
  })
})
