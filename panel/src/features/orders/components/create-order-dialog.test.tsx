import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const ordersApi = vi.hoisted(() => ({
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
      globalRole: "RENTAL_MANAGER",
    },
    warehouses: [],
  }),
}))

import { CreateOrderDialog } from "@/features/orders/components/create-order-dialog"

const CLIENT_ID = "22222222-2222-4222-8222-222222222222"
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

function renderDialog(onCreated = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return {
    onCreated,
    ...render(
      <QueryClientProvider client={queryClient}>
        <CreateOrderDialog open onOpenChange={vi.fn()} onCreated={onCreated} />
      </QueryClientProvider>
    ),
  }
}

beforeEach(() => {
  ordersApi.createOrder.mockResolvedValue(order)
  ordersApi.listOrderClients.mockResolvedValue(
    clientPage([
      {
        id: CLIENT_ID,
        type: "LEGAL_ENTITY",
        displayName: "ООО Петров",
      },
    ])
  )
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("CreateOrderDialog", () => {
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

    await user.type(
      screen.getByPlaceholderText("Например, ООО Петров"),
      "Новый клиент"
    )

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
    await user.type(
      screen.getByPlaceholderText("+7 999 000-00-00"),
      "+7 999 123-45-67"
    )
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
            email: null,
          },
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
    await user.click(
      screen.getByRole("button", { name: "Создать бронирование" })
    )

    await waitFor(() =>
      expect(ordersApi.createOrder).toHaveBeenCalledWith({
        accessToken: "orders-token",
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
        input: { clientId: CLIENT_ID },
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
    await user.type(
      screen.getByPlaceholderText("+7 999 000-00-00"),
      "+7 999 765-43-21"
    )
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
            email: null,
          },
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
