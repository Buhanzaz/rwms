import { memo, type CSSProperties } from "react"
import { useSortable } from "@dnd-kit/sortable"
import { CSS } from "@dnd-kit/utilities"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowDown01Icon,
  ArrowUp01Icon,
  Cancel01Icon,
  DragDropVerticalIcon,
  PauseIcon,
  PencilEdit01Icon,
  PinIcon,
  PinOffIcon,
  PlayIcon,
  Route01Icon,
  ViewIcon,
} from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Field, FieldLabel } from "@/components/ui/field"
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
  if (entry.entryType === "SHADOW" || entry.suspended) {
    return { color: null, style: undefined }
  }

  const color = paletteColorForRemainingPercent(
    taskTimerAt(entry, now).remainingPercent,
    palette
  )
  if (!color || !/^#[0-9A-F]{6}$/i.test(color)) {
    return { color: null, style: undefined }
  }

  return {
    color,
    style: {
      backgroundColor: `color-mix(in srgb, ${color} 18%, var(--card))`,
      borderColor: color,
      borderLeftWidth: "4px",
    } satisfies CSSProperties,
  }
}

function assignedWorkerNames(entry: TaskBoardEntryDto) {
  return Array.from(
    new Set(
      entry.assignments
        .filter(
          (assignment) =>
            assignment.status === "ACTIVE" || assignment.status === "PAUSED"
        )
        .map((assignment) => assignment.workerName?.trim())
        .filter((name): name is string => Boolean(name))
    )
  )
}

