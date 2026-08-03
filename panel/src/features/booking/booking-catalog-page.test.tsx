import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

const api = vi.hoisted(() => ({
  available: true,
  list: vi.fn(),
  check: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "access-token",
    currentUser: { id: "manager-1", rentalAccess: true },
    status: "authenticated",
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouseId: "11111111-1111-4111-8111-111111111111",
    selectedWarehouse: {
      id: "11111111-1111-4111-8111-111111111111",
      name: "СПб",
    },
  }),
}))

vi.mock("@/features/assistant/components/manager-booking-alert-dialog", () => ({
  ManagerBookingAlertDialog: () => null,
}))

vi.mock("@/features/booking/api/booking-availability-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/booking/api/booking-availability-api")
  >("@/features/booking/api/booking-availability-api")
  return {
    ...actual,
    listAvailableRentalItems: api.list,
    checkRentalItemsAvailability: api.check,
  }
})

vi.mock("@/features/booking/booking-cabin-browser", () => ({
  BookingCabinBrowser: ({
    items,
    onToggle,
    actions,
    footer,
  }: {
    items: RentalItemDto[]
    onToggle: (item: RentalItemDto) => void
    actions: ReactNode
    footer: ReactNode
  }) => (
    <div>
      {items.map((item) => (
        <button key={item.id} type="button" onClick={() => onToggle(item)}>
          Выбрать {item.number}
        </button>
      ))}
      {actions}
      {footer}
    </div>
  ),
}))

import { BookingCatalogPage } from "@/features/booking/booking-catalog-page"
import { BookingSelectionProvider } from "@/features/booking/booking-selection-provider"
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
  characteristics: [],
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

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <MemoryRouter initialEntries={["/booking"]}>
      <QueryClientProvider client={queryClient}>
        <BookingSelectionProvider>
          <Routes>
            <Route path="/booking" element={<BookingCatalogPage />} />
            <Route
              path="/booking/continue"
              element={<div>Страница продолжения</div>}
            />
          </Routes>
        </BookingSelectionProvider>
      </QueryClientProvider>
    </MemoryRouter>
  )
}

afterEach(() => {
  cleanup()
  api.available = true
  vi.clearAllMocks()
})

describe("BookingCatalogPage", () => {
  it("updates the selected count and starts booking with the in-memory selection", async () => {
    const user = userEvent.setup()
    api.list.mockResolvedValue({
      content: [item],
      page: 0,
      size: 200,
      totalElements: 1,
      totalPages: 1,
    })
    api.check.mockImplementation(async () => ({
      warehouseId: item.warehouseId,
      items: [
        {
          rentalItemId: item.id,
          available: api.available,
          reason: api.available ? "AVAILABLE" : "PRESENTATION_HELD",
        },
      ],
    }))
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Выбрать БЫТ-001" })
    )
    expect(
      screen.getByRole("button", { name: "Забронировать 1 выбранных" })
    ).toBeTruthy()

    await user.click(
      screen.getByRole("button", { name: "Забронировать 1 выбранных" })
    )
    expect(screen.getByText("Страница продолжения")).toBeTruthy()
  })

  it("removes a cabin and apologizes when the authoritative check loses it", async () => {
    const user = userEvent.setup()
    api.available = false
    api.list.mockResolvedValue({
      content: [item],
      page: 0,
      size: 200,
      totalElements: 1,
      totalPages: 1,
    })
    api.check.mockImplementation(async () => ({
      warehouseId: item.warehouseId,
      items: [
        {
          rentalItemId: item.id,
          available: false,
          reason: "PRESENTATION_HELD",
        },
      ],
    }))
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Выбрать БЫТ-001" })
    )
    expect(
      await screen.findByRole("heading", {
        name: "Бытовка уже забронирована",
      })
    ).toBeTruthy()
    expect(
      screen.getByText(/БЫТ-001 уже выбрал другой пользователь/)
    ).toBeTruthy()

    await user.click(screen.getByRole("button", { name: "ОК" }))
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Забронировать 0 выбранных" })
      ).toHaveProperty("disabled", true)
    )
  })
})
