import { memo, useEffect, useId, useMemo, useRef, useState } from "react"
import {
  closestCenter,
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core"
import {
  SortableContext,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  ArrowLeft01Icon,
  ArrowRight01Icon,
  CheckmarkCircle01Icon,
  PlayIcon,
} from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
import type { KpiPalette } from "@/features/settings/kpi/api/kpi-settings-api"
import { cn } from "@/lib/utils"
import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
} from "@/features/task-board/model/task-board"
import {
  TaskBoardCard,
  type TaskBoardRepairComplexity,
} from "@/features/task-board/task-board-card"

function queueKindLabel(queue: TaskBoardQueueDto) {
  if (queue.kind === "MOVEMENT") return "Перемещение"
  if (queue.kind === "FURNITURE_MOVEMENT") return "Перемещение мебели"
  if (queue.kind === "HOLDING") return "Ожидание"
  return "Ремонт"
}

function isReorderableEntry(entry: TaskBoardEntryDto) {
  return (
    entry.entryType === "REAL" && entry.status === "WAITING" && !entry.pinned
  )
}

function WorkerPlanControl({
  queue,
  canManage,
  pending,
  onUpdate,
}: {
  queue: TaskBoardQueueDto
  canManage: boolean
  pending: boolean
  onUpdate: (
    queue: TaskBoardQueueDto,
    workerFeedEnabled: boolean,
    availableTaskLimit: number
  ) => void
}) {
  const inputId = useId()
  const [open, setOpen] = useState(false)
  const [limit, setLimit] = useState(String(queue.availableTaskLimit))
  const parsedLimit = Number(limit)
  const limitValid =
    Number.isInteger(parsedLimit) && parsedLimit >= 1 && parsedLimit <= 50

  return (
    <div className="ml-auto flex shrink-0 items-center gap-2">
      <Popover
        open={open}
        onOpenChange={(nextOpen) => {
          if (nextOpen) setLimit(String(queue.availableTaskLimit))
          setOpen(nextOpen)
        }}
      >
        <PopoverTrigger asChild>
          <Button
            type="button"
            size="xs"
            variant="outline"
            disabled={!canManage || pending}
            aria-label={`Изменить план на день для очереди ${queue.label}`}
          >
            План на день: {queue.availableTaskLimit}
          </Button>
        </PopoverTrigger>
        <PopoverContent align="end" className="w-64">
          <form
            className="flex flex-col gap-3"
            onSubmit={(event) => {
              event.preventDefault()
              if (!limitValid || pending || !canManage) return
              onUpdate(queue, queue.workerFeedEnabled, parsedLimit)
              setOpen(false)
            }}
          >
            <Label htmlFor={inputId}>Количество задач в WorkerApp</Label>
            <Input
              id={inputId}
              type="number"
              min={1}
              max={50}
              step={1}
              value={limit}
              disabled={pending || !canManage}
              aria-invalid={!limitValid}
              onChange={(event) => setLimit(event.target.value)}
            />
            {!limitValid ? (
              <p role="alert" className="text-xs text-destructive">
                Укажите целое число от 1 до 50.
              </p>
            ) : null}
            <Button
              type="submit"
              size="sm"
              disabled={!limitValid || pending || !canManage}
            >
              Сохранить
            </Button>
          </form>
        </PopoverContent>
      </Popover>
      <Checkbox
        checked={queue.workerFeedEnabled}
        disabled={!canManage || pending}
        aria-label={`Показывать очередь ${queue.label} в WorkerApp`}
        title={`Показывать очередь ${queue.label} в WorkerApp`}
        onCheckedChange={(checked) => {
          if (typeof checked !== "boolean" || pending || !canManage) return
          onUpdate(queue, checked, queue.availableTaskLimit)
        }}
      />
    </div>
  )
}

