import { useEffect, useRef, useState } from "react"
import { useNavigate } from "react-router-dom"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import {
  navigateAfterLogin,
  toApplicationRouterPath,
  toExternalApplicationPath,
} from "@/features/auth/auth-callback-navigation"
import { useAuth } from "@/features/auth/use-auth"

export function AuthCallbackPage({
  applicationBasePath = "",
}: {
  applicationBasePath?: string
}) {
  const navigate = useNavigate()
  const { completeLogin, beginLogin, error: authError } = useAuth()
  const started = useRef(false)
  const [error, setError] = useState<string | null>(null)
  const [retrying, setRetrying] = useState(false)
  const visibleError = authError ?? error

  useEffect(() => {
    if (started.current) {
      return
    }

    started.current = true

    void completeLogin()
      .then((returnTo) => {
        const routerPath = toApplicationRouterPath(
          returnTo,
          applicationBasePath
        )
        if (applicationBasePath !== "") {
          navigate(routerPath, { replace: true })
          return
        }
        navigateAfterLogin(routerPath, navigate)
      })
      .catch((callbackError: unknown) => {
        setError(
          callbackError instanceof Error
            ? callbackError.message
            : "Не удалось завершить вход."
        )
      })
  }, [applicationBasePath, completeLogin, navigate])

  return (
    <main className="flex min-h-svh items-center justify-center bg-muted p-6">
      <Card className="w-full max-w-md" size="sm">
        <CardHeader>
          <CardTitle>
            {visibleError ? "Не удалось войти" : "Завершаем вход"}
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4 text-sm text-muted-foreground">
          {visibleError ? (
            <p role="alert">{visibleError}</p>
          ) : (
            <p>Проверяем сессию…</p>
          )}
          {visibleError ? (
            <Button
              type="button"
              disabled={retrying}
              onClick={async () => {
                setRetrying(true)
                try {
                  await beginLogin(
                    toExternalApplicationPath("/", applicationBasePath)
                  )
                } finally {
                  setRetrying(false)
                }
              }}
            >
              {retrying ? "Подключаемся…" : "Войти снова"}
            </Button>
          ) : null}
        </CardContent>
      </Card>
    </main>
  )
}
