import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, useLocation } from "react-router-dom"
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest"

import type { WarehouseInfo } from "@/api/warehouse-api"
import { AppSidebar } from "@/components/app-sidebar"
import {
  SidebarInset,
  SidebarProvider,
  SidebarTrigger,
  useSidebar,
} from "@/components/ui/sidebar"
import type { CurrentUser } from "@/features/auth/auth-model"

const mocks = vi.hoisted(() => ({
  getActiveInventory: vi.fn(),
  logout: vi.fn(),
  setSelectedWarehouseId: vi.fn(),
  useAuth: vi.fn(),
  useWarehouse: vi.fn(),
  viewport: {
    isMobile: false,
    isTabletOrSmaller: false,
  },
}))

vi.mock("@/features/auth/use-auth", () => ({ useAuth: mocks.useAuth }))
vi.mock("@/hooks/use-mobile", () => ({
  useIsMobile: () => mocks.viewport.isMobile,
  useIsTabletOrSmaller: () => mocks.viewport.isTabletOrSmaller,
}))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: mocks.useWarehouse,
}))
vi.mock("@/features/inventory/api/inventory-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/inventory/api/inventory-api")
  >("@/features/inventory/api/inventory-api")

  return {
    ...actual,
    getActiveInventory: mocks.getActiveInventory,
    subscribeInventory: () => () => undefined,
  }
})

const WAREHOUSE: WarehouseInfo = {
  id: "00000000-0000-4000-8000-000000000001",
  version: 0,
  name: "Saint Petersburg",
  city: "Санкт-Петербург",
  address: null,
  timeZone: "Europe/Moscow",
  active: true,
  lifecycleState: "ACTIVE",
  sortOrder: 0,
}

const MSK_WAREHOUSE: WarehouseInfo = {
  ...WAREHOUSE,
  id: "00000000-0000-4000-8000-000000000002",
  name: "Moscow",
  city: "Москва",
}

const CURRENT_USER: CurrentUser = {
  id: "user-1",
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

function LocationProbe() {
  const location = useLocation()

  return <output data-testid="location">{location.pathname}</output>
}

function SidebarStateProbe() {
  const { state, toggleSidebar } = useSidebar()

  return (
    <>
      <output data-testid="sidebar-state">{state}</output>
      <button type="button" onClick={toggleSidebar}>
        Переключить сайдбар
      </button>
    </>
  )
}

function renderSidebar({
  warehouses = [WAREHOUSE],
  currentUser = CURRENT_USER,
  open = false,
  controlled = true,
}: {
  warehouses?: WarehouseInfo[]
  currentUser?: CurrentUser
  open?: boolean
  controlled?: boolean
} = {}) {
  mocks.useAuth.mockReturnValue({
    currentUser,
    logout: mocks.logout,
  })
  mocks.useWarehouse.mockReturnValue({
    warehouses,
    selectedWarehouse: WAREHOUSE,
    selectedWarehouseId: WAREHOUSE.id,
    isLoading: false,
    error: null,
    setSelectedWarehouseId: mocks.setSelectedWarehouseId,
    reloadWarehouses: vi.fn(),
  })
  mocks.getActiveInventory.mockResolvedValue(null)

  return render(
    <MemoryRouter>
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: { queries: { retry: false } },
          })
        }
      >
        <SidebarProvider {...(controlled ? { open } : {})}>
          <SidebarTrigger />
          <AppSidebar />
          <SidebarInset data-testid="sidebar-inset" />
          <LocationProbe />
        </SidebarProvider>
      </QueryClientProvider>
    </MemoryRouter>
  )
}

afterEach(() => {
  cleanup()
  document.cookie = "sidebar_state=; path=/; max-age=0"
  vi.clearAllMocks()
  mocks.viewport.isMobile = false
  mocks.viewport.isTabletOrSmaller = false
})

beforeAll(() => {
  vi.stubGlobal("ResizeObserver", ResizeObserverMock)
  HTMLElement.prototype.hasPointerCapture = () => false
  HTMLElement.prototype.setPointerCapture = () => undefined
  HTMLElement.prototype.releasePointerCapture = () => undefined
  HTMLElement.prototype.scrollIntoView = () => undefined
})

