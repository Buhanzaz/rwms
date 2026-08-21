import { memo, type CSSProperties } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  DragDropVerticalIcon,
  PauseIcon,
  PencilEdit01Icon,
  PinIcon,
  PinOffIcon,
  PlayIcon,
  ViewIcon,
} from "@hugeicons/core-free-icons"

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
import type { KpiPalette } from "@/features/settings/kpi/api/kpi-settings-api"
import {
  paletteColorForRemainingPercent,
  taskTimerAt,
} from "@/features/task-board/domain/task-board-kpi-presentation"
import type { MaintenanceRepair } from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import type {
  TaskBoardEntryDto,
  TaskBoardEntryStatus,
} from "@/features/task-board/model/task-board"
import { cn } from "@/lib/utils"

export type TaskBoardRepairComplexity = Pick<
  MaintenanceRepair["complexity"],
  "type" | "name" | "color"
>

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

function formatRemaining(seconds: number) {
  return seconds < 0
    ? `Просрочено на ${formatElapsed(Math.abs(seconds))}`
    : formatElapsed(seconds)
}

function timerStateLabel(state: ReturnType<typeof taskTimerAt>["timerState"]) {
  if (state === "BREAK") return "Перерыв"
  if (state === "OFF_SHIFT") return "Вне смены"
  if (state === "PAUSED") return "Пауза"
  if (state === "DONE") return "Завершено"
  return null
}

function remainingPercentLabel(value: number) {
  const rounded = Math.round(value)
  return rounded < 0
    ? `Просрочка ${Math.abs(rounded)}%`
    : `Осталось ${rounded}%`
}

