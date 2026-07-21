import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { useState } from "react"
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest"

import type { CabinCoverProjection } from "@/features/media/media-service"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

const coverUrl = vi.hoisted(() => vi.fn())

vi.mock("@tanstack/react-virtual", () => ({
  useVirtualizer: () => ({
    getVirtualItems: () => [{ index: 0, key: "row-0", start: 0 }],
    getTotalSize: () => 240,
    measure: vi.fn(),
  }),
}))

vi.mock("@/features/rental-items/use-rental-item-covers", () => ({
  useRentalItemCardPhotos: coverUrl,
}))

vi.mock("@/components/media/photo-carousel", () => ({
  PhotoCarousel: ({
    photos,
    imageVariant,
    photoCount,
    controlsVisibility,
  }: {
    photos: Array<
      | string
      | {
          url: string
          variants?: { small?: { url: string } }
        }
    >
    imageVariant: string
    photoCount: number
    controlsVisibility: string
  }) => {
    const [activeIndex, setActiveIndex] = useState(0)
    const photo = photos[activeIndex]
    const src =
      typeof photo === "string"
        ? photo
        : imageVariant === "thumbnail"
          ? (photo?.variants?.small?.url ?? photo?.url)
          : photo?.url
    return (
      <div>
        {src ? <img src={src} alt="Обложка бытовки" /> : null}
        <span data-testid="grid-photo-count">{photoCount}</span>
        <span data-testid="grid-photo-source-count">{photos.length}</span>
        <span data-testid="grid-photo-controls">{controlsVisibility}</span>
        {photos.length > 1 ? (
          <>
            <button
              type="button"
              aria-label="Предыдущее фото"
              onClick={() =>
                setActiveIndex(
                  (current) => (current - 1 + photos.length) % photos.length
                )
              }
            />
            <button
              type="button"
              aria-label="Следующее фото"
              onClick={() =>
                setActiveIndex((current) => (current + 1) % photos.length)
              }
            />
          </>
        ) : null}
      </div>
    )
  },
}))

import { RentalItemsGridView } from "@/features/rental-items/rental-items-grid-view"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const CABIN_ID = "22222222-2222-4222-8222-222222222222"

const item: RentalItemDto = {
  id: CABIN_ID,
  version: 1,
  warehouseId: WAREHOUSE_ID,
  number: "БЫТ-001",
  type: "БК-1",
  dimensions: "2.4x6",
  finishing: "ДВП",
  category: "Новая",
  characteristics: "Пластиковое окно",
  linoleum: true,
  status: "WAREHOUSE",
  comment: null,
  hasPhotos: true,
  photoCount: 1,
  mainPhotoUrl: "/legacy-medium.jpg",
  previewPhotoUrls: ["/legacy-medium.jpg"],
  legacyPhotos: [
    {
      id: "legacy-photo",
      url: "/legacy-medium.jpg",
      variants: {
        small: { url: "/legacy-small.webp" },
        largeWebp: { url: "/legacy-large.webp" },
      },
    },
  ],
  locationNodeId: null,
  contents: null,
  contentsItems: [],
  shipmentDate: null,
  tenant: null,
  price: null,
}

beforeAll(() => {
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      disconnect() {}
    }
  )
})

afterEach(() => {
  cleanup()
  coverUrl.mockReset()
})

function renderGrid({
  renderedItem = item,
  mediaCovers = new Map<string, CabinCoverProjection>(),
  coverAvailability = "available",
}: {
  renderedItem?: RentalItemDto
  mediaCovers?: Map<string, CabinCoverProjection>
  coverAvailability?: "loading" | "available" | "unavailable"
} = {}) {
  return render(
    <RentalItemsGridView
      items={[renderedItem]}
      gridFormat={{ columns: 1, rows: 1 }}
      accessToken="read-token"
      mediaCovers={mediaCovers}
      coverAvailability={coverAvailability}
      onOpenPhotos={vi.fn()}
      onOpenItem={vi.fn()}
    />
  )
}

