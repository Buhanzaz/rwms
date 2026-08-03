import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, useLocation } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const ordersApi = vi.hoisted(() => ({
  listOrders: vi.fn(),
  createOrder: vi.fn(),
  listOrderClients: vi.fn(),
  createOrderIdempotencyKey: vi.fn(
    () => "99999999-9999-4999-8999-999999999999"
  ),
}))
const actorApi = vi.hoisted(() => ({
  listDossierActorDisplays: vi.fn(),
}))
const viewport = vi.hoisted(() => ({ isMobile: false }))

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
        name: "Москва",
        city: "Москва",
        address: null,
      },
    ],
  }),
}))

vi.mock("@/features/rental-items/dossier/actor/actor-display-api", () => ({
  listDossierActorDisplays: actorApi.listDossierActorDisplays,
}))
vi.mock("@/hooks/use-mobile", () => ({
  useIsMobile: () => viewport.isMobile,
}))

import { OrdersListPage } from "@/features/orders/pages/orders-list-page"

function LocationProbe() {
  const { pathname } = useLocation()

  return <output data-testid="location">{pathname}</output>
}

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
  managerDisplayName: "Менеджер из заказа",
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
        <LocationProbe />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  viewport.isMobile = false
  ordersApi.listOrders.mockResolvedValue({
    content: [order],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
  })
  actorApi.listDossierActorDisplays.mockResolvedValue([
    {
      subjectId: order.managerId,
      principalType: "USER",
      globalRole: "WMS_ADMIN",
      username: "manager",
      firstName: "Иван",
      lastName: "Петров",
      middleName: null,
      email: "manager@example.test",
    },
  ])
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("OrdersListPage", () => {
  it("keeps search, pagination and sorting in backend query params", async () => {
    const user = userEvent.setup()
    renderPage()

    expect(await screen.findAllByText("ORD-000001")).toHaveLength(2)
    expect(ordersApi.listOrders).toHaveBeenCalledWith({
      accessToken: "orders-token",
      page: 0,
      size: 100,
      search: "",
      sort: "updatedAt",
      direction: "desc",
      statuses: [],
      clientTypes: [],
      warehouseIds: [],
      createdFrom: undefined,
      createdTo: undefined,
    })

    await user.type(screen.getByLabelText("Поиск бронирований"), "Петров")
    await waitFor(() =>
      expect(ordersApi.listOrders).toHaveBeenLastCalledWith({
        accessToken: "orders-token",
        page: 0,
        size: 100,
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

    await user.click(screen.getByRole("button", { name: "Номер" }))
    await waitFor(() =>
      expect(ordersApi.listOrders).toHaveBeenLastCalledWith({
        accessToken: "orders-token",
        page: 0,
        size: 100,
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

  it("opens a booking from the whole row on double click", async () => {
    const user = userEvent.setup()
    renderPage()

    const row = await screen.findByRole("row", { name: /ORD-000001/ })
    expect(
      screen
        .getByRole("link", {
          name: "Открыть бронирование ORD-000001",
        })
        .getAttribute("href")
    ).toBe(`/orders/${order.id}`)

    await user.dblClick(row)

    expect(screen.getByTestId("location").textContent).toBe(
      `/orders/${order.id}`
    )
  })

  it("uses Russian warehouse labels and a person name for the manager", async () => {
    renderPage()

    expect(await screen.findAllByText("Петров Иван")).toHaveLength(2)
    expect(await screen.findAllByText("Мск")).toHaveLength(2)
  })

  it.each([
    {
      scenario: "the manager has no first or last name",
      actors: [
        {
          subjectId: order.managerId,
          principalType: "USER",
          globalRole: "WMS_ADMIN",
          username: "manager",
          firstName: " ",
          lastName: null,
          middleName: "Иванович",
          email: "manager@example.test",
        },
      ],
    },
    {
      scenario: "the manager actor is absent",
      actors: [],
    },
  ])("shows Not specified when $scenario", async ({ actors }) => {
    actorApi.listDossierActorDisplays.mockResolvedValue(actors)

    renderPage()

    expect(await screen.findAllByText("Не указано")).toHaveLength(2)
    expect(screen.queryByText(order.managerDisplayName)).toBeNull()
    expect(screen.queryByText("manager")).toBeNull()
    expect(screen.queryByText("manager@example.test")).toBeNull()
  })

  it("hides and restores booking filters without resetting them", async () => {
    const user = userEvent.setup()
    renderPage()

    const hideFilters = await screen.findByRole("button", {
      name: "Скрыть фильтры бронирований",
    })
    expect(hideFilters.getAttribute("aria-controls")).toBe("orders-filters")
    expect(hideFilters.getAttribute("aria-expanded")).toBe("true")

    await user.click(hideFilters)
    expect(document.getElementById("orders-filters")?.hidden).toBe(true)
    expect(
      screen
        .getByRole("button", { name: "Показать фильтры бронирований" })
        .getAttribute("aria-expanded")
    ).toBe("false")

    await user.click(
      screen.getByRole("button", { name: "Показать фильтры бронирований" })
    )
    expect(document.getElementById("orders-filters")?.hidden).toBe(false)
  })

  it("uses one booking filter row on desktop and keeps the booking action at the far right on mobile", async () => {
    renderPage()

    await screen.findAllByText("ORD-000001")
    const columns = document.querySelector(
      '[data-slot="orders-filter-columns"]'
    )
    const warehouse = document.querySelector(
      '[data-slot="orders-filter-warehouse"]'
    )
    const status = document.querySelector('[data-slot="orders-filter-status"]')
    const clientType = document.querySelector(
      '[data-slot="orders-filter-client-type"]'
    )
    const createdFrom = document.querySelector(
      '[data-slot="orders-filter-created-from"]'
    )
    const createdTo = document.querySelector(
      '[data-slot="orders-filter-created-to"]'
    )
    expect(columns?.className).toContain("sm:flex-row")
    expect(warehouse?.className).toContain("w-full")
    expect(warehouse?.className).toContain("sm:w-40")
    expect(status?.textContent).toContain("Статус")
    expect(clientType?.textContent).toContain("Тип клиента")
    expect(createdFrom?.contains(screen.getByLabelText("Создан с"))).toBe(true)
    expect(createdTo?.contains(screen.getByLabelText("Создан по"))).toBe(true)

    const filters = screen.getByRole("button", {
      name: "Скрыть фильтры бронирований",
    })
    const create = screen.getByRole("button", { name: "Новый заказ" })
    const actions = filters.parentElement

    expect(actions?.className).toContain("justify-between")
    expect(actions?.firstElementChild).toBe(filters)
    expect(actions?.lastElementChild).toBe(create)
  })

  it("keeps booking filters collapsed by default on mobile", async () => {
    viewport.isMobile = true
    renderPage()

    await screen.findAllByText("ORD-000001")

    const showFilters = screen.getByRole("button", {
      name: "Показать фильтры бронирований",
    })
    expect(showFilters.getAttribute("aria-expanded")).toBe("false")
    expect(document.getElementById("orders-filters")?.hidden).toBe(true)
  })

  it("renders tappable mobile booking blocks and no list footer", async () => {
    renderPage()

    const mobileBooking = await screen.findByRole("link", {
      name: "Открыть бронирование ORD-000001",
    })
    expect(
      mobileBooking.closest('[data-slot="orders-mobile-list"]')
    ).toBeTruthy()
    expect(screen.queryByText("Показано 1 из 1")).toBeNull()
    expect(screen.queryByRole("button", { name: "Назад" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Вперёд" })).toBeNull()
  })
})
