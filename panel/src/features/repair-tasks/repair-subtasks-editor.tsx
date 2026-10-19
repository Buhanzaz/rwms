import { useState } from "react"
import {
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import { ArrowDown01Icon, ArrowUp01Icon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { repairSubtaskStatusVariant } from "./repair-task-status-labels"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { taskBoardEntryMediaOwner } from "@/features/media/media-service"
import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"
import {
  REPAIR_TASKS_QUERY_KEY,
  repairTaskDetailQueryKey,
  updateRepairTaskSubtasks,
} from "@/features/repair-tasks/api/repair-tasks-api"
import type {
  RepairTaskDto,
  RepairTaskSubtaskDto,
} from "@/features/repair-tasks/model/repair-task"
import { repairTaskSourceMediaOwner } from "@/features/repair-tasks/repair-task-media-owner"
import { repairSubtaskStatusLabel } from "@/features/repair-tasks/repair-task-status-labels"
import { cn } from "@/lib/utils"

import {
  getKpiPalette,
  kpiSettingsKeys,
  type KpiPalette,
} from "@/features/settings/kpi/api/kpi-settings-api"
import {
  getTaskRegistration,
  getTaskRequirements,
  taskRegistrationQueryKey,
  taskRequirementsQueryKey,
  type TaskRequirementState,
} from "@/features/task-board/api/task-requirements-api"
import {
  requirementStyle,
  requirementStateLabel,
} from "@/features/task-board/requirement-presentation"

type RepairSnapshotLine = RepairTaskSubtaskDto["workLines"][number]

function RepairSnapshotLines({
  title,
  itemLabel,
  emptyLabel,
  lines,
  includeQuantity = false,
  showComments = true,
  states,
  palette,
}: {
  title: string
  itemLabel: string
  emptyLabel: string
  lines: RepairSnapshotLine[]
  includeQuantity?: boolean
  showComments?: boolean
  states: Map<string, TaskRequirementState>
  palette?: KpiPalette | null
}) {
  return (
    <section className="flex min-w-0 flex-col gap-2" aria-label={title}>
      <Badge variant="secondary">{title}</Badge>
      {lines.length > 0 ? (
        <div className="flex min-w-0 flex-col gap-2">
          <div
            className={cn(
              "hidden gap-3 px-3 text-xs font-medium text-muted-foreground sm:grid",
              showComments ? "grid-cols-2" : "grid-cols-1"
            )}
          >
            <span>{itemLabel}</span>
            {showComments ? <span>Комментарий</span> : null}
          </div>
          <div className="flex min-w-0 flex-col gap-2">
            {lines.map((line) => {
              const description = line.description.trim() || itemLabel
              const lineName = includeQuantity
                ? `${description} × ${line.quantity} ${line.unit}`
                : description
              const state = states.get(line.id)
              const statusLabel =
                state === "MISSING"
                  ? includeQuantity
                    ? "Нет материала"
                    : "Не выполнено"
                  : requirementStateLabel(state)

              return (
                <article
                  key={line.id}
                  aria-label={lineName}
                  style={requirementStyle(state, palette)}
                  data-requirement-state={state}
                  className="flex min-w-0 flex-col gap-3 rounded-md border p-3"
                >
                  <dl
                    className={cn(
                      "grid min-w-0 gap-1 sm:gap-3",
                      showComments ? "sm:grid-cols-2" : "sm:grid-cols-1"
                    )}
                  >
                    <dt className="text-xs font-medium text-muted-foreground sm:sr-only">
                      {itemLabel}
                    </dt>
                    <dd className="min-w-0 break-words">{lineName}</dd>
                    {showComments ? (
                      <>
                        <dt className="text-xs font-medium text-muted-foreground sm:sr-only">
                          Комментарий
                        </dt>
                        <dd className="min-w-0 break-words">
                          {line.lineComment.trim() || "—"}
                        </dd>
                      </>
                    ) : null}
                  </dl>
                  {statusLabel ? (
                    <span className="text-xs font-medium">{statusLabel}</span>
                  ) : null}
                </article>
              )
            })}
          </div>
        </div>
      ) : (
        <p className="text-muted-foreground">{emptyLabel}</p>
      )}
    </section>
  )
}

export function RepairSubtasksEditor({
  accessToken,
  task,
  readOnly = false,
}: {
  accessToken: string | null
  task: RepairTaskDto
  readOnly?: boolean
}) {
  const queryClient = useQueryClient()
  const [subtasks, setSubtasks] = useState(() => structuredClone(task.subtasks))
  const [error, setError] = useState<string | null>(null)
  const externalTaskIds = [
    ...new Set(
      task.subtasks.flatMap((subtask) =>
        subtask.externalTaskId ? [subtask.externalTaskId] : []
      )
    ),
  ]
  const registrations = useQueries({
    queries: externalTaskIds.map((externalTaskId) => ({
      queryKey: taskRegistrationQueryKey(task.warehouseId, externalTaskId),
      queryFn: () =>
        getTaskRegistration(accessToken!, task.warehouseId, externalTaskId),
      enabled: Boolean(accessToken),
      staleTime: 60_000,
    })),
  })
  const boardTaskIds = [
    ...new Set(
      registrations.flatMap((query) => (query.data ? [query.data.taskId] : []))
    ),
  ]
  const requirements = useQueries({
    queries: boardTaskIds.map((taskId) => ({
      queryKey: taskRequirementsQueryKey(task.warehouseId, taskId),
      queryFn: () =>
        getTaskRequirements(accessToken!, task.warehouseId, taskId),
      enabled: Boolean(accessToken),
      refetchInterval: 15_000,
    })),
  })
  const paletteQuery = useQuery({
    queryKey: kpiSettingsKeys.palette,
    queryFn: () => getKpiPalette(accessToken!),
    enabled: Boolean(accessToken),
    refetchInterval: 30_000,
  })
  const states = new Map<string, TaskRequirementState>(
    requirements.flatMap(
      (query) =>
        query.data?.items.map((item) => [item.itemId, item.state] as const) ??
        []
    )
  )
  const stateError = [...registrations, ...requirements].some(
    (query) => query.isError
  )
  const statesLoading =
    registrations.some((query) => query.isLoading) ||
    requirements.some((query) => query.isLoading)

  const orderChanged = subtasks.some(
    (subtask, index) => subtask.id !== task.subtasks[index]?.id
  )
  const canReorder =
    !readOnly &&
    task.status === "QUEUED" &&
    task.startedAt === null &&
    task.subtasks.every((subtask) => subtask.status === "WAITING")
  const mutation = useMutation({
    mutationFn: () => {
      if (!canReorder) {
        throw new Error("Для изменения порядка нужен доступ EDIT")
      }

      return updateRepairTaskSubtasks({
        task,
        orderedSubtaskIds: subtasks.map((subtask) => subtask.id),
      })
    },
    onSuccess: (saved) => {
      queryClient.setQueryData(
        repairTaskDetailQueryKey(saved.warehouseId, saved.id),
        saved
      )
      void queryClient.invalidateQueries({ queryKey: REPAIR_TASKS_QUERY_KEY })
      setError(null)
    },
    onError: (unknownError) =>
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Не удалось сохранить подзадания"
      ),
  })

  function moveSubtask(index: number, delta: number) {
    setSubtasks((current) => {
      const targetIndex = index + delta
      if (targetIndex < 0 || targetIndex >= current.length) {
        return current
      }
      const next = [...current]
      const [moved] = next.splice(index, 1)
      next.splice(targetIndex, 0, moved)
      return next
    })
  }

  return (
    <div className="flex flex-col gap-3">
      {stateError ? (
        <p role="alert" className="text-sm text-destructive">
          Не удалось загрузить отметки работ и материалов. Повторите загрузку
          задания.
        </p>
      ) : null}
      {statesLoading ? (
        <p role="status" className="text-sm text-muted-foreground">
          Загрузка отметок работ и материалов…
        </p>
      ) : null}
      {paletteQuery.isError ? (
        <p role="alert" className="text-sm text-destructive">
          Не удалось загрузить настройки цветов. Используются стандартные цвета
          статусов.
        </p>
      ) : null}
      {subtasks.map((subtask, index) => (
        <Card key={subtask.id} size="sm">
          <CardHeader>
            <CardTitle className="flex flex-wrap items-center gap-2">
              <span>Подзадание {index + 1}</span>
              <Badge variant={repairSubtaskStatusVariant[subtask.status]}>
                {repairSubtaskStatusLabel(subtask.status)}
              </Badge>
              <Badge
                variant={
                  (subtask.priority ?? task.priority ?? 3) <= 2
                    ? "default"
                    : "secondary"
                }
              >
                Приоритет {subtask.priority ?? task.priority ?? 3}
              </Badge>
              <Badge variant="outline">
                Очередь {subtask.queuePosition + 1}
              </Badge>
              {subtask.scheduledDate ? (
                <Badge variant="outline">{subtask.scheduledDate}</Badge>
              ) : null}
              {subtask.pinned ? (
                <Badge variant="secondary">Закреплено</Badge>
              ) : null}
            </CardTitle>
            <CardDescription>Состав работ и материалов.</CardDescription>
            {canReorder && subtasks.length > 1 ? (
              <CardAction>
                <div className="flex gap-1">
                  <Button
                    type="button"
                    size="icon-sm"
                    variant="outline"
                    aria-label={`Переместить подзадание ${index + 1} выше`}
                    disabled={mutation.isPending || index === 0}
                    onClick={() => moveSubtask(index, -1)}
                  >
                    <HugeiconsIcon
                      icon={ArrowUp01Icon}
                      data-icon="inline-start"
                    />
                  </Button>
                  <Button
                    type="button"
                    size="icon-sm"
                    variant="outline"
                    aria-label={`Переместить подзадание ${index + 1} ниже`}
                    disabled={
                      mutation.isPending || index === subtasks.length - 1
                    }
                    onClick={() => moveSubtask(index, 1)}
                  >
                    <HugeiconsIcon
                      icon={ArrowDown01Icon}
                      data-icon="inline-start"
                    />
                  </Button>
                </div>
              </CardAction>
            ) : null}
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <div className="grid gap-3 lg:grid-cols-2">
              <RepairSnapshotLines
                title="Работы"
                itemLabel="Работа"
                emptyLabel="Работ нет."
                lines={subtask.workLines}
                states={states}
                palette={paletteQuery.data?.palette}
              />
              <RepairSnapshotLines
                title="Материалы"
                itemLabel="Материал"
                emptyLabel="Материалов нет."
                lines={subtask.materialLines}
                includeQuantity
                showComments={false}
                states={states}
                palette={paletteQuery.data?.palette}
              />
            </div>
            <RepairSourcePhotos
              accessToken={accessToken}
              task={task}
              subtask={subtask}
            />
            <RepairTaskEvidencePhotos
              accessToken={accessToken}
              warehouseId={task.warehouseId}
              evidence={subtask.evidence ?? []}
            />
          </CardContent>
        </Card>
      ))}

      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
      {canReorder && subtasks.length > 1 && orderChanged ? (
        <div className="flex justify-end">
          <Button
            type="button"
            disabled={mutation.isPending}
            onClick={() => mutation.mutate()}
          >
            {mutation.isPending ? "Сохранение..." : "Сохранить порядок"}
          </Button>
        </div>
      ) : null}
    </div>
  )
}

function RepairTaskEvidencePhotos({
  accessToken,
  warehouseId,
  evidence,
}: {
  accessToken: string | null
  warehouseId: string
  evidence: NonNullable<RepairTaskSubtaskDto["evidence"]>
}) {
  if (evidence.length === 0) return null

  const byEntry = new Map<
    string,
    NonNullable<RepairTaskSubtaskDto["evidence"]>
  >()
  evidence.forEach((item) => {
    const current = byEntry.get(item.entryId)
    if (current) current.push(item)
    else byEntry.set(item.entryId, [item])
  })

  return (
    <section
      className="flex min-w-0 flex-col gap-2"
      aria-label="Фото результата задания"
    >
      <div className="grid min-w-0 gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {[...byEntry.entries()].map(([entryId, items], index) => (
          <div
            key={entryId}
            className="flex min-w-0 flex-col gap-2 [&>section]:max-w-none"
          >
            <h4 className="text-sm font-medium">
              Фото результата задания{byEntry.size > 1 ? ` ${index + 1}` : ""}
            </h4>
            <ServiceOwnerPhotos
              accessToken={accessToken}
              owner={taskBoardEntryMediaOwner(entryId, warehouseId)}
              readOnly
              maxItems={100}
              presentation="work-carousel"
              title={
                byEntry.size === 1
                  ? "Фото результата задания"
                  : `Фото результата задания ${index + 1}`
              }
              visibleMediaIds={items.map((item) => item.mediaId)}
              authoritativeReadyReferences={items.map((item) => ({
                mediaId: item.mediaId,
                generation: item.mediaGeneration,
              }))}
            />
          </div>
        ))}
      </div>
    </section>
  )
}

