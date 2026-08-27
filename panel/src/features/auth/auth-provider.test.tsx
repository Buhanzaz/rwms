import { act, cleanup, render, screen, waitFor } from "@testing-library/react"
import { useEffect } from "react"
import { useQueryClient, type QueryClient } from "@tanstack/react-query"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { CurrentUser } from "@/features/auth/auth-model"
import type { AuthContextValue } from "@/features/auth/auth-context"
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

function AuthActionsProbe({
  onActionsChanged,
}: {
  onActionsChanged: (actions: AuthContextValue) => void
}) {
  const actions = useAuth()

  useEffect(() => {
    onActionsChanged(actions)
  }, [actions, onActionsChanged])

  return null
}

function ProtectedQueryClientProbe({
  onClientChanged,
}: {
  onClientChanged: (queryClient: QueryClient) => void
}) {
  const queryClient = useQueryClient()

  useEffect(() => {
    onClientChanged(queryClient)
  }, [onClientChanged, queryClient])

  return null
}

afterEach(() => {
  cleanup()
  oidc.reset()
  getCurrentUser.mockReset()
})

describe("AuthProvider refresh-token renewal", () => {
  it("keeps protected cache state when only the bearer token rotates", async () => {
    oidc.manager.getUser.mockResolvedValue(user("old-access-token"))
    getCurrentUser.mockResolvedValue(currentUser)
    const unmounted = vi.fn()
    const clients: QueryClient[] = []

    render(
      <AuthProvider>
        <SessionProbe onUnmount={unmounted} />
        <ProtectedQueryClientProbe
          onClientChanged={(queryClient) => clients.push(queryClient)}
        />
      </AuthProvider>
    )

    await screen.findByText("authenticated:old-access-token:panel-user")
    const oldGrantClient = clients.at(-1)!
    oldGrantClient.setQueryData(["rental-items"], ["old grant"])

    act(() => oidc.emitUserLoaded(user("renewed-access-token")))

    await waitFor(() =>
      expect(
        screen.getByText("authenticated:renewed-access-token:panel-user")
      ).toBeTruthy()
    )

    expect(oidc.manager.signinRedirect).not.toHaveBeenCalled()
    expect(oidc.manager.removeUser).not.toHaveBeenCalled()
    expect(unmounted).not.toHaveBeenCalled()
    expect(clients.at(-1)).toBe(oldGrantClient)
    expect(oldGrantClient.getQueryData(["rental-items"])).toEqual(["old grant"])
  })

  it("replaces protected cache state when a silent renewal changes the verified grant", async () => {
    const upgradedGrant: CurrentUser = {
      ...currentUser,
      globalRole: "WAREHOUSE_MANAGER",
      warehouseAccessAll: false,
      warehouseAccesses: [
        {
          warehouseId: "22222222-2222-4222-8222-222222222222",
          level: "MANAGE",
        },
      ],
    }
    oidc.manager.getUser.mockResolvedValue(user("old-access-token"))
    getCurrentUser
      .mockResolvedValueOnce(currentUser)
      .mockResolvedValueOnce(upgradedGrant)
    const unmounted = vi.fn()
    const clients: QueryClient[] = []

    render(
      <AuthProvider>
        <SessionProbe onUnmount={unmounted} />
        <ProtectedQueryClientProbe
          onClientChanged={(queryClient) => clients.push(queryClient)}
        />
      </AuthProvider>
    )

    await screen.findByText("authenticated:old-access-token:panel-user")
    const oldGrantClient = clients.at(-1)!
    oldGrantClient.setQueryData(["rental-items"], ["old grant"])

    act(() => oidc.emitUserLoaded(user("renewed-access-token")))

    await screen.findByText("authenticated:renewed-access-token:panel-user")
    expect(unmounted).toHaveBeenCalledOnce()
    expect(clients.at(-1)).not.toBe(oldGrantClient)
    expect(oldGrantClient.getQueryData(["rental-items"])).toBeUndefined()
    expect(clients.at(-1)!.getQueryData(["rental-items"])).toBeUndefined()
  })

  it("replaces protected cache state before publishing a different verified principal", async () => {
    const principalB: CurrentUser = {
      ...currentUser,
      id: "1a73542f-9a89-46af-b196-22c7f3cf590e",
      username: "panel-user-b",
      displayName: "Пользователь B",
    }
    oidc.manager.getUser.mockResolvedValue(user("a-access-token"))
    getCurrentUser
      .mockResolvedValueOnce(currentUser)
      .mockResolvedValueOnce(principalB)
    const clients: QueryClient[] = []

    render(
      <AuthProvider>
        <SessionProbe onUnmount={() => undefined} />
        <ProtectedQueryClientProbe
          onClientChanged={(queryClient) => clients.push(queryClient)}
        />
      </AuthProvider>
    )

    await screen.findByText("authenticated:a-access-token:panel-user")
    const principalAClient = clients.at(-1)!
    principalAClient.setQueryData(["rental-items"], ["A only"])

    act(() => oidc.emitUserLoaded(user("b-access-token")))

    await screen.findByText("authenticated:b-access-token:panel-user-b")
    const principalBClient = clients.at(-1)!
    expect(principalBClient).not.toBe(principalAClient)
    expect(principalAClient.getQueryData(["rental-items"])).toBeUndefined()
    expect(principalBClient.getQueryData(["rental-items"])).toBeUndefined()
  })

  it("does not let a late profile restore replace a newer principal", async () => {
    const principalB: CurrentUser = {
      ...currentUser,
      id: "1a73542f-9a89-46af-b196-22c7f3cf590e",
      username: "panel-user-b",
      displayName: "Пользователь B",
    }
    let resolvePrincipalA!: (profile: CurrentUser) => void
    oidc.manager.getUser.mockResolvedValue(user("a-access-token"))
    getCurrentUser.mockImplementation((accessToken: string) => {
      if (accessToken === "a-access-token") {
        return new Promise<CurrentUser>((resolve) => {
          resolvePrincipalA = resolve
        })
      }
      return Promise.resolve(principalB)
    })

    render(
      <AuthProvider>
        <SessionProbe onUnmount={() => undefined} />
      </AuthProvider>
    )

    await waitFor(() =>
      expect(getCurrentUser).toHaveBeenCalledWith("a-access-token")
    )
    act(() => oidc.emitUserLoaded(user("b-access-token")))
    await screen.findByText("authenticated:b-access-token:panel-user-b")

    resolvePrincipalA(currentUser)
    await act(async () => {
      await Promise.resolve()
    })

    expect(
      screen.getByText("authenticated:b-access-token:panel-user-b")
    ).toBeTruthy()
  })

  it("closes the protected query client before logout redirects", async () => {
    oidc.manager.getUser.mockResolvedValue(user("a-access-token"))
    getCurrentUser.mockResolvedValue(currentUser)
    const actions: { current: AuthContextValue | null } = { current: null }
    const clients: QueryClient[] = []

    render(
      <AuthProvider>
        <AuthActionsProbe
          onActionsChanged={(next) => (actions.current = next)}
        />
        <ProtectedQueryClientProbe
          onClientChanged={(queryClient) => clients.push(queryClient)}
        />
      </AuthProvider>
    )

    await waitFor(() => expect(actions.current?.status).toBe("authenticated"))
    const principalAClient = clients.at(-1)!
    principalAClient.setQueryData(["rental-items"], ["A only"])

    await act(async () => {
      await actions.current!.logout()
    })

    await waitFor(() => expect(clients.at(-1)).not.toBe(principalAClient))
    expect(principalAClient.getQueryData(["rental-items"])).toBeUndefined()
    expect(oidc.manager.removeUser).toHaveBeenCalledOnce()
    expect(oidc.manager.signoutRedirect).toHaveBeenCalledOnce()
  })

  it("retires protected cache state when the provider leaves the route tree", async () => {
    oidc.manager.getUser.mockResolvedValue(user("a-access-token"))
    getCurrentUser.mockResolvedValue(currentUser)
    const clients: QueryClient[] = []
    const rendered = render(
      <AuthProvider>
        <SessionProbe onUnmount={() => undefined} />
        <ProtectedQueryClientProbe
          onClientChanged={(queryClient) => clients.push(queryClient)}
        />
      </AuthProvider>
    )

    await screen.findByText("authenticated:a-access-token:panel-user")
    const principalClient = clients.at(-1)!
    principalClient.setQueryData(["rental-items"], ["A only"])

    rendered.unmount()

    await waitFor(() =>
      expect(principalClient.getQueryCache().getAll()).toHaveLength(0)
    )
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

  it("publishes an authorization redirect failure instead of leaving the UI loading", async () => {
    oidc.manager.getUser.mockResolvedValue(null)
    oidc.manager.signinRedirect.mockRejectedValue(
      new Error("OIDC metadata недоступны")
    )
    const actions: { current: AuthContextValue | null } = { current: null }

    render(
      <AuthProvider>
        <AuthActionsProbe
          onActionsChanged={(next) => (actions.current = next)}
        />
      </AuthProvider>
    )

    await waitFor(() => expect(actions.current?.status).toBe("unauthenticated"))

    await act(async () => {
      await actions.current!.beginLogin("/acceptance?acceptanceId=repair-1")
    })

    await waitFor(() =>
      expect(actions.current?.error).toBe("OIDC metadata недоступны")
    )
    expect(oidc.manager.signinRedirect).toHaveBeenCalledWith({
      state: { returnTo: "/acceptance?acceptanceId=repair-1" },
    })
  })

  it("exchanges an authorization callback only once when it is delivered twice", async () => {
    oidc.manager.getUser.mockResolvedValue(null)
    oidc.manager.signinRedirectCallback.mockResolvedValue(
      user("callback-token")
    )
    getCurrentUser.mockResolvedValue(currentUser)
    const actions: { current: AuthContextValue | null } = { current: null }
    const onActionsChanged = (next: AuthContextValue) => {
      actions.current = next
    }

    render(
      <AuthProvider>
        <AuthActionsProbe onActionsChanged={onActionsChanged} />
      </AuthProvider>
    )

    await waitFor(() => expect(actions.current?.status).toBe("unauthenticated"))

    let returns: string[] = []
    await act(async () => {
      returns = await Promise.all([
        actions.current!.completeLogin(),
        actions.current!.completeLogin(),
      ])
    })

    expect(returns).toEqual(["/", "/"])
    expect(oidc.manager.signinRedirectCallback).toHaveBeenCalledTimes(1)
    expect(oidc.manager.removeUser).not.toHaveBeenCalled()
  })

  it("restores the already exchanged panel session after a duplicate callback delivery", async () => {
    window.history.replaceState(
      {},
      "",
      "/auth/callback?code=recovered-code&state=recovered-state"
    )
    oidc.manager.getUser
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(user("recovered-access-token"))
    oidc.manager.signinRedirectCallback.mockRejectedValue(
      new Error("No matching state found in storage")
    )
    getCurrentUser.mockResolvedValue(currentUser)
    const actions: { current: AuthContextValue | null } = { current: null }
    const onActionsChanged = (next: AuthContextValue) => {
      actions.current = next
    }

    render(
      <AuthProvider>
        <AuthActionsProbe onActionsChanged={onActionsChanged} />
      </AuthProvider>
    )

    await waitFor(() => expect(actions.current?.status).toBe("unauthenticated"))

    await expect(actions.current!.completeLogin()).resolves.toBe("/")

    expect(oidc.manager.removeUser).not.toHaveBeenCalled()
    await waitFor(() => expect(actions.current?.status).toBe("authenticated"))
  })

  it("fails closed when a missing callback state has no saved panel session", async () => {
    window.history.replaceState(
      {},
      "",
      "/auth/callback?code=missing-code&state=missing-state"
    )
    oidc.manager.getUser.mockResolvedValue(null)
    oidc.manager.signinRedirectCallback.mockRejectedValue(
      new Error("No matching state found in storage")
    )
    const actions: { current: AuthContextValue | null } = { current: null }
    const onActionsChanged = (next: AuthContextValue) => {
      actions.current = next
    }

    render(
      <AuthProvider>
        <AuthActionsProbe onActionsChanged={onActionsChanged} />
      </AuthProvider>
    )

    await waitFor(() => expect(actions.current?.status).toBe("unauthenticated"))

    await expect(actions.current!.completeLogin()).rejects.toThrow(
      "No matching state found in storage"
    )

    expect(oidc.manager.removeUser).toHaveBeenCalledTimes(1)
  })
})
