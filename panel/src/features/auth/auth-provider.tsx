import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react"
import type { User } from "oidc-client-ts"

import { AuthContext, type AuthStatus } from "@/features/auth/auth-context"
import {
  DEV_AUTH_BYPASS_ENABLED,
  DEV_AUTH_BYPASS_TOKEN,
} from "@/features/auth/auth-config"
import type { CurrentUser } from "@/features/auth/auth-model"
import { getCurrentUser } from "@/features/auth/current-user-api"
import {
  getSafeReturnTo,
  getUserManager,
  isPanelUser,
} from "@/features/auth/oidc-client"

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Ошибка авторизации"
}

const developmentUser: CurrentUser = {
  id: "00000000-0000-0000-0000-000000000001",
  username: "local-admin",
  displayName: "Локальный администратор",
  firstName: "Локальный",
  lastName: "администратор",
  email: null,
  principalType: "USER",
  globalRole: "SYSTEM_ADMIN",
  warehouseAccessAll: true,
  warehouseAccesses: [],
}

function DevelopmentAuthProvider({ children }: { children: ReactNode }) {
  return (
    <AuthContext.Provider
      value={{
        status: "authenticated",
        accessToken: DEV_AUTH_BYPASS_TOKEN,
        currentUser: developmentUser,
        error: null,
        beginLogin: async () => undefined,
        completeLogin: async () => "/",
        logout: async () => undefined,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function AuthProvider({ children }: { children: ReactNode }) {
  if (DEV_AUTH_BYPASS_ENABLED) {
    return <DevelopmentAuthProvider>{children}</DevelopmentAuthProvider>
  }

  return <OidcAuthProvider>{children}</OidcAuthProvider>
}

function OidcAuthProvider({ children }: { children: ReactNode }) {
  const [manager] = useState(() => getUserManager())
  const [status, setStatus] = useState<AuthStatus>("loading")
  const [oidcUser, setOidcUser] = useState<User | null>(null)
  const [currentUser, setCurrentUser] = useState<Awaited<
    ReturnType<typeof getCurrentUser>
  > | null>(null)
  const [error, setError] = useState<string | null>(null)

  const acceptUser = useCallback(
    async (user: User | null) => {
      if (user === null || user.expired) {
        setOidcUser(null)
        setCurrentUser(null)
        setStatus("unauthenticated")
        return
      }

      if (!isPanelUser(user)) {
        await manager.removeUser()
        throw new Error("Панель доступна только учётным записям пользователей.")
      }

      const profile = await getCurrentUser(user.access_token)

      if (profile.principalType !== "USER") {
        await manager.removeUser()
        throw new Error("Панель доступна только учётным записям пользователей.")
      }

      setOidcUser(user)
      setCurrentUser(profile)
      setError(null)
      setStatus("authenticated")
    },
    [manager]
  )

  useEffect(() => {
    let cancelled = false

    async function restoreSession() {
      try {
        const user = await manager.getUser()

        if (!cancelled) {
          await acceptUser(user)
        }
      } catch (restoreError) {
        if (!cancelled) {
          await manager.removeUser()
          setError(getErrorMessage(restoreError))
          setStatus("unauthenticated")
        }
      }
    }

    void restoreSession()

    const handleExpired = () => {
      void manager.removeUser()
      setOidcUser(null)
      setCurrentUser(null)
      setStatus("unauthenticated")
    }

    manager.events.addAccessTokenExpired(handleExpired)

    return () => {
      cancelled = true
      manager.events.removeAccessTokenExpired(handleExpired)
    }
  }, [acceptUser, manager])

  const beginLogin = useCallback(
    async (returnTo = "/") => {
      setError(null)
      await manager.signinRedirect({
        state: { returnTo: getSafeReturnTo(returnTo) },
      })
    },
    [manager]
  )

  const completeLogin = useCallback(async () => {
    setStatus("loading")
    setError(null)

    try {
      const user = await manager.signinRedirectCallback()
      await acceptUser(user)

      const state = user.state as { returnTo?: unknown } | undefined
      return getSafeReturnTo(state?.returnTo)
    } catch (callbackError) {
      await manager.removeUser()
      setOidcUser(null)
      setCurrentUser(null)
      setError(getErrorMessage(callbackError))
      setStatus("unauthenticated")
      throw callbackError
    }
  }, [acceptUser, manager])

  const logout = useCallback(async () => {
    setStatus("loading")

    try {
      const idToken = oidcUser?.id_token
      await manager.removeUser()
      setOidcUser(null)
      setCurrentUser(null)
      await manager.signoutRedirect({ id_token_hint: idToken })
    } catch (logoutError) {
      await manager.removeUser()
      setOidcUser(null)
      setCurrentUser(null)
      setError(getErrorMessage(logoutError))
      setStatus("unauthenticated")
    }
  }, [manager, oidcUser])

  const value = useMemo(
    () => ({
      status,
      accessToken: oidcUser?.access_token ?? null,
      currentUser,
      error,
      beginLogin,
      completeLogin,
      logout,
    }),
    [beginLogin, completeLogin, currentUser, error, logout, oidcUser, status]
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
