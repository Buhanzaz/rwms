import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { MemoryRouter, useLocation } from "react-router-dom"
import { ApiError } from "@/lib/api-client"

import type {
  CurrentUser,
  WarehouseAccessLevel,
} from "@/features/auth/auth-model"
import type {
  RepairEstimateCatalogCanvasDto,
  RepairEstimateCatalogNodeDto,
  RepairEstimateCatalogRoutingDto,
  RepairEstimateCatalogVersionDto,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"
import type { QueueDefinitionDto } from "@/features/settings/task-board/model/task-board-settings"

const mocks = vi.hoisted(() => ({
  level: "VIEW" as WarehouseAccessLevel,
  noWarehouses: false,
  windowCard: vi.fn(),
  complexityCard: vi.fn(),
  getCatalog: vi.fn(),
  getCatalogCanvas: vi.fn(),
  getCatalogSnapshot: vi.fn(),
  createCatalog: vi.fn(),
  saveCanvasChanges: vi.fn(),
  saveCanvasNode: vi.fn(),
  saveCatalogColors: vi.fn(),
  listQueueDefinitions: vi.fn(),
  getCabinSettings: vi.fn(),
  getComplexityColors: vi.fn(),
  saveComplexityColors: vi.fn(),
  toastSuccess: vi.fn(),
}))

vi.mock("sonner", () => ({
  toast: { success: mocks.toastSuccess },
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "maintenance-token",
    currentUser: currentUser(mocks.level),
  }),
}))

vi.mock(
  "@/features/settings/estimates-repairs/estimate-creation-window-settings-card",
  () => ({ EstimateCreationWindowSettingsCard: (props: { accessToken: string; readOnly: boolean }) => {
    mocks.windowCard(props)
    return <div>Общий срок сметы</div>
  } })
)

vi.mock("@/features/settings/kpi/repair-complexity-settings-card", () => ({
  GlobalRepairComplexitySettingsCard: (props: { accessToken: string; readOnly: boolean }) => {
    mocks.complexityCard(props)
    return <div>Общие границы сложности</div>
  },
}))

vi.mock("@/features/settings/task-board/api/task-board-settings-api", () => ({
  taskBoardSettingsClient: {
    listQueueDefinitions: mocks.listQueueDefinitions,
  },
  taskBoardSettingsKeys: {
    queueDefinitions: ["task-board-settings", "queue-definitions"],
  },
}))

vi.mock("@/features/rental-items/api/asset-rental-items-api", () => ({
  getCabinSettings: mocks.getCabinSettings,
}))

vi.mock(
  "@/features/settings/estimates-repairs/api/repair-complexity-colors-api",
  () => ({
    getRepairComplexityColors: mocks.getComplexityColors,
    saveRepairComplexityColors: mocks.saveComplexityColors,
  })
)

vi.mock(
  "@/features/settings/estimates-repairs/api/repair-estimate-catalog-canvas-settings-api",
  async () => {
    const actual = await vi.importActual<
      typeof import("@/features/settings/estimates-repairs/api/repair-estimate-catalog-canvas-settings-api")
    >(
      "@/features/settings/estimates-repairs/api/repair-estimate-catalog-canvas-settings-api"
    )

    return {
      ...actual,
      saveRepairEstimateCatalogCanvasNode: mocks.saveCanvasNode,
    }
  }
)

vi.mock(
  "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store",
  async () => {
    const actual = await vi.importActual<
      typeof import("@/features/settings/estimates-repairs/api/repair-estimate-catalog-store")
    >("@/features/settings/estimates-repairs/api/repair-estimate-catalog-store")

    return {
      ...actual,
      getCurrentRepairEstimateCatalog: mocks.getCatalog,
      getRepairEstimateCatalogCanvas: mocks.getCatalogCanvas,
      getRepairEstimateCatalogSnapshot: mocks.getCatalogSnapshot,
      createRepairEstimateCatalog: mocks.createCatalog,
      saveRepairEstimateCatalogCanvasChanges: mocks.saveCanvasChanges,
      saveRepairEstimateCatalogDisplayColors: mocks.saveCatalogColors,
    }
  }
)

import { EstimatesRepairsSettingsPage } from "@/features/settings/estimates-repairs/estimates-repairs-settings-page"

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const CATEGORY_ID = "00000000-0000-4000-8000-000000000010"
const FIRST_WORK_ID = "00000000-0000-4000-8000-000000000011"
const SECOND_WORK_ID = "00000000-0000-4000-8000-000000000012"
const SUBCATEGORY_ID = "00000000-0000-4000-8000-000000000013"
const MATERIAL_ID = "00000000-0000-4000-8000-000000000014"
const COMMON_CATEGORY_ID = "00000000-0000-4000-8000-000000000015"
const COMMON_WORK_ID = "00000000-0000-4000-8000-000000000016"
const COMMON_MATERIAL_ID = "00000000-0000-4000-8000-000000000017"
const REPAIR_QUEUE_ID = "00000000-0000-4000-8000-000000000020"
const FOREIGN_REPAIR_QUEUE_ID = "00000000-0000-4000-8000-000000000120"
const FURNITURE_QUEUE_ID = "00000000-0000-4000-8000-000000000023"
const CHARACTERISTIC_ID = "00000000-0000-4000-8000-000000000024"

function LocationSearchProbe() {
  const location = useLocation()
  return (
    <span data-testid="location-search" className="sr-only">
      {location.search}
    </span>
  )
}

