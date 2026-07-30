import { act, cleanup, render, screen, waitFor } from "@testing-library/react"
import { useEffect } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import { AuthProvider } from "@/features/auth/auth-provider"
import { useAuth } from "@/features/auth/use-auth"

const oidc = vi.hoisted(() => {
  let userLoaded: ((user: TestUser) => void) | undefined

  const manager = {
    getUser: vi.fn(),
    removeUser: vi.fn(),
    signinRedirect: vi.fn(),
    signinRedirectCallback: vi.fn(),
    signoutRedirect: vi.fn(),
    events: {
      addAccessTokenExpired: vi.fn(),
      removeAccessTokenExpired: vi.fn(),
      addUserLoaded: vi.fn((handler: (user: TestUser) => void) => {
        userLoaded = handler
      }),
      removeUserLoaded: vi.fn(),
    },
  }

  return {
    manager,
    emitUserLoaded: (user: TestUser) => userLoaded?.(user),
    reset: () => {
      userLoaded = undefined
      Object.values(manager).forEach((value) => {
        if (typeof value === "function") {
          value.mockReset()
        }
      })
      Object.values(manager.events).forEach((value) => value.mockClear())
    },
  }
})

const { getCurrentUser } = vi.hoisted(() => ({
  getCurrentUser: vi.fn(),
}))

vi.mock("@/features/auth/oidc-client", () => ({
  getSafeReturnTo: (value: unknown) =>
    typeof value === "string" && value.startsWith("/") ? value : "/",
  getUserManager: () => oidc.manager,
  hasRenewablePanelSession: (user: TestUser) =>
    Boolean(user.refresh_token) && user.scopes.includes("offline_access"),
  isPanelUser: (user: TestUser) => user.profile.principal_type === "USER",
}))

vi.mock("@/features/auth/current-user-api", () => ({ getCurrentUser }))

type TestUser = {
  access_token: string
  expired: boolean
  profile: { principal_type: string }
  refresh_token?: string
  scopes: string[]
}

const currentUser: CurrentUser = {
  id: "6ddf2ef1-0f6a-45d5-8959-5328e6846bee",
  username: "panel-user",
  displayName: "Пользователь панели",
  firstName: null,
  lastName: null,
  email: null,
  principalType: "USER",
  globalRole: "WMS_ADMIN",
  rentalAccess: true,
  warehouseAccessAll: true,
  warehouseAccesses: [],
}

function user(accessToken: string): TestUser {
  return {
    access_token: accessToken,
    expired: false,
    profile: { principal_type: "USER" },
    refresh_token: "refresh-token",
    scopes: ["openid", "profile", "offline_access"],
  }
}

function SessionProbe({ onUnmount }: { onUnmount: () => void }) {
  const auth = useAuth()

  useEffect(() => onUnmount, [onUnmount])

  return (
    <output>
      {auth.status}:{auth.accessToken}:{auth.currentUser?.username ?? "none"}
    </output>
  )
}

afterEach(() => {
  cleanup()
  oidc.reset()
  getCurrentUser.mockReset()
})

describe("AuthProvider refresh-token renewal", () => {
  it("updates the context on userLoaded without logging out or unmounting the panel", async () => {
    oidc.manager.getUser.mockResolvedValue(user("old-access-token"))
    getCurrentUser.mockResolvedValue(currentUser)
    const unmounted = vi.fn()

    render(
      <AuthProvider>
        <SessionProbe onUnmount={unmounted} />
      </AuthProvider>
    )

    await screen.findByText("authenticated:old-access-token:panel-user")

    act(() => oidc.emitUserLoaded(user("renewed-access-token")))

    await waitFor(() =>
      expect(
        screen.getByText("authenticated:renewed-access-token:panel-user")
      ).toBeTruthy()
    )

    expect(oidc.manager.signinRedirect).not.toHaveBeenCalled()
    expect(oidc.manager.removeUser).not.toHaveBeenCalled()
    expect(unmounted).not.toHaveBeenCalled()
  })

  it("continues to reject a renewed non-user principal", async () => {
    oidc.manager.getUser.mockResolvedValue(user("old-access-token"))
    getCurrentUser.mockResolvedValue(currentUser)

    render(
      <AuthProvider>
        <SessionProbe onUnmount={() => undefined} />
      </AuthProvider>
    )

    await screen.findByText("authenticated:old-access-token:panel-user")

    act(() =>
      oidc.emitUserLoaded({
        access_token: "worker-access-token",
        expired: false,
        profile: { principal_type: "WORKER" },
        refresh_token: "worker-refresh-token",
        scopes: ["openid", "profile", "offline_access", "worker.tasks"],
      })
    )

    await waitFor(() =>
      expect(screen.getByText("unauthenticated::none")).toBeTruthy()
    )
    expect(oidc.manager.removeUser).toHaveBeenCalledTimes(1)
  })

  it("replaces a legacy panel session before it can expire during work", async () => {
    oidc.manager.getUser.mockResolvedValue({
      ...user("legacy-access-token"),
      refresh_token: undefined,
      scopes: ["openid", "profile"],
    })

    render(
      <AuthProvider>
        <SessionProbe onUnmount={() => undefined} />
      </AuthProvider>
    )

    await waitFor(() =>
      expect(screen.getByText("unauthenticated::none")).toBeTruthy()
    )
    expect(oidc.manager.removeUser).toHaveBeenCalledTimes(1)
    expect(getCurrentUser).not.toHaveBeenCalled()
  })
})
