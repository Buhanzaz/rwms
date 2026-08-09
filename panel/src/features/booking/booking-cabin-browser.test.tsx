import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

const coverApi = vi.hoisted(() => ({
  load: vi.fn().mockResolvedValue({ items: [] }),
}))

vi.mock("@/features/rental-items/use-rental-item-covers", () => ({
  RENTAL_ITEM_COVERS_QUERY_KEY: ["rental-item-media-covers"],
  loadRentalItemCoverPage: coverApi.load,
}))

vi.mock("@/features/rental-items/rental-item-photo-dialog", () => ({
  RentalItemPhotoDialog: () => null,
}))

vi.mock("@/features/rental-items/rental-items-grid-view", () => ({
  RentalItemsGridView: ({
    items,
    renderPhotoOverlay,
    mediaCovers,
    autoHeight,
  }: {
    items: RentalItemDto[]
    renderPhotoOverlay: (item: RentalItemDto) => ReactNode
    mediaCovers: ReadonlyMap<string, { previews: readonly unknown[] }>
    autoHeight?: boolean
  }) => (
    <div
      data-testid="booking-grid"
      data-auto-height={String(autoHeight)}
      data-preview-count={String(
        items[0] ? (mediaCovers.get(items[0].id)?.previews.length ?? 0) : 0
      )}
    >
      {items.map((item) => (
        <div key={item.id}>{renderPhotoOverlay(item)}</div>
      ))}
    </div>
  ),
}))

import { BookingCabinBrowser } from "@/features/booking/booking-cabin-browser"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

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
  characteristics: [
    {
      id: "66666666-6666-4666-8666-666666666666",
      name: "Пластиковое окно",
    },
  ],
  linoleum: true,
  status: "FREE",
  comment: null,
  contents: null,
  contentsItems: [],
  shipmentDate: null,
  tenant: null,
  price: null,
  passport: {},
  tags: [],
}

function renderBrowser(onToggle = vi.fn(), compact = false) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <BookingCabinBrowser
          accessToken="access-token"
          subjectId="manager-1"
          warehouseId={item.warehouseId}
          items={[item]}
          selectedIds={new Set()}
          search=""
          onSearchChange={vi.fn()}
          onToggle={onToggle}
          compact={compact}
        />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("BookingCabinBrowser", () => {
  it("exposes only the seven booking filters and the grid presentation", () => {
    renderBrowser()

    for (const label of [
      "Номер",
      "Тип",
      "Габариты",
      "Отделка",
      "Категория",
      "Характеристики",
      "Линолеум",
    ]) {
      expect(
        screen.getByRole("button", { name: new RegExp(`^${label}`) })
      ).toBeTruthy()
    }
    expect(screen.getByTestId("booking-grid")).toBeTruthy()
    expect(screen.queryByText("Столбцы")).toBeNull()
    expect(screen.queryByText("Список")).toBeNull()
    expect(screen.queryByText("Добавить новую бытовку")).toBeNull()
  })

  it("renders the cabin checkbox over the grid photo", async () => {
    const user = userEvent.setup()
    const onToggle = vi.fn()
    renderBrowser(onToggle)

    await user.click(
      screen.getByRole("checkbox", { name: "Выбрать бытовки БЫТ-001" })
    )
    expect(onToggle).toHaveBeenCalledWith(item)
  })

  it("uses one preview per card and a compact auto-height grid when requested", async () => {
    coverApi.load.mockResolvedValue({
      items: [
        {
          cabinId: item.id,
          photoCount: 2,
          cover: null,
          previews: [
            {
              mediaId: "33333333-3333-4333-8333-333333333333",
              generation: 1,
              kind: "SMALL",
              contentType: "image/webp",
              contentPath: "/api/media/first.webp",
              width: 360,
              height: 240,
            },
            {
              mediaId: "44444444-4444-4444-8444-444444444444",
              generation: 1,
              kind: "SMALL",
              contentType: "image/webp",
              contentPath: "/api/media/second.webp",
              width: 360,
              height: 240,
            },
          ],
        },
      ],
    })
    renderBrowser(vi.fn(), true)

    const grid = screen.getByTestId("booking-grid")
    await waitFor(() =>
      expect(grid.getAttribute("data-preview-count")).toBe("1")
    )
    expect(grid.getAttribute("data-auto-height")).toBe("true")
  })
})
