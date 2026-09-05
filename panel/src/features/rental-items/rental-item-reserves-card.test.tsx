import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const api = vi.hoisted(() => ({ getRentalItemReserves: vi.fn() }))
vi.mock("./rental-item-reserves-api", () => api)
vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: "token", currentUser: { id: "viewer" } }),
}))
import { RentalItemReservesCard } from "./rental-item-reserves-card"
import type {
  RentalItemReserve,
  RentalItemReserves,
} from "./rental-item-reserves-api"

const cabinId = "11111111-1111-4111-8111-111111111111"
const warehouseId = "22222222-2222-4222-8222-222222222222"
const orderId = "44444444-4444-4444-8444-444444444444"
function reserve(
  overrides: Partial<RentalItemReserve> = {}
): RentalItemReserve {
  return {
    reservationId: "33333333-3333-4333-8333-333333333333",
    kind: "SELECTION_HOLD",
    source: "CUSTOMER",
    createdAt: "2026-09-05T11:59:00Z",
    expiresAt: "2026-09-05T12:04:00Z",
    clientDisplayName: null,
    managerDisplayName: null,
    orderId: null,
    orderNumber: null,
    orderStatus: null,
    paymentState: null,
    canOpenOrder: false,
    ...overrides,
  }
}
function response(reserves: RentalItemReserve[]): RentalItemReserves {
  return {
    rentalItemId: cabinId,
    warehouseId,
    serverTime: "2026-09-05T12:00:00Z",
    reserves,
  }
}
function renderCard(timeZone: string | undefined = "Europe/Moscow") {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <RentalItemReservesCard
          rentalItemId={cabinId}
          warehouseId={warehouseId}
          timeZone={timeZone}
        />
      </QueryClientProvider>
    </MemoryRouter>
  )
  return queryClient
}
beforeEach(() => api.getRentalItemReserves.mockReset())
afterEach(cleanup)

describe("cabin passport reserves", () => {
  it("shows customer and manager holds alongside the real order reservation", async () => {
    api.getRentalItemReserves.mockResolvedValue(
      response([
        reserve({ clientDisplayName: "Анна Клиентская" }),
        reserve({
          reservationId: "manager-hold",
          source: "MANAGER",
          managerDisplayName: "Ольга Менеджер",
        }),
        reserve({
          reservationId: "order-reserve",
          kind: "ORDER_RESERVATION",
          source: "MANAGER",
          orderId,
          orderNumber: "ORD-42",
          orderStatus: "SAVED",
          paymentState: "PENDING",
          canOpenOrder: true,
        }),
      ])
    )
    renderCard()
    expect(await screen.findByText("Анна Клиентская")).toBeTruthy()
    expect(screen.getByText("Ольга Менеджер")).toBeTruthy()
    expect(screen.getByText("Клиентское приложение")).toBeTruthy()
    expect(screen.getByText("Ожидает оплаты")).toBeTruthy()
    expect(
      screen.getByText("Ожидает оплаты").getAttribute("data-variant")
    ).toBe("warning")
    expect(
      screen
        .getByRole("link", { name: "Открыть заказ ORD-42" })
        .getAttribute("href")
    ).toBe(`/orders/${orderId}`)
    expect(screen.getAllByText(/15:04/).length).toBe(3)
    expect(api.getRentalItemReserves).toHaveBeenCalledWith(
      "token",
      cabinId,
      warehouseId
    )
  })

  it("does not invent names or expose an order link without server permission", async () => {
    api.getRentalItemReserves.mockResolvedValue(
      response([
        reserve({ orderId, orderNumber: "Hidden order", canOpenOrder: false }),
      ])
    )
    renderCard()
    await screen.findByText("Клиентское приложение")
    expect(screen.queryByRole("link")).toBeNull()
    expect(screen.queryByText("Hidden order")).toBeNull()
    expect(screen.queryByText("Клиент", { exact: true })).toBeNull()
  })

  it("does not show a successful empty list on failure and retries the real read", async () => {
    const user = userEvent.setup()
    api.getRentalItemReserves.mockRejectedValue(
      new Error("Сервис резервов недоступен")
    )
    renderCard()
    expect(await screen.findByText("Сервис резервов недоступен")).toBeTruthy()
    expect(screen.queryByText("Активных резервов нет.")).toBeNull()
    api.getRentalItemReserves.mockResolvedValue(response([]))
    await user.click(screen.getByRole("button", { name: "Обновить резервы" }))
    expect(await screen.findByText("Активных резервов нет.")).toBeTruthy()
  })

  it("removes released occupancy only from a refreshed server result and hides stale links on error", async () => {
    api.getRentalItemReserves.mockResolvedValue(
      response([
        reserve({
          orderId,
          canOpenOrder: true,
          kind: "ORDER_RESERVATION",
          paymentState: "EXPIRING",
        }),
      ])
    )
    const queryClient = renderCard()
    expect(
      await screen.findByText("Освобождаем неоплаченную бронь")
    ).toBeTruthy()
    expect(
      screen
        .getByText("Освобождаем неоплаченную бронь")
        .getAttribute("data-variant")
    ).toBe("progress")
    api.getRentalItemReserves.mockRejectedValue(
      new Error("Обновление недоступно")
    )
    await act(async () => {
      await queryClient.invalidateQueries({
        queryKey: ["rental-item-reserves"],
      })
    })
    await waitFor(() => expect(screen.queryByRole("link")).toBeNull())
    expect(screen.queryByText("Активных резервов нет.")).toBeNull()
    api.getRentalItemReserves.mockResolvedValue(response([]))
    await act(async () => {
      await queryClient.invalidateQueries({
        queryKey: ["rental-item-reserves"],
      })
    })
    expect(await screen.findByText("Активных резервов нет.")).toBeTruthy()
  })

  it("labels UTC explicitly when warehouse timezone is unavailable", async () => {
    api.getRentalItemReserves.mockResolvedValue(response([reserve()]))
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <RentalItemReservesCard
            rentalItemId={cabinId}
            warehouseId={warehouseId}
          />
        </QueryClientProvider>
      </MemoryRouter>
    )
    expect(await screen.findByText(/12:04/)).toBeTruthy()
    expect(screen.getByText(/время указано в UTC/)).toBeTruthy()
  })
})
