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
  listShipments: vi.fn(),
  createShipment: vi.fn(),
  replaceShipmentPlan: vi.fn(),
  confirmShipmentPreparation: vi.fn(),
  cancelShipment: vi.fn(),
}))

vi.mock("@/features/logistics/shipments/api", () => ({
  SHIPMENTS_QUERY_KEY: ["logistics", "shipments"],
  ...shipmentApi,
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: "shipment-token" }),
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

  it("confirms and cancels with server versions while the service owns effects", async () => {
    const identities = [CONFIRM_KEY, CANCEL_KEY]
    vi.stubGlobal("crypto", {
      randomUUID: () => identities.shift() ?? CANCEL_KEY,
    })
    const awaiting = shipmentDocument(AWAITING_ID, "AWAITING_CONFIRMATION", 5)
    shipmentApi.listShipments.mockResolvedValue([awaiting])
    shipmentApi.confirmShipmentPreparation.mockResolvedValue(
      shipmentDocument(AWAITING_ID, "CONFIRMING_PREPARATION", 6)
    )
    shipmentApi.cancelShipment.mockResolvedValue(
      shipmentDocument(AWAITING_ID, "CANCELLING", 6)
    )
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

    const cancelButtons = screen.getAllByRole("button", { name: "Отменить" })
    await user.click(cancelButtons[0]!)
    const dialog = screen.getByRole("alertdialog")
    await user.click(within(dialog).getByRole("button", { name: "Отменить" }))
    await waitFor(() =>
      expect(shipmentApi.cancelShipment).toHaveBeenCalledWith({
        accessToken: "shipment-token",
        documentId: AWAITING_ID,
        expectedVersion: 5,
        idempotencyKey: CANCEL_KEY,
      })
    )
  })
})
