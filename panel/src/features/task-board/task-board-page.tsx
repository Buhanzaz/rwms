import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import {
  closestCenter,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  pointerWithin,
  PointerSensor,
  TouchSensor,
  useSensor,
  useSensors,
  type CollisionDetection,
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
} from "@dnd-kit/core"
import { sortableKeyboardCoordinates } from "@dnd-kit/sortable"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  ArrowLeft01Icon,
  ArrowRight01Icon,
  Calendar03Icon,
  Search01Icon,
} from "@hugeicons/core-free-icons"
import {
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query"
import { Link, useNavigate, useSearchParams } from "react-router-dom"

import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from "@/components/ui/input-group"
import { Skeleton } from "@/components/ui/skeleton"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import {
  getKpiSettings,
  kpiSettingsKeys,
} from "@/features/settings/kpi/api/kpi-settings-api"
import { getMaintenanceRepair } from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import {
  completeTaskBoardEntry,
  getTaskBoard,
  moveTaskBoardEntry,
  pauseTaskBoardEntry,
  pinTaskBoardEntry,
  resumeTaskBoardEntry,
  takeTaskBoardEntry,
  TASK_BOARD_QUERY_KEY,
  taskBoardQueryKey,
} from "@/features/task-board/api/task-board-api"
import {
  canMoveEntryToQueue,
  mergeQueueCollapsedSettings,
  taskBoardTargetIndexAt,
} from "@/features/task-board/domain/task-board-domain"
import {
  nextTaskTimerTransitionAt,
  paletteForTaskBoard,
} from "@/features/task-board/domain/task-board-kpi-presentation"
import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
  TaskBoardSnapshotDto,
} from "@/features/task-board/model/task-board"
import {
  TaskBoardCardPreview,
  type TaskBoardRepairComplexity,
} from "@/features/task-board/task-board-card"
import { TaskBoardColumn } from "@/features/task-board/task-board-column"
import { TaskBoardCompletionDialog } from "@/features/task-board/task-board-completion-dialog"
import { TaskBoardTakeDialog } from "@/features/task-board/task-board-take-dialog"
import { useIsMobile } from "@/hooks/use-mobile"
import { useWarehouse } from "@/hooks/use-warehouse"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"
import { cn } from "@/lib/utils"

type BoardAction =
  | {
      kind: "take"
      entry: TaskBoardEntryDto
      workerGroupId: string
      workerId: string | null
    }
  | { kind: "pause"; entry: TaskBoardEntryDto }
  | { kind: "resume"; entry: TaskBoardEntryDto }
  | { kind: "complete"; entry: TaskBoardEntryDto }

type DropPlacement = "before" | "after" | "end"
type DropTarget = { overId: string; placement: DropPlacement }

const dragActivationConstraint = { delay: 220, tolerance: 6 }

const taskBoardCollisionDetection: CollisionDetection = (args) => {
  if (!args.pointerCoordinates) return closestCenter(args)

  const collisions = pointerWithin(args)
  const activeCollision = collisions.find(
    (collision) => collision.id === args.active.id
  )
  const nonActiveCollisions = collisions.filter(
    (collision) => collision.id !== args.active.id
  )
  const activeQueueKey = args.active.data.current?.queueKey
  const ownQueueId =
    typeof activeQueueKey === "string" ? `queue:${activeQueueKey}` : null

  if (
    activeCollision &&
    ownQueueId &&
    nonActiveCollisions.length === 1 &&
    nonActiveCollisions[0]?.id === ownQueueId
  ) {
    return [activeCollision]
  }

  return nonActiveCollisions
}

function cloneBoard(board: TaskBoardSnapshotDto): TaskBoardSnapshotDto {
  return {
    ...board,
    queues: board.queues.map((queue) => ({
      ...queue,
      entries: [...queue.entries],
    })),
  }
}

function queueContaining(board: TaskBoardSnapshotDto, entryId: string) {
  return board.queues.find((queue) =>
    queue.entries.some((entry) => entry.id === entryId)
  )
}

