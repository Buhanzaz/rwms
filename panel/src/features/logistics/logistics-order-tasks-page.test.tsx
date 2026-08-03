import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
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
const ordersApi = vi.hoisted(() => ({
  getOrder: vi.fn(),
  listOrders: vi.fn(),
}))
const cabinFurnitureApi = vi.hoisted(() => ({
  createCabinFurnitureTask: vi.fn(),
}))
const referenceApi = vi.hoisted(() => ({
  useLogisticsReferenceLabels: vi.fn(),
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
vi.mock("@/features/orders/api/orders-api", () => ({
  ORDERS_QUERY_KEY: ["orders"],
  getOrder: ordersApi.getOrder,
  listOrders: ordersApi.listOrders,
}))
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
      ],
    },
  }),
}))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouseId: "11111111-1111-4111-8111-111111111111",
  }),
}))
vi.mock("@/features/logistics/logistics-driver-picker", () => ({
  LogisticsDriverPicker: ({
    id,
    value,
    onChange,
  }: {
    id: string
    value: { id: string; name: string } | null
    onChange: (value: { id: string; name: string } | null) => void
  }) => (
    <label htmlFor={id}>
      Водитель
      <select
        id={id}
        aria-label="Водитель"
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

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const SHIPMENT_ID = "22222222-2222-4222-8222-222222222222"
const LINE_ID = "33333333-3333-4333-8333-333333333333"
const ASSET_ID = "44444444-4444-4444-8444-444444444444"
const ORDER_ID = "55555555-5555-4555-8555-555555555555"
const RETURN_ID = "66666666-6666-4666-8666-666666666666"

function shipment() {
  return {
    id: SHIPMENT_ID,
    version: 4,
    documentType: "SHIPMENT" as const,
    state: "DRAFT" as const,
    warehouseId: WAREHOUSE_ID,
    destinationWarehouseId: null,
    partySnapshot: "ООО Тест",
    driverSnapshot: null,
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
    state: "DRAFT" as const,
    warehouseId: WAREHOUSE_ID,
    destinationWarehouseId: null,
    partySnapshot: "ООО Тест",
    driverSnapshot: null,
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
    client: {
      id: "66666666-6666-4666-8666-666666666666",
      type: "LEGAL_ENTITY" as const,
      displayName: "ООО Тест",
      phone: null,
      email: null,
    },
    managerId: "99999999-9999-4999-8999-999999999999",
    managerDisplayName: "Менеджер",
    createdBy: "99999999-9999-4999-8999-999999999999",
    createdByDisplayName: "Менеджер",
    warehouseId: WAREHOUSE_ID,
    unitCount: 1,
    createdAt: "2026-07-30T08:00:00Z",
    updatedAt: "2026-07-30T08:00:00Z",
    permissions: { canEdit: true, canViewOtherManagers: true },
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

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <LogisticsOrderTasksPage />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

function useSavedOrderTask() {
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
}

beforeEach(() => {
  const document = shipment()
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
    scheduledDate: "2026-08-01",
  })
  orderShipmentApi.createOrderShipment.mockResolvedValue({
    ...document,
    version: 5,
    driverSnapshot: "Иванов Иван",
    scheduledDate: "2026-08-01",
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
          order: {
            id: ORDER_ID,
            version: 7,
            number: "ORD-000001",
            units: [
              {
                reservationId: "88888888-8888-4888-8888-888888888888",
                added: true,
                unit: { id: ASSET_ID },
                desiredContents: [],
                rentalTerm: {
                  rentalMonths: 3,
                  shipmentDate: null,
                  returnDate: null,
                },
              },
            ],
          },
        },
      ],
    ]),
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("LogisticsOrderTasksPage", () => {
  it("expands a task and shows selectable cabin composition", async () => {
    const user = userEvent.setup()
    useSavedOrderTask()
    renderPage()

    expect(await screen.findByText("ООО Тест")).toBeTruthy()
    await user.click(
      screen.getAllByRole("button", { name: "Показать бытовки" })[0]
    )

    expect(screen.getAllByText("Бытовка БЫТ-001").length).toBeGreaterThan(0)
    await user.click(screen.getAllByRole("button", { name: "Детали" })[0])
    expect(screen.getAllByText("Стол").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Проверить мебель").length).toBeGreaterThan(0)

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

  it("creates a selected shipment, schedules preparation and sends furniture tasks", async () => {
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
          driverSnapshot: "Иванов Иван",
        })
      )
    )
    await waitFor(() =>
      expect(shipmentApi.createShipmentFurnitureTasks).toHaveBeenCalled()
    )
    await waitFor(() =>
      expect(shipmentApi.replaceShipmentPlan).toHaveBeenCalledWith(
        expect.objectContaining({
          documentId: SHIPMENT_ID,
          driverSnapshot: "Иванов Иван",
        })
      )
    )
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

  it("waits for a furniture worker task before starting shipment preparation", async () => {
    const user = userEvent.setup()
    useSavedOrderTask()
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
    shipmentApi.getShipmentFurnitureReadiness.mockResolvedValue({
      shipmentId: SHIPMENT_ID,
      shipmentVersion: 5,
      state: "AWAITING_TASK_COMPLETION",
      tasks: [
        {
          rentalItemId: ASSET_ID,
          unitNumber: "БЫТ-001",
          taskId: "77777777-7777-4777-8777-777777777777",
          externalTaskId: "88888888-8888-4888-8888-888888888888",
          taskBoardTaskId: null,
          taskState: "AWAITING_WORKER",
          lineCount: 1,
        },
      ],
    })
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
    await user.selectOptions(
      within(dialog).getByRole("combobox", { name: "Водитель" }),
      "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    )
    await user.click(
      within(dialog).getByRole("button", { name: "Создать отгрузку" })
    )

    await waitFor(() =>
      expect(shipmentApi.createShipmentFurnitureTasks).toHaveBeenCalled()
    )
    expect(shipmentApi.replaceShipmentPlan).not.toHaveBeenCalled()
  })

  it("shows return details and schedules one cabin for a custom date", async () => {
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
    await user.selectOptions(
      within(dialog).getByRole("combobox", { name: "Водитель" }),
      "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    )
    await user.clear(within(dialog).getByLabelText("Дата"))
    await user.type(within(dialog).getByLabelText("Дата"), "2026-08-15")
    await user.click(
      within(dialog).getByRole("button", { name: "Создать возврат" })
    )

    await waitFor(() =>
      expect(returnApi.registerReturn).toHaveBeenCalledWith(
        expect.objectContaining({
          documentId: RETURN_ID,
          scheduledDate: "2026-08-15",
          driverSnapshot: "Иванов Иван",
        })
      )
    )
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

  it("changes a non-today shipment date to today before confirming", async () => {
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
      within(dialog).getByRole("button", { name: "Изменить на сегодня" })
    )

    await waitFor(() =>
      expect(shipmentApi.replaceShipmentPlan).toHaveBeenCalledWith(
        expect.objectContaining({
          documentId: SHIPMENT_ID,
          expectedVersion: awaiting.version,
          driverSnapshot: awaiting.driverSnapshot,
          scheduledDate: currentUtcDate(),
        })
      )
    )
    await waitFor(() =>
      expect(shipmentApi.confirmShipmentPreparation).toHaveBeenCalledWith(
        expect.objectContaining({
          documentId: SHIPMENT_ID,
          expectedVersion: 6,
        })
      )
    )
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
