import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import type { ReactNode } from "react"
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const clientsApi = vi.hoisted(() => ({
  getClient: vi.fn(),
  listClients: vi.fn(),
}))
const ordersApi = vi.hoisted(() => ({
  listClientOrders: vi.fn(),
  createOrderIdempotencyKey: vi.fn(
    () => "55555555-5555-4555-8555-555555555555"
  ),
}))
vi.mock("@/features/clients/api/clients-api", () => ({
  CLIENTS_QUERY_KEY: ["rental-clients"],
  ...clientsApi,
}))
vi.mock("@/features/orders/api/orders-api", () => ({
  ORDERS_QUERY_KEY: ["orders"],
  ...ordersApi,
}))
vi.mock("@/features/orders/orders-module-context", () => ({
  useOrdersModule: () => ({
    accessToken: "token",
    currentUser: {
      id: "22222222-2222-4222-8222-222222222222",
      displayName: "Мария Менеджер",
      globalRole: "RENTAL_MANAGER",
    },
    warehouses: [],
  }),
}))

import { ClientDetailPage } from "@/features/clients/pages/client-detail-page"
import { ClientsListPage } from "@/features/clients/pages/clients-list-page"

const CLIENT_ID = "11111111-1111-4111-8111-111111111111"
const ORDER_ID = "33333333-3333-4333-8333-333333333333"
const client = {
  id: CLIENT_ID,
  version: 1,
  type: "LEGAL_ENTITY" as const,
  displayName: "ООО Петров",
  phone: null,
  contactPerson: "Пётр Петров",
  email: null,
  responsibleManagerId: "22222222-2222-4222-8222-222222222222",
  responsibleManagerDisplayName: null,
  comment: "Постоянный клиент",
  source: "Рекомендация",
  createdAt: "2026-08-09T08:00:00Z",
  updatedAt: "2026-08-09T09:00:00Z",
}
const order = {
  id: ORDER_ID,
  version: 2,
  number: "ORD-000042",
  status: "SAVED" as const,
  client,
  managerId: client.responsibleManagerId,
  managerDisplayName: "Мария Менеджер",
  createdBy: client.responsibleManagerId,
  createdByDisplayName: "Мария Менеджер",
  warehouseId: "44444444-4444-4444-8444-444444444444",
  deliveryAddress: "Москва, Складская, 1",
  latitude: 55.75,
  longitude: 37.62,
  contactPhone: "+79990000000",
  comment: null,
  acceptableDeliveryDates: ["2026-08-15"],
  unitCount: 2,
  createdAt: "2026-08-09T08:30:00Z",
  updatedAt: "2026-08-09T09:30:00Z",
}

function LocationProbe() {
  const location = useLocation()
  return (
    <output aria-label="Текущий маршрут">
      {location.pathname + location.search}
    </output>
  )
}

function renderRoute(initialEntry: string, route: string, element: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route path={route} element={element} />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  clientsApi.getClient.mockResolvedValue(client)
  clientsApi.listClients.mockResolvedValue({
    content: [client],
    page: 0,
    size: 50,
    totalElements: 1,
    totalPages: 1,
  })
  ordersApi.listClientOrders.mockResolvedValue({
    content: [order],
    page: 0,
    size: 30,
    totalElements: 1,
    totalPages: 1,
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("client pages", () => {
  it("opens a paginated grid row by double-click and exposes keyboard navigation", async () => {
    renderRoute("/clients", "/clients", <ClientsListPage />)

    const row = await screen.findByLabelText(
      "Клиент ООО Петров. Двойное нажатие открывает карточку."
    )
    expect(row.getAttribute("tabindex")).toBe("0")
    expect(
      screen.getByRole("link", { name: "ООО Петров" }).getAttribute("href")
    ).toBe(`/clients/${CLIENT_ID}`)
    expect(screen.getAllByText("Не указан").length).toBeGreaterThan(0)
    fireEvent.doubleClick(row)

    expect(
      (await screen.findByLabelText("Текущий маршрут")).textContent
    ).toContain(`/clients/${CLIENT_ID}`)
  })

  it("shows client orders and prefilled chat, warehouse and manual-order actions", async () => {
    renderRoute(
      `/clients/${CLIENT_ID}`,
      "/clients/:clientId",
      <ClientDetailPage />
    )

    expect(await screen.findByText("ORD-000042")).toBeTruthy()
    expect(
      screen.getByRole("link", { name: "ORD-000042" }).getAttribute("href")
    ).toBe(`/orders/${ORDER_ID}`)
    expect(screen.getByText("Не указан")).toBeTruthy()
    expect(screen.getByText(client.responsibleManagerId)).toBeTruthy()
    expect(screen.getByText("Москва, Складская, 1")).toBeTruthy()
    expect(
      screen
        .getByRole("link", { name: /Новый заказ через чат/ })
        .getAttribute("href")
    ).toBe(`/assistant?clientId=${CLIENT_ID}`)
    expect(
      screen
        .getByRole("link", { name: /Подобрать по складу/ })
        .getAttribute("href")
    ).toBe(`/booking?clientId=${CLIENT_ID}`)
    expect(
      screen.getByRole("link", { name: /Создать заказ/ }).getAttribute("href")
    ).toBe(`/orders/new?clientId=${CLIENT_ID}`)

    const orderRow = screen.getByLabelText("Открыть заказ ORD-000042")
    fireEvent.keyDown(orderRow, { key: "Enter" })
    expect(
      (await screen.findByLabelText("Текущий маршрут")).textContent
    ).toContain(`/orders/${ORDER_ID}`)
  })
})
