import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, useLocation } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const auth = vi.hoisted(() => ({ useAuth: vi.fn() }))
const warehouse = vi.hoisted(() => ({ useWarehouse: vi.fn() }))
const warehouseAccess = vi.hoisted(() => ({ hasWarehouseAccess: vi.fn() }))
const viewport = vi.hoisted(() => ({ isMobile: false }))
const estimatesApi = vi.hoisted(() => ({
  getRepairEstimate: vi.fn(),
  listReturnEstimateSources: vi.fn(),
  listRepairEstimates: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({ useAuth: auth.useAuth }))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: warehouse.useWarehouse,
}))
vi.mock("@/features/auth/warehouse-access", () => ({
  hasWarehouseAccess: warehouseAccess.hasWarehouseAccess,
}))
vi.mock("@/hooks/use-workspace-back", () => ({
  useWorkspaceBack: () => vi.fn(),
  workspaceEntryNavigationOptions: {},
}))
vi.mock("@/hooks/use-mobile", () => ({
  useIsMobile: () => viewport.isMobile,
}))
vi.mock("@/features/repair-estimates/api/repair-estimates-api", () => ({
  getRepairEstimate: estimatesApi.getRepairEstimate,
  listReturnEstimateSources: estimatesApi.listReturnEstimateSources,
  listRepairEstimates: estimatesApi.listRepairEstimates,
  repairEstimateDetailQueryKey: (
    warehouseId: string,
    estimateId: string | null
  ) => ["repair-estimates", "detail", warehouseId, estimateId],
  repairEstimateListQueryKey: (warehouseId: string) => [
    "repair-estimates",
    "list",
    warehouseId,
  ],
  returnEstimateSourcesQueryKey: (
    warehouseId: string,
    returnId: string | null
  ) => ["repair-estimates", "return-sources", warehouseId, returnId],
}))
vi.mock("@/features/rental-items/dossier/actor/actor-display-api", () => ({
  listDossierActorDisplays: vi.fn(),
}))
vi.mock("@/features/repair-estimates/repair-estimate-editor-workspace", () => ({
  RepairEstimateEditorWorkspace: () => (
    <header data-slot="page-toolbar" aria-label="Редактор сметы">
      <button type="button">Назад</button>
      <span data-testid="repair-estimate-editor">Редактор сметы</span>
    </header>
  ),
}))

import { RepairEstimatesPage } from "@/features/repair-estimates/repair-estimates-page"

const warehouseId = "11111111-1111-4111-8111-111111111111"

function renderPage(initialEntry: string) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <QueryClientProvider client={queryClient}>
        <RepairEstimatesPage />
        <LocationDisplay />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

function LocationDisplay() {
  const location = useLocation()
  return (
    <output data-testid="location">{`${location.pathname}${location.search}`}</output>
  )
}

