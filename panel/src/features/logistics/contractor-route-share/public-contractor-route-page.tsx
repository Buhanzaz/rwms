import { useMemo, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Calendar03Icon,
  Camera01Icon,
  CheckmarkCircle02Icon,
  Clock01Icon,
  DeliveryTruck01Icon,
  Image01Icon,
  ImageUploadIcon,
  Loading03Icon,
  PlayIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useParams } from "react-router-dom"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button, buttonVariants } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Separator } from "@/components/ui/separator"
import { Skeleton } from "@/components/ui/skeleton"
import {
  applyPublicContractorRouteAction,
  getPublicContractorRouteShare,
  type PublicContractorEvidence,
  type PublicContractorMedia,
  type PublicContractorRouteEntry,
  type PublicContractorRouteShare,
  type PublicContractorRouteTask,
  uploadPublicContractorEvidence,
} from "@/features/logistics/contractor-route-share/contractor-route-share-api"
import { ApiError } from "@/lib/api-client"
import { cn } from "@/lib/utils"

type EntryCommand = {
  externalTaskId: string
  entryId: string
  expectedVersion: number
  action: "START" | "COMPLETE"
  evidenceId: string | null
  idempotencyKey: string
}

type EvidenceUpload = {
  externalTaskId: string
  entryId: string
  evidenceId: string
  clientUploadKey: string
  capturedAt: string
  file: File
}

const ROUTE_QUERY_KEY = "public-contractor-route-share"

function formatDate(value: string) {
  const parsed = new Date(`${value}T12:00:00Z`)
  return Number.isNaN(parsed.getTime())
    ? value
    : new Intl.DateTimeFormat("ru-RU", { dateStyle: "long" }).format(parsed)
}

function formatDateTime(value: string) {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime())
    ? value
    : new Intl.DateTimeFormat("ru-RU", {
        dateStyle: "long",
        timeStyle: "short",
      }).format(parsed)
}

function quantity(value: number, unit: string | null) {
  return `${new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 2 }).format(value)}${unit ? ` ${unit}` : ""}`
}

function operationError(error: unknown) {
  if (error instanceof ApiError && error.status === 404) {
    return "Маршрут изменился или ссылка больше недоступна. Обновите страницу."
  }
  if (
    error instanceof Error &&
    [
      "Выберите фотографию JPEG или WebP.",
      "Фотография WebP должна быть не больше 1 МБ.",
      "Фотография JPEG должна быть не больше 15 МБ.",
    ].includes(error.message)
  ) {
    return error.message
  }
  return "Не удалось выполнить действие. Проверьте соединение и повторите попытку."
}

function replaceTask(
  current: PublicContractorRouteShare | undefined,
  task: PublicContractorRouteTask
) {
  if (!current) return current
  return {
    ...current,
    tasks: current.tasks.map((item) =>
      item.externalTaskId === task.externalTaskId ? task : item
    ),
  }
}

function navigationHref(task: PublicContractorRouteTask) {
  const destination =
    task.latitude !== null && task.longitude !== null
      ? `${task.latitude},${task.longitude}`
      : task.address
  return destination
    ? `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(destination)}`
    : null
}

function statusLabel(status: PublicContractorRouteEntry["status"]) {
  switch (status) {
    case "WAITING":
      return "Ожидает"
    case "IN_PROGRESS":
      return "В работе"
    case "PAUSED":
      return "Приостановлен"
    case "DONE":
      return "Выполнен"
    case "CANCELLED":
      return "Отменён"
  }
}

function statusTone(status: PublicContractorRouteEntry["status"]) {
  if (status === "CANCELLED") return "muted" as const
  if (status === "DONE") return "success" as const
  if (status === "IN_PROGRESS") return "info" as const
  return "warning" as const
}

function mediaKey(media: PublicContractorMedia) {
  return `${media.mediaId}:${media.generation}`
}

function routeEntryKey(
  task: PublicContractorRouteTask,
  entry: PublicContractorRouteEntry
) {
  return `${task.externalTaskId}:${entry.entryId}`
}

function orderedRouteEntries(route: PublicContractorRouteEntry[]) {
  return [...route].sort(
    (left, right) =>
      left.routeIndex - right.routeIndex ||
      left.routeStepIndex - right.routeStepIndex
  )
}