function RepairSourcePhotos({
  accessToken,
  task,
  subtask,
}: {
  accessToken: string | null
  task: RepairTaskDto
  subtask: RepairTaskSubtaskDto
}) {
  const lines = [...subtask.workLines, ...subtask.materialLines].filter(
    (line) => (line.maintenanceMediaReferences?.length ?? 0) > 0
  )
  if (lines.length === 0) return null
  return (
    <section
      aria-label="Фото работ и материалов"
      className="grid min-w-0 gap-3 sm:grid-cols-2 xl:grid-cols-3"
    >
      {lines.map((line) => {
        const references = line.maintenanceMediaReferences ?? []
        return (
          <div
            key={line.id}
            className="flex min-w-0 flex-col gap-2 [&>section]:max-w-none"
          >
            <h4 className="min-h-10 text-sm font-medium">
              {line.lineType === "MATERIAL" ? "Материал" : "Работа"}:{" "}
              {line.description.trim()}
            </h4>
            <ServiceOwnerPhotos
              accessToken={accessToken}
              owner={repairTaskSourceMediaOwner(
                task,
                line,
                subtask.taskBoardEntryId
              )}
              readOnly
              maxItems={100}
              title={`Фото ${line.lineType === "MATERIAL" ? "материала" : "работы"} «${line.description.trim()}»`}
              presentation="work-carousel"
              visibleMediaIds={references.map((reference) => reference.mediaId)}
              authoritativeReadyReferences={references}
            />
          </div>
        )
      })}
    </section>
  )
}
