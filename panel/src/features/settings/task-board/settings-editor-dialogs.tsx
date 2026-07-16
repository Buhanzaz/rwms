import { useState, type FormEvent, type ReactNode } from "react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
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
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import type {
  QueueBindingRequest,
  QueueType,
  WorkerClassDto,
  WorkerClassRequest,
  WorkerDto,
  WorkerGroupDto,
  WorkerGroupRequest,
  WorkerRequest,
  WorkQueueDto,
  WorkQueueRequest,
} from "@/features/settings/task-board/model/task-board-settings"
import { queueTypeLabels } from "@/features/settings/task-board/model/task-board-settings"

function optional(value: string) {
  return value.trim() || null
}

function numberOrNull(value: string) {
  return value.trim() ? Number(value) : null
}

function BooleanField({
  id,
  checked,
  onCheckedChange,
  children,
}: {
  id: string
  checked: boolean
  onCheckedChange: (checked: boolean) => void
  children: ReactNode
}) {
  return (
    <Field orientation="horizontal">
      <Checkbox
        id={id}
        checked={checked}
        onCheckedChange={(value) => onCheckedChange(value === true)}
      />
      <FieldLabel htmlFor={id}>{children}</FieldLabel>
    </Field>
  )
}

function EditorShell({
  title,
  description,
  pending,
  error,
  children,
  onClose,
  onSubmit,
}: {
  title: string
  description: string
  pending: boolean
  error: string | null
  children: ReactNode
  onClose: () => void
  onSubmit: (event: FormEvent<HTMLFormElement>) => void
}) {
  return (
    <Dialog open onOpenChange={(open) => !open && !pending && onClose()}>
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <form onSubmit={onSubmit} className="flex flex-col gap-6">
          {children}
          {error ? <FieldError>{error}</FieldError> : null}
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
              {pending ? "Сохраняем…" : "Сохранить"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

export function QueueEditorDialog({
  queue,
  classes,
  pending,
  error,
  onClose,
  onSave,
}: {
  queue: WorkQueueDto | null
  classes: WorkerClassDto[]
  pending: boolean
  error: string | null
  onClose: () => void
  onSave: (request: WorkQueueRequest) => Promise<void>
}) {
  const [code, setCode] = useState(queue?.code ?? "")
  const [name, setName] = useState(queue?.name ?? "")
  const [description, setDescription] = useState(queue?.description ?? "")
  const [type, setType] = useState<QueueType>(queue?.type ?? "REPAIR")
  const [active, setActive] = useState(queue?.active ?? true)
  const [hidden, setHidden] = useState(queue?.hidden ?? false)
  const [collapsed, setCollapsed] = useState(queue?.collapsed ?? false)
  const [holdingPeriod, setHoldingPeriod] = useState(
    String(queue?.holdingPeriodMinutes ?? "")
  )
  const [threshold, setThreshold] = useState(
    String(queue?.notificationThreshold ?? "")
  )
  const [notify, setNotify] = useState(
    queue?.notifyWhenThresholdReached ?? false
  )
  const [bindings, setBindings] = useState<Record<string, QueueBindingRequest>>(
    () =>
      Object.fromEntries(
        (queue?.bindings ?? []).map((binding) => [
          binding.workerClass.id,
          {
            workerClassId: binding.workerClass.id,
            stopTaskOnTake: binding.stopTaskOnTake,
          },
        ])
      )
  )
  const [validation, setValidation] = useState<string | null>(null)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!code.trim() || !name.trim()) {
      setValidation("Укажите код и название очереди.")
      return
    }
    await onSave({
      version: queue?.version ?? 0,
      code: code.trim(),
      name: name.trim(),
      description: optional(description),
      type,
      active,
      hidden,
      collapsed,
      holdingPeriodMinutes:
        type === "HOLDING" ? numberOrNull(holdingPeriod) : null,
      notificationThreshold:
        type === "HOLDING" ? numberOrNull(threshold) : null,
      notifyWhenThresholdReached: type === "HOLDING" && notify,
      bindings: Object.values(bindings),
    })
  }

  return (
    <EditorShell
      title={queue ? "Очередь" : "Новая очередь"}
      description="Параметры очереди и классы исполнителей."
      pending={pending}
      error={validation ?? error}
      onClose={onClose}
      onSubmit={(event) => void submit(event)}
    >
      <FieldGroup className="grid gap-4 md:grid-cols-2">
        <Field>
          <FieldLabel htmlFor="queue-code">Код</FieldLabel>
          <Input
            id="queue-code"
            value={code}
            onChange={(event) => setCode(event.target.value)}
            required
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="queue-name">Название</FieldLabel>
          <Input
            id="queue-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            required
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="queue-type">Тип</FieldLabel>
          <Select
            value={type}
            onValueChange={(value) => setType(value as QueueType)}
          >
            <SelectTrigger id="queue-type" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {(Object.keys(queueTypeLabels) as QueueType[]).map((value) => (
                  <SelectItem key={value} value={value}>
                    {queueTypeLabels[value]}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </Field>
        <Field className="md:col-span-2">
          <FieldLabel htmlFor="queue-description">Описание</FieldLabel>
          <Textarea
            id="queue-description"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
        </Field>
        <BooleanField
          id="queue-active"
          checked={active}
          onCheckedChange={setActive}
        >
          Активна
        </BooleanField>
        <BooleanField
          id="queue-hidden"
          checked={hidden}
          onCheckedChange={setHidden}
        >
          Скрыта на доске
        </BooleanField>
        <BooleanField
          id="queue-collapsed"
          checked={collapsed}
          onCheckedChange={setCollapsed}
        >
          Свёрнута по умолчанию
        </BooleanField>
      </FieldGroup>
      {type === "HOLDING" ? (
        <FieldSet>
          <FieldLegend>Удержание</FieldLegend>
          <FieldGroup className="grid gap-4 md:grid-cols-2">
            <Field>
              <FieldLabel htmlFor="holding-period">Период, минут</FieldLabel>
              <Input
                id="holding-period"
                type="number"
                min={0}
                value={holdingPeriod}
                onChange={(event) => setHoldingPeriod(event.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="holding-threshold">
                Порог уведомления
              </FieldLabel>
              <Input
                id="holding-threshold"
                type="number"
                min={0}
                value={threshold}
                onChange={(event) => setThreshold(event.target.value)}
              />
            </Field>
            <BooleanField
              id="holding-notify"
              checked={notify}
              onCheckedChange={setNotify}
            >
              Уведомлять при достижении порога
            </BooleanField>
          </FieldGroup>
        </FieldSet>
      ) : null}
      <FieldSet>
        <FieldLegend>Классы исполнителей</FieldLegend>
        <FieldGroup className="gap-3">
          {classes.map((workerClass) => {
            const binding = bindings[workerClass.id]
            return (
              <Field key={workerClass.id} className="rounded-lg border p-3">
                <BooleanField
                  id={`queue-class-${workerClass.id}`}
                  checked={Boolean(binding)}
                  onCheckedChange={(checked) =>
                    setBindings((current) => {
                      const next = { ...current }
                      if (checked)
                        next[workerClass.id] = {
                          workerClassId: workerClass.id,
                          stopTaskOnTake: false,
                        }
                      else delete next[workerClass.id]
                      return next
                    })
                  }
                >
                  {workerClass.name}
                </BooleanField>
                {binding ? (
                  <BooleanField
                    id={`queue-stop-${workerClass.id}`}
                    checked={binding.stopTaskOnTake}
                    onCheckedChange={(checked) =>
                      setBindings((current) => ({
                        ...current,
                        [workerClass.id]: {
                          ...current[workerClass.id],
                          stopTaskOnTake: checked,
                        },
                      }))
                    }
                  >
                    Останавливать текущую задачу при взятии
                  </BooleanField>
                ) : null}
              </Field>
            )
          })}
        </FieldGroup>
      </FieldSet>
    </EditorShell>
  )
}

export function ClassEditorDialog({
  item,
  pending,
  error,
  onClose,
  onSave,
}: {
  item: WorkerClassDto | null
  pending: boolean
  error: string | null
  onClose: () => void
  onSave: (request: WorkerClassRequest) => Promise<void>
}) {
  const [code, setCode] = useState(item?.code ?? "")
  const [name, setName] = useState(item?.name ?? "")
  const [description, setDescription] = useState(item?.description ?? "")
  const [comment, setComment] = useState(item?.comment ?? "")
  const [sortOrder, setSortOrder] = useState(String(item?.sortOrder ?? 0))
  const [active, setActive] = useState(item?.active ?? true)
  const [validation, setValidation] = useState<string | null>(null)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!code.trim() || !name.trim()) {
      setValidation("Укажите код и название класса.")
      return
    }
    await onSave({
      version: item?.version ?? 0,
      code: code.trim(),
      name: name.trim(),
      description: optional(description),
      comment: optional(comment),
      sortOrder: Number(sortOrder) || 0,
      active,
    })
  }
  return (
    <EditorShell
      title={item ? "Класс рабочего" : "Новый класс"}
      description="Класс квалификации рабочих и бригад."
      pending={pending}
      error={validation ?? error}
      onClose={onClose}
      onSubmit={(event) => void submit(event)}
    >
      <FieldGroup className="grid gap-4 md:grid-cols-2">
        <Field>
          <FieldLabel htmlFor="class-code">Код</FieldLabel>
          <Input
            id="class-code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="class-name">Название</FieldLabel>
          <Input
            id="class-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="class-order">Порядок</FieldLabel>
          <Input
            id="class-order"
            type="number"
            value={sortOrder}
            onChange={(e) => setSortOrder(e.target.value)}
          />
        </Field>
        <BooleanField
          id="class-active"
          checked={active}
          onCheckedChange={setActive}
        >
          Активен
        </BooleanField>
        <Field>
          <FieldLabel htmlFor="class-description">Описание</FieldLabel>
          <Textarea
            id="class-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="class-comment">Комментарий</FieldLabel>
          <Textarea
            id="class-comment"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
          />
        </Field>
      </FieldGroup>
    </EditorShell>
  )
}

export function WorkerEditorDialog({
  item,
  classes,
  pending,
  error,
  onClose,
  onSave,
}: {
  item: WorkerDto | null
  classes: WorkerClassDto[]
  pending: boolean
  error: string | null
  onClose: () => void
  onSave: (request: WorkerRequest) => Promise<void>
}) {
  const [displayName, setDisplayName] = useState(item?.displayName ?? "")
  const [firstName, setFirstName] = useState(item?.firstName ?? "")
  const [lastName, setLastName] = useState(item?.lastName ?? "")
  const [middleName, setMiddleName] = useState(item?.middleName ?? "")
  const [comment, setComment] = useState(item?.comment ?? "")
  const [appLogin, setAppLogin] = useState(item?.appLogin ?? "")
  const [password, setPassword] = useState("")
  const [active, setActive] = useState(item?.active ?? true)
  const [qualified, setQualified] = useState<Record<string, boolean>>(() =>
    Object.fromEntries(
      (item?.qualifications ?? [])
        .filter((q) => q.active)
        .map((q) => [q.workerClass.id, true])
    )
  )
  const [validation, setValidation] = useState<string | null>(null)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!displayName.trim()) {
      setValidation("Укажите отображаемое имя.")
      return
    }
    const normalizedLogin = appLogin.trim()
    const loginChanged = normalizedLogin !== (item?.appLogin ?? "")
    if (normalizedLogin && loginChanged && password.length < 8) {
      setValidation("Для нового логина нужен пароль не короче 8 символов.")
      return
    }
    if (!normalizedLogin && password) {
      setValidation("Пароль нельзя задать без логина.")
      return
    }
    if (password && password.length < 8) {
      setValidation("Пароль должен содержать не менее 8 символов.")
      return
    }
    await onSave({
      version: item?.version ?? 0,
      displayName: displayName.trim(),
      firstName: optional(firstName),
      lastName: optional(lastName),
      middleName: optional(middleName),
      active,
      comment: optional(comment),
      appLogin: optional(appLogin),
      password: password || null,
      qualifications: classes
        .filter((c) => qualified[c.id])
        .map((c) => ({ workerClassId: c.id, active: true, comment: null })),
    })
  }
  return (
    <EditorShell
      title={item ? "Рабочий" : "Новый рабочий"}
      description="Профиль, квалификации и мобильный логин."
      pending={pending}
      error={validation ?? error}
      onClose={onClose}
      onSubmit={(event) => void submit(event)}
    >
      <FieldGroup className="grid gap-4 md:grid-cols-2">
        <Field className="md:col-span-2">
          <FieldLabel htmlFor="worker-display">Отображаемое имя</FieldLabel>
          <Input
            id="worker-display"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="worker-last">Фамилия</FieldLabel>
          <Input
            id="worker-last"
            value={lastName}
            onChange={(e) => setLastName(e.target.value)}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="worker-first">Имя</FieldLabel>
          <Input
            id="worker-first"
            value={firstName}
            onChange={(e) => setFirstName(e.target.value)}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="worker-middle">Отчество</FieldLabel>
          <Input
            id="worker-middle"
            value={middleName}
            onChange={(e) => setMiddleName(e.target.value)}
          />
        </Field>
        <BooleanField
          id="worker-active"
          checked={active}
          onCheckedChange={setActive}
        >
          Активен
        </BooleanField>
        <Field>
          <FieldLabel htmlFor="worker-login">Логин приложения</FieldLabel>
          <Input
            id="worker-login"
            value={appLogin}
            onChange={(e) => setAppLogin(e.target.value)}
            autoComplete="off"
            readOnly={Boolean(item?.appLogin)}
          />
          {item?.appLogin ? (
            <FieldDescription>
              Используйте «Пароль» для сброса или «Отключить вход» для удаления
              логина.
            </FieldDescription>
          ) : null}
        </Field>
        <Field>
          <FieldLabel htmlFor="worker-password">
            {item ? "Новый пароль (необязательно)" : "Пароль"}
          </FieldLabel>
          <Input
            id="worker-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
          />
        </Field>
        <Field className="md:col-span-2">
          <FieldLabel htmlFor="worker-comment">Комментарий</FieldLabel>
          <Textarea
            id="worker-comment"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
          />
        </Field>
      </FieldGroup>
      <FieldSet>
        <FieldLegend>Квалификации</FieldLegend>
        <FieldGroup className="grid gap-3 md:grid-cols-2">
          {classes.map((c) => (
            <BooleanField
              key={c.id}
              id={`qualification-${c.id}`}
              checked={Boolean(qualified[c.id])}
              onCheckedChange={(checked) =>
                setQualified((current) => ({ ...current, [c.id]: checked }))
              }
            >
              {c.name}
            </BooleanField>
          ))}
        </FieldGroup>
      </FieldSet>
    </EditorShell>
  )
}

export function GroupEditorDialog({
  item,
  classes,
  workers,
  pending,
  error,
  onClose,
  onSave,
}: {
  item: WorkerGroupDto | null
  classes: WorkerClassDto[]
  workers: WorkerDto[]
  pending: boolean
  error: string | null
  onClose: () => void
  onSave: (request: WorkerGroupRequest) => Promise<void>
}) {
  const [name, setName] = useState(item?.name ?? "")
  const [description, setDescription] = useState(item?.description ?? "")
  const [workerClassId, setWorkerClassId] = useState(
    item?.workerClass.id ?? classes[0]?.id ?? ""
  )
  const [active, setActive] = useState(item?.active ?? true)
  const [members, setMembers] = useState<
    Record<string, { enabled: boolean; role: string }>
  >(() =>
    Object.fromEntries(
      workers.map((w) => {
        const member = item?.members.find(
          (m) => m.workerId === w.id && m.active
        )
        return [
          w.id,
          { enabled: Boolean(member), role: member?.roleInGroup ?? "" },
        ]
      })
    )
  )
  const [validation, setValidation] = useState<string | null>(null)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!name.trim() || !workerClassId) {
      setValidation("Укажите название и класс бригады.")
      return
    }
    await onSave({
      version: item?.version ?? 0,
      workerClassId,
      name: name.trim(),
      description: optional(description),
      active,
      members: workers
        .filter((w) => members[w.id]?.enabled)
        .map((w) => ({
          workerId: w.id,
          roleInGroup: optional(members[w.id].role),
          active: true,
        })),
    })
  }
  return (
    <EditorShell
      title={item ? "Бригада" : "Новая бригада"}
      description="Класс бригады и активные участники."
      pending={pending}
      error={validation ?? error}
      onClose={onClose}
      onSubmit={(event) => void submit(event)}
    >
      <FieldGroup className="grid gap-4 md:grid-cols-2">
        <Field>
          <FieldLabel htmlFor="group-name">Название</FieldLabel>
          <Input
            id="group-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="group-class">Класс</FieldLabel>
          <Select value={workerClassId} onValueChange={setWorkerClassId}>
            <SelectTrigger id="group-class" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {classes.map((c) => (
                  <SelectItem key={c.id} value={c.id}>
                    {c.name}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </Field>
        <Field className="md:col-span-2">
          <FieldLabel htmlFor="group-description">Описание</FieldLabel>
          <Textarea
            id="group-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </Field>
        <BooleanField
          id="group-active"
          checked={active}
          onCheckedChange={setActive}
        >
          Активна
        </BooleanField>
      </FieldGroup>
      <FieldSet>
        <FieldLegend>Участники</FieldLegend>
        <FieldGroup className="gap-3">
          {workers.map((worker) => {
            const member = members[worker.id] ?? { enabled: false, role: "" }
            return (
              <Field key={worker.id} className="rounded-lg border p-3">
                <BooleanField
                  id={`member-${worker.id}`}
                  checked={member.enabled}
                  onCheckedChange={(checked) =>
                    setMembers((current) => ({
                      ...current,
                      [worker.id]: { ...member, enabled: checked },
                    }))
                  }
                >
                  {worker.displayName}
                </BooleanField>
                {member.enabled ? (
                  <Input
                    value={member.role}
                    onChange={(e) =>
                      setMembers((current) => ({
                        ...current,
                        [worker.id]: { ...member, role: e.target.value },
                      }))
                    }
                    aria-label={`Роль в бригаде: ${worker.displayName}`}
                    placeholder="Роль в бригаде"
                  />
                ) : null}
              </Field>
            )
          })}
        </FieldGroup>
      </FieldSet>
    </EditorShell>
  )
}

export function CredentialPasswordDialog({
  worker,
  pending,
  error,
  onClose,
  onSave,
}: {
  worker: WorkerDto
  pending: boolean
  error: string | null
  onClose: () => void
  onSave: (password: string) => Promise<void>
}) {
  const [password, setPassword] = useState("")
  const [validation, setValidation] = useState<string | null>(null)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (password.length < 8) {
      setValidation("Пароль должен содержать не менее 8 символов.")
      return
    }
    await onSave(password)
  }
  return (
    <EditorShell
      title="Сбросить пароль"
      description={`Учётные данные рабочего ${worker.displayName}.`}
      pending={pending}
      error={validation ?? error}
      onClose={onClose}
      onSubmit={(event) => void submit(event)}
    >
      <FieldGroup>
        <Input
          className="sr-only"
          tabIndex={-1}
          aria-hidden="true"
          autoComplete="username"
          value={worker.appLogin ?? ""}
          readOnly
        />
        <Field>
          <FieldLabel htmlFor="credential-password">Новый пароль</FieldLabel>
          <Input
            id="credential-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
          />
        </Field>
      </FieldGroup>
    </EditorShell>
  )
}
