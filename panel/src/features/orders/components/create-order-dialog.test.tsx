import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const ordersApi = vi.hoisted(() => ({
  createOrder: vi.fn(),
  listOrderClients: vi.fn(),
  createOrderIdempotencyKey: vi.fn(
    () => "99999999-9999-4999-8999-999999999999"
  ),
}))
const clientsApi = vi.hoisted(() => ({ getClient: vi.fn() }))

vi.mock("@/features/orders/api/orders-api", () => ({
  ORDERS_QUERY_KEY: ["orders"],
  ...ordersApi,
}))
vi.mock("@/features/clients/api/clients-api", () => ({
  CLIENTS_QUERY_KEY: ["rental-clients"],
  getClient: clientsApi.getClient,
}))

vi.mock("@/features/orders/orders-module-context", () => ({
  useOrdersModule: () => ({
    accessToken: "orders-token",
    currentUser: {
      id: "11111111-1111-4111-8111-111111111111",
      displayName: "Мария Менеджер",
      globalRole: "RENTAL_MANAGER",
    },
    warehouses: [],
  }),
}))

import { CreateOrderDialog } from "@/features/orders/components/create-order-dialog"

const CLIENT_ID = "22222222-2222-4222-8222-222222222222"
const delivery = {
  deliveryAddress: "Москва, Складская, 1",
  latitude: 55.75,
  longitude: 37.62,
  contactPhone: "+7 999 123-45-67",
  comment: null,
  acceptableDeliveryDates: ["2026-08-15"],
}
const order = {
  id: "33333333-3333-4333-8333-333333333333",
  version: 0,
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
  warehouseId: null,
  unitCount: 0,
  createdAt: "2026-07-19T08:00:00Z",
  updatedAt: "2026-07-19T08:00:00Z",
  units: [],
  permissions: { canEdit: true, canViewOtherManagers: false },
}

function clientPage(content: unknown[]) {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length ? 1 : 0,
  }
}

function renderDialog(onCreated = vi.fn(), initialClientId?: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return {
    onCreated,
    ...render(
      <QueryClientProvider client={queryClient}>
        <CreateOrderDialog
          open
          initialClientId={initialClientId}
          onOpenChange={vi.fn()}
          onCreated={onCreated}
        />
      </QueryClientProvider>
    ),
  }
}

function fillDelivery() {
  fireEvent.change(screen.getByLabelText("Адрес доставки"), {
    target: { value: delivery.deliveryAddress },
  })
  fireEvent.change(screen.getByLabelText("Широта"), {
    target: { value: String(delivery.latitude) },
  })
  fireEvent.change(screen.getByLabelText("Долгота"), {
    target: { value: String(delivery.longitude) },
  })
  fireEvent.change(screen.getByLabelText("Контактный телефон заказа"), {
    target: { value: delivery.contactPhone },
  })
  fireEvent.change(screen.getByLabelText("Допустимая дата приёмки 1"), {
    target: { value: delivery.acceptableDeliveryDates[0] },
  })
}

