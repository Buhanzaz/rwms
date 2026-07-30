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
})
