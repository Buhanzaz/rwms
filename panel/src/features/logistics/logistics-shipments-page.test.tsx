import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, useLocation } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { ShipmentDocument } from "@/features/logistics/shipments/model"

const shipmentApi = vi.hoisted(() => ({
  cancelShipment: vi.fn(),
  confirmShipmentPreparation: vi.fn(),
  createShipment: vi.fn(),
  createShipmentFurnitureTasks: vi.fn(),
  getShipmentFurnitureReadiness: vi.fn(),
  listShipments: vi.fn(),
  replaceShipmentPlan: vi.fn(),
}))
const driverDirectoryApi = vi.hoisted(() => ({
  listRepairWorkerGroups: vi.fn(),
}))
const rentalItemsApi = vi.hoisted(() => ({
  getAssetRentalItem: vi.fn(),
}))
const ordersApi = vi.hoisted(() => ({
  getOrder: vi.fn(),
}))
const equipmentMovementTasksApi = vi.hoisted(() => ({
  getEquipmentMovementTask: vi.fn(),
}))
const returnApi = vi.hoisted(() => ({
  listReturns: vi.fn(),
}))
const authState = vi.hoisted(() => ({
  level: "EDIT" as "VIEW" | "EDIT" | "MANAGE",
}))

vi.mock("@/features/logistics/shipments/api", () => ({
  SHIPMENT_FURNITURE_READINESS_QUERY_KEY: [
    "logistics",
    "shipment-furniture-readiness",
  ],
  SHIPMENTS_QUERY_KEY: ["logistics", "shipments"],
  ...shipmentApi,
}))

vi.mock("@/features/repair-tasks/api/repair-worker-directory-api", () => ({
  repairWorkerGroupsQueryKey: (query: unknown) => [
    "repair-worker-groups",
    query,
  ],
  listRepairWorkerGroups: driverDirectoryApi.listRepairWorkerGroups,
}))

vi.mock("@/features/rental-items/api/asset-rental-items-api", () => ({
  getAssetRentalItem: rentalItemsApi.getAssetRentalItem,
}))

vi.mock("@/features/orders/api/orders-api", () => ({
  ORDERS_QUERY_KEY: ["orders"],
  getOrder: ordersApi.getOrder,
}))

vi.mock("@/features/logistics/api/equipment-movement-tasks-api", () => ({
  getEquipmentMovementTask: equipmentMovementTasksApi.getEquipmentMovementTask,
}))

