import type { ReactNode } from "react"
import { cleanup, render, screen, within } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { MemoryRouter } from "react-router-dom"
import {
  afterAll,
  afterEach,
  beforeAll,
  describe,
  expect,
  it,
  vi,
} from "vitest"

import App from "@/App"
import type { CurrentUser } from "@/features/auth/auth-model"

const mocks = vi.hoisted(() => ({
  getActiveInventory: vi.fn(),
  useAuth: vi.fn(),
  useWarehouse: vi.fn(),
}))

vi.mock("@/contexts/warehouse-provider", () => ({
  WarehouseProvider: ({ children }: { children: ReactNode }) => children,
}))
vi.mock("@/features/auth/use-auth", () => ({
  useAuth: mocks.useAuth,
}))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: mocks.useWarehouse,
}))
vi.mock("@/hooks/use-warehouse-realtime", () => ({
  useWarehouseRealtime: vi.fn(),
}))
vi.mock("@/hooks/use-mobile", () => ({
  useIsMobile: () => false,
  useIsTabletOrSmaller: () => false,
}))
vi.mock("@/features/inventory/api/inventory-api", () => ({
  INVENTORY_QUERY_KEY: ["inventory"],
  getActiveInventory: mocks.getActiveInventory,
  inventoryActiveQueryKey: (warehouseId: string) => ["inventory", warehouseId],
  subscribeInventory: () => () => undefined,
}))
vi.mock("@/features/booking", () => ({
  BookingCatalogPage: () => null,
  BookingContinuePage: () => null,
  BookingSelectionProvider: ({ children }: { children: ReactNode }) => children,
}))
vi.mock("@/features/logistics/logistics-shipments-page", () => ({
  LogisticsShipmentsPage: () => <div data-testid="shipments-route" />,
}))
vi.mock(
  "@/features/logistics/warehouse-transfers/warehouse-transfers-page",
  () => ({
    WarehouseTransfersPage: () => <div data-testid="transfers-route" />,
  })
)
vi.mock("@/features/home/home-page", () => ({
  HomePage: () => <div data-testid="home-route" />,
}))

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"

const currentUser: CurrentUser = {
  id: "system-admin",
  username: "admin",
  displayName: "Администратор",
  firstName: null,
  lastName: null,
  email: null,
  principalType: "USER",
  globalRole: "SYSTEM_ADMIN",
  rentalAccess: true,
  warehouseAccessAll: true,
  warehouseAccesses: [],
}

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}

beforeAll(() => {
  vi.stubGlobal("ResizeObserver", ResizeObserverMock)
  HTMLElement.prototype.hasPointerCapture = () => false
  HTMLElement.prototype.setPointerCapture = () => undefined
  HTMLElement.prototype.releasePointerCapture = () => undefined
  HTMLElement.prototype.scrollIntoView = () => undefined
})

afterAll(() => {
  vi.unstubAllGlobals()
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("logistics navigation", () => {
  it.each([
    ["/logistics/board", "shipments-route"],
    ["/logistics/order-tasks", "shipments-route"],
    ["/logistics/tasks", "transfers-route"],
    ["/assistant", "home-route"],
    ["/clients", "home-route"],
    ["/orders", "home-route"],
    ["/settings", "home-route"],
    ["/settings/logistics", "home-route"],
  ])("redirects the removed RWMS route %s", async (initialPath, targetTestId) => {
    mocks.useAuth.mockReturnValue({
      accessToken: "panel-token",
      currentUser,
      logout: vi.fn(),
    })
    mocks.useWarehouse.mockReturnValue({
      warehouses: [
        {
          id: WAREHOUSE_ID,
          version: 0,
          name: "Основной склад",
          city: "Москва",
          address: null,
          timeZone: "Europe/Moscow",
          active: true,
          lifecycleState: "ACTIVE",
          sortOrder: 0,
        },
      ],
      selectedWarehouse: {
        id: WAREHOUSE_ID,
        name: "Основной склад",
        city: "Москва",
      },
      selectedWarehouseId: WAREHOUSE_ID,
      isLoading: false,
      error: null,
      setSelectedWarehouseId: vi.fn(),
      reloadWarehouses: vi.fn(),
    })
    mocks.getActiveInventory.mockResolvedValue(null)

    render(
      <MemoryRouter initialEntries={[initialPath]}>
        <QueryClientProvider
          client={
            new QueryClient({
              defaultOptions: { queries: { retry: false } },
            })
          }
        >
          <App />
        </QueryClientProvider>
      </MemoryRouter>
    )

    await screen.findByTestId(targetTestId)
    const sidebar = document.querySelector<HTMLElement>('[data-slot="sidebar"]')
    expect(sidebar).not.toBeNull()
    expect(
      within(sidebar!).queryByRole("link", { name: "Доска логистики" })
    ).toBeNull()
    expect(
      within(sidebar!).queryByRole("link", { name: "Задания" })
    ).toBeNull()
    expect(
      within(sidebar!).queryByRole("button", { name: "Настройки" })
    ).toBeNull()
    expect(screen.getByTestId(targetTestId)).toBeTruthy()
  })
})
