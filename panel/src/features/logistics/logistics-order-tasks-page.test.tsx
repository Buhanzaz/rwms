import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const shipmentApi = vi.hoisted(() => ({
  confirmShipmentPreparation: vi.fn(),
  createShipmentFurnitureTasks: vi.fn(),
  getShipmentFurnitureReadiness: vi.fn(),
  listShipments: vi.fn(),
  replaceShipmentPlan: vi.fn(),
}))
const returnApi = vi.hoisted(() => ({
  listReturns: vi.fn(),
  registerReturn: vi.fn(),
}))
const orderShipmentApi = vi.hoisted(() => ({
  createOrderShipment: vi.fn(),
}))
const shipmentTaskSettingsApi = vi.hoisted(() => ({
  getShipmentTaskSettings: vi.fn(),
}))
const ordersApi = vi.hoisted(() => ({
  getOrder: vi.fn(),
  listOrders: vi.fn(),
}))
const paymentApi = vi.hoisted(() => ({ getOrderPayment: vi.fn() }))
const cabinFurnitureApi = vi.hoisted(() => ({
  createCabinFurnitureTask: vi.fn(),
}))
const referenceApi = vi.hoisted(() => ({
  useLogisticsReferenceLabels: vi.fn(),
}))
const warehouseState = vi.hoisted(() => ({
  selectedWarehouse: {
    id: "11111111-1111-4111-8111-111111111111",
    name: "Склад обслуживания",
    timeZone: "Europe/Moscow",
  },
  warehouses: [
    {
      id: "11111111-1111-4111-8111-111111111111",
      name: "Склад обслуживания",
      timeZone: "Europe/Moscow",
    },
    {
      id: "99999999-9999-4999-8999-999999999998",
      name: "Опорный склад",
      timeZone: "Europe/Moscow",
    },
  ],
}))

vi.mock("@/features/logistics/shipments/api", () => ({
  SHIPMENTS_QUERY_KEY: ["logistics", "shipments"],
  confirmShipmentPreparation: shipmentApi.confirmShipmentPreparation,
  createShipmentFurnitureTasks: shipmentApi.createShipmentFurnitureTasks,
  getShipmentFurnitureReadiness: shipmentApi.getShipmentFurnitureReadiness,
  listShipments: shipmentApi.listShipments,
  replaceShipmentPlan: shipmentApi.replaceShipmentPlan,
}))
vi.mock("@/features/logistics/returns/api", () => ({
  RETURNS_QUERY_KEY: ["logistics", "returns"],
  listReturns: returnApi.listReturns,
  registerReturn: returnApi.registerReturn,
}))
vi.mock("@/features/logistics/order-tasks-api", () => ({
  createOrderShipment: orderShipmentApi.createOrderShipment,
}))
vi.mock("@/features/settings/logistics/api/shipment-task-settings-api", () => ({
  getShipmentTaskSettings: shipmentTaskSettingsApi.getShipmentTaskSettings,
  shipmentTaskSettingsKeys: {
    warehouse: (warehouseId: string) => [
      "logistics",
      "shipment-task-settings",
      warehouseId,
    ],
  },
}))
vi.mock("@/features/orders/api/orders-api", () => ({
  ORDERS_QUERY_KEY: ["orders"],
  getOrder: ordersApi.getOrder,
  listOrders: ordersApi.listOrders,
}))
vi.mock("@/features/orders/api/order-payments-api", () => paymentApi)
vi.mock("@/features/logistics/cabin-furniture-tasks-api", () => ({
  createCabinFurnitureTask: cabinFurnitureApi.createCabinFurnitureTask,
}))
vi.mock("@/features/logistics/use-logistics-reference-labels", () => ({
  logisticsAssetLabel: (
    labels: { assetNumbers: Map<string, string> },
    id: string
  ) => labels.assetNumbers.get(id) ?? "Бытовка недоступна",
  logisticsOrderLabel: (
    labels: { orderNumbers: Map<string, string> },
    id: string
  ) => labels.orderNumbers.get(id) ?? "Заказ недоступен",
  useLogisticsReferenceLabels: referenceApi.useLogisticsReferenceLabels,
}))
vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "task-token",
    currentUser: {
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      globalRole: "WAREHOUSE_MANAGER",
      warehouseAccessAll: false,
      warehouseAccesses: [
        {
          warehouseId: "11111111-1111-4111-8111-111111111111",
          level: "EDIT",
        },
        {
          warehouseId: "99999999-9999-4999-8999-999999999998",
          level: "EDIT",
        },
      ],
    },
  }),
}))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouseId: "11111111-1111-4111-8111-111111111111",
    selectedWarehouse: warehouseState.selectedWarehouse,
    warehouses: warehouseState.warehouses,
  }),
}))
vi.mock("@/features/logistics/logistics-driver-picker", () => ({
  LogisticsDriverPicker: ({
    id,
    value,
    warehouseId,
    onChange,
  }: {
    id: string
    value: { id: string; name: string } | null
    warehouseId: string
    onChange: (value: { id: string; name: string } | null) => void
  }) => (
    <label htmlFor={id}>
      Водитель
      <select
        id={id}
        aria-label="Водитель"
        data-warehouse-id={warehouseId}
        value={value?.id ?? ""}
        onChange={(event) =>
          onChange(
            event.target.value
              ? { id: event.target.value, name: "Иванов Иван" }
              : null
          )
        }
      >
        <option value="">Выберите водителя</option>
        <option value="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb">
          Иванов Иван
        </option>
      </select>
    </label>
  ),
}))