function assignedGroupNames(entry: TaskBoardEntryDto) {
  return Array.from(
    new Set(
      entry.assignments
        .filter(
          (assignment) =>
            assignment.status === "ACTIVE" || assignment.status === "PAUSED"
        )
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

function taskDescriptionLines(description: string | null) {
  if (!description) return []

  return description
    .split(/\s+\/\s*|\s*\/\s+/u)
    .map((line) => line.trim())
    .filter(Boolean)
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
  "border-dashed border-neutral-300 bg-neutral-100 transition-opacity dark:border-neutral-600 dark:bg-neutral-800"

function cardActionVisibility(
  entry: TaskBoardEntryDto,
  mobile: boolean,
  canEdit: boolean
) {
  const showTake =
    canEdit &&
    !entry.suspended &&
    entry.entryType === "REAL" &&
    entry.status === "WAITING"
  const showEdit =
    !entry.suspended && canEdit && canEditMaintenanceRepair(entry)
  const showDetails = entry.source?.type === "MAINTENANCE_REPAIR"
  const showPause =
    entry.entryType === "REAL" &&
    !entry.suspended &&
    !mobile &&
    entry.status === "IN_PROGRESS"
  const showResume =
    entry.entryType === "REAL" &&
    !entry.suspended &&
    !mobile &&
    entry.status === "PAUSED"
  const showSuspend =
    canEdit &&
    !entry.suspended &&
    entry.entryType === "REAL" &&
    (entry.status === "IN_PROGRESS" || entry.status === "PAUSED")
  const showRestore = canEdit && entry.entryType === "REAL" && entry.suspended
  const showFullRoute = entry.entryType === "REAL"

  return {
    showTake,
    showEdit,
    showDetails,
    showPause,
    showResume,
    showSuspend,
    showRestore,
    showFullRoute,
    showActions:
      showTake ||
      showEdit ||
      showDetails ||
      showPause ||
      showResume ||
      showFullRoute,
  }
}

function TaskBoardCardContent({
  entry,
  now,
  collapsed,
  inDailyPlan,
  repairComplexity,
}: {
  entry: TaskBoardEntryDto
  now: number
  collapsed: boolean
  inDailyPlan: boolean
  repairComplexity?: TaskBoardRepairComplexity | null
}) {
  const workers = assignedWorkerNames(entry)
  const groups = assignedGroupNames(entry)
  const timer = taskTimerAt(entry, now)
  const stateLabel = timerStateLabel(timer.timerState)

  return (
    <CardContent className="flex flex-col gap-2">
      <div className="flex flex-wrap gap-1">
        <Badge variant={entry.status === "IN_PROGRESS" ? "default" : "outline"}>
          {statusLabels[entry.status]}
        </Badge>
        {inDailyPlan ? (
          <Badge variant="outline" className="rounded-full">
            План на день
          </Badge>
        ) : null}
        {entry.entryType === "SHADOW" ? (
          <Badge variant="secondary">После предыдущего этапа</Badge>
        ) : null}
        {entry.suspended ? (
          <Badge variant="secondary">Временно отключено</Badge>
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
        <dl className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-1 text-sm text-muted-foreground">
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
  inDailyPlan = false,
  routeHighlighted = false,
  fullRouteSelected = false,
  palette = null,
  repairComplexity = null,
}: {
  entry: TaskBoardEntryDto
  now: number
  mobile: boolean
  canEdit: boolean
  collapsed: boolean
  inDailyPlan?: boolean
  routeHighlighted?: boolean
  fullRouteSelected?: boolean
  palette?: KpiPalette | null
  repairComplexity?: TaskBoardRepairComplexity | null
}) {
  const descriptionLines = taskDescriptionLines(taskDescription(entry))
  const unitLabel =
    entry.unitNumber ?? descriptionLines[0] ?? "Задание без номера"
  const {
    showTake,
    showEdit,
    showDetails,
    showPause,
    showResume,
    showFullRoute,
    showActions,
  } = cardActionVisibility(entry, mobile, canEdit)
  const appearance = kpiCardAppearance(entry, now, palette)

  return (
    <Card
      size="sm"
      style={appearance.style}
      className={cn(
        "w-full data-[size=sm]:[--card-spacing:--spacing(3)]",
        entry.entryType === "SHADOW" && shadowEntryCardClassName,
        entry.suspended && "border-muted bg-muted",
        routeHighlighted && "ring-2 ring-primary"
      )}
      data-kpi-color={appearance.color ?? undefined}
      data-timer-state={entry.timerSnapshot?.timerState}
      data-collapsed={collapsed || undefined}
      data-route-highlighted={routeHighlighted || undefined}
    >
      <CardHeader className="flex flex-col gap-2">
        <div className="flex w-full min-w-0 items-center justify-between gap-2">
          <CardTitle className="min-w-0 flex-1 leading-snug break-words">
            {unitLabel}
          </CardTitle>
          <div
            data-slot="task-board-card-actions"
            className="ml-auto flex shrink-0 items-center justify-end gap-1"
          >
            <Button
              type="button"
              size="icon-sm"
              variant={entry.pinned ? "secondary" : "ghost"}
              aria-label={
                entry.pinned
                  ? `Открепить этап ${unitLabel}`
                  : `Закрепить этап ${unitLabel}`
              }
              title={entry.pinned ? "Открепить" : "Закрепить"}
              disabled
            >
              <HugeiconsIcon
                icon={entry.pinned ? PinOffIcon : PinIcon}
                data-icon="inline-start"
              />
            </Button>
            <Button type="button" size="icon-sm" variant="ghost" disabled>
              <HugeiconsIcon icon={DragDropVerticalIcon} />
            </Button>
            <Button type="button" size="icon-sm" variant="ghost" disabled>
              <HugeiconsIcon
                icon={collapsed ? ArrowDown01Icon : ArrowUp01Icon}
              />
            </Button>
          </div>
        </div>
        {!collapsed && descriptionLines.length > 0 ? (
          <CardDescription className="flex flex-col gap-1">
            {descriptionLines.map((line, index) => (
              <span key={`${line}:${index}`}>{line}</span>
            ))}
          </CardDescription>
        ) : null}
      </CardHeader>
      <TaskBoardCardContent
        entry={entry}
        now={now}
        collapsed={collapsed}
        inDailyPlan={inDailyPlan}
        repairComplexity={repairComplexity}
      />
      {showActions ? (
        <CardFooter className="grid grid-cols-2 gap-1.5 [&>button]:min-w-0 [&>button]:text-xs">
          {showTake ? (
            <Button type="button" size="sm" className="w-full" disabled>
              <HugeiconsIcon icon={PlayIcon} data-icon="inline-start" />
              Взять в работу
            </Button>
          ) : null}
          {showFullRoute ? (
            <Button
              type="button"
              size="sm"
              variant={fullRouteSelected ? "secondary" : "outline"}
              className="w-full"
              aria-pressed={fullRouteSelected}
              disabled
            >
              <HugeiconsIcon icon={Route01Icon} data-icon="inline-start" />
              Полный путь
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
  inDailyPlan,
  routeHighlighted,
  fullRouteSelected,
  futureAvailabilityEligible = false,
  reorderEnabled = false,
  onDetails,
  onEdit,
  onTake,
  onPause,
  onResume,
  onSuspend,
  onRestore,
  onPin,
  onFutureAvailabilityChange,
  onShowFullRoute,
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
  inDailyPlan: boolean
  routeHighlighted: boolean
  fullRouteSelected: boolean
  futureAvailabilityEligible?: boolean
  reorderEnabled?: boolean
  onDetails: (entry: TaskBoardEntryDto) => void
  onEdit: (entry: TaskBoardEntryDto) => void
  onTake: (entry: TaskBoardEntryDto) => void
  onPause: (entry: TaskBoardEntryDto) => void
  onResume: (entry: TaskBoardEntryDto) => void
  onSuspend: (entry: TaskBoardEntryDto) => void
  onRestore: (entry: TaskBoardEntryDto) => void
  onPin: (entry: TaskBoardEntryDto, pinned: boolean) => void
  onFutureAvailabilityChange: (
    entry: TaskBoardEntryDto,
    available: boolean
  ) => void
  onShowFullRoute: (entry: TaskBoardEntryDto) => void
  onToggleCollapsed: (entryId: string) => void
  palette?: KpiPalette | null
  repairComplexity?: TaskBoardRepairComplexity | null
}) {
  const sortableEnabled =
    reorderEnabled &&
    canEdit &&
    !actionPending &&
    entry.entryType === "REAL" &&
    entry.status === "WAITING" &&
    !entry.suspended &&
    !entry.pinned
  const {
    attributes,
    isDragging,
    listeners,
    setNodeRef,
    transform,
    transition,
  } = useSortable({ id: entry.id, disabled: !sortableEnabled })
  const descriptionLines = taskDescriptionLines(taskDescription(entry))
  const {
    showTake,
    showEdit,
    showDetails,
    showPause,
    showResume,
    showSuspend,
    showRestore,
    showFullRoute,
    showActions,
  } = cardActionVisibility(entry, mobile, canEdit)
  const appearance = kpiCardAppearance(entry, now, palette)
  const unitLabel =
    entry.unitNumber ?? descriptionLines[0] ?? "Задание без номера"
  const futureAvailabilityId = `future-task-availability-${entry.id}`
  const sortableStyle = {
    ...appearance.style,
    transform: CSS.Transform.toString(transform),
    transition,
    zIndex: isDragging ? 10 : undefined,
  } satisfies CSSProperties

  return (
    <Card
      ref={setNodeRef}
      style={sortableStyle}
      size="sm"
      className={cn(
        "data-[size=sm]:[--card-spacing:--spacing(3)]",
        entry.entryType === "SHADOW" && shadowEntryCardClassName,
        entry.entryType === "REAL" &&
          !appearance.color &&
          entry.status === "IN_PROGRESS" &&
          "border-primary",
        entry.suspended && "border-muted bg-muted",
        (entry.status === "DONE" || entry.status === "CANCELLED") &&
          "opacity-65",
        routeHighlighted && "ring-2 ring-primary"
      )}
      data-collapsed={collapsed || undefined}
      data-kpi-color={appearance.color ?? undefined}
      data-timer-state={entry.timerSnapshot?.timerState}
      data-route-highlighted={routeHighlighted || undefined}
      data-task-id={entry.taskId}
      data-entry-id={entry.id}
      data-dragging={isDragging || undefined}
      aria-label={
        descriptionLines.length > 0
          ? `${unitLabel}: ${descriptionLines.join(", ")}`
          : unitLabel
      }
    >
      <CardHeader className="flex flex-col gap-2">
        <div className="flex w-full min-w-0 items-center justify-between gap-2">
          <CardTitle className="min-w-0 flex-1 leading-snug break-words">
            {unitLabel}
          </CardTitle>
          <div
            data-slot="task-board-card-actions"
            className="ml-auto flex shrink-0 items-center justify-end gap-1"
          >
            <Button
              type="button"
              size="icon-sm"
              variant={entry.pinned ? "secondary" : "ghost"}
              title={entry.pinned ? "Открепить" : "Закрепить"}
              disabled={
                actionPending || entry.entryType === "SHADOW" || entry.suspended
              }
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
            </Button>
            <Button
              type="button"
              size="icon-sm"
              variant="ghost"
              className="touch-none"
              disabled={!sortableEnabled}
              aria-label={`Переместить этап ${unitLabel}`}
              {...attributes}
              {...listeners}
            >
              <HugeiconsIcon icon={DragDropVerticalIcon} />
            </Button>
            <Button
              type="button"
              size="icon-sm"
              variant="ghost"
              aria-label={`${collapsed ? "Развернуть" : "Свернуть"} этап ${unitLabel}`}
              aria-expanded={!collapsed}
              onClick={() => onToggleCollapsed(entry.id)}
            >
              <HugeiconsIcon
                icon={collapsed ? ArrowDown01Icon : ArrowUp01Icon}
              />
            </Button>
          </div>
        </div>
        {!collapsed && descriptionLines.length > 0 ? (
          <CardDescription className="flex flex-col gap-1">
            {descriptionLines.map((line, index) => (
              <span key={`${line}:${index}`}>{line}</span>
            ))}
          </CardDescription>
        ) : null}
        {futureAvailabilityEligible && !entry.suspended ? (
          <Field
            orientation="horizontal"
            className="w-full gap-2 rounded-md border border-border/70 px-2 py-1.5"
            data-disabled={!canEdit || actionPending || undefined}
          >
            <Checkbox
              id={futureAvailabilityId}
              checked={entry.entryType === "REAL"}
              disabled={!canEdit || actionPending}
              aria-label={`Доступность этапа ${unitLabel} для рабочих`}
              onCheckedChange={(checked) => {
                if (typeof checked !== "boolean" || !canEdit || actionPending) {
                  return
                }
                onFutureAvailabilityChange(entry, checked)
              }}
            />
            <FieldLabel
              htmlFor={futureAvailabilityId}
              className="min-w-0 text-xs"
            >
              Доступна рабочим
            </FieldLabel>
          </Field>
        ) : null}
      </CardHeader>
      <TaskBoardCardContent
        entry={entry}
        now={now}
        collapsed={collapsed}
        inDailyPlan={inDailyPlan}
        repairComplexity={repairComplexity}
      />
      {showActions ? (
        <CardFooter className="grid grid-cols-2 gap-1.5 [&>button]:min-w-0 [&>button]:text-xs">
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
          {showFullRoute ? (
            <Button
              type="button"
              size="sm"
              variant={fullRouteSelected ? "secondary" : "outline"}
              className="w-full"
              aria-pressed={fullRouteSelected}
              onClick={() => onShowFullRoute(entry)}
            >
              <HugeiconsIcon icon={Route01Icon} data-icon="inline-start" />
              Полный путь
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
          {showSuspend ? (
            <Button
              type="button"
              size="sm"
              variant="destructive"
              className="w-full"
              title="Временно отключить всю задачу и освободить исполнителей"
              disabled={actionPending}
              onClick={() => onSuspend(entry)}
            >
              <HugeiconsIcon icon={Cancel01Icon} data-icon="inline-start" />
              Отменить задание
            </Button>
          ) : null}
          {showRestore ? (
            <Button
              type="button"
              size="sm"
              className="w-full"
              disabled={actionPending}
              onClick={() => onRestore(entry)}
            >
              <HugeiconsIcon icon={PlayIcon} data-icon="inline-start" />
              Восстановить
            </Button>
          ) : null}
        </CardFooter>
      ) : null}
    </Card>
  )
})
