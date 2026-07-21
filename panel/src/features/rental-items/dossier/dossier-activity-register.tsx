import { useState, type FormEvent } from "react"

import { ApiError } from "@/lib/api-client"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
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
  type DossierVisibility,
} from "@/features/rental-items/dossier/model/dossier-service"

const ALL_ACTIVITY_CODES = "ALL_ACTIVITY_CODES"
const ALL_SOURCE_TYPES = "ALL_SOURCE_TYPES"

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
  return `${source.producer} · ${source.aggregateType} · ${source.aggregateId}`
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

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
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
    <Card>
      <CardHeader>
        <CardTitle>Фильтры истории</CardTitle>
        <CardDescription>
          {showTechnicalFilters
            ? "Фильтры выполняются dossier-service. Время задаётся в RFC 3339, actor — только непрозрачным UUID."
            : "Фильтры выполняются dossier-service по подтверждённым событиям."}
        </CardDescription>
      </CardHeader>
      <form onSubmit={submit}>
        <CardContent>
          <FieldGroup className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            <Field>
              <FieldLabel>Код события</FieldLabel>
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
                <SelectTrigger className="w-full" aria-label="Код события">
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
                        {showTechnicalFilters ? ` · ${code}` : ""}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
            <Field>
              <FieldLabel>Источник</FieldLabel>
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
                        {sourceType}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
            {showTechnicalFilters ? (
              <Field>
                <FieldLabel htmlFor="dossier-actor-subject-id">
                  Actor subject UUID
                </FieldLabel>
                <Input
                  id="dossier-actor-subject-id"
                  value={draft.actorSubjectId ?? ""}
                  placeholder="00000000-0000-0000-0000-000000000000"
                  onChange={(event) =>
                    setDraft((current) => ({
                      ...current,
                      actorSubjectId: event.target.value,
                    }))
                  }
                />
              </Field>
            ) : null}
            <Field>
              <FieldLabel htmlFor="dossier-occurred-from">События с</FieldLabel>
              <Input
                id="dossier-occurred-from"
                value={draft.occurredFrom ?? ""}
                placeholder="2026-07-18T00:00:00Z"
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
                value={draft.occurredBefore ?? ""}
                placeholder="2026-07-19T00:00:00Z"
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    occurredBefore: event.target.value,
                  }))
                }
              />
            </Field>
          </FieldGroup>
        </CardContent>
        <CardFooter className="flex-wrap gap-2">
          <Button type="submit">Применить</Button>
          <Button type="button" variant="outline" onClick={reset}>
            Сбросить
          </Button>
        </CardFooter>
      </form>
    </Card>
  )
}

function CoverageCard({
  visibility,
  loaded,
}: {
  visibility: DossierVisibility
  loaded: number
}) {
  const partial = visibility === "PARTIAL"

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <CardTitle>Покрытие проекции</CardTitle>
          <Badge variant={partial ? "outline" : "secondary"}>
            {visibility}
          </Badge>
          <Badge variant="outline">Загружено: {loaded}</Badge>
        </div>
        <CardDescription>
          {partial
            ? "Покрытие частичное: часть producer facts отсутствует или не видна текущему пользователю. Скрытые строки и их количество сервис не раскрывает."
            : "Dossier-service сообщает полное покрытие для видимой проекции и текущих фильтров."}
        </CardDescription>
      </CardHeader>
    </Card>
  )
}

