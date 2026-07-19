import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type {
  CurrentUser,
  WarehouseAccessLevel,
} from "@/features/auth/auth-model"
import type { RepairEstimateCatalogVersionDto } from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const mocks = vi.hoisted(() => ({
  level: "VIEW" as WarehouseAccessLevel,
  listVersions: vi.fn(),
  importCatalog: vi.fn(),
  activateCatalog: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "maintenance-token",
    currentUser: currentUser(mocks.level),
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({ selectedWarehouseId: WAREHOUSE_ID }),
}))

vi.mock(
  "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store",
  async () => {
    const actual = await vi.importActual<
      typeof import("@/features/settings/estimates-repairs/api/repair-estimate-catalog-store")
    >("@/features/settings/estimates-repairs/api/repair-estimate-catalog-store")

    return {
      ...actual,
      listRepairEstimateCatalogVersions: mocks.listVersions,
      importRepairEstimateCatalog: mocks.importCatalog,
      activateRepairEstimateCatalog: mocks.activateCatalog,
    }
  }
)

import { EstimatesRepairsSettingsPage } from "@/features/settings/estimates-repairs/estimates-repairs-settings-page"

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const version: RepairEstimateCatalogVersionDto = {
  id: "00000000-0000-4000-8000-000000000002",
  warehouseId: WAREHOUSE_ID,
  version: 3,
  lifecycle: "DRAFT",
  sourceSha256: "a".repeat(64),
  nodeCount: 0,
  linkCount: 0,
  valid: true,
  createdAt: "2026-07-18T08:00:00Z",
  activatedAt: null,
}

function currentUser(level: WarehouseAccessLevel): CurrentUser {
  return {
    id: "operator-1",
    username: "operator",
    displayName: "Оператор",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole: "WAREHOUSE_MANAGER",
    warehouseAccessAll: false,
    warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level }],
  }
}

function renderPage(level: WarehouseAccessLevel) {
  mocks.level = level
  mocks.listVersions.mockResolvedValue([version])

  return render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
          },
        })
      }
    >
      <EstimatesRepairsSettingsPage />
    </QueryClientProvider>
  )
}

function button(name: string) {
  return screen.getByRole("button", { name }) as HTMLButtonElement
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("maintenance catalog warehouse access", () => {
  it("keeps catalog reads available but disables commands for VIEW", async () => {
    renderPage("VIEW")

    const drilldown = (await screen.findByRole("button", {
      name: "Конструктор каталога смет",
    })) as HTMLButtonElement

    expect(mocks.listVersions).toHaveBeenCalledWith(
      "maintenance-token",
      WAREHOUSE_ID
    )
    expect(drilldown.disabled).toBe(true)
    expect(button("Импортировать JSON").disabled).toBe(true)
    expect(button("Активировать").disabled).toBe(true)
  })

  it("enables draft drilldown but not management commands for EDIT", async () => {
    renderPage("EDIT")

    const drilldown = (await screen.findByRole("button", {
      name: "Конструктор каталога смет",
    })) as HTMLButtonElement

    expect(drilldown.disabled).toBe(false)
    expect(button("Импортировать JSON").disabled).toBe(true)
    expect(button("Активировать").disabled).toBe(true)
  })

  it("enables drilldown, import and activation for MANAGE", async () => {
    renderPage("MANAGE")

    const drilldown = (await screen.findByRole("button", {
      name: "Конструктор каталога смет",
    })) as HTMLButtonElement

    expect(drilldown.disabled).toBe(false)
    expect(button("Импортировать JSON").disabled).toBe(false)
    expect(button("Активировать").disabled).toBe(false)
  })
})
