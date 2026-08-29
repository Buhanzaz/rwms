import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"

Object.defineProperties(HTMLElement.prototype, {
  hasPointerCapture: { configurable: true, value: () => false },
  setPointerCapture: { configurable: true, value: () => undefined },
  scrollIntoView: { configurable: true, value: () => undefined },
})

const ordersApi = vi.hoisted(() => ({
  createOrderIdempotencyKey: vi.fn(
    () => "11111111-1111-4111-8111-111111111111"
  ),
  listOrderReplacementCandidates: vi.fn(),
  replaceOrderUnit: vi.fn(),
}))
const warehouseApi = vi.hoisted(() => ({
  listWarehouseSupportLinks: vi.fn(),
}))

vi.mock("@/features/orders/api/orders-api", () => ordersApi)
vi.mock("@/api/warehouse-api", () => warehouseApi)
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    warehouses: [
      { id: "55555555-5555-4555-8555-555555555555", name: "Регион" },
      { id: "66666666-6666-4666-8666-666666666666", name: "Опорный" },
    ],
  }),
}))
vi.mock("@/features/orders/orders-module-context", () => ({
  useOrdersModule: () => ({
    accessToken: "orders-token",
    currentUser: {
      id: "22222222-2222-4222-8222-222222222222",
      globalRole: "WAREHOUSE_MANAGER",
    },
    warehouses: [],
  }),
}))
vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

import { OrderUnitReplacementDialog } from "@/features/orders/components/order-unit-replacement-dialog"
import type {
  OrderDetail,
  OrderUnitCandidate,
} from "@/features/orders/domain/orders"

const ORDER_ID = "33333333-3333-4333-8333-333333333333"
const CLIENT_ID = "44444444-4444-4444-8444-444444444444"
const WAREHOUSE_ID = "55555555-5555-4555-8555-555555555555"
const OTHER_WAREHOUSE_ID = "66666666-6666-4666-8666-666666666666"
const OLD_ONE_ID = "77777777-7777-4777-8777-777777777777"
const OLD_TWO_ID = "88888888-8888-4888-8888-888888888888"
const GOOD_REPLACEMENT_ID = "99999999-9999-4999-8999-999999999999"

function unitCandidate({
  id,
  number,
  warehouseId = WAREHOUSE_ID,
  status = "FREE",
  added = false,
}: {
  id: string
  number: string
  warehouseId?: string
  status?: OrderUnitCandidate["unit"]["status"]
  added?: boolean
}): OrderUnitCandidate {
  return {
    reservationId: added ? "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" : null,
    added,
    reservationState: added ? "ACTIVE" : null,
    desiredContents: [],
    rentalTerm: null,
    unit: {
      id,
      version: 1,
      warehouseId,
      number,
      status,
      rentalType: "БК-1",
      dimensions: null,
      finishing: null,
      category: null,
      characteristics: null,
      linoleum: null,
      tags: [],
      contents: [],
      createdAt: "2026-08-10T08:00:00Z",
      updatedAt: "2026-08-10T09:00:00Z",
    },
  }
}

const oldOne = unitCandidate({
  id: OLD_ONE_ID,
  number: "СТАРАЯ-001",
  added: true,
})
const oldTwo = {
  ...unitCandidate({
    id: OLD_TWO_ID,
    number: "СТАРАЯ-002",
    added: true,
  }),
  reservationId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
} satisfies OrderUnitCandidate

const order: OrderDetail = {
  id: ORDER_ID,
  version: 7,
  number: "ORD-000042",
  status: "SAVED",
  client: {
    id: CLIENT_ID,
    version: 1,
    type: "LEGAL_ENTITY",
    displayName: "ООО Клиент",
    phone: "+79990000000",
    contactPerson: "Иван Иванов",
    email: null,
    responsibleManagerId: "22222222-2222-4222-8222-222222222222",
    responsibleManagerDisplayName: "Руководитель склада",
    comment: null,
    source: null,
    additionalContacts: [],
    createdAt: "2026-08-10T08:00:00Z",
    updatedAt: "2026-08-10T09:00:00Z",
  },
  managerId: "22222222-2222-4222-8222-222222222222",
  managerDisplayName: "Руководитель склада",
  createdBy: "22222222-2222-4222-8222-222222222222",
  createdByDisplayName: "Руководитель склада",
  warehouseId: WAREHOUSE_ID,
  deliveryAddress: "Санкт-Петербург, Невский, 1",
  latitude: 59.93,
  longitude: 30.33,
  contactPhone: "+79990000000",
  comment: null,
  additionalContacts: [],
  desiredDeliveryWindows: [
    {
      startDate: "2026-08-15",
      endDate: "2026-08-15",
    },
  ],
  unitCount: 2,
  units: [oldOne, oldTwo],
  movements: [],
  permissions: {
    canEdit: false,
    canReplaceUnits: true,
    canExtendRentalTerms: false,
    canViewOtherManagers: false,
  },
  createdAt: "2026-08-10T08:00:00Z",
  updatedAt: "2026-08-10T09:00:00Z",
}

