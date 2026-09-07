import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const TYPE_ID = "11111111-1111-4111-8111-111111111111"
const SECOND_TYPE_ID = "12111111-1111-4111-8111-111111111111"
const DIMENSION_ID = "22222222-2222-4222-8222-222222222222"
const SECOND_DIMENSION_ID = "23222222-2222-4222-8222-222222222222"
const FINISHING_ID = "33333333-3333-4333-8333-333333333333"
const CHARACTERISTIC_ID = "44444444-4444-4444-8444-444444444444"

const dnd = vi.hoisted(() => ({
  onDragEnd: null as
    | null
    | ((event: {
        active: { id: string }
        over: { id: string } | null
      }) => void),
}))

const cabinApi = vi.hoisted(() => ({
  getCabinSettings: vi.fn(),
  createCabinCatalogItem: vi.fn(),
  createIdempotencyKey: vi.fn(() => "55555555-5555-4555-8555-555555555555"),
  updateCabinCatalogItem: vi.fn(),
  deleteCabinCatalogItem: vi.fn(),
  replaceCabinCatalogOrder: vi.fn(),
  replaceCabinTypeDimensions: vi.fn(),
}))

vi.mock("@dnd-kit/core", async () => {
  const actual =
    await vi.importActual<typeof import("@dnd-kit/core")>("@dnd-kit/core")
  return {
    ...actual,
    DndContext: ({
      children,
      onDragEnd,
    }: {
      children: ReactNode
      onDragEnd: typeof dnd.onDragEnd
    }) => {
      dnd.onDragEnd = onDragEnd
      return children
    },
    useSensor: () => ({}),
    useSensors: () => [],
  }
})

vi.mock("@dnd-kit/sortable", async () => {
  const actual =
    await vi.importActual<typeof import("@dnd-kit/sortable")>(
      "@dnd-kit/sortable"
    )
  return {
    ...actual,
    SortableContext: ({ children }: { children: ReactNode }) => children,
    useSortable: () => ({
      attributes: {},
      isDragging: false,
      listeners: {},
      setNodeRef: vi.fn(),
      transform: null,
      transition: null,
    }),
  }
})

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "settings-token",
    currentUser: {
      id: "settings-user",
      username: "admin",
      displayName: "Администратор",
      firstName: null,
      lastName: null,
      email: null,
      principalType: "USER",
      globalRole: "WMS_ADMIN",
      rentalAccess: false,
      warehouseAccessAll: true,
      warehouseAccesses: [],
    },
  }),
}))

vi.mock("./cabin-status-colors-settings", () => ({
  CabinStatusColorsSettings: () => <p>Редактор глобальной палитры</p>,
}))

vi.mock(
  "@/features/rental-items/api/asset-rental-items-api",
  async (importOriginal) => ({
    ...(await importOriginal<
      typeof import("@/features/rental-items/api/asset-rental-items-api")
    >()),
    ...cabinApi,
  })
)

import { CabinCompositionSettingsPage } from "@/features/settings/cabin-composition"
import { ApiError } from "@/lib/api-client"

function catalogItem(
  id: string,
  kind: "TYPE" | "DIMENSION" | "FINISHING" | "CHARACTERISTIC",
  name: string,
  overrides: Record<string, unknown> = {}
) {
  return {
    id,
    version: 2,
    kind,
    name,
    active: true,
    sortOrder: 0,
    customerVisible: true,
    createdAt: "2026-07-28T10:00:00Z",
    updatedAt: "2026-07-28T10:00:00Z",
    ...overrides,
  }
}

