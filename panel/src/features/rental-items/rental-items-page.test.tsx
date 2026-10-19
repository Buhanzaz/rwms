import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"

const assetApi = vi.hoisted(() => ({
  listAssetRentalItems: vi.fn(),
}))
const coversApi = vi.hoisted(() => ({
  loadRentalItemCoverPage: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    status: "authenticated",
    accessToken: "asset-token",
    currentUser: { id: "operator-1" },
  }),
}))

vi.mock("@/features/auth/warehouse-access", () => ({
  hasWarehouseAccess: () => false,
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouse: { id: WAREHOUSE_ID, name: "СПБ2" },
  }),
}))

vi.mock("@/features/rental-items/api/asset-rental-items-api", () => assetApi)

vi.mock("@/features/rental-items/use-rental-item-covers", () => ({
  RENTAL_ITEM_COVERS_QUERY_KEY: ["rental-item-media-covers"],
  loadRentalItemCoverPage: coversApi.loadRentalItemCoverPage,
}))

vi.mock("@/features/rental-items/rental-items-filtering", () => ({
  buildRentalItemsFilterOptions: () => [],
  filterRentalItemsByFilters: (items: RentalItemDto[]) => items,
  pruneRentalItemsFilters: (filters: Record<string, string[]>) => filters,
}))

vi.mock("@/features/rental-items/rental-items-grid-format", () => ({
  getEffectiveRentalItemsGridFormat: () => ({ columns: 1, rows: 1 }),
  getRentalItemsDefaultGridSize: () => 1,
  getRentalItemsGridFormatMax: () => 1,
  isRentalItemsMobileViewport: () => false,
  normalizeRentalItemsGridSize: (value: number | null) => value ?? 1,
  useRentalItemsGridViewport: () => ({ width: 1280, height: 800 }),
}))

vi.mock("@/components/page-toolbar", () => ({
  PageToolbar: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  PageToolbarActions: ({ children }: { children: ReactNode }) => (
    <div>{children}</div>
  ),
  PageToolbarContent: ({ children }: { children: ReactNode }) => (
    <div>{children}</div>
  ),
}))

vi.mock("@/components/ui/toggle-group", () => ({
  ToggleGroup: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  ToggleGroupItem: ({
    children,
    onClick,
    ...props
  }: {
    children: ReactNode
    onClick?: () => void
  }) => (
    <button type="button" onClick={onClick} {...props}>
      {children}
    </button>
  ),
}))

vi.mock("@/features/rental-items/rental-items-table-view", () => ({
  RentalItemsTableView: ({ items }: { items: RentalItemDto[] }) => (
    <div data-slot="rental-items-table-grid">
      <div data-testid="rental-items-scroll-area">
        {items.map((item) => (
          <div key={item.id}>{item.number}</div>
        ))}
      </div>
    </div>
  ),
}))

vi.mock("@/features/rental-items/rental-items-grid-view", () => ({
  RentalItemsGridView: ({ items }: { items: RentalItemDto[] }) => (
    <div data-grid-format="1x1" data-testid="rental-items-grid-scroll-area">
      {items.map((item) => (
        <div key={item.id}>{item.number}</div>
      ))}
    </div>
  ),
}))

vi.mock("@/features/rental-items/rental-items-filters", () => ({
  RentalItemsFilters: () => null,
}))

vi.mock("@/features/rental-items/rental-items-column-settings-dialog", () => ({
  RentalItemsColumnSettingsDialog: () => null,
}))

vi.mock("@/features/rental-items/rental-items-grid-settings-dialog", () => ({
  RentalItemsGridSettingsDialog: () => null,
}))

vi.mock("@/features/rental-items/rental-item-photo-dialog", () => ({
  RentalItemPhotoDialog: () => null,
}))

vi.mock("@/features/rental-items/rental-item-create-dialog", () => ({
  RentalItemCreateDialog: () => null,
}))

import { RentalItemsPage } from "@/features/rental-items/rental-items-page"

function rentalItem(index: number): RentalItemDto {
  return {
    id: `00000000-0000-4000-8000-${String(index).padStart(12, "0")}`,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    number: `БЫТ-${String(index).padStart(3, "0")}`,
    rentalTypeId: "11111111-1111-4111-8111-111111111111",
    dimensionId: "22222222-2222-4222-8222-222222222222",
    finishingId: "33333333-3333-4333-8333-333333333333",
    type: "БК-1",
    dimensions: "2.4x6",
    finishing: "ДВП",
    category: null,
    characteristics: [],
    linoleum: false,
    status: "WAREHOUSE",
    comment: null,
    contents: null,
    contentsItems: [],
    shipmentDate: null,
    tenant: null,
    price: null,
    passport: {},
    tags: [],
  }
}

