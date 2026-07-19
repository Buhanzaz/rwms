import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { TransferDocument } from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import { ApiError } from "@/lib/api-client"

const transferApi = vi.hoisted(() => ({
  listWarehouseTransfers: vi.fn(),
  getWarehouseTransfer: vi.fn(),
  createWarehouseTransfer: vi.fn(),
  departWarehouseTransferLine: vi.fn(),
  arriveWarehouseTransferLine: vi.fn(),
  cancelWarehouseTransfer: vi.fn(),
  reconcileWarehouseTransfer: vi.fn(),
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

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "transfer-token",
    currentUser: {
      id: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
      username: "logistics-manager",
      displayName: "Менеджер логистики",
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
const DOCUMENT_ID = "33333333-3333-4333-8333-333333333333"
const CONFLICT_DOCUMENT_ID = "44444444-4444-4444-8444-444444444444"
const LINE_ID = "55555555-5555-4555-8555-555555555555"
const ASSET_ID = "66666666-6666-4666-8666-666666666666"
const IDEMPOTENCY_KEY = "77777777-7777-4777-8777-777777777777"
const MEDIA_ID = "88888888-8888-4888-8888-888888888888"
const TRANSIT_DOCUMENT_ID = "99999999-9999-4999-8999-999999999999"
const SECOND_MEDIA_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

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

function transferDocument(
  id: string,
  state: TransferDocument["state"],
  version: number,
  lineState: TransferDocument["lines"][number]["state"] = state === "DRAFT"
    ? "PENDING"
    : state === "IN_TRANSIT"
      ? "DEPARTED"
      : "CONFLICT"
): TransferDocument {
  return {
    id,
    version,
    documentType: "TRANSFER",
    state,
    warehouseId: SOURCE_WAREHOUSE_ID,
    destinationWarehouseId: DESTINATION_WAREHOUSE_ID,
    partySnapshot: null,
    driverSnapshot: null,
    lines: [
      {
        id: LINE_ID,
        version: 2,
        lineNumber: 1,
        assetId: ASSET_ID,
        assetVersion: 8,
        state: lineState,
        tenantSnapshot: null,
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
  const draft = transferDocument(DOCUMENT_ID, "DRAFT", 4)
  transferApi.listWarehouseTransfers.mockResolvedValue([
    draft,
    transferDocument(CONFLICT_DOCUMENT_ID, "CONFLICT", 7),
  ])
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
      screen.queryByRole("button", { name: "Отменить документ" })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Выполнить сверку" })
    ).toBeNull()
    expect(screen.getByRole("button", { name: "Обновить" })).not.toBeNull()
  })

  it("requires EDIT access to both warehouses before offering create", async () => {
    authState.destinationLevel = "VIEW"
    renderPage()

    await screen.findAllByText("Черновик")
    expect(
      screen.queryByRole("button", { name: "Создать перемещение" })
    ).toBeNull()
  })

  it("renders service documents and hides unsupported browser-owned actions", async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findAllByText("Черновик")
    expect(transferApi.listWarehouseTransfers).toHaveBeenCalledWith(
      "transfer-token",
      SOURCE_WAREHOUSE_ID
    )
    expect(screen.queryByText(/Повторить задачу/i)).toBeNull()
    expect(screen.queryByText(/Коррекция учёта/i)).toBeNull()
    expect(screen.queryByRole("button", { name: /Принять/i })).toBeNull()
    expect(screen.queryByRole("button", { name: "Отправить" })).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Отменить документ" })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Выполнить сверку" })
    ).toBeNull()
    expect(
      screen.getByText(/Перенос наполнения между бытовками недоступен/i)
    ).not.toBeNull()

    await user.click(
      screen.getByRole("button", { name: "Создать перемещение" })
    )
    expect(
      screen.getByRole("heading", { name: "Создать складское перемещение" })
    ).not.toBeNull()
    expect(screen.getByLabelText("Asset UUID")).not.toBeNull()
    expect(screen.getByLabelText("Текущая версия asset")).not.toBeNull()
    expect(screen.getByLabelText("Склад назначения")).not.toBeNull()
  })

  it("applies the returned departure projection before refetch", async () => {
    authState.sourceLevel = "MANAGE"
    authState.destinationLevel = "MANAGE"
    const user = userEvent.setup()
    const draft = transferDocument(DOCUMENT_ID, "DRAFT", 4)
    transferApi.listWarehouseTransfers
      .mockResolvedValueOnce([draft])
      .mockReturnValue(new Promise<TransferDocument[]>(() => undefined))
    transferApi.departWarehouseTransferLine.mockResolvedValue(
      transferDocument(DOCUMENT_ID, "DEPARTING", 5, "DEPARTED")
    )
    renderPage()

    const buttons = await screen.findAllByRole("button", { name: "Отправить" })
    await user.click(buttons[0]!)

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
    await screen.findAllByText("Отправляется")
    expect(screen.queryByRole("button", { name: "Отправить" })).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Отменить документ" })
    ).toBeNull()
  })

  it("rejects malformed and duplicate asset UUIDs before create", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Создать перемещение" })
    )
    const firstAsset = screen.getByLabelText("Asset UUID")
    await user.type(firstAsset, "not-a-uuid")
    await user.click(screen.getByRole("button", { name: "Создать черновик" }))

    expect(
      await screen.findByText("Укажите корректный уникальный asset UUID.")
    ).not.toBeNull()
    expect(transferApi.createWarehouseTransfer).not.toHaveBeenCalled()

    await user.clear(firstAsset)
    await user.type(firstAsset, ASSET_ID)
    await user.click(screen.getByRole("button", { name: "Добавить строку" }))
    const assetInputs = screen.getAllByLabelText("Asset UUID")
    await user.type(assetInputs[1]!, ASSET_ID)
    await user.click(screen.getByRole("button", { name: "Создать черновик" }))

    expect(
      screen.getAllByText("Укажите корректный уникальный asset UUID.")
    ).toHaveLength(2)
    expect(transferApi.createWarehouseTransfer).not.toHaveBeenCalled()
  })

  it("arrives a departed line with READY media refs and both CAS versions", async () => {
    authState.sourceLevel = "MANAGE"
    authState.destinationLevel = "MANAGE"
    const user = userEvent.setup()
    const transit = transferDocument(
      TRANSIT_DOCUMENT_ID,
      "IN_TRANSIT",
      9,
      "DEPARTED"
    )
    transferApi.listWarehouseTransfers.mockResolvedValue([transit])
    transferApi.arriveWarehouseTransferLine.mockResolvedValue(
      transferDocument(TRANSIT_DOCUMENT_ID, "ARRIVING", 10, "ARRIVING")
    )
    renderPage()

    await user.click(await screen.findByRole("button", { name: "Принять" }))
    expect(
      screen.getByRole("heading", { name: "Принять строку перемещения" })
    ).toBeTruthy()
    await user.type(screen.getByLabelText("Media UUID"), MEDIA_ID)
    await user.clear(screen.getByLabelText("Поколение"))
    await user.type(screen.getByLabelText("Поколение"), "3")
    await user.click(screen.getByRole("button", { name: "Добавить media ref" }))
    const mediaInputs = screen.getAllByLabelText("Media UUID")
    const generationInputs = screen.getAllByLabelText("Поколение")
    await user.type(mediaInputs[1]!, SECOND_MEDIA_ID)
    await user.clear(generationInputs[1]!)
    await user.type(generationInputs[1]!, "4")
    await user.click(screen.getByRole("button", { name: "Принять строку" }))

    await waitFor(() =>
      expect(transferApi.arriveWarehouseTransferLine).toHaveBeenCalledWith({
        accessToken: "transfer-token",
        documentId: TRANSIT_DOCUMENT_ID,
        lineId: LINE_ID,
        expectedVersion: 9,
        expectedLineVersion: 2,
        idempotencyKey: IDEMPOTENCY_KEY,
        references: [
          { mediaId: MEDIA_ID, generation: 3 },
          { mediaId: SECOND_MEDIA_ID, generation: 4 },
        ],
      })
    )
  })

  it("reuses the arrival Idempotency-Key after a transient error", async () => {
    authState.sourceLevel = "MANAGE"
    authState.destinationLevel = "MANAGE"
    const randomUUID = vi.fn().mockReturnValue(IDEMPOTENCY_KEY)
    vi.stubGlobal("crypto", { randomUUID })
    const user = userEvent.setup()
    const transit = transferDocument(
      TRANSIT_DOCUMENT_ID,
      "IN_TRANSIT",
      9,
      "DEPARTED"
    )
    transferApi.listWarehouseTransfers.mockResolvedValue([transit])
    transferApi.arriveWarehouseTransferLine
      .mockRejectedValueOnce(new Error("Media-service временно недоступен"))
      .mockResolvedValueOnce(
        transferDocument(TRANSIT_DOCUMENT_ID, "ARRIVING", 10, "ARRIVING")
      )
    renderPage()

    await user.click(await screen.findByRole("button", { name: "Принять" }))
    await user.type(screen.getByLabelText("Media UUID"), MEDIA_ID)
    await user.click(screen.getByRole("button", { name: "Принять строку" }))
    await screen.findByText("Media-service временно недоступен")
    await user.click(screen.getByRole("button", { name: "Принять строку" }))

    await waitFor(() =>
      expect(transferApi.arriveWarehouseTransferLine).toHaveBeenCalledTimes(2)
    )
    const firstCommand =
      transferApi.arriveWarehouseTransferLine.mock.calls[0][0]
    const retriedCommand =
      transferApi.arriveWarehouseTransferLine.mock.calls[1][0]
    expect(firstCommand.idempotencyKey).toBe(IDEMPOTENCY_KEY)
    expect(retriedCommand.idempotencyKey).toBe(IDEMPOTENCY_KEY)
    expect(randomUUID).toHaveBeenCalledTimes(1)
  })

  it("shows an arrival conflict and refreshes the server projection", async () => {
    authState.sourceLevel = "MANAGE"
    authState.destinationLevel = "MANAGE"
    const user = userEvent.setup()
    const transit = transferDocument(
      TRANSIT_DOCUMENT_ID,
      "IN_TRANSIT",
      9,
      "DEPARTED"
    )
    transferApi.listWarehouseTransfers.mockResolvedValue([transit])
    transferApi.arriveWarehouseTransferLine.mockRejectedValue(
      new ApiError("Версия документа устарела.", 409)
    )
    renderPage()

    await user.click(await screen.findByRole("button", { name: "Принять" }))
    await user.type(screen.getByLabelText("Media UUID"), MEDIA_ID)
    await user.click(screen.getByRole("button", { name: "Принять строку" }))

    await screen.findByText(/Конфликт данных: Версия документа устарела\./)
    expect(
      screen.queryByRole("heading", { name: "Принять строку перемещения" })
    ).toBeNull()
    await waitFor(() =>
      expect(
        transferApi.listWarehouseTransfers.mock.calls.length
      ).toBeGreaterThan(1)
    )
  })

  it("uses document CAS for cancellation and reconciliation", async () => {
    authState.sourceLevel = "MANAGE"
    authState.destinationLevel = "MANAGE"
    const user = userEvent.setup()
    transferApi.cancelWarehouseTransfer.mockResolvedValue(
      transferDocument(DOCUMENT_ID, "CANCELLED", 5)
    )
    transferApi.reconcileWarehouseTransfer.mockResolvedValue(
      transferDocument(CONFLICT_DOCUMENT_ID, "DEPARTING", 8)
    )
    renderPage()

    const cancelButtons = await screen.findAllByRole("button", {
      name: "Отменить документ",
    })
    await user.click(cancelButtons[0]!)
    const confirmButtons = screen.getAllByRole("button", {
      name: "Отменить документ",
    })
    await user.click(confirmButtons.at(-1)!)
    await waitFor(() =>
      expect(transferApi.cancelWarehouseTransfer).toHaveBeenCalledWith({
        accessToken: "transfer-token",
        documentId: DOCUMENT_ID,
        expectedVersion: 4,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    )

    const reconcileButtons = screen.getAllByRole("button", {
      name: "Выполнить сверку",
    })
    await user.click(reconcileButtons[0]!)
    await user.type(
      screen.getByLabelText("Причина сверки"),
      "Проверить зависший эффект"
    )
    await user.click(screen.getByRole("button", { name: "Выполнить сверку" }))

    await waitFor(() =>
      expect(transferApi.reconcileWarehouseTransfer).toHaveBeenCalledWith({
        accessToken: "transfer-token",
        documentId: CONFLICT_DOCUMENT_ID,
        expectedVersion: 7,
        idempotencyKey: IDEMPOTENCY_KEY,
        reason: "Проверить зависший эффект",
      })
    )
  })
})
