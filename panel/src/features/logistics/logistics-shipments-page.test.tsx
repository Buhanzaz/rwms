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

import type { ShipmentDocument } from "@/features/logistics/shipments/model"

const shipmentApi = vi.hoisted(() => ({
  cancelShipment: vi.fn(),
  confirmShipmentPreparation: vi.fn(),
  createShipment: vi.fn(),
  listShipments: vi.fn(),
  replaceShipmentPlan: vi.fn(),
}))
const driverDirectoryApi = vi.hoisted(() => ({
  listRepairWorkerGroups: vi.fn(),
}))
const authState = vi.hoisted(() => ({
  level: "EDIT" as "VIEW" | "EDIT" | "MANAGE",
}))

vi.mock("@/features/logistics/shipments/api", () => ({
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
const CONFIRM_KEY = "88888888-8888-4888-8888-888888888888"
const CANCEL_KEY = "99999999-9999-4999-8999-999999999999"

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
    driverSnapshot: "Иванов Иван",
    clientId: CLIENT_ID,
    equipmentMovementTaskId: null,
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

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
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
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe("LogisticsShipmentsPage", () => {
  it("keeps VIEW access read-only while preserving service reads", async () => {
    authState.level = "VIEW"
    renderPage()

    await screen.findAllByText("Ждёт подтверждения")
    expect(shipmentApi.listShipments).toHaveBeenCalledWith(
      "shipment-token",
      WAREHOUSE_ID
    )
    expect(
      screen.queryByRole("button", { name: "Создать отгрузку" })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Подтвердить подготовку" })
    ).toBeNull()
    expect(
      screen.queryByText(
        "Резерв за выбранным заказом показывается первым; свободные бытовки можно добавить в ту же отгрузку. Подготовка и задания создаются одним серверным workflow."
      )
    ).toBeNull()
  })

  it("opens the server-backed create form without a browser plan action", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Создать отгрузку" })
    )

    expect(
      screen.getByRole("heading", { name: "Создать отгрузку в аренду" })
    ).toBeTruthy()
    expect(screen.getByRole("combobox", { name: "Водитель" })).toBeTruthy()
    expect(screen.getByText(/Зарезервированные за/)).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: /Запустить старый черновик/i })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: /Создать и запустить/i })
    ).toBeNull()
  })

  it("confirms preparation with the current service-issued version", async () => {
    vi.stubGlobal("crypto", { randomUUID: () => CONFIRM_KEY })
    const user = userEvent.setup()
    shipmentApi.confirmShipmentPreparation.mockResolvedValue(
      shipmentDocument(AWAITING_ID, "CONFIRMING_PREPARATION", 6)
    )
    renderPage()

    await user.click(
      (
        await screen.findAllByRole("button", {
          name: "Подтвердить подготовку",
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
