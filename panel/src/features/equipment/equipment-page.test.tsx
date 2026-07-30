import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom"

import type { EquipmentItemDto } from "@/types/equipment"

const WAREHOUSE_ID = "00000000-0000-0000-0000-000000000001"
const RENTAL_ITEM_ID = "00000000-0000-0000-0000-000000000201"
const SECOND_RENTAL_ITEM_ID = "00000000-0000-0000-0000-000000000202"

const equipmentApi = vi.hoisted(() => ({
  getEquipmentItemsWithRentalUsages: vi.fn(),
}))
const authApi = vi.hoisted(() => ({
  useAuth: vi.fn(),
}))
const movementApi = vi.hoisted(() => ({
  createIdempotencyKey: vi.fn(),
  moveToStock: vi.fn(),
}))

vi.mock("@/features/equipment/api/equipment-rental-usages-api", () => ({
  getEquipmentItemsWithRentalUsages:
    equipmentApi.getEquipmentItemsWithRentalUsages,
}))
vi.mock("@/features/auth/use-auth", () => ({
  useAuth: authApi.useAuth,
}))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouse: {
      id: WAREHOUSE_ID,
      version: 1,
      name: "Склад Санкт-Петербург",
      city: "Санкт-Петербург",
      address: null,
      timeZone: "Europe/Moscow",
      active: true,
      sortOrder: 1,
    },
  }),
}))
vi.mock("@/features/equipment/api/equipment-usage-movements-api", () => ({
  createEquipmentUsageMoveIdempotencyKey: movementApi.createIdempotencyKey,
  moveEquipmentUsageToStock: movementApi.moveToStock,
}))
vi.mock("sonner", () => ({
  toast: { error: vi.fn(), success: vi.fn() },
}))

import { EquipmentPage } from "@/features/equipment/equipment-page"

function equipmentItem(
  id: string,
  name: string,
  usages: EquipmentItemDto["usages"]
): EquipmentItemDto {
  return {
    id,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    category: name === "Конвектор" ? "ELECTRICAL" : "FURNITURE",
    name,
    active: true,
    comment: null,
    totalQuantity: 12,
    stockQuantity: 7,
    cabinStockQuantity: 5,
    rentedQuantity: 0,
    writtenOffQuantity: 0,
    lostQuantity: 0,
    activeHeldQuantity: 0,
    reservedQuantity: 0,
    availableQuantity: 12,
    availableStock: 7,
    balances: [],
    usages,
  }
}

function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname}</div>
}

function expectExpandedUsage(params: {
  pageContainer: HTMLElement
  rentalItemNumber: string
  rentalItemType: string
  statusText: string
  quantity: string
}) {
  const usageGrids = Array.from(
    params.pageContainer.querySelectorAll<HTMLElement>(
      '[data-slot="equipment-usage-grid"]'
    )
  )
  expect(usageGrids).toHaveLength(2)

  usageGrids.forEach((usageGrid) => {
    const usage = within(usageGrid)
    expect(usage.getByText(params.rentalItemNumber)).toBeTruthy()
    expect(usage.getByText(params.rentalItemType)).toBeTruthy()
    expect(usage.getByText(params.statusText)).toBeTruthy()
    expect(usage.getByText(params.quantity)).toBeTruthy()
    expect(usage.queryByText("В бытовке")).toBeNull()
    expect(usage.queryByText("В аренде")).toBeNull()
    expect(usage.queryByText("Доступно")).toBeNull()
    expect(usage.queryByText("Склад Санкт-Петербург")).toBeNull()
    expect(usage.queryByText("SPB · Санкт-Петербург")).toBeNull()

    const header = usageGrid.querySelector<HTMLElement>(
      '[data-slot="equipment-usage-header"]'
    )
    const row = usageGrid.querySelector<HTMLElement>(
      '[data-slot="equipment-usage-row"]'
    )
    expect(header).not.toBeNull()
    expect(row).not.toBeNull()
    expect(header!.children).toHaveLength(5)
    expect(row!.children).toHaveLength(5)
    expect(within(header!).queryByText("Склад")).toBeNull()
    expect(within(header!).getByText("Количество")).toBeTruthy()
    expect(within(header!).queryByText("Доступно")).toBeNull()
    expect(header!.className).toContain("grid-cols-5")
    expect(header!.className).toContain("gap-4")
    expect(header!.className).toContain("text-left")
    expect(row!.className).toContain("grid-cols-5")
    expect(row!.className).toContain("gap-4")
    expect(row!.className).toContain("text-left")
    expect(usageGrid.querySelector(".text-right")).toBeNull()
    expect(usageGrid.querySelector(".justify-between")).toBeNull()
  })
}

