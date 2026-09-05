import { useState, type FormEvent, type ReactNode } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { Delete02Icon } from "@hugeicons/core-free-icons"

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
  QueueDefinitionDto,
  QueueDefinitionRequest,
  QueueLinkRequest,
  QueueType,
  ParticipationPolicy,
  QualificationRequest,
  WorkerClassDto,
  WorkerClassRequest,
  WorkerDto,
  WorkerGroupDto,
  WorkerGroupRequest,
  WorkerRequest,
} from "@/features/settings/task-board/model/task-board-settings"
import {
  participationPolicyLabels,
  queueTypeLabels,
} from "@/features/settings/task-board/model/task-board-settings"

function optional(value: string) {
  return value.trim() || null
}

function numberOrNull(value: string) {
  return value.trim() ? Number(value) : null
}

function defaultResultPhotoMinCount(type: QueueType) {
  return type === "HOLDING" ? 0 : 1
}

function isValidResultPhotoMinCount(value: string) {
  const count = numberOrNull(value)
  return count !== null && Number.isInteger(count) && count >= 0 && count <= 20
}

function normalizeBindings(bindings: QueueBindingRequest[]) {
  return bindings.map((binding, order) => ({
    ...binding,
    order,
    stopTaskOnTake: order === 0 ? false : binding.stopTaskOnTake,
    participationPolicy:
      order === 0
        ? ("PRIMARY" as const)
        : binding.participationPolicy === "PRIMARY"
          ? ("OPTIONAL" as const)
          : binding.participationPolicy,
    notifyOnPrimaryTake: order === 0 ? false : binding.notifyOnPrimaryTake,
  }))
}

