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

import type { WarehouseInfo } from "@/api/warehouse-api"
import { UsersPage } from "@/features/settings/users/users-page"

const mocks = vi.hoisted(() => ({
  changeAdminUserPassword: vi.fn(),
  createAdminUser: vi.fn(),
  listAdminUsers: vi.fn(),
  replaceAdminUserWarehouseAccesses: vi.fn(),
  updateAdminUser: vi.fn(),
  useAuth: vi.fn(),
  useWarehouse: vi.fn(),
}))

vi.mock("@/features/settings/users/api/users-api", () => ({
  changeAdminUserPassword: mocks.changeAdminUserPassword,
  createAdminUser: mocks.createAdminUser,
  listAdminUsers: mocks.listAdminUsers,
  replaceAdminUserWarehouseAccesses: mocks.replaceAdminUserWarehouseAccesses,
  updateAdminUser: mocks.updateAdminUser,
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: mocks.useAuth,
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: mocks.useWarehouse,
}))

const warehouse: WarehouseInfo = {
  id: "00000000-0000-4000-8000-000000000010",
  version: 1,
  name: "Основной склад",
  city: "Санкт-Петербург",
  address: null,
  latitude: null,
  longitude: null,
  timeZone: "Europe/Moscow",
  active: true,
  lifecycleState: "ACTIVE",
  sortOrder: 0,
  representative: false,
  production: true,
  mainWarehouse: false,
  representativeParentWarehouseId: null,
}

const users = [
  {
    id: "00000000-0000-4000-8000-000000000001",
    version: 1,
    username: "warehouse.ivanov",
    firstName: "Иван",
    lastName: "Иванов",
    email: "ivanov@example.ru",
    timeZoneId: "Europe/Moscow",
    active: true,
    globalRole: "WAREHOUSE_MANAGER" as const,
    mobileAppAccess: true,
    rentalAccess: false,
    warehouseAccesses: [],
  },
]

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <UsersPage />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.stubGlobal("ResizeObserver", ResizeObserverMock)
  mocks.useAuth.mockReturnValue({
    accessToken: "access-token",
    currentUser: {
      id: "00000000-0000-4000-8000-000000000099",
      globalRole: "SYSTEM_ADMIN",
    },
  })
  mocks.useWarehouse.mockReturnValue({ warehouses: [warehouse] })
  mocks.listAdminUsers.mockResolvedValue(users)
  mocks.updateAdminUser.mockImplementation(
    async (_token, _id, _version, profile) => ({
      ...users[0],
      ...profile,
      version: 2,
    })
  )
  mocks.replaceAdminUserWarehouseAccesses.mockResolvedValue({
    ...users[0],
    version: 3,
  })
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe("UsersPage", () => {
  it("keeps filters within the users returned by the server", async () => {
    const user = userEvent.setup()
    renderPage()

    const hideFilters = await screen.findByRole("button", {
      name: "Скрыть фильтры пользователей",
    })
    expect(hideFilters.getAttribute("aria-controls")).toBe("users-filters")
    expect(hideFilters.getAttribute("aria-expanded")).toBe("true")

    const filters = screen.getByRole("region", {
      name: "Фильтры пользователей",
    })
    await user.click(within(filters).getByRole("button", { name: "Логин" }))
    await user.click(
      await screen.findByRole("checkbox", { name: "warehouse.ivanov" })
    )
    await user.click(screen.getByRole("button", { name: "Применить" }))

    await waitFor(() => {
      expect(screen.getAllByText("warehouse.ivanov").length).toBeGreaterThan(0)
      expect(screen.queryByText("rental.petrov")).toBeNull()
    })

    await user.click(hideFilters)
    expect(document.getElementById("users-filters")?.hidden).toBe(true)
    expect(
      screen.queryByRole("region", { name: "Фильтры пользователей" })
    ).toBeNull()
    expect(
      screen
        .getByRole("searchbox", { name: "Поиск пользователей" })
        .getAttribute("placeholder")
    ).toBe("Логин, имя или email")
    expect(
      screen.getByRole("button", { name: "Создать пользователя" })
    ).toBeTruthy()

    await user.click(
      screen.getByRole("button", { name: "Показать фильтры пользователей" })
    )
    const reopenedFilters = screen.getByRole("region", {
      name: "Фильтры пользователей",
    })
    expect(
      within(reopenedFilters).getByRole("button", {
        name: "Логин: выбрано 1",
      }).textContent
    ).toContain("1")

    await user.click(screen.getByRole("button", { name: "Сбросить фильтры" }))
    expect(
      within(reopenedFilters).getByRole("button", {
        name: "Логин",
      })
    ).toBeTruthy()
    expect(screen.queryByText("rental.petrov")).toBeNull()
  })

  it("updates a selected profile and replaces only its warehouse grants", async () => {
    mocks.listAdminUsers.mockResolvedValue([users[0]])
    mocks.updateAdminUser.mockResolvedValue({ ...users[0], version: 2 })
    const interaction = userEvent.setup()
    renderPage()

    const editButtons = await screen.findAllByRole("button", {
      name: "Изменить",
    })
    await interaction.click(editButtons[0]!)

    await interaction.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() => {
      expect(mocks.replaceAdminUserWarehouseAccesses).toHaveBeenCalledWith(
        "access-token",
        users[0].id,
        2,
        { accesses: [] }
      )
    })
  })
})
