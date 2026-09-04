import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

import { AdminApp } from "@/apps/admin/admin-app"
import { ThemeProvider } from "@/components/theme-provider"

const mocks = vi.hoisted(() => ({
  logout: vi.fn(),
  useAuth: vi.fn(),
  useWarehouse: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({ useAuth: mocks.useAuth }))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: mocks.useWarehouse,
}))
vi.mock("@/contexts/warehouse-provider", () => ({
  WarehouseProvider: ({ children }: { children: React.ReactNode }) => children,
}))
vi.mock("@/apps/admin/admin-object-scope", () => ({
  AdminObjectScope: ({
    children,
  }: {
    children: (warehouse: { id: string; name: string }) => React.ReactNode
  }) =>
    children({
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      name: "MSK",
    }),
}))
vi.mock("@/apps/admin/object-settings-page", () => ({
  ObjectSettingsPage: () => <div>Страница настроек объекта</div>,
}))
vi.mock("@/apps/admin/general-settings-pages", () => ({
  AdminClassesSettingsPage: () => <div>Страница классов</div>,
  AdminKpiSettingsPage: () => <div>Страница KPI</div>,
  AdminLogisticsSettingsPage: () => <div>Страница логистики</div>,
  AdminWorkScheduleSettingsPage: () => <div>Страница графика</div>,
}))
vi.mock("@/features/settings/users/users-page", () => ({
  UsersPage: () => <div>Страница пользователей</div>,
}))
vi.mock("@/features/claims/claims-page", () => ({
  ClaimsPage: () => <div>Страница претензий</div>,
}))
vi.mock("@/features/settings/warehouses/warehouse-settings-page", () => ({
  WarehouseSettingsPage: () => <div>Страница складов</div>,
}))
vi.mock("@/features/assistant/pages/rental-settings-page", () => ({
  RentalSettingsPage: () => <div>Страница бронирования и чата</div>,
}))
vi.mock("@/features/settings/cabin-composition", () => ({
  CabinCompositionSettingsPage: () => <div>Страница настроек бытовок</div>,
}))
vi.mock(
  "@/features/settings/estimates-repairs/estimates-repairs-settings-page",
  () => ({
    EstimatesRepairsSettingsPage: () => <div>Страница смет и ремонтов</div>,
  })
)
vi.mock("@/features/settings/task-board/task-board-settings-page", () => ({
  TaskBoardSettingsPage: () => <div>Страница доски задач</div>,
}))

