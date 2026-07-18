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
import type {
  TaskBoardEntryDto,
  TaskBoardEntryStatus,
} from "@/features/task-board/model/task-board"
import { cn } from "@/lib/utils"

const statusLabels: Record<TaskBoardEntryStatus, string> = {
  WAITING: "Ожидает",
  IN_PROGRESS: "В работе",
  PAUSED: "На паузе",
  DONE: "Завершено",
  CANCELLED: "Отменено",
}

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
  if (!entry.activeStartedAt || entry.status !== "IN_PROGRESS") {
    return entry.activeWorkSeconds
  }
  const started = Date.parse(entry.activeStartedAt)
  return (
    entry.activeWorkSeconds +
    (Number.isFinite(started)
      ? Math.max(0, Math.floor((now - started) / 1_000))
      : 0)
  )
}

function assignedWorkerNames(entry: TaskBoardEntryDto) {
  return Array.from(
    new Set(
      entry.assignments
        .map((assignment) => assignment.workerName?.trim())
        .filter((name): name is string => Boolean(name))
    )
  )
}

function assignedGroupNames(entry: TaskBoardEntryDto) {
  return Array.from(
    new Set(
      entry.assignments
        .map((assignment) => assignment.workerGroupName?.trim())
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
        <CardTitle>{entry.unitNumber ?? entry.title}</CardTitle>
        <CardDescription>{entry.title}</CardDescription>
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
}: {
  entry: TaskBoardEntryDto
  now: number
  mobile: boolean
  dragDisabled: boolean
  actionPending: boolean
  onDetails: (entry: TaskBoardEntryDto) => void
  onPause: (entry: TaskBoardEntryDto) => void
  onResume: (entry: TaskBoardEntryDto) => void
}) {
  const workers = assignedWorkerNames(entry)
  const groups = assignedGroupNames(entry)
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
  const unitLabel = entry.unitNumber ?? `Задание ${entry.taskId}`

  return (
    <Card
      ref={setNodeRef}
      style={style}
      size="sm"
      className={cn(
        mobile && mobileEntryCardHeightClassName,
        entry.entryType === "SHADOW" && shadowEntryCardClassName
      )}
      aria-label={`${unitLabel}: ${entry.title}`}
    >
      <CardHeader>
        <CardTitle className="flex min-w-0 flex-wrap items-center gap-2">
          <span className="truncate">{unitLabel}</span>
          <Badge variant={entry.entryType === "REAL" ? "secondary" : "outline"}>
            {entry.entryType === "REAL" ? "Текущий этап" : "Будущий этап"}
          </Badge>
        </CardTitle>
        <CardDescription className="line-clamp-2">
          {entry.taskText || entry.title}
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
                : `Переместить этап ${unitLabel}`
            }
            {...attributes}
            {...listeners}
          >
            <HugeiconsIcon icon={DragDropVerticalIcon} />
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <p className="font-medium">{entry.title}</p>
        <div className="flex flex-wrap gap-2">
          <Badge variant="outline">{statusLabels[entry.status]}</Badge>
          <Badge variant="outline">
            Этап {entry.routeIndex + 1} из {entry.routeLength}
          </Badge>
        </div>
        <dl className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-1 text-muted-foreground">
          <dt>Группа</dt>
          <dd className="min-w-0 truncate text-foreground">
            {groups.join(", ") || "Не назначена"}
          </dd>
          <dt>Работники</dt>
          <dd className="min-w-0 truncate text-foreground">
            {workers.join(", ") || "Не назначены"}
          </dd>
          <dt>Время</dt>
          <dd className="text-foreground">
            {formatElapsed(elapsedSeconds(entry, now))}
          </dd>
          {entry.externalTaskId ? (
            <>
              <dt>Источник</dt>
              <dd className="min-w-0 truncate font-mono text-xs text-foreground">
                {entry.externalTaskId}
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
            Источник
          </Button>
        ) : null}
        {entry.status === "IN_PROGRESS" ? (
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
        {entry.status === "PAUSED" ? (
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