const catalog: RepairEstimateCatalogVersionDto = {
  id: "00000000-0000-4000-8000-000000000002",
  warehouseId: WAREHOUSE_ID,
  version: 3,
  lifecycle: "ACTIVE",
  sourceSha256: "a".repeat(64),
  nodeCount: 3,
  linkCount: 0,
  valid: true,
  createdAt: "2026-07-18T08:00:00Z",
  activatedAt: "2026-07-18T09:00:00Z",
}

function canvasFixture(
  routing: RepairEstimateCatalogRoutingDto | null = null
): RepairEstimateCatalogCanvasDto {
  const category: RepairEstimateCatalogNodeDto = {
    id: CATEGORY_ID,
    catalogVersionId: catalog.id,
    name: "Окна",
    nodeType: "CATEGORY",
    parentId: null,
    active: true,
    unit: null,
    unitPrice: null,
    durationMinutes: null,
    showInMainMenu: true,
    routeQueueKind: routing?.queueType === "REPAIR" ? "REPAIR" : null,
    queueDefinitionId: routing?.queueId ?? null,
    routing,
    includeInEstimate: false,
    commonItem: false,
    furnitureCategory: false,
    furnitureEquipment: null,
    forcesCapitalRepair: false,
    characteristic: null,
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
    name,
    nodeType: "WORK",
    parentId: category.id,
    unit: "шт.",
    unitPrice: "100.00",
    durationMinutes: 60,
    showInMainMenu: false,
    includeInEstimate: true,
    canvasX,
    canvasY: 320,
  })

  return {
    catalogVersion: catalog,
    categories: [category],
    nodes: [
      category,
      createWork(FIRST_WORK_ID, "Установить окно", 40),
      createWork(SECOND_WORK_ID, "Отремонтировать окно", 380),
    ],
    links: [],
  }
}

function metadataCanvasFixture(): RepairEstimateCatalogCanvasDto {
  const base = canvasFixture({
    queueId: REPAIR_QUEUE_ID,
    queueName: "Электрики",
    queueType: "REPAIR",
  })
  const category = base.nodes[0]
  const work: RepairEstimateCatalogNodeDto = {
    ...base.nodes[1],
    name: "Монтаж окна",
    unit: "шт.",
    unitPrice: "1200.00",
    durationMinutes: 45,
    includeInEstimate: true,
  }
  const subcategory: RepairEstimateCatalogNodeDto = {
    ...category,
    id: SUBCATEGORY_ID,
    name: "Внутренняя отделка",
    nodeType: "SUBCATEGORY",
    parentId: category.id,
    active: false,
    unit: "не показывать",
    routeQueueKind: null,
    queueDefinitionId: null,
    routing: null,
    canvasX: 380,
    canvasY: 40,
  }
  const material: RepairEstimateCatalogNodeDto = {
    ...work,
    id: MATERIAL_ID,
    name: "Монтажная пена",
    nodeType: "MATERIAL",
    unit: "баллон",
    unitPrice: "350.00",
    durationMinutes: null,
    includeInEstimate: false,
    canvasX: 720,
    canvasY: 320,
    comment: "Старый комментарий материала",
  }

  return {
    ...base,
    nodes: [category, subcategory, work, material],
  }
}

function commonCanvasFixture(): RepairEstimateCatalogCanvasDto {
  const base = metadataCanvasFixture()
  const category = base.nodes[0]
  const work = base.nodes.find((node) => node.id === FIRST_WORK_ID)!
  const material = base.nodes.find((node) => node.id === MATERIAL_ID)!
  const commonCategory: RepairEstimateCatalogNodeDto = {
    ...category,
    id: COMMON_CATEGORY_ID,
    name: "Общие позиции",
    canvasX: 1040,
  }
  const routing: RepairEstimateCatalogRoutingDto = {
    queueId: REPAIR_QUEUE_ID,
    queueName: "Электрики",
    queueType: "REPAIR",
  }
  const commonWork: RepairEstimateCatalogNodeDto = {
    ...work,
    id: COMMON_WORK_ID,
    name: "Общая замена окна",
    parentId: commonCategory.id,
    displayColor: "#336699",
    routeQueueKind: "REPAIR",
    queueDefinitionId: REPAIR_QUEUE_ID,
    routing,
    commonItem: true,
    forcesCapitalRepair: true,
    canvasX: 40,
    canvasY: 320,
    comment: "Повторно используемая работа",
  }
  const commonMaterial: RepairEstimateCatalogNodeDto = {
    ...material,
    id: COMMON_MATERIAL_ID,
    name: "Общая монтажная пена",
    parentId: commonCategory.id,
    displayColor: "#996633",
    routeQueueKind: "REPAIR",
    queueDefinitionId: REPAIR_QUEUE_ID,
    routing,
    includeInEstimate: true,
    commonItem: true,
    characteristic: {
      characteristicId: CHARACTERISTIC_ID,
      characteristicName: "Железная дверь",
    },
    canvasX: 380,
    canvasY: 320,
    comment: "Повторно используемый материал",
  }

  return {
    ...base,
    categories: [category, commonCategory],
    nodes: [...base.nodes, commonCategory, commonWork, commonMaterial],
  }
}

