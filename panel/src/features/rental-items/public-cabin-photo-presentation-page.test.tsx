import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { PublicCabinPhotoPresentation } from "@/features/rental-items/cabin-photo-presentations-api"
import { ApiError } from "@/lib/api-client"

const api = vi.hoisted(() => ({
  get: vi.fn(),
}))

vi.mock("@/features/rental-items/cabin-photo-presentations-api", () => ({
  getPublicCabinPhotoPresentation: api.get,
}))

import { PublicCabinPhotoPresentationPage } from "@/features/rental-items/public-cabin-photo-presentation-page"

const presentation: PublicCabinPhotoPresentation = {
  id: "11111111-1111-4111-8111-111111111111",
  cabinNumber: "БЫТ-001",
  createdAt: "2026-08-24T12:00:00Z",
  photos: [
    {
      mediaId: "22222222-2222-4222-8222-222222222222",
      generation: 1,
      sortOrder: 20,
      thumbnailUrl: "/api/logistics/public/photo-2-small",
      contentUrl: "/api/logistics/public/photo-2-large",
    },
    {
      mediaId: "33333333-3333-4333-8333-333333333333",
      generation: 4,
      sortOrder: 10,
      thumbnailUrl: "/api/logistics/public/photo-1-small",
      contentUrl: "/api/logistics/public/photo-1-large",
    },
  ],
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <MemoryRouter initialEntries={["/photos/example-token"]}>
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route
            path="/photos/:token"
            element={<PublicCabinPhotoPresentationPage />}
          />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  api.get.mockResolvedValue(presentation)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("public cabin photo presentation", () => {
  it("shows only the cabin number and immutable photo snapshot", async () => {
    renderPage()

    expect(
      await screen.findByRole("heading", {
        name: "Фотографии бытовки БЫТ-001",
      })
    ).toBeTruthy()
    expect(api.get).toHaveBeenCalledWith("example-token")
    const images = screen.getAllByRole("img")
    expect(images).toHaveLength(2)
    expect(images[0]?.getAttribute("src")).toBe(
      "/api/logistics/public/photo-1-small"
    )
    expect(images[1]?.getAttribute("src")).toBe(
      "/api/logistics/public/photo-2-small"
    )
    expect(screen.queryByText(/склад/i)).toBeNull()
    expect(screen.queryByText(/арендатор/i)).toBeNull()
    expect(screen.queryByText(/паспорт/i)).toBeNull()
  })

  it("opens the large photo and supports next navigation", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Открыть фото 1 из 2" })
    )
    const viewer = screen.getByRole("dialog")
    expect(
      within(viewer)
        .getByRole("img", { name: "Бытовка БЫТ-001, фото 1 из 2" })
        .getAttribute("src")
    ).toBe("/api/logistics/public/photo-1-large")

    await user.click(screen.getByRole("button", { name: "Следующее фото" }))

    await waitFor(() =>
      expect(
        within(viewer)
          .getByRole("img", { name: "Бытовка БЫТ-001, фото 2 из 2" })
          .getAttribute("src")
      ).toBe("/api/logistics/public/photo-2-large")
    )
    expect(
      screen.getByRole("button", { name: "Закрыть просмотр" })
    ).toBeTruthy()
  })

  it("uses a safe not-found state without exposing an upstream error", async () => {
    api.get.mockRejectedValue(
      new ApiError("internal object locator: private-bucket/key", 404)
    )
    renderPage()

    expect(
      await screen.findByRole("heading", { name: "Представление не найдено" })
    ).toBeTruthy()
    expect(screen.queryByText(/private-bucket/)).toBeNull()
  })
})