function rentalItemsPage(
  items: RentalItemDto[],
  page: number,
  totalPages: number
) {
  return {
    content: items,
    page,
    size: 200,
    totalElements: items.length * totalPages,
    totalPages,
  }
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <RentalItemsPage />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

function scrollToListEnd() {
  const scrollArea = screen.getByTestId("rental-items-scroll-area")
  Object.defineProperties(scrollArea, {
    clientHeight: { configurable: true, value: 100 },
    scrollHeight: { configurable: true, value: 400 },
    scrollTop: { configurable: true, value: 240 },
  })
  fireEvent.scroll(scrollArea)
}

function scrollToGridEnd() {
  const scrollArea = screen.getByTestId("rental-items-grid-scroll-area")
  Object.defineProperties(scrollArea, {
    clientHeight: { configurable: true, value: 100 },
    scrollHeight: { configurable: true, value: 400 },
    scrollTop: { configurable: true, value: 240 },
  })
  fireEvent.scroll(scrollArea)
}

beforeEach(() => {
  coversApi.loadRentalItemCoverPage.mockResolvedValue({ items: [] })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("RentalItemsPage lazy loading", () => {
  it("fetches the next page once when the table is scrolled near its end and removes duplicate ids", async () => {
    const first = rentalItem(1)
    const second = rentalItem(2)
    assetApi.listAssetRentalItems.mockImplementation(
      ({ page = 0 }: { page?: number }) =>
        Promise.resolve(
          page === 0
            ? rentalItemsPage([first], 0, 2)
            : rentalItemsPage([first, second], 1, 2)
        )
    )

    renderPage()

    await screen.findByText("БЫТ-001")
    scrollToListEnd()
    scrollToListEnd()

    await screen.findByText("БЫТ-002")
    expect(assetApi.listAssetRentalItems).toHaveBeenCalledTimes(2)
    expect(assetApi.listAssetRentalItems).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({
        warehouseId: WAREHOUSE_ID,
        page: 0,
        size: 200,
        search: "",
      })
    )
    expect(assetApi.listAssetRentalItems).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ page: 1 })
    )
    expect(screen.getAllByText("БЫТ-001")).toHaveLength(1)

    scrollToListEnd()
    await waitFor(() =>
      expect(assetApi.listAssetRentalItems).toHaveBeenCalledTimes(2)
    )
  })

  it("resets to page zero for a changed warehouse search", async () => {
    assetApi.listAssetRentalItems.mockImplementation(
      ({ page = 0, search = "" }: { page?: number; search?: string }) =>
        Promise.resolve(
          rentalItemsPage(
            [rentalItem(search === "СПБ2" ? 2 : 1)],
            page,
            page === 0 ? 1 : 0
          )
        )
    )

    renderPage()

    await screen.findByText("БЫТ-001")
    fireEvent.change(screen.getByLabelText("Поиск по реестру склада"), {
      target: { value: "СПБ2" },
    })

    await screen.findByText("БЫТ-002")
    expect(assetApi.listAssetRentalItems).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 0, search: "СПБ2" })
    )
  })

  it("loads the next page from the virtual card grid scroll container", async () => {
    window.localStorage.setItem(
      `rental-items:${WAREHOUSE_ID}:view-mode`,
      JSON.stringify("grid")
    )
    assetApi.listAssetRentalItems.mockImplementation(
      ({ page = 0 }: { page?: number }) =>
        Promise.resolve(
          page === 0
            ? rentalItemsPage([rentalItem(1)], 0, 2)
            : rentalItemsPage([rentalItem(2)], 1, 2)
        )
    )

    renderPage()

    await screen.findByText("БЫТ-001")
    scrollToGridEnd()

    await screen.findByText("БЫТ-002")
    expect(assetApi.listAssetRentalItems).toHaveBeenCalledTimes(2)
    expect(assetApi.listAssetRentalItems).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1 })
    )
  })

  it("keeps loaded rows visible and lets the user retry a failed next page", async () => {
    const first = rentalItem(1)
    const second = rentalItem(2)
    let secondPageAttempt = 0
    assetApi.listAssetRentalItems.mockImplementation(
      ({ page = 0 }: { page?: number }) => {
        if (page === 0) {
          return Promise.resolve(rentalItemsPage([first], 0, 2))
        }

        secondPageAttempt += 1
        return secondPageAttempt === 1
          ? Promise.reject(new Error("Temporary upstream error"))
          : Promise.resolve(rentalItemsPage([second], 1, 2))
      }
    )

    renderPage()

    await screen.findByText("БЫТ-001")
    scrollToListEnd()

    await screen.findByRole("alert")
    expect(screen.getByText("БЫТ-001")).toBeTruthy()

    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: "Повторить загрузку" }))

    await screen.findByText("БЫТ-002")
    expect(assetApi.listAssetRentalItems).toHaveBeenCalledTimes(3)
  })
})
