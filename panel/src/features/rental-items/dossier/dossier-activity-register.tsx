import { useId, useState, type FormEvent } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowUpRight01Icon,
  Calendar03Icon,
  ChevronDownIcon,
  ClipboardCheckIcon,
  ClipboardPenLineIcon,
  FilterIcon,
  Settings02Icon,
  TruckDeliveryIcon,
  Wrench01Icon,
} from "@hugeicons/core-free-icons"
import { Link } from "react-router-dom"

import { ApiError } from "@/lib/api-client"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "@/components/ui/popover"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { Skeleton } from "@/components/ui/skeleton"
import { useResponsiveFiltersOpen } from "@/hooks/use-responsive-filters-open"
import { useAuth } from "@/features/auth/use-auth"
import { taskBoardEntryMediaOwner } from "@/features/media/media-service"
import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"
import {
  formatDossierActorLabel,
  type DossierActorDisplay,
} from "@/features/rental-items/dossier/actor/actor-display"
import { useDossierActorDisplays } from "@/features/rental-items/dossier/actor/use-dossier-actor-displays"
import {
  DOSSIER_ACTIVITY_CODES,
  DOSSIER_SOURCE_TYPES,
  type CabinDossierPage,
  type DossierActivity,
  type DossierActivityCode,
  type DossierActivityFilters,
  type DossierSourceType,
  type DossierTaskEvidencePhoto,
  type DossierVisibility,
} from "@/features/rental-items/dossier/model/dossier-service"
import { cn } from "@/lib/utils"

const ALL_ACTIVITY_CODES = "ALL_ACTIVITY_CODES"
const ALL_SOURCE_TYPES = "ALL_SOURCE_TYPES"

const sourceTypeLabel: Record<DossierSourceType, string> = {
  ASSET: "Имущество",
  MAINTENANCE: "Сметы и ремонт",
  INVENTORY: "Инвентаризация",
  MEDIA: "Фотографии",
  LOGISTICS: "Логистика",
  TASK_BOARD: "Задания",
}

const activityLabel: Record<DossierActivityCode, string> = {
  CABIN_CREATED: "Бытовка создана",
  CABIN_PASSPORT_CHANGED: "Паспорт бытовки изменён",
  CABIN_STATUS_CHANGED: "Статус бытовки изменён",
  CABIN_WAREHOUSE_CHANGED: "Склад бытовки изменён",
  CABIN_LOGISTICS_EFFECT_APPLIED: "Логистическая операция выполнена",
  CABIN_COMMENT_REVISION_CHANGED: "Общий комментарий изменён",
  CABIN_MANUAL_NOTE_ADDED: "Добавлена ручная заметка",
  ESTIMATE_CREATED: "Смета создана",
  ESTIMATE_DRAFT_CHANGED: "Черновик сметы изменён",
  ESTIMATE_COMPLETED: "Смета завершена",
  ESTIMATE_AMENDED: "Смета скорректирована",
  REPAIR_CREATED: "Ремонт создан",
  REPAIR_PLAN_CHANGED: "План ремонта изменён",
  REPAIR_QUEUED: "Ремонт поставлен в очередь",
  REPAIR_STAGE_COMPLETED: "Этап ремонта завершён",
  REPAIR_PENDING_ACCEPTANCE: "Ремонт ожидает приёмки",
  REPAIR_REWORK_CREATED: "Создана доработка ремонта",
  REPAIR_TRANSFER_PREPARED: "Передача ремонта подготовлена",
  REPAIR_TRANSFERRED: "Ремонт передан на другой склад",
  REPAIR_ACCEPTED: "Ремонт принят",
  REPAIR_WRITTEN_OFF: "Бытовка списана из ремонта",
  INVENTORY_FINDING_ADDED: "Зафиксирована находка инвентаризации",
  INVENTORY_INSPECTION_SAVED: "Осмотр сохранён",
  INVENTORY_PUBLICATION_READY: "Инвентаризация готова к публикации",
  INVENTORY_PUBLICATION_REQUESTED: "Публикация инвентаризации запрошена",
  INVENTORY_PUBLICATION_SUCCEEDED: "Инвентаризация опубликована",
  INVENTORY_PUBLICATION_TRANSIENT_FAILED: "Публикация временно не выполнена",
  INVENTORY_PUBLICATION_BLOCKED: "Публикация заблокирована",
  INVENTORY_PUBLICATION_CLOSED_BLOCKED: "Заблокированная публикация закрыта",
  MEDIA_READY: "Фотография готова",
  MEDIA_FAILED: "Обработка фотографии завершилась ошибкой",
  MEDIA_ROTATED: "Фотография повёрнута",
  MEDIA_DELETED: "Фотография удалена",
  MEDIA_TASK_EVIDENCE_ATTACHED: "Фотография задания добавлена в историю",
}