describe("AppSidebar collapsed desktop navigation", () => {
  it("places movement inside the repair cycle between repairs and the task board", () => {
    renderSidebar({ open: true })

    const repairs = screen.getByRole("link", { name: "Ремонты" })
    const movement = screen.getByRole("link", { name: "Перемещение" })
    const taskBoard = screen.getByRole("link", { name: "Доска задач" })

    expect(movement.getAttribute("href")).toBe("/logistics/tasks")
    expect(
      repairs.compareDocumentPosition(movement) &
        Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()
    expect(
      movement.compareDocumentPosition(taskBoard) &
        Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()
    expect(screen.queryByText("Задания водителей")).toBeNull()
  })

  it("groups chat, clients and orders in Rental for every signed-in user", () => {
    const first = renderSidebar()
    expect(screen.getByText("Аренда")).toBeTruthy()
    const chat = screen.getByRole("link", { name: "Чат" })
    const clients = screen.getByRole("link", { name: "Клиенты" })
    const orders = screen.getByRole("link", { name: "Заказы" })

    expect(
      chat.compareDocumentPosition(clients) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()
    expect(
      clients.compareDocumentPosition(orders) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()
    expect(screen.queryByRole("link", { name: "Бронирование" })).toBeNull()
    expect(clients.getAttribute("href")).toBe("/clients")
    expect(orders.getAttribute("href")).toBe("/orders")

    first.unmount()
    renderSidebar({
      currentUser: { ...CURRENT_USER, rentalAccess: false },
    })

    expect(screen.getByText("Аренда")).toBeTruthy()
    expect(screen.getByRole("link", { name: "Чат" })).toBeTruthy()
    expect(screen.queryByRole("link", { name: "Бронирование" })).toBeNull()
    expect(screen.getByRole("link", { name: "Клиенты" })).toBeTruthy()
    expect(screen.getByRole("link", { name: "Заказы" })).toBeTruthy()
  })

  it("restores the desktop sidebar state after a reload", async () => {
    const user = userEvent.setup()
    const firstRender = render(
      <SidebarProvider>
        <SidebarStateProbe />
      </SidebarProvider>
    )

    expect(screen.getByTestId("sidebar-state").textContent).toBe("expanded")
    await user.click(
      screen.getByRole("button", { name: "Переключить сайдбар" })
    )
    expect(screen.getByTestId("sidebar-state").textContent).toBe("collapsed")

    firstRender.unmount()

    render(
      <SidebarProvider>
        <SidebarStateProbe />
      </SidebarProvider>
    )

    expect(screen.getByTestId("sidebar-state").textContent).toBe("collapsed")
  })

  it("keeps an icon rail and renders the warehouse name", () => {
    renderSidebar()

    const sidebar = document.querySelector<HTMLElement>('[data-slot="sidebar"]')
    const sidebarGap = document.querySelector<HTMLElement>(
      '[data-slot="sidebar-gap"]'
    )

    expect(sidebar).not.toBeNull()
    expect(sidebar?.getAttribute("data-state")).toBe("collapsed")
    expect(sidebar?.getAttribute("data-collapsible")).toBe("icon")
    expect(sidebar?.getAttribute("data-mobile")).toBeNull()
    expect(sidebarGap?.className).toContain(
      "group-data-[collapsible=icon]:w-(--sidebar-width-icon)"
    )
    expect(
      screen.getByRole("combobox", {
        name: "Выбор склада: Saint Petersburg",
      })
    ).toBeTruthy()
    const writeOffsButton = screen.getByRole("button", { name: "Списание" })
    expect(writeOffsButton.className).toContain(
      "group-data-[collapsible=icon]:p-0!"
    )
    expect(writeOffsButton.className).toContain(
      "group-data-[collapsible=icon]:-translate-x-2"
    )
    expect(writeOffsButton.className).toContain(
      "group-data-[collapsible=icon]:shrink-0!"
    )
    expect(writeOffsButton.className).toContain(
      "group-data-[collapsible=icon]:h-9!"
    )
    const ordersButton = screen.getByRole("link", { name: "Заказы" })
    expect(ordersButton.className).toContain(
      "group-data-[collapsible=icon]:w-9!"
    )
    expect(writeOffsButton.className).toContain(
      "group-data-[collapsible=icon]:w-[42px]!"
    )
    expect(
      writeOffsButton.closest('[data-slot="sidebar-menu-item"]')?.className
    ).toContain("group-data-[collapsible=icon]:ml-2")
    expect(
      document
        .querySelector('[data-slot="sidebar-header"]')
        ?.querySelector('[data-slot="sidebar-menu-item"]')?.className
    ).toContain("items-center")
    document
      .querySelectorAll<HTMLElement>('[data-slot="sidebar-group-label"]')
      .forEach((groupLabel) => {
        expect(groupLabel.className).toContain(
          "group-data-[collapsible=icon]:pointer-events-none"
        )
      })
    const expandIndicators = document.querySelectorAll<HTMLElement>(
      '[data-slot="sidebar-collapsed-expand-indicator"]'
    )
    expect(expandIndicators).toHaveLength(3)
    expect(expandIndicators[0].className).toContain("size-[18px]")
    expect(expandIndicators[0].className).not.toContain("bg-sidebar-accent")
    const menuSeparators = document.querySelectorAll<HTMLElement>(
      '[data-slot="sidebar-separator"]'
    )
    expect(menuSeparators).toHaveLength(4)
    expect(menuSeparators[0].className).toContain(
      "group-data-[collapsible=icon]:block"
    )
    expect(menuSeparators[0].className).toContain("w-4!")
    expect(menuSeparators[0].className).toContain("ml-[18px]!")
    expect(screen.getByTestId("sidebar-inset").className).toContain(
      "md:peer-data-[variant=inset]:peer-data-[state=collapsed]:shadow-none"
    )
  })

  it("shows the inventory label on hover in the collapsed sidebar", async () => {
    const user = userEvent.setup()
    renderSidebar()

    await user.hover(screen.getByRole("button", { name: "Инвентаризация" }))
    await waitFor(() => {
      const tooltip = document.querySelector<HTMLElement>(
        '[data-slot="tooltip-content"]'
      )
      expect(tooltip).not.toBeNull()
      expect(tooltip?.textContent).toContain("Инвентаризация")
    })
  })

  it("shows the write-off label on hover in the collapsed sidebar", async () => {
    const user = userEvent.setup()
    renderSidebar()

    await user.hover(screen.getByRole("button", { name: "Списание" }))
    await waitFor(() => {
      const tooltip = document.querySelector<HTMLElement>(
        '[data-slot="tooltip-content"]'
      )
      expect(tooltip).not.toBeNull()
      expect(tooltip?.textContent).toContain("Списание")
    })
  })

  it("shows the settings label on hover in the collapsed sidebar", async () => {
    const user = userEvent.setup()
    renderSidebar()

    await user.hover(screen.getByRole("button", { name: "Настройки" }))
    await waitFor(() => {
      const tooltip = document.querySelector<HTMLElement>(
        '[data-slot="tooltip-content"]'
      )
      expect(tooltip).not.toBeNull()
      expect(tooltip?.textContent).toContain("Настройки")
    })
  })

  it("shows and changes the warehouse name from the collapsed rail", async () => {
    const user = userEvent.setup()
    renderSidebar({ warehouses: [WAREHOUSE, MSK_WAREHOUSE] })

    const collapsedWarehouseSelector = document.querySelector<HTMLElement>(
      '[data-slot="sidebar-collapsed-warehouse-selector"]'
    )
    expect(collapsedWarehouseSelector).not.toBeNull()
    expect(collapsedWarehouseSelector?.className).toContain("ml-0")
    expect(collapsedWarehouseSelector?.className).toContain("h-9")
    expect(collapsedWarehouseSelector?.className).toContain("w-9")
    const brand = screen.getByText("RWMS panel")
    expect(brand.className).toContain("font-bold")
    expect(screen.getByTestId("rwms-logo-mark").className).toContain(
      "bg-[#1775a7]"
    )
    expect(screen.getByTestId("rwms-logo-mark").className).toContain(
      "group-data-[collapsible=icon]:size-9"
    )
    expect(screen.queryByText("WMS Panel")).toBeNull()
    expect(
      document
        .querySelector('[data-slot="sidebar-header"]')
        ?.querySelector('[data-slot="sidebar-collapsed-warehouse-selector"]')
    ).toBeNull()
    expect(
      document
        .querySelector('[data-slot="sidebar-content"]')
        ?.querySelector('[data-slot="sidebar-collapsed-warehouse-selector"]')
    ).not.toBeNull()

    const selector = screen.getByRole("combobox", {
      name: "Выбор склада: Saint Petersburg",
    })
    expect(selector.textContent).toContain("Saint Petersburg")

    await user.click(selector)
    await user.click(await screen.findByRole("option", { name: "Moscow" }))

    expect(mocks.setSelectedWarehouseId).toHaveBeenCalledWith(MSK_WAREHOUSE.id)
  })

  it("shows one warehouse name with compact expanded spacing", () => {
    renderSidebar({ open: true, warehouses: [WAREHOUSE, MSK_WAREHOUSE] })

    const selector = screen.getByRole("combobox", { name: "Выбор склада" })
    expect(selector.textContent).toBe("Saint Petersburg")
    expect(screen.getAllByText("Saint Petersburg")).toHaveLength(1)
    expect(
      selector.closest('[data-slot="sidebar-group"]')?.className
    ).toContain("pt-3")
    expect(
      selector.closest('[data-slot="sidebar-group"]')?.className
    ).toContain("pb-0")
    expect(
      screen
        .getByRole("link", { name: "Чат" })
        .closest('[data-slot="sidebar-group"]')?.className
    ).toContain("py-0")
    expect(
      screen
        .getByRole("link", { name: "Чат" })
        .closest('[data-slot="sidebar-group"]')?.className
    ).toContain("mt-2")
    expect(
      document.querySelector('[data-slot="sidebar-content"]')?.className
    ).toContain("gap-1")
  })

  it("opens right-side flyouts for write-offs and settings and follows their links", async () => {
    const user = userEvent.setup()
    renderSidebar()

    await user.click(screen.getByRole("button", { name: "Инвентаризация" }))

    const inventoryFlyout = await screen.findByLabelText(
      "Меню «Инвентаризация»"
    )
    expect(inventoryFlyout.getAttribute("data-side")).toBe("right")
    expect(inventoryFlyout.className).toContain("w-72")
    await waitFor(() => {
      expect(
        within(inventoryFlyout).getByRole("link", {
          name: "Создать инвентаризацию",
        })
      ).toBeTruthy()
    })

    await user.click(screen.getByRole("button", { name: "Списание" }))

    const writeOffsFlyout = await screen.findByLabelText("Меню «Списание»")
    expect(writeOffsFlyout.getAttribute("data-side")).toBe("right")

    const writeOffsLinks = within(writeOffsFlyout)
    expect(
      writeOffsLinks
        .getByRole("link", { name: "Списания" })
        .getAttribute("href")
    ).toBe("/write-offs")

    await user.click(writeOffsLinks.getByRole("link", { name: "Утраты" }))
    await waitFor(() => {
      expect(screen.getByTestId("location").textContent).toBe(
        "/write-offs/equipment"
      )
    })

    await user.click(screen.getByRole("button", { name: "Настройки" }))

    const settingsFlyout = await screen.findByLabelText("Меню «Настройки»")
    expect(settingsFlyout.getAttribute("data-side")).toBe("right")
    expect(settingsFlyout.className).toContain("shadow-md")
    expect(settingsFlyout.className).not.toContain("shadow-lg")

    const settingsLinks = within(settingsFlyout)
    expect(
      settingsLinks.getByRole("link", { name: "Склады" }).getAttribute("href")
    ).toBe("/settings/warehouses")

    await user.click(
      settingsLinks.getByRole("link", { name: "Настройка Доски задач" })
    )
    await waitFor(() => {
      expect(screen.getByTestId("location").textContent).toBe(
        "/settings/task-board"
      )
    })
  })

  it("keeps an icon rail accessible on tablet with saved collapsed state", async () => {
    mocks.viewport.isTabletOrSmaller = true
    document.cookie = "sidebar_state=false; path=/"
    const user = userEvent.setup()
    renderSidebar({ controlled: false })

    const sidebar = document.querySelector<HTMLElement>('[data-slot="sidebar"]')

    expect(sidebar?.getAttribute("data-collapsible")).toBe("icon")
    expect(
      document.querySelector(
        '[data-slot="sidebar-collapsed-warehouse-selector"]'
      )
    ).not.toBeNull()

    await user.click(
      screen.getByRole("button", { name: "Открыть или свернуть меню" })
    )

    await waitFor(() => {
      expect(sidebar?.getAttribute("data-state")).toBe("expanded")
      expect(sidebar?.getAttribute("data-collapsible")).toBe("")
    })
  })

  it("keeps the existing sheet navigation on phone", async () => {
    mocks.viewport.isMobile = true
    const user = userEvent.setup()
    renderSidebar()

    await user.click(
      screen.getByRole("button", { name: "Открыть или свернуть меню" })
    )

    await waitFor(() => {
      expect(
        document.querySelector('[data-sidebar="sidebar"][data-mobile="true"]')
      ).not.toBeNull()
    })
    expect(screen.getByText("Инвентаризация")).toBeTruthy()
  })
})
