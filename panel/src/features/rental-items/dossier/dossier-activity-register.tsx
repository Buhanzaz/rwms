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

function formatInstant(value: string) {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return value

  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date)
}

function actorLabel(activity: DossierActivity) {
  const actor = activity.actorRef
  if (!actor) return "Не указан источником"

  return `${actor.principalType} · ${actor.subjectId}`
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
        : "Сервис досье временно недоступен.",
  }
}

export function DossierActivityFiltersPanel({
  value,
  onApply,
}: {
  value: DossierActivityFilters
  onApply: (value: DossierActivityFilters) => void
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
          Фильтры выполняются dossier-service. Время задаётся в RFC 3339, actor
          — только непрозрачным UUID.
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
                        {code}
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
            ? "Покрытие частичное: часть producer facts отсутствует или недоступна текущему пользователю. Скрытые строки и их количество сервис не раскрывает."
            : "Dossier-service сообщает полное покрытие для видимой проекции и текущих фильтров."}
        </CardDescription>
      </CardHeader>
    </Card>
  )
}

function ActivityTable({ activities }: { activities: DossierActivity[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Подтверждённые события</CardTitle>
        <CardDescription>
          Actor и source reference показаны без восстановления имён или
          browser-ссылок.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Время</TableHead>
              <TableHead>Событие</TableHead>
              <TableHead>Источник</TableHead>
              <TableHead>Actor</TableHead>
              <TableHead>Media</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {activities.map((activity) => (
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
                  <Badge variant="outline">{activity.activityCode}</Badge>
                  <p className="mt-2 font-mono text-xs text-muted-foreground">
                    {activity.activityId}
                  </p>
                </TableCell>
                <TableCell className="min-w-80 align-top">
                  <p className="font-mono text-xs">{sourceLabel(activity)}</p>
                  {activity.sourceRef.secondaryId ? (
                    <p className="mt-1 font-mono text-xs text-muted-foreground">
                      secondaryId: {activity.sourceRef.secondaryId}
                    </p>
                  ) : null}
                  <p className="mt-1 font-mono text-xs text-muted-foreground">
                    warehouseId: {activity.warehouseId}
                  </p>
                </TableCell>
                <TableCell className="min-w-72 align-top">
                  <p className="font-mono text-xs">{actorLabel(activity)}</p>
                  {activity.actorRef?.profileRevision ? (
                    <p className="mt-1 font-mono text-xs text-muted-foreground">
                      profileRevision: {activity.actorRef.profileRevision}
                    </p>
                  ) : null}
                </TableCell>
                <TableCell className="min-w-72 align-top">
                  {activity.media.length === 0 ? (
                    <span className="text-muted-foreground">Нет</span>
                  ) : (
                    <div className="flex flex-col gap-2">
                      {activity.media.map((media) => (
                        <div key={media.mediaId}>
                          <Badge variant="secondary">{media.state}</Badge>
                          <p className="mt-1 font-mono text-xs">
                            {media.mediaId} · generation {media.generation}
                          </p>
                          <p className="font-mono text-xs text-muted-foreground">
                            findingId: {media.findingId}
                          </p>
                        </div>
                      ))}
                    </div>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
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
}: {
  pages: CabinDossierPage[] | undefined
  error: unknown
  isLoading: boolean
  hasNextPage: boolean
  isFetchingNextPage: boolean
  onLoadMore: () => void
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
      {activities.length === 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>Событий нет</CardTitle>
            <CardDescription>
              По текущим фильтрам dossier-service не вернул видимых событий.
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <ActivityTable activities={activities} />
      )}
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
