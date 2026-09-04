import { cleanup, render, screen, waitFor } from "@testing-library/react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"

import { AuthenticatedApplication } from "@/features/auth/authenticated-application"
import type { CurrentUser } from "@/features/auth/auth-model"

const mocks = vi.hoisted(() => ({
  beginLogin: vi.fn(),
  logout: vi.fn(),
  useAuth: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({ useAuth: mocks.useAuth }))

const user: CurrentUser = {
  id: "10000000-0000-4000-8000-000000000001",
  username: "manager",
  displayName: "Менеджер",
  firstName: null,
  lastName: null,
  email: null,
  principalType: "USER",
  globalRole: "RENTAL_MANAGER",
  rentalAccess: true,
  warehouseAccessAll: false,
  warehouseAccesses: [],
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("AuthenticatedApplication", () => {
  it("starts login with the complete current application route", async () => {
    mocks.useAuth.mockReturnValue({
      status: "unauthenticated",
      currentUser: null,
      error: null,
      beginLogin: mocks.beginLogin,
      logout: mocks.logout,
    })

    render(
      <MemoryRouter initialEntries={["/admin/users?active=true#list"]}>
        <AuthenticatedApplication
          isAllowed={() => true}
          accessDeniedMessage="Нет доступа"
        >
          <div>Приложение</div>
        </AuthenticatedApplication>
      </MemoryRouter>
    )

    await waitFor(() =>
      expect(mocks.beginLogin).toHaveBeenCalledWith(
        "/admin/users?active=true#list"
      )
    )
  })

  it("stores the external path when the application router uses a basename", async () => {
    mocks.useAuth.mockReturnValue({
      status: "unauthenticated",
      currentUser: null,
      error: null,
      beginLogin: mocks.beginLogin,
      logout: mocks.logout,
    })

    render(
      <MemoryRouter
        basename="/manager"
        initialEntries={["/manager/orders?status=draft#list"]}
      >
        <AuthenticatedApplication
          applicationBasePath="/manager"
          isAllowed={() => true}
          accessDeniedMessage="Нет доступа"
        >
          <div>Приложение</div>
        </AuthenticatedApplication>
      </MemoryRouter>
    )

    await waitFor(() =>
      expect(mocks.beginLogin).toHaveBeenCalledWith(
        "/manager/orders?status=draft#list"
      )
    )
  })

  it("does not mount an application for a disallowed authenticated role", () => {
    mocks.useAuth.mockReturnValue({
      status: "authenticated",
      currentUser: user,
      error: null,
      beginLogin: mocks.beginLogin,
      logout: mocks.logout,
    })

    render(
      <MemoryRouter>
        <AuthenticatedApplication
          isAllowed={(current) => current.globalRole === "WMS_ADMIN"}
          accessDeniedMessage="Нет доступа к админке"
        >
          <div>Секретное приложение</div>
        </AuthenticatedApplication>
      </MemoryRouter>
    )

    expect(screen.getByRole("alert").textContent).toContain(
      "Нет доступа к админке"
    )
    expect(screen.queryByText("Секретное приложение")).toBeNull()
  })

  it("mounts the requested application for an allowed authenticated role", () => {
    mocks.useAuth.mockReturnValue({
      status: "authenticated",
      currentUser: { ...user, globalRole: "WMS_ADMIN" },
      error: null,
      beginLogin: mocks.beginLogin,
      logout: mocks.logout,
    })

    render(
      <MemoryRouter>
        <AuthenticatedApplication
          isAllowed={(current) => current.globalRole === "WMS_ADMIN"}
          accessDeniedMessage="Нет доступа"
        >
          <div>Админка открыта</div>
        </AuthenticatedApplication>
      </MemoryRouter>
    )

    expect(screen.getByText("Админка открыта")).toBeTruthy()
  })
})