function queueFixture(
  overrides: Partial<QueueDefinitionDto> = {}
): QueueDefinitionDto {
  return {
    id: REPAIR_QUEUE_ID,
    version: 1,
    name: "Электрики",
    description: null,
    type: "REPAIR",
    purpose: "GENERAL",
    sortOrder: 0,
    active: true,
    hidden: false,
    collapsed: false,
    holdingPeriodMinutes: null,
    notificationThreshold: null,
    notifyWhenThresholdReached: false,
    resultPhotoMinCount: 1,
    availableTaskLimit: 6,
    linkedQueueDefinitionId: null,
    bindings: [],
    ...overrides,
  }
}

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
    globalRole: level === "VIEW" ? "WAREHOUSE_MANAGER" : "WMS_ADMIN",
    rentalAccess: false,
    warehouseAccessAll: false,
    warehouseAccesses: mocks.noWarehouses ? [] : [{ warehouseId: WAREHOUSE_ID, level }],
  }
}

function renderPage(
  level: WarehouseAccessLevel,
  catalogResult:
    | RepairEstimateCatalogVersionDto
    | null
    | Error
    | Promise<RepairEstimateCatalogVersionDto | null> = catalog,
  canvas: RepairEstimateCatalogCanvasDto = canvasFixture(),
  initialEntries = ["/settings/estimates-repairs"]
) {
  mocks.level = level
  if (catalogResult instanceof Error) {
    mocks.getCatalog.mockRejectedValue(catalogResult)
  } else if (catalogResult instanceof Promise) {
    mocks.getCatalog.mockImplementation(() => catalogResult)
  } else {
    mocks.getCatalog.mockResolvedValue(catalogResult)
  }
  mocks.getCatalogCanvas.mockResolvedValue(canvas)
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  const rendered = render(
    <MemoryRouter initialEntries={initialEntries}>
      <QueryClientProvider client={queryClient}>
        <EstimatesRepairsSettingsPage />
        <LocationSearchProbe />
      </QueryClientProvider>
    </MemoryRouter>
  )
  return { ...rendered, queryClient }
}

function findCatalogSection(name: string) {
  return screen.findByRole("tab", { name })
}

function getCanvasMetric(node: HTMLElement, label: string) {
  const term = within(node).getByText(label, { selector: "dt" })
  return term.nextElementSibling?.textContent
}

