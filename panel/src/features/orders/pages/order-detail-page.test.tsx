import {
  focusManager,
  QueryClient,
  QueryClientProvider,
} from "@tanstack/react-query"
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"

const ordersApi = vi.hoisted(() => ({
  getOrder: vi.fn(),
  listAvailableOrderUnits: vi.fn(),
  listOrderHistory: vi.fn(),
  addOrderUnit: vi.fn(),
  removeOrderUnit: vi.fn(),
  selectOrderWarehouse: vi.fn(),
  deleteOrder: vi.fn(),
  listOrderClients: vi.fn(),
  updateOrder: vi.fn(),
  createOrderIdempotencyKey: vi.fn(
    () => "99999999-9999-4999-8999-999999999999"
  ),
}))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))

vi.mock("sonner", () => ({ toast }))
vi.mock("@/features/orders/api/orders-api", () => ({
  ORDERS_QUERY_KEY: ["orders"],
  ...ordersApi,
}))
vi.mock("@/features/orders/orders-module-context", () => ({
  useOrdersModule: () => ({
    accessToken: "orders-token",
    currentUser: {
      id: "11111111-1111-4111-8111-111111111111",
      globalRole: "RENTAL_MANAGER",
    },
    warehouses: [
      {
        id: "22222222-2222-4222-8222-222222222222",
        code: "MSK",
        name: "Москва",
        city: "Москва",
        address: "Складская, 1",
      },
    ],
  }),
}))
vi.mock("@/features/orders/components/order-warehouse-unit-selection", () => ({
  OrderWarehouseUnitSelection: ({
    candidates,
    conflictingUnitIds,
    onAdd,
    onEditContents,
  }: {
    candidates: Array<{
      added: boolean
      unit: { id: string; number: string }
    }>
    conflictingUnitIds: ReadonlySet<string>
    onAdd: (candidate: {
      added: boolean
      unit: { id: string; number: string }
    }) => void
    onEditContents: (candidate: {
      added: boolean
      unit: { id: string; number: string }
    }) => void
  }) => {
    const candidate = candidates[0]!
    const conflicting = conflictingUnitIds.has(candidate.unit.id)
    if (candidate.added) {
      return (
        <>
          <button type="button" disabled>
            Добавлено
          </button>
          <button
            type="button"
            aria-label={`Изменить наполнение ${candidate.unit.number}`}
            onClick={() => onEditContents(candidate)}
          >
            +
          </button>
        </>
      )
    }

    return (
      <button
        type="button"
        disabled={conflicting}
        onClick={() => onAdd(candidate)}
      >
        {conflicting ? "Уже занята" : "Добавить"}
      </button>
    )
  },
}))
vi.mock("@/features/orders/components/order-unit-contents", () => ({
  OrderUnitContentsView: () => null,
  OrderUnitEquipmentDialog: () => null,
}))

import { OrderDetailPage } from "@/features/orders/pages/order-detail-page"

const ORDER_ID = "33333333-3333-4333-8333-333333333333"
const CLIENT_ID = "44444444-4444-4444-8444-444444444444"
const UNIT_ID = "55555555-5555-4555-8555-555555555555"
const WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"

const detail = {
  id: ORDER_ID,
  version: 4,
  number: "ORD-000001",
  status: "DRAFT" as const,
  client: {
    id: CLIENT_ID,
    type: "LEGAL_ENTITY" as const,
    displayName: "ООО Тест",
  },
  managerId: "11111111-1111-4111-8111-111111111111",
  managerDisplayName: "Менеджер",
  createdBy: "11111111-1111-4111-8111-111111111111",
  createdByDisplayName: "Менеджер",
  warehouseId: WAREHOUSE_ID,
  unitCount: 0,
  createdAt: "2026-07-19T08:00:00Z",
  updatedAt: "2026-07-19T09:00:00Z",
  units: [],
  permissions: { canEdit: true, canViewOtherManagers: false },
}

