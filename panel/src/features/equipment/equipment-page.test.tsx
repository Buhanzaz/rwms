import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, waitFor, within } from "@testing-library/react"
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

vi.mock("@/features/equipment/api/equipment-rental-usages-api", () => ({
  getEquipmentItemsWithRentalUsages:
    equipmentApi.getEquipmentItemsWithRentalUsages,
}))
vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: "asset-token" }),
}))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouse: {
      id: WAREHOUSE_ID,
      serviceId: WAREHOUSE_ID,
      version: 1,
      code: "SPB",
      name: "Склад Санкт-Петербург",
      city: "Санкт-Петербург",
      address: null,
      timeZone: "Europe/Moscow",
      active: true,
      sortOrder: 1,
    },
  }),
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
    code: name === "Конвектор" ? "CONVECTOR" : "CHAIR",
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
  locationText: string
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
    expect(usage.getByText(params.locationText)).toBeTruthy()
    expect(usage.queryByText("Доступно")).toBeNull()
    expect(usage.queryByText(/^\d[\d\s]*\sшт\.$/)).toBeNull()
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
    expect(header!.children).toHaveLength(3)
    expect(row!.children).toHaveLength(3)
    expect(within(header!).queryByText("Склад")).toBeNull()
    expect(within(header!).queryByText("Количество")).toBeNull()
    expect(within(header!).queryByText("Доступно")).toBeNull()
    expect(header!.className).toContain("grid-cols-3")
    expect(header!.className).toContain("gap-4")
    expect(header!.className).toContain("text-left")
    expect(row!.className).toContain("grid-cols-3")
    expect(row!.className).toContain("gap-4")
    expect(row!.className).toContain("text-left")
    expect(usageGrid.querySelector(".text-right")).toBeNull()
    expect(usageGrid.querySelector(".justify-between")).toBeNull()
  })
}

beforeEach(() => {
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
      locationText: "В бытовке",
    })

    await user.click(
      grid.getByRole("button", { name: "Развернуть оборудование Стул" })
    )
    expectExpandedUsage({
      pageContainer: container,
      rentalItemNumber: "БЫТ-002",
      rentalItemType: "БК-02",
      statusText: "Аренда",
      locationText: "В аренде",
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
})
