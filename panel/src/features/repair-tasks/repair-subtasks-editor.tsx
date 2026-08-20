import { useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import { ArrowDown01Icon, ArrowUp01Icon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
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

type RepairSnapshotLine = RepairTaskSubtaskDto["workLines"][number]

function RepairSnapshotLines({
  title,
  itemLabel,
  emptyLabel,
  lines,
  includeQuantity = false,
  showComments = true,
  accessToken = null,
  task,
  subtask,
}: {
  title: string
  itemLabel: string
  emptyLabel: string
  lines: RepairSnapshotLine[]
  includeQuantity?: boolean
  showComments?: boolean
  accessToken?: string | null
  task?: RepairTaskDto
  subtask?: RepairTaskSubtaskDto
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
              const mediaReferences = line.maintenanceMediaReferences ?? []

              return (
                <article
                  key={line.id}
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
                  {task && subtask && mediaReferences.length > 0 ? (
                    <ServiceOwnerPhotos
                      accessToken={accessToken}
                      owner={repairTaskSourceMediaOwner(task, line)}
                      readOnly
                      maxItems={100}
                      title={`Фото работы «${description}»`}
                      presentation="work-carousel"
                      visibleMediaIds={mediaReferences.map(
                        (reference) => reference.mediaId
                      )}
                      authoritativeReadyReferences={mediaReferences}
                    />
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
      {subtasks.map((subtask, index) => (
        <Card key={subtask.id} size="sm">
          <CardHeader>
            <CardTitle className="flex flex-wrap items-center gap-2">
              <span>Подзадание {index + 1}</span>
              <Badge variant="outline">
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
                accessToken={accessToken}
                task={task}
                subtask={subtask}
              />
              <RepairSnapshotLines
                title="Материалы"
                itemLabel="Материал"
                emptyLabel="Материалов нет."
                lines={subtask.materialLines}
                includeQuantity
                showComments={false}
              />
            </div>
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
      {[...byEntry.entries()].map(([entryId, items], index) => (
        <ServiceOwnerPhotos
          key={entryId}
          accessToken={accessToken}
          owner={taskBoardEntryMediaOwner(entryId, warehouseId)}
          readOnly
          maxItems={100}
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
      ))}
    </section>
  )
}
