import { useEffect, useRef } from "react"
import { useLocation } from "react-router-dom"

import App from "@/App"
import { Button } from "@/components/ui/button"
import { useAuth } from "@/features/auth/use-auth"

export function ProtectedApplication() {
  const location = useLocation()
  const { status, error, beginLogin } = useAuth()
  const redirectStarted = useRef(false)

  useEffect(() => {
    if (status !== "unauthenticated" || redirectStarted.current || error) {
      return
    }

    redirectStarted.current = true
    void beginLogin(`${location.pathname}${location.search}${location.hash}`)
  }, [
    beginLogin,
    error,
    location.hash,
    location.pathname,
    location.search,
    status,
  ])

  if (status === "authenticated") {
    return <App />
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
              `${location.pathname}${location.search}${location.hash}`
            )
          }
        >
          Войти
        </Button>
      ) : null}
    </main>
  )
}
