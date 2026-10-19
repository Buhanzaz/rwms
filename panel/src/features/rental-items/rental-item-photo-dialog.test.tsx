import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

const mediaState = vi.hoisted(() => ({
  photos: [
    {
      id: "current-1",
      url: "blob:current-medium-1",
      variants: { large: { url: "blob:current-large-1" } },
    },
    {
      id: "current-2",
      url: "blob:current-medium-2",
      variants: { large: { url: "blob:current-large-2" } },
    },
  ],
  archivePhotos: [
    { id: "old-archive", url: "blob:old-archive" },
    { id: "current-1", url: "blob:current-medium-1" },
    { id: "current-2", url: "blob:current-medium-2" },
  ],
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: "media-token" }),
}))

vi.mock("@/features/rental-items/use-rental-item-media", () => ({
  useRentalItemMedia: () => ({
    ...mediaState,
    isLoading: false,
    error: null,
    logicalPhotoCount: mediaState.photos.length,
  }),
}))

vi.mock("@/components/media/fullscreen-photo-viewer", () => ({
  FullscreenPhotoViewer: ({
    photos,
  }: {
    photos: Array<{ id: string; src: string; alt: string }>
  }) => (
    <div data-testid="fullscreen-photo-viewer">
      {photos.map((photo) => `${photo.id}|${photo.src}|${photo.alt}`).join(";")}
    </div>
  ),
}))

import { RentalItemPhotoDialog } from "@/features/rental-items/rental-item-photo-dialog"

const item: RentalItemDto = {
  id: "22222222-2222-4222-8222-222222222222",
  version: 1,
  warehouseId: "11111111-1111-4111-8111-111111111111",
  number: "БЫТ-001",
  rentalTypeId: "33333333-3333-4333-8333-333333333333",
  dimensionId: "44444444-4444-4444-8444-444444444444",
  finishingId: "55555555-5555-4555-8555-555555555555",
  type: "БК-1",
  dimensions: "2.4x6",
  finishing: "ДВП",
  category: "Новая",
  characteristics: [],
  linoleum: true,
  status: "WAREHOUSE",
  comment: null,
  contents: null,
  contentsItems: [],
  shipmentDate: null,
  tenant: null,
  price: null,
  passport: {},
  tags: [],
}

afterEach(cleanup)

describe("RentalItemPhotoDialog", () => {
  it("shows only the current active photo folder and excludes archive folders", () => {
    render(<RentalItemPhotoDialog item={item} open onOpenChange={vi.fn()} />)

    const viewer = screen.getByTestId("fullscreen-photo-viewer")
    expect(viewer.textContent).toContain(
      "current-1|blob:current-large-1|Бытовка БЫТ-001, фото 1 из 2"
    )
    expect(viewer.textContent).toContain(
      "current-2|blob:current-large-2|Бытовка БЫТ-001, фото 2 из 2"
    )
    expect(viewer.textContent).not.toContain("old-archive")
  })
})
