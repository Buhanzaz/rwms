import { memo, type CSSProperties } from "react"
import { useSortable } from "@dnd-kit/sortable"
import { CSS } from "@dnd-kit/utilities"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  DragDropVerticalIcon,
  PauseIcon,
  PlayIcon,
  ViewIcon,
} from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { cn } from "@/lib/utils"
import { repairSubtaskStatusLabel } from "@/features/repair-tasks/repair-task-status-labels"
import { taskBoardEntryTitle } from "@/features/task-board/domain/task-board-domain"
import type { TaskBoardEntryDto } from "@/features/task-board/model/task-board"

function formatElapsed(seconds: number) {
  const hours = Math.floor(seconds / 3_600)
  const minutes = Math.floor((seconds % 3_600) / 60)
  const rest = seconds % 60
  return hours > 0
    ? `${hours} ч ${minutes.toString().padStart(2, "0")} мин`
    : `${minutes.toString().padStart(2, "0")}:${rest
        .toString()
        .padStart(2, "0")}`
}

function elapsedSeconds(entry: TaskBoardEntryDto, now: number) {
  if (entry.runtimeTask) {
    const stored = entry.runtimeTask.elapsedSeconds
    if (!entry.runtimeTask.activeSince) return stored
    const started = Date.parse(entry.runtimeTask.activeSince)
    return (
      stored +
      (Number.isFinite(started)
        ? Math.max(0, Math.floor((now - started) / 1_000))
        : 0)
    )
  }
  const stored = entry.subtask.activeWorkSeconds
  if (!entry.subtask.activeStartedAt) return stored
  const started = Date.parse(entry.subtask.activeStartedAt)
  return (
    stored +
    (Number.isFinite(started)
      ? Math.max(0, Math.floor((now - started) / 1_000))
      : 0)
  )
}

function entryTitle(entry: TaskBoardEntryDto) {
  if (!entry.runtimeTask) return taskBoardEntryTitle(entry.subtask)
  return (
    entry.runtimeTask.route[entry.routeIndex]?.title ?? entry.runtimeTask.title
  )
}

function entryStatusLabel(entry: TaskBoardEntryDto) {
  if (entry.runtimeTask?.status === "RETURNING") return "Возвращается"
  return repairSubtaskStatusLabel(entry.subtask.status)
}

function returnCountdown(entry: TaskBoardEntryDto, now: number) {
  if (
    entry.runtimeTask?.status !== "RETURNING" ||
    !entry.interruption?.returnDeadline
  ) {
    return null
  }
  const deadline = Date.parse(entry.interruption.returnDeadline)
  if (!Number.isFinite(deadline)) return null
  return formatElapsed(Math.max(0, Math.ceil((deadline - now) / 1_000)))
}

function assignedWorkerNames(entry: TaskBoardEntryDto) {
  return Array.from(
    new Set(
      entry.subtask.assignments
        .map((assignment) => assignment.worker?.name.trim())
        .filter((name): name is string => Boolean(name))
    )
  )
}

const shadowEntryCardClassName =
  "border-dashed bg-muted/70 opacity-65 shadow-lg transition-opacity"
const mobileEntryCardHeightClassName = "min-h-80"

export const TaskBoardCardPreview = memo(function TaskBoardCardPreview({
  entry,
  now,
}: {
  entry: TaskBoardEntryDto
  now: number
}) {
  return (
    <Card
      size="sm"
      className={cn(
        "w-80",
        entry.entryType === "SHADOW" && shadowEntryCardClassName
      )}
    >
      <CardHeader>
        <CardTitle>{entry.task.cabinNumber}</CardTitle>
        <CardDescription>{entryTitle(entry)}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap gap-2">
        <Badge variant={entry.entryType === "REAL" ? "secondary" : "outline"}>
          {entry.entryType === "REAL" ? "Текущий этап" : "Будущий этап"}
        </Badge>
        <Badge variant="outline">
          {formatElapsed(elapsedSeconds(entry, now))}
        </Badge>
      </CardContent>
    </Card>
  )
})