function readyEvidence(entry: PublicContractorRouteEntry) {
  return entry.evidence.find(
    (item) =>
      item.state === "READY" &&
      item.mediaId !== null &&
      item.mediaGeneration !== null
  )
}

function visibleEvidence(entry: PublicContractorRouteEntry) {
  return entry.evidence.filter(
    (
      item
    ): item is PublicContractorEvidence & {
      contentPath: string
      thumbnailPath: string
    } =>
      item.state === "READY" &&
      item.contentPath !== null &&
      item.thumbnailPath !== null
  )
}

export function PublicContractorRoutePage() {
  const { token = "" } = useParams()
  const queryClient = useQueryClient()
  const operationIds = useRef(new Map<string, string>())
  const evidenceIds = useRef(new Map<string, string>())
  const [entryErrors, setEntryErrors] = useState<Record<string, string>>({})

  const routeQuery = useQuery({
    queryKey: [ROUTE_QUERY_KEY, token],
    queryFn: () => getPublicContractorRouteShare(token),
    enabled: token.length > 0,
    retry: (failureCount, error) =>
      !(error instanceof ApiError && error.status === 404) && failureCount < 2,
    refetchInterval: (query) => {
      const route = query.state.data
      if (!route) return false
      const processing = route.tasks.some((task) =>
        task.route.some((entry) =>
          entry.evidence.some((item) =>
            ["RESERVED", "UPLOADING"].includes(item.state)
          )
        )
      )
      if (processing) return 2_000
      return route.tasks.some((task) => task.status === "ACTIVE")
        ? 10_000
        : false
    },
    refetchOnWindowFocus: "always",
  })

  const actionMutation = useMutation({
    mutationFn: (command: EntryCommand) =>
      applyPublicContractorRouteAction({ token, ...command }),
    onMutate: (command) => {
      setEntryErrors((current) => ({ ...current, [command.entryId]: "" }))
    },
    onSuccess: (result, command) => {
      queryClient.setQueryData<PublicContractorRouteShare>(
        [ROUTE_QUERY_KEY, token],
        (current) => replaceTask(current, result.task)
      )
      operationIds.current.delete(
        `${command.externalTaskId}:${command.entryId}:${command.action}`
      )
    },
    onError: (error, command) => {
      setEntryErrors((current) => ({
        ...current,
        [command.entryId]: operationError(error),
      }))
      void routeQuery.refetch()
    },
  })

  const uploadMutation = useMutation({
    mutationFn: (upload: EvidenceUpload) =>
      uploadPublicContractorEvidence({
        token,
        externalTaskId: upload.externalTaskId,
        entryId: upload.entryId,
        evidenceId: upload.evidenceId,
        capturedAt: upload.capturedAt,
        file: upload.file,
      }),
    onMutate: (upload) => {
      setEntryErrors((current) => ({ ...current, [upload.entryId]: "" }))
    },
    onSuccess: (_result, upload) => {
      evidenceIds.current.delete(upload.clientUploadKey)
      void routeQuery.refetch()
    },
    onError: (error, upload) => {
      setEntryErrors((current) => ({
        ...current,
        [upload.entryId]: operationError(error),
      }))
    },
  })

  const commandId = (
    task: PublicContractorRouteTask,
    entry: PublicContractorRouteEntry,
    action: "START" | "COMPLETE"
  ) => {
    const key = `${task.externalTaskId}:${entry.entryId}:${action}`
    const existing = operationIds.current.get(key)
    if (existing) return existing
    const created = crypto.randomUUID()
    operationIds.current.set(key, created)
    return created
  }

  const applyAction = (
    task: PublicContractorRouteTask,
    entry: PublicContractorRouteEntry,
    action: "START" | "COMPLETE"
  ) => {
    const evidence = action === "COMPLETE" ? readyEvidence(entry) : null
    actionMutation.mutate({
      externalTaskId: task.externalTaskId,
      entryId: entry.entryId,
      expectedVersion: entry.version,
      action,
      evidenceId: evidence?.evidenceId ?? null,
      idempotencyKey: commandId(task, entry, action),
    })
  }

  const uploadPhoto = (
    task: PublicContractorRouteTask,
    entry: PublicContractorRouteEntry,
    file: File
  ) => {
    const now = Date.now()
    const clientUploadKey = [
      task.externalTaskId,
      entry.entryId,
      file.name,
      file.type,
      file.size,
      file.lastModified,
    ].join(":")
    let evidenceId = evidenceIds.current.get(clientUploadKey)
    if (!evidenceId) {
      evidenceId = crypto.randomUUID()
      evidenceIds.current.set(clientUploadKey, evidenceId)
    }
    const capturedAt = new Date(
      Math.min(file.lastModified || now, now)
    ).toISOString()
    uploadMutation.mutate({
      externalTaskId: task.externalTaskId,
      entryId: entry.entryId,
      evidenceId,
      clientUploadKey,
      capturedAt,
      file,
    })
  }

  if (routeQuery.isPending) return <ContractorRouteSkeleton />
  if (routeQuery.isError || !routeQuery.data) {
    const unavailable =
      routeQuery.error instanceof ApiError &&
      [404, 410].includes(routeQuery.error.status)
    return (
      <ContractorRouteState
        title={
          unavailable ? "Маршрут не найден" : "Не удалось загрузить маршрут"
        }
        description={
          unavailable
            ? "Ссылка истекла, была отозвана или задания уже переданы другому водителю. Попросите логиста отправить новую ссылку."
            : "Проверьте подключение к интернету и повторите попытку."
        }
        onRetry={unavailable ? undefined : () => void routeQuery.refetch()}
      />
    )
  }

  const route = routeQuery.data
  const scheduledDates = Array.from(
    new Set(route.tasks.map((task) => task.scheduledDate))
  )
  const firstActionableEntryKey = route.tasks
    .flatMap((task) =>
      task.status === "ACTIVE"
        ? orderedRouteEntries(task.route).map((entry) => ({ task, entry }))
        : []
    )
    .find(({ entry }) => !["DONE", "CANCELLED"].includes(entry.status))
  const firstActionableKey = firstActionableEntryKey
    ? routeEntryKey(firstActionableEntryKey.task, firstActionableEntryKey.entry)
    : null

  return (
    <main className="h-svh overflow-y-auto bg-muted/30 text-foreground">
      <header className="border-b bg-background/95 backdrop-blur">
        <div className="mx-auto flex max-w-5xl flex-col gap-4 px-4 py-5 sm:px-6 lg:px-8">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0">
              <div className="mb-2 flex items-center gap-2 text-sm font-medium text-primary">
                <HugeiconsIcon icon={DeliveryTruck01Icon} className="size-4" />
                RWMS · маршрут подрядчика
              </div>
              <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">
                Задания на рейс
              </h1>
              <p className="mt-1 text-sm text-muted-foreground">
                Выполняйте этапы по порядку и подтверждайте результат
                фотографией.
              </p>
            </div>
            <Badge variant="secondary" className="shrink-0 gap-1.5">
              <HugeiconsIcon icon={Calendar03Icon} aria-hidden="true" />
              {scheduledDates.map(formatDate).join(", ")}
            </Badge>
          </div>
          <div className="flex flex-wrap gap-x-6 gap-y-2 text-sm text-muted-foreground">
            <span>{route.tasks.length} задан.</span>
            <span>Ссылка действует до {formatDateTime(route.expiresAt)}</span>
          </div>
        </div>
      </header>

      <section className="mx-auto flex max-w-5xl flex-col gap-5 px-4 py-6 sm:px-6 lg:px-8">
        {route.tasks.map((task, taskIndex) => (
          <ContractorTaskCard
            key={task.externalTaskId}
            task={task}
            taskIndex={taskIndex}
            actionBusy={
              actionMutation.isPending &&
              actionMutation.variables?.externalTaskId === task.externalTaskId
            }
            uploadBusy={
              uploadMutation.isPending &&
              uploadMutation.variables?.externalTaskId === task.externalTaskId
            }
            firstActionableEntryKey={firstActionableKey}
            entryErrors={entryErrors}
            onAction={applyAction}
            onUpload={uploadPhoto}
          />
        ))}
      </section>
    </main>
  )
}

