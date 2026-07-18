import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { ReturnDocument } from "@/features/logistics/returns/model"

const returnApi = vi.hoisted(() => ({
  listReturns: vi.fn(),
  createReturn: vi.fn(),
  registerReturn: vi.fn(),
  requestReturnEstimate: vi.fn(),
}))

vi.mock("@/features/logistics/returns/api", () => ({
  RETURNS_QUERY_KEY: ["logistics", "returns"],
  ...returnApi,
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: "return-token" }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouseId: "11111111-1111-4111-8111-111111111111",
  }),
}))

import { LogisticsReturnsPage } from "@/features/logistics/logistics-returns-page"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const DRAFT_ID = "22222222-2222-4222-8222-222222222222"
const INSPECTION_ID = "33333333-3333-4333-8333-333333333333"
const LINE_ID = "44444444-4444-4444-8444-444444444444"
const ASSET_ID = "55555555-5555-4555-8555-555555555555"
const EQUIPMENT_ID = "66666666-6666-4666-8666-666666666666"
const IDEMPOTENCY_KEY = "77777777-7777-4777-8777-777777777777"

function returnDocument(
  id: string,
  state: ReturnDocument["state"],
  version: number
): ReturnDocument {
  return {
    id,
    version,
    documentType: "RETURN",
    state,
    warehouseId: WAREHOUSE_ID,
    destinationWarehouseId: null,
    partySnapshot: null,
    driverSnapshot: null,
    lines: [
      {
        id: LINE_ID,
        version: 1,
        lineNumber: 1,
        assetId: ASSET_ID,
        assetVersion: 8,
        state: "PENDING",
        tenantSnapshot: "ООО Тест",
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
        <LogisticsReturnsPage />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  vi.stubGlobal("crypto", { randomUUID: () => IDEMPOTENCY_KEY })
  returnApi.listReturns.mockResolvedValue([
    returnDocument(DRAFT_ID, "DRAFT", 2),
    returnDocument(INSPECTION_ID, "INSPECTION_REQUIRED", 4),
  ])
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe("LogisticsReturnsPage", () => {
  it("uses service projections and hides unsupported browser-owned controls", async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findAllByText("Требуется осмотр")
    expect(returnApi.listReturns).toHaveBeenCalledWith(
      "return-token",
      WAREHOUSE_ID
    )
    expect(
      screen.queryByRole("button", { name: /Добавить бытовку из аренды/i })
    ).toBeNull()
    expect(screen.queryByRole("button", { name: /Редактировать/i })).toBeNull()
    for (const button of screen.getAllByRole("button", {
      name: "Принять без повреждений",
    })) {
      expect(button.hasAttribute("disabled")).toBe(true)
      expect(button.getAttribute("title")).toContain("media API")
    }

    await user.click(screen.getByRole("button", { name: "Создать возврат" }))
    expect(
      screen.getByRole("heading", { name: "Создать документ возврата" })
    ).not.toBeNull()
    expect(screen.getByLabelText("Asset UUID")).not.toBeNull()
    expect(screen.getByLabelText("Текущая версия asset")).not.toBeNull()
    expect(screen.getByLabelText("Снимок контрагента")).not.toBeNull()
  })

  it("sends server versions and stable command identities for transitions", async () => {
    const user = userEvent.setup()
    returnApi.registerReturn.mockResolvedValue(
      returnDocument(DRAFT_ID, "REGISTERING", 3)
    )
    returnApi.requestReturnEstimate.mockResolvedValue(
      returnDocument(INSPECTION_ID, "ESTIMATE_PENDING", 5)
    )
    renderPage()

    const registerButtons = await screen.findAllByRole("button", {
      name: "Зарегистрировать",
    })
    await user.click(registerButtons[0]!)
    await waitFor(() =>
      expect(returnApi.registerReturn).toHaveBeenCalledWith({
        accessToken: "return-token",
        documentId: DRAFT_ID,
        expectedVersion: 2,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    )

    const estimateButtons = screen.getAllByRole("button", {
      name: "Запросить смету · строка 1",
    })
    await user.click(estimateButtons[0]!)
    await user.type(screen.getByLabelText("Equipment UUID"), EQUIPMENT_ID)
    await user.click(screen.getByRole("button", { name: "Запросить" }))

    await waitFor(() =>
      expect(returnApi.requestReturnEstimate).toHaveBeenCalledWith({
        accessToken: "return-token",
        documentId: INSPECTION_ID,
        expectedVersion: 4,
        idempotencyKey: IDEMPOTENCY_KEY,
        lines: [
          {
            lineId: LINE_ID,
            shortages: [{ equipmentId: EQUIPMENT_ID, missingQuantity: 1 }],
          },
        ],
      })
    )
  })
})
