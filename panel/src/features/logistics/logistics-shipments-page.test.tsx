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
      queueCodes: [],
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
  })
  ordersApi.getOrder.mockResolvedValue({
    id: ORDER_ID,
    number: ORDER_NUMBER,
    units: [{ unit: { id: ASSET_ID, number: ASSET_NUMBER } }],
  })
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe("LogisticsShipmentsPage", () => {
  it("shows cabin and order numbers in the shipment composition", async () => {
    renderPage()

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

    await user.selectOptions(
      screen.getByRole("combobox", { name: "Водитель" }),
      "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    )
    const scheduledDate = "2026-07-20"
    await user.type(screen.getByLabelText("Дата отгрузки"), scheduledDate)
    await user.click(screen.getByRole("button", { name: "Сохранить дату" }))

    await waitFor(() =>
      expect(shipmentApi.replaceShipmentPlan).toHaveBeenCalledWith({
        accessToken: "shipment-token",
        documentId: DRAFT_ID,
        expectedVersion: 2,
        driverSnapshot: "Иванов Иван",
        scheduledDate,
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

    await waitFor(() =>
      expect(shipmentApi.confirmShipmentPreparation).toHaveBeenCalledWith({
        accessToken: "shipment-token",
        documentId: AWAITING_ID,
        expectedVersion: 5,
        idempotencyKey: CONFIRM_KEY,
      })
    )
  })

  it("asks to change a future shipment date instead of shipping early", async () => {
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

    expect(
      screen.getByRole("heading", { name: "Изменить дату отгрузки" })
    ).toBeTruthy()
    expect(screen.getByText(/Дата отгрузки ещё не наступила/)).toBeTruthy()
    expect(shipmentApi.confirmShipmentPreparation).not.toHaveBeenCalled()
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
