import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { TransferDocument } from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"

const transferApi = vi.hoisted(() => ({
  arriveWarehouseTransferLine: vi.fn(),
  cancelWarehouseTransfer: vi.fn(),
  createWarehouseTransfer: vi.fn(),
  departWarehouseTransferLine: vi.fn(),
  getWarehouseTransfer: vi.fn(),
  listWarehouseTransfers: vi.fn(),
  reconcileWarehouseTransfer: vi.fn(),
}))
const driverDirectoryApi = vi.hoisted(() => ({
  listRepairWorkerGroups: vi.fn(),
}))
const rentalItemsApi = vi.hoisted(() => ({
  listAssetRentalItems: vi.fn(),
}))
const authState = vi.hoisted(() => ({
  sourceLevel: "EDIT" as "VIEW" | "EDIT" | "MANAGE",
  destinationLevel: "EDIT" as "VIEW" | "EDIT" | "MANAGE",
}))

vi.mock(
  "@/features/logistics/warehouse-transfers/api/warehouse-transfer-api",
  () => ({
    WAREHOUSE_TRANSFERS_QUERY_KEY: ["logistics", "warehouse-transfers"],
    ...transferApi,
  })
)

vi.mock("@/features/repair-tasks/api/repair-worker-directory-api", () => ({
  repairWorkerGroupsQueryKey: (query: unknown) => [
    "repair-worker-groups",
    query,
  ],
  listRepairWorkerGroups: driverDirectoryApi.listRepairWorkerGroups,
}))

vi.mock("@/api/equipment-api", () => ({
  getEquipmentItems: vi.fn().mockResolvedValue([]),
}))

vi.mock("@/features/rental-items/api/asset-rental-items-api", () => ({
  listAssetRentalItems: rentalItemsApi.listAssetRentalItems,
}))

vi.mock("@/features/media/service-owner-photos", () => ({
  ServiceOwnerPhotos: ({
    title,
    onReadyReferencesChange,
  }: {
    title: string
    onReadyReferencesChange?: (
      references: Array<{ mediaId: string; generation: number }>
    ) => void
  }) => (
    <button
      type="button"
      onClick={() =>
        onReadyReferencesChange?.([
          {
            mediaId: "88888888-8888-4888-8888-888888888888",
            generation: 3,
          },
        ])
      }
    >
      Подготовить {title}
    </button>
  ),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "transfer-token",
    currentUser: {
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      username: "manager",
      displayName: "Менеджер",
      firstName: null,
      lastName: null,
      email: null,
      principalType: "USER",
      globalRole: "WAREHOUSE_MANAGER",
      warehouseAccessAll: false,
      warehouseAccesses: [
        {
          warehouseId: "11111111-1111-4111-8111-111111111111",
          level: authState.sourceLevel,
        },
        {
          warehouseId: "22222222-2222-4222-8222-222222222222",
          level: authState.destinationLevel,
        },
      ],
    },
  }),
}))

const SOURCE_WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const DESTINATION_WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouseId: SOURCE_WAREHOUSE_ID,
    warehouses: [
      {
        id: SOURCE_WAREHOUSE_ID,
        serviceId: SOURCE_WAREHOUSE_ID,
        version: 1,
        code: "MSK",
        name: "Москва",
        city: "Москва",
        address: null,
        timeZone: "Europe/Moscow",
        active: true,
        sortOrder: 1,
      },
      {
        id: DESTINATION_WAREHOUSE_ID,
        serviceId: DESTINATION_WAREHOUSE_ID,
        version: 1,
        code: "SPB",
        name: "Петербург",
        city: "Санкт-Петербург",
        address: null,
        timeZone: "Europe/Moscow",
        active: true,
        sortOrder: 2,
      },
    ],
  }),
}))

import { WarehouseTransfersPage } from "@/features/logistics/warehouse-transfers/warehouse-transfers-page"

const DOCUMENT_ID = "33333333-3333-4333-8333-333333333333"
const TRANSIT_DOCUMENT_ID = "44444444-4444-4444-8444-444444444444"
const LINE_ID = "55555555-5555-4555-8555-555555555555"
const ASSET_ID = "66666666-6666-4666-8666-666666666666"
const EQUIPMENT_TASK_ID = "77777777-7777-4777-8777-777777777777"
const IDEMPOTENCY_KEY = "99999999-9999-4999-8999-999999999999"
const TRANSFER_CABIN = {
  id: ASSET_ID,
  version: 8,
  warehouseId: SOURCE_WAREHOUSE_ID,
  number: "БЫТ-001",
  type: "БК",
  dimensions: null,
  finishing: null,
  category: null,
  characteristics: null,
  linoleum: null,
  status: "FREE" as const,
  comment: null,
  hasPhotos: false,
  photoCount: 0,
  mainPhotoUrl: null,
  locationNodeId: null,
  contents: null,
  contentsItems: [{ name: "Стол", quantity: 1 }],
  shipmentDate: null,
  tenant: null,
  price: null,
}

