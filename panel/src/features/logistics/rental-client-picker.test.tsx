import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const ordersApi = vi.hoisted(() => ({
  listOrderClients: vi.fn(),
}))

vi.mock("@/features/orders/api/orders-api", () => ({
  ORDERS_QUERY_KEY: ["orders"],
  listOrderClients: ordersApi.listOrderClients,
}))

import { RentalClientPicker } from "@/features/logistics/rental-client-picker"

const RECENT_CLIENT = {
  id: "11111111-1111-4111-8111-111111111111",
  version: 3,
  type: "LEGAL_ENTITY" as const,
  displayName: "ООО Недавний контрагент",
}

function renderPicker(onChange = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <RentalClientPicker
        accessToken="client-token"
        idPrefix="shipment"
        value={null}
        onChange={onChange}
      />
    </QueryClientProvider>
  )
  return onChange
}

beforeEach(() => {
  ordersApi.listOrderClients.mockResolvedValue({
    content: [RECENT_CLIENT],
    page: 0,
    size: 5,
    totalElements: 1,
    totalPages: 1,
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("RentalClientPicker", () => {
  it("shows the five most recent counterparties before the user starts typing", async () => {
    const user = userEvent.setup()
    renderPicker()

    await waitFor(() =>
      expect(ordersApi.listOrderClients).toHaveBeenCalledWith({
        accessToken: "client-token",
        search: "",
        page: 0,
        size: 5,
      })
    )

    await user.click(screen.getByLabelText("Контрагент"))

    expect(await screen.findByText(RECENT_CLIENT.displayName)).toBeTruthy()
    expect(screen.queryByText("Тип контрагента")).toBeNull()
    expect(screen.queryByText("Компания")).toBeNull()
    expect(screen.queryByText("Клиент")).toBeNull()
  })

  it("searches all existing counterparties without a type selector", async () => {
    const user = userEvent.setup()
    renderPicker()

    await user.type(screen.getByLabelText("Контрагент"), "Тест")

    await waitFor(() =>
      expect(ordersApi.listOrderClients).toHaveBeenCalledWith({
        accessToken: "client-token",
        search: "Тест",
        page: 0,
        size: 50,
      })
    )
  })
})
