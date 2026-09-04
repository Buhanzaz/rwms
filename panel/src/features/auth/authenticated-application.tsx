import { useEffect, useRef, type ReactNode } from "react"
import { useLocation } from "react-router-dom"

import { Button } from "@/components/ui/button"
import { toExternalApplicationPath } from "@/features/auth/auth-callback-navigation"
import type { CurrentUser } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"

type AuthenticatedApplicationProps = {
  children: ReactNode
  isAllowed: (user: CurrentUser) => boolean
  accessDeniedMessage: string
  applicationBasePath?: string
}

/** Shared fail-closed interactive boundary for the independently built RWMS web applications. */
export function AuthenticatedApplication({
  children,
  isAllowed,
  accessDeniedMessage,
  applicationBasePath = "",
}: AuthenticatedApplicationProps) {
  const location = useLocation()
  const { status, currentUser, error, beginLogin, logout } = useAuth()
  const redirectStarted = useRef(false)

  useEffect(() => {
    if (status !== "unauthenticated" || redirectStarted.current || error) {
      return
    }

    redirectStarted.current = true
    void beginLogin(
      toExternalApplicationPath(
        `${location.pathname}${location.search}${location.hash}`,
        applicationBasePath
      )
    )
  }, [
    beginLogin,
    error,
    location.hash,
    location.pathname,
    location.search,
    status,
    applicationBasePath,
  ])

  if (status === "authenticated" && currentUser !== null) {
    if (isAllowed(currentUser)) {
      return children
    }

    return (
      <main className="flex min-h-svh flex-col items-center justify-center gap-4 bg-muted p-6 text-center">
        <p className="max-w-md text-sm text-muted-foreground" role="alert">
          {accessDeniedMessage}
        </p>
        <Button type="button" variant="outline" onClick={() => void logout()}>
          Выйти
        </Button>
      </main>
    )
  }

  return (
    <main className="flex min-h-svh flex-col items-center justify-center gap-4 bg-muted p-6 text-center">
      <p className="text-sm text-muted-foreground">
        {error ?? "Проверяем сессию…"}
      </p>
      {error ? (
        <Button
          type="button"
          onClick={() =>
            void beginLogin(
              toExternalApplicationPath(
                `${location.pathname}${location.search}${location.hash}`,
                applicationBasePath
              )
            )
          }
        >
          Войти
        </Button>
      ) : null}
    </main>
  )
}
