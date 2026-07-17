import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { Link, useNavigate } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
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
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
  type CollisionDetection,
} from "@dnd-kit/core"
import { sortableKeyboardCoordinates } from "@dnd-kit/sortable"
import { HugeiconsIcon } from "@hugeicons/react"
import { Add01Icon, Search01Icon } from "@hugeicons/core-free-icons"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from "@/components/ui/input-group"
import { Skeleton } from "@/components/ui/skeleton"
import { cn } from "@/lib/utils"
import { DEV_MAINTENANCE_FIXTURES_ENABLED } from "@/features/maintenance/maintenance-runtime"
import { useAuth } from "@/features/auth/use-auth"
import {
  REPAIR_TASKS_MOCK_STORAGE_KEY,
  REPAIR_TASKS_UPDATED_EVENT,
} from "@/features/repair-tasks/adapters/local-storage-repair-tasks-adapter"
import { REPAIR_TASKS_QUERY_KEY } from "@/features/repair-tasks/api/repair-tasks-api"
import type { PendingEstimateMediaUpload } from "@/features/repair-estimates/model/repair-estimate"
import type {
  RepairTaskWorkerGroupSnapshotDto,
  RepairTaskWorkerSnapshotDto,
} from "@/features/repair-tasks/model/repair-task"
import {
  TASK_BOARD_QUERY_KEY,
  completeTaskBoardEntry,
  confirmTaskBoardGroupReturned,
  evaluateTaskBoardMock,
  getTaskBoardActiveWorkerId,
  getTaskBoard,
  listTaskBoardNotifications,
  listTaskBoardWorkers,
  markAllTaskBoardNotificationsRead,
  markTaskBoardNotificationRead,
  moveTaskBoardEntry,
  pauseTaskBoardEntry,
  resumeTaskBoardEntry,
  setTaskBoardActiveWorker,
  subscribeTaskBoardMock,
  takeTaskBoardEntry,
  TASK_BOARD_NOTIFICATIONS_QUERY_KEY,
  taskBoardQueryKey,
} from "@/features/task-board/api/task-board-api"
import {
  canMoveEntryToQueue,
  mergeQueueCollapsedSettings,
  taskBoardEntryTitle,
  taskBoardQueuePositionAt,
} from "@/features/task-board/domain/task-board-domain"
import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
  TaskBoardSnapshotDto,
} from "@/features/task-board/model/task-board"
import { TaskBoardCardPreview } from "@/features/task-board/task-board-card"
import { TaskBoardColumn } from "@/features/task-board/task-board-column"
import { TaskBoardCompletionDialog } from "@/features/task-board/task-board-completion-dialog"
import { TaskBoardTakeDialog } from "@/features/task-board/task-board-take-dialog"
import { TaskBoardMockToolbar } from "@/features/task-board/task-board-mock-toolbar"
import { useIsMobile } from "@/hooks/use-mobile"
import { useWarehouse } from "@/hooks/use-warehouse"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"

type BoardAction =
  | {
      kind: "take"
      entry: TaskBoardEntryDto
      workerGroup: RepairTaskWorkerGroupSnapshotDto
      workers?: RepairTaskWorkerSnapshotDto[]
    }
  | { kind: "pause"; entry: TaskBoardEntryDto }
  | { kind: "resume"; entry: TaskBoardEntryDto }
  | {
      kind: "complete"
      entry: TaskBoardEntryDto
      pendingUploads: PendingEstimateMediaUpload[]
    }

type DropPlacement = "before" | "after" | "end"
type DropTarget = { overId: string; placement: DropPlacement }

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

  // At the untouched origin pointerWithin sees the transformed card and its
  // parent queue. Preserve the active collision so drag-end resolves to no-op.
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