const settings = {
  types: [catalogItem(TYPE_ID, "TYPE", "БК-2")],
  dimensions: [catalogItem(DIMENSION_ID, "DIMENSION", "2.4x6")],
  finishings: [catalogItem(FINISHING_ID, "FINISHING", "ЛДСП")],
  characteristics: [
    catalogItem(CHARACTERISTIC_ID, "CHARACTERISTIC", "Металлическая дверь"),
  ],
  typeDimensions: [
    { typeId: TYPE_ID, dimensionId: DIMENSION_ID, sortOrder: 0 },
  ],
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <CabinCompositionSettingsPage />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
  cabinApi.getCabinSettings.mockResolvedValue(settings)
  cabinApi.replaceCabinCatalogOrder.mockResolvedValue(settings)
  cabinApi.replaceCabinTypeDimensions.mockResolvedValue(settings)
  dnd.onDragEnd = null
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("CabinCompositionSettingsPage", () => {
  it("opens global status colors from the existing cabin settings without a warehouse selector", async () => {
    renderPage()
    await userEvent
      .setup()
      .click(await screen.findByRole("tab", { name: "Цвета статусов" }))
    expect(screen.getByText("Редактор глобальной палитры")).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Добавить" })).toBeNull()
  })

  it("shows names only and saves selected dimensions for a type", async () => {
    const user = userEvent.setup()
    renderPage()

    expect(await screen.findByRole("tab", { name: "Типы" })).toBeTruthy()
    expect(
      screen.queryByRole("heading", { name: "Настройки бытовок" })
    ).toBeNull()
    expect(screen.getByText("БК-2")).toBeTruthy()
    expect(screen.queryByText("Металлическая дверь")).toBeNull()
    expect(screen.queryByText(TYPE_ID)).toBeNull()
    expect(screen.queryByText(DIMENSION_ID)).toBeNull()
    expect(screen.getByRole("button", { name: "Изменить БК-2" })).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Удалить" })).toBeNull()

    await user.click(
      screen.getByRole("button", { name: "Настроить габариты БК-2" })
    )
    const dialog = await screen.findByRole("dialog", {
      name: "Габариты для типа «БК-2»",
    })
    const checkbox = within(dialog).getByRole("checkbox", { name: "2.4x6" })
    expect((checkbox as HTMLButtonElement).getAttribute("data-state")).toBe(
      "checked"
    )
    await user.click(
      within(dialog).getByRole("button", { name: "Сохранить габариты" })
    )

    expect(cabinApi.replaceCabinTypeDimensions).toHaveBeenCalledWith({
      accessToken: "settings-token",
      typeId: TYPE_ID,
      expectedVersion: 2,
      dimensionIds: [DIMENSION_ID],
    })
  })

  it("persists the complete kind order after a catalog card is moved", async () => {
    const secondType = catalogItem(SECOND_TYPE_ID, "TYPE", "БК-3", {
      version: 3,
      sortOrder: 0,
    })
    const firstType = catalogItem(TYPE_ID, "TYPE", "БК-2", {
      sortOrder: 1,
    })
    cabinApi.getCabinSettings.mockResolvedValue({
      ...settings,
      types: [firstType, secondType],
    })
    renderPage()

    await screen.findByRole("tab", { name: "Типы" })
    expect(
      screen
        .getByText("БК-3")
        .compareDocumentPosition(screen.getByText("БК-2")) &
        Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()

    dnd.onDragEnd?.({
      active: { id: TYPE_ID },
      over: { id: SECOND_TYPE_ID },
    })

    await waitFor(() =>
      expect(cabinApi.replaceCabinCatalogOrder).toHaveBeenCalledWith({
        accessToken: "settings-token",
        kind: "TYPE",
        items: [
          { id: TYPE_ID, expectedVersion: 2 },
          { id: SECOND_TYPE_ID, expectedVersion: 3 },
        ],
      })
    )
    await waitFor(() =>
      expect(
        screen
          .getByText("БК-2")
          .compareDocumentPosition(screen.getByText("БК-3")) &
          Node.DOCUMENT_POSITION_FOLLOWING
      ).toBeTruthy()
    )
  })

  it("shows linked type dimensions in their global catalog order", async () => {
    cabinApi.getCabinSettings.mockResolvedValue({
      ...settings,
      dimensions: [
        catalogItem(DIMENSION_ID, "DIMENSION", "6 × 2,4", { sortOrder: 2 }),
        catalogItem(SECOND_DIMENSION_ID, "DIMENSION", "4 × 2,4", {
          sortOrder: 1,
        }),
      ],
      typeDimensions: [
        { typeId: TYPE_ID, dimensionId: DIMENSION_ID, sortOrder: 0 },
        { typeId: TYPE_ID, dimensionId: SECOND_DIMENSION_ID, sortOrder: 1 },
      ],
    })

    renderPage()

    expect(await screen.findByText("Габариты: 4 × 2,4, 6 × 2,4")).toBeTruthy()
  })

  it("restores the server order when saving a moved catalog card fails", async () => {
    const secondType = catalogItem(SECOND_TYPE_ID, "TYPE", "БК-3", {
      version: 3,
      sortOrder: 0,
    })
    const firstType = catalogItem(TYPE_ID, "TYPE", "БК-2", {
      sortOrder: 1,
    })
    cabinApi.getCabinSettings.mockResolvedValue({
      ...settings,
      types: [firstType, secondType],
    })
    cabinApi.replaceCabinCatalogOrder.mockRejectedValue(
      new ApiError("Не удалось сохранить порядок.", 400)
    )
    renderPage()

    await screen.findByRole("tab", { name: "Типы" })
    dnd.onDragEnd?.({
      active: { id: TYPE_ID },
      over: { id: SECOND_TYPE_ID },
    })

    await waitFor(() =>
      expect(cabinApi.replaceCabinCatalogOrder).toHaveBeenCalledTimes(1)
    )
    await waitFor(() =>
      expect(
        screen
          .getByText("БК-3")
          .compareDocumentPosition(screen.getByText("БК-2")) &
          Node.DOCUMENT_POSITION_FOLLOWING
      ).toBeTruthy()
    )
    await waitFor(() =>
      expect(
        cabinApi.getCabinSettings.mock.calls.length
      ).toBeGreaterThanOrEqual(2)
    )
  })

  it("uses the catalog-specific editor title and saves characteristic visibility", async () => {
    const user = userEvent.setup()
    cabinApi.updateCabinCatalogItem.mockResolvedValue(
      settings.characteristics[0]
    )
    renderPage()

    await screen.findByRole("tab", { name: "Типы" })
    await user.click(screen.getByRole("button", { name: "Изменить БК-2" }))
    const typeEditor = await screen.findByRole("dialog", {
      name: "Настройка типа бытовки",
    })
    expect(
      within(typeEditor).queryByRole("checkbox", {
        name: "Отображать клиенту",
      })
    ).toBeNull()
    await user.click(within(typeEditor).getByRole("button", { name: "Отмена" }))

    await user.click(await screen.findByRole("tab", { name: "Характеристики" }))
    await user.click(
      screen.getByRole("button", { name: "Изменить Металлическая дверь" })
    )
    const characteristicEditor = await screen.findByRole("dialog", {
      name: "Настройка характеристики",
    })
    const customerVisible = within(characteristicEditor).getByRole("checkbox", {
      name: "Отображать клиенту",
    })
    expect((customerVisible as HTMLButtonElement).dataset.state).toBe("checked")
    await user.click(customerVisible)
    await user.click(
      within(characteristicEditor).getByRole("button", { name: "Сохранить" })
    )

    await waitFor(() =>
      expect(cabinApi.updateCabinCatalogItem).toHaveBeenCalledWith({
        accessToken: "settings-token",
        id: CHARACTERISTIC_ID,
        expectedVersion: 2,
        name: "Металлическая дверь",
        active: true,
        customerVisible: false,
      })
    )
  })

  it("allows saving no dimensions for a type without cabins", async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByRole("tab", { name: "Типы" })
    await user.click(
      screen.getByRole("button", { name: "Настроить габариты БК-2" })
    )

    const dialog = await screen.findByRole("dialog", {
      name: "Габариты для типа «БК-2»",
    })
    expect(
      within(dialog).getByText(
        /Можно снять все габариты только у типа без бытовок/
      )
    ).toBeTruthy()

    await user.click(within(dialog).getByRole("checkbox", { name: "2.4x6" }))
    await user.click(
      within(dialog).getByRole("button", { name: "Сохранить габариты" })
    )

    await waitFor(() =>
      expect(cabinApi.replaceCabinTypeDimensions).toHaveBeenCalledWith({
        accessToken: "settings-token",
        typeId: TYPE_ID,
        expectedVersion: 2,
        dimensionIds: [],
      })
    )
  })

  it("keeps the dimensions dialog open and shows the service reason when clearing is forbidden", async () => {
    const user = userEvent.setup()
    cabinApi.replaceCabinTypeDimensions.mockRejectedValue(
      new ApiError(
        "Нельзя снять все габариты: этот тип уже выбран у существующей бытовки.",
        409
      )
    )
    renderPage()

    await screen.findByRole("tab", { name: "Типы" })
    await user.click(
      screen.getByRole("button", { name: "Настроить габариты БК-2" })
    )

    const dialog = await screen.findByRole("dialog", {
      name: "Габариты для типа «БК-2»",
    })
    await user.click(within(dialog).getByRole("checkbox", { name: "2.4x6" }))
    await user.click(
      within(dialog).getByRole("button", { name: "Сохранить габариты" })
    )

    expect(
      await within(dialog).findByText(
        "Нельзя снять все габариты: этот тип уже выбран у существующей бытовки."
      )
    ).toBeTruthy()
    expect(
      screen.getByRole("dialog", { name: "Габариты для типа «БК-2»" })
    ).toBeTruthy()
  })

  it("deletes an unused catalog item after explicit confirmation and refreshes settings", async () => {
    const user = userEvent.setup()
    cabinApi.deleteCabinCatalogItem.mockResolvedValue(undefined)
    renderPage()

    await user.click(await screen.findByRole("tab", { name: "Характеристики" }))
    await screen.findByText("Металлическая дверь")
    await user.click(
      screen.getByRole("button", { name: "Изменить Металлическая дверь" })
    )
    const editor = await screen.findByRole("dialog", {
      name: "Настройка характеристики",
    })
    await user.click(within(editor).getByRole("button", { name: "Удалить" }))

    const confirmation = await screen.findByRole("alertdialog", {
      name: "Удалить характеристику «Металлическая дверь»?",
    })
    await user.click(
      within(confirmation).getByRole("button", { name: "Удалить" })
    )

    await waitFor(() =>
      expect(cabinApi.deleteCabinCatalogItem).toHaveBeenCalledWith({
        accessToken: "settings-token",
        id: CHARACTERISTIC_ID,
        expectedVersion: 2,
      })
    )
    await waitFor(() =>
      expect(
        cabinApi.getCabinSettings.mock.calls.length
      ).toBeGreaterThanOrEqual(2)
    )
  })

  it("keeps the catalog entry and shows the service reason when deletion is forbidden", async () => {
    const user = userEvent.setup()
    cabinApi.deleteCabinCatalogItem.mockRejectedValue(
      new ApiError("Характеристика используется в бытовке «БЫТ-042».", 409)
    )
    renderPage()

    await user.click(await screen.findByRole("tab", { name: "Характеристики" }))
    await screen.findByText("Металлическая дверь")
    await user.click(
      screen.getByRole("button", { name: "Изменить Металлическая дверь" })
    )
    const editor = await screen.findByRole("dialog", {
      name: "Настройка характеристики",
    })
    await user.click(within(editor).getByRole("button", { name: "Удалить" }))

    const confirmation = await screen.findByRole("alertdialog", {
      name: "Удалить характеристику «Металлическая дверь»?",
    })
    await user.click(
      within(confirmation).getByRole("button", { name: "Удалить" })
    )

    expect(
      (await within(confirmation).findByRole("alert")).textContent
    ).toContain("Характеристика используется в бытовке «БЫТ-042».")
    expect(screen.getByText("Металлическая дверь")).toBeTruthy()
    expect(
      screen.getByRole("alertdialog", {
        name: "Удалить характеристику «Металлическая дверь»?",
      })
    ).toBeTruthy()
    expect(cabinApi.getCabinSettings).toHaveBeenCalledTimes(1)
  })
})