import { LogisticsOrderTasksPage } from "@/features/logistics/logistics-order-tasks-page"
import { orderPaymentFixture } from "@/features/orders/domain/order-payment.fixtures"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const SHIPMENT_ID = "22222222-2222-4222-8222-222222222222"
const LINE_ID = "33333333-3333-4333-8333-333333333333"
const ASSET_ID = "44444444-4444-4444-8444-444444444444"
const ORDER_ID = "55555555-5555-4555-8555-555555555555"
const RETURN_ID = "66666666-6666-4666-8666-666666666666"
const DRIVER_WORKER_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
const SECOND_ASSET_ID = "88888888-8888-4888-8888-888888888888"
const THIRD_ASSET_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"
const SUPPORT_WAREHOUSE_ID = "99999999-9999-4999-8999-999999999998"

function shipment() {
  return {
    id: SHIPMENT_ID,
    version: 4,
    documentType: "SHIPMENT" as const,
    customerDeliveryPurpose: "RENTAL_DELIVERY" as const,
    state: "DRAFT" as const,
    warehouseId: WAREHOUSE_ID,
    destinationWarehouseId: null,
    partySnapshot: "ООО Тест",
    driverSnapshot: null,
    driverWorkerId: null,
    clientId: "66666666-6666-4666-8666-666666666666",
    equipmentMovementTaskId: null,
    scheduledDate: null,
    rentalOrderId: ORDER_ID,
    lines: [
      {
        id: LINE_ID,
        version: 1,
        lineNumber: 1,
        assetId: ASSET_ID,
        assetVersion: 3,
        state: "PENDING" as const,
        tenantSnapshot: "Арендатор",
        rentalOrderId: ORDER_ID,
        inventorySourceWarehouseId: WAREHOUSE_ID,
      },
    ],
    createdAt: "2026-07-30T08:00:00Z",
    updatedAt: "2026-07-30T08:00:00Z",
  }
}

function currentUtcDate() {
  return new Date().toISOString().slice(0, 10)
}

function nextUtcDate() {
  const nextDay = new Date()
  nextDay.setUTCDate(nextDay.getUTCDate() + 1)
  return nextDay.toISOString().slice(0, 10)
}

function rentalReturn() {
  return {
    id: RETURN_ID,
    version: 1,
    documentType: "RETURN" as const,
    customerDeliveryPurpose: null,
    state: "DRAFT" as const,
    warehouseId: WAREHOUSE_ID,
    destinationWarehouseId: null,
    partySnapshot: "ООО Тест",
    driverSnapshot: null,
    driverWorkerId: null,
    clientId: "66666666-6666-4666-8666-666666666666",
    equipmentMovementTaskId: null,
    scheduledDate: null,
    rentalOrderId: ORDER_ID,
    rentalShipmentId: SHIPMENT_ID,
    lines: [
      {
        id: "77777777-7777-4777-8777-777777777777",
        version: 1,
        lineNumber: 1,
        assetId: ASSET_ID,
        assetVersion: 7,
        state: "PENDING" as const,
        tenantSnapshot: "Арендатор",
        rentalOrderId: ORDER_ID,
      },
    ],
    createdAt: "2026-07-30T08:00:00Z",
    updatedAt: "2026-07-30T08:00:00Z",
  }
}

function savedOrder() {
  return {
    id: ORDER_ID,
    version: 7,
    number: "ORD-000001",
    status: "SAVED" as const,
    customerDeliveryPurpose: "RENTAL_DELIVERY" as const,
    client: {
      id: "66666666-6666-4666-8666-666666666666",
      version: 3,
      type: "LEGAL_ENTITY" as const,
      displayName: "ООО Тест",
      phone: "+79990000002",
      contactPerson: "Анна Петрова",
      email: null,
      responsibleManagerId: "99999999-9999-4999-8999-999999999999",
      responsibleManagerDisplayName: "Менеджер",
      comment: null,
      source: null,
      additionalContacts: [
        { name: "Сергей Клиентский", phone: "+79990000003" },
      ],
      createdAt: "2026-07-01T08:00:00Z",
      updatedAt: "2026-07-30T08:00:00Z",
    },
    managerId: "99999999-9999-4999-8999-999999999999",
    managerDisplayName: "Менеджер",
    createdBy: "99999999-9999-4999-8999-999999999999",
    createdByDisplayName: "Менеджер",
    warehouseId: WAREHOUSE_ID,
    deliveryAddress: "Санкт-Петербург, Невский проспект, 1",
    latitude: 59.9343,
    longitude: 30.3351,
    contactPhone: "+79990000001",
    comment: "Позвонить за час",
    additionalContacts: [{ name: "Ольга По Заказу", phone: "+79990000004" }],
    desiredDeliveryWindows: [
      {
        startDate: currentUtcDate(),
        endDate: currentUtcDate(),
      },
    ],
    unitCount: 1,
    createdAt: "2026-07-30T08:00:00Z",
    updatedAt: "2026-07-30T08:00:00Z",
    permissions: {
      canEdit: true,
      canReplaceUnits: false,
      canViewOtherManagers: true,
    },
    units: [
      {
        reservationId: "88888888-8888-4888-8888-888888888888",
        added: true,
        unit: {
          id: ASSET_ID,
          version: 3,
          warehouseId: WAREHOUSE_ID,
          number: "БЫТ-001",
          status: "WAREHOUSE" as const,
          rentalType: "БК-1",
          dimensions: "2.4x6",
          finishing: "ДВП",
          category: "Новая",
          characteristics: null,
          linoleum: false,
          tags: [],
          contents: [],
          createdAt: "2026-07-01T08:00:00Z",
          updatedAt: "2026-07-01T08:00:00Z",
        },
        desiredContents: [],
        rentalTerm: { rentalMonths: 3, shipmentDate: null, returnDate: null },
      },
    ],
  }
}