function moveBinding(
  bindings: QueueBindingRequest[],
  workerClassId: string,
  direction: -1 | 1
) {
  const index = bindings.findIndex(
    (binding) => binding.workerClassId === workerClassId
  )
  const nextIndex = index + direction
  if (index < 0 || nextIndex < 0 || nextIndex >= bindings.length) {
    return bindings
  }

  const next = [...bindings]
  ;[next[index], next[nextIndex]] = [next[nextIndex], next[index]]
  return normalizeBindings(next)
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
  submitLabel = "Сохранить",
  pendingLabel = "Сохраняем…",
  submitDisabled = false,
  destructiveSubmit = false,
  footerActions,
  footerDestructiveAction,
}: {
  title: string
  description: string
  pending: boolean
  error: string | null
  children: ReactNode
  onClose: () => void
  onSubmit: (event: FormEvent<HTMLFormElement>) => void
  submitLabel?: string
  pendingLabel?: string
  submitDisabled?: boolean
  destructiveSubmit?: boolean
  footerActions?: ReactNode
  footerDestructiveAction?: ReactNode
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
          <DialogFooter
            className={
              footerActions || footerDestructiveAction
                ? "gap-2 sm:justify-between"
                : "gap-2"
            }
          >
            {footerActions || footerDestructiveAction ? (
              <div className="flex flex-wrap items-center gap-2">
                {footerDestructiveAction}
                {footerActions}
              </div>
            ) : null}
            <div className="flex flex-wrap justify-end gap-2">
              <Button
                type="button"
                variant="outline"
                disabled={pending}
                onClick={onClose}
              >
                Отмена
              </Button>
              <Button
                type="submit"
                variant={destructiveSubmit ? "destructive" : "default"}
                disabled={pending || submitDisabled}
              >
                {pending ? pendingLabel : submitLabel}
              </Button>
            </div>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

export function QueueLinkEditorDialog({
  definition,
  definitions,
  pending,
  error,
  onClose,
  onSave,
}: {
  definition: QueueDefinitionDto
  definitions: QueueDefinitionDto[]
  pending: boolean
  error: string | null
  onClose: () => void
  onSave: (request: QueueLinkRequest) => Promise<void>
}) {
  const [selectedId, setSelectedId] = useState("")
  const linked = definition.linkedQueueDefinitionId !== null
  const primaryClassIds = new Set(
    definition.bindings
      .filter((binding) => binding.participationPolicy === "PRIMARY")
      .map((binding) => binding.workerClass.id)
  )
  const candidates = definitions.filter(
    (item) =>
      item.id !== definition.id &&
      item.purpose === "GENERAL" &&
      item.type !== "HOLDING" &&
      item.linkedQueueDefinitionId === null &&
      item.bindings.some(
        (binding) =>
          binding.participationPolicy === "PRIMARY" &&
          primaryClassIds.has(binding.workerClass.id)
      )
  )
  const partner = linked
    ? definitions.find((item) => item.id === definition.linkedQueueDefinitionId)
    : candidates.find((item) => item.id === selectedId)

  return (
    <EditorShell
      title={`Связь очереди «${definition.name}»`}
      description="После завершения работ группа получает следующий этап по той же бытовке в парной очереди. Один участник берёт или сдаёт задание за всю группу."
      pending={pending}
      error={error}
      onClose={onClose}
      submitLabel={linked ? "Разорвать связь" : "Связать очереди"}
      destructiveSubmit={linked}
      submitDisabled={!partner}
      onSubmit={(event) => {
        event.preventDefault()
        if (!partner || pending) return
        void onSave({
          expectedVersion: definition.version,
          linkedQueueDefinitionId: linked ? null : partner.id,
          linkedQueueExpectedVersion: partner.version,
        })
      }}
    >
      <FieldGroup>
        {linked ? (
          <Field>
            <FieldLabel>Парная очередь</FieldLabel>
            <p>{partner?.name ?? "Очередь недоступна — обновите список"}</p>
            <FieldDescription>
              Разрыв связи снимет закрепление следующего этапа за группой. После
              этого можно выбрать другую пару.
            </FieldDescription>
          </Field>
        ) : (
          <Field data-disabled={pending || candidates.length === 0}>
            <FieldLabel htmlFor="linked-queue">Парная очередь</FieldLabel>
            <Select
              value={selectedId}
              onValueChange={setSelectedId}
              disabled={pending || candidates.length === 0}
            >
              <SelectTrigger id="linked-queue" className="w-full">
                <SelectValue placeholder="Выберите очередь" />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  {candidates.map((item) => (
                    <SelectItem key={item.id} value={item.id}>
                      {item.name}
                    </SelectItem>
                  ))}
                </SelectGroup>
              </SelectContent>
            </Select>
            <FieldDescription>
              {candidates.length
                ? "Доступны свободные рабочие очереди с общим основным классом исполнителей."
                : "Нет свободных очередей с общим основным классом исполнителей."}
            </FieldDescription>
          </Field>
        )}
      </FieldGroup>
    </EditorShell>
  )
}

export function QueueDefinitionEditorDialog({
  definition,
  classes,
  pending,
  error,
  onClose,
  onSave,
  onDelete,
}: {
  definition: QueueDefinitionDto | null
  classes: WorkerClassDto[]
  pending: boolean
  error: string | null
  onClose: () => void
  onSave: (request: QueueDefinitionRequest) => Promise<void>
  onDelete?: () => void
}) {
  const [name, setName] = useState(definition?.name ?? "")
  const [type, setType] = useState<QueueType>(definition?.type ?? "REPAIR")
  const [active, setActive] = useState(definition?.active ?? true)
  const [hidden, setHidden] = useState(definition?.hidden ?? false)
  const [collapsed, setCollapsed] = useState(definition?.collapsed ?? false)
  const [holdingPeriod, setHoldingPeriod] = useState(
    String(definition?.holdingPeriodMinutes ?? "")
  )
  const [threshold, setThreshold] = useState(
    String(definition?.notificationThreshold ?? "")
  )
  const [notify, setNotify] = useState(
    definition?.notifyWhenThresholdReached ?? false
  )
  const [resultPhotoMinCount, setResultPhotoMinCount] = useState(
    String(definition?.resultPhotoMinCount ?? defaultResultPhotoMinCount(type))
  )
  const [availableTaskLimit, setAvailableTaskLimit] = useState(
    String(definition?.availableTaskLimit ?? 6)
  )
  const [bindings, setBindings] = useState<QueueBindingRequest[]>(() =>
    normalizeBindings(
      [...(definition?.bindings ?? [])]
        .sort((left, right) => left.order - right.order)
        .map((binding) => ({
          workerClassId: binding.workerClass.id,
          order: binding.order,
          stopTaskOnTake: binding.stopTaskOnTake,
          participationPolicy: binding.participationPolicy,
          notifyOnPrimaryTake: binding.notifyOnPrimaryTake,
        }))
    )
  )
  const [validation, setValidation] = useState<string | null>(null)
  const resultPhotoMinCountInvalid =
    !isValidResultPhotoMinCount(resultPhotoMinCount)
  const availableTaskLimitNumber = numberOrNull(availableTaskLimit)
  const availableTaskLimitInvalid =
    availableTaskLimitNumber === null ||
    !Number.isInteger(availableTaskLimitNumber) ||
    availableTaskLimitNumber < 1 ||
    availableTaskLimitNumber > 50

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!name.trim()) {
      setValidation("Укажите название общей очереди.")
      return
    }
    const photoMinCount = numberOrNull(resultPhotoMinCount)
    if (photoMinCount === null || resultPhotoMinCountInvalid) {
      setValidation("Минимум фотографий должен быть целым числом от 0 до 20.")
      return
    }
    if (availableTaskLimitInvalid || availableTaskLimitNumber === null) {
      setValidation(
        "Количество заданий в плане на день должно быть целым числом от 1 до 50."
      )
      return
    }
    await onSave({
      version: definition?.version ?? 0,
      name: name.trim(),
      description: definition?.description ?? null,
      type,
      purpose: "GENERAL",
      sortOrder: definition?.sortOrder ?? 0,
      active,
      hidden,
      collapsed,
      holdingPeriodMinutes:
        type === "HOLDING" ? numberOrNull(holdingPeriod) : null,
      notificationThreshold:
        type === "HOLDING" ? numberOrNull(threshold) : null,
      notifyWhenThresholdReached: type === "HOLDING" && notify,
      resultPhotoMinCount: photoMinCount,
      availableTaskLimit: availableTaskLimitNumber,
      bindings: normalizeBindings(bindings),
    })
  }

  return (
    <EditorShell
      title={definition ? "Очередь каталога" : "Новая очередь каталога"}
      description="Параметры очереди задаются один раз для всей системы."
      pending={pending}
      error={validation ?? error}
      onClose={onClose}
      onSubmit={(event) => void submit(event)}
      footerDestructiveAction={
        definition && onDelete ? (
          <Button
            type="button"
            variant="destructive"
            size="icon"
            aria-label="Удалить"
            title="Удалить"
            disabled={pending}
            onClick={onDelete}
          >
            <HugeiconsIcon icon={Delete02Icon} aria-hidden="true" />
          </Button>
        ) : null
      }
    >
      <FieldGroup>
        <Field>
          <FieldLabel htmlFor="queue-definition-name">Название</FieldLabel>
          <Input
            id="queue-definition-name"
            value={name}
            maxLength={128}
            onChange={(event) => setName(event.target.value)}
            required
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="queue-definition-type">Тип</FieldLabel>
          <Select
            value={type}
            onValueChange={(value) => {
              const nextType = value as QueueType
              setType(nextType)
              if (!definition) {
                setResultPhotoMinCount(
                  String(defaultResultPhotoMinCount(nextType))
                )
              }
            }}
          >
            <SelectTrigger id="queue-definition-type" className="w-full">
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
        <Field data-invalid={resultPhotoMinCountInvalid || undefined}>
          <FieldLabel htmlFor="queue-result-photo-min-count">
            Минимум фото результата
          </FieldLabel>
          <Input
            id="queue-result-photo-min-count"
            type="number"
            min={0}
            max={20}
            step={1}
            value={resultPhotoMinCount}
            aria-invalid={resultPhotoMinCountInvalid}
            onChange={(event) => setResultPhotoMinCount(event.target.value)}
            required
          />
          <FieldDescription>
            Завершение задания доступно после загрузки указанного количества
            фотографий.
          </FieldDescription>
        </Field>
        <Field data-invalid={availableTaskLimitInvalid || undefined}>
          <FieldLabel htmlFor="queue-available-task-limit">
            План для новых складов
          </FieldLabel>
          <Input
            id="queue-available-task-limit"
            type="number"
            min={1}
            max={50}
            step={1}
            value={availableTaskLimit}
            aria-invalid={availableTaskLimitInvalid}
            onChange={(event) => setAvailableTaskLimit(event.target.value)}
            required
          />
          <FieldDescription>
            Начальное количество задач WorkerApp при создании очереди на складе.
            Текущий план каждого склада меняется прямо на доске задач.
          </FieldDescription>
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
        <FieldDescription>
          Выбранные классы получают связанные задания в указанном порядке.
        </FieldDescription>
        <FieldGroup className="gap-3">
          {classes.map((workerClass) => {
            const binding = bindings.find(
              (item) => item.workerClassId === workerClass.id
            )
            return (
              <Field key={workerClass.id} className="rounded-lg border p-3">
                <BooleanField
                  id={`queue-class-${workerClass.id}`}
                  checked={Boolean(binding)}
                  onCheckedChange={(checked) =>
                    setBindings((current) => {
                      if (checked) {
                        return normalizeBindings([
                          ...current,
                          {
                            workerClassId: workerClass.id,
                            order: current.length,
                            stopTaskOnTake: false,
                            participationPolicy:
                              current.length === 0 ? "PRIMARY" : "OPTIONAL",
                            notifyOnPrimaryTake: false,
                          },
                        ])
                      }
                      return normalizeBindings(
                        current.filter(
                          (item) => item.workerClassId !== workerClass.id
                        )
                      )
                    })
                  }
                >
                  {workerClass.name}
                </BooleanField>
                {!workerClass.active ? (
                  <FieldDescription>Класс отключён.</FieldDescription>
                ) : null}
              </Field>
            )
          })}
        </FieldGroup>
        {bindings.length > 0 ? (
          <FieldGroup className="gap-3">
            {bindings.map((binding, index) => {
              const workerClass = classes.find(
                (item) => item.id === binding.workerClassId
              )
              const primary = index === 0
              if (!workerClass) return null

              return (
                <Field
                  key={binding.workerClassId}
                  className="rounded-lg border p-3"
                >
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="font-medium">{workerClass.name}</span>
                    <div className="flex items-center gap-2">
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        disabled={index === 0}
                        onClick={() =>
                          setBindings((current) =>
                            moveBinding(current, binding.workerClassId, -1)
                          )
                        }
                      >
                        Выше
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        disabled={index === bindings.length - 1}
                        onClick={() =>
                          setBindings((current) =>
                            moveBinding(current, binding.workerClassId, 1)
                          )
                        }
                      >
                        Ниже
                      </Button>
                    </div>
                  </div>
                  {primary ? null : (
                    <FieldGroup className="gap-3">
                      <BooleanField
                        id={`queue-stop-${workerClass.id}`}
                        checked={binding.stopTaskOnTake}
                        onCheckedChange={(checked) =>
                          setBindings((current) =>
                            current.map((item) =>
                              item.workerClassId === workerClass.id
                                ? { ...item, stopTaskOnTake: checked }
                                : item
                            )
                          )
                        }
                      >
                        Останавливать текущую работу при взятии
                      </BooleanField>
                      <Field>
                        <FieldLabel
                          htmlFor={`queue-participation-${workerClass.id}`}
                        >
                          Участие
                        </FieldLabel>
                        <Select
                          value={binding.participationPolicy}
                          onValueChange={(value) =>
                            setBindings((current) =>
                              current.map((item) =>
                                item.workerClassId === workerClass.id
                                  ? {
                                      ...item,
                                      participationPolicy:
                                        value as ParticipationPolicy,
                                    }
                                  : item
                              )
                            )
                          }
                        >
                          <SelectTrigger
                            id={`queue-participation-${workerClass.id}`}
                            className="w-full"
                          >
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectGroup>
                              {(["REQUIRED", "OPTIONAL"] as const).map(
                                (policy) => (
                                  <SelectItem key={policy} value={policy}>
                                    {participationPolicyLabels[policy]}
                                  </SelectItem>
                                )
                              )}
                            </SelectGroup>
                          </SelectContent>
                        </Select>
                      </Field>
                      <BooleanField
                        id={`queue-notify-on-primary-${workerClass.id}`}
                        checked={binding.notifyOnPrimaryTake}
                        onCheckedChange={(checked) =>
                          setBindings((current) =>
                            current.map((item) =>
                              item.workerClassId === workerClass.id
                                ? { ...item, notifyOnPrimaryTake: checked }
                                : item
                            )
                          )
                        }
                      >
                        Уведомлять после начала работы первого класса
                      </BooleanField>
                    </FieldGroup>
                  )}
                </Field>
              )
            })}
          </FieldGroup>
        ) : (
          <FieldDescription>
            Выберите хотя бы один класс, если задания должны быть доступны
            рабочим.
          </FieldDescription>
        )}
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
  onDelete,
}: {
  item: WorkerClassDto | null
  pending: boolean
  error: string | null
  onClose: () => void
  onSave: (request: WorkerClassRequest) => Promise<void>
  onDelete?: () => void
}) {
  const [name, setName] = useState(item?.name ?? "")
  const [description, setDescription] = useState(item?.description ?? "")
  const [comment, setComment] = useState(item?.comment ?? "")
  const [sortOrder, setSortOrder] = useState(String(item?.sortOrder ?? 0))
  const [active, setActive] = useState(item?.active ?? true)
  const [validation, setValidation] = useState<string | null>(null)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!name.trim()) {
      setValidation("Укажите название класса.")
      return
    }
    await onSave({
      version: item?.version ?? 0,
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
      footerDestructiveAction={
        item && onDelete ? (
          <Button
            type="button"
            variant="destructive"
            size="icon"
            aria-label="Удалить"
            title="Удалить"
            disabled={pending}
            onClick={onDelete}
          >
            <HugeiconsIcon icon={Delete02Icon} aria-hidden="true" />
          </Button>
        ) : null
      }
    >
      <FieldGroup className="grid gap-4 md:grid-cols-2">
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
  onResetPassword,
  onDisableCredentials,
  onEnableCredentials,
  onDelete,
}: {
  item: WorkerDto | null
  classes: WorkerClassDto[]
  pending: boolean
  error: string | null
  onClose: () => void
  onSave: (request: WorkerRequest) => Promise<void>
  onResetPassword?: () => void
  onDisableCredentials?: () => void
  onEnableCredentials?: () => void
  onDelete?: () => void
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
    const editableClassIds = new Set(
      classes.map((workerClass) => workerClass.id)
    )
    const preservedQualifications =
      item?.qualifications
        .filter(
          (qualification) =>
            qualification.active &&
            !editableClassIds.has(qualification.workerClass.id)
        )
        .map((qualification) => ({
          workerClassId: qualification.workerClass.id,
          active: true,
          comment: qualification.comment,
        })) ?? []
    const editableQualifications: QualificationRequest[] = classes
      .filter((workerClass) => qualified[workerClass.id])
      .map((workerClass) => ({
        workerClassId: workerClass.id,
        active: true,
        comment: null,
      }))
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
      qualifications: [...editableQualifications, ...preservedQualifications],
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
      footerActions={
        item ? (
          <>
            {item.appLogin && onResetPassword ? (
              <Button
                type="button"
                variant="outline"
                disabled={pending}
                onClick={onResetPassword}
              >
                Сменить пароль
              </Button>
            ) : null}
            {item.appLogin && onDisableCredentials ? (
              <Button
                type="button"
                variant="outline"
                disabled={pending}
                onClick={onDisableCredentials}
              >
                Отключить вход
              </Button>
            ) : null}
            {item.appLogin && onEnableCredentials ? (
              <Button
                type="button"
                variant="outline"
                disabled={pending}
                onClick={onEnableCredentials}
              >
                Включить вход
              </Button>
            ) : null}
          </>
        ) : null
      }
      footerDestructiveAction={
        item && onDelete ? (
          <Button
            type="button"
            variant="destructive"
            size="icon"
            aria-label="Удалить"
            title="Удалить"
            disabled={pending}
            onClick={onDelete}
          >
            <HugeiconsIcon icon={Delete02Icon} aria-hidden="true" />
          </Button>
        ) : null
      }
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
              Используйте «Сменить пароль» или «Отключить вход» внизу окна.
            </FieldDescription>
          ) : null}
        </Field>
        {!item?.appLogin ? (
          <Field>
            <FieldLabel htmlFor="worker-password">Пароль</FieldLabel>
            <Input
              id="worker-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
            />
          </Field>
        ) : null}
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
  onDisableAvailability,
  onEnableAvailability,
  onDelete,
}: {
  item: WorkerGroupDto | null
  classes: WorkerClassDto[]
  workers: WorkerDto[]
  pending: boolean
  error: string | null
  onClose: () => void
  onSave: (request: WorkerGroupRequest) => Promise<void>
  onDisableAvailability?: () => void
  onEnableAvailability?: () => void
  onDelete?: () => void
}) {
  const [name, setName] = useState(item?.name ?? "")
  const [description, setDescription] = useState(item?.description ?? "")
  const [workerClassId, setWorkerClassId] = useState(
    item?.workerClass.id ?? classes[0]?.id ?? ""
  )
  const [active, setActive] = useState(item?.active ?? true)
  const [members, setMembers] = useState<Record<string, boolean>>(() =>
    Object.fromEntries(
      workers.map((worker) => [
        worker.id,
        Boolean(
          item?.members.some(
            (member) => member.workerId === worker.id && member.active
          )
        ),
      ])
    )
  )
  const [validation, setValidation] = useState<string | null>(null)
  const eligibleWorkers = workers.filter(
    (worker) =>
      worker.active &&
      worker.qualifications.some(
        (qualification) =>
          qualification.active && qualification.workerClass.id === workerClassId
      )
  )

  function changeWorkerClass(nextWorkerClassId: string) {
    setWorkerClassId(nextWorkerClassId)
    setMembers((current) =>
      Object.fromEntries(
        workers.map((worker) => [
          worker.id,
          Boolean(
            current[worker.id] &&
            worker.active &&
            worker.qualifications.some(
              (qualification) =>
                qualification.active &&
                qualification.workerClass.id === nextWorkerClassId
            )
          ),
        ])
      )
    )
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!name.trim() || !workerClassId) {
      setValidation("Укажите название и класс бригады.")
      return
    }
    const selectedWorkerIds = new Set(
      eligibleWorkers
        .filter((worker) => members[worker.id])
        .map((worker) => worker.id)
    )
    await onSave({
      version: item?.version ?? 0,
      workerClassId,
      name: name.trim(),
      description: optional(description),
      active,
      members: eligibleWorkers
        .filter((worker) => selectedWorkerIds.has(worker.id))
        .map((worker) => ({
          workerId: worker.id,
          active: true,
        })),
      currentGroupChanges: workers
        .filter((worker) => {
          const shouldBeCurrent = selectedWorkerIds.has(worker.id)
          const isCurrent = item !== null && worker.currentGroupId === item.id
          return shouldBeCurrent !== isCurrent
        })
        .map((worker) => ({
          workerId: worker.id,
          expectedVersion: worker.version,
          current: selectedWorkerIds.has(worker.id),
        })),
    })
  }
  return (
    <EditorShell
      title={item ? "Бригада" : "Новая бригада"}
      description="Выбранные участники сохраняются и назначаются текущими исполнителями бригады одной командой."
      pending={pending}
      error={validation ?? error}
      onClose={onClose}
      onSubmit={(event) => void submit(event)}
      footerActions={
        item ? (
          <>
            {onDisableAvailability ? (
              <Button
                type="button"
                variant="outline"
                disabled={pending || !item.active}
                onClick={onDisableAvailability}
              >
                Отключить работу
              </Button>
            ) : null}
            {onEnableAvailability ? (
              <Button
                type="button"
                variant="outline"
                disabled={pending || !item.active}
                onClick={onEnableAvailability}
              >
                Включить работу
              </Button>
            ) : null}
          </>
        ) : null
      }
      footerDestructiveAction={
        item && onDelete ? (
          <Button
            type="button"
            variant="destructive"
            size="icon"
            aria-label="Удалить"
            title="Удалить"
            disabled={pending}
            onClick={onDelete}
          >
            <HugeiconsIcon icon={Delete02Icon} aria-hidden="true" />
          </Button>
        ) : null
      }
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
          <Select value={workerClassId} onValueChange={changeWorkerClass}>
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
        <FieldDescription>
          Выбранные рабочие станут участниками и текущими исполнителями этой
          бригады после одного сохранения.
        </FieldDescription>
        <FieldGroup className="gap-3">
          {eligibleWorkers.map((worker) => (
            <Field key={worker.id} className="rounded-lg border p-3">
              <BooleanField
                id={`member-${worker.id}`}
                checked={Boolean(members[worker.id])}
                onCheckedChange={(checked) =>
                  setMembers((current) => ({
                    ...current,
                    [worker.id]: checked,
                  }))
                }
              >
                {worker.displayName}
              </BooleanField>
            </Field>
          ))}
          {eligibleWorkers.length === 0 ? (
            <FieldDescription>
              Нет активных рабочих с этой квалификацией.
            </FieldDescription>
          ) : null}
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

export function GroupAvailabilityDialog({
  group,
  pending,
  error,
  onClose,
  onSave,
}: {
  group: WorkerGroupDto
  pending: boolean
  error: string | null
  onClose: () => void
  onSave: (reason: string) => Promise<void>
}) {
  const [reason, setReason] = useState("")
  const [validation, setValidation] = useState<string | null>(null)
  const visibleError = validation ?? error

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const normalizedReason = reason.trim()
    if (!normalizedReason) {
      setValidation("Укажите причину недоступности.")
      return
    }
    setValidation(null)
    await onSave(normalizedReason)
  }

  return (
    <EditorShell
      title={`Отключить бригаду «${group.name}»?`}
      description="Бригада станет недоступна для новых заданий. Сервис проверит текущую работу и вернёт конфликт без локальной подмены результата."
      pending={pending}
      error={null}
      submitLabel="Отключить"
      pendingLabel="Отключаем…"
      destructiveSubmit
      onClose={onClose}
      onSubmit={(event) => void submit(event)}
    >
      <FieldGroup>
        <Field data-invalid={visibleError ? true : undefined}>
          <FieldLabel htmlFor="group-unavailability-reason">Причина</FieldLabel>
          <Textarea
            id="group-unavailability-reason"
            value={reason}
            maxLength={1000}
            disabled={pending}
            aria-invalid={Boolean(visibleError)}
            placeholder="Например, пересменка или техническая пауза"
            onChange={(event) => {
              setReason(event.target.value)
              setValidation(null)
            }}
          />
          <FieldDescription>
            Причина будет сохранена в состоянии выбранной бригады.
          </FieldDescription>
          <FieldError>{visibleError}</FieldError>
        </Field>
      </FieldGroup>
    </EditorShell>
  )
}
