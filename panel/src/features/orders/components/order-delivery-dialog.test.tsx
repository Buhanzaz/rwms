import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"

const ordersApi = vi.hoisted(() => ({
  updateOrder: vi.fn(),
  createOrderIdempotencyKey: vi.fn(
    () => "99999999-9999-4999-8999-999999999999"
  ),
}))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))

vi.mock("sonner", () => ({ toast }))
vi.mock("@/features/orders/api/orders-api", () => ordersApi)
vi.mock("@/features/orders/orders-module-context", () => ({
  useOrdersModule: () => ({ accessToken: "orders-token" }),
}))

import { OrderDeliveryDialog } from "@/features/orders/components/order-delivery-dialog"
import type { OrderDetail } from "@/features/orders/domain/orders"

const ORDER_ID = "33333333-3333-4333-8333-333333333333"
const CLIENT_ID = "44444444-4444-4444-8444-444444444444"

const order: OrderDetail = {
  id: ORDER_ID,
  version: 4,
  number: "ORD-000001",
  status: "DRAFT",
  customerDeliveryPurpose: "RENTAL_DELIVERY",
  client: {
    id: CLIENT_ID,
    version: 1,
    type: "LEGAL_ENTITY",
    displayName: "ООО Тест",
    phone: "+79990000000",
    contactPerson: "Иван Иванов",
    email: null,
    responsibleManagerId: "11111111-1111-4111-8111-111111111111",
    responsibleManagerDisplayName: "Менеджер",
    comment: null,
    source: null,
    additionalContacts: [],
    createdAt: "2026-07-19T08:00:00Z",
    updatedAt: "2026-07-19T08:00:00Z",
  },
  managerId: "11111111-1111-4111-8111-111111111111",
  managerDisplayName: "Менеджер",
  createdBy: "11111111-1111-4111-8111-111111111111",
  createdByDisplayName: "Менеджер",
  warehouseId: null,
  deliveryAddress: "Москва, Складская, 1",
  latitude: 55.75,
  longitude: 37.62,
  contactPhone: "+79990000000",
  comment: "Позвонить за час",
  additionalContacts: [{ name: "Прораб", phone: "+79990000001" }],
  desiredDeliveryWindows: [
    {
      startDate: "2026-08-15",
      endDate: "2026-08-15",
    },
  ],
  unitCount: 0,
  createdAt: "2026-07-19T08:00:00Z",
  updatedAt: "2026-07-19T09:00:00Z",
  units: [],
  movements: [],
  permissions: {
    canEdit: true,
    canReplaceUnits: false,
    canExtendRentalTerms: false,
    canViewOtherManagers: false,
  },
}

const updatedOrder: OrderDetail = {
  ...order,
  version: 5,
  comment: "Новый комментарий",
  updatedAt: "2026-07-19T10:00:00Z",
}

function renderDialog({
  order: renderedOrder = order,
  onOpenChange = vi.fn<(open: boolean) => void>(),
  onUpdated = vi.fn<(updatedOrder: OrderDetail) => void>(),
  onConflict = vi.fn<() => void>(),
}: {
  order?: OrderDetail
  onOpenChange?: (open: boolean) => void
  onUpdated?: (updatedOrder: OrderDetail) => void
  onConflict?: () => void
} = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <OrderDeliveryDialog
        open
        order={renderedOrder}
        onOpenChange={onOpenChange}
        onUpdated={onUpdated}
        onConflict={onConflict}
      />
    </QueryClientProvider>
  )
  return { onOpenChange, onUpdated, onConflict }
}

