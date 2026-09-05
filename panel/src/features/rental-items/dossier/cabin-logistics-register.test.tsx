import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { ApiError } from "@/lib/api-client"
import type { CurrentUser } from "@/features/auth/auth-model"
import { CabinLogisticsRegister } from "./cabin-logistics-register"

const state = vi.hoisted(() => ({
  token: "token" as string | null,
  user: null as CurrentUser | null,
  listWarehouses: vi.fn(),
  getWarehouse: vi.fn(),
  listShipments: vi.fn(),
  listReturns: vi.fn(),
  selectWarehouse: vi.fn(),
  details: vi.fn(() => null),
}))
vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: state.token, currentUser: state.user }),
}))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({ setSelectedWarehouseId: state.selectWarehouse }),
}))
vi.mock("./logistics-history-details", () => ({
  LogisticsHistoryDetails: state.details,
}))
vi.mock("@/api/warehouse-api", () => ({
  listWarehouses: state.listWarehouses,
  getWarehouse: state.getWarehouse,
}))
vi.mock("@/features/logistics/shipments/api", () => ({
  SHIPMENTS_QUERY_KEY: ["shipments"],
  listShipments: state.listShipments,
}))
vi.mock("@/features/logistics/returns/api", () => ({
  RETURNS_QUERY_KEY: ["returns"],
  listReturns: state.listReturns,
}))

const warehouses = [
  { id: "warehouse-1", name: "Москва", lifecycleState: "ACTIVE" },
  { id: "warehouse-2", name: "Закрытый склад", lifecycleState: "INACTIVE" },
]
function document(kind: "SHIPMENT" | "RETURN", warehouseId = "warehouse-1") {
  return {
    id: `document-${warehouseId}`,
    version: 8,
    documentType: kind,
    warehouseId,
    state: kind === "SHIPMENT" ? "SHIPPED" : "ACCEPTED",
    partySnapshot: "ООО Прежний клиент",
    driverSnapshot: "Иван Петров",
    scheduledDate: "2025-01-15",
    rentalOrderId: "past-order",
    lines: [
      {
        assetId: "cabin",
        state: kind === "SHIPMENT" ? "DEPARTED" : "ARRIVED",
        tenantSnapshot: null,
      },
    ],
    createdAt: "2025-01-14T10:00:00Z",
    updatedAt: "2025-01-15T12:00:00Z",
  }
}
function show(kind: "SHIPMENT" | "RETURN") {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <CabinLogisticsRegister cabinId="cabin" kind={kind} />
      </QueryClientProvider>
    </MemoryRouter>
  )
}
beforeEach(() => {
  vi.resetAllMocks()
  state.token = "token"
  state.user = {
    id: "viewer",
    username: "viewer",
    displayName: "Наблюдатель",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole: "VIEWER",
    rentalAccess: false,
    warehouseAccessAll: false,
    warehouseAccesses: warehouses.map((warehouse) => ({
      warehouseId: warehouse.id,
      level: "VIEW",
    })),
  }
  state.getWarehouse.mockImplementation(async (_token, id) =>
    warehouses.find((warehouse) => warehouse.id === id)
  )
  state.listWarehouses.mockResolvedValue(warehouses)
  state.listShipments.mockResolvedValue([])
  state.listReturns.mockResolvedValue([])
})
afterEach(cleanup)

describe("CabinLogisticsRegister", () => {
  it.each(["SHIPMENT", "RETURN"] as const)(
    "shows permanent %s documents from authorized inactive warehouses",
    async (kind) => {
      const list = kind === "SHIPMENT" ? state.listShipments : state.listReturns
      list.mockImplementation(async (_token, warehouseId) => [
        document(kind, warehouseId),
      ])
      show(kind)
      await screen.findByText(/ООО Прежний клиент · Закрытый склад/)
      expect(list).toHaveBeenCalledWith(
        "token",
        "warehouse-1",
        undefined,
        "cabin"
      )
      expect(list).toHaveBeenCalledWith(
        "token",
        "warehouse-2",
        undefined,
        "cabin"
      )
      expect(state.listWarehouses).not.toHaveBeenCalled()
      const user = userEvent.setup()
      const triggers = screen.getAllByRole("button", { name: /Подробнее:/ })
      expect(state.details).not.toHaveBeenCalled()
      await user.click(triggers[0])
      expect(state.details).toHaveBeenCalledWith(
        expect.objectContaining({ cabinId: "cabin", documentVersion: 8 }),
        undefined
      )
      expect(screen.getByText("Иван Петров")).toBeTruthy()
      expect(
        screen.getByText(kind === "SHIPMENT" ? "Выехала" : "Прибыла")
      ).toBeTruthy()
      expect(
        screen.getByRole("link", { name: "Открыть заказ" }).getAttribute("href")
      ).toBe("/orders/past-order")
    }
  )

  it("keeps cancelled documents and opens a dated document in its own warehouse", async () => {
    state.listShipments.mockImplementation(async (_token, id) =>
      id === "warehouse-1"
        ? [{ ...document("SHIPMENT"), state: "CANCELLED" }]
        : []
    )
    show("SHIPMENT")
    await screen.findByText("Отменено")
    const user = userEvent.setup()
    await user.click(screen.getByRole("button", { name: /Подробнее:/ }))
    const link = screen.getByRole("link", { name: "Открыть документ" })
    expect(link.getAttribute("href")).toBe(
      "/logistics/shipments?shipmentId=document-warehouse-1&date=2025-01-15"
    )
    await user.click(link)
    expect(state.selectWarehouse).toHaveBeenCalledWith("warehouse-1")
  })

  it("does not show an empty or partial history on an authorization error and allows retry", async () => {
    state.listReturns.mockRejectedValue(
      new ApiError("Нет доступа к истории", 403)
    )
    show("RETURN")
    await screen.findByRole("alert")
    expect(screen.queryByText("Документов пока нет.")).toBeNull()
    state.listReturns.mockResolvedValue([])
    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: "Повторить загрузку" }))
    await screen.findByText("Документов пока нет.")
  })

  it("does not read warehouses outside the explicit grants", async () => {
    state.user!.warehouseAccesses = [
      { warehouseId: "warehouse-1", level: "VIEW" },
    ]
    show("SHIPMENT")
    await screen.findByText("Документов пока нет.")
    expect(state.getWarehouse).toHaveBeenCalledTimes(1)
    expect(state.listShipments).toHaveBeenCalledTimes(1)
  })

  it("requests the inactive directory only for a system administrator", async () => {
    state.user!.warehouseAccessAll = true
    state.user!.globalRole = "SYSTEM_ADMIN"
    show("SHIPMENT")
    await screen.findByText("Документов пока нет.")
    expect(state.listWarehouses).toHaveBeenCalledWith("token", true)
  })

  it("does not read protected history without a token", () => {
    state.token = null
    show("RETURN")
    expect(screen.getByRole("alert").textContent).toContain(
      "требуется авторизация"
    )
    expect(state.getWarehouse).not.toHaveBeenCalled()
    expect(state.listReturns).not.toHaveBeenCalled()
  })
})
