import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react"
import type { User } from "oidc-client-ts"

import { AuthContext, type AuthStatus } from "@/features/auth/auth-context"
import { getCurrentUser } from "@/features/auth/current-user-api"
import { clearMediaPreviewCache } from "@/features/media/media-preview-cache"
import {
  getSafeReturnTo,
  getUserManager,
  hasRenewablePanelSession,
  isPanelUser,
} from "@/features/auth/oidc-client"

const MISSING_OIDC_STATE_MESSAGE = "No matching state found in storage"

type CallbackCompletion = {
  callbackUrl: string
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
  return (
    error instanceof Error && error.message === MISSING_OIDC_STATE_MESSAGE
  )
}

class PanelPrincipalError extends Error {
  constructor() {
    super("Панель доступна только учётным записям пользователей.")
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
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
        clearMediaPreviewCache()
        setOidcUser(null)
        setCurrentUser(null)
        setStatus("unauthenticated")
        return
      }

      if (!isPanelUser(user)) {
        await manager.removeUser()
        clearMediaPreviewCache()
        throw new PanelPrincipalError()
      }

      // Sessions issued before refresh-token support cannot be renewed. Replace
      // them immediately on page load instead of interrupting an open form when
      // their five-minute access token expires.
      if (!hasRenewablePanelSession(user)) {
        await manager.removeUser()
        clearMediaPreviewCache()
        setOidcUser(null)
        setCurrentUser(null)
        setStatus("unauthenticated")
        return
      }

      // A refresh-token renewal emits userLoaded. Publish the fresh token before
      // loading the profile, so in-flight panel UI stays mounted and subsequent
      // API requests do not keep using an expired bearer token.
      setOidcUser(user)

      const profile = await getCurrentUser(user.access_token)

      if (profile.principalType !== "USER") {
        await manager.removeUser()
        throw new PanelPrincipalError()
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

    const handleUserLoaded = (user: User) => {
      void acceptUser(user).catch((renewError) => {
        if (renewError instanceof PanelPrincipalError) {
          clearMediaPreviewCache()
          setOidcUser(null)
          setCurrentUser(null)
          setError(getErrorMessage(renewError))
          setStatus("unauthenticated")
          return
        }

        // The token was refreshed successfully. A transient /me failure must
        // not turn it into a logout or unmount the application.
        setError(getErrorMessage(renewError))
      })
    }

    const handleExpired = () => {
      void manager.removeUser()
      clearMediaPreviewCache()
      setOidcUser(null)
      setCurrentUser(null)
      setStatus("unauthenticated")
    }

    manager.events.addAccessTokenExpired(handleExpired)
    manager.events.addUserLoaded(handleUserLoaded)

    return () => {
      cancelled = true
      manager.events.removeAccessTokenExpired(handleExpired)
      manager.events.removeUserLoaded(handleUserLoaded)
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

  const completeLogin = useCallback(() => {
    const callbackUrl = window.location.href
    if (callbackCompletion?.callbackUrl === callbackUrl) {
      return callbackCompletion.promise
    }

    const completion = (async () => {
      setStatus("loading")
      setError(null)

      try {
        const user = await manager.signinRedirectCallback()
        await acceptUser(user)

        const state = user.state as { returnTo?: unknown } | undefined
        return getSafeReturnTo(state?.returnTo)
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
              await acceptUser(existingUser)
              return "/"
            }
          } catch {
            // Continue to the fail-closed branch below.
          }
        }

        await manager.removeUser()
        clearMediaPreviewCache()
        setOidcUser(null)
        setCurrentUser(null)
        setError(getErrorMessage(callbackError))
        setStatus("unauthenticated")
        throw callbackError
      }
    })()

    callbackCompletion = { callbackUrl, promise: completion }
    return completion
  }, [acceptUser, manager])

  const logout = useCallback(async () => {
    setStatus("loading")

    try {
      const idToken = oidcUser?.id_token
      await manager.removeUser()
      clearMediaPreviewCache()
      setOidcUser(null)
      setCurrentUser(null)
      await manager.signoutRedirect({ id_token_hint: idToken })
    } catch (logoutError) {
      await manager.removeUser()
      clearMediaPreviewCache()
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
