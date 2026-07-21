import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

import type {
  CurrentUser,
  WarehouseAccessLevel,
} from "@/features/auth/auth-model"
import type { RepairEstimateCatalogVersionDto } from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const mocks = vi.hoisted(() => ({
  level: "VIEW" as WarehouseAccessLevel,
  listVersions: vi.fn(),
  getCatalogCanvas: vi.fn(),
  bootstrapCatalog: vi.fn(),
  forkCatalog: vi.fn(),
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
      getRepairEstimateCatalogCanvas: mocks.getCatalogCanvas,
      bootstrapRepairEstimateCatalog: mocks.bootstrapCatalog,
      forkRepairEstimateCatalog: mocks.forkCatalog,
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

type VersionsFixture =
  | RepairEstimateCatalogVersionDto[]
  | Error
  | Promise<RepairEstimateCatalogVersionDto[]>

function renderPage(
  level: WarehouseAccessLevel,
  versions: VersionsFixture = [version]
) {
  mocks.level = level
  if (versions instanceof Error) {
    mocks.listVersions.mockRejectedValue(versions)
  } else if (Array.isArray(versions)) {
    mocks.listVersions.mockResolvedValue(versions)
  } else {
    mocks.listVersions.mockImplementation(() => versions)
  }
  mocks.getCatalogCanvas.mockResolvedValue({
    catalogVersion: version,
    categories: [],
    nodes: [],
    links: [],
  })

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
  it("opens a catalog section through its navigation button", async () => {
    const user = userEvent.setup()
    renderPage("VIEW")

    const drilldown = await screen.findByRole("button", {
      name: "Конструктор каталога смет",
    })
    await user.click(drilldown)

    expect(await screen.findByRole("button", { name: "Назад" })).toBeTruthy()
    await waitFor(() => {
      expect(mocks.getCatalogCanvas).toHaveBeenCalledWith({
        accessToken: "maintenance-token",
        warehouseId: WAREHOUSE_ID,
        catalogVersionId: version.id,
      })
    })
  })

  it("shows a loading state instead of disabled catalog navigation", async () => {
    renderPage(
      "MANAGE",
      new Promise<RepairEstimateCatalogVersionDto[]>(() => undefined)
    )

    const status = await screen.findByRole("status")

    expect(status.textContent).toContain(
      "Загружаем доступную версию каталога смет"
    )
    expect(
      screen.queryByRole("button", { name: "Конструктор каталога смет" })
    ).toBeNull()
  })

  it("shows an actionable empty state when the warehouse has no catalog version", async () => {
    renderPage("MANAGE", [])

    await screen.findByText("Каталог смет ещё не создан")
    const status = screen.getByRole("status")

    expect(status.textContent).toContain("Каталог смет ещё не создан")
    expect(button("Создать первый каталог").disabled).toBe(false)
    expect(
      screen.queryByRole("button", { name: "Конструктор каталога смет" })
    ).toBeNull()
  })

  it("shows the version error and a retry action instead of dead navigation", async () => {
    renderPage("MANAGE", new Error("maintenance-service недоступен"))

    const alert = await screen.findByRole("alert")

    expect(alert.textContent).toContain("Не удалось загрузить каталог смет")
    expect(alert.textContent).toContain("maintenance-service недоступен")
    expect(button("Повторить загрузку").disabled).toBe(false)
    expect(
      screen.queryByRole("button", { name: "Конструктор каталога смет" })
    ).toBeNull()
  })

  it("keeps catalog reads available but disables commands for VIEW", async () => {
    renderPage("VIEW")

    const drilldown = (await screen.findByRole("button", {
      name: "Конструктор каталога смет",
    })) as HTMLButtonElement

    expect(mocks.listVersions).toHaveBeenCalledWith(
      "maintenance-token",
      WAREHOUSE_ID
    )
    expect(drilldown.disabled).toBe(false)
    expect(button("Создать базовый каталог").disabled).toBe(true)
    expect(button("Создать черновик").disabled).toBe(true)
    expect(button("Активировать").disabled).toBe(true)
  })

  it("enables draft drilldown but not management commands for EDIT", async () => {
    renderPage("EDIT")

    const drilldown = (await screen.findByRole("button", {
      name: "Конструктор каталога смет",
    })) as HTMLButtonElement
    const furniture = button("Мебель")

    expect(drilldown.disabled).toBe(false)
    expect(furniture.disabled).toBe(false)
    expect(button("Создать базовый каталог").disabled).toBe(true)
    expect(button("Создать черновик").disabled).toBe(true)
    expect(button("Активировать").disabled).toBe(true)
  })

  it("enables draft editing, bootstrap and activation for MANAGE", async () => {
    renderPage("MANAGE")

    const drilldown = (await screen.findByRole("button", {
      name: "Конструктор каталога смет",
    })) as HTMLButtonElement

    expect(drilldown.disabled).toBe(false)
    expect(button("Создать базовый каталог").disabled).toBe(false)
    expect(button("Создать черновик").disabled).toBe(true)
    expect(button("Активировать").disabled).toBe(false)
  })

  it.each(["ACTIVE", "SUPERSEDED"] as const)(
    "allows MANAGE to fork a read-only %s version",
    async (lifecycle) => {
      renderPage("MANAGE", [
        {
          ...version,
          lifecycle,
          activatedAt: "2026-07-18T09:00:00Z",
        },
      ])

      const drilldown = (await screen.findByRole("button", {
        name: "Конструктор каталога смет",
      })) as HTMLButtonElement

      expect(drilldown.disabled).toBe(false)
      expect(button("Создать базовый каталог").disabled).toBe(false)
      expect(button("Создать черновик").disabled).toBe(false)
      expect(button("Активировать").disabled).toBe(true)
    }
  )
})