beforeEach(() => {
  authApi.useAuth.mockReturnValue({
    accessToken: "asset-token",
    currentUser: {
      id: "operator-1",
      username: "operator",
      displayName: "Оператор",
      firstName: null,
      lastName: null,
      email: null,
      principalType: "USER",
      globalRole: "WAREHOUSE_MANAGER",
      warehouseAccessAll: false,
      warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level: "MANAGE" }],
    },
  })
  movementApi.createIdempotencyKey.mockReturnValue(
    "00000000-0000-0000-0000-000000000901"
  )
  movementApi.moveToStock.mockResolvedValue({
    id: "00000000-0000-0000-0000-000000000999",
    version: 0,
    equipmentId: "00000000-0000-0000-0000-000000000101",
    sourceBalanceId: "00000000-0000-0000-0000-000000000301",
    targetBalanceId: "00000000-0000-0000-0000-000000000401",
    quantity: 3,
    kind: "CABIN_TO_STOCK",
    occurredAt: "2026-07-22T10:00:00Z",
  })
  equipmentApi.getEquipmentItemsWithRentalUsages.mockResolvedValue([
    equipmentItem("00000000-0000-0000-0000-000000000101", "Конвектор", [
      {
        id: "00000000-0000-0000-0000-000000000301",
        balanceVersion: 4,
        rentalItemId: RENTAL_ITEM_ID,
        rentalItemNumber: "БЫТ-001",
        rentalItemType: "БК-01",
        rentalItemStatus: "WAREHOUSE",
        warehouseId: WAREHOUSE_ID,
        locationKind: "CABIN_NON_RENTED",
        quantity: 5,
        availableQuantity: 4,
      },
    ]),
    equipmentItem("00000000-0000-0000-0000-000000000102", "Стул", [
      {
        id: "00000000-0000-0000-0000-000000000302",
        balanceVersion: 6,
        rentalItemId: SECOND_RENTAL_ITEM_ID,
        rentalItemNumber: "БЫТ-002",
        rentalItemType: "БК-02",
        rentalItemStatus: "RENTED",
        warehouseId: WAREHOUSE_ID,
        locationKind: "CABIN_RENTED",
        quantity: 9,
        availableQuantity: 8,
      },
    ]),
  ])
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("EquipmentPage", () => {
  it("shows the loading message in the top-left corner of the equipment grid", async () => {
    equipmentApi.getEquipmentItemsWithRentalUsages.mockImplementationOnce(
      () => new Promise(() => undefined)
    )
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/"]}>
          <Routes>
            <Route path="/" element={<EquipmentPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )

    const loadingState = await screen.findByText("Загрузка оборудования...")

    expect(loadingState.className).toContain("items-start")
    expect(loadingState.className).toContain("justify-start")
    expect(loadingState.className).not.toContain("items-center")
  })

  it("keeps an empty desktop grid header and fills the remaining panel height", async () => {
    equipmentApi.getEquipmentItemsWithRentalUsages.mockResolvedValueOnce([])
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/"]}>
          <Routes>
            <Route path="/" element={<EquipmentPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )

    const desktopGrid = await waitFor(() => {
      const element = container.querySelector<HTMLElement>(
        '[data-slot="operations-list-grid"]'
      )
      expect(element).not.toBeNull()
      return element!
    })

    expect(
      within(desktopGrid).getByRole("button", { name: "Наименование" })
    ).toBeTruthy()
    expect(desktopGrid.className).toContain("min-h-full")
    expect(desktopGrid.parentElement?.className).toContain("flex-1")
    expect(desktopGrid.parentElement?.className).toContain("block")
    expect(desktopGrid.parentElement?.className).not.toContain("hidden")
    expect(screen.queryByText("Доп. оборудование не найдено.")).toBeNull()
  })

  it("keeps search and the equipment grid available without collapsible filters", async () => {
    const user = userEvent.setup()
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/"]}>
          <Routes>
            <Route path="/" element={<EquipmentPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )

    await waitFor(() =>
      expect(
        container.querySelector('[data-slot="operations-list-grid"]')
      ).not.toBeNull()
    )
    expect(
      screen.queryByRole("button", { name: /фильтры оборудования/i })
    ).toBeNull()
    expect(document.getElementById("equipment-filters")).toBeNull()
    expect(screen.queryByRole("button", { name: "Категория" })).toBeNull()

    const search = screen.getByRole("searchbox", {
      name: "Поиск оборудования",
    })
    await user.type(search, "Конвектор")
    await waitFor(() =>
      expect(
        equipmentApi.getEquipmentItemsWithRentalUsages
      ).toHaveBeenLastCalledWith("asset-token", {
        warehouseId: WAREHOUSE_ID,
        search: "Конвектор",
      })
    )
    expect(
      container.querySelector('[data-slot="operations-list-grid"]')
    ).not.toBeNull()
  })

  it("shows the same compact, left-aligned cabin submenu for every equipment category", async () => {
    const user = userEvent.setup()
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/"]}>
          <Routes>
            <Route path="/" element={<EquipmentPage />} />
            <Route path="*" element={<LocationProbe />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )

    await waitFor(() =>
      expect(
        equipmentApi.getEquipmentItemsWithRentalUsages
      ).toHaveBeenCalledWith("asset-token", {
        warehouseId: WAREHOUSE_ID,
        search: "",
      })
    )
    const desktopGrid = await waitFor(() => {
      const element = container.querySelector(
        '[data-slot="operations-list-grid"]'
      )
      expect(element).not.toBeNull()
      return element as HTMLElement
    })
    const grid = within(desktopGrid)
    const convectorToggle = grid.getByRole("button", {
      name: "Развернуть оборудование Конвектор",
    })
    const convectorTotalsRow = convectorToggle.closest("tr")
    expect(convectorTotalsRow).not.toBeNull()
    expect(within(convectorTotalsRow!).getByText("12")).toBeTruthy()
    expect(within(convectorTotalsRow!).getByText("7")).toBeTruthy()
    expect(within(convectorTotalsRow!).getByText("5")).toBeTruthy()
    expect(grid.getByRole("button", { name: "Всего" })).toBeTruthy()
    expect(grid.getByRole("button", { name: "На складе" })).toBeTruthy()
    expect(grid.getByRole("button", { name: "В бытовках" })).toBeTruthy()

    await user.click(convectorToggle)
    expectExpandedUsage({
      pageContainer: container,
      rentalItemNumber: "БЫТ-001",
      rentalItemType: "БК-01",
      statusText: "Склад",
      quantity: "5",
    })

    await user.click(
      grid.getByRole("button", { name: "Развернуть оборудование Стул" })
    )
    expectExpandedUsage({
      pageContainer: container,
      rentalItemNumber: "БЫТ-002",
      rentalItemType: "БК-02",
      statusText: "Аренда",
      quantity: "9",
    })

    await user.click(
      grid.getByRole("button", { name: "Развернуть оборудование Конвектор" })
    )
    await user.click(
      grid.getByRole("button", {
        name: "Открыть карточку бытовки БЫТ-001",
      })
    )
    expect(within(container).getByTestId("location").textContent).toBe(
      `/warehouse/${RENTAL_ITEM_ID}`
    )
  })

  it("moves the selected available quantity to stock through asset-service", async () => {
    const user = userEvent.setup()
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/"]}>
          <Routes>
            <Route path="/" element={<EquipmentPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )

    const desktopGrid = await waitFor(() => {
      const element = container.querySelector(
        '[data-slot="operations-list-grid"]'
      )
      expect(element).not.toBeNull()
      return element as HTMLElement
    })
    await user.click(
      within(desktopGrid).getByRole("button", {
        name: "Развернуть оборудование Конвектор",
      })
    )
    await user.click(
      screen.getAllByRole("button", { name: "Переместить на склад" })[0]!
    )

    const dialog = await screen.findByRole("alertdialog", {
      name: "Переместить на склад?",
    })
    await user.clear(within(dialog).getByLabelText("Количество"))
    await user.type(within(dialog).getByLabelText("Количество"), "3")
    await user.click(
      within(dialog).getByRole("button", { name: "Переместить на склад" })
    )

    await waitFor(() =>
      expect(movementApi.moveToStock).toHaveBeenCalledWith({
        accessToken: "asset-token",
        idempotencyKey: "00000000-0000-0000-0000-000000000901",
        input: {
          equipmentId: "00000000-0000-0000-0000-000000000101",
          warehouseId: WAREHOUSE_ID,
          rentalItemId: RENTAL_ITEM_ID,
          sourceLocationKind: "CABIN_NON_RENTED",
          sourceExpectedVersion: 4,
          targetExpectedVersion: 0,
          quantity: 3,
        },
      })
    )
  })

  it("does not offer a move command without warehouse manage access", async () => {
    const user = userEvent.setup()
    authApi.useAuth.mockReturnValue({
      accessToken: "asset-token",
      currentUser: {
        id: "operator-1",
        username: "operator",
        displayName: "Оператор",
        firstName: null,
        lastName: null,
        email: null,
        principalType: "USER",
        globalRole: "WAREHOUSE_MANAGER",
        warehouseAccessAll: false,
        warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level: "EDIT" }],
      },
    })
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/"]}>
          <Routes>
            <Route path="/" element={<EquipmentPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )

    const desktopGrid = await waitFor(() => {
      const element = container.querySelector(
        '[data-slot="operations-list-grid"]'
      )
      expect(element).not.toBeNull()
      return element as HTMLElement
    })
    await user.click(
      within(desktopGrid).getByRole("button", {
        name: "Развернуть оборудование Конвектор",
      })
    )

    expect(
      screen.queryByRole("button", { name: "Переместить на склад" })
    ).toBeNull()
    expect(screen.queryByText("Действие")).toBeNull()
  })
})