vi.mock("@/features/logistics/returns/api", () => ({
  RETURNS_QUERY_KEY: ["logistics", "returns"],
  listReturns: returnApi.listReturns,
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
        value={value?.id ?? ""}
        onChange={(event) =>
          onChange(
            event.target.value
              ? {
                  id: event.target.value,
                  name: "Иванов Иван",
                }
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

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "shipment-token",
    currentUser: {
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      username: "dispatcher",
      displayName: "Диспетчер",
      firstName: null,
      lastName: null,
      email: null,
      principalType: "USER",
      globalRole: "WAREHOUSE_MANAGER",
      warehouseAccessAll: false,
      warehouseAccesses: [
        {
          warehouseId: "11111111-1111-4111-8111-111111111111",
          level: authState.level,
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

import { LogisticsShipmentsPage } from "@/features/logistics/logistics-shipments-page"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const DRAFT_ID = "22222222-2222-4222-8222-222222222222"
const AWAITING_ID = "33333333-3333-4333-8333-333333333333"
const LINE_ID = "44444444-4444-4444-8444-444444444444"
const ASSET_ID = "55555555-5555-4555-8555-555555555555"
const CLIENT_ID = "66666666-6666-4666-8666-666666666666"
const ORDER_ID = "77777777-7777-4777-8777-777777777777"
const SCHEDULE_KEY = "10101010-1010-4010-8010-101010101010"
const CONFIRM_KEY = "88888888-8888-4888-8888-888888888888"
const CANCEL_KEY = "99999999-9999-4999-8999-999999999999"
const FURNITURE_KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"
const FURNITURE_TASK_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbc"
const EXTERNAL_FURNITURE_TASK_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
const EQUIPMENT_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
const EQUIPMENT_TASK_LINE_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
const RETURN_TASK_LINE_ID = "ffffffff-ffff-4fff-8fff-ffffffffffff"
const RETURN_ID = "abababab-abab-4bab-8bab-abababababab"
const DRIVER_WORKER_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
const ASSET_NUMBER = "БЫТ-041"
const ORDER_NUMBER = "ORD-000007"

function shipmentDocument(
  id: string,
  state: ShipmentDocument["state"],
  version: number
): ShipmentDocument {
  return {
    id,
    version,
    documentType: "SHIPMENT",
    state,
    warehouseId: WAREHOUSE_ID,
    destinationWarehouseId: null,
    partySnapshot: "ООО Тест",
    driverSnapshot: state === "DRAFT" ? null : "Иванов Иван",
    driverWorkerId: state === "DRAFT" ? null : DRIVER_WORKER_ID,
    clientId: CLIENT_ID,
    equipmentMovementTaskId: null,
    scheduledDate: state === "DRAFT" ? null : "2026-07-18",
    rentalOrderId: ORDER_ID,
    lines: [
      {
        id: LINE_ID,
        version: 1,
        lineNumber: 1,
        assetId: ASSET_ID,
        assetVersion: 8,
        state: "PENDING",
        tenantSnapshot: null,
        rentalOrderId: ORDER_ID,
      },
    ],
    createdAt: "2026-07-18T08:00:00Z",
    updatedAt: "2026-07-18T08:10:00Z",
  }
}

function LocationProbe() {
  const location = useLocation()
  return (
    <output data-testid="current-location">{`${location.pathname}${location.search}`}</output>
  )
}

function renderPage(initialEntry = "/") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <QueryClientProvider client={queryClient}>
        <LocationProbe />
        <LogisticsShipmentsPage />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  authState.level = "EDIT"
  driverDirectoryApi.listRepairWorkerGroups.mockResolvedValue([
    {
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      warehouseId: WAREHOUSE_ID,
      name: "Водители",
      active: true,
      queueIds: [],
      routeQueueKinds: ["MOVEMENT"],
      members: [
        {
          id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
          name: "Иванов Иван",
        },
      ],
    },
  ])
  shipmentApi.listShipments.mockResolvedValue([
    shipmentDocument(DRAFT_ID, "DRAFT", 2),
    shipmentDocument(AWAITING_ID, "AWAITING_CONFIRMATION", 5),
  ])
  shipmentApi.getShipmentFurnitureReadiness.mockImplementation(
    (_accessToken: string, documentId: string) =>
      Promise.resolve({
        shipmentId: documentId,
        shipmentVersion: documentId === DRAFT_ID ? 2 : 5,
        state: "READY",
        tasks: [],
      })
  )
  rentalItemsApi.getAssetRentalItem.mockResolvedValue({
    id: ASSET_ID,
    number: ASSET_NUMBER,
    contentsItems: [
      {
        equipmentId: EQUIPMENT_ID,
        equipmentName: "Стол",
        name: "Стол",
        quantity: 1,
      },
    ],
  })
  ordersApi.getOrder.mockResolvedValue({
    id: ORDER_ID,
    number: ORDER_NUMBER,
    status: "SAVED",
    client: {
      id: CLIENT_ID,
      displayName: "ООО Тест",
      contactPerson: "Анна Смирнова",
      phone: "+7 900 100-20-30",
      additionalContacts: [
        { name: "Павел Сидоров", phone: "+7 900 200-30-40" },
      ],
    },
    deliveryAddress: "Москва, ул. Тестовая, 1",
    latitude: 55.751244,
    longitude: 37.618423,
    contactPhone: "+7 900 300-40-50",
    comment: "Позвонить за час",
    additionalContacts: [{ name: "Олег Кузнецов", phone: "+7 900 400-50-60" }],
    desiredDeliveryWindows: [
      {
        startDate: "2026-07-20",
        endDate: "2026-07-20",
      },
    ],
    units: [
      {
        unit: { id: ASSET_ID, number: ASSET_NUMBER },
        desiredContents: [
          {
            equipmentId: EQUIPMENT_ID,
            equipmentName: "Конвектор",
            quantity: 2,
          },
        ],
        rentalTerm: {
          rentalMonths: 3,
          shipmentDate: "2026-07-18",
          returnDate: "2026-10-18",
        },
      },
    ],
  })
  returnApi.listReturns.mockResolvedValue([
    {
      id: RETURN_ID,
      rentalShipmentId: DRAFT_ID,
      driverSnapshot: "Петров Пётр",
      lines: [{ assetId: ASSET_ID }],
    },
  ])
  equipmentMovementTasksApi.getEquipmentMovementTask.mockResolvedValue({
    id: FURNITURE_TASK_ID,
    externalTaskId: EXTERNAL_FURNITURE_TASK_ID,
    lines: [],
  })
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe("LogisticsShipmentsPage", () => {
  it("shows cabin and order numbers in the shipment composition", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать состав" }))[0]!
    )

    expect(
      (await screen.findAllByText(`Бытовка ${ASSET_NUMBER}`)).length
    ).toBeGreaterThan(0)
    expect(
      screen.getAllByRole("link", { name: `Заказ ${ORDER_NUMBER}` }).length
    ).toBeGreaterThan(0)
    expect(rentalItemsApi.getAssetRentalItem).toHaveBeenCalledWith(
      "shipment-token",
      ASSET_ID
    )
    expect(ordersApi.getOrder).toHaveBeenCalledWith("shipment-token", ORDER_ID)
    expect(screen.queryByText(new RegExp(ASSET_ID))).toBeNull()
    expect(screen.queryByText(new RegExp(ORDER_ID))).toBeNull()
  })

  it("shows order contacts, rental details, drivers, and the cabin filling status when expanded", async () => {
    shipmentApi.listShipments.mockResolvedValue([
      shipmentDocument(DRAFT_ID, "SHIPPED", 6),
    ])
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать состав" }))[0]!
    )

    expect(
      screen.getAllByRole("region", { name: "Заказ и контакты клиента" }).length
    ).toBeGreaterThan(0)
    expect(screen.getAllByText("Анна Смирнова").length).toBeGreaterThan(0)
    expect(
      screen.getAllByText("Москва, ул. Тестовая, 1").length
    ).toBeGreaterThan(0)
    expect(screen.getAllByText("55.751244, 37.618423").length).toBeGreaterThan(
      0
    )
    expect(
      screen.getAllByRole("link", { name: "+7 900 300-40-50" }).length
    ).toBeGreaterThan(0)
    expect(screen.getAllByText("Требуется наполнение").length).toBeGreaterThan(
      0
    )
    expect(screen.getAllByText("Срок аренды: 3 мес.").length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Когда уехала:/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Отгрузил:/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Привёз:/).length).toBeGreaterThan(0)
    expect(screen.getAllByText("Петров Пётр").length).toBeGreaterThan(0)
  })

  it("keeps unavailable owner data neutral instead of claiming a filling mismatch", async () => {
    ordersApi.getOrder.mockRejectedValue(new Error("orders unavailable"))
    rentalItemsApi.getAssetRentalItem.mockRejectedValue(
      new Error("assets unavailable")
    )
    returnApi.listReturns.mockRejectedValue(new Error("returns unavailable"))
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать состав" }))[0]!
    )

    expect(
      (await screen.findAllByText("Наполнение недоступно")).length
    ).toBeGreaterThan(0)
    expect(screen.queryByText("Требуется наполнение")).toBeNull()
    expect(screen.queryByText("Требуются действия")).toBeNull()
    expect(
      (await screen.findAllByText("Данные возврата недоступны")).length
    ).toBeGreaterThan(0)
  })

  it("shows empty current contents, saved desired contents, and linked task lines for the cabin", async () => {
    shipmentApi.getShipmentFurnitureReadiness.mockImplementation(
      (_accessToken: string, documentId: string) =>
        Promise.resolve({
          shipmentId: documentId,
          shipmentVersion: documentId === DRAFT_ID ? 2 : 5,
          state: documentId === DRAFT_ID ? "AWAITING_TASK_COMPLETION" : "READY",
          tasks:
            documentId === DRAFT_ID
              ? [
                  {
                    rentalItemId: ASSET_ID,
                    unitNumber: ASSET_NUMBER,
                    taskId: FURNITURE_TASK_ID,
                    externalTaskId: EXTERNAL_FURNITURE_TASK_ID,
                    taskBoardTaskId: null,
                    taskState: "AWAITING_WORKER",
                    lineCount: 2,
                  },
                ]
              : [],
        })
    )
    rentalItemsApi.getAssetRentalItem.mockResolvedValue({
      id: ASSET_ID,
      number: ASSET_NUMBER,
      contentsItems: [],
    })
    equipmentMovementTasksApi.getEquipmentMovementTask.mockResolvedValue({
      id: FURNITURE_TASK_ID,
      externalTaskId: EXTERNAL_FURNITURE_TASK_ID,
      lines: [
        {
          id: EQUIPMENT_TASK_LINE_ID,
          equipmentId: EQUIPMENT_ID,
          equipmentName: "Конвектор",
          sourceRentalItemId: null,
          sourceLocationKind: "STOCK",
          targetRentalItemId: ASSET_ID,
          targetLocationKind: "CABIN_NON_RENTED",
          quantity: 2,
        },
        {
          id: RETURN_TASK_LINE_ID,
          equipmentId: EQUIPMENT_ID,
          equipmentName: "Стол",
          sourceRentalItemId: ASSET_ID,
          sourceLocationKind: "CABIN_NON_RENTED",
          targetRentalItemId: null,
          targetLocationKind: "STOCK",
          quantity: 1,
        },
      ],
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать состав" }))[0]!
    )

    expect(
      (await screen.findAllByText("Требуется наполнение")).length
    ).toBeGreaterThan(0)
    expect(screen.getAllByText("Требуется:").length).toBeGreaterThan(0)
    expect(screen.getAllByText("В бытовке:").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Конвектор — 2 шт.").length).toBeGreaterThan(0)
    expect(screen.getAllByText("нет").length).toBeGreaterThan(0)
    expect(screen.getAllByText("× 2").length).toBeGreaterThan(0)

    await waitFor(() =>
      expect(
        equipmentMovementTasksApi.getEquipmentMovementTask
      ).toHaveBeenCalledWith("shipment-token", FURNITURE_TASK_ID)
    )
    expect(
      (await screen.findAllByText(/Склад → Бытовка БЫТ-041/)).length
    ).toBeGreaterThan(0)
    expect(
      screen.getAllByText(/Бытовка БЫТ-041 → Склад/).length
    ).toBeGreaterThan(0)
  })

  it("keeps VIEW access read-only while preserving service reads", async () => {
    authState.level = "VIEW"
    renderPage()

    await screen.findAllByText("Ждёт подтверждения")
    expect(shipmentApi.listShipments).toHaveBeenCalledWith(
      "shipment-token",
      WAREHOUSE_ID
    )
    expect(screen.queryByRole("button", { name: "Отгрузить" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Отгружена" })).toBeNull()
  })

  it("shows inline shipment filters and filters locally by counterparty", async () => {
    shipmentApi.listShipments.mockResolvedValue([
      {
        ...shipmentDocument(DRAFT_ID, "DRAFT", 2),
        partySnapshot: "ООО Альфа",
        driverSnapshot: "Иванов Иван",
      },
      {
        ...shipmentDocument(AWAITING_ID, "AWAITING_CONFIRMATION", 5),
        partySnapshot: "ООО Бета",
        driverSnapshot: "Петров Пётр",
      },
    ])
    const user = userEvent.setup()
    renderPage()

    await screen.findAllByText("ООО Альфа")
    expect(screen.getAllByRole("button", { name: "Статус" })).not.toHaveLength(
      0
    )
    expect(
      screen.getAllByRole("button", { name: "Контрагент" })
    ).not.toHaveLength(0)
    expect(
      screen.getAllByRole("button", { name: "Водитель" })
    ).not.toHaveLength(0)
    expect(screen.getByLabelText("Отгрузка с")).toBeTruthy()
    expect(screen.getByLabelText("Отгрузка по")).toBeTruthy()
    expect(screen.queryByText("Дата", { exact: true })).toBeNull()
    expect(screen.queryByRole("button", { name: "Обновить" })).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Показать все документы" })
    ).toBeNull()

    await user.click(screen.getAllByRole("button", { name: "Контрагент" })[0]!)
    await user.click(screen.getByRole("checkbox", { name: "ООО Альфа" }))
    await user.click(screen.getByRole("button", { name: "Применить" }))

    await waitFor(() => expect(screen.queryByText("ООО Бета")).toBeNull())
    expect(screen.getAllByText("ООО Альфа")).not.toHaveLength(0)
  })

  it("keeps the shipment search compact and lets the user hide filters", async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findAllByText("Ждёт подтверждения")
    const search = screen.getByRole("textbox", { name: "Поиск отгрузок" })
    expect(search.parentElement?.classList.contains("max-w-xl")).toBe(true)

    const hideFilters = screen.getByRole("button", {
      name: "Скрыть фильтры",
    })
    expect(hideFilters.getAttribute("aria-controls")).toBe(
      "logistics-shipment-filters"
    )
    expect(hideFilters.getAttribute("aria-expanded")).toBe("true")

    await user.click(hideFilters)

    expect(document.getElementById("logistics-shipment-filters")?.hidden).toBe(
      true
    )
    const showFilters = screen.getByRole("button", {
      name: "Показать фильтры",
    })
    expect(showFilters.getAttribute("aria-expanded")).toBe("false")

    await user.click(showFilters)

    expect(document.getElementById("logistics-shipment-filters")?.hidden).toBe(
      false
    )
  })

  it("assigns a driver and date to the shipment created from a saved order", async () => {
    vi.stubGlobal("crypto", { randomUUID: () => SCHEDULE_KEY })
    const user = userEvent.setup()
    shipmentApi.replaceShipmentPlan.mockResolvedValue(
      shipmentDocument(DRAFT_ID, "PREPARING", 3)
    )
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Отгрузить" }))[0]!
    )

    const scheduleDialog = screen.getByRole("dialog")
    expect(
      within(scheduleDialog).getByRole("region", { name: "Бытовки отгрузки" })
    ).toBeTruthy()
    expect(
      within(scheduleDialog).getByText(`Бытовка ${ASSET_NUMBER}`)
    ).toBeTruthy()
    expect(
      within(scheduleDialog).getByText("Требуется наполнение")
    ).toBeTruthy()

    await user.selectOptions(
      screen.getByRole("combobox", { name: "Водитель" }),
      "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    )
    const desiredDay = document.querySelector<HTMLButtonElement>(
      '[data-day="20.07.2026"][data-desired-window="true"]'
    )
    expect(desiredDay).toBeTruthy()
    await user.click(desiredDay!)
    expect(screen.queryByLabelText("Фактическое время ходки")).toBeNull()
    await user.click(screen.getByRole("button", { name: "Сохранить дату" }))

    await waitFor(() =>
      expect(shipmentApi.replaceShipmentPlan).toHaveBeenCalledWith({
        accessToken: "shipment-token",
        documentId: DRAFT_ID,
        expectedVersion: 2,
        driverSnapshot: "Иванов Иван",
        driverWorkerId: DRIVER_WORKER_ID,
        scheduledDate: "2026-07-20",
        idempotencyKey: SCHEDULE_KEY,
      })
    )
  })

  it("asks the service to create only the furniture movements needed for a saved order", async () => {
    vi.stubGlobal("crypto", { randomUUID: () => FURNITURE_KEY })
    shipmentApi.getShipmentFurnitureReadiness.mockImplementation(
      (_accessToken: string, documentId: string) =>
        Promise.resolve({
          shipmentId: documentId,
          shipmentVersion: documentId === DRAFT_ID ? 2 : 5,
          state: documentId === DRAFT_ID ? "REQUIRES_TASK_CREATION" : "READY",
          tasks: [],
        })
    )
    shipmentApi.createShipmentFurnitureTasks.mockResolvedValue({
      shipmentId: DRAFT_ID,
      shipmentVersion: 2,
      tasks: [
        {
          rentalItemId: ASSET_ID,
          unitNumber: ASSET_NUMBER,
          taskId: FURNITURE_TASK_ID,
          lineCount: 2,
        },
      ],
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Добавить мебель" }))[0]!
    )

    await waitFor(() =>
      expect(shipmentApi.createShipmentFurnitureTasks).toHaveBeenCalledWith({
        accessToken: "shipment-token",
        documentId: DRAFT_ID,
        expectedVersion: 2,
        idempotencyKey: FURNITURE_KEY,
      })
    )
    expect(
      screen.getByText("Мебель обработана: создано заданий: 1.")
    ).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Добавить мебель" })).toBeNull()
  })

  it("opens the unfinished furniture task and restores the primary shipment action when furniture is ready", async () => {
    shipmentApi.getShipmentFurnitureReadiness.mockImplementation(
      (_accessToken: string, documentId: string) =>
        Promise.resolve({
          shipmentId: documentId,
          shipmentVersion: documentId === DRAFT_ID ? 2 : 5,
          state: documentId === DRAFT_ID ? "AWAITING_TASK_COMPLETION" : "READY",
          tasks:
            documentId === DRAFT_ID
              ? [
                  {
                    rentalItemId: ASSET_ID,
                    unitNumber: ASSET_NUMBER,
                    taskId: FURNITURE_TASK_ID,
                    externalTaskId: EXTERNAL_FURNITURE_TASK_ID,
                    taskBoardTaskId: null,
                    taskState: "AWAITING_WORKER",
                    lineCount: 2,
                  },
                ]
              : [],
        })
    )
    const user = userEvent.setup()
    renderPage()

    const taskButton = (
      await screen.findAllByRole("button", {
        name: "Требуется закрыть задание",
      })
    )[0]!
    expect(taskButton.getAttribute("data-variant")).toBe("outline")
    await user.click(taskButton)
    expect(screen.getByTestId("current-location").textContent).toBe(
      `/task-board?externalTaskId=${EXTERNAL_FURNITURE_TASK_ID}`
    )

    cleanup()
    shipmentApi.getShipmentFurnitureReadiness.mockImplementation(
      (_accessToken: string, documentId: string) =>
        Promise.resolve({
          shipmentId: documentId,
          shipmentVersion: documentId === DRAFT_ID ? 2 : 5,
          state: "READY",
          tasks: [],
        })
    )
    renderPage()
    expect(
      (
        await screen.findAllByRole("button", { name: "Отгрузить" })
      )[0]!.getAttribute("data-variant")
    ).toBe("default")
  })

  it("marks a due shipment as shipped with the current service-issued version", async () => {
    vi.stubGlobal("crypto", { randomUUID: () => CONFIRM_KEY })
    const user = userEvent.setup()
    shipmentApi.confirmShipmentPreparation.mockResolvedValue(
      shipmentDocument(AWAITING_ID, "CONFIRMING_PREPARATION", 6)
    )
    renderPage()

    await user.click(
      (
        await screen.findAllByRole("button", {
          name: "Отгружена",
        })
      )[0]!
    )

    const dateDialog = screen.queryByRole("dialog", {
      name: "Дата отгрузки отличается",
    })
    if (dateDialog) {
      await user.click(
        within(dateDialog).getByRole("button", {
          name: "Оставить назначенную",
        })
      )
    }

    await waitFor(() =>
      expect(shipmentApi.confirmShipmentPreparation).toHaveBeenCalledWith({
        accessToken: "shipment-token",
        documentId: AWAITING_ID,
        expectedVersion: 5,
        idempotencyKey: CONFIRM_KEY,
        keepScheduledDate: true,
      })
    )
  })

  it("opens a separate schedule command without chaining shipment confirmation", async () => {
    vi.stubGlobal("crypto", { randomUUID: () => CONFIRM_KEY })
    const future = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10)
    shipmentApi.listShipments.mockResolvedValue([
      {
        ...shipmentDocument(AWAITING_ID, "AWAITING_CONFIRMATION", 5),
        scheduledDate: future,
      },
    ])
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Отгружена" }))[0]!
    )

    const dialog = screen.getByRole("dialog", {
      name: "Дата отгрузки отличается",
    })
    expect(within(dialog).getByText(/Назначенная дата/)).toBeTruthy()
    await user.click(
      within(dialog).getByRole("button", { name: "Изменить дату отдельно" })
    )
    expect(
      screen.getByRole("heading", { name: "Изменить дату отгрузки" })
    ).toBeTruthy()
    expect(shipmentApi.confirmShipmentPreparation).not.toHaveBeenCalled()
    expect(shipmentApi.replaceShipmentPlan).not.toHaveBeenCalled()
  })

  it("cancels a preparation through the service workflow with document CAS", async () => {
    vi.stubGlobal("crypto", { randomUUID: () => CANCEL_KEY })
    const user = userEvent.setup()
    shipmentApi.cancelShipment.mockResolvedValue(
      shipmentDocument(DRAFT_ID, "CANCELLING", 3)
    )
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Отменить" }))[0]!
    )
    await user.click(
      within(screen.getByRole("alertdialog")).getByRole("button", {
        name: "Отменить",
      })
    )

    await waitFor(() =>
      expect(shipmentApi.cancelShipment).toHaveBeenCalledWith({
        accessToken: "shipment-token",
        documentId: DRAFT_ID,
        expectedVersion: 2,
        idempotencyKey: CANCEL_KEY,
      })
    )
  })
})
