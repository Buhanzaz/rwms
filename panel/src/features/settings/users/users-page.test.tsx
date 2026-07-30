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

import { UsersPage } from "@/features/settings/users/users-page"

const { listAdminUsers } = vi.hoisted(() => ({
  listAdminUsers: vi.fn(),
}))

vi.mock("@/features/settings/users/api/users-api", () => ({
  changeAdminUserPassword: vi.fn(),
  createAdminUser: vi.fn(),
  listAdminUsers,
  replaceAdminUserWarehouseAccesses: vi.fn(),
  updateAdminUser: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "access-token",
    currentUser: {
      id: "00000000-0000-4000-8000-000000000099",
      globalRole: "SYSTEM_ADMIN",
    },
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({ warehouses: [] }),
}))

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
  {
    id: "00000000-0000-4000-8000-000000000002",
    version: 1,
    username: "rental.petrov",
    firstName: "Пётр",
    lastName: "Петров",
    email: "petrov@example.ru",
    timeZoneId: "Europe/Moscow",
    active: false,
    globalRole: "RENTAL_MANAGER" as const,
    mobileAppAccess: false,
    rentalAccess: true,
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
  listAdminUsers.mockResolvedValue(users)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe("UsersPage", () => {
  it("keeps the selected filters while the filter panel is collapsed", async () => {
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
    expect(screen.getAllByText("rental.petrov").length).toBeGreaterThan(0)
  })
})