const validReplacement = unitCandidate({
  id: GOOD_REPLACEMENT_ID,
  number: "ЗАМЕНА-100",
})

function candidatePage(content: OrderUnitCandidate[]) {
  return {
    content,
    page: 0,
    size: 50,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
  }
}

function renderDialog({
  onOpenChange = vi.fn<(open: boolean) => void>(),
  onReplaced = vi.fn<(projection: OrderDetail) => void>(),
  onConflict = vi.fn<() => void>(),
}: {
  onOpenChange?: (open: boolean) => void
  onReplaced?: (projection: OrderDetail) => void
  onConflict?: () => void
} = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <OrderUnitReplacementDialog
          open
          order={order}
          onOpenChange={onOpenChange}
          onReplaced={onReplaced}
          onConflict={onConflict}
        />
      </QueryClientProvider>
    </MemoryRouter>
  )
  return { onOpenChange, onReplaced, onConflict }
}

beforeEach(() => {
  warehouseApi.listWarehouseSupportLinks.mockResolvedValue({
    servedWarehouseId: WAREHOUSE_ID,
    warehouseVersion: 2,
    links: [
      {
        id: "12121212-1212-4121-8121-121212121212",
        version: 1,
        supportWarehouseId: OTHER_WAREHOUSE_ID,
        servedWarehouseId: WAREHOUSE_ID,
        active: true,
        priority: 1,
        allowDrivers: true,
        allowVehicles: true,
        allowInventory: true,
        allowDirectFulfillment: true,
        allowInterwarehouseTransfer: true,
        allowContractorFallback: false,
        allowedWeekdays: [],
        allowedDates: [],
        excludedDates: [],
        serviceStart: null,
        serviceEnd: null,
      },
    ],
  })
  ordersApi.listOrderReplacementCandidates.mockResolvedValue(
    candidatePage([validReplacement])
  )
  ordersApi.replaceOrderUnit.mockResolvedValue(order)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("OrderUnitReplacementDialog", () => {
  it("preserves one and two client-replacement targets in selection order", async () => {
    const user = userEvent.setup()
    renderDialog()

    await user.click(
      screen.getByRole("checkbox", { name: "Заменить бытовку СТАРАЯ-002" })
    )
    let link = screen.getByRole("link", {
      name: "Продолжить через клиента",
    })
    let url = new URL(link.getAttribute("href")!, "https://panel.example")
    expect(url.pathname).toBe("/booking")
    expect(url.searchParams.get("clientId")).toBe(CLIENT_ID)
    expect(url.searchParams.get("orderId")).toBe(ORDER_ID)
    expect(url.searchParams.get("mode")).toBe("REPLACEMENT")
    expect(url.searchParams.getAll("replacementUnitId")).toEqual([OLD_TWO_ID])

    await user.click(
      screen.getByRole("checkbox", { name: "Заменить бытовку СТАРАЯ-001" })
    )
    link = screen.getByRole("link", {
      name: "Продолжить через клиента",
    })
    url = new URL(link.getAttribute("href")!, "https://panel.example")
    expect(url.searchParams.getAll("replacementUnitId")).toEqual([
      OLD_TWO_ID,
      OLD_ONE_ID,
    ])
    expect(
      screen.getByRole("button", {
        name: "Выбрать замену самостоятельно",
      })
    ).toHaveProperty("disabled", true)
  })

  it("requires a reason and exposes only free same-warehouse non-order candidates", async () => {
    ordersApi.listOrderReplacementCandidates.mockResolvedValue(
      candidatePage([
        validReplacement,
        unitCandidate({
          id: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
          number: "ДРУГОЙ-СКЛАД",
          warehouseId: OTHER_WAREHOUSE_ID,
        }),
        unitCandidate({
          id: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
          number: "НЕ-СВОБОДНА",
          status: "BOOKED",
        }),
        unitCandidate({
          id: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
          number: "УЖЕ-ДОБАВЛЕНА",
          added: true,
        }),
        unitCandidate({
          id: OLD_ONE_ID,
          number: "ТЕКУЩАЯ-В-ОТВЕТЕ",
        }),
      ])
    )
    const callbacks = renderDialog()
    const user = userEvent.setup()

    await user.click(
      screen.getByRole("checkbox", { name: "Заменить бытовку СТАРАЯ-001" })
    )
    await user.click(
      screen.getByRole("button", {
        name: "Выбрать замену самостоятельно",
      })
    )
    await user.click(
      await screen.findByRole("button", {
        name: "Выбрать заменяющую бытовку ЗАМЕНА-100",
      })
    )

    expect(screen.queryByText("ДРУГОЙ-СКЛАД")).toBeNull()
    expect(screen.queryByText("НЕ-СВОБОДНА")).toBeNull()
    expect(screen.queryByText("УЖЕ-ДОБАВЛЕНА")).toBeNull()
    expect(screen.queryByText("ТЕКУЩАЯ-В-ОТВЕТЕ")).toBeNull()

    await user.click(screen.getByRole("button", { name: "Подтвердить замену" }))
    expect(
      await screen.findByText("Укажите причину самостоятельной замены.")
    ).toBeTruthy()
    expect(ordersApi.replaceOrderUnit).not.toHaveBeenCalled()

    await user.type(screen.getByLabelText("Причина замены"), "Протечка")
    await user.click(screen.getByRole("button", { name: "Подтвердить замену" }))

    await waitFor(() =>
      expect(ordersApi.replaceOrderUnit).toHaveBeenCalledWith({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 7,
        unitId: OLD_ONE_ID,
        replacementRentalItemId: GOOD_REPLACEMENT_ID,
        reason: "Протечка",
        inventorySourceWarehouseId: null,
        idempotencyKey: "11111111-1111-4111-8111-111111111111",
      })
    )
    expect(callbacks.onReplaced).toHaveBeenCalledWith(order)
    expect(callbacks.onOpenChange).toHaveBeenCalledWith(false)
  })

  it("switches to an allowed physical source and resets the stale candidate", async () => {
    const supportReplacement = unitCandidate({
      id: "13131313-1313-4131-8131-131313131313",
      number: "ОПОРНАЯ-200",
      warehouseId: OTHER_WAREHOUSE_ID,
    })
    ordersApi.listOrderReplacementCandidates.mockImplementation(
      ({
        inventorySourceWarehouseId,
      }: {
        inventorySourceWarehouseId: string | null
      }) =>
        Promise.resolve(
          candidatePage(
            inventorySourceWarehouseId
              ? [supportReplacement]
              : [validReplacement]
          )
        )
    )
    const user = userEvent.setup()
    renderDialog()

    await user.click(
      screen.getByRole("checkbox", { name: "Заменить бытовку СТАРАЯ-001" })
    )
    await user.click(
      screen.getByRole("button", { name: "Выбрать замену самостоятельно" })
    )
    await user.click(
      await screen.findByRole("button", {
        name: "Выбрать заменяющую бытовку ЗАМЕНА-100",
      })
    )

    const sourceSelect = screen.getByLabelText("Склад-источник бытовки")
    sourceSelect.focus()
    await user.keyboard("{Enter}{ArrowDown}{Enter}")

    expect(screen.queryByText("ЗАМЕНА-100")).toBeNull()
    await waitFor(() =>
      expect(ordersApi.listOrderReplacementCandidates).toHaveBeenLastCalledWith(
        expect.objectContaining({
          orderId: ORDER_ID,
          inventorySourceWarehouseId: OTHER_WAREHOUSE_ID,
          page: 0,
        })
      )
    )
    await user.click(
      await screen.findByRole("button", {
        name: "Выбрать заменяющую бытовку ОПОРНАЯ-200",
      })
    )
    await user.type(screen.getByLabelText("Причина замены"), "Другой источник")
    await user.click(screen.getByRole("button", { name: "Подтвердить замену" }))

    await waitFor(() =>
      expect(ordersApi.replaceOrderUnit).toHaveBeenCalledWith(
        expect.objectContaining({
          orderId: ORDER_ID,
          replacementRentalItemId: supportReplacement.unit.id,
          inventorySourceWarehouseId: OTHER_WAREHOUSE_ID,
        })
      )
    )
    expect(order.warehouseId).toBe(WAREHOUSE_ID)
  })

  it("refreshes after a conflict without clearing the user's replacement draft", async () => {
    ordersApi.replaceOrderUnit.mockRejectedValue(
      new ApiError("Конфликт замены", 409, "ORDER_UNIT_REPLACEMENT_CONFLICT")
    )
    const callbacks = renderDialog()
    const user = userEvent.setup()

    await user.click(
      screen.getByRole("checkbox", { name: "Заменить бытовку СТАРАЯ-001" })
    )
    await user.click(
      screen.getByRole("button", {
        name: "Выбрать замену самостоятельно",
      })
    )
    const replacement = await screen.findByRole("button", {
      name: "Выбрать заменяющую бытовку ЗАМЕНА-100",
    })
    await user.click(replacement)
    await user.type(screen.getByLabelText("Причина замены"), "Протечка")
    await user.click(screen.getByRole("button", { name: "Подтвердить замену" }))

    expect(
      await screen.findByText(
        /Состав заказа или доступность бытовок изменились/
      )
    ).toBeTruthy()
    expect(callbacks.onConflict).toHaveBeenCalledOnce()
    expect(callbacks.onReplaced).not.toHaveBeenCalled()
    expect(callbacks.onOpenChange).not.toHaveBeenCalledWith(false)
    expect(screen.getByLabelText("Причина замены")).toHaveProperty(
      "value",
      "Протечка"
    )
    expect(
      screen
        .getByRole("checkbox", {
          name: "Заменить бытовку СТАРАЯ-001",
        })
        .getAttribute("aria-checked")
    ).toBe("true")
    expect(replacement.getAttribute("aria-pressed")).toBe("true")
    await waitFor(() =>
      expect(
        ordersApi.listOrderReplacementCandidates.mock.calls.length
      ).toBeGreaterThan(1)
    )
    expect(ordersApi.createOrderIdempotencyKey).toHaveBeenCalledOnce()
  })
})
