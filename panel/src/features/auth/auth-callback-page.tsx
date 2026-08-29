import { useEffect, useRef, useState } from "react"
import { useNavigate } from "react-router-dom"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { navigateAfterLogin } from "@/features/auth/auth-callback-navigation"
import { useAuth } from "@/features/auth/use-auth"

export function AuthCallbackPage() {
  const navigate = useNavigate()
  const { completeLogin, beginLogin } = useAuth()
  const started = useRef(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (started.current) {
      return
    }

    started.current = true

    void completeLogin()
      .then((returnTo) => navigateAfterLogin(returnTo, navigate))
      .catch((callbackError: unknown) => {
        setError(
          callbackError instanceof Error
            ? callbackError.message
            : "Не удалось завершить вход."
        )
      })
  }, [completeLogin, navigate])

  return (
    <main className="flex min-h-svh items-center justify-center bg-muted p-6">
      <Card className="w-full max-w-md" size="sm">
        <CardHeader>
          <CardTitle>{error ? "Не удалось войти" : "Завершаем вход"}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4 text-sm text-muted-foreground">
          {error ? <p role="alert">{error}</p> : <p>Проверяем сессию…</p>}
          {error ? (
            <Button type="button" onClick={() => void beginLogin("/")}>
              Войти снова
            </Button>
          ) : null}
        </CardContent>
      </Card>
    </main>
  )
}