function savedOrderWithThreeCabins() {
  const order = savedOrder()
  return {
    ...order,
    unitCount: 3,
    units: [
      ...order.units,
      {
        reservationId: "88888888-8888-4888-8888-888888888889",
        added: true,
        unit: {
          ...order.units[0].unit,
          id: SECOND_ASSET_ID,
          number: "БЫТ-002",
        },
        desiredContents: [],
        rentalTerm: {
          rentalMonths: 3,
          shipmentDate: null,
          returnDate: null,
        },
      },
      {
        reservationId: "99999999-9999-4999-8999-999999999998",
        added: true,
        unit: {
          ...order.units[0].unit,
          id: THIRD_ASSET_ID,
          number: "БЫТ-003",
        },
        desiredContents: [],
        rentalTerm: {
          rentalMonths: 3,
          shipmentDate: null,
          returnDate: null,
        },
      },
    ],
  }
}

function savedOrderWithMixedSources() {
  const order = savedOrderWithThreeCabins()
  return {
    ...order,
    unitCount: 2,
    units: [
      order.units[0],
      {
        ...order.units[1],
        unit: {
          ...order.units[1].unit,
          warehouseId: SUPPORT_WAREHOUSE_ID,
        },
      },
    ],
  }
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const rendered = render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <LogisticsOrderTasksPage />
      </QueryClientProvider>
    </MemoryRouter>
  )
  return { ...rendered, queryClient }
}

function useSavedOrderTask(order = savedOrder()) {
  shipmentApi.listShipments.mockResolvedValue([])
  ordersApi.listOrders.mockResolvedValue({
    content: [order],
    page: 0,
    size: 100,
    totalElements: 1,
    totalPages: 1,
  })
  ordersApi.getOrder.mockResolvedValue(order)
}

function useThreeCabinReferenceLabels() {
  referenceApi.useLogisticsReferenceLabels.mockReturnValue({
    assetNumbers: new Map([
      [ASSET_ID, "БЫТ-001"],
      [SECOND_ASSET_ID, "БЫТ-002"],
      [THIRD_ASSET_ID, "БЫТ-003"],
    ]),
    orderNumbers: new Map([[ORDER_ID, "ORD-000001"]]),
    assets: new Map(),
    orders: new Map(),
  })
}