beforeEach(() => {
  ordersApi.updateOrder.mockResolvedValue(updatedOrder)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("OrderDeliveryDialog", () => {
  it("names a missing manager contact action as adding order data", () => {
    renderDialog({
      order: {
        ...order,
        contactPhone: null,
        desiredDeliveryWindows: [],
      },
    })

    expect(
      screen.getByRole("heading", { name: "Добавить данные заказа" })
    ).toBeTruthy()
    expect(
      screen.getByText(
        "Контактный телефон и комментарий сохраняются в заказе. Адрес, координаты и дополнительные контакты клиент укажет в представлении."
      )
    ).toBeTruthy()
    expect(screen.queryByLabelText("Адрес доставки")).toBeNull()
    expect(screen.queryByLabelText("Широта")).toBeNull()
    expect(screen.queryByLabelText("Долгота")).toBeNull()
  })

  it("names a complete manager contact action as changing order data", () => {
    renderDialog()

    expect(
      screen.getByRole("heading", { name: "Изменить данные заказа" })
    ).toBeTruthy()
  })

  it("keeps the existing client while saving changed manager fields", async () => {
    const callbacks = renderDialog()
    const user = userEvent.setup()

    const comment = screen.getByLabelText("Комментарий к заказу")
    await user.clear(comment)
    await user.type(comment, "Новый комментарий")
    await user.click(
      screen.getByRole("button", { name: "Сохранить данные заказа" })
    )

    await waitFor(() =>
      expect(ordersApi.updateOrder).toHaveBeenCalledWith({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 4,
        clientId: CLIENT_ID,
        delivery: {
          contactPhone: "+79990000000",
          comment: "Новый комментарий",
        },
        idempotencyKey: expect.any(String),
      })
    )
    expect(callbacks.onUpdated).toHaveBeenCalledWith(updatedOrder)
    expect(callbacks.onOpenChange).toHaveBeenCalledWith(false)
    expect(toast.success).toHaveBeenCalledWith("Данные заказа сохранены.")
    const updateInput = ordersApi.updateOrder.mock.calls[0]?.[0].delivery
    expect(updateInput).not.toHaveProperty("deliveryAddress")
    expect(updateInput).not.toHaveProperty("latitude")
    expect(updateInput).not.toHaveProperty("longitude")
    expect(updateInput).not.toHaveProperty("additionalContacts")
  }, 15_000)

  it("validates a missing manager contact before sending an update", async () => {
    renderDialog()
    const user = userEvent.setup()

    await user.clear(screen.getByLabelText("Контактный телефон заказа"))
    await user.click(
      screen.getByRole("button", { name: "Сохранить данные заказа" })
    )

    expect(
      await screen.findByText("Укажите контактный телефон заказа.")
    ).toBeTruthy()
    expect(
      screen.getByText("Проверьте контактный телефон заказа.")
    ).toBeTruthy()
    expect(ordersApi.updateOrder).not.toHaveBeenCalled()
  }, 15_000)

  it("keeps the dialog open and refreshes the order boundary after a stale update", async () => {
    ordersApi.updateOrder.mockRejectedValue(
      new ApiError("Версия заказа устарела", 409, "ORDER_VERSION_CONFLICT")
    )
    const callbacks = renderDialog()
    const user = userEvent.setup()

    const comment = screen.getByLabelText("Комментарий к заказу")
    await user.clear(comment)
    await user.type(comment, "Обновлённый комментарий")
    await user.click(
      screen.getByRole("button", { name: "Сохранить данные заказа" })
    )

    expect(
      await screen.findByText(
        "Данные заказа изменились. Актуальные значения загружены с сервера."
      )
    ).toBeTruthy()
    expect(callbacks.onConflict).toHaveBeenCalledOnce()
    expect(callbacks.onOpenChange).not.toHaveBeenCalledWith(false)

    await user.click(
      screen.getByRole("button", { name: "Сохранить данные заказа" })
    )
    await waitFor(() => expect(ordersApi.updateOrder).toHaveBeenCalledTimes(2))
    expect(ordersApi.updateOrder.mock.calls[0][0].idempotencyKey).toBe(
      ordersApi.updateOrder.mock.calls[1][0].idempotencyKey
    )
  }, 15_000)
})