beforeEach(() => {
  mocks.noWarehouses = false
  vi.stubGlobal("ResizeObserver", ResizeObserverMock)
  Object.defineProperties(HTMLElement.prototype, {
    hasPointerCapture: {
      configurable: true,
      value: () => false,
    },
    setPointerCapture: {
      configurable: true,
      value: () => undefined,
    },
    releasePointerCapture: {
      configurable: true,
      value: () => undefined,
    },
    scrollIntoView: {
      configurable: true,
      value: () => undefined,
    },
  })
  mocks.listQueueDefinitions.mockResolvedValue([
    queueFixture(),
    queueFixture({
      id: FURNITURE_QUEUE_ID,
      name: "Перемещение мебели",
      type: "FURNITURE_MOVEMENT",
    }),
  ])
  mocks.getCabinSettings.mockResolvedValue({
    types: [],
    dimensions: [],
    finishings: [],
    characteristics: [
      {
        id: CHARACTERISTIC_ID,
        version: 1,
        kind: "CHARACTERISTIC",
        name: "Железная дверь",
        active: true,
        createdAt: "2026-07-30T10:00:00Z",
        updatedAt: "2026-07-30T10:00:00Z",
      },
    ],
    typeDimensions: [],
  })
  mocks.getComplexityColors.mockResolvedValue({
    version: 3,
    lightColor: "#22C55E",
    mediumColor: "#EAB308",
    complexColor: "#F97316",
    capitalColor: "#DC2626",
    updatedAt: "2026-07-30T10:00:00Z",
  })
  mocks.saveComplexityColors.mockResolvedValue({
    version: 4,
    lightColor: "#16A34A",
    mediumColor: "#EAB308",
    complexColor: "#F97316",
    capitalColor: "#DC2626",
    updatedAt: "2026-07-30T10:05:00Z",
  })
  mocks.saveCanvasNode.mockResolvedValue(canvasFixture().nodes[0])
  mocks.getCatalogSnapshot.mockResolvedValue(canvasFixture())
  mocks.saveCatalogColors.mockResolvedValue(canvasFixture())
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe("maintenance catalog settings", () => {
  it("edits the four repair type colors through the global estimate setting", async () => {
    const user = userEvent.setup()
    const { queryClient } = renderPage("MANAGE")
    const overviewKey = ["maintenance", "repairs", WAREHOUSE_ID, "overview"]
    const otherWarehouseKey = [
      "maintenance",
      "repairs",
      "other-warehouse",
      "task-board-complexities",
    ]
    const unrelatedKey = ["asset-rental-items", WAREHOUSE_ID]
    queryClient.setQueryData(overviewKey, {})
    queryClient.setQueryData(otherWarehouseKey, {})
    queryClient.setQueryData(unrelatedKey, {})

    await user.click(await findCatalogSection("Цветовая индикация кнопок"))
    expect(
      await screen.findByRole("heading", { name: "Цвета типов ремонта" })
    ).toBeTruthy()
    expect(screen.getByLabelText("Лёгкий ремонт")).toBeTruthy()
    expect(screen.getByLabelText("Средний ремонт")).toBeTruthy()
    expect(screen.getByLabelText("Тяжёлый ремонт")).toBeTruthy()
    expect(screen.getByLabelText("Капитальный ремонт")).toBeTruthy()
    expect(
      screen.getByLabelText("Выбрать цвет: Лёгкий ремонт").className
    ).toContain("rwms-color-picker")

    fireEvent.change(screen.getByLabelText("Лёгкий ремонт"), {
      target: { value: "#16A34A" },
    })
    await user.click(
      screen.getByRole("button", {
        name: "Сохранить цвета типов ремонта",
      })
    )

    await waitFor(() =>
      expect(mocks.saveComplexityColors).toHaveBeenCalledWith(
        "maintenance-token",
        {
          version: 3,
          lightColor: "#16A34A",
          mediumColor: "#EAB308",
          complexColor: "#F97316",
          capitalColor: "#DC2626",
        }
      )
    )
    await waitFor(() =>
      expect(queryClient.getQueryState(overviewKey)?.isInvalidated).toBe(true)
    )
    expect(queryClient.getQueryState(otherWarehouseKey)?.isInvalidated).toBe(
      true
    )
    expect(queryClient.getQueryState(unrelatedKey)?.isInvalidated).toBe(false)
  })

  it("keeps an unsaved complexity palette through a remote revision and a failed refresh", async () => {
    const user = userEvent.setup()
    const { queryClient } = renderPage("MANAGE")
    await user.click(await findCatalogSection("Цветовая индикация кнопок"))
    const input = await screen.findByLabelText("Лёгкий ремонт")
    fireEvent.change(input, { target: { value: "#112233" } })
    const key = ["maintenance", "repair-complexity-colors"]
    const latest = {
      ...(await mocks.getComplexityColors()),
      version: 4,
      lightColor: "#445566",
    }
    await act(async () => {
      queryClient.setQueryData(key, latest)
    })
    expect(input).toHaveProperty("value", "#112233")
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Сохранить цвета типов ремонта" })
      ).toHaveProperty("disabled", true)
    )
    mocks.getComplexityColors.mockRejectedValueOnce(new Error("Связь прервана"))
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: key })
    })
    expect(screen.getByLabelText("Лёгкий ремонт")).toHaveProperty(
      "value",
      "#112233"
    )
    expect(await screen.findByText(/Не удалось обновить палитру/)).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Загрузить актуальные цвета" })
    )
    expect(screen.getByLabelText("Лёгкий ремонт")).toHaveProperty(
      "value",
      "#445566"
    )
    expect(mocks.saveComplexityColors).not.toHaveBeenCalled()
  })

  it("retains the draft after a version conflict and reads the winning palette", async () => {
    const user = userEvent.setup()
    renderPage("MANAGE")
    await user.click(await findCatalogSection("Цветовая индикация кнопок"))
    const input = await screen.findByLabelText("Лёгкий ремонт")
    fireEvent.change(input, { target: { value: "#112233" } })
    const latest = {
      ...(await mocks.getComplexityColors()),
      version: 4,
      lightColor: "#445566",
    }
    mocks.getComplexityColors.mockResolvedValue(latest)
    mocks.saveComplexityColors.mockRejectedValueOnce(
      new ApiError("Конфликт версий", 409)
    )
    await user.click(
      screen.getByRole("button", { name: "Сохранить цвета типов ремонта" })
    )
    await screen.findByRole("button", { name: "Загрузить актуальные цвета" })
    expect(input).toHaveProperty("value", "#112233")
    expect(mocks.toastSuccess).not.toHaveBeenCalled()
    await user.click(
      screen.getByRole("button", { name: "Загрузить актуальные цвета" })
    )
    expect(screen.getByLabelText("Лёгкий ремонт")).toHaveProperty(
      "value",
      "#445566"
    )
  })

  it("shows only the estimate settings menu without versions or drafts", async () => {
    renderPage("MANAGE")

    expect(await findCatalogSection("Конструктор каталога смет")).toBeTruthy()
    expect(screen.queryByText("Настройка смет — единый каталог")).toBeNull()
    expect(screen.queryByText("Версия каталога")).toBeNull()
    expect(screen.queryByText(/черновик/i)).toBeNull()
    expect(screen.queryByRole("button", { name: "Активировать" })).toBeNull()
  })

  it("opens the catalog constructor by default when a catalog is available", async () => {
    renderPage("MANAGE")

    await waitFor(() => {
      expect(screen.getByTestId("location-search").textContent).toBe(
        "?catalog=repair-estimate-catalog-canvas"
      )
    })
    expect(
      (await findCatalogSection("Конструктор каталога смет")).getAttribute(
        "aria-selected"
      )
    ).toBe("true")
    expect(await screen.findByRole("button", { name: /^Окна/ })).toBeTruthy()
  })

  it("keeps the active catalog section in the URL without a back control", async () => {
    const user = userEvent.setup()
    renderPage("MANAGE")

    await user.click(await findCatalogSection("Работы"))
    await waitFor(() => {
      expect(screen.getByTestId("location-search").textContent).toBe(
        "?catalog=repair-estimate-catalog-works"
      )
    })
    expect(screen.queryByRole("button", { name: "Назад" })).toBeNull()
  })

  it("keeps the six object-style catalog tabs above action and category content", async () => {
    const user = userEvent.setup()
    renderPage("MANAGE")

    await findCatalogSection("Конструктор каталога смет")
    const navigation = await screen.findByRole("navigation", {
      name: "Разделы каталога смет",
    })
    const actions = [
      "Цветовая индикация кнопок",
      "Конструктор каталога смет",
      "Работы",
      "Материалы",
      "Мебель",
      "Общее",
      "Сроки и сложность",
    ]

    const tabsList = within(navigation).getByRole("tablist")
    expect(tabsList.className).toContain("bg-muted/75")
    expect(tabsList.className).toContain("backdrop-blur-md")
    for (const action of actions) {
      expect(
        within(navigation).getByRole("tab", { name: action }).className
      ).toContain("data-[state=active]:bg-background")
    }

    await user.click(
      within(navigation).getByRole("tab", {
        name: "Конструктор каталога смет",
      })
    )
    const category = await screen.findByRole("button", { name: /^Окна/ })

    expect(
      screen.getByRole("navigation", { name: "Разделы каталога смет" })
    ).toBeTruthy()
    expect(
      within(navigation)
        .getByRole("tab", { name: "Конструктор каталога смет" })
        .getAttribute("aria-selected")
    ).toBe("true")
    expect(category.className).toContain("h-9")

    await user.click(category)
    expect(
      await screen.findByRole("group", { name: "Блок: Установить окно" })
    ).toBeTruthy()
    expect(
      screen.getByRole("navigation", { name: "Разделы каталога смет" })
    ).toBeTruthy()
  })

  it("saves semantic button colours through the current catalog version", async () => {
    const user = userEvent.setup()
    renderPage("MANAGE")

    await user.click(await findCatalogSection("Цветовая индикация кнопок"))
    expect(await screen.findByText(/Верхний уровень каталога/)).toBeTruthy()

    const categoryColor = screen.getByRole("textbox", {
      name: "HTML-цвет: Категории",
    })
    await user.clear(categoryColor)
    await user.type(categoryColor, "#336699")
    await user.click(screen.getByRole("button", { name: "Сохранить цвета" }))

    await waitFor(() => {
      expect(mocks.saveCatalogColors).toHaveBeenCalledWith(
        expect.objectContaining({
          accessToken: "maintenance-token",
          catalogVersionId: catalog.id,
        }),
        expect.objectContaining({ CATEGORY: "#336699" })
      )
    })
  })

  it("shows loading, error and empty states for the current catalog", async () => {
    const pending = new Promise<RepairEstimateCatalogVersionDto | null>(
      () => undefined
    )
    const view = renderPage("MANAGE", pending)
    expect((await screen.findByRole("status")).textContent).toContain(
      "Загружаем каталог смет"
    )

    view.unmount()
    renderPage("MANAGE", new Error("maintenance-service недоступен"))
    expect((await screen.findByRole("alert")).textContent).toContain(
      "maintenance-service недоступен"
    )

    cleanup()
    renderPage("MANAGE", null)
    expect(await screen.findByText("Каталог смет ещё не создан")).toBeTruthy()
    expect(
      (
        screen.getByRole("button", {
          name: "Создать единый каталог",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(false)
  })

  it("keeps VIEW read-only and lets EDIT open the active node editor directly", async () => {
    const user = userEvent.setup()
    const view = renderPage("VIEW")
    await user.click(await findCatalogSection("Конструктор каталога смет"))
    await user.click(await screen.findByRole("button", { name: /^Окна/ }))
    expect(screen.queryByRole("button", { name: "Редактировать" })).toBeNull()
    expect(screen.queryByRole("button", { name: "Общее" })).toBeNull()
    expect(screen.queryByRole("radio", { name: "Путь" })).toBeNull()

    view.unmount()
    renderPage("EDIT")
    await user.click(await findCatalogSection("Конструктор каталога смет"))
    await user.click(await screen.findByRole("button", { name: /^Окна/ }))
    const workNode = await screen.findByRole("group", {
      name: "Блок: Установить окно",
    })
    await user.click(
      within(workNode).getByRole("button", { name: "Редактировать" })
    )
    expect(
      await screen.findByRole("dialog", { name: "Редактировать блок" })
    ).toBeTruthy()
    expect(screen.queryByText("Фотографии")).toBeNull()
    expect(
      screen.queryByRole("checkbox", { name: "Фото обязательно" })
    ).toBeNull()
  })

  it("renders type-specific metadata in catalog constructor blocks", async () => {
    const user = userEvent.setup()
    renderPage("VIEW", catalog, metadataCanvasFixture())

    await user.click(await findCatalogSection("Конструктор каталога смет"))
    await user.click(await screen.findByRole("button", { name: /^Окна/ }))

    const categoryNode = await screen.findByRole("group", {
      name: "Блок: Окна",
    })
    const subcategoryNode = screen.getByRole("group", {
      name: "Блок: Внутренняя отделка",
    })
    const workNode = screen.getByRole("group", {
      name: "Блок: Монтаж окна",
    })
    const materialNode = screen.getByRole("group", {
      name: "Блок: Монтажная пена",
    })

    expect(getCanvasMetric(categoryNode, "Активно")).toBe("Да")
    await waitFor(() =>
      expect(getCanvasMetric(categoryNode, "Очередь")).toBe("Электрики")
    )
    expect(within(categoryNode).queryByText("Единица")).toBeNull()

    expect(getCanvasMetric(subcategoryNode, "Активно")).toBe("Нет")
    expect(within(subcategoryNode).queryByText("Единица")).toBeNull()
    expect(within(subcategoryNode).queryByText("Очередь")).toBeNull()

    expect(getCanvasMetric(workNode, "Единица")).toBe("шт.")
    expect(getCanvasMetric(workNode, "Цена")).toBe("1\u00a0200,00 ₽")
    expect(getCanvasMetric(workNode, "Длительность")).toBe("45 мин")
    expect(getCanvasMetric(workNode, "Активно")).toBe("Да")
    expect(getCanvasMetric(workNode, "Учёт в смете")).toBe("Да")
    const workMetrics = within(workNode)
      .getByText("Единица", { selector: "dt" })
      .closest("dl")
    expect(workMetrics?.className).toContain("grid-cols-2")
    expect(
      within(workNode).getByText("Единица", { selector: "dt" }).className
    ).toContain("text-foreground")
    expect(workMetrics?.querySelectorAll("dt")).toHaveLength(5)
    expect(workMetrics?.querySelectorAll("dd")).toHaveLength(5)

    expect(getCanvasMetric(materialNode, "Единица")).toBe("баллон")
    expect(getCanvasMetric(materialNode, "Цена")).toBe("350,00 ₽")
    expect(getCanvasMetric(materialNode, "Активно")).toBe("Да")
    expect(getCanvasMetric(materialNode, "Учёт в смете")).toBe("Нет")
    expect(within(materialNode).queryByText("Длительность")).toBeNull()
    expect(
      within(materialNode).queryByText("Старый комментарий материала")
    ).toBeNull()
  })

  it("binds a category to an exact global queue definition", async () => {
    const user = userEvent.setup()
    renderPage("EDIT")

    await user.click(await findCatalogSection("Конструктор каталога смет"))
    await user.click(await screen.findByRole("button", { name: /^Окна/ }))
    const categoryNode = await screen.findByRole("group", {
      name: "Блок: Окна",
    })
    await user.click(
      within(categoryNode).getByRole("button", { name: "Редактировать" })
    )

    const dialog = await screen.findByRole("dialog", {
      name: "Редактировать блок",
    })
    const queueSelect = await within(dialog).findByLabelText(
      "Очередь доски задач"
    )
    await waitFor(() => {
      expect(mocks.listQueueDefinitions).toHaveBeenCalledWith(
        "maintenance-token"
      )
      expect((queueSelect as HTMLButtonElement).disabled).toBe(false)
    })

    await user.click(queueSelect)
    expect(
      screen.getByRole("option", {
        name: /Электрики.*Ремонт/,
      })
    ).toBeTruthy()
    expect(
      screen.queryByRole("option", { name: /Перемещение мебели/ })
    ).toBeNull()
    await user.click(
      screen.getByRole("option", {
        name: /Электрики.*Ремонт/,
      })
    )
    await user.click(within(dialog).getByRole("button", { name: "Сохранить" }))

    await waitFor(() => {
      expect(mocks.saveCanvasNode).toHaveBeenCalledWith(
        expect.objectContaining({
          catalogVersionId: catalog.id,
        }),
        expect.objectContaining({
          id: CATEGORY_ID,
          routing: {
            queueId: REPAIR_QUEUE_ID,
            queueName: "Электрики",
            queueType: "REPAIR",
          },
        })
      )
    })
  })

  it("requires an explicit UUID replacement for a queue from another warehouse", async () => {
    const user = userEvent.setup()
    renderPage(
      "EDIT",
      {
        ...catalog,
        warehouseId: "00000000-0000-4000-8000-000000000099",
      },
      canvasFixture({
        queueId: FOREIGN_REPAIR_QUEUE_ID,
        queueName: "Электрики",
        queueType: "REPAIR",
      })
    )

    await user.click(await findCatalogSection("Конструктор каталога смет"))
    await user.click(await screen.findByRole("button", { name: /^Окна/ }))
    const categoryNode = await screen.findByRole("group", {
      name: "Блок: Окна",
    })
    await user.click(
      within(categoryNode).getByRole("button", { name: "Редактировать" })
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Редактировать блок",
    })
    const queueSelect = await within(dialog).findByLabelText(
      "Очередь доски задач"
    )

    await waitFor(() => {
      expect(queueSelect.textContent).toContain("Электрики")
      expect(queueSelect.textContent).toContain("недоступна")
      expect((queueSelect as HTMLButtonElement).disabled).toBe(false)
    })
    await user.click(queueSelect)
    await user.click(screen.getByRole("option", { name: "Электрики · Ремонт" }))
    await user.click(within(dialog).getByRole("button", { name: "Сохранить" }))

    await waitFor(() => {
      expect(mocks.saveCanvasNode).toHaveBeenCalledWith(
        expect.objectContaining({
          catalogVersionId: catalog.id,
        }),
        expect.objectContaining({
          id: CATEGORY_ID,
          routing: {
            queueId: REPAIR_QUEUE_ID,
            queueName: "Электрики",
            queueType: "REPAIR",
          },
        })
      )
    })
  })

  it("clears an existing category queue binding explicitly", async () => {
    const user = userEvent.setup()
    renderPage(
      "EDIT",
      catalog,
      canvasFixture({
        queueId: REPAIR_QUEUE_ID,
        queueName: "Электрики",
        queueType: "REPAIR",
      })
    )

    await user.click(await findCatalogSection("Конструктор каталога смет"))
    await user.click(await screen.findByRole("button", { name: /^Окна/ }))
    const categoryNode = await screen.findByRole("group", {
      name: "Блок: Окна",
    })
    await user.click(
      within(categoryNode).getByRole("button", { name: "Редактировать" })
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Редактировать блок",
    })
    const queueSelect = await within(dialog).findByLabelText(
      "Очередь доски задач"
    )
    await waitFor(() =>
      expect((queueSelect as HTMLButtonElement).disabled).toBe(false)
    )
    await user.click(queueSelect)
    await user.click(
      screen.getByRole("option", {
        name: "Без очереди (очистить привязку)",
      })
    )
    await user.click(within(dialog).getByRole("button", { name: "Сохранить" }))

    await waitFor(() => {
      expect(mocks.saveCanvasNode).toHaveBeenCalledWith(
        expect.any(Object),
        expect.objectContaining({
          id: CATEGORY_ID,
          routeQueueKind: null,
          routing: null,
        })
      )
    })
  })

  it("persists the forced-capital flag only from the work editor", async () => {
    const user = userEvent.setup()
    renderPage("EDIT")

    await user.click(await findCatalogSection("Конструктор каталога смет"))
    await user.click(await screen.findByRole("button", { name: /^Окна/ }))
    const workNode = await screen.findByRole("group", {
      name: "Блок: Установить окно",
    })
    await user.click(
      within(workNode).getByRole("button", { name: "Редактировать" })
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Редактировать блок",
    })
    await user.click(
      within(dialog).getByRole("checkbox", {
        name: "Автоматически переводит бытовку в капитальный ремонт",
      })
    )
    expect(
      within(dialog).queryByRole("checkbox", {
        name: "Сопоставить с характеристикой бытовки",
      })
    ).toBeNull()
    await user.click(within(dialog).getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(mocks.saveCanvasNode).toHaveBeenCalledWith(
        expect.any(Object),
        expect.objectContaining({
          id: FIRST_WORK_ID,
          forcesCapitalRepair: true,
          characteristicId: null,
        })
      )
    )
  })

  it("links a material to the canonical cabin characteristic", async () => {
    const user = userEvent.setup()
    renderPage("EDIT", catalog, metadataCanvasFixture())

    await user.click(await findCatalogSection("Конструктор каталога смет"))
    await user.click(await screen.findByRole("button", { name: /^Окна/ }))
    const materialNode = await screen.findByRole("group", {
      name: "Блок: Монтажная пена",
    })
    await user.click(
      within(materialNode).getByRole("button", { name: "Редактировать" })
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Редактировать блок",
    })
    const characteristicCheckbox = within(dialog).getByRole("checkbox", {
      name: "Сопоставить с характеристикой бытовки",
    })
    expect(within(dialog).queryByLabelText("Комментарий")).toBeNull()
    expect(characteristicCheckbox.className).toContain("size-5")
    expect(characteristicCheckbox.className).toContain("border-primary")
    await user.click(characteristicCheckbox)
    expect(
      await within(dialog).findByRole("combobox", {
        name: "Характеристика бытовки",
      })
    ).toBeTruthy()
    expect(
      within(dialog).queryByRole("checkbox", {
        name: "Автоматически переводит бытовку в капитальный ремонт",
      })
    ).toBeNull()
    await user.click(within(dialog).getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(mocks.saveCanvasNode).toHaveBeenCalledWith(
        expect.any(Object),
        expect.objectContaining({
          id: MATERIAL_ID,
          forcesCapitalRepair: false,
          characteristicId: CHARACTERISTIC_ID,
          comment: null,
        })
      )
    )
  })

  it("copies a selected common material into the current constructor category without arrows", async () => {
    const user = userEvent.setup()
    renderPage("EDIT", catalog, commonCanvasFixture())

    await user.click(await findCatalogSection("Конструктор каталога смет"))
    await user.click(await screen.findByRole("button", { name: /^Окна/ }))

    const blockButton = screen.getByRole("button", { name: "Блок" })
    const commonButton = screen.getByRole("button", { name: "Общее" })
    const saveButton = screen.getByRole("button", { name: "Сохранить" })
    expect(
      blockButton.compareDocumentPosition(commonButton) &
        Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()
    expect(
      commonButton.compareDocumentPosition(saveButton) &
        Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()

    await user.click(commonButton)
    const dialog = await screen.findByRole("dialog", {
      name: "Добавить общее",
    })
    expect(
      within(dialog).getByRole("button", {
        name: "Добавить: Общая замена окна",
      })
    ).toBeTruthy()
    expect(
      within(dialog).queryByRole("button", {
        name: "Добавить: Общая монтажная пена",
      })
    ).toBeNull()

    await user.click(within(dialog).getByRole("button", { name: "Материалы" }))
    await user.click(
      within(dialog).getByRole("button", {
        name: "Добавить: Общая монтажная пена",
      })
    )

    await waitFor(() => {
      expect(mocks.saveCanvasNode).toHaveBeenCalledWith(
        expect.objectContaining({
          accessToken: "maintenance-token",
          catalogVersionId: catalog.id,
        }),
        expect.objectContaining({
          name: "Общая монтажная пена",
          nodeType: "MATERIAL",
          parentId: CATEGORY_ID,
          displayColor: "#996633",
          unit: "баллон",
          unitPrice: "350.00",
          durationMinutes: null,
          includeInEstimate: true,
          commonItem: false,
          routeQueueKind: "REPAIR",
          routing: {
            queueId: REPAIR_QUEUE_ID,
            queueName: "Электрики",
            queueType: "REPAIR",
          },
          characteristicId: CHARACTERISTIC_ID,
          canvasX: null,
          canvasY: null,
          comment: null,
        })
      )
    })
    expect(mocks.saveCanvasNode.mock.calls[0]?.[1]?.id).toBeUndefined()
    expect(mocks.saveCanvasChanges).not.toHaveBeenCalled()

    await user.click(commonButton)
    const reopened = await screen.findByRole("dialog", {
      name: "Добавить общее",
    })
    expect(
      within(reopened).getByRole("button", {
        name: "Добавить: Общая монтажная пена",
      })
    ).toBeTruthy()
  })

  it("keeps the common picker open and reports an error when a copy cannot be saved", async () => {
    const user = userEvent.setup()
    mocks.saveCanvasNode.mockRejectedValueOnce(new Error("Такой блок уже есть"))
    renderPage("EDIT", catalog, commonCanvasFixture())

    await user.click(await findCatalogSection("Конструктор каталога смет"))
    await user.click(await screen.findByRole("button", { name: /^Окна/ }))
    await user.click(screen.getByRole("button", { name: "Общее" }))

    const dialog = await screen.findByRole("dialog", {
      name: "Добавить общее",
    })
    const commonWork = within(dialog).getByRole("button", {
      name: "Добавить: Общая замена окна",
    })
    await user.click(commonWork)

    expect(await within(dialog).findByText("Такой блок уже есть")).toBeTruthy()
    expect((commonWork as HTMLButtonElement).disabled).toBe(false)
  })

  it("saves a typed arrow and a moved node with one catalog command", async () => {
    const user = userEvent.setup()
    mocks.saveCanvasChanges.mockResolvedValue(canvasFixture())
    renderPage("EDIT")

    await user.click(await findCatalogSection("Конструктор каталога смет"))
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
    fireEvent.pointerDown(sourceAnchor, { pointerId: 1, pointerType: "mouse" })
    fireEvent.pointerUp(targetAnchor, { pointerId: 1, pointerType: "mouse" })

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

    expect(mocks.saveCanvasChanges).not.toHaveBeenCalled()
    const saveButton = screen.getByRole("button", { name: "Сохранить" })
    expect((saveButton as HTMLButtonElement).disabled).toBe(false)
    await user.click(saveButton)

    await waitFor(() => {
      expect(mocks.saveCanvasChanges).toHaveBeenCalledWith(
        expect.objectContaining({ catalogVersionId: catalog.id }),
        expect.objectContaining({
          nodePositions: [{ nodeId: FIRST_WORK_ID, x: 120, y: 360 }],
          addedLinks: [
            expect.objectContaining({
              sourceNodeId: FIRST_WORK_ID,
              targetNodeId: SECOND_WORK_ID,
              linkType: "DEPENDENCY",
              canvasAnchors: { source: "BOTTOM", target: "TOP" },
            }),
          ],
          deletedLinkIds: [],
        })
      )
    })
    expect(mocks.toastSuccess).toHaveBeenCalledWith("Каталог сохранён.")
  })

  it("snaps the arrow preview to a target point and cancels it on empty canvas", async () => {
    const user = userEvent.setup()
    renderPage("EDIT")

    await user.click(await findCatalogSection("Конструктор каталога смет"))
    await user.click(await screen.findByRole("button", { name: /^Окна/ }))

    const firstWork = await screen.findByRole("group", {
      name: "Блок: Установить окно",
    })
    const secondWork = screen.getByRole("group", {
      name: "Блок: Отремонтировать окно",
    })
    vi.spyOn(secondWork, "getBoundingClientRect").mockReturnValue({
      x: 380,
      y: 320,
      top: 320,
      right: 636,
      bottom: 528,
      left: 380,
      width: 256,
      height: 208,
      toJSON: () => undefined,
    })

    fireEvent.pointerDown(
      within(firstWork).getByRole("button", {
        name: "Нижняя точка связи: Установить окно",
      }),
      { pointerId: 1, pointerType: "mouse" }
    )
    fireEvent.pointerMove(secondWork, {
      pointerId: 1,
      pointerType: "mouse",
      clientX: 500,
      clientY: 330,
    })

    const preview = document.querySelector("[data-catalog-link-preview='true']")
    expect(preview?.getAttribute("d")).toMatch(/508 321$/)

    fireEvent.click(
      document.querySelector("[data-catalog-canvas='true']") as HTMLElement
    )
    expect(
      document.querySelector("[data-catalog-link-preview='true']")
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Отменить стрелку" })
    ).toBeNull()
  })
  it("opens the common settings without a warehouse or an initialized catalog", async () => {
    const user = userEvent.setup()
    mocks.noWarehouses = true
    renderPage("MANAGE", null)
    await user.click(await screen.findByRole("tab", { name: "Сроки и сложность" }))
    expect(await screen.findByText("Общий срок сметы")).toBeTruthy()
    expect(screen.getByText("Общие границы сложности")).toBeTruthy()
    expect(mocks.windowCard).toHaveBeenLastCalledWith({ accessToken: "maintenance-token", readOnly: false })
    expect(mocks.complexityCard).toHaveBeenLastCalledWith({ accessToken: "maintenance-token", readOnly: false })
    expect(screen.getByTestId("location-search").textContent).toBe("?catalog=maintenance-settings")
    expect(screen.queryByRole("combobox", { name: "Объект" })).toBeNull()
  })

  it("opens global settings from a deep link with read-only access for warehouse managers", async () => {
    renderPage("VIEW", new Error("catalog unavailable"), canvasFixture(), ["/settings/estimates-repairs?catalog=maintenance-settings"])
    expect(await screen.findByText("Общий срок сметы")).toBeTruthy()
    expect(mocks.windowCard).toHaveBeenLastCalledWith({ accessToken: "maintenance-token", readOnly: true })
    expect(mocks.complexityCard).toHaveBeenLastCalledWith({ accessToken: "maintenance-token", readOnly: true })
  })

  it("returns from common settings to the selected catalog section", async () => {
    const user = userEvent.setup()
    renderPage("MANAGE")
    await findCatalogSection("Конструктор каталога смет")
    await user.click(screen.getByRole("tab", { name: "Сроки и сложность" }))
    expect(await screen.findByText("Общий срок сметы")).toBeTruthy()
    await user.click(screen.getByRole("tab", { name: "Конструктор каталога смет" }))
    expect(await screen.findByRole("button", { name: /^Окна/ })).toBeTruthy()
    expect(screen.queryByText("Общий срок сметы")).toBeNull()
  })

})