const candidate = {
  reservationId: null,
  added: false,
  unit: {
    id: UNIT_ID,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    number: "БЫТ-001",
    status: "FREE" as const,
    rentalType: null,
    dimensions: null,
    finishing: null,
    category: null,
    characteristics: null,
    linoleum: null,
    tags: [],
    contents: [],
    createdAt: "2026-07-19T08:00:00Z",
    updatedAt: "2026-07-19T08:00:00Z",
  },
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return {
    queryClient,
    ...render(
      <MemoryRouter initialEntries={[`/orders/${ORDER_ID}`]}>
        <QueryClientProvider client={queryClient}>
          <Routes>
            <Route path="/orders/:orderId" element={<OrderDetailPage />} />
          </Routes>
        </QueryClientProvider>
      </MemoryRouter>
    ),
  }
}

beforeEach(() => {
  ordersApi.getOrder.mockResolvedValue(detail)
  ordersApi.listAvailableOrderUnits.mockResolvedValue({
    content: [candidate],
    page: 0,
    size: 40,
    totalElements: 1,
    totalPages: 1,
  })
  ordersApi.listOrderHistory.mockResolvedValue([])
  ordersApi.addOrderUnit.mockRejectedValue(
    new ApiError("Бытовка уже зарезервирована", 409, "UNIT_ALREADY_RESERVED")
  )
  ordersApi.listOrderClients.mockResolvedValue({
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
  })
  ordersApi.updateOrder.mockResolvedValue(detail)
  ordersApi.deleteOrder.mockResolvedValue({
    ...detail,
    version: 5,
    status: "CANCELLED",
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("OrderDetailPage reservation conflict", () => {
  it("removes a newly occupied candidate on visible polling and keeps this order unit", async () => {
    const selectedCandidate = {
      ...candidate,
      reservationId: "66666666-6666-4666-8666-666666666666",
      added: true,
      unit: {
        ...candidate.unit,
        id: "77777777-7777-4777-8777-777777777777",
        number: "БЫТ-002",
      },
    }
    ordersApi.getOrder.mockResolvedValue({
      ...detail,
      unitCount: 1,
      units: [selectedCandidate],
    })
    ordersApi.listAvailableOrderUnits
      .mockResolvedValueOnce({
        content: [candidate, selectedCandidate],
        page: 0,
        size: 40,
        totalElements: 2,
        totalPages: 1,
      })
      .mockResolvedValue({
        content: [selectedCandidate],
        page: 0,
        size: 40,
        totalElements: 1,
        totalPages: 1,
      })

    renderPage()

    expect(await screen.findByRole("button", { name: "Добавить" })).toBeTruthy()
    await waitFor(
      () =>
        expect(screen.queryByRole("button", { name: "Добавить" })).toBeNull(),
      { timeout: 3_500 }
    )
    expect(screen.getByRole("button", { name: "Добавлено" })).toBeTruthy()
    expect(screen.getByText("БЫТ-002")).toBeTruthy()
    expect(
      ordersApi.listAvailableOrderUnits.mock.calls.length
    ).toBeGreaterThanOrEqual(2)
  })

  it("does not keep availability polling active in a hidden page", async () => {
    focusManager.setFocused(false)
    const { unmount } = renderPage()

    try {
      await waitFor(() =>
        expect(ordersApi.listAvailableOrderUnits).toHaveBeenCalledTimes(1)
      )
      await new Promise((resolve) => window.setTimeout(resolve, 2_200))
      expect(ordersApi.listAvailableOrderUnits).toHaveBeenCalledTimes(1)
    } finally {
      unmount()
      focusManager.setFocused(undefined)
    }
  })

  it("shows the confirmed reservation immediately from the command projection", async () => {
    const user = userEvent.setup()
    const addedCandidate = {
      ...candidate,
      reservationId: "66666666-6666-4666-8666-666666666666",
      added: true,
    }
    ordersApi.addOrderUnit.mockResolvedValueOnce({
      ...detail,
      version: 5,
      unitCount: 1,
      units: [addedCandidate],
    })

    renderPage()
    await user.click(await screen.findByRole("button", { name: "Добавить" }))

    expect(
      await screen.findByRole("button", { name: "Добавлено" })
    ).toBeTruthy()
    expect(
      screen.getAllByRole("button", {
        name: "Изменить наполнение БЫТ-001",
      }).length
    ).toBeGreaterThan(0)
    expect(screen.queryByRole("button", { name: "Добавить" })).toBeNull()
  })

  it("rolls back the action state, explains the conflict and refreshes projections", async () => {
    ordersApi.listAvailableOrderUnits
      .mockResolvedValueOnce({
        content: [candidate],
        page: 0,
        size: 40,
        totalElements: 1,
        totalPages: 1,
      })
      .mockResolvedValue({
        content: [],
        page: 0,
        size: 40,
        totalElements: 0,
        totalPages: 0,
      })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole("button", { name: "Добавить" }))

    await waitFor(() => expect(ordersApi.addOrderUnit).toHaveBeenCalledTimes(1))
    expect(toast.error).toHaveBeenCalledWith(
      "Бытовка уже занята другим заказом. Список доступных бытовок обновлён."
    )
    await waitFor(() => {
      expect(ordersApi.getOrder.mock.calls.length).toBeGreaterThanOrEqual(2)
      expect(
        ordersApi.listAvailableOrderUnits.mock.calls.length
      ).toBeGreaterThanOrEqual(2)
      expect(
        ordersApi.listOrderHistory.mock.calls.length
      ).toBeGreaterThanOrEqual(2)
    })
    expect(screen.queryByRole("button", { name: "Добавить" })).toBeNull()
  })

  it("allows a later server-confirmed free unit after an earlier conflict", async () => {
    const freePage = {
      content: [candidate],
      page: 0,
      size: 40,
      totalElements: 1,
      totalPages: 1,
    }
    ordersApi.listAvailableOrderUnits
      .mockResolvedValueOnce(freePage)
      .mockResolvedValueOnce({
        content: [],
        page: 0,
        size: 40,
        totalElements: 0,
        totalPages: 0,
      })
      .mockResolvedValue(freePage)
    const user = userEvent.setup()
    const { queryClient } = renderPage()

    await user.click(await screen.findByRole("button", { name: "Добавить" }))
    await waitFor(() =>
      expect(ordersApi.listAvailableOrderUnits).toHaveBeenCalledTimes(2)
    )
    expect(screen.queryByRole("button", { name: "Добавить" })).toBeNull()

    await act(async () => {
      await queryClient.invalidateQueries({
        queryKey: ["orders", "available-units"],
      })
    })

    expect(await screen.findByRole("button", { name: "Добавить" })).toBeTruthy()
  })
})

describe("OrderDetailPage draft actions", () => {
  it("updates the detail projection after selecting an existing client", async () => {
    const replacementClient = {
      id: "66666666-6666-4666-8666-666666666666",
      type: "LEGAL_ENTITY" as const,
      displayName: "ООО Новый клиент",
    }
    ordersApi.listOrderClients.mockResolvedValue({
      content: [replacementClient],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    })
    ordersApi.updateOrder.mockResolvedValue({
      ...detail,
      version: 5,
      client: replacementClient,
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Редактировать заказ" })
    )
    const input = screen.getByPlaceholderText("Например, ООО Петров")
    await user.clear(input)
    await user.type(input, "Новый")
    await user.click(await screen.findByText(replacementClient.displayName))
    await user.click(
      screen.getByRole("button", { name: "Сохранить изменения" })
    )

    await waitFor(() =>
      expect(ordersApi.updateOrder).toHaveBeenCalledWith({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 4,
        clientId: replacementClient.id,
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
      })
    )
    expect(await screen.findByText(replacementClient.displayName)).toBeTruthy()
    expect(toast.success).toHaveBeenCalledWith("Клиент заказа изменён.")
  })

  it("labels draft deletion as a logical cancellation and releases reservations", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Удалить черновик" })
    )

    const confirmation = await screen.findByRole("alertdialog")
    expect(
      within(confirmation).getByText(
        "Это логическое удаление: заказ будет отменён, а все активные резервирования бытовок будут освобождены. Действие фиксируется в истории."
      )
    ).toBeTruthy()
    expect(
      within(confirmation).getByRole("button", { name: "Не удалять" })
    ).toBeTruthy()

    await user.click(
      within(confirmation).getByRole("button", { name: "Удалить черновик" })
    )

    await waitFor(() =>
      expect(ordersApi.deleteOrder).toHaveBeenCalledWith({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 4,
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
      })
    )
    expect(toast.success).toHaveBeenCalledWith(
      "Черновик удалён: заказ логически отменён, резервирования освобождены."
    )
  })
})
