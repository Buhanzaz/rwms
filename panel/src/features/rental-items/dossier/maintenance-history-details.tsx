import { useQuery } from "@tanstack/react-query"
import { Link } from "react-router-dom"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { useAuth } from "@/features/auth/use-auth"
import {
  getMaintenanceEstimate,
  type MaintenanceEstimate,
} from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import { REPAIR_ESTIMATES_QUERY_KEY } from "@/features/repair-estimates/api/repair-estimates-api"
import { formatMoneyDecimal } from "@/features/repair-estimates/domain/repair-estimate-domain"
import {
  getRepairTask,
  REPAIR_TASKS_QUERY_KEY,
} from "@/features/repair-tasks/api/repair-tasks-api"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import { repairSubtaskStatusLabel } from "@/features/repair-tasks/repair-task-status-labels"
import { RepairTaskStatusBadge } from "@/features/repair-tasks/repair-task-status-badge"
import { formatDossierActorDisplay } from "./actor/actor-display"
import { useDossierActorDisplays } from "./actor/use-dossier-actor-displays"

function instant(value: string | null | undefined) {
  return value
    ? new Intl.DateTimeFormat("ru-RU", {
        dateStyle: "medium",
        timeStyle: "short",
      }).format(new Date(value))
    : "Не зафиксировано"
}

function date(value: string | null) {
  return value
    ? new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium" }).format(
        new Date(`${value}T12:00:00`)
      )
    : "Не указана"
}

function EstimateDetails({ estimate }: { estimate: MaintenanceEstimate }) {
  const actors = useDossierActorDisplays(
    estimate.actor.actorType === "USER" ? [estimate.actor.actorId] : []
  )
  const author = actors.get(estimate.actor.actorId)
  const revision = estimate.revisions.find(
    (item) => item.revision === estimate.currentRevision
  )!
  return (
    <section
      aria-label="Подробности сметы"
      className="flex min-w-0 flex-col gap-3 text-sm"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="font-medium">Смета · редакция {revision.revision}</h4>
        <Badge variant="outline">
          {estimate.lifecycle === "COMPLETED" ? "Завершена" : "Черновик"}
        </Badge>
      </div>
      <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <div>
          <dt className="text-muted-foreground">От кого / источник</dt>
          <dd>{revision.sourceParty || "Не указан"}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Дата поступления в смете</dt>
          <dd>{date(revision.dispatchDate)}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Автор сметы</dt>
          <dd>
            {author
              ? formatDossierActorDisplay(author)
              : estimate.actor.actorType === "SERVICE"
                ? "Сервис"
                : "Имя автора недоступно"}
          </dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Создана / завершена</dt>
          <dd>
            {instant(estimate.createdAt)} / {instant(estimate.completedAt)}
          </dd>
        </div>
      </dl>
      <ul aria-label="Работы и материалы сметы" className="flex flex-col gap-2">
        {revision.lines.map((line) => (
          <li key={line.id} className="flex flex-col gap-1">
            <p>
              <Badge variant="secondary">
                {line.lineType === "WORK" ? "Работа" : "Материал"}
              </Badge>{" "}
              {line.description} · {line.quantity} {line.unit} ·{" "}
              {formatMoneyDecimal(line.lineTotal)} ₽
            </p>
            {line.comment ? (
              <p className="text-muted-foreground">{line.comment}</p>
            ) : null}
          </li>
        ))}
      </ul>
      <p className="font-medium">
        Итого по смете: {formatMoneyDecimal(revision.total)} ₽
      </p>
      {estimate.revisions.length > 1 ? (
        <section aria-label="Изменения сметы" className="flex flex-col gap-2">
          <h4 className="font-medium">Изменения сметы</h4>
          {[...estimate.revisions]
            .sort((a, b) => a.revision - b.revision)
            .map((item) => (
              <p key={item.revision}>
                Редакция {item.revision} · {instant(item.recordedAt)} ·{" "}
                {item.reason || "Причина не зафиксирована"}
              </p>
            ))}
        </section>
      ) : null}
    </section>
  )
}

const acceptanceLabels: Record<RepairTaskDto["acceptanceStatus"], string> = {
  NOT_READY: "Не готов к приёмке",
  PENDING: "Ожидает приёмки",
  IN_REWORK: "На доработке",
  ACCEPTED: "Принят",
  WRITTEN_OFF: "Списан",
}

