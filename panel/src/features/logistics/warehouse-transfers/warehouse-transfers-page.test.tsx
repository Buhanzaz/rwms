import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { TransferDocument } from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"

Object.defineProperties(HTMLElement.prototype, {
  hasPointerCapture: { configurable: true, value: () => false },
  releasePointerCapture: { configurable: true, value: () => undefined },
  scrollIntoView: { configurable: true, value: () => undefined },
  setPointerCapture: { configurable: true, value: () => undefined },
})

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
const equipmentApi = vi.hoisted(() => ({
  getEquipmentItems: vi.fn(),
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
  getEquipmentItems: equipmentApi.getEquipmentItems,
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
const SECOND_ASSET_ID = "67676767-6767-4676-8676-676767676767"
const EMPTY_ASSET_ID = "68686868-6868-4686-8686-686868686868"
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
  contentsItems: [
    {
      equipmentId: EQUIPMENT_TASK_ID,
      equipmentName: "Стол",
      name: "Стол",
      quantity: 1,
    },
  ],
  shipmentDate: null,
  tenant: null,
  price: null,
}
const SECOND_TRANSFER_CABIN = {
  ...TRANSFER_CABIN,
  id: SECOND_ASSET_ID,
  version: 3,
  number: "БЫТ-111",
  contents: "Стул — 2",
  contentsItems: [
    {
      equipmentId: EQUIPMENT_TASK_ID,
      equipmentName: "Стул",
      name: "Стул",
      quantity: 2,
    },
  ],
}
const EMPTY_TRANSFER_CABIN = {
  ...TRANSFER_CABIN,
  id: EMPTY_ASSET_ID,
  version: 2,
  number: "БЫТ-222",
  contents: null,
  contentsItems: [],
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
    scheduledDate: "2026-07-19",
    scheduledAt: null,
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
    content: [TRANSFER_CABIN, SECOND_TRANSFER_CABIN, EMPTY_TRANSFER_CABIN],
  })
  equipmentApi.getEquipmentItems.mockResolvedValue([
    {
      id: EQUIPMENT_TASK_ID,
      name: "Стол",
      category: "FURNITURE",
      active: true,
      availableStock: 12,
    },
  ])
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

  it("opens the server-backed form with per-cabin furniture controls", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Создать перемещение" })
    )

    expect(
      screen.getByRole("heading", { name: "Создать складское перемещение" })
    ).toBeTruthy()
    expect(screen.getByText("Бытовки и наполнение")).toBeTruthy()
    expect(screen.getByRole("combobox", { name: "Водитель" })).toBeTruthy()
    expect(screen.getByLabelText("Дата задания")).toBeTruthy()
    expect(screen.queryByText("Мебель")).toBeNull()
    expect(screen.queryByLabelText("Asset UUID")).toBeNull()

    await user.click(screen.getByLabelText("Номер бытовки"))
    await user.click(await screen.findByText(TRANSFER_CABIN.number))

    expect(await screen.findByText("Стол")).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Изменить наполнение" })
    ).toBeTruthy()
  })

  it("filters transfer cabins by the typed cabin number", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Создать перемещение" })
    )
    const cabinInput = screen.getByRole("combobox", {
      name: "Номер бытовки",
    })
    await user.click(cabinInput)
    await user.type(cabinInput, "111")

    expect(
      await screen.findByRole("option", { name: SECOND_TRANSFER_CABIN.number })
    ).toBeTruthy()
    expect(
      screen.queryByRole("option", { name: TRANSFER_CABIN.number })
    ).toBeNull()
  })

  it("offers furniture addition for an empty selected cabin", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Создать перемещение" })
    )
    await user.click(screen.getByLabelText("Номер бытовки"))
    await user.click(await screen.findByText(EMPTY_TRANSFER_CABIN.number))

    expect(screen.getByRole("button", { name: "Добавить мебель" })).toBeTruthy()
  })

  it("creates a transfer with a date and the selected cabin composition", async () => {
    const user = userEvent.setup()
    transferApi.createWarehouseTransfer.mockResolvedValue(
      transferDocument(DOCUMENT_ID, "DRAFT", 4)
    )
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Создать перемещение" })
    )
    const dialog = screen.getByRole("dialog")
    await user.click(
      within(dialog).getByRole("combobox", { name: "Склад назначения" })
    )
    await user.click(
      await screen.findByRole("option", { name: "Петербург · Санкт-Петербург" })
    )
    fireEvent.change(within(dialog).getByLabelText("Дата задания"), {
      target: { value: "2026-07-25" },
    })
    await user.click(within(dialog).getByLabelText("Номер бытовки"))
    await user.click(await screen.findByText(TRANSFER_CABIN.number))
    await user.click(
      await within(dialog).findByRole("button", {
        name: "Изменить наполнение",
      })
    )
    await user.click(
      screen.getByRole("button", { name: "Сохранить наполнение" })
    )
    expect(transferApi.createWarehouseTransfer).not.toHaveBeenCalled()
    await user.click(
      within(dialog).getByRole("button", { name: "Создать перемещение" })
    )

    await waitFor(() =>
      expect(transferApi.createWarehouseTransfer).toHaveBeenCalledWith({
        accessToken: "transfer-token",
        warehouseId: SOURCE_WAREHOUSE_ID,
        destinationWarehouseId: DESTINATION_WAREHOUSE_ID,
        driverSnapshot: null,
        scheduledDate: "2026-07-25",
        lines: [{ assetId: ASSET_ID, assetVersion: 8 }],
        furnitureReplacements: [
          {
            assetId: ASSET_ID,
            contents: [{ equipmentId: EQUIPMENT_TASK_ID, quantity: 1 }],
          },
        ],
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    )
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
