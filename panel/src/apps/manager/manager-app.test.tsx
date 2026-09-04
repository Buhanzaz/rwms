import { type ReactNode } from "react"
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ManagerApp } from "@/apps/manager/manager-app"

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
  WarehouseProvider: ({ children }: { children: ReactNode }) => children,
}))
vi.mock("@/features/assistant/pages/assistant-page", () => ({
  AssistantPage: () => <div>Рабочий чат</div>,
}))
vi.mock("@/features/clients/clients-routes", () => ({
  ClientsRoutes: () => <div>Рабочие клиенты</div>,
}))
vi.mock("@/features/orders/orders-routes", () => ({
  OrdersRoutes: () => <div>Рабочие заказы</div>,
}))
vi.mock("@/features/claims/claims-page", () => ({
  ClaimsPage: () => <div>Рабочие претензии</div>,
}))

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("ManagerApp", () => {
  it("reuses the manager workflows under an adaptive /manager shell", async () => {
    mocks.useAuth.mockReturnValue({
      currentUser: { displayName: "Анна Менеджер" },
      logout: mocks.logout,
    })
    mocks.useWarehouse.mockReturnValue({
      warehouses: [{ id: "warehouse-1" }],
      isLoading: false,
      error: null,
    })
    const user = userEvent.setup()

    render(
      <MemoryRouter basename="/manager" initialEntries={["/manager/clients"]}>
        <ManagerApp />
      </MemoryRouter>
    )

    expect(screen.getByText("Рабочие клиенты")).toBeTruthy()
    expect(
      screen
        .getByRole("link", { name: "BLOCKBOX: менеджер аренды" })
        .getAttribute("href")
    ).toBe("/manager/assistant")
    expect(screen.getAllByRole("navigation")).toHaveLength(2)
    expect(
      screen
        .getByRole("navigation", { name: "Основные разделы менеджера" })
        .closest("div")?.parentElement?.className
    ).toContain("fixed")

    await user.click(
      screen
        .getByRole("navigation", { name: "Основные разделы менеджера" })
        .querySelector('a[href="/manager/orders"]')!
    )
    expect(await screen.findByText("Рабочие заказы")).toBeTruthy()

    await user.click(
      screen
        .getByRole("navigation", { name: "Основные разделы менеджера" })
        .querySelector('a[href="/manager/claims"]')!
    )
    expect(await screen.findByText("Рабочие претензии")).toBeTruthy()
  })

  it("explains missing warehouse assignments without blocking manager workflows", () => {
    mocks.useAuth.mockReturnValue({
      currentUser: { displayName: "Анна Менеджер" },
      logout: mocks.logout,
    })
    mocks.useWarehouse.mockReturnValue({
      warehouses: [],
      isLoading: false,
      error: null,
    })

    render(
      <MemoryRouter basename="/manager" initialEntries={["/manager/clients"]}>
        <ManagerApp />
      </MemoryRouter>
    )

    expect(screen.getByText("Не назначены обслуживаемые склады")).toBeTruthy()
    expect(screen.getByText("Рабочие клиенты")).toBeTruthy()
  })

  it("fails closed when the warehouse directory cannot be loaded", () => {
    mocks.useAuth.mockReturnValue({
      currentUser: { displayName: "Анна Менеджер" },
      logout: mocks.logout,
    })
    mocks.useWarehouse.mockReturnValue({
      warehouses: [],
      isLoading: false,
      error: "Справочник складов недоступен",
    })

    render(
      <MemoryRouter basename="/manager" initialEntries={["/manager/orders"]}>
        <ManagerApp />
      </MemoryRouter>
    )

    expect(screen.getByText("Справочник складов недоступен")).toBeTruthy()
    expect(screen.queryByText("Рабочие заказы")).toBeNull()
  })
})
