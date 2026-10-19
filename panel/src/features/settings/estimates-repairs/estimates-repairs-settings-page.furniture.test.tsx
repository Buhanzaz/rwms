import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { MemoryRouter } from "react-router-dom"

import type { CurrentUser } from "@/features/auth/auth-model"
import type {
  RepairEstimateCatalogNodeDto,
  RepairEstimateCatalogSectionDto,
  RepairEstimateCatalogVersionDto,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const mocks = vi.hoisted(() => ({
  getCatalog: vi.fn(),
  getFurnitureCatalog: vi.fn(),
  saveFurniture: vi.fn(),
  deleteFurniture: vi.fn(),
  getEquipmentItems: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "maintenance-token",
    currentUser,
  }),
}))

vi.mock(
  "@/features/settings/estimates-repairs/estimate-creation-window-settings-card",
  () => ({ EstimateCreationWindowSettingsCard: () => null })
)

vi.mock("@/api/equipment-api", () => ({
  getEquipmentItems: mocks.getEquipmentItems,
}))

vi.mock(
  "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store",
  async () => {
    const actual = await vi.importActual<
      typeof import("@/features/settings/estimates-repairs/api/repair-estimate-catalog-store")
    >("@/features/settings/estimates-repairs/api/repair-estimate-catalog-store")

    return {
      ...actual,
      getCurrentRepairEstimateCatalog: mocks.getCatalog,
    }
  }
)

vi.mock(
  "@/features/settings/estimates-repairs/api/repair-estimate-furniture-catalog-settings-api",
  () => ({
    getRepairEstimateFurnitureCatalogSettings: async () => ({
      id: "repair-estimate-catalog-furniture",
      title: "Мебель",
      sectionType: "MATERIAL",
      categoryScope: "FURNITURE_ONLY",
      order: 40,
    }),
    getRepairEstimateFurnitureCatalog: mocks.getFurnitureCatalog,
    saveRepairEstimateFurnitureCatalogItem: mocks.saveFurniture,
    deleteRepairEstimateFurnitureCatalogItem: mocks.deleteFurniture,
  })
)

import { EstimatesRepairsSettingsPage } from "@/features/settings/estimates-repairs/estimates-repairs-settings-page"

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const VERSION_ID = "00000000-0000-4000-8000-000000000002"
const CATEGORY_ID = "00000000-0000-4000-8000-000000000003"
const FURNITURE_ID = "00000000-0000-4000-8000-000000000004"

const currentUser: CurrentUser = {
  id: "manager-1",
  username: "manager",
  displayName: "Менеджер",
  firstName: null,
  lastName: null,
  email: null,
  principalType: "USER",
  globalRole: "WMS_ADMIN",
  rentalAccess: false,
  warehouseAccessAll: false,
  warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level: "MANAGE" }],
}

const version: RepairEstimateCatalogVersionDto = {
  id: VERSION_ID,
  warehouseId: WAREHOUSE_ID,
  version: 3,
  lifecycle: "ACTIVE",
  sourceSha256: "a".repeat(64),
  nodeCount: 1,
  linkCount: 0,
  valid: true,
  createdAt: "2026-07-20T08:00:00Z",
  activatedAt: "2026-07-20T08:05:00Z",
}

const furnitureCategory: RepairEstimateCatalogNodeDto = {
  id: CATEGORY_ID,
  catalogVersionId: VERSION_ID,
  name: "Мебельная группа",
  nodeType: "CATEGORY",
  parentId: null,
  active: true,
  unit: null,
  unitPrice: null,
  durationMinutes: null,
  showInMainMenu: true,
  routeQueueKind: null,
  queueDefinitionId: null,
  routing: null,
  includeInEstimate: false,
  commonItem: false,
  furnitureCategory: true,
  furnitureEquipment: null,
  forcesCapitalRepair: false,
  characteristic: null,
  canvasX: null,
  canvasY: null,
  comment: null,
}

const furnitureSection: RepairEstimateCatalogSectionDto = {
  kind: "furniture",
  title: "Мебель",
  sectionType: "MATERIAL",
  categories: [furnitureCategory],
  nodes: [furnitureCategory],
  links: [],
}

const furnitureEquipment = {
  equipmentId: "00000000-0000-4000-8000-000000000005",
  equipmentName: "Стул",
  equipmentVersion: 3,
  maximumPerCabin: 4,
}

const existingFurniture: RepairEstimateCatalogNodeDto = {
  ...furnitureCategory,
  id: FURNITURE_ID,
  name: "Стул",
  nodeType: "MATERIAL",
  parentId: CATEGORY_ID,
  unit: "шт.",
  unitPrice: "100.00",
  includeInEstimate: true,
  showInMainMenu: false,
  furnitureCategory: false,
  furnitureEquipment,
}

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  const invalidateQueries = vi.spyOn(queryClient, "invalidateQueries")

  render(
    <MemoryRouter initialEntries={["/settings/estimates-repairs"]}>
      <QueryClientProvider client={queryClient}>
        <EstimatesRepairsSettingsPage />
      </QueryClientProvider>
    </MemoryRouter>
  )

  return { invalidateQueries }
}