function kpiCardAppearance(
  entry: TaskBoardEntryDto,
  now: number,
  palette: KpiPalette | null
) {
  const color = paletteColorForRemainingPercent(
    taskTimerAt(entry, now).remainingPercent,
    palette
  )
  if (!color || !/^#[0-9A-F]{6}$/i.test(color)) {
    return { color: null, style: undefined }
  }

  const red = Number.parseInt(color.slice(1, 3), 16)
  const green = Number.parseInt(color.slice(3, 5), 16)
  const blue = Number.parseInt(color.slice(5, 7), 16)
  return {
    color,
    style: {
      backgroundColor: `rgba(${red}, ${green}, ${blue}, 0.12)`,
      borderColor: color,
      borderLeftWidth: "4px",
    } satisfies CSSProperties,
  }
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

function canEditMaintenanceRepair(entry: TaskBoardEntryDto) {
  return (
    entry.entryType === "REAL" &&
    entry.status === "WAITING" &&
    entry.activeStartedAt === null &&
    entry.source?.type === "MAINTENANCE_REPAIR" &&
    entry.assignments.every((assignment) => assignment.startedAt === null)
  )
}

function taskDescription(entry: TaskBoardEntryDto) {
  const text = entry.taskText?.trim()
  if (text) {
    const repairText = text.replace(/\s*Материалы\s*:\s*[\s\S]*$/iu, "").trim()
    return repairText || null
  }

  return entry.title === "MAINTENANCE_REPAIR" ? null : entry.title
}

function ordinaryRepairComplexity(
  complexity: TaskBoardRepairComplexity | null | undefined
) {
  return complexity?.type === "LIGHT" ||
    complexity?.type === "MEDIUM" ||
    complexity?.type === "COMPLEX"
    ? complexity
    : null
}

function repairComplexityBadgeStyle(complexity: TaskBoardRepairComplexity) {
  const color = complexity.color
  const red = Number.parseInt(color.slice(1, 3), 16)
  const green = Number.parseInt(color.slice(3, 5), 16)
  const blue = Number.parseInt(color.slice(5, 7), 16)
  const foreground =
    (red * 299 + green * 587 + blue * 114) / 1000 >= 150 ? "#111827" : "#FFFFFF"

  return { backgroundColor: color, borderColor: color, color: foreground }
}

function RepairComplexityBadge({
  complexity,
}: {
  complexity: TaskBoardRepairComplexity | null | undefined
}) {
  const ordinaryComplexity = ordinaryRepairComplexity(complexity)
  if (!ordinaryComplexity) return null

  return (
    <Badge
      variant="outline"
      style={repairComplexityBadgeStyle(ordinaryComplexity)}
      data-repair-complexity-type={ordinaryComplexity.type}
    >
      {ordinaryComplexity.name}
    </Badge>
  )
}

const shadowEntryCardClassName =
  "border-dashed bg-muted/70 opacity-65 shadow-lg transition-opacity"

function cardActionVisibility(
  entry: TaskBoardEntryDto,
  mobile: boolean,
  canEdit: boolean
) {
  const showTake =
    canEdit && entry.entryType === "REAL" && entry.status === "WAITING"
  const showEdit = canEdit && canEditMaintenanceRepair(entry)
  const showDetails = entry.source?.type === "MAINTENANCE_REPAIR"
  const showPause =
    entry.entryType === "REAL" && !mobile && entry.status === "IN_PROGRESS"
  const showResume =
    entry.entryType === "REAL" && !mobile && entry.status === "PAUSED"

  return {
    showTake,
    showEdit,
    showDetails,
    showPause,
    showResume,
    showActions: showTake || showEdit || showDetails || showPause || showResume,
  }
}

function TaskBoardCardContent({
  entry,
  now,
  collapsed,
  repairComplexity,
}: {
  entry: TaskBoardEntryDto
  now: number
  collapsed: boolean
  repairComplexity?: TaskBoardRepairComplexity | null
}) {
  const workers = assignedWorkerNames(entry)
  const groups = assignedGroupNames(entry)
  const timer = taskTimerAt(entry, now)
  const stateLabel = timerStateLabel(timer.timerState)

  return (
    <CardContent className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-2">
        <Badge variant={entry.status === "IN_PROGRESS" ? "default" : "outline"}>
          {statusLabels[entry.status]}
        </Badge>
        {entry.entryType === "SHADOW" ? (
          <Badge variant="secondary">После предыдущего этапа</Badge>
        ) : null}
        <Badge variant="outline">
          Этап {entry.routeIndex + 1} из {entry.routeLength}
        </Badge>
        <RepairComplexityBadge
          complexity={
            entry.source?.type === "MAINTENANCE_REPAIR"
              ? repairComplexity
              : null
          }
        />
        <Badge variant={entry.priority <= 2 ? "default" : "secondary"}>
          Приоритет {entry.priority}
        </Badge>
        {timer.remainingPercent !== null ? (
          <Badge variant="outline">
            {remainingPercentLabel(timer.remainingPercent)}
          </Badge>
        ) : null}
      </div>
      {!collapsed ? (
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
            {formatElapsed(timer.countedActiveSeconds)}
          </dd>
          {timer.remainingSeconds !== null ? (
            <>
              <dt>Осталось</dt>
              <dd className="text-foreground">
                {stateLabel ? `${stateLabel} · ` : null}
                {formatRemaining(timer.remainingSeconds)}
              </dd>
            </>
          ) : null}
        </dl>
      ) : null}
    </CardContent>
  )
}

export const TaskBoardCardPreview = memo(function TaskBoardCardPreview({
  entry,
  now,
  mobile,
  canEdit,
  collapsed,
  palette = null,
  repairComplexity = null,
}: {
  entry: TaskBoardEntryDto
  now: number
  mobile: boolean
  canEdit: boolean
  collapsed: boolean
  palette?: KpiPalette | null
  repairComplexity?: TaskBoardRepairComplexity | null
}) {
  const description = taskDescription(entry)
  const unitLabel = entry.unitNumber ?? description ?? "Задание без номера"
  const {
    showTake,
    showEdit,
    showDetails,
    showPause,
    showResume,
    showActions,
  } = cardActionVisibility(entry, mobile, canEdit)
  const appearance = kpiCardAppearance(entry, now, palette)

  return (
    <Card
      size="sm"
      style={appearance.style}
      className={cn(
        "w-full",
        entry.entryType === "SHADOW" && shadowEntryCardClassName
      )}
      data-kpi-color={appearance.color ?? undefined}
      data-timer-state={entry.timerSnapshot?.timerState}
      data-collapsed={collapsed || undefined}
    >
      <CardHeader className="flex flex-col gap-2">
        <div className="flex w-full min-w-0 items-center justify-between gap-2">
          <CardTitle className="min-w-0 flex-1 truncate">{unitLabel}</CardTitle>
          <div
            data-slot="task-board-card-actions"
            className="ml-auto flex shrink-0 items-center justify-end gap-1"
          >
            <Button
              type="button"
              size="sm"
              variant={entry.pinned ? "secondary" : "ghost"}
              disabled
            >
              <HugeiconsIcon
                icon={entry.pinned ? PinOffIcon : PinIcon}
                data-icon="inline-start"
              />
              {entry.pinned ? "Открепить" : "Закрепить"}
            </Button>
            <Button type="button" size="icon-sm" variant="ghost" disabled>
              <HugeiconsIcon icon={DragDropVerticalIcon} />
            </Button>
          </div>
        </div>
        {!collapsed && description ? (
          <CardDescription>{description}</CardDescription>
        ) : null}
      </CardHeader>
      <TaskBoardCardContent
        entry={entry}
        now={now}
        collapsed={collapsed}
        repairComplexity={repairComplexity}
      />
      {showActions ? (
        <CardFooter className="flex flex-col gap-2">
          {showTake ? (
            <Button type="button" size="sm" className="w-full" disabled>
              <HugeiconsIcon icon={PlayIcon} data-icon="inline-start" />
              Взять в работу
            </Button>
          ) : null}
          {showEdit ? (
            <Button
              type="button"
              size="sm"
              variant="outline"
              className="w-full"
              disabled
            >
              <HugeiconsIcon icon={PencilEdit01Icon} data-icon="inline-start" />
              Редактировать
            </Button>
          ) : null}
          {showDetails ? (
            <Button
              type="button"
              size="sm"
              variant="ghost"
              className="w-full"
              disabled
            >
              <HugeiconsIcon icon={ViewIcon} data-icon="inline-start" />
              Детали
            </Button>
          ) : null}
          {showPause ? (
            <Button
              type="button"
              size="sm"
              variant="outline"
              className="w-full"
              disabled
            >
              <HugeiconsIcon icon={PauseIcon} data-icon="inline-start" />
              Пауза
            </Button>
          ) : null}
          {showResume ? (
            <Button type="button" size="sm" className="w-full" disabled>
              <HugeiconsIcon icon={PlayIcon} data-icon="inline-start" />
              Продолжить
            </Button>
          ) : null}
        </CardFooter>
      ) : null}
    </Card>
  )
})

export const TaskBoardCard = memo(function TaskBoardCard({
  entry,
  now,
  mobile,
  canEdit,
  collapsed,
  actionPending,
  onDetails,
  onEdit,
  onTake,
  onPause,
  onResume,
  onPin,
  onToggleCollapsed,
  palette = null,
  repairComplexity = null,
}: {
  entry: TaskBoardEntryDto
  now: number
  mobile: boolean
  canEdit: boolean
  collapsed: boolean
  actionPending: boolean
  onDetails: (entry: TaskBoardEntryDto) => void
  onEdit: (entry: TaskBoardEntryDto) => void
  onTake: (entry: TaskBoardEntryDto) => void
  onPause: (entry: TaskBoardEntryDto) => void
  onResume: (entry: TaskBoardEntryDto) => void
  onPin: (entry: TaskBoardEntryDto, pinned: boolean) => void
  onToggleCollapsed: (entryId: string) => void
  palette?: KpiPalette | null
  repairComplexity?: TaskBoardRepairComplexity | null
}) {
  const description = taskDescription(entry)
  const {
    showTake,
    showEdit,
    showDetails,
    showPause,
    showResume,
    showActions,
  } = cardActionVisibility(entry, mobile, canEdit)
  const appearance = kpiCardAppearance(entry, now, palette)
  const unitLabel = entry.unitNumber ?? "Задание без номера"

  return (
    <Card
      style={appearance.style}
      size="sm"
      className={cn(
        entry.entryType === "SHADOW" && shadowEntryCardClassName,
        !appearance.color &&
          entry.status === "IN_PROGRESS" &&
          "border-primary bg-primary/5",
        !appearance.color && entry.status === "PAUSED" && "bg-muted/60",
        (entry.status === "DONE" || entry.status === "CANCELLED") &&
          "opacity-65"
      )}
      data-collapsed={collapsed || undefined}
      data-kpi-color={appearance.color ?? undefined}
      data-timer-state={entry.timerSnapshot?.timerState}
      aria-label={description ? `${unitLabel}: ${description}` : unitLabel}
    >
      <CardHeader className="flex flex-col gap-2">
        <div className="flex w-full min-w-0 items-center justify-between gap-2">
          <CardTitle className="min-w-0 flex-1 truncate">{unitLabel}</CardTitle>
          <div
            data-slot="task-board-card-actions"
            className="ml-auto flex shrink-0 items-center justify-end gap-1"
          >
            <Button
              type="button"
              size="sm"
              variant={entry.pinned ? "secondary" : "ghost"}
              disabled={actionPending || entry.entryType === "SHADOW"}
              aria-pressed={entry.pinned}
              aria-label={
                entry.pinned
                  ? `Открепить этап ${unitLabel}`
                  : `Закрепить этап ${unitLabel}`
              }
              onClick={() => onPin(entry, !entry.pinned)}
            >
              <HugeiconsIcon
                icon={entry.pinned ? PinOffIcon : PinIcon}
                data-icon="inline-start"
              />
              {entry.pinned ? "Открепить" : "Закрепить"}
            </Button>
            <Button
              type="button"
              size="icon-sm"
              variant="ghost"
              className="touch-none"
              aria-label={`${collapsed ? "Развернуть" : "Свернуть"} этап ${unitLabel}`}
              aria-expanded={!collapsed}
              onClick={() => onToggleCollapsed(entry.id)}
            >
              <HugeiconsIcon icon={DragDropVerticalIcon} />
            </Button>
          </div>
        </div>
        {!collapsed && description ? (
          <CardDescription>{description}</CardDescription>
        ) : null}
      </CardHeader>
      <TaskBoardCardContent
        entry={entry}
        now={now}
        collapsed={collapsed}
        repairComplexity={repairComplexity}
      />
      {showActions ? (
        <CardFooter className="flex flex-col gap-2">
          {showTake ? (
            <Button
              type="button"
              size="sm"
              className="w-full"
              disabled={actionPending}
              onClick={() => onTake(entry)}
            >
              <HugeiconsIcon icon={PlayIcon} data-icon="inline-start" />
              Взять в работу
            </Button>
          ) : null}
          {showEdit ? (
            <Button
              type="button"
              size="sm"
              variant="outline"
              className="w-full"
              disabled={actionPending}
              onClick={() => onEdit(entry)}
            >
              <HugeiconsIcon icon={PencilEdit01Icon} data-icon="inline-start" />
              Редактировать
            </Button>
          ) : null}
          {showDetails ? (
            <Button
              type="button"
              size="sm"
              variant="ghost"
              className="w-full"
              onClick={() => onDetails(entry)}
            >
              <HugeiconsIcon icon={ViewIcon} data-icon="inline-start" />
              Детали
            </Button>
          ) : null}
          {showPause ? (
            <Button
              type="button"
              size="sm"
              variant="outline"
              className="w-full"
              disabled={actionPending}
              onClick={() => onPause(entry)}
            >
              <HugeiconsIcon icon={PauseIcon} data-icon="inline-start" />
              Пауза
            </Button>
          ) : null}
          {showResume ? (
            <Button
              type="button"
              size="sm"
              className="w-full"
              disabled={actionPending}
              onClick={() => onResume(entry)}
            >
              <HugeiconsIcon icon={PlayIcon} data-icon="inline-start" />
              Продолжить
            </Button>
          ) : null}
        </CardFooter>
      ) : null}
    </Card>
  )
})
