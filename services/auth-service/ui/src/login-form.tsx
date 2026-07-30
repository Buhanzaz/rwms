import { useEffect, useState } from "react";

import { loadCsrfToken, type CsrfToken } from "@/auth/csrf";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";

const PREFILL_DEV_CREDENTIALS =
  import.meta.env.DEV ||
  import.meta.env.VITE_DEV_DEFAULT_CREDENTIALS === "true";

export function LoginForm() {
  const search = new URLSearchParams(window.location.search);
  const isWorkerLogin = search.get("surface") === "worker";
  const hasLoginError = search.has("error");
  const hasLoggedOut = search.has("logout");
  const [csrf, setCsrf] = useState<CsrfToken | null>(null);
  const [csrfLoaded, setCsrfLoaded] = useState(false);

  useEffect(() => {
    document.title = isWorkerLogin
      ? "Вход — RWMS Рабочий"
      : "Вход — WMS Panel";

    let cancelled = false;

    void loadCsrfToken().then((token) => {
      if (!cancelled) {
        setCsrf(token);
        setCsrfLoaded(true);
      }
    });

    return () => {
      cancelled = true;
    };
  }, [isWorkerLogin]);

  return (
    <div className="flex flex-col gap-6">
      <Card className="overflow-hidden p-0">
        <CardContent className="grid p-0 md:grid-cols-2">
          <form method="post" action="login" className="p-6 md:p-8">
            <FieldGroup>
              <div className="flex flex-col items-center gap-2 text-center">
                <p className="text-sm font-medium text-primary">
                  {isWorkerLogin ? "RWMS Рабочий" : "WMS Panel"}
                </p>
                <h1 className="text-2xl font-bold">Вход в систему</h1>
                <FieldDescription>
                  {isWorkerLogin
                    ? "Введите логин и пароль рабочего из настроек доски."
                    : "Используйте учётную запись панели."}
                </FieldDescription>
              </div>

              {csrf ? (
                <input
                  type="hidden"
                  name={csrf.parameterName}
                  value={csrf.token}
                />
              ) : null}

              <Field>
                <FieldLabel htmlFor="username">Логин</FieldLabel>
                <Input
                  id="username"
                  name="username"
                  type="text"
                  autoComplete="username"
                  defaultValue={
                    PREFILL_DEV_CREDENTIALS && !isWorkerLogin ? "admin" : ""
                  }
                  aria-invalid={hasLoginError}
                  autoFocus
                  required
                />
              </Field>

              <Field>
                <FieldLabel htmlFor="password">Пароль</FieldLabel>
                <Input
                  id="password"
                  name="password"
                  type="password"
                  autoComplete="current-password"
                  defaultValue={
                    PREFILL_DEV_CREDENTIALS && !isWorkerLogin ? "admin" : ""
                  }
                  aria-invalid={hasLoginError}
                  required
                />
              </Field>

              {hasLoginError ? (
                <FieldError>Неверный логин или пароль.</FieldError>
              ) : null}

              {hasLoggedOut ? (
                <FieldDescription className="text-center">
                  Вы вышли из системы.
                </FieldDescription>
              ) : null}

              {csrfLoaded && csrf === null ? (
                <FieldError>
                  Не удалось подготовить защищённый вход. Обновите страницу.
                </FieldError>
              ) : null}

              <Field>
                <Button
                  type="submit"
                  className="w-full"
                  disabled={csrf === null}
                >
                  {csrfLoaded ? "Войти" : "Подготавливаем вход…"}
                </Button>
              </Field>
            </FieldGroup>
          </form>

          <div className="relative hidden min-h-[32rem] bg-muted md:block">
            <img
              src="wms-login-cover.png"
              alt="Складская площадка с модульными бытовками"
              className="absolute inset-0 size-full object-cover"
            />
            <div
              className="absolute inset-0 bg-linear-to-t from-foreground/30 via-transparent to-transparent"
              aria-hidden="true"
            />
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
