import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react"
import { QueryClientProvider } from "@tanstack/react-query"
import type { User } from "oidc-client-ts"

import { AuthContext, type AuthStatus } from "@/features/auth/auth-context"
import { getCurrentUser } from "@/features/auth/current-user-api"
import {
  getSafeReturnTo,
  getUserManager,
  hasRenewablePanelSession,
  isPanelUser,
} from "@/features/auth/oidc-client"
import {
  PANEL_AUTH_CONFIG,
  type AuthApplicationConfig,
} from "@/features/auth/auth-config"
import {
  ProtectedClientState,
  type ProtectedClientSnapshot,
  type ProtectedPrincipalGrant,
} from "@/features/auth/protected-client-state"

const MISSING_OIDC_STATE_MESSAGE = "No matching state found in storage"

type CallbackCompletion = {
  callbackKey: string
  promise: Promise<string>
}

/*
 * The authorization response has one-use state and PKCE data. A route remount or a duplicate
 * callback delivery must await the first consumer instead of attempting a second exchange after
 * oidc-client-ts has correctly removed that state record.
 */
let callbackCompletion: CallbackCompletion | null = null

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Ошибка авторизации"
}

function isAlreadyConsumedCallbackError(error: unknown) {
  return error instanceof Error && error.message === MISSING_OIDC_STATE_MESSAGE
}

class InteractiveUserPrincipalError extends Error {
  constructor() {
    super("Приложение доступно только учётным записям пользователей.")
  }
}

/**
 * Produces the client-side authorization revision from the verified `/me`
 * profile and the effective bearer scopes. Token rotation alone retains the
 * protected client; a subject, role, scope or warehouse-grant change replaces it.
 */
function protectedPrincipalGrant(
  user: User,
  profile: Awaited<ReturnType<typeof getCurrentUser>>
): ProtectedPrincipalGrant {
  return {
    subjectId: profile.id,
    grantRevision: JSON.stringify({
      oidcScopes: [...user.scopes].sort(),
      globalRole: profile.globalRole,
      rentalAccess: profile.rentalAccess,
      warehouseAccessAll: profile.warehouseAccessAll,
      warehouseAccesses: [...profile.warehouseAccesses]
        .sort(
          (left, right) =>
            left.warehouseId.localeCompare(right.warehouseId, "en") ||
            left.level.localeCompare(right.level, "en")
        )
        .map(({ warehouseId, level }) => ({ warehouseId, level })),
    }),
  }
}

export function AuthProvider({
  children,
  runtime = PANEL_AUTH_CONFIG,
}: {
  children: ReactNode
  runtime?: AuthApplicationConfig
}) {
  return <OidcAuthProvider runtime={runtime}>{children}</OidcAuthProvider>
}