function cloneBoard(board: TaskBoardSnapshotDto) {
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
  if (!entry || !canMoveEntryToQueue(entry, targetQueue)) return board

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

export function TaskBoardPage() {
  const isMobile = useIsMobile()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { accessToken } = useAuth()
  const { selectedWarehouse, selectedWarehouseId } = useWarehouse()
  const serviceWarehouseId = selectedWarehouse?.id ?? null
  const [search, setSearch] = useState("")
  const [showFuture, setShowFuture] = useState(false)
  const [collapsedQueues, setCollapsedQueues] = useState<Set<string>>(
    () => new Set()
  )
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
  const [activeWorkerId, setActiveWorkerId] = useState<string | null>(null)
  const collapsedSettingsRef = useRef<{
    warehouseId: string
    values: Map<string, boolean>
  } | null>(null)
  const seenNotificationIdsRef = useRef<Set<string> | null>(null)
  const runtimeEvaluationPendingRef = useRef(false)

  const boardQuery = useQuery({
    queryKey: taskBoardQueryKey(
      selectedWarehouseId ?? "none",
      serviceWarehouseId ?? "none"
    ),
    queryFn: () =>
      getTaskBoard(selectedWarehouseId!, serviceWarehouseId!, accessToken),
    enabled: selectedWarehouseId !== null && serviceWarehouseId !== null,
  })
  const workersQuery = useQuery({
    queryKey: ["task-board", serviceWarehouseId, "mock-workers"],
    queryFn: () => listTaskBoardWorkers(serviceWarehouseId!),
    enabled: DEV_MAINTENANCE_FIXTURES_ENABLED && serviceWarehouseId !== null,
  })
  const activeMockWorkerQuery = useQuery({
    queryKey: ["task-board", "mock-active-worker"],
    queryFn: getTaskBoardActiveWorkerId,
    enabled: DEV_MAINTENANCE_FIXTURES_ENABLED,
  })
  const activeWorker = (workersQuery.data ?? []).find(
    (worker) =>
      worker.id === (activeWorkerId ?? activeMockWorkerQuery.data ?? null)
  )
  const effectiveActiveWorkerId =
    activeWorker?.id ?? workersQuery.data?.[0]?.id ?? null
  const notificationsQuery = useQuery({
    queryKey: [...TASK_BOARD_NOTIFICATIONS_QUERY_KEY, effectiveActiveWorkerId],
    queryFn: () => listTaskBoardNotifications(effectiveActiveWorkerId),
    enabled:
      DEV_MAINTENANCE_FIXTURES_ENABLED && Boolean(effectiveActiveWorkerId),
  })

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

  useEffect(() => {
    setPreview(boardQuery.data ? cloneBoard(boardQuery.data) : null)
  }, [boardQuery.data, setPreview])

  useEffect(() => {
    if (!selectedWarehouseId || !boardQuery.data) return
    const previous = collapsedSettingsRef.current
    const merged = mergeQueueCollapsedSettings({
      current: collapsedQueues,
      previous:
        previous?.warehouseId === selectedWarehouseId ? previous.values : null,
      queues: boardQuery.data.queues,
      reset: previous?.warehouseId !== selectedWarehouseId,
    })
    collapsedSettingsRef.current = {
      warehouseId: selectedWarehouseId,
      values: merged.settings,
    }
    if (
      merged.collapsed.size !== collapsedQueues.size ||
      [...merged.collapsed].some((queueKey) => !collapsedQueues.has(queueKey))
    ) {
      setCollapsedQueues(merged.collapsed)
    }
  }, [boardQuery.data, collapsedQueues, selectedWarehouseId])

  useEffect(() => {
    const notifications = notificationsQuery.data
    if (!notifications) return
    const currentIds = new Set(notifications.map((item) => item.id))
    if (seenNotificationIdsRef.current) {
      notifications
        .filter(
          (item) =>
            !item.readAt && !seenNotificationIdsRef.current?.has(item.id)
        )
        .forEach((item) => toast(item.message))
    }
    seenNotificationIdsRef.current = currentIds
  }, [notificationsQuery.data])

  useEffect(() => {
    if (DEV_MAINTENANCE_FIXTURES_ENABLED) return
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    if (!DEV_MAINTENANCE_FIXTURES_ENABLED || !serviceWarehouseId) return
    const reconcile = async () => {
      if (runtimeEvaluationPendingRef.current) return
      runtimeEvaluationPendingRef.current = true
      try {
        await evaluateTaskBoardMock()
        await queryClient.invalidateQueries({ queryKey: TASK_BOARD_QUERY_KEY })
        await queryClient.invalidateQueries({
          queryKey: TASK_BOARD_NOTIFICATIONS_QUERY_KEY,
        })
      } catch (unknownError) {
        setError(
          unknownError instanceof Error
            ? unknownError.message
            : "Не удалось обновить MOCK-время доски"
        )
      } finally {
        runtimeEvaluationPendingRef.current = false
      }
    }
    const timer = window.setInterval(() => void reconcile(), 1_000)
    return () => window.clearInterval(timer)
  }, [queryClient, serviceWarehouseId])

  useEffect(() => {
    if (!DEV_MAINTENANCE_FIXTURES_ENABLED) return
    const invalidate = () => {
      void queryClient.invalidateQueries({ queryKey: TASK_BOARD_QUERY_KEY })
    }
    const handleStorage = (event: StorageEvent) => {
      if (event.key === REPAIR_TASKS_MOCK_STORAGE_KEY) invalidate()
    }
    window.addEventListener(REPAIR_TASKS_UPDATED_EVENT, invalidate)
    window.addEventListener("storage", handleStorage)
    return () => {
      window.removeEventListener(REPAIR_TASKS_UPDATED_EVENT, invalidate)
      window.removeEventListener("storage", handleStorage)
    }
  }, [queryClient])

  useEffect(() => {
    return subscribeTaskBoardMock(() => {
      void queryClient.invalidateQueries({ queryKey: TASK_BOARD_QUERY_KEY })
      void queryClient.invalidateQueries({
        queryKey: TASK_BOARD_NOTIFICATIONS_QUERY_KEY,
      })
      void queryClient.invalidateQueries({
        queryKey: ["task-board", serviceWarehouseId, "mock-workers"],
      })
      void queryClient.invalidateQueries({
        queryKey: ["task-board", "mock-active-worker"],
      })
    })
  }, [queryClient, serviceWarehouseId])

  const invalidateTaskData = useCallback(
    () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: TASK_BOARD_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: REPAIR_TASKS_QUERY_KEY }),
      ]),
    [queryClient]
  )

  const actionMutation = useMutation({
    mutationFn: async (action: BoardAction) => {
      if (action.kind === "take") {
        return takeTaskBoardEntry({
          entry: action.entry,
          workerGroup: action.workerGroup,
          workers: action.workers,
          accessToken: accessToken ?? undefined,
        })
      }
      if (action.kind === "pause") {
        return pauseTaskBoardEntry(action.entry, accessToken)
      }
      if (action.kind === "resume") {
        return resumeTaskBoardEntry(action.entry, accessToken)
      }
      return completeTaskBoardEntry(
        action.entry,
        action.pendingUploads,
        accessToken
      )
    },
    onMutate: () => setNotice(null),
    onSuccess: async () => {
      setError(null)
      setTakeEntry(null)
      setCompleteEntry(null)
      await invalidateTaskData()
    },
    onError: async (unknownError) => {
      setNotice(null)
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Не удалось изменить подзадание"
      )
      await invalidateTaskData()
    },
  })

  const dragMutation = useMutation<
    unknown,
    Error,
    {
      entry: TaskBoardEntryDto
      queue: TaskBoardQueueDto
      queuePosition: number
    }
  >({
    mutationFn: (params) =>
      moveTaskBoardEntry({
        task: params.entry.task,
        subtaskId: params.entry.subtask.id,
        queue: params.queue,
        queuePosition: params.queuePosition,
        entry: params.entry,
        accessToken,
      }),
    onMutate: () => setNotice(null),
    onSuccess: async () => {
      setError(null)
      setNotice("Положение карточки сохранено.")
      await invalidateTaskData()
    },
    onError: async (unknownError) => {
      setPreview(boardQuery.data ? cloneBoard(boardQuery.data) : null)
      setNotice(null)
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Не удалось сохранить положение карточки"
      )
      await invalidateTaskData()
    },
  })

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(TouchSensor, {
      activationConstraint: { delay: 220, tolerance: 6 },
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
          if (!normalizedSearch) return true
          const haystack = [
            entry.task.cabinNumber,
            entry.task.reason,
            entry.task.authorName,
            entry.task.comment,
            entry.subtask.assigneeName ?? "",
            entry.subtask.workerGroup?.name ?? "",
            ...entry.subtask.assignments.map(
              (assignment) => assignment.worker?.name ?? ""
            ),
            taskBoardEntryTitle(entry.subtask),
          ]
            .join(" ")
            .toLocaleLowerCase("ru")
          return haystack.includes(normalizedSearch)
        }),
      })),
    [normalizedSearch, preview, showFuture]
  )
  const activeEntry =
    activeEntryId && preview
      ? (preview.queues
          .flatMap((queue) => queue.entries)
          .find((entry) => entry.id === activeEntryId) ?? null)
      : null

  function handleDragStart(event: DragStartEvent) {
    if (!previewRef.current) return
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
      setActiveEntryId(null)
      dragBaselineRef.current = null
      lastDropTargetRef.current = null
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
      setActiveEntryId(null)
      dragBaselineRef.current = null
      lastDropTargetRef.current = null
      return
    }
    const requestedQueue = targetQueueForOver(baseline, finalTarget.overId)
    const baselineEntry = queueContaining(baseline, activeId)?.entries.find(
      (candidate) => candidate.id === activeId
    )
    if (
      !requestedQueue ||
      !baselineEntry ||
      !canMoveEntryToQueue(baselineEntry, requestedQueue)
    ) {
      dragOutcomeRef.current = requestedQueue
        ? `${draggedLabel} нельзя поместить в очередь «${requestedQueue.label}».`
        : "Цель переноса недоступна."
      setPreview(baseline)
      setActiveEntryId(null)
      dragBaselineRef.current = null
      lastDropTargetRef.current = null
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
      setActiveEntryId(null)
      dragBaselineRef.current = null
      lastDropTargetRef.current = null
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
      setActiveEntryId(null)
      dragBaselineRef.current = null
      lastDropTargetRef.current = null
      return
    }
    dragOutcomeRef.current = `${draggedLabel} отпущена в очереди «${targetQueue.label}». Положение сохраняется.`
    setActiveEntryId(null)
    dragBaselineRef.current = null
    lastDropTargetRef.current = null
    dragMutation.mutate({
      entry,
      queue: targetQueue,
      queuePosition: taskBoardQueuePositionAt(targetQueue.entries, activeId),
    })
  }

  function openTakeDialog(entry: TaskBoardEntryDto) {
    setTakeEntry(entry)
    setError(null)
  }

  function describeEntry(entryId: string) {
    const board = dragBaselineRef.current ?? previewRef.current
    const entry = board?.queues
      .flatMap((queue) => queue.entries)
      .find((candidate) => candidate.id === entryId)
    return entry ? `карточка бытовки ${entry.task.cabinNumber}` : "карточка"
  }

  function describeQueue(overId: string | undefined) {
    if (!overId) return null
    const board = dragBaselineRef.current ?? previewRef.current
    return board ? (targetQueueForOver(board, overId)?.label ?? null) : null
  }

  const busy = actionMutation.isPending || dragMutation.isPending
  const actionsBusy = busy || activeEntryId !== null
  const boardNow = preview?.now ? Date.parse(preview.now) : now
  const effectiveNow = Number.isFinite(boardNow) ? boardNow : now

  const returnMutation = useMutation({
    mutationFn: confirmTaskBoardGroupReturned,
    onSuccess: async () => {
      toast.success("Возвращение бригады подтверждено")
      await invalidateTaskData()
    },
    onError: (unknownError) =>
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Не удалось подтвердить возвращение бригады"
      ),
  })

  const notificationMutation = useMutation({
    mutationFn: markTaskBoardNotificationRead,
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: TASK_BOARD_NOTIFICATIONS_QUERY_KEY,
      }),
  })

  const allNotificationsMutation = useMutation({
    mutationFn: markAllTaskBoardNotificationsRead,
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: TASK_BOARD_NOTIFICATIONS_QUERY_KEY,
      }),
  })

  return (
    <div className="flex h-full min-h-0 flex-col gap-3 overflow-hidden">
      <PageToolbar>
        <PageToolbarContent className="min-w-52 sm:max-w-sm">
          <InputGroup>
            <InputGroupAddon>
              <HugeiconsIcon icon={Search01Icon} />
            </InputGroupAddon>
            <InputGroupInput
              value={search}
              aria-label="Поиск по доске задач"
              name="task-board-search"
              autoComplete="off"
              placeholder="Бытовка, причина или исполнитель"
              onChange={(event) => setSearch(event.target.value)}
            />
          </InputGroup>
        </PageToolbarContent>

        <PageToolbarActions className="w-full sm:w-auto">
          {DEV_MAINTENANCE_FIXTURES_ENABLED ? (
            <TaskBoardMockToolbar
              workers={workersQuery.data ?? []}
              activeWorkerId={effectiveActiveWorkerId}
              notifications={notificationsQuery.data ?? []}
              onWorkerChange={(workerId) => {
                setActiveWorkerId(workerId)
                seenNotificationIdsRef.current = null
                void setTaskBoardActiveWorker(workerId)
                void queryClient.invalidateQueries({
                  queryKey: ["task-board", "mock-active-worker"],
                })
              }}
              onMarkRead={(notification) =>
                notificationMutation.mutate(notification)
              }
              onMarkAllRead={() => {
                if (effectiveActiveWorkerId) {
                  allNotificationsMutation.mutate({
                    workerId: effectiveActiveWorkerId,
                    notifications: notificationsQuery.data ?? [],
                  })
                }
              }}
            />
          ) : null}
          <div className="grid w-full grid-cols-2 gap-2 sm:contents">
            <Button
              asChild
              className="w-full sm:w-auto"
              disabled={!selectedWarehouseId}
            >
              <Link
                to="/repairs?create=1"
                state={workspaceEntryNavigationOptions.state}
              >
                <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                Создать задание
              </Link>
            </Button>
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
          </div>
          <div className="flex basis-full items-center gap-1 sm:ml-auto sm:w-auto sm:basis-auto">
            <Badge variant="secondary" className="h-9 px-3">
              Текущих: {preview?.realEntries ?? 0}
            </Badge>
            <Badge variant="outline" className="h-9 px-3">
              Будущих: {preview?.shadowEntries ?? 0}
            </Badge>
          </div>
        </PageToolbarActions>
      </PageToolbar>

      {search.trim() ? (
        <p role="status" className="text-xs text-muted-foreground">
          Во время поиска перенос и действия всей очереди отключены. Действия
          видимых карточек остаются доступны.
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
          Не удалось загрузить доску задач.
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
            setActiveEntryId(null)
            dragBaselineRef.current = null
            lastDropTargetRef.current = null
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
                  now={effectiveNow}
                  mobile={isMobile}
                  collapsed={collapsedQueues.has(queue.key)}
                  dragDisabled={busy || Boolean(normalizedSearch)}
                  actionPending={actionsBusy}
                  queueActionsDisabled={Boolean(normalizedSearch)}
                  onToggleCollapsed={(queueKey) =>
                    setCollapsedQueues((current) => {
                      const next = new Set(current)
                      if (next.has(queueKey)) next.delete(queueKey)
                      else next.add(queueKey)
                      return next
                    })
                  }
                  onDetails={(entry) =>
                    entry.detailsHref &&
                    navigate(entry.detailsHref, workspaceEntryNavigationOptions)
                  }
                  onTake={openTakeDialog}
                  onPause={(entry) =>
                    actionMutation.mutate({ kind: "pause", entry })
                  }
                  onResume={(entry) =>
                    actionMutation.mutate({ kind: "resume", entry })
                  }
                  onConfirmReturn={(entry) => returnMutation.mutate(entry)}
                  onComplete={(entry) => {
                    setCompleteEntry(entry)
                    setError(null)
                  }}
                />
              ))}
            </div>
          </div>
          <DragOverlay>
            {activeEntry ? (
              <TaskBoardCardPreview entry={activeEntry} now={effectiveNow} />
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
        onTake={({ workerGroup, workers }) => {
          if (!takeEntry) return
          actionMutation.mutate({
            kind: "take",
            entry: takeEntry,
            workerGroup,
            workers,
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
        onComplete={(pendingUploads) => {
          if (!completeEntry) return
          actionMutation.mutate({
            kind: "complete",
            entry: completeEntry,
            pendingUploads,
          })
        }}
      />
    </div>
  )
}
