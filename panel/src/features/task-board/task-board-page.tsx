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
import { Add01Icon, Search01Icon } from "@hugeicons/core-free-icons"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Link, useNavigate } from "react-router-dom"

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
import { useAuth } from "@/features/auth/use-auth"
import {
  completeTaskBoardEntry,
  getTaskBoard,
  moveTaskBoardEntry,
  pauseTaskBoardEntry,
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
import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
  TaskBoardSnapshotDto,
} from "@/features/task-board/model/task-board"
import { TaskBoardCardPreview } from "@/features/task-board/task-board-card"
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
    queueCode: nextTarget.queueCode,
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

export function TaskBoardPage() {
  const isMobile = useIsMobile()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { accessToken } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const warehouseId = selectedWarehouse?.serviceId ?? null
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
  const collapsedSettingsRef = useRef<{
    warehouseId: string
    values: Map<string, boolean>
  } | null>(null)

  const boardQuery = useQuery({
    queryKey: taskBoardQueryKey(warehouseId ?? "none"),
    queryFn: () => getTaskBoard(accessToken!, warehouseId!),
    enabled: Boolean(accessToken && warehouseId),
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
    if (!warehouseId || !boardQuery.data) return
    const previous = collapsedSettingsRef.current
    const merged = mergeQueueCollapsedSettings({
      current: collapsedQueues,
      previous: previous?.warehouseId === warehouseId ? previous.values : null,
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
    { entry: TaskBoardEntryDto; queue: TaskBoardQueueDto; targetIndex: number }
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

  const busy = actionMutation.isPending || dragMutation.isPending
  const actionsBusy = busy || activeEntryId !== null

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
              placeholder="Бытовка, задание или исполнитель"
              onChange={(event) => setSearch(event.target.value)}
            />
          </InputGroup>
        </PageToolbarContent>

        <PageToolbarActions className="w-full sm:w-auto">
          <div className="grid w-full grid-cols-2 gap-2 sm:contents">
            <Button
              asChild
              className="w-full sm:w-auto"
              disabled={!warehouseId}
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
                  onTake={(entry) => {
                    setTakeEntry(entry)
                    setError(null)
                  }}
                  onPause={(entry) =>
                    actionMutation.mutate({ kind: "pause", entry })
                  }
                  onResume={(entry) =>
                    actionMutation.mutate({ kind: "resume", entry })
                  }
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
              <TaskBoardCardPreview entry={activeEntry} now={now} />
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
          if (!takeEntry) return
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
          if (!completeEntry) return
          actionMutation.mutate({ kind: "complete", entry: completeEntry })
        }}
      />
    </div>
  )
}