describe("RentalItemsGridView photo covers", () => {
  it("renders the real imported SMALL URL instead of the legacy base URL", () => {
    coverUrl.mockReturnValue({ photos: [], availability: "available" })

    renderGrid()

    expect(
      screen.getByRole("img", { name: "Обложка бытовки" }).getAttribute("src")
    ).toBe("/legacy-small.webp")
    expect(screen.getByTestId("grid-photo-count").textContent).toBe("1")
  })

  it("shows arrows and navigates through two logical service SMALL photos", async () => {
    const user = userEvent.setup()
    coverUrl.mockReturnValue({
      photos: [
        {
          id: "33333333-3333-4333-8333-333333333333",
          generation: 4,
          url: "blob:service-small",
        },
        {
          id: "44444444-4444-4444-8444-444444444444",
          generation: 1,
          url: "blob:service-second-small",
        },
      ],
      availability: "available",
    })
    const projection: CabinCoverProjection = {
      cabinId: CABIN_ID,
      photoCount: 2,
      cover: {
        mediaId: "33333333-3333-4333-8333-333333333333",
        generation: 4,
        kind: "SMALL",
        contentType: "image/webp",
        contentPath: "/api/media/small.webp",
        width: 360,
        height: 240,
      },
      previews: [
        {
          mediaId: "33333333-3333-4333-8333-333333333333",
          generation: 4,
          kind: "SMALL",
          contentType: "image/webp",
          contentPath: "/api/media/small.webp",
          width: 360,
          height: 240,
        },
        {
          mediaId: "44444444-4444-4444-8444-444444444444",
          generation: 1,
          kind: "SMALL",
          contentType: "image/webp",
          contentPath: "/api/media/second-small.webp",
          width: 360,
          height: 240,
        },
      ],
    }

    renderGrid({
      renderedItem: {
        ...item,
        hasPhotos: false,
        photoCount: 0,
        mainPhotoUrl: null,
        previewPhotoUrls: [],
        legacyPhotos: [],
      },
      mediaCovers: new Map([[CABIN_ID, projection]]),
    })

    expect(
      screen.getByRole("img", { name: "Обложка бытовки" }).getAttribute("src")
    ).toBe("blob:service-small")
    expect(screen.getByTestId("grid-photo-count").textContent).toBe("2")
    expect(screen.getByTestId("grid-photo-source-count").textContent).toBe("2")
    expect(screen.getByTestId("grid-photo-controls").textContent).toBe("always")

    await user.click(screen.getByRole("button", { name: "Следующее фото" }))
    expect(
      screen.getByRole("img", { name: "Обложка бытовки" }).getAttribute("src")
    ).toBe("blob:service-second-small")
  })

  it("shows Нет фото after a successful response with no photos", () => {
    coverUrl.mockReturnValue({ photos: [], availability: "available" })

    renderGrid({
      renderedItem: {
        ...item,
        hasPhotos: false,
        photoCount: 0,
        mainPhotoUrl: null,
        previewPhotoUrls: [],
        legacyPhotos: [],
      },
      mediaCovers: new Map([
        [
          CABIN_ID,
          {
            cabinId: CABIN_ID,
            photoCount: 0,
            cover: null,
            previews: [],
          },
        ],
      ]),
    })

    expect(screen.getByText("Нет фото")).toBeTruthy()
  })

  it("shows a local service error without replacing the warehouse grid", () => {
    coverUrl.mockReturnValue({ photos: [], availability: "available" })

    renderGrid({
      renderedItem: {
        ...item,
        hasPhotos: false,
        photoCount: 0,
        mainPhotoUrl: null,
        previewPhotoUrls: [],
        legacyPhotos: [],
      },
      coverAvailability: "unavailable",
    })

    expect(screen.getByText("Сервис фото недоступен")).toBeTruthy()
    expect(screen.getByText("БЫТ-001")).toBeTruthy()
  })
})