function ContractorTaskCard({
  task,
  taskIndex,
  actionBusy,
  uploadBusy,
  firstActionableEntryKey,
  entryErrors,
  onAction,
  onUpload,
}: {
  task: PublicContractorRouteTask
  taskIndex: number
  actionBusy: boolean
  uploadBusy: boolean
  firstActionableEntryKey: string | null
  entryErrors: Record<string, string>
  onAction: (
    task: PublicContractorRouteTask,
    entry: PublicContractorRouteEntry,
    action: "START" | "COMPLETE"
  ) => void
  onUpload: (
    task: PublicContractorRouteTask,
    entry: PublicContractorRouteEntry,
    file: File
  ) => void
}) {
  const navigation = navigationHref(task)
  const entries = useMemo(() => orderedRouteEntries(task.route), [task.route])

  return (
    <Card className="overflow-hidden shadow-sm">
      <CardHeader className="gap-3 border-b bg-card/80">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <CardDescription>Задание {taskIndex + 1}</CardDescription>
            <CardTitle className="mt-1 text-lg sm:text-xl">
              {task.title}
            </CardTitle>
            {task.unitNumber ? (
              <p className="mt-1 text-sm font-medium">
                Бытовка №{task.unitNumber}
              </p>
            ) : null}
          </div>
          <Badge
            variant={
              task.status === "DONE"
                ? "success"
                : task.status === "CANCELLED"
                  ? "muted"
                  : "warning"
            }
          >
            {task.status === "DONE"
              ? "Завершено"
              : task.status === "CANCELLED"
                ? "Отменено"
                : "Активно"}
          </Badge>
        </div>
        <div className="grid gap-3 text-sm sm:grid-cols-[1fr_auto] sm:items-end">
          <div className="min-w-0">
            <p className="text-xs text-muted-foreground">Адрес</p>
            <p className="mt-0.5 font-medium break-words">
              {task.address || "Адрес уточняет логист"}
            </p>
          </div>
          {navigation ? (
            <Button asChild variant="outline" size="sm">
              <a href={navigation} target="_blank" rel="noreferrer">
                Открыть маршрут
              </a>
            </Button>
          ) : null}
        </div>
        {task.contactPhone ? (
          <p className="text-sm">
            Контакт:{" "}
            <a
              className="font-medium underline underline-offset-4"
              href={`tel:${task.contactPhone}`}
            >
              {task.contactPhone}
            </a>
          </p>
        ) : null}
        {task.description || task.logisticsComment ? (
          <Alert>
            <AlertTitle>Комментарий к заданию</AlertTitle>
            <AlertDescription>
              {task.description || task.logisticsComment}
            </AlertDescription>
          </Alert>
        ) : null}
      </CardHeader>
      <CardContent className="space-y-5 pt-5">
        {task.cargo.length > 0 ? (
          <section aria-label={`Груз задания ${taskIndex + 1}`}>
            <h2 className="text-sm font-semibold">Что везём или забираем</h2>
            <ul className="mt-2 grid gap-2 sm:grid-cols-2">
              {task.cargo.map((item, index) => (
                <li
                  key={`${item.kind}:${item.name}:${index}`}
                  className="rounded-lg border bg-muted/30 px-3 py-2 text-sm"
                >
                  <span className="font-medium">{item.name}</span>
                  <span className="ml-2 text-muted-foreground">
                    {quantity(item.quantity, item.unit)}
                  </span>
                  {item.comment ? (
                    <p className="mt-1 text-xs text-muted-foreground">
                      {item.comment}
                    </p>
                  ) : null}
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {task.sourceMedia.length > 0 ? (
          <MediaGallery title="Фотографии груза" media={task.sourceMedia} />
        ) : null}

        <Separator />
        <section aria-label={`Этапы задания ${taskIndex + 1}`}>
          <div className="mb-3 flex items-center justify-between gap-3">
            <h2 className="text-sm font-semibold">Порядок выполнения</h2>
            <span className="text-xs text-muted-foreground">
              {entries.length} этап.
            </span>
          </div>
          <ol className="space-y-3">
            {entries.map((entry, entryIndex) => (
              <li key={entry.entryId}>
                <RouteEntryCard
                  task={task}
                  entry={entry}
                  entryIndex={entryIndex}
                  actionBusy={actionBusy}
                  uploadBusy={uploadBusy}
                  taskActive={task.status === "ACTIVE"}
                  startAllowed={
                    routeEntryKey(task, entry) === firstActionableEntryKey
                  }
                  error={entryErrors[entry.entryId]}
                  onAction={onAction}
                  onUpload={onUpload}
                />
              </li>
            ))}
          </ol>
        </section>
      </CardContent>
    </Card>
  )
}

function RouteEntryCard({
  task,
  entry,
  entryIndex,
  actionBusy,
  uploadBusy,
  taskActive,
  startAllowed,
  error,
  onAction,
  onUpload,
}: {
  task: PublicContractorRouteTask
  entry: PublicContractorRouteEntry
  entryIndex: number
  actionBusy: boolean
  uploadBusy: boolean
  taskActive: boolean
  startAllowed: boolean
  error?: string
  onAction: (
    task: PublicContractorRouteTask,
    entry: PublicContractorRouteEntry,
    action: "START" | "COMPLETE"
  ) => void
  onUpload: (
    task: PublicContractorRouteTask,
    entry: PublicContractorRouteEntry,
    file: File
  ) => void
}) {
  const ready = readyEvidence(entry)
  const processing = entry.evidence.some((item) =>
    ["RESERVED", "UPLOADING"].includes(item.state)
  )
  const entryMedia = visibleEvidence(entry)
  const canUpload = taskActive && entry.status === "IN_PROGRESS" && !processing
  const canComplete =
    taskActive &&
    entry.status === "IN_PROGRESS" &&
    entry.completionAllowed &&
    Boolean(ready)

  return (
    <article className="rounded-xl border bg-background p-3 sm:p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 gap-3">
          <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold text-primary">
            {entryIndex + 1}
          </span>
          <div className="min-w-0">
            <h3 className="font-medium break-words">
              {entry.taskText || entry.queueName}
            </h3>
            {entry.plannedDurationMinutes !== null ? (
              <p className="mt-1 flex items-center gap-1 text-xs text-muted-foreground">
                <HugeiconsIcon icon={Clock01Icon} className="size-3.5" />
                Около {entry.plannedDurationMinutes} мин.
              </p>
            ) : null}
          </div>
        </div>
        <Badge variant={statusTone(entry.status)}>
          {statusLabel(entry.status)}
        </Badge>
      </div>

      {entry.works.length > 0 || entry.materials.length > 0 ? (
        <div className="mt-3 grid gap-3 text-sm sm:grid-cols-2">
          {entry.works.length > 0 ? (
            <div>
              <p className="text-xs font-medium text-muted-foreground">
                Работы
              </p>
              <ul className="mt-1 space-y-1">
                {entry.works.map((work) => (
                  <li key={work.id}>
                    {work.name} · {quantity(work.quantity, work.unit)}
                    {work.comment ? (
                      <span className="block text-xs text-muted-foreground">
                        {work.comment}
                      </span>
                    ) : null}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
          {entry.materials.length > 0 ? (
            <div>
              <p className="text-xs font-medium text-muted-foreground">
                Материалы
              </p>
              <ul className="mt-1 space-y-1">
                {entry.materials.map((material) => (
                  <li key={material.id}>
                    {material.name} ·{" "}
                    {quantity(material.quantity, material.unit)}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      ) : null}

      {entry.comments.length > 0 ? (
        <div className="mt-3 rounded-lg bg-muted/50 px-3 py-2 text-sm">
          {entry.comments.map((comment) => (
            <p key={comment.id} className="break-words">
              {comment.text}
            </p>
          ))}
        </div>
      ) : null}

      {entry.sourceMedia.length > 0 ? (
        <div className="mt-3">
          <MediaGallery title="Фото к этапу" media={entry.sourceMedia} />
        </div>
      ) : null}

      {entryMedia.length > 0 ? (
        <div className="mt-3">
          <MediaGallery
            title="Подтверждение результата"
            media={entryMedia.map((evidence) => ({
              mediaId: evidence.mediaId!,
              generation: evidence.mediaGeneration!,
              contentType: evidence.contentType,
              capturedAt: evidence.capturedAt,
              recordedAt: evidence.recordedAt,
              contentPath: evidence.contentPath,
              thumbnailPath: evidence.thumbnailPath,
            }))}
          />
        </div>
      ) : null}

      {processing ? (
        <p
          className="mt-3 flex items-center gap-2 text-sm text-muted-foreground"
          role="status"
        >
          <HugeiconsIcon icon={Loading03Icon} className="size-4 animate-spin" />
          Обрабатываем фотографию…
        </p>
      ) : null}

      {error ? (
        <Alert variant="destructive" className="mt-3">
          <AlertTitle>Не удалось выполнить этап</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}

      <div className="mt-4 flex flex-wrap gap-2">
        {entry.status === "WAITING" ? (
          <Button
            size="sm"
            disabled={actionBusy || !taskActive || !startAllowed}
            onClick={() => onAction(task, entry, "START")}
            title={
              startAllowed
                ? undefined
                : "Сначала завершите предыдущий этап маршрута"
            }
          >
            <HugeiconsIcon icon={PlayIcon} data-icon="inline-start" />
            {actionBusy ? "Начинаем…" : "Начать этап"}
          </Button>
        ) : null}

        {entry.status === "IN_PROGRESS" ? (
          <>
            <label
              className={cn(
                buttonVariants({ variant: "outline", size: "sm" }),
                (!canUpload || uploadBusy) && "pointer-events-none opacity-50"
              )}
            >
              <HugeiconsIcon icon={ImageUploadIcon} className="size-4" />
              {uploadBusy ? "Загружаем…" : "Добавить фото"}
              <input
                className="sr-only"
                type="file"
                accept="image/jpeg,image/webp"
                capture="environment"
                disabled={!canUpload || uploadBusy}
                onChange={(event) => {
                  const file = event.target.files?.[0]
                  if (file) onUpload(task, entry, file)
                  event.target.value = ""
                }}
              />
            </label>
            <Button
              size="sm"
              disabled={!canComplete || actionBusy || uploadBusy}
              onClick={() => onAction(task, entry, "COMPLETE")}
              title={
                canComplete
                  ? undefined
                  : "Сначала добавьте фотографию и дождитесь её обработки"
              }
            >
              <HugeiconsIcon
                icon={CheckmarkCircle02Icon}
                data-icon="inline-start"
              />
              {actionBusy ? "Завершаем…" : "Завершить этап"}
            </Button>
          </>
        ) : null}

        {entry.status === "DONE" ? (
          <span className="flex items-center gap-1.5 text-sm font-medium text-muted-foreground">
            <HugeiconsIcon icon={CheckmarkCircle02Icon} className="size-4" />
            Этап подтверждён
          </span>
        ) : null}
      </div>
    </article>
  )
}

function MediaGallery({
  title,
  media,
}: {
  title: string
  media: PublicContractorMedia[]
}) {
  const unique = Array.from(
    new Map(media.map((item) => [mediaKey(item), item])).values()
  )
  return (
    <section aria-label={title}>
      <p className="mb-2 flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <HugeiconsIcon icon={Camera01Icon} className="size-3.5" />
        {title}
      </p>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
        {unique.map((item, index) => (
          <a
            key={mediaKey(item)}
            href={item.contentPath}
            target="_blank"
            rel="noreferrer"
            className="group overflow-hidden rounded-lg border bg-muted outline-none focus-visible:ring-2 focus-visible:ring-ring"
            aria-label={`${title}, открыть фото ${index + 1}`}
          >
            <img
              src={item.thumbnailPath}
              alt={`${title}, фото ${index + 1}`}
              width={640}
              height={480}
              loading="lazy"
              className="aspect-[4/3] w-full object-cover transition-transform group-hover:scale-[1.02]"
            />
          </a>
        ))}
      </div>
    </section>
  )
}

function ContractorRouteSkeleton() {
  return (
    <main className="h-svh overflow-y-auto bg-muted/30">
      <header className="border-b bg-background">
        <div className="mx-auto flex max-w-5xl flex-col gap-3 px-4 py-5 sm:px-6 lg:px-8">
          <Skeleton className="h-8 w-72 max-w-full" />
          <Skeleton className="h-4 w-96 max-w-full" />
        </div>
      </header>
      <div className="mx-auto max-w-5xl space-y-5 px-4 py-6 sm:px-6 lg:px-8">
        <Skeleton className="h-72 rounded-xl" />
        <Skeleton className="h-72 rounded-xl" />
      </div>
    </main>
  )
}

function ContractorRouteState({
  title,
  description,
  onRetry,
}: {
  title: string
  description: string
  onRetry?: () => void
}) {
  return (
    <main className="flex h-svh items-center justify-center overflow-y-auto bg-muted/30 p-6">
      <div className="flex max-w-md flex-col items-center gap-3 text-center">
        <div className="flex size-12 items-center justify-center rounded-full bg-muted text-muted-foreground">
          <HugeiconsIcon
            icon={Image01Icon}
            className="size-6"
            aria-hidden="true"
          />
        </div>
        <h1 className="text-xl font-semibold">{title}</h1>
        <p className="text-sm text-muted-foreground">{description}</p>
        {onRetry ? (
          <Button variant="outline" onClick={onRetry}>
            Повторить
          </Button>
        ) : null}
      </div>
    </main>
  )
}