async function openFurnitureEditor(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("tab", { name: "Мебель" }))
  await user.click(
    await screen.findByRole("button", { name: /^Мебельная группа/ })
  )
  await user.click(await screen.findByRole("button", { name: "Добавить" }))
  return screen.findByRole("dialog", { name: "Добавить: Мебель" })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal("ResizeObserver", ResizeObserverMock)
  mocks.getCatalog.mockResolvedValue(version)
  mocks.getFurnitureCatalog.mockResolvedValue(furnitureSection)
  mocks.saveFurniture.mockResolvedValue({
    ...furnitureCategory,
    id: FURNITURE_ID,
    name: "Стол",
    nodeType: "MATERIAL",
    parentId: CATEGORY_ID,
    unit: "шт.",
    unitPrice: "0.00",
    includeInEstimate: true,
    showInMainMenu: false,
    furnitureCategory: false,
    furnitureEquipment: {
      equipmentId: "00000000-0000-4000-8000-000000000005",
      equipmentName: "Стол",
      equipmentVersion: 0,
      maximumPerCabin: null,
    },
  })
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("automatic furniture equipment link", () => {
  it("saves a per-cabin maximum without loading or selecting asset equipment", async () => {
    const user = userEvent.setup()
    const { invalidateQueries } = renderPage()
    const dialog = await openFurnitureEditor(user)

    expect(dialog.textContent).toContain("Будет создано автоматически")
    expect(dialog.textContent).toContain(
      "автоматически создаст и привяжет строку в «Доп. оборудовании»"
    )
    expect(
      screen.queryByRole("combobox", {
        name: "Дополнительное оборудование",
      })
    ).toBeNull()

    await user.type(screen.getByRole("textbox", { name: "Название" }), "Стол")
    await user.type(
      screen.getByRole("spinbutton", {
        name: "Максимальное количество в одной бытовке",
      }),
      "4"
    )
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() => {
      expect(mocks.saveFurniture).toHaveBeenCalledWith(
        expect.objectContaining({
          accessToken: "maintenance-token",
          catalogVersionId: VERSION_ID,
        }),
        expect.objectContaining({
          name: "Стол",
          furnitureEquipment: {
            equipmentId: null,
            equipmentName: "Стол",
            equipmentVersion: null,
            maximumPerCabin: 4,
          },
        })
      )
    })
    expect(mocks.getEquipmentItems).not.toHaveBeenCalled()
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["equipment-items"],
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["estimate-catalog"],
    })
  })

  it("shows an existing canonical link read-only and preserves it on edit", async () => {
    const user = userEvent.setup()
    mocks.getFurnitureCatalog.mockResolvedValue({
      ...furnitureSection,
      nodes: [furnitureCategory, existingFurniture],
    })
    mocks.saveFurniture.mockResolvedValue({
      ...existingFurniture,
      name: "Стул складной",
    })
    renderPage()

    await user.click(await screen.findByRole("tab", { name: "Мебель" }))
    await user.click(
      await screen.findByRole("button", { name: /^Мебельная группа/ })
    )
    await user.click(
      await screen.findByRole("button", { name: "Редактировать" })
    )

    const dialog = await screen.findByRole("dialog", {
      name: "Редактировать: Мебель",
    })
    expect(dialog.textContent).toContain("Связано автоматически")
    expect(dialog.textContent).toContain("Стул")
    expect(
      (
        screen.getByRole("spinbutton", {
          name: "Максимальное количество в одной бытовке",
        }) as HTMLInputElement
      ).value
    ).toBe("4")
    expect(screen.queryByText("Фотографии")).toBeNull()
    expect(
      screen.queryByRole("combobox", {
        name: "Дополнительное оборудование",
      })
    ).toBeNull()

    const name = screen.getByRole("textbox", { name: "Название" })
    await user.clear(name)
    await user.type(name, "Стул складной")
    const maximum = screen.getByRole("spinbutton", {
      name: "Максимальное количество в одной бытовке",
    })
    await user.clear(maximum)
    await user.type(maximum, "2")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() => {
      expect(mocks.saveFurniture).toHaveBeenCalledWith(
        expect.any(Object),
        expect.objectContaining({
          id: FURNITURE_ID,
          name: "Стул складной",
          furnitureEquipment: {
            ...furnitureEquipment,
            maximumPerCabin: 2,
          },
        })
      )
    })
    expect(mocks.getEquipmentItems).not.toHaveBeenCalled()
  })
})