export const TaskBoardColumn = memo(function TaskBoardColumn({
  queue,
  visibleEntries,
  now,
  mobile,
  canEdit,
  canManage,
  collapsed,
  actionPending,
  configPending,
  queueActionsDisabled,
  reorderDisabled,
  dailyPlanEntryIds,
  futureEntryIds,
  highlightedTaskId,
  initialScrollTop,
  onToggleCollapsed,
  onUpdateWorkerPlan,
  onReorder,
  onDetails,
  onEdit,
  onTake,
  onPause,
  onResume,
  onPin,
  onFutureAvailabilityChange,
  onScrollTopChange,
  onShowFullRoute,
  isEntryCollapsed,
  onToggleEntryCollapsed,
  onComplete,
  palette,
  repairComplexitiesByRepairId,
}: {
  queue: TaskBoardQueueDto
  visibleEntries: TaskBoardEntryDto[]
  now: number
  mobile: boolean
  canEdit: boolean
  canManage: boolean
  collapsed: boolean
  actionPending: boolean
  configPending: boolean
  queueActionsDisabled: boolean
  reorderDisabled: boolean
  dailyPlanEntryIds: ReadonlySet<string>
  futureEntryIds: ReadonlySet<string>
  highlightedTaskId: string | null
  initialScrollTop: number
  onToggleCollapsed: (queueKey: string) => void
  onUpdateWorkerPlan: (
    queue: TaskBoardQueueDto,
    workerFeedEnabled: boolean,
    availableTaskLimit: number
  ) => void
  onReorder: (entry: TaskBoardEntryDto, targetIndex: number) => void
  onDetails: (entry: TaskBoardEntryDto) => void
  onEdit: (entry: TaskBoardEntryDto) => void
  onTake: (entry: TaskBoardEntryDto) => void
  onPause: (entry: TaskBoardEntryDto) => void
  onResume: (entry: TaskBoardEntryDto) => void
  onPin: (entry: TaskBoardEntryDto, pinned: boolean) => void
  onFutureAvailabilityChange: (
    entry: TaskBoardEntryDto,
    available: boolean
  ) => void
  onScrollTopChange: (queueKey: string, scrollTop: number) => void
  onShowFullRoute: (entry: TaskBoardEntryDto) => void
  isEntryCollapsed: (entryId: string) => boolean
  onToggleEntryCollapsed: (entryId: string) => void
  onComplete: (entry: TaskBoardEntryDto) => void
  palette: KpiPalette | null
  repairComplexitiesByRepairId: ReadonlyMap<string, TaskBoardRepairComplexity>
}) {
  const scrollBodyRef = useRef<HTMLDivElement | null>(null)
  const restoredScrollQueueRef = useRef<string | null>(null)
  const fullRouteScrollTopRef = useRef<number | null>(null)
  const reorderableEntries = useMemo(
    () => visibleEntries.filter(isReorderableEntry),
    [visibleEntries]
  )
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    })
  )
  const nextWaiting = visibleEntries.find(
    (entry) => entry.entryType === "REAL" && entry.status === "WAITING"
  )
  const inProgress = visibleEntries.find(
    (entry) => entry.status === "IN_PROGRESS"
  )

  useEffect(() => {
    const scrollBody = scrollBodyRef.current
    if (!scrollBody || mobile || restoredScrollQueueRef.current === queue.key) {
      return
    }
    restoredScrollQueueRef.current = queue.key
    scrollBody.scrollTop = initialScrollTop
  }, [initialScrollTop, mobile, queue.key])

  useEffect(() => {
    const scrollBody = scrollBodyRef.current
    if (!scrollBody) return

    if (!highlightedTaskId) {
      const scrollTopBeforeRoute = fullRouteScrollTopRef.current
      fullRouteScrollTopRef.current = null
      if (scrollTopBeforeRoute === null) return
      scrollBody.scrollTo({
        top: scrollTopBeforeRoute,
        left: scrollBody.scrollLeft,
        behavior: "smooth",
      })
      return
    }
    if (mobile || collapsed) return
    const target = Array.from(
      scrollBody.querySelectorAll<HTMLElement>("[data-task-id]")
    ).find((element) => element.dataset.taskId === highlightedTaskId)
    if (!target) return

    if (fullRouteScrollTopRef.current === null) {
      fullRouteScrollTopRef.current = scrollBody.scrollTop
    }
    const scrollBounds = scrollBody.getBoundingClientRect()
    const targetBounds = target.getBoundingClientRect()
    const targetTop =
      scrollBody.scrollTop +
      targetBounds.top -
      scrollBounds.top -
      (scrollBody.clientHeight - targetBounds.height) / 2
    scrollBody.scrollTo({
      top: Math.max(0, targetTop),
      left: scrollBody.scrollLeft,
      behavior: "smooth",
    })
  }, [collapsed, highlightedTaskId, mobile, visibleEntries])

  function handleDragEnd(event: DragEndEvent) {
    if (reorderDisabled || !event.over) return
    const sourceIndex = reorderableEntries.findIndex(
      (entry) => entry.id === String(event.active.id)
    )
    const targetIndex = reorderableEntries.findIndex(
      (entry) => entry.id === String(event.over?.id)
    )
    if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) {
      return
    }
    onReorder(reorderableEntries[sourceIndex]!, targetIndex)
  }

  if (collapsed) {
    return (
      <section
        className={cn(
          "flex shrink-0 items-center gap-3 rounded-lg bg-card ring-1 ring-foreground/10",
          mobile
            ? "h-auto w-full flex-row px-3 py-2"
            : "h-full min-h-0 w-16 flex-col py-3"
        )}
        aria-label={`${queue.label}, свёрнута`}
      >
        <Button
          type="button"
          size="icon-sm"
          variant="ghost"
          aria-label={`Развернуть очередь ${queue.label}`}
          onClick={() => onToggleCollapsed(queue.key)}
        >
          <HugeiconsIcon icon={ArrowRight01Icon} />
        </Button>
        <Badge variant="secondary">{visibleEntries.length}</Badge>
        <span className={cn(!mobile && "[writing-mode:vertical-rl]")}>
          {queue.label}
        </span>
      </section>
    )
  }

  return (
    <section
      className={cn(
        "flex min-h-0 shrink-0 flex-col overflow-hidden rounded-lg bg-muted/35 ring-1 ring-foreground/10",
        mobile ? "h-auto w-full" : "h-full w-80"
      )}
      aria-label={`Очередь ${queue.label}`}
    >
      <header
        className={cn(
          "sticky top-0 flex shrink-0 flex-col gap-2 bg-card p-3 shadow-xs",
          !mobile && "h-32"
        )}
      >
        <div className="flex h-8 shrink-0 items-start justify-between gap-2">
          <div className="min-w-0">
            <h2 className="truncate font-heading text-sm font-medium">
              {queue.label}
            </h2>
          </div>
          <Button
            type="button"
            size="icon-sm"
            variant="ghost"
            aria-label={`Свернуть очередь ${queue.label}`}
            onClick={() => onToggleCollapsed(queue.key)}
          >
            <HugeiconsIcon icon={ArrowLeft01Icon} />
          </Button>
        </div>
        <div
          data-slot="task-board-column-config"
          className="flex h-6 shrink-0 items-center gap-1 overflow-hidden whitespace-nowrap"
        >
          <Badge variant="secondary">{visibleEntries.length}</Badge>
          <Badge
            variant="outline"
            className="max-w-24 min-w-0 truncate"
            title={queueKindLabel(queue)}
          >
            {queueKindLabel(queue)}
          </Badge>
          <WorkerPlanControl
            queue={queue}
            canManage={canManage}
            pending={configPending}
            onUpdate={onUpdateWorkerPlan}
          />
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={!nextWaiting || actionPending || queueActionsDisabled}
            onClick={() => nextWaiting && onTake(nextWaiting)}
          >
            <HugeiconsIcon icon={PlayIcon} data-icon="inline-start" />
            Начать
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={!inProgress || actionPending || queueActionsDisabled}
            onClick={() => inProgress && onComplete(inProgress)}
          >
            <HugeiconsIcon
              icon={CheckmarkCircle01Icon}
              data-icon="inline-start"
            />
            Завершить
          </Button>
        </div>
      </header>

      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        onDragEnd={handleDragEnd}
      >
        <div
          ref={scrollBodyRef}
          data-slot="task-board-column-scroll-body"
          className={cn(
            "flex min-h-0 flex-1 touch-pan-y flex-col gap-3 p-3",
            mobile
              ? "overflow-visible"
              : "[scrollbar-width:none] overflow-y-auto overscroll-contain [&::-webkit-scrollbar]:hidden"
          )}
          onScroll={(event) =>
            onScrollTopChange(queue.key, event.currentTarget.scrollTop)
          }
        >
          <SortableContext
            items={reorderableEntries.map((entry) => entry.id)}
            strategy={verticalListSortingStrategy}
          >
            {visibleEntries.map((entry) => (
              <TaskBoardCard
                key={entry.id}
                entry={entry}
                now={now}
                mobile={mobile}
                canEdit={canEdit}
                collapsed={isEntryCollapsed(entry.id)}
                actionPending={actionPending}
                inDailyPlan={dailyPlanEntryIds.has(entry.id)}
                routeHighlighted={highlightedTaskId === entry.taskId}
                fullRouteSelected={highlightedTaskId === entry.taskId}
                futureAvailabilityEligible={futureEntryIds.has(entry.id)}
                reorderEnabled={!reorderDisabled}
                onDetails={onDetails}
                onEdit={onEdit}
                onTake={onTake}
                onPause={onPause}
                onResume={onResume}
                onPin={onPin}
                onFutureAvailabilityChange={onFutureAvailabilityChange}
                onShowFullRoute={onShowFullRoute}
                onToggleCollapsed={onToggleEntryCollapsed}
                palette={palette}
                repairComplexity={
                  entry.source?.type === "MAINTENANCE_REPAIR"
                    ? repairComplexitiesByRepairId.get(entry.source.sourceId)
                    : null
                }
              />
            ))}
          </SortableContext>
        </div>
      </DndContext>
    </section>
  )
})