function formatInstant(value: string) {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return value

  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date)
}

function sourceLabel(activity: DossierActivity) {
  const source = activity.sourceRef
  return `${source.producer} · ${source.aggregateType}`
}

function dossierErrorPresentation(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 401) {
      return {
        title: "Требуется авторизация",
        description: "Сервис досье отклонил текущую сессию.",
      }
    }
    if (error.status === 403) {
      return {
        title: "Нет доступа к досье",
        description:
          "Сервис досье не разрешил чтение этой проекции текущему пользователю.",
      }
    }
    if (error.status === 404) {
      return {
        title: "Досье ещё не сформировано",
        description:
          "Нет видимого подтверждённого события для этой бытовки. Данные asset-service не подставляются вместо проекции.",
      }
    }
  }

  return {
    title: "Не удалось загрузить историю",
    description:
      error instanceof Error
        ? error.message
        : "Не удалось получить ответ от dossier-service.",
  }
}

export function DossierActivityFiltersPanel({
  value,
  onApply,
  showTechnicalFilters = false,
}: {
  value: DossierActivityFilters
  onApply: (value: DossierActivityFilters) => void
  showTechnicalFilters?: boolean
}) {
  const [draft, setDraft] = useState(value)
  const [periodOpen, setPeriodOpen] = useState(false)
  const formId = useId()
  const { filtersOpen, setFiltersOpen } = useResponsiveFiltersOpen()

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setPeriodOpen(false)
    onApply({
      occurredFrom: draft.occurredFrom?.trim() || undefined,
      occurredBefore: draft.occurredBefore?.trim() || undefined,
      activityCodes: draft.activityCodes,
      sourceTypes: draft.sourceTypes,
      actorSubjectId: draft.actorSubjectId?.trim() || undefined,
    })
  }

  function reset() {
    setDraft({})
    onApply({})
  }

  return (
    <div className="flex flex-col gap-2">
      <Button
        type="button"
        size="icon"
        variant={filtersOpen ? "secondary" : "outline"}
        className="self-start md:hidden"
        aria-label={
          filtersOpen ? "Скрыть фильтры истории" : "Показать фильтры истории"
        }
        aria-controls="dossier-activity-filters"
        aria-expanded={filtersOpen}
        onClick={() => setFiltersOpen((current) => !current)}
      >
        <HugeiconsIcon icon={FilterIcon} aria-hidden="true" />
      </Button>
      <div id="dossier-activity-filters" hidden={!filtersOpen}>
        <form id={formId} onSubmit={submit} aria-label="Фильтры истории">
          <FieldGroup className="flex flex-row flex-wrap items-center gap-2">
            <Field className="w-full sm:w-auto sm:max-w-72">
              <FieldLabel className="sr-only">Событие</FieldLabel>
              <Select
                value={draft.activityCodes?.[0] ?? ALL_ACTIVITY_CODES}
                onValueChange={(activityCode) =>
                  setDraft((current) => ({
                    ...current,
                    activityCodes:
                      activityCode === ALL_ACTIVITY_CODES
                        ? undefined
                        : [activityCode as DossierActivityCode],
                  }))
                }
              >
                <SelectTrigger className="w-full" aria-label="Событие">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value={ALL_ACTIVITY_CODES}>
                      Все события
                    </SelectItem>
                    {DOSSIER_ACTIVITY_CODES.map((code) => (
                      <SelectItem key={code} value={code}>
                        {activityLabel[code]}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
            <Field className="w-full sm:w-auto sm:max-w-56">
              <FieldLabel className="sr-only">Источник</FieldLabel>
              <Select
                value={draft.sourceTypes?.[0] ?? ALL_SOURCE_TYPES}
                onValueChange={(sourceType) =>
                  setDraft((current) => ({
                    ...current,
                    sourceTypes:
                      sourceType === ALL_SOURCE_TYPES
                        ? undefined
                        : [sourceType as DossierSourceType],
                  }))
                }
              >
                <SelectTrigger className="w-full" aria-label="Источник события">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value={ALL_SOURCE_TYPES}>
                      Все источники
                    </SelectItem>
                    {DOSSIER_SOURCE_TYPES.map((sourceType) => (
                      <SelectItem key={sourceType} value={sourceType}>
                        {showTechnicalFilters
                          ? sourceType
                          : sourceTypeLabel[sourceType]}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
            <Popover open={periodOpen} onOpenChange={setPeriodOpen}>
              <PopoverTrigger asChild>
                <Button
                  type="button"
                  variant="outline"
                  className="w-full sm:w-auto"
                >
                  <HugeiconsIcon
                    icon={Calendar03Icon}
                    data-icon="inline-start"
                    aria-hidden="true"
                  />
                  {draft.occurredFrom || draft.occurredBefore
                    ? "Период выбран"
                    : "Период"}
                </Button>
              </PopoverTrigger>
              <PopoverContent
                align="start"
                className="w-80 max-w-[calc(100vw-2rem)]"
                aria-label="Период истории"
              >
                <PopoverHeader>
                  <PopoverTitle>Период истории</PopoverTitle>
                  <PopoverDescription>
                    Дата и время с часовым поясом. Нижняя граница включается,
                    верхняя — нет.
                  </PopoverDescription>
                </PopoverHeader>
                <FieldGroup className="gap-3">
                  <Field>
                    <FieldLabel htmlFor="dossier-occurred-from">
                      События с
                    </FieldLabel>
                    <Input
                      id="dossier-occurred-from"
                      form={formId}
                      name="dossier-occurred-from"
                      value={draft.occurredFrom ?? ""}
                      placeholder="2026-07-18T00:00:00+03:00"
                      autoComplete="off"
                      spellCheck={false}
                      onChange={(event) =>
                        setDraft((current) => ({
                          ...current,
                          occurredFrom: event.target.value,
                        }))
                      }
                    />
                  </Field>
                  <Field>
                    <FieldLabel htmlFor="dossier-occurred-before">
                      События до
                    </FieldLabel>
                    <Input
                      id="dossier-occurred-before"
                      form={formId}
                      name="dossier-occurred-before"
                      value={draft.occurredBefore ?? ""}
                      placeholder="2026-07-19T00:00:00+03:00"
                      autoComplete="off"
                      spellCheck={false}
                      onChange={(event) =>
                        setDraft((current) => ({
                          ...current,
                          occurredBefore: event.target.value,
                        }))
                      }
                    />
                  </Field>
                </FieldGroup>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => setPeriodOpen(false)}
                >
                  Готово
                </Button>
              </PopoverContent>
            </Popover>
            <Button type="submit" className="w-full sm:w-auto">
              Применить
            </Button>
            <Button
              type="button"
              variant="ghost"
              className="w-full sm:w-auto"
              onClick={reset}
            >
              Сбросить
            </Button>
          </FieldGroup>
        </form>
      </div>
    </div>
  )
}

/** Visual category for one cabin-history timeline marker. */
type TimelineCategory =
  "INTERNAL" | "LOGISTICS" | "ESTIMATE" | "REPAIR" | "INVENTORY"

/** Activities joined only by a canonical source or media-folder identity. */
type TimelineGroup = {
  key: string
  category: TimelineCategory
  activities: DossierActivity[]
}

const timelineCategoryPresentation = {
  INTERNAL: { label: "Внутренняя операция", icon: Settings02Icon },
  LOGISTICS: { label: "Логистика", icon: TruckDeliveryIcon },
  ESTIMATE: { label: "Смета", icon: ClipboardPenLineIcon },
  REPAIR: { label: "Ремонт и приёмка", icon: Wrench01Icon },
  INVENTORY: { label: "Инвентаризация", icon: ClipboardCheckIcon },
} as const

const mediaStateLabel = {
  PROCESSING: "Обработка",
  READY: "Готово",
  FAILED: "Ошибка",
  DELETED: "Удалено",
} as const

function activityTimestamp(activity: DossierActivity) {
  const parsed = Date.parse(activity.occurredAt ?? activity.recordedAt)
  return Number.isFinite(parsed) ? parsed : 0
}

function timelineCategory(activity: DossierActivity): TimelineCategory {
  if (activity.activityCode.startsWith("ESTIMATE_")) return "ESTIMATE"
  if (activity.activityCode.startsWith("REPAIR_")) return "REPAIR"
  if (activity.activityCode.startsWith("INVENTORY_")) return "INVENTORY"
  if (activity.activityCode.startsWith("MEDIA_")) {
    return activity.sourceRef.secondaryId &&
      activity.sourceRef.secondaryId !== activity.cabinId
      ? "INVENTORY"
      : "INTERNAL"
  }
  if (
    activity.activityCode === "CABIN_LOGISTICS_EFFECT_APPLIED" ||
    activity.activityCode === "CABIN_WAREHOUSE_CHANGED"
  ) {
    return "LOGISTICS"
  }
  return "INTERNAL"
}

function timelineGroupKey(
  activity: DossierActivity,
  category: TimelineCategory
) {
  if (category === "ESTIMATE") {
    return `estimate:${activity.sourceRef.aggregateId}`
  }
  if (category === "REPAIR") {
    return `repair:${activity.sourceRef.aggregateId}`
  }
  if (category === "INVENTORY") {
    return `inventory:${activity.sourceRef.secondaryId ?? activity.sourceRef.aggregateId}`
  }
  if (activity.activityCode.startsWith("MEDIA_")) {
    const folderIds = new Set(activity.media.map((item) => item.folderId))
    if (folderIds.size === 1) {
      return `media-folder:${[...folderIds][0]}`
    }
  }
  return `activity:${activity.activityId}`
}

function buildTimelineGroups(activities: DossierActivity[]) {
  const grouped = new Map<string, TimelineGroup>()
  activities.forEach((activity) => {
    const category = timelineCategory(activity)
    const key = timelineGroupKey(activity, category)
    const existing = grouped.get(key)
    if (existing) {
      existing.activities.push(activity)
    } else {
      grouped.set(key, { key, category, activities: [activity] })
    }
  })

  return [...grouped.values()]
    .map((group) => ({
      ...group,
      activities: [...group.activities].sort(
        (left, right) =>
          activityTimestamp(left) - activityTimestamp(right) ||
          left.activityId.localeCompare(right.activityId)
      ),
    }))
    .sort((left, right) => {
      const leftLatest = left.activities.at(-1)
      const rightLatest = right.activities.at(-1)
      if (!leftLatest || !rightLatest) return 0
      return (
        activityTimestamp(leftLatest) - activityTimestamp(rightLatest) ||
        left.key.localeCompare(right.key)
      )
    })
}

function pluralize(value: number, one: string, few: string, many: string) {
  const mod100 = value % 100
  const mod10 = value % 10
  if (mod100 >= 11 && mod100 <= 14) return many
  if (mod10 === 1) return one
  if (mod10 >= 2 && mod10 <= 4) return few
  return many
}

function groupMedia(group: TimelineGroup) {
  return [
    ...new Map(
      group.activities.flatMap((activity) =>
        activity.media.map((item) => [item.mediaId, item] as const)
      )
    ).values(),
  ]
}

function timelineGroupTitle(group: TimelineGroup) {
  const latest = group.activities.at(-1)
  if (!latest) return "Операция"
  const media = groupMedia(group)
  if (
    media.length > 0 &&
    group.activities.every((activity) =>
      activity.activityCode.startsWith("MEDIA_")
    )
  ) {
    const noun = pluralize(
      media.length,
      "фотография",
      "фотографии",
      "фотографий"
    )
    const allReady = group.activities.every(
      (activity) => activity.activityCode === "MEDIA_READY"
    )
    const verb =
      media.length % 10 === 1 && media.length % 100 !== 11
        ? "Добавлена"
        : media.length % 10 >= 2 &&
            media.length % 10 <= 4 &&
            (media.length % 100 < 11 || media.length % 100 > 14)
          ? "Добавлены"
          : "Добавлено"
    return allReady
      ? `${verb} ${media.length} ${noun}`
      : `Фотографии операции: ${media.length}`
  }
  return activityLabel[latest.activityCode]
}

function timelineGroupSummary(group: TimelineGroup) {
  const parts: string[] = []
  if (group.activities.length > 1) {
    parts.push(
      `${group.activities.length} ${pluralize(group.activities.length, "событие", "события", "событий")}`
    )
  }
  const mediaCount = groupMedia(group).length
  if (mediaCount > 0) {
    parts.push(`${mediaCount} ${pluralize(mediaCount, "фото", "фото", "фото")}`)
  }
  return parts.join(" · ")
}

function timelineGroupRange(group: TimelineGroup) {
  const first = group.activities[0]
  const last = group.activities.at(-1)
  if (!first || !last) return "Время не указано"
  const firstValue = first.occurredAt ?? first.recordedAt
  const lastValue = last.occurredAt ?? last.recordedAt
  if (firstValue === lastValue) return formatInstant(lastValue)
  return `${formatInstant(firstValue)} — ${formatInstant(lastValue)}`
}

function actorLabel(
  activity: DossierActivity,
  actorDisplays: Map<string, DossierActorDisplay>
) {
  return activity.actorRef
    ? formatDossierActorLabel(
        activity.actorRef,
        actorDisplays.get(activity.actorRef.subjectId)
      )
    : "Автор не указан"
}

function groupActorSummary(
  group: TimelineGroup,
  actorDisplays: Map<string, DossierActorDisplay>
) {
  const labels = [
    ...new Set(
      group.activities.map((activity) => actorLabel(activity, actorDisplays))
    ),
  ]
  if (labels.length <= 1) return labels[0] ?? "Автор не указан"
  return `${labels[0]} и ещё ${labels.length - 1}`
}

function timelineGroupLink(group: TimelineGroup) {
  const source = group.activities.at(-1)?.sourceRef
  if (!source) return null
  if (group.category === "ESTIMATE") {
    return {
      label: "Открыть смету",
      to: `/estimates?estimateId=${encodeURIComponent(source.aggregateId)}`,
    }
  }
  if (group.category === "REPAIR") {
    return {
      label: "Открыть ремонт",
      to: `/repairs?repairId=${encodeURIComponent(source.aggregateId)}`,
    }
  }
  if (group.category === "INVENTORY") {
    return { label: "Открыть историю инвентаризаций", to: "/inventory/history" }
  }
  if (group.category === "LOGISTICS") {
    if (source.aggregateType === "RETURN") {
      return { label: "Открыть возвраты", to: "/logistics/returns" }
    }
    if (
      source.aggregateType === "TRANSFER" ||
      group.activities.some(
        (activity) => activity.activityCode === "CABIN_WAREHOUSE_CHANGED"
      )
    ) {
      return { label: "Открыть перемещения", to: "/logistics/transfers" }
    }
    if (source.aggregateType === "SHIPMENT") {
      return { label: "Открыть отгрузки", to: "/logistics/shipments" }
    }
  }
  return null
}

function TechnicalActorDetails({ activity }: { activity: DossierActivity }) {
  const actor = activity.actorRef
  if (!actor) return null
  return (
    <div
      className="flex flex-col gap-1 text-xs text-muted-foreground"
      data-testid={`technical-actor-${activity.activityId}`}
    >
      <p className="font-mono">actor: {actor.principalType}</p>
      <p className="font-mono">subject: {activity.sourceRef.aggregateType}</p>
      <p className="font-mono">
        entityType: {activity.sourceRef.aggregateType}
      </p>
      {actor.profileRevision ? (
        <p className="font-mono">profileRevision: {actor.profileRevision}</p>
      ) : null}
    </div>
  )
}

function ActivityMediaSummary({ activity }: { activity: DossierActivity }) {
  const counts = new Map<string, number>()
  activity.media.forEach((item) => {
    counts.set(item.state, (counts.get(item.state) ?? 0) + 1)
  })
  if (counts.size === 0) return null
  return (
    <div className="flex flex-wrap gap-2">
      {[...counts.entries()].map(([state, count]) => (
        <Badge key={state} variant="secondary">
          {mediaStateLabel[state as keyof typeof mediaStateLabel]}: {count}
        </Badge>
      ))}
    </div>
  )
}

/** Opens one task-owned evidence photo through the authorized media boundary. */
function TaskEvidencePhotoLink({
  photo,
  warehouseId,
  accessToken,
}: {
  photo: DossierTaskEvidencePhoto
  warehouseId: string
  accessToken: string | null
}) {
  const [open, setOpen] = useState(false)
  const owner = taskBoardEntryMediaOwner(photo.taskBoardEntryId, warehouseId)

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <Button
        type="button"
        variant="outline"
        size="sm"
        className="self-start"
        onClick={() => setOpen(true)}
      >
        Открыть фото задания
        <HugeiconsIcon
          icon={ArrowUpRight01Icon}
          data-icon="inline-end"
          aria-hidden="true"
        />
      </Button>
      <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Фотография задания</DialogTitle>
          <DialogDescription>
            Фотография открывается с проверкой доступа к заданию.
          </DialogDescription>
        </DialogHeader>
        <ServiceOwnerPhotos
          accessToken={accessToken}
          owner={owner}
          readOnly
          maxItems={1}
          title="Фотография задания"
          visibleMediaIds={[photo.mediaId]}
          authoritativeReadyReferences={[
            { mediaId: photo.mediaId, generation: photo.generation },
          ]}
        />
      </DialogContent>
    </Dialog>
  )
}

/** Renders every opaque task-photo reference recorded with the activity. */
function TaskEvidencePhotoLinks({
  activity,
  accessToken,
}: {
  activity: DossierActivity
  accessToken: string | null
}) {
  if (activity.taskEvidencePhotos.length === 0) return null

  return (
    <div className="flex flex-wrap gap-2">
      {activity.taskEvidencePhotos.map((photo) => (
        <TaskEvidencePhotoLink
          key={`${photo.taskBoardEntryId}:${photo.mediaId}:${photo.generation}`}
          photo={photo}
          warehouseId={activity.warehouseId}
          accessToken={accessToken}
        />
      ))}
    </div>
  )
}

function TimelineGroupDetails({
  group,
  actorDisplays,
  showTechnicalActorDetails,
  accessToken,
}: {
  group: TimelineGroup
  actorDisplays: Map<string, DossierActorDisplay>
  showTechnicalActorDetails: boolean
  accessToken: string | null
}) {
  const link = timelineGroupLink(group)
  const mediaCount = groupMedia(group).length
  return (
    <div className="flex min-w-0 flex-col gap-4 px-3 pt-1 pb-4">
      <Separator />
      <dl className="grid gap-3 text-sm sm:grid-cols-3">
        <div className="flex flex-col gap-1">
          <dt className="text-muted-foreground">Когда</dt>
          <dd className="font-medium">{timelineGroupRange(group)}</dd>
        </div>
        <div className="flex flex-col gap-1">
          <dt className="text-muted-foreground">Кто</dt>
          <dd className="font-medium">
            {groupActorSummary(group, actorDisplays)}
          </dd>
        </div>
        <div className="flex flex-col gap-1">
          <dt className="text-muted-foreground">Состав операции</dt>
          <dd className="font-medium">
            {group.activities.length}{" "}
            {pluralize(
              group.activities.length,
              "событие",
              "события",
              "событий"
            )}
            {mediaCount > 0 ? `, ${mediaCount} фото` : ""}
          </dd>
        </div>
      </dl>
      <div className="flex flex-col gap-2">
        <h4 className="text-sm font-medium">Ход операции</h4>
        <ol className="flex flex-col gap-3">
          {group.activities.map((activity) => (
            <li
              key={activity.activityId}
              className="grid min-w-0 gap-2 rounded-lg bg-muted/50 p-3 text-sm sm:grid-cols-[11rem_minmax(0,1fr)]"
            >
              <div className="flex flex-col gap-1 text-muted-foreground">
                <time dateTime={activity.occurredAt ?? activity.recordedAt}>
                  {activity.occurredAt
                    ? formatInstant(activity.occurredAt)
                    : "Время операции не указано"}
                </time>
                <span>Записано: {formatInstant(activity.recordedAt)}</span>
              </div>
              <div className="flex min-w-0 flex-col gap-2">
                <p className="font-medium">
                  {activityLabel[activity.activityCode]}
                </p>
                <p className="text-muted-foreground">
                  {actorLabel(activity, actorDisplays)}
                </p>
                <ActivityMediaSummary activity={activity} />
                <TaskEvidencePhotoLinks
                  activity={activity}
                  accessToken={accessToken}
                />
                {showTechnicalActorDetails ? (
                  <div className="flex flex-col gap-2">
                    <p className="font-mono text-xs break-all text-muted-foreground">
                      {sourceLabel(activity)} · {activity.sourceRef.aggregateId}
                    </p>
                    <TechnicalActorDetails activity={activity} />
                  </div>
                ) : null}
              </div>
            </li>
          ))}
        </ol>
      </div>
      {link ? (
        <Button variant="outline" size="sm" className="self-start" asChild>
          <Link to={link.to}>
            {link.label}
            <HugeiconsIcon
              icon={ArrowUpRight01Icon}
              data-icon="inline-end"
              aria-hidden="true"
            />
          </Link>
        </Button>
      ) : null}
    </div>
  )
}

function ActivityTimeline({
  activities,
  visibility,
  showTechnicalActorDetails,
  accessToken,
}: {
  activities: DossierActivity[]
  visibility: DossierVisibility
  showTechnicalActorDetails: boolean
  accessToken: string | null
}) {
  const actorDisplays = useDossierActorDisplays(
    activities.flatMap((activity) =>
      activity.actorRef ? [activity.actorRef.subjectId] : []
    )
  )
  const groups = buildTimelineGroups(activities)
  const partial = visibility === "PARTIAL"

  return (
    <Card data-testid="dossier-timeline">
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <CardTitle className="text-balance">История операций</CardTitle>
          <Badge variant={partial ? "outline" : "secondary"}>
            {visibility}
          </Badge>
          <Badge variant="outline">Загружено: {activities.length}</Badge>
          <Badge variant="outline">Групп: {groups.length}</Badge>
          {showTechnicalActorDetails ? (
            <Badge variant="outline">Подтверждено dossier-service</Badge>
          ) : null}
        </div>
        <CardDescription>
          {partial
            ? "История может быть неполной: показаны только доступные подтверждённые события. Нажмите на операцию, чтобы раскрыть детали."
            : "Подтверждённые события собраны по операциям. Нажмите на строку, чтобы увидеть полный состав."}
        </CardDescription>
      </CardHeader>
      <CardContent>
        {groups.length === 0 ? (
          <p className="py-8 text-center text-sm text-muted-foreground">
            Событий нет
          </p>
        ) : (
          <ol
            className="flex flex-col"
            aria-label="Хронология операций бытовки"
          >
            {groups.map((group, index) => {
              const latest = index === groups.length - 1
              const presentation = timelineCategoryPresentation[group.category]
              const Icon = presentation.icon
              const summary = timelineGroupSummary(group)
              const latestActivity = group.activities.at(-1)
              return (
                <li
                  key={group.key}
                  className="relative grid grid-cols-[2.5rem_minmax(0,1fr)] gap-3 pb-2 last:pb-0"
                >
                  {index < groups.length - 1 ? (
                    <span
                      className="absolute top-10 bottom-0 left-[1.21875rem] w-px bg-history-past/40"
                      aria-hidden="true"
                    />
                  ) : null}
                  <span
                    className={cn(
                      "relative flex size-10 items-center justify-center rounded-full",
                      latest
                        ? "bg-primary text-primary-foreground"
                        : "bg-history-past text-history-past-foreground"
                    )}
                    data-timeline-state={latest ? "latest" : "past"}
                    data-timeline-category={group.category}
                    aria-hidden="true"
                  >
                    <HugeiconsIcon icon={Icon} />
                  </span>
                  <Collapsible className="min-w-0">
                    <CollapsibleTrigger asChild>
                      <Button
                        variant="ghost"
                        className="group h-auto w-full justify-between gap-3 px-3 py-2 text-left whitespace-normal hover:bg-muted/70 hover:text-foreground dark:hover:bg-muted/70"
                      >
                        <span className="flex min-w-0 flex-1 flex-col items-start gap-1">
                          <span className="flex flex-wrap items-center gap-2">
                            <Badge variant="outline">
                              {presentation.label}
                            </Badge>
                            {latest ? <Badge>Последнее действие</Badge> : null}
                          </span>
                          <span className="font-medium">
                            {timelineGroupTitle(group)}
                          </span>
                          <span className="flex max-w-full flex-wrap gap-x-2 gap-y-1 text-xs text-muted-foreground">
                            <time
                              className="tabular-nums"
                              dateTime={
                                latestActivity?.occurredAt ??
                                latestActivity?.recordedAt
                              }
                            >
                              {timelineGroupRange(group)}
                            </time>
                            <span aria-hidden="true">·</span>
                            <span className="truncate">
                              {groupActorSummary(group, actorDisplays)}
                            </span>
                            {summary ? (
                              <>
                                <span aria-hidden="true">·</span>
                                <span>{summary}</span>
                              </>
                            ) : null}
                          </span>
                          {showTechnicalActorDetails && latestActivity ? (
                            <span className="font-mono text-xs text-muted-foreground">
                              {sourceLabel(latestActivity)}
                            </span>
                          ) : null}
                        </span>
                        <HugeiconsIcon
                          icon={ChevronDownIcon}
                          data-icon="inline-end"
                          className="transition-transform group-data-[state=open]:rotate-180 motion-reduce:transition-none"
                          aria-hidden="true"
                        />
                      </Button>
                    </CollapsibleTrigger>
                    <CollapsibleContent>
                      <TimelineGroupDetails
                        group={group}
                        actorDisplays={actorDisplays}
                        showTechnicalActorDetails={showTechnicalActorDetails}
                        accessToken={accessToken}
                      />
                    </CollapsibleContent>
                  </Collapsible>
                </li>
              )
            })}
          </ol>
        )}
      </CardContent>
    </Card>
  )
}

export function DossierActivityRegister({
  pages,
  error,
  isLoading,
  hasNextPage,
  isFetchingNextPage,
  onLoadMore,
  showTechnicalActorDetails = false,
}: {
  pages: CabinDossierPage[] | undefined
  error: unknown
  isLoading: boolean
  hasNextPage: boolean
  isFetchingNextPage: boolean
  onLoadMore: () => void
  showTechnicalActorDetails?: boolean
}) {
  const { accessToken } = useAuth()

  if (isLoading) {
    return (
      <Card aria-busy="true">
        <CardHeader>
          <CardTitle>Загрузка истории…</CardTitle>
          <CardDescription>
            Получаем подтверждённые события из dossier-service.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-4/5" />
        </CardContent>
      </Card>
    )
  }

  if (error) {
    const presentation = dossierErrorPresentation(error)
    return (
      <Card>
        <CardHeader>
          <CardTitle>{presentation.title}</CardTitle>
          <CardDescription>{presentation.description}</CardDescription>
        </CardHeader>
      </Card>
    )
  }

  if (!pages || pages.length === 0) return null

  const activities = pages.flatMap((page) => page.activities)
  const visibility = pages.some((page) => page.visibility === "PARTIAL")
    ? "PARTIAL"
    : "COMPLETE"

  return (
    <div className="flex flex-col gap-4">
      <ActivityTimeline
        activities={activities}
        visibility={visibility}
        showTechnicalActorDetails={showTechnicalActorDetails}
        accessToken={accessToken}
      />
      {hasNextPage ? (
        <Button
          className="self-start"
          variant="outline"
          disabled={isFetchingNextPage}
          onClick={onLoadMore}
        >
          {isFetchingNextPage ? "Загрузка…" : "Загрузить ещё"}
        </Button>
      ) : null}
    </div>
  )
}