function targetQueueForOver(board: TaskBoardSnapshotDto, overId: string) {
  if (overId.startsWith("queue:")) {
    const key = overId.slice("queue:".length)
    return board.queues.find((queue) => queue.key === key)
  }
  return queueContaining(board, overId)
}

function previewMove(
  board: TaskBoardSnapshotDto,
  activeId: string,
  overId: string,
  placement: DropPlacement
) {
  const sourceQueue = queueContaining(board, activeId)
  const targetQueue = targetQueueForOver(board, overId)
  if (!sourceQueue || !targetQueue) return board
  const entry = sourceQueue.entries.find(
    (candidate) => candidate.id === activeId
  )
  if (!entry || !canMoveEntryToQueue(entry)) return board

  const next = cloneBoard(board)
  const nextSource = next.queues.find((queue) => queue.key === sourceQueue.key)!
  const nextTarget = next.queues.find((queue) => queue.key === targetQueue.key)!
  const sourceIndex = nextSource.entries.findIndex(
    (candidate) => candidate.id === activeId
  )
  const [moved] = nextSource.entries.splice(sourceIndex, 1)
  if (!moved) return board
  const hoveredIndex = nextTarget.entries.findIndex(
    (candidate) => candidate.id === overId
  )
  const insertionIndex =
    placement === "end"
      ? nextTarget.entries.length
      : hoveredIndex < 0
        ? nextTarget.entries.length
        : hoveredIndex + (placement === "after" ? 1 : 0)
  nextTarget.entries.splice(insertionIndex, 0, {
    ...moved,
    queueKey: nextTarget.key,
    queueId: nextTarget.settingsQueueId,
  })

  if (
    nextSource.key === nextTarget.key &&
    nextTarget.entries.every(
      (candidate, index) => candidate.id === sourceQueue.entries[index]?.id
    )
  ) {
    return board
  }
  return next
}

function placementForEvent(event: DragOverEvent | DragEndEvent): DropPlacement {
  if (!event.over || String(event.over.id).startsWith("queue:")) return "end"
  const activeRect =
    event.active.rect.current.translated ?? event.active.rect.current.initial
  if (!activeRect) return "before"
  const activeCenter = activeRect.top + activeRect.height / 2
  const overCenter = event.over.rect.top + event.over.rect.height / 2
  return activeCenter < overCenter ? "before" : "after"
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}

