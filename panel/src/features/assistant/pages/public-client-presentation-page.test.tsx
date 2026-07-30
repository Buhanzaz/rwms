import { cleanup, render, screen } from "@testing-library/react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { ClientPresentation } from "@/features/assistant/api/rental-presentations-api"

const fixtures = vi.hoisted(() => ({
  presentation: {
    id: "presentation-1",
    revision: 1,
    state: "ACTIVE",
    expiresAt: "2026-07-27T15:00:00Z",
    viewUntil: "2026-07-27T15:00:00Z",
    bookedOrderId: null,
    groups: [
      {
        key: "available",
        label: "Доступные варианты",
        cabins: [
          {
            id: "cabin-1",
            number: "БЫТ-1",
            rentalType: "Бытовка",
            dimensions: "6 × 2,4 м",
            finishing: "ДВП",
            category: "Стандарт",
            characteristics: null,
            linoleum: true,
            passport: {
              legacyId: "spb-1",
              legacyWarehouseId: "spb",
              legacyNumber: "БЫТ-001",
              source: "old-panel-rental-items-v1",
              locationNodeId: "node-1",
              hasPhotos: false,
              photoCount: 0,
              mainPhotoUrl: "https://old.invalid/1.jpg",
              previewPhotoUrls: "https://old.invalid/1-small.jpg",
              tenant: "ООО Строй",
            },
            tags: [],
            photos: [],
          },
        ],
      },
    ],
  },
}))

vi.mock("@tanstack/react-query", () => ({
  useMutation: () => ({
    isError: false,
    isPending: false,
    mutate: vi.fn(),
  }),
  useQuery: ({ queryKey }: { queryKey: unknown[] }) => ({
    data:
      queryKey[0] === "public-client-presentation"
        ? fixtures.presentation
        : undefined,
    isError: false,
    isPending: false,
  }),
}))

vi.mock("@/components/media/photo-carousel", () => ({
  PhotoCarousel: ({ title }: { title: string }) => <div>{title}</div>,
}))

import { PublicClientPresentationPage } from "@/features/assistant/pages/public-client-presentation-page"

afterEach(cleanup)

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/client-presentations/example-token"]}>
      <Routes>
        <Route
          path="/client-presentations/:token"
          element={<PublicClientPresentationPage />}
        />
      </Routes>
    </MemoryRouter>
  )
}

describe("PublicClientPresentationPage layout", () => {
  it("leaves the last cabin clear of the sticky action bar and device safe area", () => {
    renderPage()

    expect(screen.getByRole("heading", { name: "Бытовка БЫТ-1" })).toBeTruthy()

    const page = screen.getByRole("main")
    expect(page.className).toContain("h-svh")
    expect(page.className).toContain("overflow-y-auto")

    const content = Array.from(page.children).find((element) =>
      element.classList.contains("max-w-6xl")
    )
    const actionBar = Array.from(page.children).find((element) =>
      element.classList.contains("bottom-0")
    )

    expect(content?.className).toContain(
      "pb-[calc(10rem+env(safe-area-inset-bottom))]"
    )
    expect(actionBar?.className).toContain(
      "pb-[calc(0.75rem+env(safe-area-inset-bottom))]"
    )
  })

  it("hides imported identity metadata from the public passport", () => {
    renderPage()

    expect(screen.getByText("tenant")).toBeTruthy()
    expect(screen.getByText("ООО Строй")).toBeTruthy()
    expect(screen.queryByText("legacyId")).toBeNull()
    expect(screen.queryByText("legacyWarehouseId")).toBeNull()
    expect(screen.queryByText("legacyNumber")).toBeNull()
    expect(screen.queryByText("source")).toBeNull()
    expect(screen.queryByText("old-panel-rental-items-v1")).toBeNull()
    expect(screen.queryByText("locationNodeId")).toBeNull()
    expect(screen.queryByText("hasPhotos")).toBeNull()
    expect(screen.queryByText("photoCount")).toBeNull()
    expect(screen.queryByText("mainPhotoUrl")).toBeNull()
    expect(screen.queryByText("previewPhotoUrls")).toBeNull()
  })
})

void (fixtures.presentation satisfies ClientPresentation)
