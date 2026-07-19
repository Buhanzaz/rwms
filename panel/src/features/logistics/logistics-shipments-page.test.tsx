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
import { ApiError } from "@/lib/api-client"

const shipmentApi = vi.hoisted(() => ({
  listShipments: vi.fn(),
  createShipment: vi.fn(),
  replaceShipmentPlan: vi.fn(),
  confirmShipmentPreparation: vi.fn(),
  cancelShipment: vi.fn(),
}))
const authState = vi.hoisted(() => ({
  level: "EDIT" as "VIEW" | "EDIT" | "MANAGE",
}))

vi.mock("@/features/logistics/shipments/api", () => ({
  SHIPMENTS_QUERY_KEY: ["logistics", "shipments"],
  ...shipmentApi,
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "shipment-token",
    currentUser: {
      id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
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
const SLOT_KEY = "66666666-6666-4666-8666-666666666666"
const CREATE_KEY = "77777777-7777-4777-8777-777777777777"
const PLAN_KEY = "88888888-8888-4888-8888-888888888888"
const CONFIRM_KEY = "99999999-9999-4999-8999-999999999999"
const CANCEL_KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

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
    lines: [
      {
        id: LINE_ID,
        version: 1,
        lineNumber: 1,
        assetId: ASSET_ID,
        assetVersion: 8,
        state: "PENDING",
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
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <LogisticsShipmentsPage />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  authState.level = "EDIT"
  shipmentApi.listShipments.mockResolvedValue([
    shipmentDocument(DRAFT_ID, "DRAFT", 0),
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
    expect(screen.queryByRole("button", { name: "Отменить" })).toBeNull()
    expect(screen.getByRole("button", { name: "Обновить" })).not.toBeNull()
  })

  it("uses service projections and hides browser-owned discovery/finalize controls", async () => {
    renderPage()

    await screen.findAllByText("Ждёт подтверждения")
    expect(shipmentApi.listShipments).toHaveBeenCalledWith(
      "shipment-token",
      WAREHOUSE_ID
    )
    expect(
      screen.queryByRole("button", { name: /Завершить отгрузку/i })
    ).toBeNull()
    expect(screen.queryByLabelText(/Компания из справочника/i)).toBeNull()
    expect(screen.queryByLabelText(/Оборудование/i)).toBeNull()
    for (const button of screen.getAllByRole("button", {
      name: "Запустить старый черновик",
    })) {
      expect(button.hasAttribute("disabled")).toBe(true)
      expect(button.getAttribute("title")).toContain("immutable")
    }
  })

  it("retries only the exact immutable plan after a persisted create", async () => {
    const identities = [SLOT_KEY, CREATE_KEY, PLAN_KEY]
    vi.stubGlobal("crypto", {
      randomUUID: () => identities.shift() ?? PLAN_KEY,
    })
    const user = userEvent.setup()
    const draft = shipmentDocument(DRAFT_ID, "DRAFT", 0)
    shipmentApi.createShipment.mockResolvedValue(draft)
    shipmentApi.replaceShipmentPlan
      .mockRejectedValueOnce(new Error("Временная ошибка plan"))
      .mockResolvedValueOnce(shipmentDocument(DRAFT_ID, "PREPARING", 1))
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Создать отгрузку" })
    )
    await user.type(screen.getByLabelText("Компания"), "ООО Тест")
    await user.type(screen.getByLabelText("Водитель"), "Иванов Иван")
    await user.type(screen.getByLabelText("Asset UUID"), ASSET_ID)
    await user.click(
      screen.getByRole("button", { name: "Создать и запустить" })
    )

    await screen.findByText("Временная ошибка plan")
    expect(shipmentApi.createShipment).toHaveBeenCalledOnce()
    expect(shipmentApi.createShipment).toHaveBeenCalledWith({
      accessToken: "shipment-token",
      warehouseId: WAREHOUSE_ID,
      partySnapshot: "ООО Тест",
      driverSnapshot: "Иванов Иван",
      lines: [{ assetId: ASSET_ID, assetVersion: 0, allocations: [] }],
      idempotencyKey: CREATE_KEY,
    })
    const firstPlan = shipmentApi.replaceShipmentPlan.mock.calls[0][0]
    expect(firstPlan).toEqual({
      accessToken: "shipment-token",
      documentId: DRAFT_ID,
      expectedVersion: 0,
      partySnapshot: "ООО Тест",
      driverSnapshot: "Иванов Иван",
      lines: [{ assetId: ASSET_ID, assetVersion: 0, allocations: [] }],
      idempotencyKey: PLAN_KEY,
    })
    expect(screen.getByLabelText("Компания").hasAttribute("disabled")).toBe(
      true
    )
    expect(screen.getByLabelText("Asset UUID").hasAttribute("disabled")).toBe(
      true
    )

    await user.click(
      screen.getByRole("button", { name: "Повторить запуск подготовки" })
    )
    await waitFor(() =>
      expect(shipmentApi.replaceShipmentPlan).toHaveBeenCalledTimes(2)
    )
    expect(shipmentApi.createShipment).toHaveBeenCalledOnce()
    expect(shipmentApi.replaceShipmentPlan.mock.calls[1][0]).toEqual(firstPlan)
  })

  it("rejects duplicate asset IDs before creating a server document", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Создать отгрузку" })
    )
    await user.type(screen.getByLabelText("Компания"), "ООО Тест")
    await user.type(screen.getByLabelText("Водитель"), "Иванов Иван")
    await user.type(screen.getByLabelText("Asset UUID"), ASSET_ID)
    await user.click(screen.getByRole("button", { name: "Добавить строку" }))
    const assetInputs = screen.getAllByLabelText("Asset UUID")
    await user.type(assetInputs[1]!, ASSET_ID)
    await user.click(
      screen.getByRole("button", { name: "Создать и запустить" })
    )

    expect(
      await screen.findByText(/для каждой строки уникальный asset UUID/i)
    ).toBeTruthy()
    expect(shipmentApi.createShipment).not.toHaveBeenCalled()
    expect(shipmentApi.replaceShipmentPlan).not.toHaveBeenCalled()
  })

  it("applies the confirmed server projection before background refetch", async () => {
    const identities = [CONFIRM_KEY]
    vi.stubGlobal("crypto", {
      randomUUID: () => identities.shift() ?? CONFIRM_KEY,
    })
    const awaiting = shipmentDocument(AWAITING_ID, "AWAITING_CONFIRMATION", 5)
    const confirming = shipmentDocument(
      AWAITING_ID,
      "CONFIRMING_PREPARATION",
      6
    )
    shipmentApi.listShipments
      .mockResolvedValueOnce([awaiting])
      .mockResolvedValue([confirming])
    shipmentApi.confirmShipmentPreparation.mockResolvedValue(confirming)
    const user = userEvent.setup()
    renderPage()

    const confirmButtons = await screen.findAllByRole("button", {
      name: "Подтвердить подготовку",
    })
    await user.click(confirmButtons[0]!)
    await waitFor(() =>
      expect(shipmentApi.confirmShipmentPreparation).toHaveBeenCalledWith({
        accessToken: "shipment-token",
        documentId: AWAITING_ID,
        expectedVersion: 5,
        idempotencyKey: CONFIRM_KEY,
      })
    )
    await screen.findAllByText("Подтверждается")
    expect(
      screen.queryByRole("button", { name: "Подтвердить подготовку" })
    ).toBeNull()
    expect(screen.queryByRole("button", { name: "Отменить" })).toBeNull()
  })

  it("retries a transient cancellation failure with the same version and key", async () => {
    vi.stubGlobal("crypto", { randomUUID: () => CANCEL_KEY })
    const preparing = shipmentDocument(DRAFT_ID, "PREPARING", 3)
    const cancelling = shipmentDocument(DRAFT_ID, "CANCELLING", 4)
    shipmentApi.listShipments
      .mockResolvedValueOnce([preparing])
      .mockResolvedValue([cancelling])
    shipmentApi.cancelShipment
      .mockRejectedValueOnce(new Error("Сеть временно недоступна"))
      .mockResolvedValueOnce(cancelling)
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Отменить" }))[0]!
    )
    await user.click(
      within(screen.getByRole("alertdialog")).getByRole("button", {
        name: "Отменить",
      })
    )
    expect(await screen.findByText("Сеть временно недоступна")).toBeTruthy()
    const firstCommand = shipmentApi.cancelShipment.mock.calls[0]![0]
    expect(firstCommand).toEqual({
      accessToken: "shipment-token",
      documentId: DRAFT_ID,
      expectedVersion: 3,
      idempotencyKey: CANCEL_KEY,
    })

    await user.click(screen.getAllByRole("button", { name: "Отменить" })[0]!)
    await user.click(
      within(screen.getByRole("alertdialog")).getByRole("button", {
        name: "Отменить",
      })
    )
    await waitFor(() =>
      expect(shipmentApi.cancelShipment).toHaveBeenCalledTimes(2)
    )
    expect(shipmentApi.cancelShipment.mock.calls[1]![0]).toEqual(firstCommand)
    await screen.findAllByText("Отменяется")
  })

  it("refreshes a cancellation conflict before allowing a new command", async () => {
    const nextCancelKey = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    const identities = [CANCEL_KEY, nextCancelKey]
    vi.stubGlobal("crypto", {
      randomUUID: () => identities.shift() ?? nextCancelKey,
    })
    const preparingV3 = shipmentDocument(DRAFT_ID, "PREPARING", 3)
    const preparingV4 = shipmentDocument(DRAFT_ID, "PREPARING", 4)
    const cancelling = shipmentDocument(DRAFT_ID, "CANCELLING", 5)
    shipmentApi.listShipments
      .mockResolvedValueOnce([preparingV3])
      .mockResolvedValue([preparingV4])
    shipmentApi.cancelShipment
      .mockRejectedValueOnce(new ApiError("Версия документа устарела", 409))
      .mockResolvedValueOnce(cancelling)
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Отменить" }))[0]!
    )
    await user.click(
      within(screen.getByRole("alertdialog")).getByRole("button", {
        name: "Отменить",
      })
    )
    expect(await screen.findByText("Версия документа устарела")).toBeTruthy()
    await waitFor(() =>
      expect(shipmentApi.listShipments).toHaveBeenCalledTimes(2)
    )

    await user.click(screen.getAllByRole("button", { name: "Отменить" })[0]!)
    await user.click(
      within(screen.getByRole("alertdialog")).getByRole("button", {
        name: "Отменить",
      })
    )
    await waitFor(() =>
      expect(shipmentApi.cancelShipment).toHaveBeenCalledTimes(2)
    )
    expect(shipmentApi.cancelShipment.mock.calls[0]![0]).toMatchObject({
      expectedVersion: 3,
      idempotencyKey: CANCEL_KEY,
    })
    expect(shipmentApi.cancelShipment.mock.calls[1]![0]).toMatchObject({
      expectedVersion: 4,
      idempotencyKey: nextCancelKey,
    })
  })
})