function addDays(value: string, days: number) {
  const date = new Date(`${value}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

function formatBoardDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    weekday: "short",
    day: "2-digit",
    month: "short",
  }).format(new Date(`${value}T00:00:00`))
}

function TaskBoardDateSelector({
  selectedDate,
  availableDates,
  disabled,
  onSelect,
}: {
  selectedDate: string | null
  availableDates: string[]
  disabled: boolean
  onSelect: (date: string) => void
}) {
  const firstAvailable = availableDates[0] ?? null
  const [windowCenter, setWindowCenter] = useState(
    selectedDate ?? firstAvailable ?? ""
  )
  const available = useMemo(() => new Set(availableDates), [availableDates])

  if (!windowCenter) {
    return (
      <p className="text-xs text-muted-foreground">
        Дат с запланированными заданиями пока нет.
      </p>
    )
  }

  const dates = Array.from({ length: 7 }, (_, index) =>
    addDays(windowCenter, index - 3)
  )
  return (
    <section aria-label="Дата очереди" className="flex items-center gap-2">
      <Button
        type="button"
        size="icon"
        variant="ghost"
        disabled={disabled}
        aria-label="Предыдущие даты"
        onClick={() => setWindowCenter((current) => addDays(current, -7))}
      >
        <HugeiconsIcon icon={ArrowLeft01Icon} />
      </Button>
      <div className="flex min-w-0 flex-1 gap-1 overflow-x-auto py-1">
        {dates.map((date) => {
          const enabled = available.has(date)
          return (
            <Button
              key={date}
              type="button"
              size="default"
              variant={date === selectedDate ? "default" : "outline"}
              className="shrink-0"
              disabled={disabled || !enabled}
              aria-pressed={date === selectedDate}
              onClick={() => onSelect(date)}
            >
              {formatBoardDate(date)}
            </Button>
          )
        })}
      </div>
      <Button
        type="button"
        size="icon"
        variant="ghost"
        disabled={disabled}
        aria-label="Следующие даты"
        onClick={() => setWindowCenter((current) => addDays(current, 7))}
      >
        <HugeiconsIcon icon={ArrowRight01Icon} />
      </Button>
    </section>
  )
}

export function TaskBoardPage() {
  const isMobile = useIsMobile()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const warehouseId = selectedWarehouse?.id ?? null
  const canEdit = Boolean(
    warehouseId && hasWarehouseAccess(currentUser, warehouseId, "EDIT")
  )
  const [search, setSearch] = useState("")
  const [showFuture, setShowFuture] = useState(false)
  const [collapsedQueues, setCollapsedQueues] = useState<Set<string>>(
    () => new Set()
  )
  const [entryCollapseStates, setEntryCollapseStates] = useState<
    Map<string, boolean>
  >(() => new Map())
  const [preview, setPreviewState] = useState<TaskBoardSnapshotDto | null>(null)
  const previewRef = useRef<TaskBoardSnapshotDto | null>(null)
  const dragBaselineRef = useRef<TaskBoardSnapshotDto | null>(null)
  const lastDropTargetRef = useRef<DropTarget | null>(null)
  const dragOutcomeRef = useRef("Перенос завершён.")
  const [activeEntryId, setActiveEntryId] = useState<string | null>(null)
  const [takeEntry, setTakeEntry] = useState<TaskBoardEntryDto | null>(null)
  const [completeEntry, setCompleteEntry] = useState<TaskBoardEntryDto | null>(
    null
  )
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const focusedExternalTaskId = searchParams.get("externalTaskId")
  const focusedTaskId = searchParams.get("taskId")
  const requestedDate = searchParams.get("date")
  const collapsedSettingsRef = useRef<{
    warehouseId: string
    values: Map<string, boolean>
  } | null>(null)

  const boardQuery = useQuery({
    queryKey: taskBoardQueryKey(warehouseId ?? "none", requestedDate),
    queryFn: () => getTaskBoard(accessToken!, warehouseId!, requestedDate),
    enabled: Boolean(accessToken && warehouseId),
  })
  const kpiSettingsQuery = useQuery({
    queryKey: kpiSettingsKeys.warehouse(warehouseId ?? "none"),
    queryFn: () => getKpiSettings(accessToken!, warehouseId!),
    enabled: Boolean(accessToken && warehouseId),
    staleTime: 60_000,
  })
  const taskBoardPalette = paletteForTaskBoard(kpiSettingsQuery.data ?? null)
  const maintenanceRepairSourceIds = useMemo(
    () =>
      Array.from(
        new Set(
          (
            boardQuery.data?.queues.flatMap((queue) => queue.entries) ?? []
          ).flatMap((entry) =>
            entry.source?.type === "MAINTENANCE_REPAIR"
              ? [entry.source.sourceId]
              : []
          )
        )
      ).sort(),
    [boardQuery.data]
  )
  const repairComplexityQueries = useQueries({
    queries: maintenanceRepairSourceIds.map((repairId) => ({
      queryKey: [
        "maintenance",
        "repairs",
        warehouseId ?? "none",
        repairId,
        "task-board-complexity",
      ],
      queryFn: () => getMaintenanceRepair(accessToken!, warehouseId!, repairId),
      enabled: Boolean(accessToken && warehouseId),
    })),
  })
  const repairComplexitiesByRepairId = useMemo(
    () =>
      new Map<string, TaskBoardRepairComplexity>(
        maintenanceRepairSourceIds.flatMap((repairId, index) => {
          const complexity = repairComplexityQueries[index]?.data?.complexity
          return complexity ? [[repairId, complexity]] : []
        })
      ),
    [maintenanceRepairSourceIds, repairComplexityQueries]
  )

  const setPreview = useCallback(
    (
      value:
        | TaskBoardSnapshotDto
        | null
        | ((
            current: TaskBoardSnapshotDto | null
          ) => TaskBoardSnapshotDto | null)
    ) => {
      setPreviewState((current) => {
        const next = typeof value === "function" ? value(current) : value
        previewRef.current = next
        return next
      })
    },
    []
  )

  const isEntryCollapsed = useCallback(
    (entryId: string) => entryCollapseStates.get(entryId) ?? isMobile,
    [entryCollapseStates, isMobile]
  )

  const toggleEntryCollapsed = useCallback(
    (entryId: string) => {
      setEntryCollapseStates((current) => {
        const next = new Map(current)
        next.set(entryId, !(current.get(entryId) ?? isMobile))
        return next
      })
    },
    [isMobile]
  )

  useEffect(() => {
    setPreview(boardQuery.data ? cloneBoard(boardQuery.data) : null)
  }, [boardQuery.data, setPreview])

  useEffect(() => {
    if (!warehouseId || !boardQuery.data) return
    const previous = collapsedSettingsRef.current
    const merged = mergeQueueCollapsedSettings({
      current: collapsedQueues,
      previous:
        previous && previous.warehouseId === warehouseId
          ? previous.values
          : null,
      queues: boardQuery.data.queues,
      reset: previous?.warehouseId !== warehouseId,
    })
    collapsedSettingsRef.current = { warehouseId, values: merged.settings }
    if (
      merged.collapsed.size !== collapsedQueues.size ||
      [...merged.collapsed].some((queueKey) => !collapsedQueues.has(queueKey))
    ) {
      setCollapsedQueues(merged.collapsed)
    }
  }, [boardQuery.data, collapsedQueues, warehouseId])

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [])

  const nextTimerTransitionAt = nextTaskTimerTransitionAt(
    boardQuery.data?.queues.flatMap((queue) => queue.entries) ?? []
  )
  useEffect(() => {
    if (nextTimerTransitionAt === null) return
    const delay = Math.max(0, nextTimerTransitionAt - Date.now()) + 100
    const timer = window.setTimeout(
      () =>
        void queryClient.invalidateQueries({
          queryKey: taskBoardQueryKey(warehouseId ?? "none", requestedDate),
          exact: true,
        }),
      delay
    )
    return () => window.clearTimeout(timer)
  }, [nextTimerTransitionAt, queryClient, requestedDate, warehouseId])

  const invalidateTaskBoard = useCallback(
    () => queryClient.invalidateQueries({ queryKey: TASK_BOARD_QUERY_KEY }),
    [queryClient]
  )

  const actionMutation = useMutation({
    mutationFn: (action: BoardAction) => {
      if (!accessToken)
        throw new Error("Не получен токен доступа к доске заданий.")
      if (action.kind === "take") {
        return takeTaskBoardEntry({
          accessToken,
          entry: action.entry,
          workerGroupId: action.workerGroupId,
          workerId: action.workerId,
        })
      }
      if (action.kind === "pause") {
        return pauseTaskBoardEntry(accessToken, action.entry)
      }
      if (action.kind === "resume") {
        return resumeTaskBoardEntry(accessToken, action.entry)
      }
      return completeTaskBoardEntry(accessToken, action.entry)
    },
    onMutate: () => setNotice(null),
    onSuccess: async () => {
      setError(null)
      setTakeEntry(null)
      setCompleteEntry(null)
      await invalidateTaskBoard()
    },
    onError: async (unknownError) => {
      setNotice(null)
      setError(errorMessage(unknownError, "Не удалось изменить этап"))
      await invalidateTaskBoard()
    },
  })

  const dragMutation = useMutation<
    unknown,
    Error,
    {
      entry: TaskBoardEntryDto
      queue: TaskBoardQueueDto
      targetIndex: number
      targetDate: string
    }
  >({
    mutationFn: (params) => {
      if (!accessToken)
        throw new Error("Не получен токен доступа к доске заданий.")
      return moveTaskBoardEntry({ accessToken, ...params })
    },
    onMutate: () => setNotice(null),
    onSuccess: async () => {
      setError(null)
      setNotice("Положение карточки сохранено.")
      await invalidateTaskBoard()
    },
    onError: async (unknownError) => {
      setPreview(boardQuery.data ? cloneBoard(boardQuery.data) : null)
      setNotice(null)
      setError(
        errorMessage(unknownError, "Не удалось сохранить положение карточки")
      )
      await invalidateTaskBoard()
    },
  })

  const pinMutation = useMutation({
    mutationFn: (params: { entry: TaskBoardEntryDto; pinned: boolean }) => {
      if (!accessToken)
        throw new Error("Не получен токен доступа к доске заданий.")
      return pinTaskBoardEntry({ accessToken, ...params })
    },
    onMutate: () => setNotice(null),
    onSuccess: async () => {
      setError(null)
      setNotice("Закрепление задания сохранено.")
      await invalidateTaskBoard()
    },
    onError: async (unknownError) => {
      setNotice(null)
      setError(
        errorMessage(unknownError, "Не удалось изменить закрепление задания")
      )
      await invalidateTaskBoard()
    },
  })

  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: dragActivationConstraint,
    }),
    useSensor(TouchSensor, {
      activationConstraint: dragActivationConstraint,
    }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  )

  const normalizedSearch = search.trim().toLocaleLowerCase("ru")
  const visibleQueues = useMemo(
    () =>
      (preview?.queues ?? []).map((queue) => ({
        queue,
        visibleEntries: queue.entries.filter((entry) => {
          if (!showFuture && entry.entryType === "SHADOW") return false
          if (
            focusedExternalTaskId &&
            entry.externalTaskId !== focusedExternalTaskId
          ) {
            return false
          }
          if (focusedTaskId && entry.taskId !== focusedTaskId) return false
          if (!normalizedSearch) return true
          const haystack = [
            entry.unitNumber ?? "",
            entry.title,
            entry.taskText ?? "",
            entry.externalTaskId ?? "",
            ...entry.assignments.flatMap((assignment) => [
              assignment.workerName ?? "",
              assignment.workerGroupName ?? "",
            ]),
          ]
            .join(" ")
            .toLocaleLowerCase("ru")
          return haystack.includes(normalizedSearch)
        }),
      })),
    [
      focusedExternalTaskId,
      focusedTaskId,
      normalizedSearch,
      preview,
      showFuture,
    ]
  )
  const activeEntry =
    activeEntryId && preview
      ? (preview.queues
          .flatMap((queue) => queue.entries)
          .find((entry) => entry.id === activeEntryId) ?? null)
      : null
  const activeEntryCollapsed = activeEntry
    ? isEntryCollapsed(activeEntry.id)
    : false

  function handleDragStart(event: DragStartEvent) {
    if (!canEdit || !previewRef.current) return
    dragBaselineRef.current = cloneBoard(previewRef.current)
    lastDropTargetRef.current = null
    dragOutcomeRef.current = "Перенос начат."
    setActiveEntryId(String(event.active.id))
    setError(null)
    setNotice(null)
  }

  function handleDragOver(event: DragOverEvent) {
    if (!event.over) return
    const activeId = String(event.active.id)
    const overId = String(event.over.id)
    const baseline = dragBaselineRef.current
    if (!baseline || overId === activeId) return
    const target = { overId, placement: placementForEvent(event) }
    lastDropTargetRef.current = target
    setPreview(previewMove(baseline, activeId, target.overId, target.placement))
  }

  function handleDragEnd(event: DragEndEvent) {
    const activeId = String(event.active.id)
    const baseline = dragBaselineRef.current
    const draggedLabel = describeEntry(activeId)
    if (!event.over || !baseline) {
      dragOutcomeRef.current = "Перенос карточки отменён."
      setPreview(boardQuery.data ? cloneBoard(boardQuery.data) : null)
      clearDrag()
      return
    }
    const eventOverId = String(event.over.id)
    const finalTarget =
      eventOverId === activeId
        ? lastDropTargetRef.current
        : { overId: eventOverId, placement: placementForEvent(event) }
    if (!finalTarget) {
      dragOutcomeRef.current = `${draggedLabel} осталась на прежнем месте.`
      setPreview(baseline)
      clearDrag()
      return
    }
    const requestedQueue = targetQueueForOver(baseline, finalTarget.overId)
    const baselineEntry = queueContaining(baseline, activeId)?.entries.find(
      (candidate) => candidate.id === activeId
    )
    if (
      !requestedQueue ||
      !baselineEntry ||
      !canMoveEntryToQueue(baselineEntry)
    ) {
      dragOutcomeRef.current = requestedQueue
        ? `${draggedLabel} сейчас нельзя переместить.`
        : "Цель переноса недоступна."
      setPreview(baseline)
      clearDrag()
      return
    }
    const current = previewMove(
      baseline,
      activeId,
      finalTarget.overId,
      finalTarget.placement
    )
    setPreview(current)
    const sourceQueue = queueContaining(baseline, activeId)
    const targetQueue = queueContaining(current, activeId)
    const entry = targetQueue?.entries.find(
      (candidate) => candidate.id === activeId
    )
    if (!sourceQueue || !targetQueue || !entry) {
      dragOutcomeRef.current = "Цель переноса недоступна."
      setPreview(baseline)
      clearDrag()
      return
    }
    const sourceIndex = sourceQueue.entries.findIndex(
      (candidate) => candidate.id === activeId
    )
    const targetIndex = targetQueue.entries.findIndex(
      (candidate) => candidate.id === activeId
    )
    if (sourceQueue.key === targetQueue.key && sourceIndex === targetIndex) {
      dragOutcomeRef.current = `${draggedLabel} осталась на прежнем месте.`
      clearDrag()
      return
    }
    dragOutcomeRef.current = `${draggedLabel} отпущена в очереди «${targetQueue.label}». Положение сохраняется.`
    clearDrag()
    dragMutation.mutate({
      entry,
      queue: targetQueue,
      targetIndex: taskBoardTargetIndexAt(targetQueue.entries, activeId),
      targetDate:
        current.selectedDate ?? baseline.selectedDate ?? entry.scheduledDate,
    })
  }

  function clearDrag() {
    setActiveEntryId(null)
    dragBaselineRef.current = null
    lastDropTargetRef.current = null
  }

  function describeEntry(entryId: string) {
    const board = dragBaselineRef.current ?? previewRef.current
    const entry = board?.queues
      .flatMap((queue) => queue.entries)
      .find((candidate) => candidate.id === entryId)
    return entry ? `карточка ${entry.unitNumber ?? entry.title}` : "карточка"
  }

  function describeQueue(overId: string | undefined) {
    if (!overId) return null
    const board = dragBaselineRef.current ?? previewRef.current
    return board ? (targetQueueForOver(board, overId)?.label ?? null) : null
  }

  const busy =
    actionMutation.isPending || dragMutation.isPending || pinMutation.isPending
  const actionsBusy = busy || activeEntryId !== null || !canEdit

  return (
    <div className="flex h-full min-h-0 flex-col gap-3 overflow-hidden">
      <PageToolbar>
        <PageToolbarContent className="max-w-xl">
          <InputGroup>
            <InputGroupAddon>
              <HugeiconsIcon icon={Search01Icon} />
            </InputGroupAddon>
            <InputGroupInput
              value={search}
              aria-label="Поиск по доске задач"
              name="task-board-search"
              autoComplete="off"
              placeholder="Бытовка, задание или исполнитель"
              onChange={(event) => setSearch(event.target.value)}
            />
          </InputGroup>
        </PageToolbarContent>

        <PageToolbarActions className="w-full sm:w-auto">
          <div className="flex basis-full items-center gap-1 sm:w-auto sm:basis-auto">
            <Badge variant="secondary" className="h-9 px-3">
              Текущих: {preview?.realEntries ?? 0}
            </Badge>
            <Badge variant="outline" className="h-9 px-3">
              Будущих: {preview?.shadowEntries ?? 0}
            </Badge>
          </div>
          <div className="grid w-full grid-cols-2 gap-2 sm:contents">
            <Button
              type="button"
              variant={showFuture ? "default" : "outline"}
              className={cn(
                "w-full sm:w-auto",
                !showFuture && "bg-transparent dark:bg-transparent"
              )}
              aria-pressed={showFuture}
              onClick={() => setShowFuture((current) => !current)}
            >
              Неактивные
            </Button>
            {canEdit ? (
              <Button asChild className="w-full sm:w-auto">
                <Link
                  to="/repairs?create=1"
                  state={workspaceEntryNavigationOptions.state}
                >
                  <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                  Создать задание
                </Link>
              </Button>
            ) : (
              <Button className="w-full sm:w-auto" disabled>
                <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                Создать задание
              </Button>
            )}
          </div>
        </PageToolbarActions>
      </PageToolbar>

      <div className="flex items-start gap-2 rounded-lg border bg-card p-2">
        <HugeiconsIcon
          icon={Calendar03Icon}
          className="mt-2 shrink-0 text-muted-foreground"
        />
        <TaskBoardDateSelector
          key={`${warehouseId ?? "none"}:${preview?.selectedDate ?? "none"}`}
          selectedDate={preview?.selectedDate ?? null}
          availableDates={preview?.availableDates ?? []}
          disabled={boardQuery.isLoading || busy}
          onSelect={(date) => {
            const next = new URLSearchParams(searchParams)
            next.set("date", date)
            setSearchParams(next, { replace: true })
          }}
        />
      </div>

      {search.trim() ? (
        <p role="status" className="text-xs text-muted-foreground">
          Во время поиска перенос и действия всей очереди отключены. Действия
          видимых карточек остаются доступны.
        </p>
      ) : null}
      {focusedExternalTaskId || focusedTaskId ? (
        <p
          role="status"
          className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground"
        >
          Открыто связанное логистическое задание.
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => {
              const next = new URLSearchParams(searchParams)
              next.delete("externalTaskId")
              next.delete("taskId")
              setSearchParams(next, { replace: true })
            }}
          >
            Показать все задания
          </Button>
        </p>
      ) : null}
      {!accessToken ? (
        <p role="alert" className="text-xs text-destructive">
          Не получен токен доступа к доске заданий.
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
      {notice ? (
        <p role="status" className="text-xs text-muted-foreground">
          {notice}
        </p>
      ) : null}

      {boardQuery.isLoading ? (
        <div
          className={cn(
            "flex min-h-0 flex-1 gap-3 overflow-hidden",
            isMobile && "flex-col overflow-y-auto"
          )}
        >
          {[0, 1, 2].map((index) => (
            <Skeleton
              key={index}
              className={cn("h-full w-80 shrink-0", isMobile && "h-80 w-full")}
            />
          ))}
        </div>
      ) : boardQuery.isError ? (
        <p role="alert" className="text-xs text-destructive">
          {errorMessage(boardQuery.error, "Не удалось загрузить доску задач.")}
        </p>
      ) : preview ? (
        <DndContext
          sensors={sensors}
          collisionDetection={taskBoardCollisionDetection}
          onDragStart={handleDragStart}
          onDragOver={handleDragOver}
          onDragEnd={handleDragEnd}
          onDragCancel={() => {
            dragOutcomeRef.current = "Перенос карточки отменён."
            clearDrag()
            setPreview(boardQuery.data ? cloneBoard(boardQuery.data) : null)
          }}
          accessibility={{
            screenReaderInstructions: {
              draggable:
                "Нажмите пробел, чтобы поднять карточку. Используйте стрелки для перемещения и снова пробел для сохранения.",
            },
            announcements: {
              onDragStart: ({ active }) =>
                `Поднята ${describeEntry(String(active.id))}.`,
              onDragOver: ({ active, over }) => {
                const queueLabel = describeQueue(
                  over ? String(over.id) : undefined
                )
                return queueLabel
                  ? `${describeEntry(String(active.id))} над очередью «${queueLabel}».`
                  : "Цель переноса не выбрана."
              },
              onDragEnd: () => dragOutcomeRef.current,
              onDragCancel: () => dragOutcomeRef.current,
            },
          }}
        >
          <div
            className={cn(
              "min-h-0 flex-1",
              isMobile
                ? "overflow-x-hidden overflow-y-auto overscroll-y-contain"
                : "touch-pan-x overflow-x-auto overscroll-x-contain"
            )}
          >
            <div
              className={cn(
                "flex items-stretch gap-3 p-px pb-4",
                isMobile
                  ? "min-h-full w-full min-w-0 flex-col"
                  : "h-full min-w-max"
              )}
            >
              {visibleQueues.map(({ queue, visibleEntries }) => (
                <TaskBoardColumn
                  key={queue.key}
                  queue={queue}
                  visibleEntries={visibleEntries}
                  now={now}
                  mobile={isMobile}
                  canEdit={canEdit}
                  collapsed={collapsedQueues.has(queue.key)}
                  dragDisabled={!canEdit || busy || Boolean(normalizedSearch)}
                  actionPending={actionsBusy}
                  queueActionsDisabled={!canEdit || Boolean(normalizedSearch)}
                  palette={taskBoardPalette}
                  repairComplexitiesByRepairId={repairComplexitiesByRepairId}
                  onToggleCollapsed={(queueKey) =>
                    setCollapsedQueues((current) => {
                      const next = new Set(current)
                      if (next.has(queueKey)) next.delete(queueKey)
                      else next.add(queueKey)
                      return next
                    })
                  }
                  onDetails={(entry) => {
                    if (entry.source?.type !== "MAINTENANCE_REPAIR") return
                    navigate(
                      `/repairs?repairId=${encodeURIComponent(entry.source.sourceId)}`,
                      workspaceEntryNavigationOptions
                    )
                  }}
                  onEdit={(entry) => {
                    if (
                      !canEdit ||
                      entry.source?.type !== "MAINTENANCE_REPAIR"
                    ) {
                      return
                    }
                    navigate(
                      `/repairs?repairId=${encodeURIComponent(entry.source.sourceId)}&edit=1`,
                      workspaceEntryNavigationOptions
                    )
                  }}
                  onTake={(entry) => {
                    if (!canEdit) return
                    setTakeEntry(entry)
                    setError(null)
                  }}
                  onPause={(entry) => {
                    if (canEdit) {
                      actionMutation.mutate({ kind: "pause", entry })
                    }
                  }}
                  onResume={(entry) => {
                    if (canEdit) {
                      actionMutation.mutate({ kind: "resume", entry })
                    }
                  }}
                  onPin={(entry, pinned) => {
                    if (canEdit) pinMutation.mutate({ entry, pinned })
                  }}
                  isEntryCollapsed={isEntryCollapsed}
                  onToggleEntryCollapsed={toggleEntryCollapsed}
                  onComplete={(entry) => {
                    if (!canEdit) return
                    setCompleteEntry(entry)
                    setError(null)
                  }}
                />
              ))}
            </div>
          </div>
          <DragOverlay>
            {activeEntry ? (
              <TaskBoardCardPreview
                entry={activeEntry}
                now={now}
                mobile={isMobile}
                canEdit={canEdit}
                collapsed={activeEntryCollapsed}
                palette={taskBoardPalette}
                repairComplexity={
                  activeEntry.source?.type === "MAINTENANCE_REPAIR"
                    ? repairComplexitiesByRepairId.get(
                        activeEntry.source.sourceId
                      )
                    : null
                }
              />
            ) : null}
          </DragOverlay>
        </DndContext>
      ) : null}

      <TaskBoardTakeDialog
        entry={takeEntry}
        pending={actionMutation.isPending}
        error={takeEntry ? error : null}
        onOpenChange={(open) => {
          if (!open) {
            setTakeEntry(null)
            setError(null)
          }
        }}
        onTake={({ workerGroupId, workerId }) => {
          if (!canEdit || !takeEntry) return
          actionMutation.mutate({
            kind: "take",
            entry: takeEntry,
            workerGroupId,
            workerId,
          })
        }}
      />
      <TaskBoardCompletionDialog
        entry={completeEntry}
        pending={actionMutation.isPending}
        error={completeEntry ? error : null}
        onOpenChange={(open) => {
          if (!open) {
            setCompleteEntry(null)
            setError(null)
          }
        }}
        onComplete={() => {
          if (!canEdit || !completeEntry) return
          actionMutation.mutate({ kind: "complete", entry: completeEntry })
        }}
      />
    </div>
  )
}
