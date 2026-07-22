import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const ordersApi = vi.hoisted(() => ({
  listOrders: vi.fn(),
  createOrder: vi.fn(),
  listOrderClients: vi.fn(),
  createOrderIdempotencyKey: vi.fn(
    () => "99999999-9999-4999-8999-999999999999"
  ),
}))

vi.mock("@/features/orders/api/orders-api", () => ({
  ORDERS_QUERY_KEY: ["orders"],
  ...ordersApi,
}))

vi.mock("@/features/orders/orders-module-context", () => ({
  useOrdersModule: () => ({
    accessToken: "orders-token",
    currentUser: {
      id: "11111111-1111-4111-8111-111111111111",
      username: "admin",
      displayName: "Администратор",
      firstName: null,
      lastName: null,
      email: null,
      principalType: "USER",
      globalRole: "WMS_ADMIN",
      warehouseAccessAll: true,
      warehouseAccesses: [],
    },
    warehouses: [
      {
        id: "22222222-2222-4222-8222-222222222222",
        code: "MSK",
        name: "Москва",
        city: "Москва",
        address: null,
      },
    ],
  }),
}))

import { OrdersListPage } from "@/features/orders/pages/orders-list-page"

const order = {
  id: "33333333-3333-4333-8333-333333333333",
  version: 1,
  number: "ORD-000001",
  status: "DRAFT" as const,
  client: {
    id: "44444444-4444-4444-8444-444444444444",
    type: "LEGAL_ENTITY" as const,
    displayName: "ООО Тест",
  },
  managerId: "55555555-5555-4555-8555-555555555555",
  managerDisplayName: "Менеджер",
  createdBy: "11111111-1111-4111-8111-111111111111",
  createdByDisplayName: "Администратор",
  warehouseId: "22222222-2222-4222-8222-222222222222",
  unitCount: 2,
  createdAt: "2026-07-19T08:00:00Z",
  updatedAt: "2026-07-19T09:00:00Z",
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <MemoryRouter initialEntries={["/orders"]}>
      <QueryClientProvider client={queryClient}>
        <OrdersListPage />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  ordersApi.listOrders.mockResolvedValue({
    content: [order],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("OrdersListPage", () => {
  it("keeps search, pagination and sorting in backend query params", async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText("ORD-000001")
    expect(ordersApi.listOrders).toHaveBeenCalledWith({
      accessToken: "orders-token",
      page: 0,
      size: 20,
      search: "",
      sort: "updatedAt",
      direction: "desc",
      statuses: [],
      clientTypes: [],
      warehouseIds: [],
      createdFrom: undefined,
      createdTo: undefined,
    })

    await user.type(screen.getByLabelText("Поиск заказов"), "Петров")
    await waitFor(() =>
      expect(ordersApi.listOrders).toHaveBeenLastCalledWith({
        accessToken: "orders-token",
        page: 0,
        size: 20,
        search: "Петров",
        sort: "updatedAt",
        direction: "desc",
        statuses: [],
        clientTypes: [],
        warehouseIds: [],
        createdFrom: undefined,
        createdTo: undefined,
      })
    )

    await user.click(screen.getByRole("button", { name: "Сортировать: Номер" }))
    await waitFor(() =>
      expect(ordersApi.listOrders).toHaveBeenLastCalledWith({
        accessToken: "orders-token",
        page: 0,
        size: 20,
        search: "Петров",
        sort: "number",
        direction: "asc",
        statuses: [],
        clientTypes: [],
        warehouseIds: [],
        createdFrom: undefined,
        createdTo: undefined,
      })
    )
  })
})