beforeEach(() => {
  warehouseState.selectedWarehouse = {
    id: WAREHOUSE_ID,
    name: "Склад обслуживания",
    timeZone: "Europe/Moscow",
  }
  warehouseState.warehouses = [
    warehouseState.selectedWarehouse,
    {
      id: SUPPORT_WAREHOUSE_ID,
      name: "Опорный склад",
      timeZone: "Europe/Moscow",
    },
  ]
  const document = shipment()
  paymentApi.getOrderPayment.mockResolvedValue(
    orderPaymentFixture({
      orderId: ORDER_ID,
      orderVersion: 7,
      state: null,
      receipt: null,
      startedAt: null,
      expiresAt: null,
      canConfirm: false,
    })
  )
  shipmentApi.listShipments.mockResolvedValue([document])
  returnApi.listReturns.mockResolvedValue([])
  ordersApi.listOrders.mockResolvedValue({
    content: [],
    page: 0,
    size: 100,
    totalElements: 0,
    totalPages: 0,
  })
  shipmentApi.getShipmentFurnitureReadiness.mockResolvedValue({
    shipmentId: SHIPMENT_ID,
    shipmentVersion: 4,
    state: "READY",
    tasks: [],
  })
  shipmentApi.createShipmentFurnitureTasks.mockResolvedValue({
    shipmentId: SHIPMENT_ID,
    shipmentVersion: 5,
    tasks: [],
  })
  shipmentApi.replaceShipmentPlan.mockResolvedValue({
    ...document,
    version: 6,
    state: "PREPARING",
    driverSnapshot: "Иванов Иван",
    driverWorkerId: DRIVER_WORKER_ID,
    scheduledDate: "2026-08-01",
  })
  orderShipmentApi.createOrderShipment.mockResolvedValue({
    ...document,
    version: 5,
    driverSnapshot: "Иванов Иван",
    driverWorkerId: DRIVER_WORKER_ID,
    scheduledDate: "2026-08-01",
  })
  shipmentTaskSettingsApi.getShipmentTaskSettings.mockResolvedValue({
    warehouseId: WAREHOUSE_ID,
    version: 0,
    maxCabinsPerShipmentTask: 3,
    updatedBy: "00000000-0000-4000-8000-000000000010",
    updatedAt: "2026-08-10T10:00:00Z",
  })
  referenceApi.useLogisticsReferenceLabels.mockReturnValue({
    assetNumbers: new Map([[ASSET_ID, "БЫТ-001"]]),
    orderNumbers: new Map([[ORDER_ID, "ORD-000001"]]),
    assets: new Map([
      [
        ASSET_ID,
        {
          status: "available",
          asset: {
            id: ASSET_ID,
            number: "БЫТ-001",
            contentsItems: [
              {
                equipmentId: "77777777-7777-4777-8777-777777777777",
                equipmentName: "Стол",
                quantity: 1,
              },
            ],
            comment: "Проверить мебель",
          },
        },
      ],
    ]),
    orders: new Map([
      [
        ORDER_ID,
        {
          status: "available",
          order: savedOrder(),
        },
      ],
    ]),
  })
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe("LogisticsOrderTasksPage", () => {
  it("labels unpaid orders and blocks cabin selection and shipment creation", async () => {
    const user = userEvent.setup()
    useSavedOrderTask()
    paymentApi.getOrderPayment.mockResolvedValue(
      orderPaymentFixture({ orderId: ORDER_ID, orderVersion: 7 })
    )
    renderPage()
    expect(
      (await screen.findAllByText("Ожидает оплаты")).length
    ).toBeGreaterThan(0)
    await user.click(
      screen.getAllByRole("button", { name: "Показать бытовки" })[0]
    )
    for (const checkbox of screen.getAllByRole("checkbox", {
      name: "Выбрать бытовку БЫТ-001",
    })) {
      expect((checkbox as HTMLButtonElement).disabled).toBe(true)
    }
    expect(
      screen.queryByRole("button", { name: "Создать отгрузку" })
    ).toBeNull()
    expect(
      screen
        .getAllByRole("link", { name: "Чек и оплата" })[0]
        .getAttribute("href")
    ).toBe(`/orders/${ORDER_ID}`)
    expect(orderShipmentApi.createOrderShipment).not.toHaveBeenCalled()
  })

  it("admits cabins only after a refreshed server payment confirmation", async () => {
    const user = userEvent.setup()
    useSavedOrderTask()
    paymentApi.getOrderPayment.mockResolvedValue(
      orderPaymentFixture({ orderId: ORDER_ID, orderVersion: 7 })
    )
    const { queryClient } = renderPage()
    await user.click(
      (await screen.findAllByRole("button", { name: "Показать бытовки" }))[0]
    )
    expect(
      (
        screen.getAllByRole("checkbox", {
          name: "Выбрать бытовку БЫТ-001",
        })[0] as HTMLButtonElement
      ).disabled
    ).toBe(true)
    paymentApi.getOrderPayment.mockResolvedValue(
      orderPaymentFixture({
        orderId: ORDER_ID,
        orderVersion: 7,
        state: "CONFIRMED",
        canConfirm: false,
        source: "MANAGER_CONFIRMATION",
        resolvedAt: "2026-09-05T12:01:00Z",
      })
    )
    await act(async () => {
      await queryClient.invalidateQueries({
        queryKey: ["orders", "order-tasks-detail", ORDER_ID],
      })
    })
    await waitFor(() =>
      expect(
        (
          screen.getAllByRole("checkbox", {
            name: "Выбрать бытовку БЫТ-001",
          })[0] as HTMLButtonElement
        ).disabled
      ).toBe(false)
    )
    expect(orderShipmentApi.createOrderShipment).not.toHaveBeenCalled()
  })

  it("shows release in progress and removes an expired virtual task", async () => {
    useSavedOrderTask()
    paymentApi.getOrderPayment.mockResolvedValue(
      orderPaymentFixture({
        orderId: ORDER_ID,
        orderVersion: 7,
        state: "EXPIRING",
        canConfirm: false,
      })
    )
    const { queryClient } = renderPage()
    expect(
      (await screen.findAllByText("Освобождаем неоплаченную бронь")).length
    ).toBeGreaterThan(0)
    paymentApi.getOrderPayment.mockResolvedValue(
      orderPaymentFixture({
        orderId: ORDER_ID,
        orderVersion: 7,
        state: "EXPIRED",
        orderStatus: "CANCELLED",
        canConfirm: false,
      })
    )
    await act(async () => {
      await queryClient.invalidateQueries({
        queryKey: ["orders", "order-tasks-detail", ORDER_ID],
      })
    })
    await waitFor(() =>
      expect(
        screen.queryByRole("button", { name: "Показать бытовки" })
      ).toBeNull()
    )
  })

  it("shows payment read failures without treating missing data as legacy admission", async () => {
    const user = userEvent.setup()
    useSavedOrderTask()
    paymentApi.getOrderPayment.mockRejectedValue(
      new Error("Оплата временно недоступна")
    )
    renderPage()
    expect(await screen.findByText("Оплата временно недоступна")).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Показать бытовки" })
    ).toBeNull()
    paymentApi.getOrderPayment.mockResolvedValue(
      orderPaymentFixture({ orderId: ORDER_ID, orderVersion: 7 })
    )
    await user.click(
      screen.getByRole("button", { name: "Повторить загрузку оплаты заказов" })
    )
    expect(
      (await screen.findAllByText("Ожидает оплаты")).length
    ).toBeGreaterThan(0)
    expect(orderShipmentApi.createOrderShipment).not.toHaveBeenCalled()
  })

  it("does not combine stale order contents with a different payment version", async () => {
    useSavedOrderTask()
    paymentApi.getOrderPayment.mockResolvedValue(
      orderPaymentFixture({
        orderId: ORDER_ID,
        orderVersion: 8,
        state: "CONFIRMED",
      })
    )
    renderPage()
    expect(
      await screen.findByText(
        "Данные заказа и оплаты обновляются. Повторите загрузку заданий."
      )
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Показать бытовки" })
    ).toBeNull()
  })

  it("expands a task and shows selectable cabin composition", async () => {
    const user = userEvent.setup()
    useSavedOrderTask()
    renderPage()

    expect(await screen.findByText("ООО Тест")).toBeTruthy()
    await user.click(
      screen.getAllByRole("button", { name: "Показать бытовки" })[0]
    )

    const overview = screen.getAllByRole("region", {
      name: "Заказ и контакты клиента",
    })[0]
    expect(
      within(overview)
        .getByRole("link", { name: "Заказ ORD-000001" })
        .getAttribute("href")
    ).toBe(`/orders/${ORDER_ID}`)
    expect(
      within(overview).getByText("Санкт-Петербург, Невский проспект, 1")
    ).toBeTruthy()
    expect(within(overview).getByText("Анна Петрова")).toBeTruthy()
    expect(
      within(overview)
        .getByRole("link", { name: "+79990000001" })
        .getAttribute("href")
    ).toBe("tel:+79990000001")
    expect(
      within(overview).getByRole("link", { name: "+79990000002" })
    ).toBeTruthy()
    expect(within(overview).getByText("Сергей Клиентский")).toBeTruthy()
    expect(within(overview).getByText("Ольга По Заказу")).toBeTruthy()
    expect(within(overview).getByText("59.9343, 30.3351")).toBeTruthy()
    expect(within(overview).getByText("Позвонить за час")).toBeTruthy()

    expect(screen.getAllByText("Бытовка БЫТ-001").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Требуются действия").length).toBeGreaterThan(0)
    await user.click(screen.getAllByRole("button", { name: "Детали" })[0])
    expect(screen.getAllByText("Стол — 1 шт.").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Проверить мебель").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Склад обслуживания:").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Склад-источник:").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Склад обслуживания").length).toBeGreaterThan(0)

    const checkbox = screen.getAllByRole("checkbox", {
      name: "Выбрать бытовку БЫТ-001",
    })[0]
    await user.click(checkbox)
    expect(
      screen.getAllByRole("button", { name: "Создать отгрузку" }).length
    ).toBeGreaterThan(0)
    expect(
      screen
        .getAllByRole("region", {
          name: "Действия с выбранными бытовками",
        })
        .some((actions) => actions.closest("td") !== null)
    ).toBe(true)
  })

  it("creates one selected-cabin trip without automatically chaining furniture or planning commands", async () => {
    const user = userEvent.setup()
    useSavedOrderTask()
    renderPage()
    await user.click(
      (await screen.findAllByRole("button", { name: "Показать бытовки" }))[0]
    )
    await user.click(
      screen.getAllByRole("checkbox", { name: "Выбрать бытовку БЫТ-001" })[0]
    )
    await user.click(
      screen.getAllByRole("button", { name: "Создать отгрузку" }).at(-1)!
    )

    const dialog = await screen.findByRole("dialog", {
      name: "Создать отгрузку",
    })
    const selectedCabins = within(dialog).getByRole("region", {
      name: "Выбранные бытовки и наполнение",
    })
    expect(within(selectedCabins).getByText("Бытовка БЫТ-001")).toBeTruthy()
    expect(within(selectedCabins).getByText("Требуются действия")).toBeTruthy()
    expect(within(selectedCabins).getByText(/Стол — 1 шт\./)).toBeTruthy()
    await user.selectOptions(
      within(dialog).getByRole("combobox", { name: "Водитель" }),
      "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    )
    expect(
      within(dialog).queryByLabelText("Фактическое время ходки")
    ).toBeNull()
    expect(within(dialog).getByLabelText("Желаемые даты клиента")).toBeTruthy()
    expect(
      within(dialog).getByLabelText(
        "Фактическая дата ходки: календарь фактической ходки"
      )
    ).toBeTruthy()
    expect(
      within(dialog)
        .getByLabelText("Фактическая дата ходки: календарь фактической ходки")
        .querySelectorAll('[data-desired-window="true"]').length
    ).toBeGreaterThan(0)
    await user.click(
      within(dialog).getByRole("button", { name: "Создать отгрузку" })
    )

    await waitFor(() =>
      expect(orderShipmentApi.createOrderShipment).toHaveBeenCalledWith(
        expect.objectContaining({
          orderId: ORDER_ID,
          unitIds: [ASSET_ID],
          inventorySourceWarehouseId: null,
          driverSnapshot: "Иванов Иван",
          driverWorkerId: DRIVER_WORKER_ID,
        })
      )
    )
    expect(shipmentApi.createShipmentFurnitureTasks).not.toHaveBeenCalled()
    expect(shipmentApi.replaceShipmentPlan).not.toHaveBeenCalled()
  })

  it("uses one physical source for the driver directory and shipment command", async () => {
    const user = userEvent.setup()
    const order = savedOrder()
    order.units[0].unit.warehouseId = SUPPORT_WAREHOUSE_ID
    useSavedOrderTask(order)
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать бытовки" }))[0]
    )
    await user.click(
      screen.getAllByRole("checkbox", { name: "Выбрать бытовку БЫТ-001" })[0]
    )
    await user.click(
      screen.getAllByRole("button", { name: "Создать отгрузку" }).at(-1)!
    )

    const dialog = await screen.findByRole("dialog", {
      name: "Создать отгрузку",
    })
    expect(within(dialog).getByText("Склад обслуживания")).toBeTruthy()
    expect(within(dialog).getByText("Опорный склад")).toBeTruthy()
    const driverPicker = within(dialog).getByRole("combobox", {
      name: "Водитель",
    })
    expect(driverPicker.getAttribute("data-warehouse-id")).toBe(
      SUPPORT_WAREHOUSE_ID
    )
    await user.selectOptions(driverPicker, DRIVER_WORKER_ID)
    await user.click(
      within(dialog).getByRole("button", { name: "Создать отгрузку" })
    )

    await waitFor(() =>
      expect(orderShipmentApi.createOrderShipment).toHaveBeenCalledWith(
        expect.objectContaining({
          orderId: ORDER_ID,
          inventorySourceWarehouseId: SUPPORT_WAREHOUSE_ID,
          unitIds: [ASSET_ID],
        })
      )
    )
  })

  it("blocks a mixed-source shipment before opening its schedule", async () => {
    const user = userEvent.setup()
    useSavedOrderTask(savedOrderWithMixedSources())
    useThreeCabinReferenceLabels()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать бытовки" }))[0]
    )
    const first = screen.getAllByRole("checkbox", {
      name: "Выбрать бытовку БЫТ-001",
    })[0]
    const second = screen.getAllByRole("checkbox", {
      name: "Выбрать бытовку БЫТ-002",
    })[0]
    await user.click(first)
    await user.click(second)

    expect(
      await screen.findByText(
        "В одну отгрузку можно выбрать бытовки только с одного склада-источника. Создайте отдельную отгрузку для другого склада."
      )
    ).toBeTruthy()
    expect(second.getAttribute("aria-checked")).toBe("false")
    expect(orderShipmentApi.createOrderShipment).not.toHaveBeenCalled()
  })

  it("shows a saved order as an actionable task before its first shipment", async () => {
    const user = userEvent.setup()
    const order = savedOrder()
    shipmentApi.listShipments.mockResolvedValue([])
    ordersApi.listOrders.mockResolvedValue({
      content: [order],
      page: 0,
      size: 100,
      totalElements: 1,
      totalPages: 1,
    })
    ordersApi.getOrder.mockResolvedValue(order)
    renderPage()

    expect(await screen.findByText("ООО Тест")).toBeTruthy()
    await user.click(
      screen.getAllByRole("button", { name: "Показать бытовки" })[0]
    )
    expect(screen.getAllByText("Бытовка БЫТ-001").length).toBeGreaterThan(0)
    await user.click(
      screen.getAllByRole("checkbox", { name: "Выбрать бытовку БЫТ-001" })[0]
    )
    await user.click(
      screen.getAllByRole("button", { name: "Создать отгрузку" }).at(-1)!
    )

    const dialog = await screen.findByRole("dialog", {
      name: "Создать отгрузку",
    })
    await user.selectOptions(
      within(dialog).getByRole("combobox", { name: "Водитель" }),
      "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    )
    await user.click(
      within(dialog).getByRole("button", { name: "Создать отгрузку" })
    )
    await waitFor(() =>
      expect(orderShipmentApi.createOrderShipment).toHaveBeenCalledWith(
        expect.objectContaining({
          orderId: ORDER_ID,
          unitIds: [ASSET_ID],
          expectedVersion: 7,
        })
      )
    )
  })

  it("limits a saved-order shipment to the configured cabin cap and shows it in the dialog", async () => {
    const user = userEvent.setup()
    shipmentTaskSettingsApi.getShipmentTaskSettings.mockResolvedValue({
      warehouseId: WAREHOUSE_ID,
      version: 4,
      maxCabinsPerShipmentTask: 2,
      updatedBy: "admin-1",
      updatedAt: "2026-08-10T12:00:00Z",
    })
    useSavedOrderTask(savedOrderWithThreeCabins())
    useThreeCabinReferenceLabels()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать бытовки" }))[0]
    )
    const first = screen.getAllByRole("checkbox", {
      name: "Выбрать бытовку БЫТ-001",
    })[0]
    const second = screen.getAllByRole("checkbox", {
      name: "Выбрать бытовку БЫТ-002",
    })[0]
    const third = screen.getAllByRole("checkbox", {
      name: "Выбрать бытовку БЫТ-003",
    })[0]
    await waitFor(() =>
      expect((first as HTMLButtonElement).disabled).toBe(false)
    )

    await user.click(first)
    await user.click(second)

    expect((third as HTMLButtonElement).disabled).toBe(true)
    expect(
      screen.getAllByText("Лимит одного задания: 2.").length
    ).toBeGreaterThan(0)
    expect(screen.getAllByText(/Выбрано бытовок:/).length).toBeGreaterThan(0)

    await user.click(
      screen.getAllByRole("button", { name: "Создать отгрузку" }).at(-1)!
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Создать отгрузку",
    })
    expect(
      within(dialog).getByText(
        /Выбрано бытовок: 2 из 2\. Выбранные бытовки будут объединены/
      )
    ).toBeTruthy()
    const selectedCabins = within(dialog).getByRole("region", {
      name: "Выбранные бытовки и наполнение",
    })
    expect(within(selectedCabins).getByText("Бытовка БЫТ-001")).toBeTruthy()
    expect(within(selectedCabins).getByText("Бытовка БЫТ-002")).toBeTruthy()
    expect(within(selectedCabins).queryByText("Бытовка БЫТ-003")).toBeNull()
  })

  it("revalidates the cap when it changes while a shipment dialog is open", async () => {
    const user = userEvent.setup()
    shipmentTaskSettingsApi.getShipmentTaskSettings.mockResolvedValue({
      warehouseId: WAREHOUSE_ID,
      version: 4,
      maxCabinsPerShipmentTask: 3,
      updatedBy: "admin-1",
      updatedAt: "2026-08-10T12:00:00Z",
    })
    useSavedOrderTask(savedOrderWithThreeCabins())
    useThreeCabinReferenceLabels()
    const { queryClient } = renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать бытовки" }))[0]
    )
    const cabinNames = ["БЫТ-001", "БЫТ-002", "БЫТ-003"]
    for (const cabinNumber of cabinNames) {
      const checkbox = screen.getAllByRole("checkbox", {
        name: `Выбрать бытовку ${cabinNumber}`,
      })[0]
      await waitFor(() =>
        expect((checkbox as HTMLButtonElement).disabled).toBe(false)
      )
      await user.click(checkbox)
    }
    await user.click(
      screen.getAllByRole("button", { name: "Создать отгрузку" }).at(-1)!
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Создать отгрузку",
    })

    act(() => {
      queryClient.setQueryData(
        ["logistics", "shipment-task-settings", WAREHOUSE_ID],
        {
          warehouseId: WAREHOUSE_ID,
          version: 5,
          maxCabinsPerShipmentTask: 2,
          updatedBy: "admin-2",
          updatedAt: "2026-08-10T12:05:00Z",
        }
      )
    })
    await user.selectOptions(
      within(dialog).getByRole("combobox", { name: "Водитель" }),
      DRIVER_WORKER_ID
    )
    await user.click(
      within(dialog).getByRole("button", { name: "Создать отгрузку" })
    )

    expect(
      await screen.findByText(
        "В одном задании отгрузки можно выбрать не больше 2 бытовок."
      )
    ).toBeTruthy()
    expect(orderShipmentApi.createOrderShipment).not.toHaveBeenCalled()
  })

  it("blocks saved-order cabin selection when the shipment cap cannot be loaded", async () => {
    const user = userEvent.setup()
    shipmentTaskSettingsApi.getShipmentTaskSettings.mockRejectedValue(
      new Error("logistics-service недоступен")
    )
    useSavedOrderTask(savedOrder())
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать бытовки" }))[0]
    )
    expect(
      await screen.findByText(
        /Не удалось загрузить лимит бытовок в одном задании отгрузки: logistics-service недоступен/
      )
    ).toBeTruthy()
    const checkbox = screen.getAllByRole("checkbox", {
      name: "Выбрать бытовку БЫТ-001",
    })[0]
    expect((checkbox as HTMLButtonElement).disabled).toBe(true)
    expect(screen.queryByText("Лимит одного задания: 3.")).toBeNull()
  })

  it("creates furniture work only from the explicit action and never auto-plans the shipment", async () => {
    const user = userEvent.setup()
    shipmentApi.getShipmentFurnitureReadiness.mockResolvedValue({
      shipmentId: SHIPMENT_ID,
      shipmentVersion: 4,
      state: "REQUIRES_TASK_CREATION",
      tasks: [],
    })
    shipmentApi.createShipmentFurnitureTasks.mockResolvedValue({
      shipmentId: SHIPMENT_ID,
      shipmentVersion: 5,
      tasks: [
        {
          rentalItemId: ASSET_ID,
          unitNumber: "БЫТ-001",
          taskId: "77777777-7777-4777-8777-777777777777",
          lineCount: 1,
        },
      ],
    })
    renderPage()

    await user.click(
      (
        await screen.findAllByRole("button", {
          name: "Создать задачу на мебель",
        })
      )[0]
    )

    await waitFor(() =>
      expect(shipmentApi.createShipmentFurnitureTasks).toHaveBeenCalled()
    )
    expect(shipmentApi.replaceShipmentPlan).not.toHaveBeenCalled()
  })

  it("shows return details and schedules one cabin from the highlighted client window", async () => {
    const user = userEvent.setup()
    const outbound = {
      ...shipment(),
      state: "SHIPPED" as const,
      driverSnapshot: "Отгрузил Иван",
      scheduledDate: "2026-07-30",
    }
    shipmentApi.listShipments.mockResolvedValue([outbound])
    returnApi.listReturns.mockResolvedValue([rentalReturn()])
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать бытовки" }))[0]
    )
    await user.click(screen.getAllByRole("button", { name: "Детали" })[0])

    expect(screen.getAllByText("Отгрузил Иван").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Привёз:").length).toBeGreaterThan(0)
    expect(
      screen.getAllByRole("button", { name: "Возврат" }).length
    ).toBeGreaterThan(0)

    await user.click(screen.getAllByRole("button", { name: "Возврат" }).at(-1)!)
    const dialog = await screen.findByRole("dialog", {
      name: "Возврат из аренды",
    })
    const selectedCabins = within(dialog).getByRole("region", {
      name: "Выбранные бытовки и наполнение",
    })
    expect(within(selectedCabins).getByText("Бытовка БЫТ-001")).toBeTruthy()
    expect(within(selectedCabins).getByText("Требуются действия")).toBeTruthy()
    await user.selectOptions(
      within(dialog).getByRole("combobox", { name: "Водитель" }),
      "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    )
    const calendar = within(dialog).getByLabelText(
      "Фактическая дата ходки: календарь фактической ходки"
    )
    const desiredDay = calendar.querySelector<HTMLButtonElement>(
      '[data-desired-window="true"]'
    )
    expect(desiredDay).toBeTruthy()
    await user.click(desiredDay!)
    expect(
      within(dialog).queryByLabelText("Фактическое время ходки")
    ).toBeNull()
    await user.click(
      within(dialog).getByRole("button", { name: "Создать возврат" })
    )

    await waitFor(() =>
      expect(returnApi.registerReturn).toHaveBeenCalledWith(
        expect.objectContaining({
          documentId: RETURN_ID,
          scheduledDate: currentUtcDate(),
          driverSnapshot: "Иванов Иван",
          driverWorkerId: DRIVER_WORKER_ID,
        })
      )
    )
  })

  it("marks a return due by the warehouse-local date instead of UTC", async () => {
    vi.setSystemTime("2026-08-31T20:30:00Z")
    warehouseState.selectedWarehouse = {
      ...warehouseState.selectedWarehouse,
      timeZone: "Asia/Novosibirsk",
    }
    warehouseState.warehouses = [
      warehouseState.selectedWarehouse,
      {
        id: SUPPORT_WAREHOUSE_ID,
        name: "Опорный склад",
        timeZone: "Europe/Moscow",
      },
    ]
    shipmentApi.listShipments.mockResolvedValue([
      {
        ...shipment(),
        state: "SHIPPED" as const,
        scheduledDate: "2026-08-30",
      },
    ])
    const order = savedOrder()
    referenceApi.useLogisticsReferenceLabels.mockReturnValue({
      assetNumbers: new Map([[ASSET_ID, "БЫТ-001"]]),
      orderNumbers: new Map([[ORDER_ID, "ORD-000001"]]),
      assets: new Map(),
      orders: new Map([
        [
          ORDER_ID,
          {
            status: "available",
            order: {
              ...order,
              units: order.units.map((unit) => ({
                ...unit,
                rentalTerm: {
                  rentalMonths: 3,
                  shipmentDate: "2026-06-01",
                  returnDate: "2026-09-01",
                },
              })),
            },
          },
        ],
      ]),
    })

    renderPage()

    expect(
      (await screen.findAllByText("Требует возврата")).length
    ).toBeGreaterThan(0)
  })

  it("asks whether to keep or change a non-today shipment date", async () => {
    const user = userEvent.setup()
    const awaiting = {
      ...shipment(),
      state: "AWAITING_CONFIRMATION" as const,
      driverSnapshot: "Иванов Иван",
      scheduledDate: nextUtcDate(),
    }
    shipmentApi.listShipments.mockResolvedValue([awaiting])
    shipmentApi.getShipmentFurnitureReadiness.mockResolvedValue({
      shipmentId: SHIPMENT_ID,
      shipmentVersion: awaiting.version,
      state: "READY",
      tasks: [],
    })
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать бытовки" }))[0]
    )
    await user.click(
      screen.getAllByRole("button", { name: "Готово к отгрузке" }).at(-1)!
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Дата отгрузки отличается",
    })
    await user.click(
      within(dialog).getByRole("button", { name: "Оставить назначенную" })
    )

    await waitFor(() =>
      expect(shipmentApi.confirmShipmentPreparation).toHaveBeenCalledWith(
        expect.objectContaining({
          documentId: SHIPMENT_ID,
          expectedVersion: awaiting.version,
          keepScheduledDate: true,
        })
      )
    )
    expect(shipmentApi.replaceShipmentPlan).not.toHaveBeenCalled()
  })

  it("does not chain a schedule change with shipment confirmation", async () => {
    const user = userEvent.setup()
    const awaiting = {
      ...shipment(),
      state: "AWAITING_CONFIRMATION" as const,
      driverSnapshot: "Иванов Иван",
      scheduledDate: nextUtcDate(),
    }
    shipmentApi.listShipments.mockResolvedValue([awaiting])
    shipmentApi.getShipmentFurnitureReadiness.mockResolvedValue({
      shipmentId: SHIPMENT_ID,
      shipmentVersion: awaiting.version,
      state: "READY",
      tasks: [],
    })
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать бытовки" }))[0]
    )
    await user.click(
      screen.getAllByRole("button", { name: "Готово к отгрузке" }).at(-1)!
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Дата отгрузки отличается",
    })
    expect(
      within(dialog).queryByRole("button", { name: "Изменить на сегодня" })
    ).toBeNull()
    expect(within(dialog).getByText(/отдельными командами/i)).toBeTruthy()
    expect(shipmentApi.replaceShipmentPlan).not.toHaveBeenCalled()
    expect(shipmentApi.confirmShipmentPreparation).not.toHaveBeenCalled()
  })

  it("confirms a shipment scheduled for today without the date decision dialog", async () => {
    const user = userEvent.setup()
    const awaiting = {
      ...shipment(),
      state: "AWAITING_CONFIRMATION" as const,
      driverSnapshot: "Иванов Иван",
      scheduledDate: currentUtcDate(),
    }
    shipmentApi.listShipments.mockResolvedValue([awaiting])
    shipmentApi.getShipmentFurnitureReadiness.mockResolvedValue({
      shipmentId: SHIPMENT_ID,
      shipmentVersion: awaiting.version,
      state: "READY",
      tasks: [],
    })
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать бытовки" }))[0]
    )
    await user.click(
      screen.getAllByRole("button", { name: "Готово к отгрузке" }).at(-1)!
    )

    await waitFor(() =>
      expect(shipmentApi.confirmShipmentPreparation).toHaveBeenCalledWith(
        expect.objectContaining({
          documentId: SHIPMENT_ID,
          expectedVersion: awaiting.version,
        })
      )
    )
    expect(
      screen.queryByRole("dialog", { name: "Дата отгрузки отличается" })
    ).toBeNull()
    expect(shipmentApi.replaceShipmentPlan).not.toHaveBeenCalled()
  })
})