beforeEach(() => {
  auth.useAuth.mockReturnValue({
    accessToken: "maintenance-token",
    currentUser: { id: "operator" },
  })
  warehouse.useWarehouse.mockReturnValue({ selectedWarehouseId: warehouseId })
  warehouseAccess.hasWarehouseAccess.mockReturnValue(true)
  viewport.isMobile = false
  estimatesApi.listRepairEstimates.mockResolvedValue([])
  estimatesApi.listReturnEstimateSources.mockResolvedValue([])
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("RepairEstimatesPage editor toolbar", () => {
  it("does not add a second Back toolbar around the actual editor", async () => {
    renderPage("/estimates?create=1")

    expect(await screen.findByRole("button", { name: "Назад" })).toBeTruthy()
    expect(screen.getAllByRole("button", { name: "Назад" })).toHaveLength(1)
    expect(
      document.querySelectorAll('[data-slot="page-toolbar"]')
    ).toHaveLength(1)
  })

  it("keeps Back available when creating an estimate is unavailable", async () => {
    warehouseAccess.hasWarehouseAccess.mockReturnValue(false)

    renderPage("/estimates?create=1")

    expect(await screen.findByText("Создание сметы недоступно")).toBeTruthy()
    expect(screen.getAllByRole("button", { name: "Назад" })).toHaveLength(1)
    expect(document.querySelector('[data-slot="page-toolbar"]')).not.toBeNull()
  })

  it("opens the mobile-app warning instead of navigating to the create editor", async () => {
    viewport.isMobile = true
    const user = userEvent.setup()

    renderPage("/estimates")

    await user.click(
      await screen.findByRole("button", { name: "Создать смету" })
    )

    expect(
      await screen.findByRole("dialog", {
        name: "Создание сметы доступно в мобильном приложении",
      })
    ).toBeTruthy()
    expect(
      screen.getByRole("link", { name: "Скачать приложение" })
    ).toBeTruthy()
    expect(screen.queryByTestId("repair-estimate-editor")).toBeNull()
    expect(screen.getByTestId("location").textContent).toBe("/estimates")
  })

  it("blocks a mobile create deep link and returns to the estimate list on dismissal", async () => {
    viewport.isMobile = true
    const user = userEvent.setup()

    renderPage("/estimates?create=1&source=return")

    expect(
      await screen.findByRole("dialog", {
        name: "Создание сметы доступно в мобильном приложении",
      })
    ).toBeTruthy()
    expect(screen.queryByTestId("repair-estimate-editor")).toBeNull()
    expect(screen.getByTestId("location").textContent).toBe(
      "/estimates?create=1&source=return"
    )

    await user.click(screen.getByRole("button", { name: "Понятно" }))

    await waitFor(() => {
      expect(screen.getByTestId("location").textContent).toBe(
        "/estimates?source=return"
      )
    })
    expect(screen.queryByRole("dialog")).toBeNull()
  })

  it("keeps an existing estimate available for viewing on mobile", async () => {
    viewport.isMobile = true
    estimatesApi.getRepairEstimate.mockResolvedValue({
      id: "estimate-1",
      authorName: "Иванов Иван",
    })

    renderPage("/estimates?estimateId=estimate-1")

    expect(await screen.findByTestId("repair-estimate-editor")).toBeTruthy()
    expect(screen.queryByRole("dialog")).toBeNull()
  })

  it("opens the only created return estimate directly instead of a shared workspace", async () => {
    const returnId = "22222222-2222-4222-8222-222222222222"
    const estimateId = "33333333-3333-4333-8333-333333333333"
    estimatesApi.listReturnEstimateSources.mockResolvedValue([
      {
        returnId,
        lineId: "44444444-4444-4444-8444-444444444444",
        warehouseId,
        rentalItemId: "55555555-5555-4555-8555-555555555555",
        estimateId,
      },
    ])
    estimatesApi.getRepairEstimate.mockResolvedValue({
      id: estimateId,
      authorName: "Иванов Иван",
    })

    renderPage(`/estimates?returnId=${returnId}&returnLineCount=1`)

    await waitFor(() =>
      expect(screen.getByTestId("location").textContent).toBe(
        `/estimates?estimateId=${estimateId}`
      )
    )
    expect(screen.queryByText("Новые сметы из возврата")).toBeNull()
  })

  it("keeps several return cabins as separate estimates", async () => {
    const returnId = "22222222-2222-4222-8222-222222222222"
    const firstEstimateId = "33333333-3333-4333-8333-333333333333"
    const secondEstimateId = "44444444-4444-4444-8444-444444444444"
    estimatesApi.listReturnEstimateSources.mockResolvedValue([
      {
        returnId,
        lineId: "55555555-5555-4555-8555-555555555555",
        warehouseId,
        rentalItemId: "66666666-6666-4666-8666-666666666666",
        estimateId: firstEstimateId,
      },
      {
        returnId,
        lineId: "77777777-7777-4777-8777-777777777777",
        warehouseId,
        rentalItemId: "88888888-8888-4888-888888888888",
        estimateId: secondEstimateId,
      },
    ])
    const user = userEvent.setup()

    renderPage(`/estimates?returnId=${returnId}&returnLineCount=2`)

    expect(await screen.findByText("Новые сметы из возврата")).toBeTruthy()
    await waitFor(() =>
      expect(screen.getAllByText(/Отдельная смета/)).toHaveLength(2)
    )
    await user.click(
      screen.getAllByRole("button", { name: "Открыть смету" })[1]!
    )

    expect(screen.getByTestId("location").textContent).toBe(
      `/estimates?estimateId=${secondEstimateId}`
    )
  })
})