function OidcAuthProvider({
  children,
  runtime,
}: {
  children: ReactNode
  runtime: AuthApplicationConfig
}) {
  const [manager] = useState(() => getUserManager(runtime))
  const [protectedClientState] = useState(() => new ProtectedClientState())
  const [protectedClientSnapshot, setProtectedClientSnapshot] =
    useState<ProtectedClientSnapshot>(() => protectedClientState.snapshot)
  const [status, setStatus] = useState<AuthStatus>("loading")
  const [oidcUser, setOidcUser] = useState<User | null>(null)
  const [currentUser, setCurrentUser] = useState<Awaited<
    ReturnType<typeof getCurrentUser>
  > | null>(null)
  const [error, setError] = useState<string | null>(null)
  const authenticationAttempt = useRef(0)

  const isCurrentAuthenticationAttempt = (attempt: number) =>
    attempt === authenticationAttempt.current

  const closeProtectedClientState = useCallback(
    async (attempt: number) => {
      if (!isCurrentAuthenticationAttempt(attempt)) return false
      const nextSnapshot = await protectedClientState.deactivate()
      if (!isCurrentAuthenticationAttempt(attempt)) return false
      setProtectedClientSnapshot(nextSnapshot)
      return true
    },
    [protectedClientState]
  )

  const publishUnauthenticatedState = useCallback((attempt: number) => {
    if (!isCurrentAuthenticationAttempt(attempt)) return false
    setOidcUser(null)
    setCurrentUser(null)
    setStatus("unauthenticated")
    return true
  }, [])

  const resetToUnauthenticated = useCallback(
    async (attempt: number) => {
      const closed = await closeProtectedClientState(attempt)
      if (!closed) return false
      return publishUnauthenticatedState(attempt)
    },
    [closeProtectedClientState, publishUnauthenticatedState]
  )

  const acceptUser = useCallback(
    async (user: User | null, attempt: number) => {
      if (user === null || user.expired) {
        await resetToUnauthenticated(attempt)
        return
      }

      if (!isPanelUser(user)) {
        if (!isCurrentAuthenticationAttempt(attempt)) return
        await manager.removeUser()
        if (!isCurrentAuthenticationAttempt(attempt)) return
        await resetToUnauthenticated(attempt)
        throw new InteractiveUserPrincipalError()
      }

      // Sessions issued before refresh-token support cannot be renewed. Replace
      // them immediately on page load instead of interrupting an open form when
      // their five-minute access token expires.
      if (!hasRenewablePanelSession(user)) {
        if (!isCurrentAuthenticationAttempt(attempt)) return
        await manager.removeUser()
        if (!isCurrentAuthenticationAttempt(attempt)) return
        await resetToUnauthenticated(attempt)
        return
      }

      // Verify the profile before publishing a refreshed token. A userLoaded
      // event can represent a different browser principal, so exposing its
      // token to the prior UI before the revision boundary would cross scopes.
      const profile = await getCurrentUser(user.access_token)
      if (!isCurrentAuthenticationAttempt(attempt)) return

      if (profile.principalType !== "USER") {
        await manager.removeUser()
        if (!isCurrentAuthenticationAttempt(attempt)) return
        await resetToUnauthenticated(attempt)
        throw new InteractiveUserPrincipalError()
      }

      const nextSnapshot = await protectedClientState.activate(
        protectedPrincipalGrant(user, profile)
      )
      if (!isCurrentAuthenticationAttempt(attempt)) return
      setProtectedClientSnapshot(nextSnapshot)
      setOidcUser(user)
      setCurrentUser(profile)
      setError(null)
      setStatus("authenticated")
    },
    [manager, protectedClientState, resetToUnauthenticated]
  )

  useEffect(() => {
    let cancelled = false

    async function restoreSession() {
      const attempt = ++authenticationAttempt.current
      try {
        const user = await manager.getUser()

        if (!cancelled && isCurrentAuthenticationAttempt(attempt)) {
          await acceptUser(user, attempt)
        }
      } catch (restoreError) {
        if (!cancelled && isCurrentAuthenticationAttempt(attempt)) {
          await manager.removeUser()
          if (!isCurrentAuthenticationAttempt(attempt)) return
          await resetToUnauthenticated(attempt)
          if (!isCurrentAuthenticationAttempt(attempt)) return
          setError(getErrorMessage(restoreError))
        }
      }
    }

    void restoreSession()

    const handleUserLoaded = (user: User) => {
      const attempt = ++authenticationAttempt.current
      void acceptUser(user, attempt).catch((renewError) => {
        if (!isCurrentAuthenticationAttempt(attempt)) return
        if (renewError instanceof InteractiveUserPrincipalError) {
          setError(getErrorMessage(renewError))
          return
        }

        // The token was refreshed successfully. A transient /me failure must
        // not turn it into a logout or unmount the application.
        setError(getErrorMessage(renewError))
      })
    }

    const handleExpired = () => {
      const attempt = ++authenticationAttempt.current
      void (async () => {
        try {
          await manager.removeUser()
        } catch {
          // The local boundary must still close when OIDC storage is gone.
        }
        await resetToUnauthenticated(attempt)
      })()
    }

    manager.events.addAccessTokenExpired(handleExpired)
    manager.events.addUserLoaded(handleUserLoaded)

    return () => {
      cancelled = true
      authenticationAttempt.current += 1
      manager.events.removeAccessTokenExpired(handleExpired)
      manager.events.removeUserLoaded(handleUserLoaded)
      void protectedClientState.deactivate().catch(() => undefined)
    }
  }, [acceptUser, manager, protectedClientState, resetToUnauthenticated])

  const beginLogin = useCallback(
    async (returnTo = "/") => {
      setError(null)
      try {
        await manager.signinRedirect({
          state: { returnTo: getSafeReturnTo(returnTo, runtime) },
        })
      } catch (loginError) {
        setError(getErrorMessage(loginError))
      }
    },
    [manager, runtime]
  )

  const completeLogin = useCallback(() => {
    const callbackUrl = window.location.href
    const callbackKey = `${runtime.clientId}|${callbackUrl}`
    if (callbackCompletion?.callbackKey === callbackKey) {
      return callbackCompletion.promise
    }

    const completion = (async () => {
      const attempt = ++authenticationAttempt.current
      setStatus("loading")
      setError(null)

      try {
        const user = await manager.signinRedirectCallback()
        await acceptUser(user, attempt)

        const state = user.state as { returnTo?: unknown } | undefined
        return getSafeReturnTo(state?.returnTo, runtime)
      } catch (callbackError) {
        /*
         * A first callback may have already exchanged the code and saved the user before a
         * duplicate delivery reaches a newly mounted provider. Accept only that already saved,
         * validated panel session; a genuinely missing state still fails closed below.
         */
        if (isAlreadyConsumedCallbackError(callbackError)) {
          try {
            const existingUser = await manager.getUser()
            if (
              existingUser !== null &&
              !existingUser.expired &&
              isPanelUser(existingUser) &&
              hasRenewablePanelSession(existingUser)
            ) {
              await acceptUser(existingUser, attempt)
              return runtime.postLogoutPath
            }
          } catch {
            // Continue to the fail-closed branch below.
          }
        }

        if (isCurrentAuthenticationAttempt(attempt)) {
          await manager.removeUser()
          if (isCurrentAuthenticationAttempt(attempt)) {
            await resetToUnauthenticated(attempt)
          }
        }
        if (!isCurrentAuthenticationAttempt(attempt)) throw callbackError
        setError(getErrorMessage(callbackError))
        throw callbackError
      }
    })()

    callbackCompletion = { callbackKey, promise: completion }
    return completion
  }, [acceptUser, manager, resetToUnauthenticated, runtime])

  const logout = useCallback(async () => {
    const attempt = ++authenticationAttempt.current
    setStatus("loading")
    setError(null)

    try {
      const idToken = oidcUser?.id_token
      const closed = await closeProtectedClientState(attempt)
      if (!closed || !isCurrentAuthenticationAttempt(attempt)) return
      setOidcUser(null)
      setCurrentUser(null)
      await manager.removeUser()
      if (!isCurrentAuthenticationAttempt(attempt)) return
      await manager.signoutRedirect({ id_token_hint: idToken })
    } catch (logoutError) {
      if (!isCurrentAuthenticationAttempt(attempt)) return
      try {
        await manager.removeUser()
      } catch {
        // The local boundary was already closed before the OIDC call failed.
      }
      if (!isCurrentAuthenticationAttempt(attempt)) return
      publishUnauthenticatedState(attempt)
      setError(getErrorMessage(logoutError))
    }
  }, [
    closeProtectedClientState,
    manager,
    oidcUser,
    publishUnauthenticatedState,
  ])

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

  return (
    <AuthContext.Provider value={value}>
      <QueryClientProvider
        client={protectedClientSnapshot.queryClient}
        key={protectedClientSnapshot.revision}
      >
        {children}
      </QueryClientProvider>
    </AuthContext.Provider>
  )
}