beforeEach(() => {
  ordersApi.createOrder.mockResolvedValue(order)
  ordersApi.listOrderClients.mockResolvedValue(
    clientPage([
      {
        id: CLIENT_ID,
        type: "LEGAL_ENTITY",
        displayName: "ООО Петров",
        phone: delivery.contactPhone,
        contactPerson: "Пётр Петров",
        email: null,
      },
    ])
  )
  clientsApi.getClient.mockResolvedValue({
    id: CLIENT_ID,
    version: 1,
    type: "LEGAL_ENTITY",
    displayName: "ООО Петров",
    phone: delivery.contactPhone,
    contactPerson: "Пётр Петров",
    email: null,
    responsibleManagerId: "11111111-1111-4111-8111-111111111111",
    responsibleManagerDisplayName: "Менеджер",
    comment: null,
    source: null,
    createdAt: "2026-08-09T08:00:00Z",
    updatedAt: "2026-08-09T08:00:00Z",
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("CreateOrderDialog", () => {
  it("prefills the client and editable order phone from a client detail action", async () => {
    renderDialog(vi.fn(), CLIENT_ID)

    expect(await screen.findByDisplayValue("ООО Петров")).toBeTruthy()
    expect(
      (screen.getByLabelText("Контактный телефон заказа") as HTMLInputElement)
        .value
    ).toBe(delivery.contactPhone)
    expect(clientsApi.getClient).toHaveBeenCalledWith("orders-token", CLIENT_ID)
  })

  it("announces incomplete delivery metadata and focuses the first invalid field", async () => {
    renderDialog(vi.fn(), CLIENT_ID)
    await screen.findByDisplayValue("ООО Петров")

    const submit = screen.getByRole("button", {
      name: "Создать бронирование",
    })
    fireEvent.submit(submit.closest("form")!)

    expect(
      (
        await screen.findByText(
          "Проверьте обязательные поля доставки и приёмки."
        )
      ).getAttribute("role")
    ).toBe("alert")
    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByLabelText("Адрес доставки")
      )
    )
    expect(ordersApi.createOrder).not.toHaveBeenCalled()
  })

  it("offers an inline client-creation action while the backend search is running", async () => {
    let resolveSearch!: (page: ReturnType<typeof clientPage>) => void
    ordersApi.listOrderClients.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveSearch = resolve
        })
    )
    const user = userEvent.setup()
    renderDialog()

    const input = screen.getByPlaceholderText("Например, ООО Петров")
    await user.type(input, "Новый клиент")

    expect(
      await screen.findByText("Проверяем существующих клиентов…")
    ).toBeTruthy()
    const createClient = await screen.findByRole("button", {
      name: "Создать нового клиента «Новый клиент»",
    })
    expect((createClient as HTMLButtonElement).disabled).toBe(false)
    await act(async () => resolveSearch(clientPage([])))
    expect(await screen.findByText("Клиенты не найдены")).toBeTruthy()

    await user.click(createClient)
    expect(
      await screen.findByText(
        "Новый клиент «Новый клиент» будет создан в базе после успешного создания бронирования."
      )
    ).toBeTruthy()
    expect(ordersApi.createOrder).not.toHaveBeenCalled()
  })

  it("does not offer client creation when a normalized exact match exists", async () => {
    const user = userEvent.setup()
    renderDialog()

    await user.type(
      screen.getByPlaceholderText("Например, ООО Петров"),
      "  ооо   Петров "
    )

    expect(await screen.findByText("ООО Петров")).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Клиент уже найден" })
    ).toHaveProperty("disabled", true)
  })

  it("keeps client creation available when the backend search fails", async () => {
    ordersApi.listOrderClients.mockRejectedValue(new Error("Поиск недоступен"))
    const user = userEvent.setup()
    renderDialog()

    const input = screen.getByPlaceholderText("Например, ООО Петров")
    await user.click(input)
    fireEvent.change(input, {
      target: { value: "Новый клиент" },
    })

    expect(await screen.findByText("Поиск недоступен")).toBeTruthy()
    expect(
      screen.getByRole("button", {
        name: "Создать нового клиента «Новый клиент»",
      })
    ).toHaveProperty("disabled", false)

    await user.click(
      screen.getByRole("button", {
        name: "Создать нового клиента «Новый клиент»",
      })
    )
    expect(
      await screen.findByText(
        "Новый клиент «Новый клиент» будет создан в базе после успешного создания бронирования."
      )
    ).toBeTruthy()
    expect(ordersApi.createOrder).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText("Основной телефон"), {
      target: { value: "+7 999 123-45-67" },
    })
    fireEvent.change(screen.getByLabelText("Основное контактное лицо"), {
      target: { value: "Иван Иванов" },
    })
    fillDelivery()
    await user.click(
      screen.getByRole("button", { name: "Создать бронирование" })
    )

    await waitFor(() =>
      expect(ordersApi.createOrder).toHaveBeenCalledWith({
        accessToken: "orders-token",
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
        input: {
          newClient: {
            clientType: "LEGAL_ENTITY",
            displayName: "Новый клиент",
            phone: "+7 999 123-45-67",
            contactPerson: "Иван Иванов",
            email: null,
            comment: null,
            source: null,
          },
          ...delivery,
        },
      })
    )
  })

  it("links an explicitly selected existing client", async () => {
    const user = userEvent.setup()
    renderDialog()

    const input = screen.getByPlaceholderText("Например, ООО Петров")
    await user.type(input, "Петров")
    const existingClient = await screen.findByText("ООО Петров")
    await user.click(existingClient)
    fillDelivery()
    await user.click(
      screen.getByRole("button", { name: "Создать бронирование" })
    )

    await waitFor(() =>
      expect(ordersApi.createOrder).toHaveBeenCalledWith({
        accessToken: "orders-token",
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
        input: { clientId: CLIENT_ID, ...delivery },
      })
    )
  })

  it("creates a new client only after the explicit inline action", async () => {
    ordersApi.listOrderClients.mockResolvedValue(clientPage([]))
    const user = userEvent.setup()
    renderDialog()

    const input = screen.getByPlaceholderText("Например, ООО Петров")
    await user.type(input, "  ООО   Новый клиент  ")
    expect(ordersApi.createOrder).not.toHaveBeenCalled()

    await user.click(
      await screen.findByRole("button", {
        name: "Создать нового клиента «ООО Новый клиент»",
      })
    )
    expect(ordersApi.createOrder).not.toHaveBeenCalled()
    const manager = screen.getByLabelText(
      "Ответственный менеджер"
    ) as HTMLInputElement
    expect(manager.value).toBe("Мария Менеджер")
    expect(manager.readOnly).toBe(true)
    await user.type(
      screen.getByLabelText("Основной телефон"),
      "+7 999 765-43-21"
    )
    await user.type(
      screen.getByLabelText("Основное контактное лицо"),
      "Анна Петрова"
    )
    fillDelivery()
    await user.click(
      screen.getByRole("button", { name: "Создать бронирование" })
    )

    await waitFor(() =>
      expect(ordersApi.createOrder).toHaveBeenCalledWith({
        accessToken: "orders-token",
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
        input: {
          newClient: {
            clientType: "LEGAL_ENTITY",
            displayName: "ООО Новый клиент",
            phone: "+7 999 765-43-21",
            contactPerson: "Анна Петрова",
            email: null,
            comment: null,
            source: null,
          },
          ...delivery,
        },
      })
    )
  })

  it("reuses the logical-command idempotency key after an uncertain error", async () => {
    ordersApi.createOrder
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValueOnce(order)
    const user = userEvent.setup()
    renderDialog()

    await user.type(
      screen.getByPlaceholderText("Например, ООО Петров"),
      "Петров"
    )
    await user.click(await screen.findByText("ООО Петров"))
    fillDelivery()
    await user.click(
      screen.getByRole("button", { name: "Создать бронирование" })
    )
    await screen.findByText("Failed to fetch")
    await user.click(
      screen.getByRole("button", { name: "Создать бронирование" })
    )

    await waitFor(() => expect(ordersApi.createOrder).toHaveBeenCalledTimes(2))
    expect(ordersApi.createOrder.mock.calls[0][0].idempotencyKey).toBe(
      ordersApi.createOrder.mock.calls[1][0].idempotencyKey
    )
    expect(ordersApi.createOrderIdempotencyKey).toHaveBeenCalledTimes(1)
  })
})