function ActorCell({
  activity,
  display,
  showTechnicalActorDetails,
}: {
  activity: DossierActivity
  display: DossierActorDisplay | undefined
  showTechnicalActorDetails: boolean
}) {
  const actor = activity.actorRef
  if (!actor) {
    return <span className="text-muted-foreground">Автор не указан</span>
  }

  return (
    <div className="flex flex-col gap-2">
      <p className="font-medium">{formatDossierActorLabel(actor, display)}</p>
      {showTechnicalActorDetails ? (
        <div
          className="flex flex-col gap-1 text-xs text-muted-foreground"
          data-testid={`technical-actor-${activity.activityId}`}
        >
          <p className="font-mono">
            {actor.principalType} · {actor.subjectId}
          </p>
          <p className="font-mono">actor: {actor.principalType}</p>
          <p className="font-mono">actorId: {actor.subjectId}</p>
          <p className="font-mono">
            subject: {activity.sourceRef.aggregateType}
          </p>
          <p className="font-mono">
            subjectId: {activity.sourceRef.aggregateId}
          </p>
          <p className="font-mono">
            entityType: {activity.sourceRef.aggregateType}
          </p>
          {actor.profileRevision ? (
            <p className="font-mono">
              profileRevision: {actor.profileRevision}
            </p>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}

function ActivityTable({
  activities,
  actorDisplays,
  showTechnicalActorDetails,
}: {
  activities: DossierActivity[]
  actorDisplays: Map<string, DossierActorDisplay>
  showTechnicalActorDetails: boolean
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Подтверждённые события</CardTitle>
        <CardDescription>
          {showTechnicalActorDetails
            ? "Имя и роль автора получены из auth-service. Исходные Actor, subject и source reference показаны отдельно как технические данные."
            : "Показаны понятное описание действия и пользователь, выполнивший его."}
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Время</TableHead>
              <TableHead>Событие</TableHead>
              <TableHead>Actor</TableHead>
              <TableHead>Media</TableHead>
              {showTechnicalActorDetails ? (
                <TableHead>Источник</TableHead>
              ) : null}
            </TableRow>
          </TableHeader>
          <TableBody>
            {activities.length === 0 ? (
              <TableRow>
                <TableCell
                  colSpan={showTechnicalActorDetails ? 5 : 4}
                  className="h-24 text-center text-muted-foreground"
                >
                  Событий нет
                </TableCell>
              </TableRow>
            ) : (
              activities.map((activity) => (
                <TableRow key={activity.activityId}>
                  <TableCell className="min-w-48 align-top">
                    <p>
                      {activity.occurredAt
                        ? formatInstant(activity.occurredAt)
                        : "Бизнес-время не указано"}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      Записано: {formatInstant(activity.recordedAt)}
                    </p>
                  </TableCell>
                  <TableCell className="min-w-64 align-top">
                    <Badge variant="outline">
                      {activityLabel[activity.activityCode]}
                    </Badge>
                    {showTechnicalActorDetails ? (
                      <>
                        <p className="mt-2 font-mono text-xs text-muted-foreground">
                          {activity.activityCode}
                        </p>
                        <p className="mt-2 font-mono text-xs text-muted-foreground">
                          {activity.activityId}
                        </p>
                      </>
                    ) : null}
                  </TableCell>
                  <TableCell className="min-w-72 align-top">
                    <ActorCell
                      activity={activity}
                      display={
                        activity.actorRef
                          ? actorDisplays.get(activity.actorRef.subjectId)
                          : undefined
                      }
                      showTechnicalActorDetails={showTechnicalActorDetails}
                    />
                  </TableCell>
                  <TableCell className="min-w-72 align-top">
                    {activity.media.length === 0 ? (
                      <span className="text-muted-foreground">Нет</span>
                    ) : (
                      <div className="flex flex-col gap-2">
                        {activity.media.map((media) => (
                          <div key={media.mediaId}>
                            <Badge variant="secondary">{media.state}</Badge>
                            {showTechnicalActorDetails ? (
                              <>
                                <p className="mt-1 font-mono text-xs">
                                  {media.mediaId} · generation{" "}
                                  {media.generation}
                                </p>
                                <p className="font-mono text-xs text-muted-foreground">
                                  findingId: {media.findingId}
                                </p>
                              </>
                            ) : null}
                          </div>
                        ))}
                      </div>
                    )}
                  </TableCell>
                  {showTechnicalActorDetails ? (
                    <TableCell className="min-w-80 align-top">
                      <p className="font-mono text-xs">
                        {sourceLabel(activity)}
                      </p>
                      {activity.sourceRef.secondaryId ? (
                        <p className="mt-1 font-mono text-xs text-muted-foreground">
                          secondaryId: {activity.sourceRef.secondaryId}
                        </p>
                      ) : null}
                      <p className="mt-1 font-mono text-xs text-muted-foreground">
                        warehouseId: {activity.warehouseId}
                      </p>
                    </TableCell>
                  ) : null}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}

function ResolvedActivityTable({
  activities,
  showTechnicalActorDetails,
}: {
  activities: DossierActivity[]
  showTechnicalActorDetails: boolean
}) {
  const actorDisplays = useDossierActorDisplays(
    activities.flatMap((activity) =>
      activity.actorRef ? [activity.actorRef.subjectId] : []
    )
  )
  return (
    <ActivityTable
      activities={activities}
      actorDisplays={actorDisplays}
      showTechnicalActorDetails={showTechnicalActorDetails}
    />
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
  if (isLoading) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Загрузка истории...</CardTitle>
          <CardDescription>
            Получаем подтверждённые события из dossier-service.
          </CardDescription>
        </CardHeader>
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
      <CoverageCard visibility={visibility} loaded={activities.length} />
      <ResolvedActivityTable
        activities={activities}
        showTechnicalActorDetails={showTechnicalActorDetails}
      />
      {hasNextPage ? (
        <Button
          className="self-start"
          variant="outline"
          disabled={isFetchingNextPage}
          onClick={onLoadMore}
        >
          {isFetchingNextPage ? "Загрузка..." : "Загрузить ещё"}
        </Button>
      ) : null}
    </div>
  )
}
