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
  getCatalog: vi.fn(),
  getCatalogCanvas: vi.fn(),
  bootstrapCatalog: vi.fn(),
  saveCanvasChanges: vi.fn(),
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
      getCurrentRepairEstimateCatalog: mocks.getCatalog,
      getRepairEstimateCatalogCanvas: mocks.getCatalogCanvas,
      bootstrapRepairEstimateCatalog: mocks.bootstrapCatalog,
      saveRepairEstimateCatalogCanvasChanges: mocks.saveCanvasChanges,
    }
  }
)

import { EstimatesRepairsSettingsPage } from "@/features/settings/estimates-repairs/estimates-repairs-settings-page"

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const CATEGORY_ID = "00000000-0000-4000-8000-000000000010"
const FIRST_WORK_ID = "00000000-0000-4000-8000-000000000011"
const SECOND_WORK_ID = "00000000-0000-4000-8000-000000000012"

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

function canvasFixture(): RepairEstimateCatalogCanvasDto {
  const category: RepairEstimateCatalogNodeDto = {
    id: CATEGORY_ID,
    catalogVersionId: catalog.id,
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
    includeInEstimate: false,
    commonItem: false,
    furnitureCategory: false,
    furnitureEquipment: null,
    references: [],
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

function renderPage(
  level: WarehouseAccessLevel,
  catalogResult:
    | RepairEstimateCatalogVersionDto
    | null
    | Error
    | Promise<RepairEstimateCatalogVersionDto | null> = catalog
) {
  mocks.level = level
  if (catalogResult instanceof Error) {
    mocks.getCatalog.mockRejectedValue(catalogResult)
  } else if (catalogResult instanceof Promise) {
    mocks.getCatalog.mockImplementation(() => catalogResult)
  } else {
    mocks.getCatalog.mockResolvedValue(catalogResult)
  }
  mocks.getCatalogCanvas.mockResolvedValue(canvasFixture())

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

beforeEach(() => {
  vi.stubGlobal("ResizeObserver", ResizeObserverMock)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe("maintenance catalog settings", () => {
  it("shows only the estimate settings menu without versions or drafts", async () => {
    renderPage("MANAGE")

    expect(
      await screen.findByRole("button", { name: "Конструктор каталога смет" })
    ).toBeTruthy()
    expect(screen.queryByText("Версия каталога")).toBeNull()
    expect(screen.queryByText(/черновик/i)).toBeNull()
    expect(screen.queryByRole("button", { name: "Активировать" })).toBeNull()
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
          name: "Создать первый каталог",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(false)
  })

  it("keeps VIEW read-only and lets EDIT open the active node editor directly", async () => {
    const user = userEvent.setup()
    const view = renderPage("VIEW")
    await user.click(
      await screen.findByRole("button", { name: "Конструктор каталога смет" })
    )
    await user.click(await screen.findByRole("button", { name: /^Окна/ }))
    expect(screen.queryByRole("button", { name: "Редактировать" })).toBeNull()
    expect(screen.queryByRole("radio", { name: "Путь" })).toBeNull()

    view.unmount()
    renderPage("EDIT")
    await user.click(
      await screen.findByRole("button", { name: "Конструктор каталога смет" })
    )
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

  it("saves a typed arrow and a moved node with one catalog command", async () => {
    const user = userEvent.setup()
    mocks.saveCanvasChanges.mockResolvedValue(canvasFixture())
    renderPage("EDIT")

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
})