export const TaskBoardCard = memo(function TaskBoardCard({
  entry,
  now,
  mobile,
  dragDisabled,
  actionPending,
  onDetails,
  onPause,
  onResume,
  onConfirmReturn,
}: {
  entry: TaskBoardEntryDto
  now: number
  mobile: boolean
  dragDisabled: boolean
  actionPending: boolean
  onDetails: (entry: TaskBoardEntryDto) => void
  onPause: (entry: TaskBoardEntryDto) => void
  onResume: (entry: TaskBoardEntryDto) => void
  onConfirmReturn: (entry: TaskBoardEntryDto) => void
}) {
  const workers = assignedWorkerNames(entry)
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({
    id: entry.id,
    disabled: dragDisabled,
    data: { type: "entry", queueKey: entry.queueKey, entryId: entry.id },
  })
  const style: CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.35 : 1,
  }
  return (
    <Card
      ref={setNodeRef}
      style={style}
      size="sm"
      className={cn(
        mobile && mobileEntryCardHeightClassName,
        entry.entryType === "SHADOW" && shadowEntryCardClassName
      )}
      aria-label={`Бытовка ${entry.task.cabinNumber}: ${entryTitle(entry)}`}
    >
      <CardHeader>
        <CardTitle className="flex min-w-0 flex-wrap items-center gap-2">
          <span className="truncate">{entry.task.cabinNumber}</span>
          <Badge variant={entry.entryType === "REAL" ? "secondary" : "outline"}>
            {entry.entryType === "REAL" ? "Текущий этап" : "Будущий этап"}
          </Badge>
          {entry.runtimeTask?.demo ? (
            <Badge variant="outline">Демо</Badge>
          ) : null}
        </CardTitle>
        <CardDescription className="line-clamp-2">
          {entry.task.reason || "Причина не указана"}
        </CardDescription>
        <CardAction>
          <Button
            type="button"
            size="icon-sm"
            variant="ghost"
            className="touch-none"
            disabled={dragDisabled}
            aria-label={
              dragDisabled
                ? "Перемещение карточки сейчас недоступно"
                : `Переместить этап бытовки ${entry.task.cabinNumber}`
            }
            {...attributes}
            {...listeners}
          >
            <HugeiconsIcon icon={DragDropVerticalIcon} />
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <p className="font-medium">{entryTitle(entry)}</p>
        <div className="flex flex-wrap gap-2">
          <Badge variant="outline">{entryStatusLabel(entry)}</Badge>
          <Badge variant="outline">
            Этап {entry.routeIndex + 1} из {entry.routeLength}
          </Badge>
          {entry.runtimeTask?.pauseReasons.map((reason) => (
            <Badge key={reason.id} variant="outline">
              {reason.label}
            </Badge>
          ))}
        </div>
        <dl className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-1 text-muted-foreground">
          <dt>Группа</dt>
          <dd className="min-w-0 truncate text-foreground">
            {entry.subtask.workerGroup?.name ?? "Не назначена"}
          </dd>
          <dt>Работники</dt>
          <dd className="min-w-0 truncate text-foreground">
            {workers.join(", ") || entry.subtask.assigneeName || "Не назначены"}
          </dd>
          <dt>Время</dt>
          <dd className="text-foreground">
            {formatElapsed(elapsedSeconds(entry, now))}
          </dd>
          {returnCountdown(entry, now) ? (
            <>
              <dt>Возврат</dt>
              <dd className="text-foreground">
                через {returnCountdown(entry, now)}
              </dd>
            </>
          ) : null}
        </dl>
      </CardContent>
      <CardFooter className="flex flex-wrap justify-end gap-1">
        {entry.detailsHref ? (
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => onDetails(entry)}
          >
            <HugeiconsIcon icon={ViewIcon} data-icon="inline-start" />
            Детали
          </Button>
        ) : null}
        {entry.runtimeTask?.status === "RETURNING" && entry.interruption ? (
          <Button
            type="button"
            size="sm"
            disabled={actionPending}
            onClick={() => onConfirmReturn(entry)}
          >
            Бригада вернулась
          </Button>
        ) : null}
        {entry.subtask.status === "IN_PROGRESS" ? (
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={actionPending}
            onClick={() => onPause(entry)}
          >
            <HugeiconsIcon icon={PauseIcon} data-icon="inline-start" />
            Пауза
          </Button>
        ) : null}
        {entry.subtask.status === "PAUSED" &&
        (!entry.runtimeTask ||
          entry.runtimeTask.pauseReasons.some(
            (reason) => reason.type === "MANUAL"
          )) ? (
          <Button
            type="button"
            size="sm"
            disabled={actionPending}
            onClick={() => onResume(entry)}
          >
            <HugeiconsIcon icon={PlayIcon} data-icon="inline-start" />
            Продолжить
          </Button>
        ) : null}
      </CardFooter>
    </Card>
  )
})