const adminRoutes = [
  ["Претензии", "/admin/claims", "Страница претензий"],
  ["Пользователи", "/admin/users", "Страница пользователей"],
  ["Объекты", "/admin/warehouses", "Страница складов"],
  ["Настройки объекта", "/admin/object-settings", "Страница настроек объекта"],
  ["Бытовки", "/admin/cabins", "Страница настроек бытовок"],
  ["Бронирование и чат", "/admin/rental", "Страница бронирования и чата"],
  ["KPI", "/admin/kpi", "Страница KPI"],
  ["Сметы и ремонт", "/admin/estimates-repairs", "Страница смет и ремонтов"],
  ["Доски задач", "/admin/task-boards", "Страница доски задач"],
  ["Логистика", "/admin/logistics", "Страница логистики"],
  ["Классы", "/admin/classes", "Страница классов"],
  ["График работы", "/admin/work-schedule", "Страница графика"],
] as const

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("AdminApp", () => {
  it("keeps administration routes inside the separate /admin application", async () => {
    mocks.useAuth.mockReturnValue({
      currentUser: {
        displayName: "Администратор",
        globalRole: "SYSTEM_ADMIN",
      },
      logout: mocks.logout,
    })
    mocks.useWarehouse.mockReturnValue({ isLoading: false, error: null })
    const user = userEvent.setup()

    render(
      <ThemeProvider>
        <MemoryRouter initialEntries={["/admin/warehouses"]}>
          <AdminApp />
        </MemoryRouter>
      </ThemeProvider>
    )

    expect(screen.getByText("Страница складов")).toBeTruthy()
    const workspace = screen.getByRole("main")
    expect(workspace.className).toContain("min-h-0")
    expect(workspace.className).toContain("flex-1")
    expect(workspace.className).toContain("overflow-hidden")
    expect(workspace.className).toContain("bg-background")
    expect(workspace.className).toContain("border-border")
    expect(workspace.parentElement?.className).toContain("h-svh")
    expect(workspace.id).toBe("admin-content")
    expect(
      screen
        .getByRole("link", { name: "К основному содержимому" })
        .getAttribute("href")
    ).toBe("#admin-content")
    expect(screen.queryByRole("button", { name: /фоновое видео/i })).toBeNull()
    const video = document.querySelector(
      "video[aria-hidden='true']"
    ) as HTMLVideoElement
    expect(video).toBeTruthy()
    fireEvent.loadedMetadata(video)
    expect(video.playbackRate).toBe(0.5)
    const navigation = screen.getByRole("navigation", {
      name: "Разделы администрирования",
    })
    const sidebar = navigation.closest('[data-slot="sidebar"]')
    expect(sidebar).not.toBeNull()
    expect(sidebar?.className).toContain("bg-sidebar")
    expect(sidebar?.className).toContain("border-border")
    expect(document.querySelector('[data-slot="sidebar-rail"]')).toBeNull()
    expect(
      within(sidebar as HTMLElement).queryByText("Администратор")
    ).toBeNull()
    expect(navigation.className).toContain("flex-col")
    expect(navigation.className).not.toContain("overflow-x-auto")
    expect(screen.getByText("Настройки пользователей")).toBeTruthy()
    expect(screen.getByText("Настройки объектов")).toBeTruthy()
    expect(screen.getByText("Общие настройки")).toBeTruthy()
    expect(
      within(navigation).queryByRole("link", {
        name: "Настройки главного доступа",
      })
    ).toBeNull()
    const brand = within(sidebar as HTMLElement).getByLabelText(
      "BLOCKBOX: администрирование"
    )
    expect(brand.tagName).toBe("DIV")
    expect(brand.className).toContain("h-full")
    expect(brand.className).toContain("items-center")
    const header = workspace.querySelector("header")
    expect(header?.className).toContain("border-b")
    expect(header?.className).toContain("border-border")
    expect(
      within(sidebar as HTMLElement).queryByRole("link", {
        name: "BLOCKBOX: администрирование",
      })
    ).toBeNull()
    const logout = within(sidebar as HTMLElement).getByRole("button", {
      name: "Выйти",
    })
    for (const [label, href, pageContent] of adminRoutes) {
      const link = within(navigation).getByRole("link", { name: label })
      expect(link.getAttribute("href")).toBe(href)
      expect(href.startsWith("/admin/")).toBe(true)

      await user.click(link)
      expect(await screen.findByText(pageContent)).toBeTruthy()
    }

    await user.click(logout)
    expect(mocks.logout).toHaveBeenCalledOnce()
  })

  it("redirects the old task-board address to the renamed task boards page", async () => {
    mocks.useAuth.mockReturnValue({
      currentUser: {
        displayName: "Администратор",
        globalRole: "SYSTEM_ADMIN",
      },
      logout: mocks.logout,
    })
    mocks.useWarehouse.mockReturnValue({ isLoading: false, error: null })

    render(
      <ThemeProvider>
        <MemoryRouter initialEntries={["/admin/task-board"]}>
          <AdminApp />
        </MemoryRouter>
      </ThemeProvider>
    )

    expect(await screen.findByText("Страница доски задач")).toBeTruthy()
  })

  it("surfaces warehouse bootstrap failures instead of mounting settings", () => {
    mocks.useAuth.mockReturnValue({
      currentUser: { displayName: "Администратор", globalRole: "WMS_ADMIN" },
      logout: mocks.logout,
    })
    mocks.useWarehouse.mockReturnValue({
      isLoading: false,
      error: "Нет доступа к справочнику складов",
    })

    render(
      <ThemeProvider>
        <MemoryRouter initialEntries={["/admin/users"]}>
          <AdminApp />
        </MemoryRouter>
      </ThemeProvider>
    )

    expect(screen.getByText("Нет доступа к справочнику складов")).toBeTruthy()
    expect(screen.queryByText("Страница пользователей")).toBeNull()
  })
})
