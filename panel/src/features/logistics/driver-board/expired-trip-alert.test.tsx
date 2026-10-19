import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ExpiredTripAlert } from "./expired-trip-alert"
const api = vi.hoisted(() => ({
  getExpiredTrips: vi.fn(),
  getRentalExpiredTrips: vi.fn(),
}))
vi.mock("./driver-board-api", () => api)
afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

function show() {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <ExpiredTripAlert
        accessToken="token"
        warehouseId="warehouse"
        subjectId="manager"
      />
    </QueryClientProvider>
  )
}

describe("expired trip history", () => {
  it("uses one limited manager feed for all authorized warehouses", async () => {
    api.getRentalExpiredTrips.mockResolvedValue([
      {
        id: "trip",
        warehouseId: "w2",
        scheduledDate: "2026-09-04",
        unitNumber: "Б-19",
        state: "CANCELLED",
        failureCode: "TRIP_DAY_EXPIRED",
      },
    ])
    render(
      <QueryClientProvider client={new QueryClient()}>
        <ExpiredTripAlert
          accessToken="rental-token"
          subjectId="manager"
          warehouseNames={{ w2: "Представительский склад" }}
        />
      </QueryClientProvider>
    )
    expect(await screen.findByText(/Представительский склад/)).toBeTruthy()
    expect(api.getRentalExpiredTrips).toHaveBeenCalledWith("rental-token")
    expect(api.getExpiredTrips).not.toHaveBeenCalled()
    expect(screen.queryByRole("link", { name: "Позвонить клиенту" })).toBeNull()
  })
  it("shows cancellation and custody guidance, with a client call action", async () => {
    api.getExpiredTrips.mockResolvedValue([
      {
        id: "trip",
        scheduledDate: "2026-09-04",
        unitNumber: "Б-17",
        state: "CANCELLED",
        failureCode: "TRIP_DAY_EXPIRED_CARGO_REVIEW",
        tripDetails: {
          clientName: "Клиент",
          primaryContactPhone: "+79990000000",
        },
      },
    ])
    show()
    expect(
      await screen.findByText("Отменённые просроченные рейсы: 1")
    ).toBeTruthy()
    await userEvent.click(screen.getByText(/Последние 50 автоотмен/))
    expect(screen.getByText(/бытовка не освобождена/)).toBeTruthy()
    expect(
      screen
        .getByRole("link", { name: "Позвонить клиенту" })
        .getAttribute("href")
    ).toBe("tel:+79990000000")
    expect(api.getExpiredTrips).toHaveBeenCalledWith("token", "warehouse")
  })
  it("does not fabricate an empty history on service failure", async () => {
    api.getExpiredTrips.mockRejectedValue(new Error("unavailable"))
    show()
    expect(
      await screen.findByText("История автоотмен временно недоступна")
    ).toBeTruthy()
  })
})
