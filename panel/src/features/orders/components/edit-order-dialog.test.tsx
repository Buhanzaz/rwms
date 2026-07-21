import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"

const ordersApi = vi.hoisted(() => ({
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
    warehouses: [],
  }),
}))

import { EditOrderDialog } from "@/features/orders/components/edit-order-dialog"
import type {
  OrderClientSearchItem,
  OrderDetail,
} from "@/features/orders/domain/orders"

const ORDER_ID = "33333333-3333-4333-8333-333333333333"
const CLIENT_ID = "44444444-4444-4444-8444-444444444444"
const REPLACEMENT_CLIENT_ID = "55555555-5555-4555-8555-555555555555"

const order: OrderDetail = {
  id: ORDER_ID,
  version: 4,
  number: "ORD-000001",
  status: "DRAFT",
  client: {
    id: CLIENT_ID,
    type: "LEGAL_ENTITY",
    displayName: "ООО Тест",
  },
  managerId: "11111111-1111-4111-8111-111111111111",
  managerDisplayName: "Менеджер",
  createdBy: "11111111-1111-4111-8111-111111111111",
  createdByDisplayName: "Менеджер",
  warehouseId: null,
  unitCount: 0,
  createdAt: "2026-07-19T08:00:00Z",
  updatedAt: "2026-07-19T09:00:00Z",
  units: [],
  permissions: { canEdit: true, canViewOtherManagers: false },
}

const replacementClient: OrderClientSearchItem = {
  id: REPLACEMENT_CLIENT_ID,
  type: "LEGAL_ENTITY",
  displayName: "ООО Новый клиент",
}
const updatedOrder: OrderDetail = {
  ...order,
  version: 5,
  client: replacementClient,
  updatedAt: "2026-07-19T10:00:00Z",
}

function clientPage(content: OrderClientSearchItem[]) {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length ? 1 : 0,
  }
}

function renderDialog({
  onOpenChange = vi.fn<(open: boolean) => void>(),
  onUpdated = vi.fn<(updatedOrder: OrderDetail) => void>(),
  onConflict = vi.fn<() => void>(),
}: {
  onOpenChange?: (open: boolean) => void
  onUpdated?: (updatedOrder: OrderDetail) => void
  onConflict?: () => void
} = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <EditOrderDialog
        open
        order={order}
        onOpenChange={onOpenChange}
        onUpdated={onUpdated}
        onConflict={onConflict}
      />
    </QueryClientProvider>
  )
  return { onOpenChange, onUpdated, onConflict }
}

async function selectReplacementClient(
  user: ReturnType<typeof userEvent.setup>
) {
  const input = screen.getByPlaceholderText("Например, ООО Петров")
  await user.clear(input)
  await user.type(input, "Новый")
  await user.click(await screen.findByText(replacementClient.displayName))
}

beforeEach(() => {
  ordersApi.listOrderClients.mockResolvedValue(clientPage([replacementClient]))
  ordersApi.updateOrder.mockResolvedValue(updatedOrder)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("EditOrderDialog", () => {
  it("changes the order client through an existing-client search only", async () => {
    const callbacks = renderDialog()
    const user = userEvent.setup()

    expect(
      screen.getByText(
        "Выберите другого существующего клиента для изменения заказа."
      )
    ).toBeTruthy()
    expect(screen.queryByText(/Создать нового клиента/i)).toBeNull()

    await selectReplacementClient(user)
    await user.click(
      screen.getByRole("button", { name: "Сохранить изменения" })
    )

    await waitFor(() =>
      expect(ordersApi.updateOrder).toHaveBeenCalledWith({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 4,
        clientId: REPLACEMENT_CLIENT_ID,
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
      })
    )
    expect(callbacks.onUpdated).toHaveBeenCalledWith(updatedOrder)
    expect(callbacks.onOpenChange).toHaveBeenCalledWith(false)
    expect(toast.success).toHaveBeenCalledWith("Клиент заказа изменён.")
  })

  it("keeps the dialog open and refreshes the order boundary on a stale write", async () => {
    ordersApi.updateOrder.mockRejectedValue(
      new ApiError("Версия заказа устарела", 409, "ORDER_VERSION_CONFLICT")
    )
    const callbacks = renderDialog()
    const user = userEvent.setup()

    await selectReplacementClient(user)
    await user.click(
      screen.getByRole("button", { name: "Сохранить изменения" })
    )

    expect(
      await screen.findByText(
        "Данные заказа изменились. Актуальные значения загружены с сервера."
      )
    ).toBeTruthy()
    expect(callbacks.onConflict).toHaveBeenCalledOnce()
    expect(callbacks.onOpenChange).not.toHaveBeenCalledWith(false)

    await user.click(
      screen.getByRole("button", { name: "Сохранить изменения" })
    )
    await waitFor(() => expect(ordersApi.updateOrder).toHaveBeenCalledTimes(2))
    expect(ordersApi.updateOrder.mock.calls[0][0].idempotencyKey).toBe(
      ordersApi.updateOrder.mock.calls[1][0].idempotencyKey
    )
  })
})