function transferDocument(
  id: string,
  state: TransferDocument["state"],
  version: number,
  lineState: TransferDocument["lines"][number]["state"] = state === "IN_TRANSIT"
    ? "DEPARTED"
    : "PENDING"
): TransferDocument {
  return {
    id,
    version,
    documentType: "TRANSFER",
    state,
    warehouseId: SOURCE_WAREHOUSE_ID,
    destinationWarehouseId: DESTINATION_WAREHOUSE_ID,
    partySnapshot: null,
    driverSnapshot: "Иванов Иван",
    clientId: null,
    equipmentMovementTaskId: EQUIPMENT_TASK_ID,
    lines: [
      {
        id: LINE_ID,
        version: 2,
        lineNumber: 1,
        assetId: ASSET_ID,
        assetVersion: 8,
        state: lineState,
        tenantSnapshot: null,
        rentalOrderId: null,
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
    <QueryClientProvider client={queryClient}>
      <WarehouseTransfersPage />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  authState.sourceLevel = "EDIT"
  authState.destinationLevel = "EDIT"
  vi.stubGlobal("crypto", { randomUUID: () => IDEMPOTENCY_KEY })
  driverDirectoryApi.listRepairWorkerGroups.mockResolvedValue([
    {
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      warehouseId: SOURCE_WAREHOUSE_ID,
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
  rentalItemsApi.listAssetRentalItems.mockResolvedValue({
    content: [TRANSFER_CABIN],
  })
  const draft = transferDocument(DOCUMENT_ID, "DRAFT", 4)
  transferApi.listWarehouseTransfers.mockResolvedValue([draft])
  transferApi.getWarehouseTransfer.mockResolvedValue(draft)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe("WarehouseTransfersPage", () => {
  it("keeps VIEW access read-only while preserving service reads", async () => {
    authState.sourceLevel = "VIEW"
    authState.destinationLevel = "VIEW"
    renderPage()

    await screen.findAllByText("Черновик")
    expect(transferApi.listWarehouseTransfers).toHaveBeenCalledWith(
      "transfer-token",
      SOURCE_WAREHOUSE_ID
    )
    expect(
      screen.queryByRole("button", { name: "Создать перемещение" })
    ).toBeNull()
    expect(screen.queryByRole("button", { name: "Отправить" })).toBeNull()
    expect(
      screen.queryByText(
        "Выберите свободные бытовки и мебель исходного склада. При создании logistics-service зарегистрирует задания для бытовок и мебели и обновит остатки после выполнения."
      )
    ).toBeNull()
  })

  it("opens the server-backed form with cabin and furniture sections", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Создать перемещение" })
    )

    expect(
      screen.getByRole("heading", { name: "Создать складское перемещение" })
    ).toBeTruthy()
    expect(screen.getByText("Бытовки и наполнение")).toBeTruthy()
    expect(screen.getByText("Мебель")).toBeTruthy()
    expect(screen.getByRole("combobox", { name: "Водитель" })).toBeTruthy()
    expect(screen.getByText(/отдельной серверной задачей/)).toBeTruthy()
    expect(screen.queryByLabelText("Asset UUID")).toBeNull()

    await user.click(screen.getByLabelText("Номер бытовки"))
    await user.click(await screen.findByText(TRANSFER_CABIN.number))

    expect(await screen.findByText("Стол")).toBeTruthy()
  })

  it("departs a cabin with both service-issued versions", async () => {
    authState.sourceLevel = "MANAGE"
    authState.destinationLevel = "MANAGE"
    const user = userEvent.setup()
    transferApi.departWarehouseTransferLine.mockResolvedValue(
      transferDocument(DOCUMENT_ID, "DEPARTING", 5, "DEPARTED")
    )
    renderPage()

    await user.click(await screen.findByRole("button", { name: "Отправить" }))

    await waitFor(() =>
      expect(transferApi.departWarehouseTransferLine).toHaveBeenCalledWith({
        accessToken: "transfer-token",
        documentId: DOCUMENT_ID,
        lineId: LINE_ID,
        expectedVersion: 4,
        expectedLineVersion: 2,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    )
  })

  it("arrives a cabin with media references and both CAS versions", async () => {
    authState.sourceLevel = "MANAGE"
    authState.destinationLevel = "MANAGE"
    const user = userEvent.setup()
    const transit = transferDocument(TRANSIT_DOCUMENT_ID, "IN_TRANSIT", 9)
    transferApi.listWarehouseTransfers.mockResolvedValue([transit])
    transferApi.arriveWarehouseTransferLine.mockResolvedValue(
      transferDocument(TRANSIT_DOCUMENT_ID, "ARRIVING", 10, "ARRIVING")
    )
    renderPage()

    await user.click(await screen.findByRole("button", { name: "Принять" }))
    await user.click(
      screen.getByRole("button", { name: "Подготовить Фотографии строки 1" })
    )
    await user.click(screen.getByRole("button", { name: /^Принять$/ }))

    await waitFor(() =>
      expect(transferApi.arriveWarehouseTransferLine).toHaveBeenCalledWith({
        accessToken: "transfer-token",
        documentId: TRANSIT_DOCUMENT_ID,
        lineId: LINE_ID,
        expectedVersion: 9,
        expectedLineVersion: 2,
        idempotencyKey: IDEMPOTENCY_KEY,
        references: [
          {
            mediaId: "88888888-8888-4888-8888-888888888888",
            generation: 3,
          },
        ],
      })
    )
  })

  it("cancels the document through the service-owned workflow", async () => {
    authState.sourceLevel = "MANAGE"
    authState.destinationLevel = "MANAGE"
    const user = userEvent.setup()
    transferApi.cancelWarehouseTransfer.mockResolvedValue(
      transferDocument(DOCUMENT_ID, "CANCELLED", 5, "CANCELLED")
    )
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Отменить документ" }))[0]!
    )
    await user.click(
      within(screen.getByRole("alertdialog")).getByRole("button", {
        name: "Отменить документ",
      })
    )

    await waitFor(() =>
      expect(transferApi.cancelWarehouseTransfer).toHaveBeenCalledWith({
        accessToken: "transfer-token",
        documentId: DOCUMENT_ID,
        expectedVersion: 4,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    )
  })
})
