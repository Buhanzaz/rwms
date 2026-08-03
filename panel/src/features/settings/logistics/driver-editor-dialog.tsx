import { useState, type FormEvent } from "react"

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import type {
  WorkerDto,
  WorkerRequest,
} from "@/features/settings/task-board/model/task-board-settings"

function optional(value: string) {
  return value.trim() || null
}

function normalizedLogin(value: string | null | undefined) {
  return optional(value ?? "")?.toLowerCase() ?? null
}

export type DriverEditorDialogProps = {
  worker: WorkerDto | null
  primaryClassId: string
  pending: boolean
  error: string | null
  onClose: () => void
  onSave: (request: WorkerRequest) => Promise<void>
}

/**
 * Driver editing deliberately has no editable qualifications. The logistics
 * queue's primary class is attached on creation, and existing qualifications
 * are retained while editing, so the driver remains available in the queue.
 */
export function DriverEditorDialog({
  worker,
  primaryClassId,
  pending,
  error,
  onClose,
  onSave,
}: DriverEditorDialogProps) {
  const [displayName, setDisplayName] = useState(worker?.displayName ?? "")
  const [firstName, setFirstName] = useState(worker?.firstName ?? "")
  const [lastName, setLastName] = useState(worker?.lastName ?? "")
  const [middleName, setMiddleName] = useState(worker?.middleName ?? "")
  const [comment, setComment] = useState(worker?.comment ?? "")
  const [appLogin, setAppLogin] = useState(worker?.appLogin ?? "")
  const [password, setPassword] = useState("")
  const [active, setActive] = useState(worker?.active ?? true)
  const [validation, setValidation] = useState<string | null>(null)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setValidation(null)

    if (!displayName.trim()) {
      setValidation("Укажите отображаемое имя.")
      return
    }

    const login = optional(appLogin)
    const passwordProvided = Boolean(password)
    const loginChanged =
      normalizedLogin(login) !== normalizedLogin(worker?.appLogin)

    if (!login && passwordProvided) {
      setValidation("Пароль нельзя задать без логина.")
      return
    }
    if (!worker && login && !passwordProvided) {
      setValidation("Для нового логина нужен пароль не короче 8 символов.")
      return
    }
    if (worker && login && loginChanged && !passwordProvided) {
      setValidation(
        "Для изменения логина нужен новый пароль не короче 8 символов."
      )
      return
    }
    if (passwordProvided && password.length < 8) {
      setValidation("Пароль должен содержать не менее 8 символов.")
      return
    }

    const qualifications = worker
      ? worker.qualifications.map((qualification) => ({
          workerClassId: qualification.workerClass.id,
          active: qualification.active,
          comment: qualification.comment,
        }))
      : [
          {
            workerClassId: primaryClassId,
            active: true,
            comment: null,
          },
        ]

    await onSave({
      version: worker?.version ?? 0,
      displayName: displayName.trim(),
      firstName: optional(firstName),
      lastName: optional(lastName),
      middleName: optional(middleName),
      active,
      comment: optional(comment),
      appLogin: login,
      password: passwordProvided ? password : null,
      qualifications,
    })
  }

  return (
    <Dialog open onOpenChange={(open) => !open && !pending && onClose()}>
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>
            {worker ? "Редактировать водителя" : "Новый водитель"}
          </DialogTitle>
          <DialogDescription>
            Профиль водителя и мобильный логин.
          </DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(event) => void submit(event)}
          className="flex flex-col gap-6"
        >
          <FieldGroup className="grid gap-4 md:grid-cols-2">
            <Field className="md:col-span-2">
              <FieldLabel htmlFor="driver-display">Отображаемое имя</FieldLabel>
              <Input
                id="driver-display"
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                autoFocus
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="driver-last">Фамилия</FieldLabel>
              <Input
                id="driver-last"
                value={lastName}
                onChange={(event) => setLastName(event.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="driver-first">Имя</FieldLabel>
              <Input
                id="driver-first"
                value={firstName}
                onChange={(event) => setFirstName(event.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="driver-middle">Отчество</FieldLabel>
              <Input
                id="driver-middle"
                value={middleName}
                onChange={(event) => setMiddleName(event.target.value)}
              />
            </Field>
            <Field orientation="horizontal">
              <Checkbox
                id="driver-active"
                checked={active}
                onCheckedChange={(value) => setActive(value === true)}
              />
              <FieldLabel htmlFor="driver-active">Активен</FieldLabel>
            </Field>
            <Field>
              <FieldLabel htmlFor="driver-login">Логин приложения</FieldLabel>
              <Input
                id="driver-login"
                value={appLogin}
                onChange={(event) => setAppLogin(event.target.value)}
                autoComplete="off"
              />
              <FieldDescription>
                {worker?.credentialStatus === "ERROR"
                  ? "Доступ в приложение не настроен. Проверьте логин и укажите пароль ещё раз."
                  : "Необязательно: логин нужен для входа водителя в приложение."}
              </FieldDescription>
            </Field>
            <Field>
              <FieldLabel htmlFor="driver-password">
                {worker ? "Новый пароль (необязательно)" : "Пароль"}
              </FieldLabel>
              <Input
                id="driver-password"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete="new-password"
              />
            </Field>
            <Field className="md:col-span-2">
              <FieldLabel htmlFor="driver-comment">Комментарий</FieldLabel>
              <Textarea
                id="driver-comment"
                value={comment}
                onChange={(event) => setComment(event.target.value)}
              />
            </Field>
          </FieldGroup>
          {validation || error ? (
            <FieldError>{validation ?? error}</FieldError>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={onClose}
            >
              Отмена
            </Button>
            <Button type="submit" disabled={pending}>
              {pending
                ? "Сохраняем…"
                : worker
                  ? "Сохранить изменения"
                  : "Сохранить"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