function RepairDetails({ task }: { task: RepairTaskDto }) {
  const actors = useDossierActorDisplays([
    task.actorId,
    ...(task.decisionActorId ? [task.decisionActorId] : []),
  ])
  const actor = (id: string) => {
    const display = actors.get(id)
    return display
      ? formatDossierActorDisplay(display)
      : "Имя участника недоступно"
  }
  return (
    <section
      aria-label="Подробности ремонта"
      className="flex min-w-0 flex-col gap-3 text-sm"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="font-medium">
          {task.kind === "REWORK" ? "Доработка" : "Ремонт"}
        </h4>
        <RepairTaskStatusBadge
          status={task.status}
          awaitingMovement={task.awaitingMovement}
        />
        <Badge variant="outline">
          {acceptanceLabels[task.acceptanceStatus]}
        </Badge>
      </div>
      <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <div>
          <dt className="text-muted-foreground">Источник / автор</dt>
          <dd>
            {task.sourceParty || "Источник не указан"} · {actor(task.actorId)}
          </dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Начало / завершение работ</dt>
          <dd>
            {instant(task.startedAt)} / {instant(task.completedAt)}
          </dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Готов к приёмке</dt>
          <dd>{instant(task.readyAt)}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Участник решения приёмки</dt>
          <dd>
            {task.decisionActorId
              ? actor(task.decisionActorId)
              : "Не зафиксирован"}
          </dd>
        </div>
      </dl>
      <p className="text-muted-foreground">
        Текущее состояние задания. Даты решений и участники событий приведены
        ниже в ходе операции.
      </p>
      {task.taskBoardAvailable === false ? (
        <p role="status">
          Доска заданий недоступна: сведения об исполнителях и времени могут
          быть неполными.
        </p>
      ) : null}
      <ol aria-label="Этапы ремонта" className="flex flex-col gap-3">
        {task.subtasks.map((stage, index) => (
          <li key={stage.id}>
            <Card size="sm">
              <CardHeader>
                <CardTitle className="flex flex-wrap items-center gap-2">
                  {index + 1}.{" "}
                  {stage.taskTitle || stage.queueName || "Этап ремонта"}
                  <Badge variant="outline">
                    {repairSubtaskStatusLabel(stage.status)}
                  </Badge>
                </CardTitle>
              </CardHeader>
              <CardContent className="flex flex-col gap-2">
                {stage.groupComment ? <p>{stage.groupComment}</p> : null}
                {[...stage.workLines, ...stage.materialLines].map((line) => (
                  <div key={line.id} className="flex flex-col gap-1">
                    <p>
                      {line.lineType === "WORK" ? "Работа" : "Материал"}:{" "}
                      {line.description} · {line.quantity} {line.unit}
                      {line.rework
                        ? ` · ${line.rework.disposition === "REPEAT" ? "Переделать" : "Добавлено в доработке"}`
                        : ""}
                    </p>
                    {line.lineComment ? (
                      <p className="text-muted-foreground">
                        {line.lineComment}
                      </p>
                    ) : null}
                  </div>
                ))}
                <p className="text-muted-foreground">
                  Начало: {instant(stage.startedAt)} · Завершение:{" "}
                  {instant(stage.completedAt)}
                </p>
                {stage.workerGroup ? (
                  <p>Бригада: {stage.workerGroup.name}</p>
                ) : null}
                {stage.assignments.length === 0 ? (
                  <p className="text-muted-foreground">
                    Назначения исполнителей не зафиксированы.
                  </p>
                ) : (
                  <ul
                    aria-label="Исполнители этапа"
                    className="flex flex-col gap-1"
                  >
                    {stage.assignments.map((assignment) => (
                      <li key={assignment.id}>
                        {assignment.worker?.name ||
                          "Имя исполнителя недоступно"}{" "}
                        · Назначен: {instant(assignment.assignedAt)} · Начал:{" "}
                        {instant(assignment.startedAt)} · Завершил:{" "}
                        {instant(assignment.finishedAt)}
                      </li>
                    ))}
                  </ul>
                )}
              </CardContent>
            </Card>
          </li>
        ))}
      </ol>
      {task.sourceRepairTaskId ? (
        <Button variant="outline" size="sm" className="self-start" asChild>
          <Link
            to={`/repairs?repairId=${encodeURIComponent(task.sourceRepairTaskId)}`}
          >
            Исходный ремонт
          </Link>
        </Button>
      ) : null}
    </section>
  )
}

/** Loads owner details only while its canonical dossier operation is expanded. */
export function MaintenanceHistoryDetails({
  kind,
  sourceId,
  cabinId,
  warehouseId,
}: {
  kind: "ESTIMATE" | "REPAIR"
  sourceId: string
  cabinId: string
  warehouseId: string
}) {
  const { accessToken, currentUser } = useAuth()
  const query = useQuery({
    queryKey: [
      ...(kind === "ESTIMATE"
        ? REPAIR_ESTIMATES_QUERY_KEY
        : REPAIR_TASKS_QUERY_KEY),
      "dossier-detail",
      currentUser?.id,
      warehouseId,
      cabinId,
      sourceId,
    ],
    queryFn: async () => {
      if (kind === "ESTIMATE") {
        const estimate = await getMaintenanceEstimate(
          accessToken!,
          warehouseId,
          sourceId
        )
        if (
          estimate.id !== sourceId ||
          estimate.rentalItemId !== cabinId ||
          estimate.warehouseId !== warehouseId ||
          !estimate.revisions.some(
            (revision) => revision.revision === estimate.currentRevision
          )
        )
          throw new Error(
            "Сервис вернул смету, не соответствующую операции бытовки."
          )
        return { kind: "ESTIMATE" as const, estimate }
      }
      const task = await getRepairTask(sourceId, warehouseId)
      if (!task)
        throw new Error("Ремонт недоступен. События истории сохранены ниже.")
      if (
        task.id !== sourceId ||
        task.rentalItemId !== cabinId ||
        task.warehouseId !== warehouseId
      )
        throw new Error(
          "Сервис вернул ремонт, не соответствующий операции бытовки."
        )
      return { kind: "REPAIR" as const, task }
    },
    enabled: Boolean(accessToken),
    staleTime: 30_000,
    retry: false,
  })
  if (!accessToken)
    return <p role="alert">Для просмотра подробностей требуется авторизация.</p>
  if (query.error)
    return (
      <div className="flex flex-col gap-2">
        <p role="alert">{query.error.message}</p>
        <Button
          variant="outline"
          size="sm"
          className="self-start"
          onClick={() => void query.refetch()}
        >
          Повторить загрузку подробностей
        </Button>
      </div>
    )
  if (!query.data)
    return (
      <Skeleton
        className="h-24 w-full"
        aria-label="Загрузка подробностей операции"
      />
    )
  return query.data.kind === "ESTIMATE" ? (
    <EstimateDetails estimate={query.data.estimate} />
  ) : (
    <RepairDetails task={query.data.task} />
  )
}
