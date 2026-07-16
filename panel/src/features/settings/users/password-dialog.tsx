import { useState, type FormEvent } from "react"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Field,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import type { AdminUser } from "@/features/settings/users/model/users"

export function PasswordDialog({
  user,
  pending,
  serverError,
  onOpenChange,
  onSubmit,
}: {
  user: AdminUser
  pending: boolean
  serverError: string | null
  onOpenChange: (open: boolean) => void
  onSubmit: (password: string) => Promise<void>
}) {
  const [password, setPassword] = useState("")
  const [confirmation, setConfirmation] = useState("")
  const [validationError, setValidationError] = useState<string | null>(null)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setValidationError(null)

    if (password.length < 8) {
      setValidationError("Пароль должен содержать не менее 8 символов.")
      return
    }

    if (password !== confirmation) {
      setValidationError("Пароли не совпадают.")
      return
    }

    await onSubmit(password)
  }

  return (
    <Dialog open onOpenChange={(open) => !pending && onOpenChange(open)}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Сменить пароль</DialogTitle>
          <DialogDescription>
            Новый пароль для пользователя {user.username}.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="flex flex-col gap-6">
          <input
            type="text"
            name="username"
            value={user.username}
            autoComplete="username"
            readOnly
            tabIndex={-1}
            aria-hidden="true"
            className="sr-only"
          />
          <FieldGroup>
            <Field data-invalid={validationError !== null && !password}>
              <FieldLabel htmlFor="new-user-password">Новый пароль</FieldLabel>
              <Input
                id="new-user-password"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete="new-password"
                aria-invalid={validationError !== null && !password}
              />
            </Field>
            <Field
              data-invalid={
                validationError !== null && password !== confirmation
              }
            >
              <FieldLabel htmlFor="confirm-user-password">
                Повторите пароль
              </FieldLabel>
              <Input
                id="confirm-user-password"
                type="password"
                value={confirmation}
                onChange={(event) => setConfirmation(event.target.value)}
                autoComplete="new-password"
                aria-invalid={
                  validationError !== null && password !== confirmation
                }
              />
            </Field>
          </FieldGroup>

          {validationError || serverError ? (
            <FieldError>{validationError ?? serverError}</FieldError>
          ) : null}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={() => onOpenChange(false)}
            >
              Отмена
            </Button>
            <Button type="submit" disabled={pending}>
              {pending ? "Сохраняем…" : "Сохранить пароль"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
