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
import {
  REPAIR_TASKS_QUERY_KEY,
  repairTaskDetailQueryKey,
  updateRepairTaskSubtasks,
} from "@/features/repair-tasks/api/repair-tasks-api"
import type {
  RepairTaskDto,
  RepairTaskSubtaskDto,
} from "@/features/repair-tasks/model/repair-task"
import { repairSubtaskStatusLabel } from "@/features/repair-tasks/repair-task-status-labels"

type RepairSnapshotLine = RepairTaskSubtaskDto["workLines"][number]

function RepairSnapshotLines({
  title,
  itemLabel,
  emptyLabel,
  lines,
  includeQuantity = false,
}: {
  title: string
  itemLabel: string
  emptyLabel: string
  lines: RepairSnapshotLine[]
  includeQuantity?: boolean
}) {
  return (
    <section className="flex min-w-0 flex-col gap-2" aria-label={title}>
      <Badge variant="secondary">{title}</Badge>
      {lines.length > 0 ? (
        <div className="flex min-w-0 flex-col gap-2">
          <div className="hidden grid-cols-2 gap-3 px-3 text-xs font-medium text-muted-foreground sm:grid">
            <span>{itemLabel}</span>
            <span>Комментарий</span>
          </div>
          <dl className="flex min-w-0 flex-col gap-2">
            {lines.map((line) => {
              const description = line.description.trim() || itemLabel
              const lineName = includeQuantity
                ? `${description} × ${line.quantity} ${line.unit}`
                : description

              return (
                <div
                  key={line.id}
                  className="grid min-w-0 gap-1 rounded-md border p-3 sm:grid-cols-2 sm:gap-3"
                >
                  <dt className="text-xs font-medium text-muted-foreground sm:sr-only">
                    {itemLabel}
                  </dt>
                  <dd className="min-w-0 break-words">{lineName}</dd>
                  <dt className="text-xs font-medium text-muted-foreground sm:sr-only">
                    Комментарий
                  </dt>
                  <dd className="min-w-0 break-words">
                    {line.lineComment.trim() || "—"}
                  </dd>
                </div>
              )
            })}
          </dl>
        </div>
      ) : (
        <p className="text-muted-foreground">{emptyLabel}</p>
      )}
    </section>
  )
}

export function RepairSubtasksEditor({
  task,
  readOnly = false,
}: {
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
      {subtasks.map((subtask, index) => {
        const movementTitle =
          subtask.kind === "MOVE_TO_REPAIR"
            ? "Перемещение на ремонт"
            : subtask.kind === "MOVE_FROM_REPAIR"
              ? "Перемещение с ремонта"
              : null
        return (
          <Card key={subtask.id} size="sm">
            <CardHeader>
              <CardTitle className="flex flex-wrap items-center gap-2">
                <span>{movementTitle ?? `Подзадание ${index + 1}`}</span>
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
              <CardDescription>
                {movementTitle
                  ? subtask.kind === "MOVE_TO_REPAIR"
                    ? "Доставка бытовки к месту ремонта."
                    : "Возврат бытовки после ремонта."
                  : "Состав работ и материалов."}
              </CardDescription>
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
            <CardContent>
              {movementTitle ? (
                <p className="text-muted-foreground">
                  Этап перемещения не содержит строк работ или материалов.
                </p>
              ) : (
                <div className="grid gap-3 lg:grid-cols-2">
                  <RepairSnapshotLines
                    title="Работы"
                    itemLabel="Работа"
                    emptyLabel="Работ нет."
                    lines={subtask.workLines}
                  />
                  <RepairSnapshotLines
                    title="Материалы"
                    itemLabel="Материал"
                    emptyLabel="Материалов нет."
                    lines={subtask.materialLines}
                    includeQuantity
                  />
                </div>
              )}
            </CardContent>
          </Card>
        )
      })}

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
