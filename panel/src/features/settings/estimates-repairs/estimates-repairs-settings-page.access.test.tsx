import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type {
  CurrentUser,
  WarehouseAccessLevel,
} from "@/features/auth/auth-model"
import type {
  RepairEstimateCatalogCanvasDto,
  RepairEstimateCatalogNodeDto,
  RepairEstimateCatalogVersionDto,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const mocks = vi.hoisted(() => ({
  level: "VIEW" as WarehouseAccessLevel,
  listVersions: vi.fn(),
  getCatalogCanvas: vi.fn(),
  bootstrapCatalog: vi.fn(),
  forkCatalog: vi.fn(),
  activateCatalog: vi.fn(),
  saveCatalogLink: vi.fn(),
  moveCanvasNode: vi.fn(),
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

vi.mock("@/features/media/service-owner-photos", () => ({
  ServiceOwnerPhotos: () => null,
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
      saveRepairEstimateCatalogLink: mocks.saveCatalogLink,
      moveRepairEstimateCatalogCanvasNode: mocks.moveCanvasNode,
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

const CATEGORY_ID = "00000000-0000-4000-8000-000000000010"
const FIRST_WORK_ID = "00000000-0000-4000-8000-000000000011"
const SECOND_WORK_ID = "00000000-0000-4000-8000-000000000012"

function canvasFixture(
  catalogVersion: RepairEstimateCatalogVersionDto
): RepairEstimateCatalogCanvasDto {
  const category: RepairEstimateCatalogNodeDto = {
    id: CATEGORY_ID,
    catalogVersionId: catalogVersion.id,
    mediaOwnerId: "00000000-0000-4000-8000-000000000013",
    code: "WINDOWS",
    name: "Окна",
    nodeType: "CATEGORY",
    parentId: null,
    parentCode: null,
    active: true,
    unit: null,
    unitPrice: null,
    durationMinutes: null,
    showInMainMenu: true,
    routeQueueKind: null,
    workQueueId: null,
    workQueueCode: null,
    routing: null,
    photoRequired: false,
    includeInEstimate: false,
    commonItem: false,
    furnitureCategory: false,
    furnitureEquipment: null,
    references: [],
    mediaReferences: [],
    canvasX: 40,
    canvasY: 40,
    comment: null,
  }
  const createWork = (
    id: string,
    name: string,
    canvasX: number
  ): RepairEstimateCatalogNodeDto => ({
    ...category,
    id,
    mediaOwnerId:
      id === FIRST_WORK_ID
        ? "00000000-0000-4000-8000-000000000014"
        : "00000000-0000-4000-8000-000000000015",
    code: id === FIRST_WORK_ID ? "WINDOW_INSTALL" : "WINDOW_REPAIR",
    name,
    nodeType: "WORK",
    parentId: category.id,
    parentCode: category.code,
    unit: "шт.",
    unitPrice: "100.00",
    durationMinutes: 60,
    showInMainMenu: false,
    includeInEstimate: true,
    canvasX,
    canvasY: 320,
  })
  const firstWork = createWork(FIRST_WORK_ID, "Установить окно", 40)
  const secondWork = createWork(SECOND_WORK_ID, "Отремонтировать окно", 380)

  return {
    catalogVersion,
    categories: [category],
    nodes: [category, firstWork, secondWork],
    links: [],
  }
}

type CanvasFixture =
  | RepairEstimateCatalogCanvasDto
  | ((catalogVersionId: string) => RepairEstimateCatalogCanvasDto)

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
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
  versions: VersionsFixture = [version],
  canvas: CanvasFixture = {
    catalogVersion: version,
    categories: [],
    nodes: [],
    links: [],
  }
) {
  mocks.level = level
  if (versions instanceof Error) {
    mocks.listVersions.mockRejectedValue(versions)
  } else if (Array.isArray(versions)) {
    mocks.listVersions.mockResolvedValue(versions)
  } else {
    mocks.listVersions.mockImplementation(() => versions)
  }
  mocks.getCatalogCanvas.mockImplementation(
    (request: { catalogVersionId: string }) =>
      Promise.resolve(
        typeof canvas === "function" ? canvas(request.catalogVersionId) : canvas
      )
  )

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

beforeEach(() => {
  vi.stubGlobal("ResizeObserver", ResizeObserverMock)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
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

  it("forks a published canvas node into a draft and opens its editor", async () => {
    const user = userEvent.setup()
    const activeVersion: RepairEstimateCatalogVersionDto = {
      ...version,
      lifecycle: "ACTIVE",
      activatedAt: "2026-07-18T09:00:00Z",
    }
    const draftVersion: RepairEstimateCatalogVersionDto = {
      ...version,
      id: "00000000-0000-4000-8000-000000000016",
      version: 4,
    }
    renderPage("MANAGE", [activeVersion], (catalogVersionId) =>
      canvasFixture(
        catalogVersionId === draftVersion.id ? draftVersion : activeVersion
      )
    )

    await screen.findByRole("button", { name: "Конструктор каталога смет" })
    mocks.listVersions.mockResolvedValue([activeVersion, draftVersion])
    mocks.forkCatalog.mockResolvedValue(draftVersion)

    await user.click(button("Конструктор каталога смет"))
    await user.click(await screen.findByRole("button", { name: /^Окна/ }))

    const workNode = await screen.findByRole("group", {
      name: "Блок: Установить окно",
    })
    expect(
      within(workNode).queryByRole("button", {
        name: "Верхняя точка связи: Установить окно",
      })
    ).toBeNull()

    await user.click(
      within(workNode).getByRole("button", {
        name: "Редактировать в черновике",
      })
    )

    await waitFor(() => {
      expect(mocks.forkCatalog).toHaveBeenCalledWith(
        {
          accessToken: "maintenance-token",
          warehouseId: WAREHOUSE_ID,
          catalogVersionId: activeVersion.id,
        },
        activeVersion.version,
        expect.any(String)
      )
    })

    const dialog = await screen.findByRole("dialog", {
      name: "Редактировать блок",
    })
    expect(
      within(dialog).getByRole("button", { name: "Сохранить" })
    ).toBeTruthy()
    await user.click(within(dialog).getByRole("button", { name: "Отмена" }))

    expect(screen.getByRole("radio", { name: "Путь" })).toBeTruthy()
    expect(screen.getByRole("radio", { name: "Зависимость" })).toBeTruthy()
    expect(
      screen.getByRole("button", {
        name: "Верхняя точка связи: Установить окно",
      })
    ).toBeTruthy()
  })

  it("draws links and moves nodes in a draft canvas", async () => {
    const user = userEvent.setup()
    const link = {
      id: "00000000-0000-4000-8000-000000000017",
      catalogVersionId: version.id,
      sourceNodeId: FIRST_WORK_ID,
      targetNodeId: SECOND_WORK_ID,
      linkType: "DEPENDENCY" as const,
      sortOrder: 10,
      canvasAnchors: { source: "BOTTOM" as const, target: "TOP" as const },
    }
    mocks.saveCatalogLink.mockResolvedValue(link)
    mocks.moveCanvasNode.mockResolvedValue({ x: 120, y: 360 })
    renderPage("EDIT", [version], canvasFixture(version))

    await user.click(
      await screen.findByRole("button", { name: "Конструктор каталога смет" })
    )
    await user.click(await screen.findByRole("button", { name: /^Окна/ }))

    const firstWork = await screen.findByRole("group", {
      name: "Блок: Установить окно",
    })
    const secondWork = screen.getByRole("group", {
      name: "Блок: Отремонтировать окно",
    })
    const sourceAnchor = within(firstWork).getByRole("button", {
      name: "Нижняя точка связи: Установить окно",
    })
    const targetAnchor = within(secondWork).getByRole("button", {
      name: "Верхняя точка связи: Отремонтировать окно",
    })

    await user.click(screen.getByRole("radio", { name: "Зависимость" }))

    fireEvent.pointerDown(sourceAnchor, {
      pointerId: 1,
      pointerType: "mouse",
      clientX: 168,
      clientY: 528,
    })
    expect(
      await screen.findByRole("button", { name: "Отменить точку" })
    ).toBeTruthy()
    fireEvent.pointerUp(targetAnchor, {
      pointerId: 1,
      pointerType: "mouse",
      clientX: 508,
      clientY: 320,
    })

    await waitFor(() => {
      expect(mocks.saveCatalogLink).toHaveBeenCalledWith(
        {
          accessToken: "maintenance-token",
          warehouseId: WAREHOUSE_ID,
          catalogVersionId: version.id,
        },
        expect.objectContaining({
          sourceNodeId: FIRST_WORK_ID,
          targetNodeId: SECOND_WORK_ID,
          linkType: "DEPENDENCY",
          canvasAnchors: { source: "BOTTOM", target: "TOP" },
        })
      )
    })

    fireEvent.pointerDown(firstWork, {
      pointerId: 2,
      pointerType: "mouse",
      button: 0,
      clientX: 40,
      clientY: 320,
    })
    fireEvent.pointerMove(firstWork, {
      pointerId: 2,
      pointerType: "mouse",
      clientX: 120,
      clientY: 360,
    })
    fireEvent.pointerUp(firstWork, {
      pointerId: 2,
      pointerType: "mouse",
      clientX: 120,
      clientY: 360,
    })

    await waitFor(() => {
      expect(mocks.moveCanvasNode).toHaveBeenCalledWith(
        {
          accessToken: "maintenance-token",
          warehouseId: WAREHOUSE_ID,
          catalogVersionId: version.id,
        },
        FIRST_WORK_ID,
        { x: 120, y: 360 }
      )
    })
  })
})
