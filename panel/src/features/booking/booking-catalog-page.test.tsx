import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

const api = vi.hoisted(() => ({
  available: true,
  list: vi.fn(),
  check: vi.fn(),
  hold: vi.fn(),
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

vi.mock("@/features/booking/api/manual-booking-drafts-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/booking/api/manual-booking-drafts-api")
  >("@/features/booking/api/manual-booking-drafts-api")
  return { ...actual, putManualBookingDraftHold: api.hold }
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

function configureAvailableItem() {
  api.list.mockResolvedValue({
    content: [item],
    page: 0,
    size: 50,
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
  api.hold.mockImplementation(async (params) => ({
    draftId: params.draftId,
    warehouseId: params.warehouseId,
    expiresAt: "2099-08-04T12:00:00Z",
    rentalItemIds: [...params.rentalItemIds],
  }))
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
  vi.useRealTimers()
})

describe("BookingCatalogPage", () => {
  it("loads available cabins in 50-item pages without a timed full-catalog refresh", async () => {
    configureAvailableItem()
    renderPage()

    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(1))
    expect(api.list).toHaveBeenCalledWith(
      expect.objectContaining({ size: 50, search: "" })
    )

    vi.useFakeTimers()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(15_100)
    })
    expect(api.list).toHaveBeenCalledTimes(1)
  })

  it("stages checked cabins without a hold and reserves only on Continue", async () => {
    const user = userEvent.setup()
    configureAvailableItem()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Выбрать БЫТ-001" })
    )
    expect(
      screen.getByRole("button", { name: "Добавить 1 выбранных" })
    ).toBeTruthy()
    expect(api.hold).not.toHaveBeenCalled()

    await user.click(
      screen.getByRole("button", { name: "Добавить 1 выбранных" })
    )
    expect(
      screen.getByRole("button", { name: "Добавить 0 выбранных" })
    ).toHaveProperty("disabled", true)
    expect(api.hold).not.toHaveBeenCalled()

    await user.click(
      screen.getByRole("button", { name: "Продолжить бронирование" })
    )
    expect(await screen.findByText("Страница продолжения")).toBeTruthy()
    expect(api.hold).toHaveBeenCalledTimes(1)
    expect(api.hold.mock.calls[0][0]).toMatchObject({
      warehouseId: item.warehouseId,
      rentalItemIds: [item.id],
    })
    expect(api.hold.mock.calls[0][0].draftId).toMatch(/^[0-9a-f-]{36}$/)
  })

  it("does not navigate when the server reserve fails", async () => {
    const user = userEvent.setup()
    configureAvailableItem()
    api.hold.mockRejectedValue(new Error("Резерв не создан"))
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Выбрать БЫТ-001" })
    )
    await user.click(
      screen.getByRole("button", { name: "Добавить 1 выбранных" })
    )
    await user.click(
      screen.getByRole("button", { name: "Продолжить бронирование" })
    )

    await waitFor(() => expect(api.hold).toHaveBeenCalledTimes(1))
    expect(screen.queryByText("Страница продолжения")).toBeNull()
    expect(
      screen.getByRole("button", { name: "Продолжить бронирование" })
    ).toBeTruthy()
  })

  it("removes a lost preliminary cabin and shows its full details", async () => {
    const user = userEvent.setup()
    configureAvailableItem()
    api.available = false
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Выбрать БЫТ-001" })
    )
    expect(
      await screen.findByRole("heading", { name: "Бытовка уже недоступна" })
    ).toBeTruthy()
    expect(
      screen.getByRole("list", { name: "Недоступные бытовки" }).textContent
    ).toContain("БЫТ-001 — БК-1 — Новая")

    await user.click(screen.getByRole("button", { name: "ОК" }))
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Добавить 0 выбранных" })
      ).toHaveProperty("disabled", true)
    )
  })
})
